package br.com.pessoasaqui.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.UserIntent
import br.com.pessoasaqui.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aba Principal do Radar de Proximidade e Mural Local (10m).
 * Design moderno em nível de produção: responsivo, com tipografia refinada e sem quebras de layout.
 */
@Composable
fun PeopleRadarTab(
    people: List<NearbyPerson>,
    currentIntent: UserIntent,
    isDemoMode: Boolean = false,
    isBluetoothEnabled: Boolean = true,
    localMessages: List<ChatMessage> = emptyList(),
    onSendLocalMessage: (String) -> Unit = {},
    onClearLocalMessages: () -> Unit = {},
    onToggleDemoMode: () -> Unit = {},
    onEnableBluetooth: () -> Unit = {},
    onOpenIntentSelector: () -> Unit,
    onToggleMark: (String) -> Unit,
    onAcceptMutual: (String) -> Unit = onToggleMark,
    onOpenChat: (NearbyPerson) -> Unit
) {
    var selectedSubTab by remember { mutableIntStateOf(0) } // 0 = Radar de Pessoas, 1 = Mural Local (10m)
    var localInputText by remember { mutableStateOf("") }
    var selectedAuthorToInteract by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

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

    val infiniteTransition = rememberInfiniteTransition(label = "radarPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        // Seletor Superior: Radar vs Mural Local (10m)
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = DarkSurface,
            contentColor = RadarCyan,
            divider = {}
        ) {
            Tab(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (people.isNotEmpty()) "Radar (${people.size})" else "Radar",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (selectedSubTab == 0) RadarCyan else TextSecondary
                            )
                        )
                    }
                }
            )
            Tab(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (localMessages.isNotEmpty()) "Mural Local (${localMessages.size})" else "Mural Local (10m)",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (selectedSubTab == 1) RadarCyan else TextSecondary
                            )
                        )
                        if (localMessages.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = if (selectedSubTab == 1) RadarCyan.copy(alpha = 0.2f) else DarkCardElevated
                            ) {
                                Text(
                                    text = "${localMessages.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (selectedSubTab == 1) RadarCyan else TextSecondary,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            )
        }

        if (selectedSubTab == 0) {
            // ================= SUB-ABA 0: RADAR DE PESSOAS =================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
        if (!isBluetoothEnabled) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = WarningAmber.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Bluetooth Desligado",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Ative para achar pessoas no raio de 10m",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                        }
                    }
                    Button(
                        onClick = onEnableBluetooth,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarningAmber,
                            contentColor = DeepBlack
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Ativar",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }

        if (isDemoMode) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = WarningAmber.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "🧪", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Modo Demonstração (Contatos Simulados)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = WarningAmber,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    TextButton(
                        onClick = onToggleDemoMode,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "Modo Real",
                            color = WarningAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Card de Status do Radar & Seletor de Intenção
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Linha superior: Status do radar e contagem de pessoas
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(RadarCyan.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp * pulseScale)
                                    .clip(CircleShape)
                                    .background(RadarCyan)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Pessoas por perto",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = if (people.isEmpty()) "Buscando pessoas no local..." else "${people.size} pessoa(s) no seu alcance",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = RadarCyan.copy(alpha = 0.12f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldGreen)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "10 Metros",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = RadarCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }

                // Linha inferior: Seletor de Intenção do Usuário
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkCardElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenIntentSelector() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = currentIntent.icon,
                                fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Minha intenção agora:",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                )
                                Text(
                                    text = currentIntent.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DarkCard,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = "Alterar",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = RadarCyan,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Lista de Pessoas ou Estado Vazio
        if (people.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier.size(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(150.dp * pulseScale)
                                .clip(CircleShape)
                                .background(RadarCyan.copy(alpha = 0.04f))
                        )
                        Box(
                            modifier = Modifier
                                .size(110.dp * pulseScale)
                                .clip(CircleShape)
                                .background(RadarCyan.copy(alpha = 0.08f))
                        )
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(RadarCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonSearch,
                                contentDescription = null,
                                tint = RadarCyan,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Text(
                        text = "Buscando pessoas por perto...",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Qualquer pessoa que estiver fisicamente perto com o PessoasAqui aberto aparecerá automaticamente aqui no app.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        onClick = onToggleDemoMode,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarCyan)
                    ) {
                        Text(
                            text = if (isDemoMode) "Desativar Modo Demonstração" else "🧪 Testar com Contatos de Exemplo",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(people, key = { it.id }) { person ->
                    PersonCard(
                        person = person,
                        onToggleMark = { onToggleMark(person.id) },
                        onAcceptMutual = { onAcceptMutual(person.id) },
                        onOpenChat = { onOpenChat(person) }
                    )
                }
            }
        }
    }
} else {
    // ================= SUB-ABA 1: MURAL LOCAL EFÊMERO (10m) =================
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
                                            text = formatSmartDateTime(msg.timestampMs),
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

        // Barra de Digitação e Envio Local
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
}

// Diálogo de confirmação para limpar mural local
if (showClearConfirmDialog) {
    AlertDialog(
        onDismissRequest = { showClearConfirmDialog = false },
        title = { Text("Limpar Mural Local?") },
        text = { Text("Todas as mensagens públicas do mural atual de 10m serão removidas do seu dispositivo.") },
        confirmButton = {
            TextButton(
                onClick = {
                    onClearLocalMessages()
                    showClearConfirmDialog = false
                }
            ) {
                Text("Limpar", color = AlertRed, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { showClearConfirmDialog = false }) {
                Text("Cancelar")
            }
        }
    )
}

// Diálogo de Interação com autor de mensagem do mural local
selectedAuthorToInteract?.let { (authorId, authorAlias) ->
    val matchedPeer = people.find { it.id == authorId || it.technicalIdentityHash == authorId }
    AlertDialog(
        onDismissRequest = { selectedAuthorToInteract = null },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(RadarCyan.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = authorAlias.take(1).uppercase(),
                        color = RadarCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(authorAlias, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Esta pessoa está por perto no raio de 10 metros.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                )
                if (matchedPeer != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkCardElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Distância estimada:", color = TextSecondary, fontSize = 12.sp)
                            Text(
                                matchedPeer.proximityLabel,
                                color = RadarCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = matchedPeer ?: NearbyPerson(
                        id = authorId,
                        technicalIdentityHash = authorId,
                        alias = authorAlias,
                        estimatedDistanceMeters = 5.0,
                        proximityLabel = "10m"
                    )
                    onOpenChat(target)
                    selectedAuthorToInteract = null
                },
                colors = ButtonDefaults.buttonColors(containerColor = RadarCyan, contentColor = DeepBlack),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Conversar no Privado", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { selectedAuthorToInteract = null }) {
                Text("Fechar")
            }
        }
    )
}
    }
}

/**
 * Card individual de pessoa próxima com layout responsivo e profissional.
 */
@Composable
fun PersonCard(
    person: NearbyPerson,
    onToggleMark: () -> Unit,
    onAcceptMutual: () -> Unit = onToggleMark,
    onOpenChat: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenChat() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar com anel colorido de presença
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(person.avatarColorHex).copy(alpha = 0.15f))
                        .border(1.5.dp, Color(person.avatarColorHex), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (person.isPhotoVisible) {
                        Text(
                            text = person.alias.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(person.avatarColorHex)
                            )
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color(person.avatarColorHex),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Coluna central com dados da pessoa
                Column(modifier = Modifier.weight(1f)) {
                    // Nome e Selo de Família
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = person.alias,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (person.isFamily) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = FamilyPurple.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "Família • ${person.familyRole?.label ?: "Membro"}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = FamilyPurple,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Linha com distância e intenção com formatação limpa e tags separadas
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Badge de Distância
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (person.estimatedDistanceMeters < 3.0) EmeraldGreen.copy(alpha = 0.15f) else RadarCyan.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "• ${person.estimatedDistanceMeters.toInt()}m",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (person.estimatedDistanceMeters < 3.0) EmeraldGreen else RadarCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Intenção com ícone e texto truncado de forma segura
                        Text(
                            text = "${person.intent.icon} ${person.intent.label}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 12.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Botão de Marcação (Coração)
                IconButton(
                    onClick = {
                        if (person.isMarkingMe && !person.isMarkedByMe) {
                            onAcceptMutual()
                        } else {
                            onToggleMark()
                        }
                    },
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = when {
                            person.isMutualConnection -> EmeraldGreenGlow
                            person.isMarkingMe && !person.isMarkedByMe -> EmeraldGreen.copy(alpha = 0.22f)
                            person.isMarkedByMe -> RadarCyanGlow
                            else -> DarkCardElevated
                        }
                    )
                ) {
                    Icon(
                        imageVector = if (person.isMarkedByMe || person.isMutualConnection) Icons.Default.Favorite else if (person.isMarkingMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Marcar pessoa",
                        tint = when {
                            person.isMutualConnection -> EmeraldGreen
                            person.isMarkingMe && !person.isMarkedByMe -> EmeraldGreen
                            person.isMarkedByMe -> RadarCyan
                            else -> TextSecondary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Banner indicando que a pessoa marcou o usuário
            AnimatedVisibility(visible = person.isMarkingMe && !person.isMarkedByMe && !person.isMutualConnection) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = EmeraldGreen.copy(alpha = 0.15f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { onAcceptMutual() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "★ Marcou você! Toque no coração para aceitar conexão mútua.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = EmeraldGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Banner elegante de Conexão Mútua Estabelecida
            AnimatedVisibility(visible = person.isMutualConnection) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = EmeraldGreen.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "✨ Conexão Mútua Estabelecida! Vínculo liberado.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = EmeraldGreen,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ChatBubble,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
