package br.com.pessoasaqui.ui.screens

import android.app.Activity
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import br.com.pessoasaqui.domain.model.ActiveCallState
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun CallOverlayDialog(
    callState: ActiveCallState,
    onAnswer: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleSpeakerphone: () -> Unit = {}
) {
    var callSeconds by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    val answerPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        onAnswer()
    }

    // Cronômetro da chamada quando conectada
    LaunchedEffect(callState.isConnected) {
        if (callState.isConnected) {
            callSeconds = 0
            while (true) {
                delay(1000)
                callSeconds++
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val formattedDuration = remember(callSeconds) {
        val mins = callSeconds / 60
        val secs = callSeconds % 60
        String.format("%02d:%02d", mins, secs)
    }

    Dialog(
        onDismissRequest = { /* Não fecha ao clicar fora durante a ligação */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Intercepta o botão voltar do Android para encerrar/recusar a chamada
        BackHandler {
            onEnd()
        }

        // Garante que a DialogWindow respeite transparência e insets em todos os aparelhos Android
        val view = LocalView.current
        SideEffect {
            var parent = view.parent
            while (parent != null) {
                if (parent is DialogWindowProvider) {
                    val window = parent.window
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    break
                }
                parent = parent.parent
            }
        }

        // BoxWithConstraints permite auto-adaptação dinâmica para telas pequenas (ex: 6,2" com barra de 3 botões)
        // e telas maiores (ex: 6,7" com navegação por gestos)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DeepBlack.copy(alpha = 0.98f),
                            DarkSurface.copy(alpha = 0.98f),
                            DeepBlack
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val screenHeight = maxHeight
            val isCompactScreen = screenHeight < 680.dp // Ex: 6,2" com barra clássica de 3 botões

            val avatarSize = if (isCompactScreen) 96.dp else 126.dp
            val avatarFontSize = if (isCompactScreen) 38.sp else 46.sp
            val centerSpacing = if (isCompactScreen) 10.dp else 18.dp
            val actionBtnSize = if (isCompactScreen) 52.dp else 58.dp
            val endCallBtnSize = if (isCompactScreen) 62.dp else 68.dp
            val btnIconSize = if (isCompactScreen) 24.dp else 28.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = if (isCompactScreen) 6.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ================= TOPO: BADGE CRIPTOGRAFIA E2EE =================
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkCardElevated.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = if (isCompactScreen) 4.dp else 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Chamada Criptografada Ponta a Ponta (E2EE)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = EmeraldGreen,
                                fontWeight = FontWeight.SemiBold
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(centerSpacing))

                // ================= CORPO CENTRAL: AVATAR / VÍDEO / NOME / STATUS =================
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // Avatar com pulso animado se estiver chamando
                    Box(
                        modifier = Modifier
                            .size(avatarSize)
                            .scale(if (!callState.isConnected) pulseScale else 1f)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(RadarCyan.copy(alpha = 0.35f), FamilyPurple.copy(alpha = 0.35f))
                                )
                            )
                            .border(3.dp, if (callState.isConnected) EmeraldGreen else RadarCyan, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = callState.peerAlias.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                fontSize = avatarFontSize
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(if (isCompactScreen) 12.dp else 18.dp))

                    Text(
                        text = callState.peerAlias,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = when {
                            callState.isConnected -> "Conectado • $formattedDuration"
                            callState.isIncoming -> "Chamada de ${if (callState.isVideo) "Vídeo" else "Voz"} Recebida..."
                            else -> "Chamando ${if (callState.isVideo) "(Vídeo)" else "(Voz)"}..."
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = if (callState.isConnected) EmeraldGreen else TextSecondary,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center
                    )

                    // Se for chamada de vídeo conectada, exibe container de vídeo responsivo
                    if (callState.isVideo && callState.isConnected) {
                        Spacer(modifier = Modifier.height(14.dp))
                        val remoteBitmap = remember(callState.remoteVideoFrameBase64) {
                            try {
                                if (!callState.remoteVideoFrameBase64.isNullOrBlank()) {
                                    val decoded = Base64.decode(callState.remoteVideoFrameBase64, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(decoded, 0, decoded.size)?.asImageBitmap()
                                } else null
                            } catch (_: Exception) { null }
                        }

                        val videoHeight = if (isCompactScreen) 160.dp else 220.dp

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(videoHeight)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, RadarCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                            color = DarkCard
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (remoteBitmap != null) {
                                    Image(
                                        bitmap = remoteBitmap,
                                        contentDescription = "Vídeo de ${callState.peerAlias}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Surface(
                                        color = DeepBlack.copy(alpha = 0.65f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = "● Ao Vivo • ${callState.peerAlias}",
                                            style = MaterialTheme.typography.labelSmall.copy(color = EmeraldGreen),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                } else if (callState.isCameraOn) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.Videocam,
                                            contentDescription = null,
                                            tint = RadarCyan,
                                            modifier = Modifier.size(38.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Câmera ativa • Conectando transmissão segura...",
                                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Câmera desativada",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(centerSpacing))

                // ================= BARRA INFERIOR DE CONTROLES (PROTEGIDA CONTRA SISTEMA) =================
                // navigationBarsPadding já adicionou o espaço da barra de 3 botões ou gestos.
                // O padding bottom garante respiro visual ergonômico acima dos botões físicos/virtuais.
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (isCompactScreen) 26.dp else 22.dp)
                ) {
                    if (!callState.isConnected && callState.isIncoming) {
                        // Chamada recebida aguardando resposta (Recusar vs Atender)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Botão Recusar
                            IconButton(
                                onClick = onEnd,
                                modifier = Modifier
                                    .size(endCallBtnSize)
                                    .clip(CircleShape)
                                    .background(AlertRed)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "Recusar",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(btnIconSize + 4.dp)
                                )
                            }

                            // Botão Atender
                            IconButton(
                                onClick = {
                                    val hasAudio = ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.RECORD_AUDIO
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    val hasCamera = if (callState.isVideo) {
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.CAMERA
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    } else true

                                    if (hasAudio && hasCamera) {
                                        onAnswer()
                                    } else {
                                        val perms = if (callState.isVideo) {
                                            arrayOf(
                                                android.Manifest.permission.RECORD_AUDIO,
                                                android.Manifest.permission.CAMERA
                                            )
                                        } else {
                                            arrayOf(android.Manifest.permission.RECORD_AUDIO)
                                        }
                                        answerPermissionsLauncher.launch(perms)
                                    }
                                },
                                modifier = Modifier
                                    .size(endCallBtnSize)
                                    .clip(CircleShape)
                                    .background(EmeraldGreen)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "Atender",
                                    tint = DeepBlack,
                                    modifier = Modifier.size(btnIconSize + 4.dp)
                                )
                            }
                        }
                    } else {
                        // Chamada ativa ou discando
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Botão Mudo
                            IconButton(
                                onClick = onToggleMute,
                                modifier = Modifier
                                    .size(actionBtnSize)
                                    .clip(CircleShape)
                                    .background(if (callState.isMuted) WarningAmber else DarkCardElevated)
                            ) {
                                Icon(
                                    imageVector = if (callState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "Mudo",
                                    tint = if (callState.isMuted) DeepBlack else TextPrimary,
                                    modifier = Modifier.size(btnIconSize)
                                )
                            }

                            // Botão Desligar / Encerrar
                            IconButton(
                                onClick = onEnd,
                                modifier = Modifier
                                    .size(endCallBtnSize)
                                    .clip(CircleShape)
                                    .background(AlertRed)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "Desligar",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(btnIconSize + 4.dp)
                                )
                            }

                            // Botão Câmera (se chamada de vídeo)
                            if (callState.isVideo) {
                                IconButton(
                                    onClick = onToggleCamera,
                                    modifier = Modifier
                                        .size(actionBtnSize)
                                        .clip(CircleShape)
                                        .background(if (!callState.isCameraOn) WarningAmber else DarkCardElevated)
                                ) {
                                    Icon(
                                        imageVector = if (callState.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                        contentDescription = "Câmera",
                                        tint = if (!callState.isCameraOn) DeepBlack else TextPrimary,
                                        modifier = Modifier.size(btnIconSize)
                                    )
                                }
                            }

                            // Botão Alto-falante / Viva-Voz
                            IconButton(
                                onClick = onToggleSpeakerphone,
                                modifier = Modifier
                                    .size(actionBtnSize)
                                    .clip(CircleShape)
                                    .background(if (callState.isSpeakerphoneOn) RadarCyan else DarkCardElevated)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Alto-falante",
                                    tint = if (callState.isSpeakerphoneOn) DeepBlack else TextPrimary,
                                    modifier = Modifier.size(btnIconSize)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
