package com.example.meusrecortes.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meusrecortes.data.ClipInfo
import com.example.meusrecortes.video.VideoBufferManager
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (androidx.navigation3.runtime.NavKey) -> Unit, // Requerido pelo template original
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Estados observados do ViewModel
    val isActivated by viewModel.isActivated.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val isActivating by viewModel.isActivating.collectAsState()
    val activationError by viewModel.activationError.collectAsState()

    val recordingState by viewModel.recordingState.collectAsState()
    val savedClips by viewModel.savedClips.collectAsState()
    val antesSegundos by viewModel.antesSegundos.collectAsState()
    val depoisSegundos by viewModel.depoisSegundos.collectAsState()
    val activeVideoUri by viewModel.activeVideoUri.collectAsState()

    var showGallery by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Gerenciar requisição de permissões
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val cam = perms[Manifest.permission.CAMERA] ?: false
        val aud = perms[Manifest.permission.RECORD_AUDIO] ?: false
        hasPermissions = cam && aud
        if (cam && aud) {
            Toast.makeText(context, "Permissões concedidas! Câmera ativada.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "O app necessita de Câmera e Áudio para gravar replays.", Toast.LENGTH_LONG).show()
        }
    }

    // Ouvir eventos rápidos do buffer manager
    LaunchedEffect(key1 = true) {
        viewModel.events.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Se o aplicativo não estiver ativado, exibe a tela de ativação
    if (!isActivated) {
        ActivationScreen(
            deviceId = deviceId,
            isActivating = isActivating,
            activationError = activationError,
            onActivate = { key ->
                viewModel.activateApp(key) {
                    Toast.makeText(context, "Aplicativo ativado com sucesso!", Toast.LENGTH_SHORT).show()
                }
            }
        )
        return
    }

    // Estrutura principal com a Câmera
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        if (!hasPermissions) {
            // Tela informativa de pedido de permissões
            PermissionExplanationScreen(
                onRequestPermissions = {
                    val reqList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    }
                    permissionLauncher.launch(reqList)
                }
            )
        } else {
            // Tela do Visor e Controles da Câmera
            Box(modifier = Modifier.fillMaxSize()) {
                
                // 1. Visor da Câmera em Tela Cheia (PreviewView)
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            viewModel.getBufferManager().bindCamera(
                                lifecycleOwner = lifecycleOwner,
                                surfaceProvider = this.surfaceProvider,
                                onCameraReady = {}
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 2. Overlay com Gradiente
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.5f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.7f)
                                )
                            )
                        )
                )

                // 3. HUD - Topo (Indicador de gravação de buffer + Botão Engrenagem)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp, start = 20.dp, end = 20.dp)
                ) {
                    // Botão de Engrenagem (Configurações) no canto esquerdo
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .align(Alignment.CenterStart)
                    ) {
                        Text("⚙️", fontSize = 20.sp)
                    }

                    // Chip indicador centralizado
                    Box(
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        TopStatusHud(
                            recordingState = recordingState,
                            antes = antesSegundos,
                            depois = depoisSegundos
                        )
                    }
                }

                // 4. HUD - Controles e Ajustes Inferiores
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                ) {
                    // Painel de Botões Inferiores (Galeria, Botão Principal de Corte, Ligar/Desligar Buffer)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        
                        // Botão Galeria (Esquerda)
                        IconButton(
                            onClick = { showGallery = true },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Text("🎬", fontSize = 24.sp)
                        }

                        // Botão Principal (Centro)
                        MainActionButton(
                            state = recordingState,
                            onToggleMonitoring = { viewModel.toggleMonitoring() },
                            onTriggerClip = { viewModel.triggerClip() }
                        )

                        // Botão Câmera Ligar/Desligar (Direita)
                        val isMonitoring = recordingState !is VideoBufferManager.RecordingState.Idle
                        IconButton(
                            onClick = { viewModel.toggleMonitoring() },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isMonitoring) Color(0xFFE53935).copy(alpha = 0.8f) 
                                    else Color.Black.copy(alpha = 0.6f)
                                )
                                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Text(if (isMonitoring) "⏹️" else "📹", fontSize = 22.sp)
                        }
                    }
                }
            }
        }
    }

    // 5. Galeria de Vídeos (BottomSheet Deslizante)
    if (showGallery) {
        ModalBottomSheet(
            onDismissRequest = { showGallery = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White
        ) {
            GallerySheetContent(
                clips = savedClips,
                onPlay = { clip -> viewModel.playVideo(clip.uri) },
                onShare = { clip -> shareVideo(context, clip.uri) },
                onDelete = { clip -> viewModel.deleteClip(clip) },
                onClose = { showGallery = false }
            )
        }
    }

    // 6. Player de Vídeo Dialog
    activeVideoUri?.let { uri ->
        VideoPlayerDialog(
            videoUri = uri,
            onDismiss = { viewModel.playVideo(null) }
        )
    }

    // 7. Pop-up de Diálogo de Configurações (Ajustes de Tempo)
    if (showSettingsDialog) {
        SettingsDialog(
            antesSegundos = antesSegundos,
            depoisSegundos = depoisSegundos,
            onAntesChange = { viewModel.setAntesSegundos(it) },
            onDepoisChange = { viewModel.setDepoisSegundos(it) },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

/**
 * Tela de Ativação do Aplicativo (Sistemas Supabase)
 */
@Composable
fun ActivationScreen(
    deviceId: String,
    isActivating: Boolean,
    activationError: String?,
    onActivate: (String) -> Unit
) {
    var licenseInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF281545), Color(0xFF121212))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Ativação da Licença 🔑",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Insira sua chave de licença Supabase para desbloquear o aplicativo de replays.",
                    fontSize = 14.sp,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Caixa do Device ID
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "ID do Dispositivo", fontSize = 11.sp, color = Color.Gray)
                            Text(text = deviceId, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        
                        Button(
                            onClick = {
                                clipboardManager.setText(buildAnnotatedString { append(deviceId) })
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A148C)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Copiar", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Input da Licença
                OutlinedTextField(
                    value = licenseInput,
                    onValueChange = { licenseInput = it.uppercase() },
                    label = { Text("Chave de Licença") },
                    placeholder = { Text("REPLAY-XXXX-XXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFBA68C8),
                        unfocusedBorderColor = Color.DarkGray,
                        focusedLabelColor = Color(0xFFBA68C8),
                        unfocusedLabelColor = Color.Gray
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                // Mensagem de Erro
                activationError?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = err,
                        color = Color(0xFFE53935),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Botão de Envio
                Button(
                    onClick = { onActivate(licenseInput) },
                    enabled = !isActivating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFBA68C8),
                        disabledContainerColor = Color.DarkGray
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (isActivating) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text("ATIVAR APLICATIVO", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "Suporte para comercialização: comercial@exemplo.com",
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }
        }
    }
}

/**
 * Diálogo popup translúcido para Configurações de Replay ( sliders antes/depois )
 */
@Composable
fun SettingsDialog(
    antesSegundos: Int,
    depoisSegundos: Int,
    onAntesChange: (Int) -> Unit,
    onDepoisChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Configurações do Replay ⚙️",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(20.dp))

                // Ajuste Antes
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Tempo Antes (Passado)", color = Color.LightGray, fontSize = 12.sp)
                        Text(text = "${antesSegundos}s", color = Color(0xFFBA68C8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = antesSegundos.toFloat(),
                        onValueChange = { onAntesChange(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFBA68C8),
                            activeTrackColor = Color(0xFFBA68C8),
                            inactiveTrackColor = Color(0xFF424242)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Ajuste Depois
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Tempo Depois (Futuro)", color = Color.LightGray, fontSize = 12.sp)
                        Text(text = "${depoisSegundos}s", color = Color(0xFF4DB6AC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = depoisSegundos.toFloat(),
                        onValueChange = { onDepoisChange(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4DB6AC),
                            activeTrackColor = Color(0xFF4DB6AC),
                            inactiveTrackColor = Color(0xFF424242)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Total do Clipe: ${antesSegundos + depoisSegundos} segundos",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBA68C8)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("SALVAR & FECHAR", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

/**
 * Outros sub-composables já criados anteriormente
 */
@Composable
fun TopStatusHud(
    recordingState: VideoBufferManager.RecordingState,
    antes: Int,
    depois: Int
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val isMonitoring = recordingState is VideoBufferManager.RecordingState.Monitoring || 
                           recordingState is VideoBufferManager.RecordingState.Clipping
        
        if (isMonitoring) {
            val infiniteTransition = rememberInfiniteTransition(label = "blink")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(1.2f)
                    .clip(CircleShape)
                    .background(Color(0xFFBA68C8).copy(alpha = alpha)) 
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "BUFFER ATIVO (${antes}s + ${depois}s)",
                color = Color(0xFFE1BEE7),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.Gray)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "BUFFER DESLIGADO",
                color = Color.LightGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun MainActionButton(
    state: VideoBufferManager.RecordingState,
    onToggleMonitoring: () -> Unit,
    onTriggerClip: () -> Unit
) {
    when (state) {
        is VideoBufferManager.RecordingState.Idle -> {
            Button(
                onClick = onToggleMonitoring,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4A148C)
                ),
                shape = CircleShape,
                modifier = Modifier
                    .height(56.dp)
                    .width(180.dp)
                    .shadow(8.dp, CircleShape)
            ) {
                Text(
                    text = "LIGAR GRAVAÇÃO",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        is VideoBufferManager.RecordingState.Initializing -> {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
            }
        }
        is VideoBufferManager.RecordingState.Monitoring -> {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(90.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFE040FB).copy(alpha = 0.5f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFFD500F9), Color(0xFFF50057))
                            )
                        )
                        .clickable { onTriggerClip() }
                        .shadow(6.dp, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "LANCE!",
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        Text(
                            text = "REPLAY",
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        is VideoBufferManager.RecordingState.Clipping -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(80.dp)
            ) {
                CircularProgressIndicator(
                    progress = { 1f },
                    color = Color(0xFFFFD600),
                    strokeWidth = 4.dp,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF263238)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${state.secondsRemaining}s",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFFFD600)
                        )
                        Text(
                            text = "GRAVANDO",
                            fontSize = 8.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }
        }
        is VideoBufferManager.RecordingState.Saving -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(80.dp)
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF00E676),
                    strokeWidth = 3.dp,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF212121)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "SALVANDO",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E676)
                    )
                }
            }
        }
    }
}

@Composable
fun GallerySheetContent(
    clips: List<ClipInfo>,
    onPlay: (ClipInfo) -> Unit,
    onShare: (ClipInfo) -> Unit,
    onDelete: (ClipInfo) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Replays Salvos 🎙️",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            IconButton(onClick = onClose) {
                Text("❌", fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (clips.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nenhum replay salvo ainda.\nLigue a gravação de buffer e grave seus melhores lances!",
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(clips) { clip ->
                    ClipItemCard(
                        clip = clip,
                        onPlay = { onPlay(clip) },
                        onShare = { onShare(clip) },
                        onDelete = { onDelete(clip) }
                    )
                }
            }
        }
    }
}

@Composable
fun ClipItemCard(
    clip: ClipInfo,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A2A)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF37474F)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚽", fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = clip.displayName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val durationSec = clip.durationMs / 1000
                    val sizeMb = clip.sizeBytes.toFloat() / (1024 * 1024)
                    Text(
                        text = "Duração: ${durationSec}s | Tamanho: %.1f MB".format(sizeMb),
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPlay) {
                    Text("▶️", fontSize = 18.sp)
                }
                IconButton(onClick = onShare) {
                    Text("📤", fontSize = 18.sp)
                }
                IconButton(onClick = onDelete) {
                    Text("🗑️", fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
fun VideoPlayerDialog(
    videoUri: Uri,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setVideoURI(videoUri)
                            val mediaController = MediaController(context)
                            mediaController.setAnchorView(this)
                            setMediaController(mediaController)
                            setOnPreparedListener { start() }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .padding(top = 48.dp, end = 24.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .align(Alignment.TopEnd)
                ) {
                    Text("❌", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun PermissionExplanationScreen(
    onRequestPermissions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Replay de Lances ⚽",
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Para gravar e salvar instantaneamente os replays dos seus melhores lances, precisamos de acesso à câmera e gravação de áudio do microfone.",
            fontSize = 15.sp,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFBA68C8)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("CONCEDER ACESSO", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
        }
    }
}

fun shareVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartilhar Replay via"))
}
