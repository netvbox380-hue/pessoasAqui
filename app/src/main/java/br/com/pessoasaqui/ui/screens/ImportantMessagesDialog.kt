package br.com.pessoasaqui.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.MessageType
import br.com.pessoasaqui.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ImportantMessagesDialog(
    peerAlias: String,
    importantMessages: List<ChatMessage>,
    onUnmarkImportant: (messageId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val timeFormatter = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(24.dp)),
            color = DarkCard,
            border = BorderStroke(1.5.dp, WarningAmber.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header com Título e Fechar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(WarningAmber.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Mensagens Importantes",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = "Caixinha isolada com $peerAlias (${importantMessages.size})",
                                style = MaterialTheme.typography.bodySmall.copy(color = RadarCyan)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = DarkSurface, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                if (importantMessages.isEmpty()) {
                    // Estado Vazio
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = TextSecondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Nenhuma mensagem importante salva",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Para guardar mensagens importantes nesta conversa, dê um toque longo em qualquer mensagem e selecione 'Marcar como Importante ⭐'.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextSecondary,
                                    lineHeight = 18.sp
                                ),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Lista de Mensagens Importantes
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(importantMessages, key = { it.id }) { msg ->
                            val isMe = msg.isFromMe
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = DarkSurface,
                                border = BorderStroke(1.dp, if (isMe) RadarCyan.copy(alpha = 0.25f) else EmeraldGreen.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // Linha do Autor e Data
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isMe) "Você" else msg.senderAlias,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (isMe) RadarCyan else EmeraldGreen
                                            )
                                        )
                                        Text(
                                            text = timeFormatter.format(Date(msg.timestampMs)),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = TextSecondary,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Conteúdo da Mensagem
                                    when (msg.messageType) {
                                        MessageType.AUDIO -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Mic, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Mensagem de Voz (${msg.audioDurationSeconds}s)",
                                                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                                                )
                                            }
                                        }
                                        MessageType.IMAGE -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Image, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Foto Criptografada E2EE",
                                                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                                                )
                                            }
                                        }
                                        MessageType.DOCUMENT -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.AttachFile, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = msg.fileName ?: "Documento",
                                                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                                                )
                                            }
                                        }
                                        else -> {
                                            Text(
                                                text = msg.text,
                                                style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Ações da Mensagem
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (msg.messageType == MessageType.TEXT) {
                                            IconButton(
                                                onClick = {
                                                    clipboardManager.setText(AnnotatedString(msg.text))
                                                    Toast.makeText(context, "Texto copiado!", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = TextSecondary, modifier = Modifier.size(15.dp))
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }

                                        // Desmarcar estrela
                                        IconButton(
                                            onClick = { onUnmarkImportant(msg.id) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(Icons.Default.Star, contentDescription = "Desmarcar", tint = WarningAmber, modifier = Modifier.size(17.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
