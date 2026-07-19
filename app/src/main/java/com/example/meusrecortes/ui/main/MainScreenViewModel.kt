package com.example.meusrecortes.ui.main

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meusrecortes.data.ClipInfo
import com.example.meusrecortes.data.ReplayRepository
import com.example.meusrecortes.video.VideoBufferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = ReplayRepository.getInstance(appContext)
    private val bufferManager = VideoBufferManager.getInstance(appContext)

    // Estados de Configuração dos Tempos
    private val _antesSegundos = MutableStateFlow(5)
    val antesSegundos: StateFlow<Int> = _antesSegundos.asStateFlow()

    private val _depoisSegundos = MutableStateFlow(5)
    val depoisSegundos: StateFlow<Int> = _depoisSegundos.asStateFlow()

    // Lista de Clipes Salvos
    val savedClips: StateFlow<List<ClipInfo>> = repository.savedClipsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Estado da Gravação do Buffer
    val recordingState = bufferManager.state

    // Fluxo de Eventos Rápidos (Mensagens Toast/Snackbar)
    val events = bufferManager.events

    // Vídeo selecionado para reprodução na galeria
    private val _activeVideoUri = MutableStateFlow<Uri?>(null)
    val activeVideoUri: StateFlow<Uri?> = _activeVideoUri.asStateFlow()

    // --- ESTADOS DO SISTEMA DE ATIVAÇÃO (SUPABASE) ---
    private val _isActivated = MutableStateFlow(false)
    val isActivated: StateFlow<Boolean> = _isActivated.asStateFlow()

    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _isActivating = MutableStateFlow(false)
    val isActivating: StateFlow<Boolean> = _isActivating.asStateFlow()

    private val _activationError = MutableStateFlow<String?>(null)
    val activationError: StateFlow<String?> = _activationError.asStateFlow()

    init {
        // Carregar configurações salvas
        _antesSegundos.value = repository.getAntesSegundos()
        _depoisSegundos.value = repository.getDepoisSegundos()
        
        // Carregar Device ID e estado de ativação local inicial
        _deviceId.value = repository.getDeviceId()
        _isActivated.value = repository.isActivatedLocal()

        // 1. Verificar licença remotamente em segundo plano na inicialização
        viewModelScope.launch(Dispatchers.IO) {
            repository.checkLicenseStatusOnStartup().collectLatest { active ->
                withContext(Dispatchers.Main) {
                    _isActivated.value = active
                    if (!active) {
                        bufferManager.stopMonitoring()
                    }
                }
            }
        }

        // 2. Loop de escuta de comandos remotos de corte enviados por outro celular/web
        viewModelScope.launch(Dispatchers.IO) {
            var lastProcessedId = ""
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (recordingState.value !is VideoBufferManager.RecordingState.Idle) {
                    val key = repository.getLocalLicenseKey()
                    val newCmdId = repository.checkRemoteClipCommandId(key, lastProcessedId)
                    if (newCmdId != null) {
                        if (lastProcessedId.isEmpty()) {
                            lastProcessedId = newCmdId
                        } else {
                            lastProcessedId = newCmdId
                            withContext(Dispatchers.Main) {
                                bufferManager.triggerClip()
                                Log.d("MainScreenViewModel", "Recorte acionado remotamente pelo controle!")
                            }
                        }
                    }
                }
            }
        }
    }

    fun setAntesSegundos(value: Int) {
        _antesSegundos.value = value
        bufferManager.updateConfig(value, _depoisSegundos.value)
    }

    fun setDepoisSegundos(value: Int) {
        _depoisSegundos.value = value
        bufferManager.updateConfig(_antesSegundos.value, value)
    }

    fun toggleMonitoring() {
        val state = recordingState.value
        if (state == VideoBufferManager.RecordingState.Idle) {
            // Verificar licença remotamente antes de permitir iniciar a gravação
            viewModelScope.launch(Dispatchers.IO) {
                repository.checkLicenseStatusOnStartup().collectLatest { active ->
                    withContext(Dispatchers.Main) {
                        _isActivated.value = active
                        if (!active) {
                            bufferManager.stopMonitoring()
                            Log.w("MainScreenViewModel", "Licença revogada ou desativada no Supabase.")
                        } else {
                            bufferManager.startMonitoring()
                        }
                    }
                }
            }
        } else {
            bufferManager.stopMonitoring()
        }
    }

    fun triggerClip() {
        bufferManager.triggerClip()
    }

    fun playVideo(uri: Uri?) {
        _activeVideoUri.value = uri
    }

    fun deleteClip(clip: ClipInfo) {
        viewModelScope.launch {
            val success = repository.deleteClip(clip)
            if (success) {
                if (_activeVideoUri.value == clip.uri) {
                    _activeVideoUri.value = null
                }
                Log.d("MainScreenViewModel", "Clipe deletado com sucesso.")
            }
        }
    }

    /**
     * Tenta ativar o aplicativo com a licença fornecida no Supabase.
     */
    fun activateApp(licenseKey: String, onSuccess: () -> Unit) {
        if (licenseKey.trim().isEmpty()) {
            _activationError.value = "Insira uma chave de licença."
            return
        }

        viewModelScope.launch {
            _isActivating.value = true
            _activationError.value = null

            val result = repository.validateLicenseOnSupabase(licenseKey)
            
            _isActivating.value = false
            if (result.isSuccess) {
                _isActivated.value = true
                onSuccess()
            } else {
                _activationError.value = result.exceptionOrNull()?.message ?: "Erro desconhecido ao ativar."
            }
        }
    }

    fun getBufferManager(): VideoBufferManager = bufferManager

    override fun onCleared() {
        super.onCleared()
        bufferManager.stopMonitoring()
    }
}
