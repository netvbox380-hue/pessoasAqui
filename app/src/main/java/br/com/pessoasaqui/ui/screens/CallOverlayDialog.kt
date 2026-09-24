package br.com.pessoasaqui.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.domain.model.ActiveCallState
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun CallOverlayDialog(
    callState: ActiveCallState,
    onAnswer: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit
) {
    var callSeconds by remember { mutableStateOf(0) }

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
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DeepBlack.copy(alpha = 0.95f),
                            DarkSurface.copy(alpha = 0.98f),
                            DeepBlack
                        )
                    )
                )
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar com status de criptografia E2EE
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkCardElevated.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 16.dp)
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
                            )
                        )
                    }
                }

                // Corpo Central: Avatar, Nome e Status
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Avatar com pulso se estiver chamando
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .scale(if (!callState.isConnected) pulseScale else 1f)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(RadarCyan.copy(alpha = 0.3f), FamilyPurple.copy(alpha = 0.3f))
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
                                fontSize = 48.sp
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = callState.peerAlias,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = when {
                            callState.isConnected -> "Conectado • $formattedDuration"
                            callState.isIncoming -> "Chamada de ${if (callState.isVideo) "Vídeo" else "Voz"} Recebida..."
                            else -> "Chamando ${if (callState.isVideo) "(Vídeo)" else "(Voz)"}..."
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = if (callState.isConnected) EmeraldGreen else TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    // Se for chamada de vídeo conectada, exibe tela de vídeo simulada
                    if (callState.isVideo && callState.isConnected) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, RadarCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                            color = DarkCard
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (callState.isCameraOn) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.Videocam,
                                            contentDescription = null,
                                            tint = RadarCyan,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Transmissão de Vídeo Segura Ativa",
                                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
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

                // Barra Inferior de Controles
                if (!callState.isConnected && callState.isIncoming) {
                    // Chamada recebida aguardando resposta
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botão Recusar
                        IconButton(
                            onClick = onEnd,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(AlertRed)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Recusar",
                                tint = TextPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Botão Atender
                        IconButton(
                            onClick = onAnswer,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(EmeraldGreen)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Atender",
                                tint = DeepBlack,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                } else {
                    // Chamada ativa ou discando
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botão Mudo
                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (callState.isMuted) WarningAmber else DarkCardElevated)
                        ) {
                            Icon(
                                imageVector = if (callState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mudo",
                                tint = if (callState.isMuted) DeepBlack else TextPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Botão Desligar / Encerrar
                        IconButton(
                            onClick = onEnd,
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(AlertRed)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Desligar",
                                tint = TextPrimary,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        // Botão Câmera (se chamada de vídeo) ou Alto-falante
                        if (callState.isVideo) {
                            IconButton(
                                onClick = onToggleCamera,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(if (!callState.isCameraOn) WarningAmber else DarkCardElevated)
                            ) {
                                Icon(
                                    imageVector = if (callState.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                    contentDescription = "Câmera",
                                    tint = if (!callState.isCameraOn) DeepBlack else TextPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { /* Alterna viva-voz */ },
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(DarkCardElevated)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Alto-falante",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
