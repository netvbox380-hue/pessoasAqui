package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.ContactTag
import br.com.pessoasaqui.domain.model.MessageType
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.RecurringReminder
import br.com.pessoasaqui.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationsTab(
    connectedPeople: List<NearbyPerson>,
    privateChats: Map<String, List<ChatMessage>> = emptyMap(),
    nearbyPeople: List<NearbyPerson> = emptyList(),
    onOpenChat: (NearbyPerson) -> Unit,
    onToggleMarkPerson: (String) -> Unit = {},
    isCreatingInvite: Boolean = false,
    onCreateInviteLink: () -> Unit = {},
    onRedeemInviteLink: ((String, (Boolean, String?, NearbyPerson?) -> Unit) -> Unit)? = null,
    contactTags: List<ContactTag> = emptyList(),
    contactTagAssignments: Map<String, Set<String>> = emptyMap()
) {
    var showPasteInviteDialog by remember { mutableStateOf(false) }
    var showConversationsDrawer by remember { mutableStateOf(false) }

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    fun formatSmartDateTime(timestampMs: Long): String {
        val nowCal = java.util.Calendar.getInstance()
        val msgCal = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }

        val isToday = nowCal.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR) &&
                nowCal.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR)
        val timeStr = timeFormatter.format(Date(timestampMs))
        if (isToday) return "Hoje às $timeStr"

        nowCal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val isYesterday = nowCal.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR) &&
                nowCal.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR)
        if (isYesterday) return "Ontem às $timeStr"

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return if (msgCal.get(java.util.Calendar.YEAR) == currentYear) {
            SimpleDateFormat("dd/MM 'às' HH:mm", Locale.getDefault()).format(Date(timestampMs))
        } else {
            SimpleDateFormat("dd/MM/yy 'às' HH:mm", Locale.getDefault()).format(Date(timestampMs))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 16.dp)
    ) {
        // Card Informativo Superior com Gavetinha de Convites
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = EmeraldGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Conversas Privadas Criptografadas",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Conexões mútuas e familiares sem limites de distância.",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = { showConversationsDrawer = true }
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(RadarCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Widgets,
                            contentDescription = "Gaveta de Convites",
                            tint = RadarCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // ================= GAVETINHA DE CONVITES =================
        if (showConversationsDrawer) {
            AlertDialog(
                onDismissRequest = { showConversationsDrawer = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Widgets,
                            contentDescription = null,
                            tint = RadarCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Gaveta de Convites", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 1. Criar Convite
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isCreatingInvite) {
                                    showConversationsDrawer = false
                                    onCreateInviteLink()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(RadarCyan.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Criar Convite Criptografado", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Link seguro de uso único para compartilhar", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }

                        // 2. Colar Convite
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showConversationsDrawer = false
                                    showPasteInviteDialog = true
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(EmeraldGreen.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Colar Link de Convite", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Conectar com link recebido de outra pessoa", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showConversationsDrawer = false }) {
                        Text("Fechar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }

        if (showPasteInviteDialog) {
                    var pastedInviteText by remember { mutableStateOf("") }
                    var isRedeeming by remember { mutableStateOf(false) }
                    var pasteError by remember { mutableStateOf<String?>(null) }
                    val clipboard = LocalClipboardManager.current

                    AlertDialog(
                        onDismissRequest = {
                            if (!isRedeeming) showPasteInviteDialog = false
                        },
                        title = {
                            Text(
                                text = "Inserir Link de Convite",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Cole o link de convite recebido para estabelecer a conexão mútua imediata:",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                )
                                OutlinedTextField(
                                    value = pastedInviteText,
                                    onValueChange = {
                                        pastedInviteText = it
                                        pasteError = null
                                    },
                                    placeholder = {
                                        Text("https://pessoasaqui.onrender.com/invite?id=...", color = TextSecondary.copy(alpha = 0.5f))
                                    },
                                    singleLine = false,
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = EmeraldGreen,
                                        unfocusedBorderColor = DarkCard,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    )
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            val clipText = clipboard.getText()?.text
                                            if (!clipText.isNullOrBlank()) {
                                                pastedInviteText = clipText.trim()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp), tint = RadarCyan)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Colar", color = RadarCyan, fontSize = 12.sp)
                                    }
                                }
                                if (pasteError != null) {
                                    Text(
                                        text = pasteError ?: "",
                                        color = AlertRed,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val input = pastedInviteText.trim()
                                    if (input.isBlank()) {
                                        pasteError = "Por favor, cole o link do convite."
                                        return@Button
                                    }
                                    isRedeeming = true
                                    pasteError = null
                                    onRedeemInviteLink?.invoke(input) { success, msg, peer ->
                                        isRedeeming = false
                                        if (success && peer != null) {
                                            showPasteInviteDialog = false
                                            onOpenChat(peer)
                                        } else {
                                            pasteError = msg ?: "Falha ao validar convite."
                                        }
                                    }
                                },
                                enabled = !isRedeeming && pastedInviteText.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldGreen,
                                    contentColor = TextPrimary
                                )
                            ) {
                                if (isRedeeming) {
                                    CircularProgressIndicator(
                                        color = TextPrimary,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Validando...", fontWeight = FontWeight.Bold)
                                } else {
                                    Text("★ Conectar", fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showPasteInviteDialog = false },
                                enabled = !isRedeeming
                            ) {
                                Text("Cancelar", color = TextSecondary)
                            }
                        },
                        containerColor = DarkSurface
                    )
                }

                val supportHistory = remember(privateChats) {
                    privateChats["suporte-oficial"]
                        ?: privateChats["peer-suporte-oficial"]
                        ?: privateChats["SUPORTE-OFICIAL"]
                        ?: emptyList()
                }

                if (connectedPeople.isEmpty() && supportHistory.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = EmeraldGreen.copy(alpha = 0.5f),
                                modifier = Modifier.size(54.dp)
                            )
                            Text(
                                text = "Nenhuma conexão mútua ainda",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Como funciona a conexão privada:",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = RadarCyan
                                        )
                                    )
                                    Text(
                                        text = "1. Na aba 'Pessoas' ou no 'Mural Local', toque na estrela ★ para marcar alguém.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                    )
                                    Text(
                                        text = "2. Quando a outra pessoa também marcar você, a conversa privada à distância se abre aqui automaticamente!",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                    )
                                    Text(
                                        text = "3. Seus contatos mútuos são guardados no seu aparelho e vocês continuam conversando de qualquer lugar do mundo.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = EmeraldGreen)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = onCreateInviteLink,
                                            enabled = !isCreatingInvite,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = RadarCyanGlow,
                                                contentColor = RadarCyan
                                            ),
                                            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f))
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Criar Convite", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }

                                        Button(
                                            onClick = { showPasteInviteDialog = true },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = EmeraldGreen.copy(alpha = 0.15f),
                                                contentColor = EmeraldGreen
                                            ),
                                            border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.5f))
                                        ) {
                                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Colar Convite", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        if (supportHistory.isNotEmpty()) {
                            item(key = "suporte-oficial") {
                                val lastMsg = supportHistory.last()
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onOpenChat(
                                                NearbyPerson(
                                                    id = "suporte-oficial",
                                                    technicalIdentityHash = "suporte-oficial",
                                                    alias = "Suporte PessoasAqui",
                                                    estimatedDistanceMeters = 0.0,
                                                    proximityLabel = "Canal Oficial de Atendimento",
                                                    isMutualConnection = true
                                                )
                                            )
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                                .background(EmeraldGreen.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Headset,
                                                contentDescription = null,
                                                tint = EmeraldGreen,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Suporte PessoasAqui",
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    )
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = EmeraldGreen.copy(alpha = 0.2f),
                                                    border = BorderStroke(1.dp, EmeraldGreen)
                                                ) {
                                                    Text(
                                                        text = "OFICIAL",
                                                        color = EmeraldGreen,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Text(
                                                text = lastMsg.text,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = EmeraldGreen,
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        items(connectedPeople, key = { it.id }) { contact ->
                            // Busca histórico privado dessa pessoa para exibir prévia da última mensagem
                            val history = privateChats[contact.id]
                                ?: privateChats[contact.technicalIdentityHash]
                                ?: emptyList()
                            val lastMsg = history.lastOrNull()

                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                border = if (contact.isFamily) {
                                    BorderStroke(1.dp, FamilyPurple.copy(alpha = 0.5f))
                                } else {
                                    BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.3f))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenChat(contact) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Avatar
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(Color(contact.avatarColorHex).copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = contact.alias.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                color = Color(contact.avatarColorHex),
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f, fill = false)
                                            ) {
                                                Text(
                                                    text = contact.alias,
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    ),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )

                                                val cClean = contact.technicalIdentityHash.ifBlank { contact.id }.removePrefix("peer-").trim().uppercase()
                                                val cTagIds = contactTagAssignments[cClean] ?: emptySet()
                                                val cTags = contactTags.filter { cTagIds.contains(it.id) }
                                                cTags.take(2).forEach { t ->
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    val tColor = try { Color(android.graphics.Color.parseColor(t.colorHex)) } catch (_: Exception) { RadarCyan }
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = tColor.copy(alpha = 0.18f),
                                                        border = BorderStroke(1.dp, tColor.copy(alpha = 0.5f))
                                                    ) {
                                                        Text(
                                                            text = t.name,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = tColor,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold
                                                            ),
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            if (lastMsg != null) {
                                                Text(
                                                    text = formatSmartDateTime(lastMsg.timestampMs),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = TextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))

                                        // Prévia da última mensagem ou status de conexão
                                        val previewText = when {
                                            lastMsg == null -> if (contact.isFamily) "Família • Vínculo permanente" else "Conexão Mútua • Conversa liberada"
                                            lastMsg.messageType == MessageType.IMAGE -> "📷 Foto enviada"
                                            lastMsg.messageType == MessageType.AUDIO -> "🎤 Áudio (${lastMsg.audioDurationSeconds}s)"
                                            lastMsg.messageType == MessageType.DOCUMENT -> "📄 ${lastMsg.fileName ?: "Documento"}"
                                            else -> lastMsg.text
                                        }

                                        Text(
                                            text = previewText,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = if (lastMsg != null) TextSecondary else EmeraldGreen
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Icon(
                                        imageVector = Icons.Default.Chat,
                                        contentDescription = "Abrir conversa",
                                        tint = if (contact.isFamily) FamilyPurple else EmeraldGreen,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
