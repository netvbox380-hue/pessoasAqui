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
import br.com.pessoasaqui.domain.model.MessageType
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationsTab(
    localMessages: List<ChatMessage>,
    connectedPeople: List<NearbyPerson>,
    privateChats: Map<String, List<ChatMessage>> = emptyMap(),
    nearbyPeople: List<NearbyPerson> = emptyList(),
    onSendLocalMessage: (String) -> Unit,
    onOpenChat: (NearbyPerson) -> Unit,
    onToggleMarkPerson: (String) -> Unit = {},
    onClearLocalMessages: () -> Unit = {},
    isCreatingInvite: Boolean = false,
    onCreateInviteLink: () -> Unit = {},
    onRedeemInviteLink: ((String, (Boolean, String?, NearbyPerson?) -> Unit) -> Unit)? = null
) {
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Mural Local (10m), 1 = Conversas Privadas
    var localInputText by remember { mutableStateOf("") }
    var selectedAuthorToInteract by remember { mutableStateOf<Pair<String, String>?>(null) } // senderId to senderAlias
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showPasteInviteDialog by remember { mutableStateOf(false) }

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        // Seletor de Seções (Mural Local vs Conversas Privadas)
        TabRow(
            selectedTabIndex = selectedSection,
            containerColor = DarkSurface,
            contentColor = RadarCyan,
            divider = {}
        ) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Mural Local (10m)",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (selectedSection == 0) RadarCyan else TextSecondary
                            )
                        )
                        if (localMessages.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = if (selectedSection == 0) RadarCyan.copy(alpha = 0.2f) else DarkCardElevated
                            ) {
                                Text(
                                    text = "${localMessages.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (selectedSection == 0) RadarCyan else TextSecondary,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            )

            Tab(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Privado (${connectedPeople.size})",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (selectedSection == 1) RadarCyan else TextSecondary
                            )
                        )
                    }
                }
            )
        }

        if (selectedSection == 0) {
            // ================= SEÇÃO: MURAL LOCAL EFÊMERO (10m) =================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Card Informativo Superior
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = null,
                            tint = RadarCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mural Público do Local",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = "Visível para qualquer pessoa a até 10 metros. Não requer telefone.",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }
                        if (localMessages.isNotEmpty()) {
                            IconButton(
                                onClick = { showClearConfirmDialog = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Limpar mural",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Lista de Mensagens do Mural
                if (localMessages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = null,
                                tint = RadarCyan.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "Nenhuma mensagem no mural local no momento.",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                ),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Envie uma pergunta ou aviso abaixo. Qualquer pessoa próxima a até 10 metros poderá responder!",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Sugestões de perguntas rápidas:",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = RadarCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )

                            val suggestions = listOf(
                                "Alguém sabe se já começaram o atendimento?",
                                "Qual a senha do Wi-Fi daqui?",
                                "Alguém tem um carregador tipo C emprestado?"
                            )

                            suggestions.forEach { suggestion ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DarkCardElevated,
                                    border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { localInputText = suggestion }
                                ) {
                                    Text(
                                        text = "💬 $suggestion",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(localMessages, key = { it.id }) { msg ->
                            val isMe = msg.isFromMe

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(0.92f),
                                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // Avatar para outras pessoas
                                    if (!isMe) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(EmeraldGreen.copy(alpha = 0.2f))
                                                .clickable {
                                                    selectedAuthorToInteract = Pair(msg.senderId, msg.senderAlias)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = msg.senderAlias.take(1).uppercase(),
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    color = EmeraldGreen,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    // Balão da Mensagem
                                    Card(
                                        shape = if (isMe) {
                                            RoundedCornerShape(16.dp, 16.dp, 3.dp, 16.dp)
                                        } else {
                                            RoundedCornerShape(16.dp, 16.dp, 16.dp, 3.dp)
                                        },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isMe) DarkCardElevated else DarkSurface
                                        ),
                                        border = if (isMe) {
                                            BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f))
                                        } else {
                                            BorderStroke(1.dp, DarkCardElevated)
                                        },
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .clickable(!isMe) {
                                                selectedAuthorToInteract = Pair(msg.senderId, msg.senderAlias)
                                            }
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = if (isMe) "Você" else msg.senderAlias,
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isMe) RadarCyan else EmeraldGreen
                                                        )
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = DeepBlack.copy(alpha = 0.5f)
                                                    ) {
                                                        Text(
                                                            text = "10m",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = TextSecondary,
                                                                fontSize = 10.sp
                                                            ),
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = timeFormatter.format(Date(msg.timestampMs)),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = TextSecondary,
                                                        fontSize = 10.sp
                                                    )
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Text(
                                                text = msg.text,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    color = TextPrimary,
                                                    lineHeight = 20.sp
                                                )
                                            )

                                            if (!isMe) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "★ Toque para interagir com ${msg.senderAlias}",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = RadarCyan.copy(alpha = 0.7f),
                                                        fontSize = 10.sp
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Barra de Digitação e Envio Local (Sem excesso de padding, com layout ergonômico)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = localInputText,
                        onValueChange = { localInputText = it },
                        placeholder = {
                            Text(
                                "Perguntar ou conversar aqui...",
                                style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = null,
                                tint = RadarCyan.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RadarCyan,
                            unfocusedBorderColor = DarkCardElevated,
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (localInputText.isNotBlank()) {
                                onSendLocalMessage(localInputText.trim())
                                localInputText = ""
                            }
                        },
                        enabled = localInputText.isNotBlank(),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (localInputText.isNotBlank()) RadarCyan else DarkCardElevated,
                            contentColor = if (localInputText.isNotBlank()) DeepBlack else TextSecondary
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Enviar",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        } else {
            // ================= SEÇÃO: CONEXÕES PRIVADAS (Distância Ilimitada) =================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Card Informativo Superior
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
                                text = "Conexões mútuas e familiares. Conversas sem limites de distância com voz, vídeo e fotos.",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }
                    }
                }

                // Botões de Convite Criptografado de Uso Único
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCreateInviteLink,
                        enabled = !isCreatingInvite,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RadarCyanGlow,
                            contentColor = RadarCyan
                        ),
                        border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f))
                    ) {
                        if (isCreatingInvite) {
                            CircularProgressIndicator(
                                color = RadarCyan,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Criando...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Criar Convite",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Button(
                        onClick = { showPasteInviteDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldGreen.copy(alpha = 0.15f),
                            contentColor = EmeraldGreen
                        ),
                        border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Colar Convite",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
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

                if (connectedPeople.isEmpty()) {
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
                                            Text(
                                                text = contact.alias,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            if (lastMsg != null) {
                                                Text(
                                                    text = timeFormatter.format(Date(lastMsg.timestampMs)),
                                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))

                                        // Prévia da última mensagem ou status de conexão
                                        val previewText = when {
                                            lastMsg == null -> if (contact.isFamily) "Família • Vínculo permanente" else "Conexão Mútua • Conversa liberada"
                                            lastMsg.messageType == MessageType.IMAGE -> "📷 Foto enviada"
                                            lastMsg.messageType == MessageType.AUDIO -> "🎤 Áudio (${lastMsg.audioDurationSeconds}s)"
                                            lastMsg.messageType == MessageType.DOCUMENT -> "📄 Documento"
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

        // ================= DIÁLOGO: INTERAÇÃO COM AUTOR NO MURAL LOCAL =================
        selectedAuthorToInteract?.let { author ->
            val authorId = author.first
            val authorAlias = author.second
            val peerInRadar = nearbyPeople.firstOrNull {
                it.alias.equals(authorAlias, ignoreCase = true) ||
                        it.technicalIdentityHash == authorId ||
                        it.id == authorId
            }

            AlertDialog(
                onDismissRequest = { selectedAuthorToInteract = null },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(EmeraldGreen.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = authorAlias.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = EmeraldGreen,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                },
                title = {
                    Text(authorAlias, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Autor de mensagem no Mural Local (10m).",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                            textAlign = TextAlign.Center
                        )
                        if (peerInRadar != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkCardElevated
                            ) {
                                Text(
                                    text = "Distância estimada: ~${peerInRadar.estimatedDistanceMeters.toInt()}m (${peerInRadar.proximityLabel})",
                                    style = MaterialTheme.typography.labelSmall.copy(color = RadarCyan),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            selectedAuthorToInteract = null
                            if (peerInRadar != null) {
                                onOpenChat(peerInRadar)
                            } else {
                                onToggleMarkPerson(authorId)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RadarCyan,
                            contentColor = DeepBlack
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (peerInRadar != null) "Abrir Conversa Privada" else "Marcar Contato",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedAuthorToInteract = null }) {
                        Text("Fechar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }

        // ================= DIÁLOGO: CONFIRMAR LIMPEZA DO MURAL =================
        if (showClearConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showClearConfirmDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = AlertRed,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text("Limpar Mural Local?", fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        text = "Deseja apagar as mensagens deste mural? Novas perguntas continuarão aparecendo conforme pessoas por perto enviarem.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onClearLocalMessages()
                            showClearConfirmDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AlertRed,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Limpar", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirmDialog = false }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }
    }
}
