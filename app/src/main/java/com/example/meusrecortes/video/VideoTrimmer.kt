package com.example.meusrecortes.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object VideoTrimmer {
    private const val TAG = "VideoTrimmer"

    /**
     * Define um segmento de vídeo a ser cortado e copiado.
     * @param file O arquivo de vídeo de origem.
     * @param startTimeMs Tempo de início em milissegundos.
     * @param endTimeMs Tempo de fim em milissegundos.
     */
    data class ClipSegment(
        val file: File,
        val startTimeMs: Long,
        val endTimeMs: Long
    )

    /**
     * Corta e concatena múltiplos segmentos de vídeo em um único arquivo de saída.
     * Realiza a operação sem re-decodificar os frames, copiando as amostras diretamente.
     *
     * @param segments Lista de segmentos a serem unidos ordenadamente.
     * @param outputFile Arquivo MP4 de destino final.
     * @return true se a operação for concluída com sucesso, false caso contrário.
     */
    fun trimAndConcat(segments: List<ClipSegment>, outputFile: File): Boolean {
        if (segments.isEmpty()) return false

        var muxer: MediaMuxer? = null
        val extractors = ArrayList<MediaExtractor>()
        
        try {
            // 1. Inicializar os extractors para analisar o formato das trilhas
            val validSegments = segments.filter { it.file.exists() && it.file.length() > 0 }
            if (validSegments.isEmpty()) {
                Log.e(TAG, "Nenhum arquivo de segmento válido fornecido.")
                return false
            }

            // Precisamos descobrir quais trilhas estão presentes no primeiro segmento válido
            // para configurar as trilhas do Muxer.
            val firstExtractor = MediaExtractor()
            firstExtractor.setDataSource(validSegments[0].file.absolutePath)
            
            var videoInputTrackIndex = -1
            var audioInputTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoInputTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/")) {
                    audioInputTrackIndex = i
                    audioFormat = format
                }
            }

            firstExtractor.release()

            if (videoFormat == null) {
                Log.e(TAG, "Não foi possível encontrar uma trilha de vídeo no primeiro arquivo.")
                return false
            }

            // 2. Configurar o MediaMuxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val videoOutputTrackIndex = muxer.addTrack(videoFormat)
            val audioOutputTrackIndex = if (audioFormat != null) {
                muxer.addTrack(audioFormat)
            } else {
                -1
            }

            muxer.start()

            // Um buffer reutilizável para ler as amostras
            val bufferSize = 1024 * 1024 // 1 MB
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var globalTimeOffsetUs = 0L

            // 3. Processar cada segmento sequencialmente
            for (segment in validSegments) {
                val segmentExtractor = MediaExtractor()
                segmentExtractor.setDataSource(segment.file.absolutePath)
                extractors.add(segmentExtractor)

                var segVideoTrackIndex = -1
                var segAudioTrackIndex = -1

                for (i in 0 until segmentExtractor.trackCount) {
                    val format = segmentExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        segVideoTrackIndex = i
                    } else if (mime.startsWith("audio/")) {
                        segAudioTrackIndex = i
                    }
                }

                if (segVideoTrackIndex == -1) {
                    Log.w(TAG, "Segmento ${segment.file.name} não tem trilha de vídeo, pulando.")
                    continue
                }

                // Processar a trilha de vídeo para este segmento
                val videoDurationUs = copyTrackData(
                    extractor = segmentExtractor,
                    inputTrackIndex = segVideoTrackIndex,
                    outputTrackIndex = videoOutputTrackIndex,
                    muxer = muxer,
                    startTimeUs = segment.startTimeMs * 1000,
                    endTimeUs = segment.endTimeMs * 1000,
                    timeOffsetUs = globalTimeOffsetUs,
                    buffer = buffer,
                    bufferInfo = bufferInfo
                )

                // Processar a trilha de áudio se ambos arquivo e segmento tiverem áudio
                if (audioOutputTrackIndex != -1 && segAudioTrackIndex != -1) {
                    copyTrackData(
                        extractor = segmentExtractor,
                        inputTrackIndex = segAudioTrackIndex,
                        outputTrackIndex = audioOutputTrackIndex,
                        muxer = muxer,
                        startTimeUs = segment.startTimeMs * 1000,
                        endTimeUs = segment.endTimeMs * 1000,
                        timeOffsetUs = globalTimeOffsetUs,
                        buffer = buffer,
                        bufferInfo = bufferInfo
                    )
                }

                // Incrementar o offset global para o próximo segmento
                globalTimeOffsetUs += videoDurationUs
            }

            Log.d(TAG, "Vídeo recortado e salvo com sucesso em ${outputFile.name}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao cortar/concatenar vídeos: ", e)
            return false
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                // Silenciar erros de parada de muxer caso tenha falhado antes
            }
            for (extractor in extractors) {
                try {
                    extractor.release()
                } catch (e: Exception) {
                    // Silenciar
                }
            }
        }
    }

    /**
     * Copia os dados de uma trilha específica dentro do intervalo de tempo solicitado para o Muxer.
     * Retorna a duração real do trecho copiado em microssegundos.
     */
    private fun copyTrackData(
        extractor: MediaExtractor,
        inputTrackIndex: Int,
        outputTrackIndex: Int,
        muxer: MediaMuxer,
        startTimeUs: Long,
        endTimeUs: Long,
        timeOffsetUs: Long,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ): Long {
        extractor.selectTrack(inputTrackIndex)
        // Ir para o ponto de início desejado
        extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        var firstSampleTimeUs = -1L
        var lastSampleTimeUs = 0L
        var actualDurationUs = 0L

        while (true) {
            val trackIndex = extractor.sampleTrackIndex
            if (trackIndex != inputTrackIndex) {
                if (trackIndex == -1) break // Fim do arquivo
                // Se for outra trilha, avançamos para achar a nossa
                extractor.advance()
                continue
            }

            val sampleTimeUs = extractor.sampleTime
            // Se passou do tempo final desejado, interrompe a extração desta trilha
            if (sampleTimeUs > endTimeUs) {
                break
            }

            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break // Fim dos dados
            }

            if (firstSampleTimeUs == -1L) {
                firstSampleTimeUs = sampleTimeUs
            }

            // Calcular o timestamp relativo ao início do corte
            val relativeTimeUs = sampleTimeUs - firstSampleTimeUs
            
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = timeOffsetUs + relativeTimeUs
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
            
            lastSampleTimeUs = relativeTimeUs
            extractor.advance()
        }

        // Limpar a seleção da trilha para permitir reutilização do extractor
        extractor.unselectTrack(inputTrackIndex)
        
        actualDurationUs = lastSampleTimeUs
        return if (actualDurationUs > 0) actualDurationUs else (endTimeUs - startTimeUs)
    }
}
