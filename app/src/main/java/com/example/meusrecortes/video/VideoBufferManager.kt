package com.example.meusrecortes.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.meusrecortes.data.ReplayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoBufferManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VideoBufferManager"
        private const val ROTATION_INTERVAL_MS = 45000L // Rotacionar arquivos a cada 45 segundos

        @Volatile
        private var INSTANCE: VideoBufferManager? = null

        fun getInstance(context: Context): VideoBufferManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VideoBufferManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    sealed interface RecordingState {
        object Idle : RecordingState
        object Initializing : RecordingState
        object Monitoring : RecordingState
        data class Clipping(val secondsRemaining: Int) : RecordingState
        data class Saving(val progress: String) : RecordingState
    }

    private val repository = ReplayRepository.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Estado exposto para a UI
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    // Eventos (como mensagens de sucesso/erro)
    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events

    // Instâncias do CameraX
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // Gerenciamento de Arquivos Circulares
    private val tempDir = File(context.cacheDir, "buffer_videos").apply { mkdirs() }
    private val tempFile1 = File(tempDir, "temp_video_1.mp4")
    private val tempFile2 = File(tempDir, "temp_video_2.mp4")
    
    private var isFile1Active = true
    private var currentRecordingStartTime = 0L
    private var previousFileDurationMs = 0L
    private var previousFile: File? = null

    private var rotationJob: Job? = null
    private var countdownJob: Job? = null

    // Configurações ativas
    private var antesSegundos = 5
    private var depoisSegundos = 5

    init {
        // Atualizar tempos locais ao iniciar
        antesSegundos = repository.getAntesSegundos()
        depoisSegundos = repository.getDepoisSegundos()
    }

    fun updateConfig(antes: Int, depois: Int) {
        antesSegundos = antes
        depoisSegundos = depois
        repository.saveConfig(antes, depois)
    }

    /**
     * Inicializa o CameraX e vincula os casos de uso de Preview e VideoCapture.
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onCameraReady: () -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Configurar o Recorder
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD)) // HD (720p) é balanceado para velocidade e armazenamento
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                // Configurar o Preview
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(surfaceProvider)

                // Configurar o Streaming MJPEG para Controle Remoto
                val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                var frameCounter = 0
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    try {
                        frameCounter++
                        if (frameCounter % 3 == 0) {
                            val jpegBytes = convertYuvToJpeg(imageProxy)
                            if (jpegBytes != null) {
                                mjpegServer.pushFrame(jpegBytes)
                            }
                        }
                    } catch (_: Exception) {}
                    finally {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Desvincular qualquer caso de uso anterior
                cameraProvider?.unbindAll()

                // Vincular ao ciclo de vida
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture,
                    imageAnalysis
                )

                onCameraReady()
                Log.d(TAG, "Câmera vinculada com sucesso (com Streaming MJPEG).")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao vincular câmera: ", e)
                scope.launch { _events.emit("Erro ao inicializar a câmera: ${e.message}") }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    val mjpegServer = MjpegServer()

    /**
     * Inicia o monitoramento (gravação circular em buffer).
     */
    fun startMonitoring() {
        if (videoCapture == null) {
            scope.launch { _events.emit("A câmera não está inicializada.") }
            return
        }

        if (_state.value != RecordingState.Idle) return

        mjpegServer.start()

        _state.value = RecordingState.Initializing
        isFile1Active = true
        previousFile = null
        previousFileDurationMs = 0L

        // Limpar arquivos antigos se existirem
        if (tempFile1.exists()) tempFile1.delete()
        if (tempFile2.exists()) tempFile2.delete()

        startRecordingOnTempFile()
    }

    /**
     * Para completamente o monitoramento e apaga arquivos temporários.
     */
    fun stopMonitoring() {
        mjpegServer.stop()
        rotationJob?.cancel()
        countdownJob?.cancel()
        
        activeRecording?.stop()
        activeRecording = null
        
        if (tempFile1.exists()) tempFile1.delete()
        if (tempFile2.exists()) tempFile2.delete()
        previousFile = null
        
        _state.value = RecordingState.Idle
        Log.d(TAG, "Monitoramento parado.")
    }

    /**
     * Dispara o recorte do replay (X segundos antes e Y segundos depois do clique).
     */
    fun triggerClip() {
        val currentState = _state.value
        if (currentState != RecordingState.Monitoring) {
            Log.w(TAG, "Não é possível recortar. Estado atual: $currentState")
            return
        }

        val cliqueTime = System.currentTimeMillis()
        val cliqueOffsetMs = cliqueTime - currentRecordingStartTime

        // Parar o job de rotação para manter o arquivo atual gravando o tempo posterior
        rotationJob?.cancel()

        scope.launch {
            // Entrar em contagem regressiva
            countdownJob = launch {
                for (sec in depoisSegundos downTo 1) {
                    _state.value = RecordingState.Clipping(sec)
                    delay(1000)
                }
            }
            
            countdownJob?.join()

            // Após passar os segundos posteriores, parar gravação atual e realizar o processamento
            _state.value = RecordingState.Saving("Processando vídeo...")
            activeRecording?.stop()
            activeRecording = null

            // Aguarda um pequeno instante para o fechamento do arquivo MP4 pelo CameraX
            delay(800)

            val currentFile = if (isFile1Active) tempFile1 else tempFile2
            val finalAntesSegundos = antesSegundos
            val finalDepoisSegundos = depoisSegundos

            withContext(Dispatchers.IO) {
                processAndSaveClip(currentFile, cliqueOffsetMs, finalAntesSegundos, finalDepoisSegundos)
            }
        }
    }

    /**
     * Inicia a gravação em um dos arquivos temporários.
     */
    private fun startRecordingOnTempFile() {
        val targetFile = if (isFile1Active) tempFile1 else tempFile2
        
        val fileOutputOptions = FileOutputOptions.Builder(targetFile).build()

        try {
            activeRecording = videoCapture?.output
                ?.prepareRecording(context, fileOutputOptions)
                ?.withAudioEnabled() // Gravar áudio também
                ?.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            currentRecordingStartTime = System.currentTimeMillis()
                            if (_state.value == RecordingState.Initializing) {
                                _state.value = RecordingState.Monitoring
                            }
                            Log.d(TAG, "Gravação iniciada no arquivo: ${targetFile.name}")
                            
                            // Agendar a próxima rotação se estivermos monitorando normalmente
                            if (_state.value == RecordingState.Monitoring) {
                                scheduleRotation()
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (recordEvent.hasError()) {
                                Log.e(TAG, "Erro ao gravar vídeo temporário: ${recordEvent.error}")
                            } else {
                                Log.d(TAG, "Gravação finalizada no arquivo: ${targetFile.name}")
                            }
                        }
                    }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissão de microfone/áudio ausente: ", e)
            _state.value = RecordingState.Idle
            scope.launch { _events.emit("Erro de permissão de gravação.") }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar gravação temporária: ", e)
            _state.value = RecordingState.Idle
            scope.launch { _events.emit("Erro ao iniciar gravação do buffer.") }
        }
    }

    private fun scheduleRotation() {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            delay(ROTATION_INTERVAL_MS)
            
            // Antes de alternar, guardar a duração real do arquivo atual
            val currentFile = if (isFile1Active) tempFile1 else tempFile2
            previousFileDurationMs = System.currentTimeMillis() - currentRecordingStartTime
            previousFile = currentFile

            // 1. Parar a gravação no arquivo antigo primeiro para liberar o gravador CameraX
            val oldRecording = activeRecording
            activeRecording = null
            oldRecording?.stop()

            // 2. Aguardar o fechamento completo do arquivo antigo pelo CameraX
            delay(400)
            
            // 3. Alternar o arquivo ativo e iniciar no novo arquivo temporário
            isFile1Active = !isFile1Active
            startRecordingOnTempFile()
        }
    }

    /**
     * Processa os arquivos temporários e salva o clipe final cortado na galeria do MediaStore.
     */
    private suspend fun processAndSaveClip(
        currentFile: File,
        cliqueOffsetMs: Long,
        antesSec: Int,
        depoisSec: Int
    ) {
        val antesMs = antesSec * 1000L
        val depoisMs = depoisSec * 1000L
        val totalClipDurationMs = antesMs + depoisMs

        val segments = ArrayList<VideoTrimmer.ClipSegment>()

        // 1. Determinar onde buscar o vídeo anterior ao clique
        if (cliqueOffsetMs >= antesMs) {
            // Caso simples: O vídeo anterior cabe todo dentro do arquivo atual
            val startClipMs = cliqueOffsetMs - antesMs
            val endClipMs = cliqueOffsetMs + depoisMs
            
            segments.add(VideoTrimmer.ClipSegment(currentFile, startClipMs, endClipMs))
            Log.d(TAG, "Recorte cabe totalmente no arquivo atual: start=$startClipMs, end=$endClipMs")
        } else {
            // Caso composto: Precisamos de frames do arquivo anterior
            val faltaMs = antesMs - cliqueOffsetMs
            val prevFile = previousFile

            if (prevFile != null && prevFile.exists()) {
                // Obter a duração do arquivo anterior
                val prevDuration = getFileDurationMs(prevFile)
                val prevStartMs = maxOf(0L, prevDuration - faltaMs)
                
                segments.add(VideoTrimmer.ClipSegment(prevFile, prevStartMs, prevDuration))
                Log.d(TAG, "Recorte composto - Parte 1 (Anterior): start=$prevStartMs, end=$prevDuration")
            }

            // Adicionar a parte do arquivo atual
            val currentEndMs = cliqueOffsetMs + depoisMs
            segments.add(VideoTrimmer.ClipSegment(currentFile, 0L, currentEndMs))
            Log.d(TAG, "Recorte composto - Parte 2 (Atual): start=0, end=$currentEndMs")
        }

        // 2. Definir arquivo final na Galeria pública
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "REPLAY_$timeStamp.mp4"
        
        // Criar um arquivo local temporário para fazer o muxing antes de salvar na galeria
        val outputTempFile = File(context.cacheDir, "temp_output.mp4")
        if (outputTempFile.exists()) outputTempFile.delete()

        // 3. Fazer o corte
        val success = VideoTrimmer.trimAndConcat(segments, outputTempFile)

        if (success && outputTempFile.exists() && outputTempFile.length() > 0) {
            // 4. Copiar para a Galeria usando MediaStore
            val savedUri = saveVideoToGallery(outputTempFile, fileName)
            if (savedUri != null) {
                Log.d(TAG, "Replay salvo com sucesso: $savedUri")
                repository.notifyNewClipSaved()
                _events.emit("Replay salvo com sucesso!")
            } else {
                Log.e(TAG, "Erro ao mover arquivo para o MediaStore.")
                _events.emit("Erro ao salvar replay na galeria.")
            }
        } else {
            Log.e(TAG, "Falha na junção e corte do vídeo.")
            _events.emit("Falha ao recortar o vídeo.")
        }

        // Limpar temporários
        if (outputTempFile.exists()) outputTempFile.delete()
        if (tempFile1.exists()) tempFile1.delete()
        if (tempFile2.exists()) tempFile2.delete()
        previousFile = null

        // 5. Retornar ao estado de monitoramento reiniciando o loop
        _state.value = RecordingState.Initializing
        isFile1Active = true
        startRecordingOnTempFile()
    }

    private fun getFileDurationMs(file: File): Long {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever().apply {
                setDataSource(file.absolutePath)
            }
            val timeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            timeStr?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível obter duração do arquivo ${file.name}, usando padrão estimativo.")
            previousFileDurationMs
        } finally {
            retriever?.release()
        }
    }

    private fun saveVideoToGallery(videoFile: File, displayName: String): String? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            // Diretório "Movies/Meus Recortes" na Galeria pública
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Meus Recortes")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val videoUri = resolver.insert(collection, contentValues) ?: return null

        try {
            resolver.openOutputStream(videoUri).use { outputStream ->
                if (outputStream == null) return null
                videoFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(videoUri, contentValues, null, null)
            }
            return videoUri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar vídeo na galeria pública: ", e)
            resolver.delete(videoUri, null, null)
            return null
        }
    }

    private fun convertYuvToJpeg(image: androidx.camera.core.ImageProxy): ByteArray? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 35, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
