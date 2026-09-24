package br.com.pessoasaqui.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.FamilyRole
import br.com.pessoasaqui.domain.model.MessageType
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    person: NearbyPerson,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStartCall: (NearbyPerson, Boolean) -> Unit = { _, _ -> },
    onSendAudioMessage: (NearbyPerson, Int) -> Unit = { _, _ -> },
    onToggleMarkPerson: (NearbyPerson) -> Unit = { _ -> },
    onRequestFamilyRole: (NearbyPerson, FamilyRole) -> Unit = { _, _ -> },
    onGenerateFamilyRecovery: (NearbyPerson) -> Unit = { _ -> },
    onBlockUser: (String) -> Unit = { _ -> },
    onReportUser: (String) -> Unit = { _ -> }
) {
    var inputText by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showFamilyRoleDialog by remember { mutableStateOf(false) }
    var restrictedFeatureDialog by remember { mutableStateOf<String?>(null) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var playingAudioId by remember { mutableStateOf<String?>(null) }

    val isMutualOrFamily = person.isMutualConnection || person.isFamily
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Timer de gravação de áudio
    LaunchedEffect(isRecordingAudio) {
        if (isRecordingAudio) {
            recordingSeconds = 0
            while (isRecordingAudio) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    // Auto-scroll para a última mensagem
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(person.avatarColorHex).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = person.alias.take(1).uppercase(),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(person.avatarColorHex)
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = person.alias,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = when {
                                    person.isFamily -> "Família • ${person.familyRole?.label ?: "Vínculo Seguro"}"
                                    person.isMutualConnection -> "Conexão Mútua • Qualquer distância"
                                    else -> "Radar Local • ~${person.estimatedDistanceMeters}m"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = when {
                                        person.isFamily -> FamilyPurple
                                        person.isMutualConnection -> EmeraldGreen
                                        else -> RadarCyan
                                    }
                                )
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    // Ícone de Chamada de Voz (desbloqueado somente para Mútuo ou Familiar)
                    IconButton(onClick = {
                        if (isMutualOrFamily) {
                            onStartCall(person, false)
                        } else {
                            restrictedFeatureDialog = "Chamada de Voz"
                        }
                    }) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Chamada de Voz",
                                tint = if (isMutualOrFamily) RadarCyan else TextSecondary.copy(alpha = 0.6f)
                            )
                            if (!isMutualOrFamily) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Bloqueado",
                                    tint = WarningAmber,
                                    modifier = Modifier
                                        .size(10.dp)
                                        .align(Alignment.BottomEnd)
                                )
                            }
                        }
                    }

                    // Ícone de Chamada de Vídeo (desbloqueado somente para Mútuo ou Familiar)
                    IconButton(onClick = {
                        if (isMutualOrFamily) {
                            onStartCall(person, true)
                        } else {
                            restrictedFeatureDialog = "Chamada de Vídeo"
                        }
                    }) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "Chamada de Vídeo",
                                tint = if (isMutualOrFamily) RadarCyan else TextSecondary.copy(alpha = 0.6f)
                            )
                            if (!isMutualOrFamily) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Bloqueado",
                                    tint = WarningAmber,
                                    modifier = Modifier
                                        .size(10.dp)
                                        .align(Alignment.BottomEnd)
                                )
                            }
                        }
                    }

                    // Menu Mais Opções
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Mais opções",
                            tint = TextPrimary
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(DarkSurface)
                    ) {
                        if (!person.isFamily) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "👨‍👩‍👧 Convidar como Familiar",
                                        color = FamilyPurple,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showFamilyRoleDialog = true
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "🔐 Recuperar identidade de ${person.alias}",
                                        color = RadarCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onGenerateFamilyRecovery(person)
                                }
                            )
                        }

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (person.isMarkedByMe) "★ Desmarcar Conexão" else "☆ Marcar Conexão Mútua",
                                    color = RadarCyan
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleMarkPerson(person)
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Bloquear contato", color = TextPrimary) },
                            onClick = {
                                menuExpanded = false
                                onBlockUser(person.id)
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Denunciar abuso", color = AlertRed) },
                            onClick = {
                                menuExpanded = false
                                onReportUser(person.id)
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        },
        containerColor = DeepBlack,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        // imePadding() garante que o conteúdo e barra de digitação subam perfeitamente sobre o teclado no Android 14/15/16
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // Banner de Status de Permissões / Restrições
            if (!isMutualOrFamily) {
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = DarkCardElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Radar Local • Somente Texto Criptografado",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = WarningAmber,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Chamadas de voz, vídeo e áudios são liberados quando ambos se marcam mutuamente ou confirmam parentesco.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onToggleMarkPerson(person) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    text = if (person.isMarkedByMe) "★ Marcado por você" else "☆ Marcar Conexão",
                                    fontSize = 11.sp,
                                    color = RadarCyan
                                )
                            }
                            Button(
                                onClick = { showFamilyRoleDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = FamilyPurple),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    text = "👨‍👩‍👧 Convidar Familiar",
                                    fontSize = 11.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = DarkCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (person.isFamily) Icons.Default.Shield else Icons.Default.Verified,
                            contentDescription = null,
                            tint = if (person.isFamily) FamilyPurple else EmeraldGreen,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (person.isFamily)
                                "🛡️ Familiar Conectado • Chamadas, vídeos e áudios ilimitados (E2EE)"
                            else
                                "🤝 Conexão Mútua Confirmada • Chamadas, vídeos e áudios liberados (E2EE)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (person.isFamily) FamilyPurple else EmeraldGreen,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            // Lista de Mensagens
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    val isMe = msg.isFromMe
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            shape = RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isMe) 16.dp else 4.dp,
                                bottomEnd = if (isMe) 4.dp else 16.dp
                            ),
                            color = if (isMe) RadarCyan else DarkSurface,
                            modifier = Modifier.widthIn(max = 290.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                if (msg.messageType == MessageType.AUDIO) {
                                    // Player de Áudio Criptografado
                                    val isPlaying = playingAudioId == msg.id
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (isPlaying) {
                                                    playingAudioId = null
                                                } else {
                                                    playingAudioId = msg.id
                                                    scope.launch {
                                                        delay((msg.audioDurationSeconds.coerceAtLeast(2) * 1000).toLong())
                                                        if (playingAudioId == msg.id) {
                                                            playingAudioId = null
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(if (isMe) DeepBlack.copy(alpha = 0.2f) else DarkCardElevated)
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                                                tint = if (isMe) DeepBlack else RadarCyan,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Column {
                                            // Barras de visualização de áudio
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                modifier = Modifier.height(18.dp)
                                            ) {
                                                val heights = listOf(8, 14, 10, 18, 12, 16, 10, 14, 6, 12, 18, 14, 8)
                                                heights.forEachIndexed { index, barH ->
                                                    val activeHeight = if (isPlaying) {
                                                        (barH + (index * 2) % 6).dp
                                                    } else {
                                                        barH.dp
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .width(3.dp)
                                                            .height(activeHeight)
                                                            .clip(RoundedCornerShape(2.dp))
                                                            .background(
                                                                if (isMe) DeepBlack.copy(alpha = 0.7f) else RadarCyan.copy(alpha = 0.8f)
                                                            )
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(2.dp))

                                            Text(
                                                text = if (isPlaying) "Reproduzindo áudio..." else "Áudio Cifrado • 0:${String.format("%02d", msg.audioDurationSeconds.coerceAtLeast(3))}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = if (isMe) DeepBlack.copy(alpha = 0.8f) else TextSecondary,
                                                    fontSize = 10.sp
                                                )
                                            )
                                        }
                                    }
                                } else {
                                    // Mensagem de Texto Normal
                                    Text(
                                        text = msg.text,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = if (isMe) DeepBlack else TextPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = if (isMe) DeepBlack.copy(alpha = 0.6f) else TextSecondary,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Barra de Gravação de Áudio Ativa
            if (isRecordingAudio) {
                Surface(
                    color = DarkSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(AlertRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Gravando áudio seguro: 0:${String.format("%02d", recordingSeconds)}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Cancelar Gravação
                            TextButton(
                                onClick = {
                                    isRecordingAudio = false
                                    recordingSeconds = 0
                                }
                            ) {
                                Text("Cancelar", color = AlertRed)
                            }

                            // Enviar Áudio Gravado
                            Button(
                                onClick = {
                                    val finalDur = recordingSeconds.coerceAtLeast(2)
                                    isRecordingAudio = false
                                    recordingSeconds = 0
                                    onSendAudioMessage(person, finalDur)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = RadarCyan)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Enviar Áudio",
                                    tint = DeepBlack,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Enviar", color = DeepBlack, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                // Barra Padrão de Entrada de Mensagem com navigationBarsPadding()
                Surface(
                    color = DarkSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Mensagem cifrada...") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarCyan,
                                unfocusedBorderColor = DarkCardElevated
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        if (inputText.isNotBlank()) {
                            // Botão de Enviar Texto
                            IconButton(
                                onClick = {
                                    onSendMessage(inputText)
                                    inputText = ""
                                },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = RadarCyan)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Enviar",
                                    tint = DeepBlack
                                )
                            }
                        } else {
                            // Botão de Gravar Áudio (Microfone)
                            IconButton(
                                onClick = {
                                    if (isMutualOrFamily) {
                                        isRecordingAudio = true
                                    } else {
                                        restrictedFeatureDialog = "Mensagem de Áudio"
                                    }
                                },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (isMutualOrFamily) RadarCyan else DarkCardElevated
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Gravar Áudio",
                                    tint = if (isMutualOrFamily) DeepBlack else TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Diálogo para convidar/vincular papel de familiar com confirmação mútua
        if (showFamilyRoleDialog) {
            AlertDialog(
                onDismissRequest = { showFamilyRoleDialog = false },
                title = { Text("Convidar ${person.alias} como Familiar", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "A rede familiar requer confirmação mútua. Ao escolher o papel, um convite criptografado será enviado para ${person.alias} aceitar.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        FamilyRole.values().forEach { role ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = DarkCard,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onRequestFamilyRole(person, role)
                                        showFamilyRoleDialog = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = role.label,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showFamilyRoleDialog = false }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }

        // Diálogo explicativo quando tenta usar recurso restrito sem conexão mútua ou familiar
        restrictedFeatureDialog?.let { featureName ->
            AlertDialog(
                onDismissRequest = { restrictedFeatureDialog = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Recurso Protegido: $featureName",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Chamadas de voz, vídeo e áudios gravados são exclusivos para Familiares ou Contatos Marcados Mutuamente.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Text(
                            text = "Para sua privacidade, estranhos no radar local possuem acesso restrito apenas a mensagens de texto criptografadas.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = "Assim que ambos se marcarem ou confirmarem parentesco, todos os recursos são liberados a qualquer distância!",
                            style = MaterialTheme.typography.bodySmall.copy(color = EmeraldGreen)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            restrictedFeatureDialog = null
                            onToggleMarkPerson(person)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RadarCyan,
                            contentColor = DeepBlack
                        )
                    ) {
                        Text("Marcar Conexão", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { restrictedFeatureDialog = null }) {
                        Text("Entendido", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }
    }
}
