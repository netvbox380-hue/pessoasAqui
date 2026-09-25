package br.com.pessoasaqui.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import br.com.pessoasaqui.core.media.VoiceNoteManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.FamilyRole
import br.com.pessoasaqui.domain.model.MessageType
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

fun uriToBase64Image(context: android.content.Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return null
        inputStream?.close()

        val maxDim = 800
        val width = originalBitmap.width
        val height = originalBitmap.height
        val scaledBitmap = if (width > maxDim || height > maxDim) {
            val ratio = width.toFloat() / height.toFloat()
            if (width > height) {
                android.graphics.Bitmap.createScaledBitmap(originalBitmap, maxDim, (maxDim / ratio).toInt(), true)
            } else {
                android.graphics.Bitmap.createScaledBitmap(originalBitmap, (maxDim * ratio).toInt(), maxDim, true)
            }
        } else {
            originalBitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, outputStream)
        val byteArray = outputStream.toByteArray()
        Base64.encodeToString(byteArray, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }
}

fun uriToBase64Doc(context: android.content.Context, uri: Uri): Pair<String, Long>? {
    return try {
        var fileName = "documento.pdf"
        var fileSize = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: "documento.pdf"
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: return null
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        Pair("$fileName:${if (fileSize > 0) fileSize else bytes.size.toLong()}:$b64", bytes.size.toLong())
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    person: NearbyPerson,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStartCall: (NearbyPerson, Boolean) -> Unit = { _, _ -> },
    onSendAudioMessage: (NearbyPerson, Int, String?) -> Unit = { _, _, _ -> },
    onSendImageMessage: (NearbyPerson, String) -> Unit = { _, _ -> },
    onSendDocumentMessage: (NearbyPerson, String, String, Long) -> Unit = { _, _, _, _ -> },
    onToggleMarkPerson: (NearbyPerson) -> Unit = { _ -> },
    onAcceptMutualConnection: (NearbyPerson) -> Unit = { _ -> },
    onRequestFamilyRole: (NearbyPerson, FamilyRole) -> Unit = { _, _ -> },
    onGenerateFamilyRecovery: (NearbyPerson) -> Unit = { _ -> },
    onBlockUser: (String) -> Unit = { _ -> },
    onReportUser: (String) -> Unit = { _ -> }
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showFamilyRoleDialog by remember { mutableStateOf(false) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    var restrictedFeatureDialog by remember { mutableStateOf<String?>(null) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var playingAudioId by remember { mutableStateOf<String?>(null) }

    val voiceNoteManager = remember { VoiceNoteManager() }
    DisposableEffect(Unit) {
        onDispose {
            voiceNoteManager.stopRecording()
            voiceNoteManager.stopPlayback()
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val started = voiceNoteManager.startRecording(context)
            if (started) isRecordingAudio = true
        }
    }

    var pendingCallType by remember { mutableStateOf<Boolean?>(null) } // true for video, false for voice
    val callPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val audioGranted = permissionsMap[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val isVideo = pendingCallType ?: false
        val cameraGranted = if (isVideo) {
            permissionsMap[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } else true

        if (audioGranted && cameraGranted) {
            onStartCall(person, isVideo)
        }
        pendingCallType = null
    }

    val isMutualOrFamily = person.isMutualConnection || person.isFamily
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val base64 = uriToBase64Image(context, it)
                if (base64 != null) {
                    onSendImageMessage(person, base64)
                }
            }
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val res = uriToBase64Doc(context, it)
                if (res != null) {
                    val parts = res.first.split(":", limit = 3)
                    val name = parts.getOrNull(0) ?: "documento.pdf"
                    val b64 = parts.getOrNull(2) ?: ""
                    onSendDocumentMessage(person, name, b64, res.second)
                }
            }
        }
    }

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
                                    else -> "Pessoas no local • ~${person.estimatedDistanceMeters}m"
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
                    // Ícone de Chamada de Voz
                    IconButton(onClick = {
                        if (isMutualOrFamily) {
                            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio) {
                                onStartCall(person, false)
                            } else {
                                pendingCallType = false
                                callPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            }
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

                    // Ícone de Chamada de Vídeo
                    IconButton(onClick = {
                        if (isMutualOrFamily) {
                            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio && hasCamera) {
                                onStartCall(person, true)
                            } else {
                                pendingCallType = true
                                callPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                            }
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
                                    text = when {
                                        person.isMutualConnection -> "★ Desfazer Conexão Mútua"
                                        person.isMarkingMe && !person.isMarkedByMe -> "★ Aceitar Conexão Mútua"
                                        person.isMarkedByMe -> "★ Desmarcar Conexão"
                                        else -> "☆ Marcar Conexão Mútua"
                                    },
                                    color = when {
                                        person.isMutualConnection -> EmeraldGreen
                                        person.isMarkingMe && !person.isMarkedByMe -> EmeraldGreen
                                        else -> RadarCyan
                                    },
                                    fontWeight = if (person.isMarkingMe || person.isMutualConnection) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                if (person.isMarkingMe && !person.isMarkedByMe) {
                                    onAcceptMutualConnection(person)
                                } else {
                                    onToggleMarkPerson(person)
                                }
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
                                text = "Pessoas no local • Somente Texto Criptografado",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = WarningAmber,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Chamadas de voz, vídeo, áudios e envio de fotos/documentos são liberados quando ambos se marcam mutuamente ou confirmam parentesco.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (person.isMarkingMe && !person.isMarkedByMe) {
                                Button(
                                    onClick = { onAcceptMutualConnection(person) },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        text = "★ Aceitar Conexão Mútua",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onToggleMarkPerson(person) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        text = if (person.isMarkedByMe) "★ Marcado por você (Aguardando)" else "☆ Marcar Conexão",
                                        fontSize = 11.sp,
                                        color = RadarCyan
                                    )
                                }
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
                                "🛡️ Familiar Conectado • Chamadas, vídeos, áudios e arquivos liberados (E2EE)"
                            else
                                "🤝 Conexão Mútua Confirmada • Chamadas, vídeos, áudios e arquivos liberados (E2EE)",
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
                                when (msg.messageType) {
                                    MessageType.AUDIO -> {
                                        // Player de Áudio Criptografado
                                        val isPlaying = playingAudioId == msg.id
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    if (isPlaying) {
                                                        voiceNoteManager.stopPlayback()
                                                        playingAudioId = null
                                                    } else {
                                                        playingAudioId = msg.id
                                                        if (!msg.mediaBase64.isNullOrBlank()) {
                                                            val ok = voiceNoteManager.playVoiceNote(context, msg.id, msg.mediaBase64) {
                                                                playingAudioId = null
                                                            }
                                                            if (!ok) {
                                                                scope.launch {
                                                                    delay((msg.audioDurationSeconds.coerceAtLeast(2) * 1000).toLong())
                                                                    if (playingAudioId == msg.id) playingAudioId = null
                                                                }
                                                            }
                                                        } else {
                                                            scope.launch {
                                                                delay((msg.audioDurationSeconds.coerceAtLeast(2) * 1000).toLong())
                                                                if (playingAudioId == msg.id) {
                                                                    playingAudioId = null
                                                                }
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
                                    }

                                    MessageType.IMAGE -> {
                                        // Foto / Imagem Cifrada
                                        val bitmap = remember(msg.mediaBase64) {
                                            try {
                                                if (!msg.mediaBase64.isNullOrBlank()) {
                                                    val decoded = Base64.decode(msg.mediaBase64, Base64.DEFAULT)
                                                    BitmapFactory.decodeByteArray(decoded, 0, decoded.size)?.asImageBitmap()
                                                } else null
                                            } catch (_: Exception) { null }
                                        }

                                        if (bitmap != null) {
                                            Image(
                                                bitmap = bitmap,
                                                contentDescription = "Foto Cifrada",
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 220.dp)
                                                    .clip(RoundedCornerShape(10.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "📷 Foto Cifrada Ponta a Ponta",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = if (isMe) DeepBlack.copy(alpha = 0.8f) else EmeraldGreen,
                                                    fontSize = 10.sp
                                                )
                                            )
                                        } else {
                                            Text(
                                                text = "📷 [Imagem Cifrada Recebida]",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    color = if (isMe) DeepBlack else TextPrimary
                                                )
                                            )
                                        }
                                    }

                                    MessageType.DOCUMENT -> {
                                        // Documento / Arquivo Cifrado
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = null,
                                                tint = if (isMe) DeepBlack else RadarCyan,
                                                modifier = Modifier.size(30.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = msg.fileName ?: "Documento",
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isMe) DeepBlack else TextPrimary
                                                    )
                                                )
                                                val sizeLabel = if (msg.fileSizeBytes > 0) "${msg.fileSizeBytes / 1024} KB" else "Arquivo anexado"
                                                Text(
                                                    text = "$sizeLabel • Cifrado E2EE",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = if (isMe) DeepBlack.copy(alpha = 0.7f) else TextSecondary,
                                                        fontSize = 10.sp
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    else -> {
                                        // Mensagem de Texto Padrão
                                        Text(
                                            text = msg.text,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = if (isMe) DeepBlack else TextPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                    }
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
                            TextButton(
                                onClick = {
                                    isRecordingAudio = false
                                    recordingSeconds = 0
                                    voiceNoteManager.cancelRecording()
                                }
                            ) {
                                Text("Cancelar", color = AlertRed)
                            }

                            Button(
                                onClick = {
                                    val finalDur = recordingSeconds.coerceAtLeast(1)
                                    isRecordingAudio = false
                                    recordingSeconds = 0
                                    val recorded = voiceNoteManager.stopRecording()
                                    if (recorded != null) {
                                        onSendAudioMessage(person, recorded.first, recorded.second)
                                    } else {
                                        onSendAudioMessage(person, finalDur, null)
                                    }
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
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botão de Anexo (Fotos e Documentos)
                        IconButton(
                            onClick = {
                                if (isMutualOrFamily) {
                                    showAttachmentOptions = true
                                } else {
                                    restrictedFeatureDialog = "Envio de Fotos e Documentos"
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "Anexar Foto ou Documento",
                                tint = if (isMutualOrFamily) RadarCyan else TextSecondary.copy(alpha = 0.6f)
                            )
                        }

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

                        Spacer(modifier = Modifier.width(6.dp))

                        if (inputText.isNotBlank()) {
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
                            IconButton(
                                onClick = {
                                    if (isMutualOrFamily) {
                                        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                        if (hasAudio) {
                                            val started = voiceNoteManager.startRecording(context)
                                            if (started) isRecordingAudio = true
                                        } else {
                                            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
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

        // Diálogo para escolha de anexo (Foto ou Documento)
        if (showAttachmentOptions) {
            AlertDialog(
                onDismissRequest = { showAttachmentOptions = false },
                title = { Text("Anexar à Conversa Cifrada", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAttachmentOptions = false
                                    imagePickerLauncher.launch("image/*")
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = RadarCyan)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Enviar Foto / Imagem", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Galeria de fotos com cifragem ponta a ponta", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAttachmentOptions = false
                                    documentPickerLauncher.launch("*/*")
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = EmeraldGreen)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Enviar Documento", fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("PDFs e arquivos com transmissão protegida", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showAttachmentOptions = false }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
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
                            text = "Chamadas de voz, vídeo, áudios e envio de fotos/documentos são exclusivos para Familiares ou Contatos Marcados Mutuamente.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Text(
                            text = "Para sua privacidade e segurança, pessoas no local possuem acesso restrito apenas a mensagens de texto criptografadas.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = "Assim que ambos se marcarem ou confirmarem parentesco, todos os recursos multimídia são liberados a qualquer distância!",
                            style = MaterialTheme.typography.bodySmall.copy(color = EmeraldGreen)
                        )
                    }
                },
                confirmButton = {
                    val canAccept = person.isMarkingMe && !person.isMarkedByMe
                    Button(
                        onClick = {
                            restrictedFeatureDialog = null
                            if (canAccept) {
                                onAcceptMutualConnection(person)
                            } else {
                                onToggleMarkPerson(person)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (canAccept) EmeraldGreen else RadarCyan,
                            contentColor = if (canAccept) TextPrimary else DeepBlack
                        )
                    ) {
                        Text(
                            text = if (canAccept) "★ Aceitar Conexão Mútua" else "Marcar Conexão",
                            fontWeight = FontWeight.Bold
                        )
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
