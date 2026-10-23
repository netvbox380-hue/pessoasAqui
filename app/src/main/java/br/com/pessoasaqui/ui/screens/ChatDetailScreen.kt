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
import android.widget.Toast
import br.com.pessoasaqui.core.media.VoiceNoteManager
import br.com.pessoasaqui.core.media.DocumentViewerHelper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.ContactTag
import br.com.pessoasaqui.domain.model.FamilyRole
import br.com.pessoasaqui.domain.model.MessageType
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.RecurringReminder
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

        // Qualidade Cristalina (2K Quad HD - 2048px)
        val maxDim = 2048
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
        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, outputStream)
        val byteArray = outputStream.toByteArray()
        Base64.encodeToString(byteArray, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }
}

fun uriToBase64Doc(context: android.content.Context, uri: Uri, maxBytes: Long = 40L * 1024L * 1024L): Pair<String, Long>? {
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
        if (fileSize > maxBytes) {
            return null
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > maxBytes) {
            return null
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        Pair("$fileName:${if (fileSize > 0) fileSize else bytes.size.toLong()}:$b64", bytes.size.toLong())
    } catch (_: Exception) {
        null
    }
}

private fun isSameDay(ts1: Long, ts2: Long): Boolean {
    val cal1 = Calendar.getInstance().apply { timeInMillis = ts1 }
    val cal2 = Calendar.getInstance().apply { timeInMillis = ts2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun formatMessageDateHeader(timestampMs: Long): String {
    val nowCal = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = timestampMs }

    val isToday = nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)
    if (isToday) return "Hoje"

    nowCal.add(Calendar.DAY_OF_YEAR, -1)
    val isYesterday = nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "Ontem"

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    return if (msgCal.get(Calendar.YEAR) == currentYear) {
        SimpleDateFormat("d 'de' MMMM", Locale("pt", "BR")).format(Date(timestampMs))
    } else {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestampMs))
    }
}

private fun formatMessageTime(timestampMs: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
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
    onReportUser: (String) -> Unit = { _ -> },
    onReportUserWithDetails: ((String, String, String, List<ChatMessage>) -> Unit)? = null,
    recurringReminders: List<RecurringReminder> = emptyList(),
    onSaveReminder: (RecurringReminder) -> Unit = {},
    onDeleteReminder: (String) -> Unit = {},
    onToggleReminder: (String, Boolean) -> Unit = { _, _ -> },
    onConfirmReminder: (String) -> Unit = {},
    allConnectedPeople: List<NearbyPerson> = emptyList(),
    maxAllowedBroadcastRecipients: Int = 3,
    remainingBroadcastCooldownMs: Long = 0L,
    onSendBroadcast: ((List<String>, String, (Int, Int) -> Unit, (Boolean, String) -> Unit) -> Unit)? = null,
    isCreatingInvite: Boolean = false,
    onCreateInviteLink: () -> Unit = {},
    onRedeemInviteLink: ((String, (Boolean, String?, NearbyPerson?) -> Unit) -> Unit)? = null,
    isVip: Boolean = false,
    onUpgradeToVip: () -> Unit = {},
    contactTags: List<ContactTag> = emptyList(),
    contactTagAssignments: Map<String, Set<String>> = emptyMap(),
    onToggleContactTag: (String, String) -> Unit = { _, _ -> },
    onCreateContactTag: (String, String) -> Unit = { _, _ -> },
    onDeleteContactTag: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showFamilyRoleDialog by remember { mutableStateOf(false) }
    var showRemindersDialog by remember { mutableStateOf(false) }
    var reminderInitialText by remember { mutableStateOf("") }
    var showResourceDrawer by remember { mutableStateOf(false) }
    var showBroadcastDialog by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showPasteInviteInChat by remember { mutableStateOf(false) }
    var showTagSelectorDialog by remember { mutableStateOf(false) }
    var restrictedFeatureDialog by remember { mutableStateOf<String?>(null) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var playingAudioId by remember { mutableStateOf<String?>(null) }
    var fullscreenImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var fullscreenImageSender by remember { mutableStateOf("") }

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
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val selected = uris.take(30)
        scope.launch(Dispatchers.IO) {
            selected.forEach { uri ->
                val base64 = uriToBase64Image(context, uri)
                if (base64 != null) {
                    onSendImageMessage(person, base64)
                    delay(200)
                }
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val selected = uris.take(10)
        scope.launch(Dispatchers.IO) {
            selected.forEach { uri ->
                val res = uriToBase64Doc(context, uri, maxBytes = 40L * 1024L * 1024L)
                if (res != null) {
                    val parts = res.first.split(":", limit = 3)
                    val name = parts.getOrNull(0) ?: "video.mp4"
                    val b64 = parts.getOrNull(2) ?: ""
                    onSendDocumentMessage(person, name, b64, res.second)
                    delay(250)
                }
            }
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val selected = uris.take(30)
        scope.launch(Dispatchers.IO) {
            selected.forEach { uri ->
                val res = uriToBase64Doc(context, uri, maxBytes = 40L * 1024L * 1024L)
                if (res != null) {
                    val parts = res.first.split(":", limit = 3)
                    val name = parts.getOrNull(0) ?: "documento.pdf"
                    val b64 = parts.getOrNull(2) ?: ""
                    onSendDocumentMessage(person, name, b64, res.second)
                    delay(200)
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = person.alias,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                                val pCleanId = person.technicalIdentityHash.ifBlank { person.id }.removePrefix("peer-").trim().uppercase()
                                val pTagIds = contactTagAssignments[pCleanId] ?: emptySet()
                                val pTags = contactTags.filter { pTagIds.contains(it.id) }
                                pTags.take(2).forEach { t ->
                                    Spacer(modifier = Modifier.width(4.dp))
                                    val tColor = try { Color(android.graphics.Color.parseColor(t.colorHex)) } catch (_: Exception) { RadarCyan }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = tColor.copy(alpha = 0.2f),
                                        border = BorderStroke(1.dp, tColor.copy(alpha = 0.6f))
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
                            text = {
                                Text(
                                    text = "📢 Mensagem de Transmissão",
                                    color = RadarCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Campaign,
                                    contentDescription = null,
                                    tint = RadarCyan
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                showBroadcastDialog = true
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "⏰ Lembretes e Agendamento",
                                    color = FamilyPurple,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = FamilyPurple
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                reminderInitialText = inputText
                                showRemindersDialog = true
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "🏷️ Etiquetas do Contato",
                                    color = RadarCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Label,
                                    contentDescription = null,
                                    tint = RadarCyan
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                showTagSelectorDialog = true
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
                                showReportDialog = true
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
                itemsIndexed(messages) { index, msg ->
                    val isMe = msg.isFromMe
                    val showDateHeader = index == 0 || !isSameDay(messages[index - 1].timestampMs, msg.timestampMs)

                    if (showDateHeader) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = DarkCardElevated.copy(alpha = 0.95f),
                                border = BorderStroke(0.5.dp, TextSecondary.copy(alpha = 0.25f))
                            ) {
                                Text(
                                    text = formatMessageDateHeader(msg.timestampMs),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = TextSecondary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

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
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        fullscreenImageBitmap = bitmap
                                                        fullscreenImageSender = msg.senderAlias
                                                    }
                                            ) {
                                                Image(
                                                    bitmap = bitmap,
                                                    contentDescription = "Foto Cifrada (Toque para ampliar)",
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 240.dp),
                                                    contentScale = ContentScale.Crop
                                                )

                                                // Badge discreto indicando que a imagem abre em tela cheia com zoom
                                                Surface(
                                                    color = DeepBlack.copy(alpha = 0.7f),
                                                    shape = RoundedCornerShape(topStart = 8.dp),
                                                    modifier = Modifier.align(Alignment.BottomEnd)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.ZoomIn,
                                                            contentDescription = "Ampliar",
                                                            tint = RadarCyan,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(3.dp))
                                                        Text(
                                                            text = "Toque para ampliar",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = TextPrimary,
                                                                fontSize = 9.sp
                                                            )
                                                        )
                                                    }
                                                }
                                            }
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
                                        // Documento / Arquivo Cifrado com Suporte Completo à Abertura (PDF, XML, DOC, etc.)
                                        val fileName = msg.fileName ?: "documento.pdf"
                                        val fileExt = fileName.substringAfterLast('.', "DOC").uppercase(Locale.ROOT)
                                        val extColor = when (fileExt) {
                                            "PDF" -> AlertRed
                                            "XML" -> WarningAmber
                                            "DOC", "DOCX" -> RadarCyan
                                            "XLS", "XLSX" -> EmeraldGreen
                                            else -> FamilyPurple
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isMe) DeepBlack.copy(alpha = 0.15f) else DarkCardElevated,
                                            border = BorderStroke(1.dp, if (isMe) DeepBlack.copy(alpha = 0.25f) else extColor.copy(alpha = 0.4f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    DocumentViewerHelper.openDocument(context, fileName, msg.mediaBase64)
                                                }
                                                .padding(vertical = 2.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Badge com tipo do documento (PDF, XML, etc.)
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = extColor.copy(alpha = 0.2f),
                                                        border = BorderStroke(1.dp, extColor.copy(alpha = 0.5f))
                                                    ) {
                                                        Text(
                                                            text = fileExt.take(4),
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = if (isMe) DeepBlack else extColor,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                fontSize = 10.sp
                                                            ),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.width(8.dp))

                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = fileName,
                                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (isMe) DeepBlack else TextPrimary
                                                            ),
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                        val sizeLabel = if (msg.fileSizeBytes > 0) "${msg.fileSizeBytes / 1024} KB" else "Arquivo seguro"
                                                        Text(
                                                            text = "$sizeLabel • E2EE Cifrado",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = if (isMe) DeepBlack.copy(alpha = 0.7f) else TextSecondary,
                                                                fontSize = 10.sp
                                                            )
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(6.dp))

                                                // Rodapé do documento indicando toque para abrir
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.End,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        text = "Toque para abrir documento",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            color = if (isMe) DeepBlack.copy(alpha = 0.85f) else extColor,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 10.sp
                                                        )
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.OpenInNew,
                                                        contentDescription = "Abrir",
                                                        tint = if (isMe) DeepBlack.copy(alpha = 0.85f) else extColor,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    else -> {
                                        // Mensagem de Texto Padrão (com suporte responsivo a badge de Transmissão)
                                        val isBroadcast = msg.text.startsWith("📢 [Transmissão]: ") || msg.text.startsWith("📢 [Transmissao]: ")
                                        val cleanText = if (isBroadcast) {
                                            msg.text.removePrefix("📢 [Transmissão]: ").removePrefix("📢 [Transmissao]: ").trim()
                                        } else {
                                            msg.text
                                        }

                                        if (isBroadcast) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isMe) DeepBlack.copy(alpha = 0.12f) else RadarCyan.copy(alpha = 0.15f),
                                                modifier = Modifier.padding(bottom = 6.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Campaign,
                                                        contentDescription = null,
                                                        tint = if (isMe) DeepBlack else RadarCyan,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "TRANSMISSÃO",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 10.sp,
                                                            color = if (isMe) DeepBlack else RadarCyan
                                                        )
                                                    )
                                                }
                                            }
                                        }

                                        Text(
                                            text = cleanText,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = if (isMe) DeepBlack else TextPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatMessageTime(msg.timestampMs),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (isMe) DeepBlack.copy(alpha = 0.65f) else TextSecondary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Normal
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Cifrado Ponta a Ponta",
                                        tint = if (isMe) DeepBlack.copy(alpha = 0.65f) else TextSecondary,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    if (isMe) {
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Icon(
                                            imageVector = Icons.Default.DoneAll,
                                            contentDescription = "Entregue",
                                            tint = DeepBlack.copy(alpha = 0.65f),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Barra de Gravação de Áudio Ativa Responsiva (Android 14 & 16)
            if (isRecordingAudio) {
                val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse_alpha"
                )
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse_scale"
                )
                val wavePhase by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 6.28f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "wave_phase"
                )

                Surface(
                    color = DarkSurface,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Lado Esquerdo: Ponto vermelho pulsante + Timer 00:08
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                                    .clip(CircleShape)
                                    .background(AlertRed.copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val minutes = recordingSeconds / 60
                            val seconds = recordingSeconds % 60
                            Text(
                                text = String.format("%02d:%02d", minutes, seconds),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            )
                        }

                        // Centro: Visualizador de Ondas de Áudio (Soundwave bars oscilantes com weight flexível)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val barCount = 9
                            for (i in 0 until barCount) {
                                val sinFactor = kotlin.math.sin(wavePhase + i * 0.7f)
                                val normalizedHeight = (6.dp + (sinFactor.coerceAtLeast(0.1f) * 16).dp)
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(normalizedHeight)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            if (i % 2 == 0) RadarCyan.copy(alpha = 0.85f) else AlertRed.copy(alpha = 0.75f)
                                        )
                                )
                            }
                        }

                        // Lado Direito: Botão Descartar (Lixeira vermelha) + Botão Enviar (Ciano circular)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Descartar / Cancelar
                            IconButton(
                                onClick = {
                                    isRecordingAudio = false
                                    recordingSeconds = 0
                                    voiceNoteManager.cancelRecording()
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(AlertRed.copy(alpha = 0.15f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Cancelar Gravação",
                                    tint = AlertRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Enviar
                            IconButton(
                                onClick = {
                                    isRecordingAudio = false
                                    recordingSeconds = 0
                                    val recorded = voiceNoteManager.stopRecording()
                                    if (recorded != null) {
                                        onSendAudioMessage(person, recorded.first, recorded.second)
                                    } else {
                                        Toast.makeText(context, "Não foi possível gravar áudio. Tente novamente.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(RadarCyan)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Enviar Áudio Cifrado",
                                    tint = DeepBlack,
                                    modifier = Modifier.size(20.dp)
                                )
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
                        // Botão da Gaveta de Recursos (Fotos, Documentos, Lembretes, Transmissão)
                        IconButton(
                            onClick = {
                                showResourceDrawer = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Widgets,
                                contentDescription = "Gaveta de Recursos",
                                tint = if (isMutualOrFamily) RadarCyan else TextSecondary.copy(alpha = 0.8f)
                            )
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Mensagem cifrada...") },
                            leadingIcon = {
                                IconButton(
                                    onClick = { showEmojiPicker = !showEmojiPicker }
                                ) {
                                    Icon(
                                        imageVector = if (showEmojiPicker) Icons.Default.Keyboard else Icons.Default.SentimentSatisfiedAlt,
                                        contentDescription = if (showEmojiPicker) "Teclado" else "Emojis",
                                        tint = if (showEmojiPicker) RadarCyan else TextSecondary.copy(alpha = 0.85f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            },
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
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(RadarCyan)
                                    .pointerInput(inputText) {
                                        detectTapGestures(
                                            onTap = {
                                                onSendMessage(inputText)
                                                inputText = ""
                                            },
                                            onLongPress = {
                                                Toast.makeText(
                                                    context,
                                                    "⏰ Agendando lembrete com o texto digitado...",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                reminderInitialText = inputText
                                                showRemindersDialog = true
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Enviar (Toque) / Agendar Lembrete (Premir e Segurar)",
                                    tint = DeepBlack,
                                    modifier = Modifier.size(20.dp)
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

                if (showEmojiPicker) {
                    EmojiPickerPanel(
                        onEmojiSelected = { emoji ->
                            inputText += emoji
                        },
                        onBackspace = {
                            if (inputText.isNotEmpty()) {
                                val codePoints = inputText.codePoints().toArray()
                                if (codePoints.isNotEmpty()) {
                                    val sb = StringBuilder()
                                    for (i in 0 until codePoints.size - 1) {
                                        sb.appendCodePoint(codePoints[i])
                                    }
                                    inputText = sb.toString()
                                }
                            }
                        },
                        onClose = { showEmojiPicker = false }
                    )
                }
            }
        }

        // Gaveta de Recursos (Fotos, Documentos, Lembretes, Transmissão)
        if (showResourceDrawer) {
            AlertDialog(
                onDismissRequest = { showResourceDrawer = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Widgets,
                            contentDescription = null,
                            tint = RadarCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Gaveta de Recursos", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 1. Fotos / Imagens (Múltipla Seleção em HD)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showResourceDrawer = false
                                    if (isMutualOrFamily) {
                                        imagePickerLauncher.launch("image/*")
                                    } else {
                                        restrictedFeatureDialog = "Envio de Fotos e Imagens"
                                    }
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
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Enviar Fotos (Alta Definição)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Marque até 30 fotos com qualidade cristalina HD", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }

                        // 2. Vídeos (Múltipla Seleção Criptografada)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showResourceDrawer = false
                                    if (isMutualOrFamily) {
                                        videoPickerLauncher.launch("video/*")
                                    } else {
                                        restrictedFeatureDialog = "Envio de Vídeos"
                                    }
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
                                        .background(WarningAmber.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Enviar Vídeos", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Múltiplos vídeos cifrados ponta a ponta (até 40MB)", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }

                        // 3. Documentos (Múltipla Seleção)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showResourceDrawer = false
                                    if (isMutualOrFamily) {
                                        documentPickerLauncher.launch("*/*")
                                    } else {
                                        restrictedFeatureDialog = "Envio de Documentos"
                                    }
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
                                    Icon(Icons.Default.Description, contentDescription = null, tint = EmeraldGreen, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Enviar Documentos / Arquivos", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Marque múltiplos PDFs e arquivos protegidos", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }

                        // 4. Etiquetas do Contato
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showResourceDrawer = false
                                    showTagSelectorDialog = true
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
                                    Icon(Icons.Default.Label, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("🏷️ Etiquetas do Contato", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Classifique este contato para transmissões segmentadas", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }

                        // 3. Criar / Agendar Lembrete
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showResourceDrawer = false
                                    reminderInitialText = inputText
                                    showRemindersDialog = true
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
                                        .background(FamilyPurple.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = FamilyPurple, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("⏰ Agendar Lembrete", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Alarme recorrente e persistência de 3 dias", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }

                        // 4. Mensagem de Transmissão
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showResourceDrawer = false
                                    showBroadcastDialog = true
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
                                    Icon(Icons.Default.Campaign, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("📢 Transmissão para Contatos", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Envio simultâneo para conexões mútuas", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }

                        // 5. Criar Convite Criptografado
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isCreatingInvite) {
                                    showResourceDrawer = false
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
                                    Text("🔗 Criar Convite Criptografado", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Link seguro de uso único para compartilhar", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }

                        // 6. Colar Link de Convite
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showResourceDrawer = false
                                    showPasteInviteInChat = true
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
                                    Text("📥 Colar Link de Convite", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Conectar com link recebido de outra pessoa", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }

                        // 7. Sessão Emojis & Reações
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showResourceDrawer = false
                                    showEmojiPicker = true
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
                                        .background(WarningAmber.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.SentimentSatisfiedAlt, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("😀 Sessão Emojis e Reações", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                    Text("Carinhas, gestos e os emojis mais usados", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showResourceDrawer = false }) {
                        Text("Fechar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }

        // Diálogo para Colar Convite direto da conversa
        if (showPasteInviteInChat) {
            var pastedInviteText by remember { mutableStateOf("") }
            var isRedeeming by remember { mutableStateOf(false) }
            var pasteError by remember { mutableStateOf<String?>(null) }
            val clipboard = LocalClipboardManager.current

            AlertDialog(
                onDismissRequest = {
                    if (!isRedeeming) showPasteInviteInChat = false
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
                            onRedeemInviteLink?.invoke(input) { success, msg, _ ->
                                isRedeeming = false
                                if (success) {
                                    showPasteInviteInChat = false
                                    Toast.makeText(context, "Conexão mútua estabelecida com sucesso!", Toast.LENGTH_SHORT).show()
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
                        onClick = { showPasteInviteInChat = false },
                        enabled = !isRedeeming
                    ) {
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

        if (fullscreenImageBitmap != null) {
            FullscreenImageViewerDialog(
                bitmap = fullscreenImageBitmap!!,
                senderName = fullscreenImageSender,
                onDismiss = { fullscreenImageBitmap = null }
            )
        }

        if (showRemindersDialog) {
            val targetIdClean = person.technicalIdentityHash.ifBlank { person.id }
            RecurringRemindersDialog(
                reminders = recurringReminders.filter {
                    it.targetId == targetIdClean || it.targetId == person.id || it.targetAlias.equals(person.alias, ignoreCase = true)
                },
                connectedPeople = listOf(person),
                initialRecipient = person,
                initialMessageText = reminderInitialText,
                onSaveReminder = onSaveReminder,
                onDeleteReminder = onDeleteReminder,
                onToggleReminder = onToggleReminder,
                onConfirmReminder = onConfirmReminder,
                isVip = isVip,
                onUpgradeToVip = onUpgradeToVip,
                onDismiss = {
                    showRemindersDialog = false
                    reminderInitialText = ""
                }
            )
        }

        if (showBroadcastDialog) {
            val broadcastRecipients = remember(allConnectedPeople, person) {
                if (allConnectedPeople.isNotEmpty()) allConnectedPeople else listOf(person)
            }
            BroadcastMessageDialog(
                connectedPeople = broadcastRecipients,
                isVip = isVip,
                maxAllowedRecipients = maxAllowedBroadcastRecipients,
                remainingCooldownMs = remainingBroadcastCooldownMs,
                contactTags = contactTags,
                contactTagAssignments = contactTagAssignments,
                onSendBroadcast = { targetIds, msg, onProgress, onResult ->
                    if (onSendBroadcast != null) {
                        onSendBroadcast(targetIds, msg, onProgress, onResult)
                    } else {
                        onResult(false, "Serviço de transmissão indisponível")
                    }
                },
                onUpgradeToVip = onUpgradeToVip,
                onDismiss = { showBroadcastDialog = false }
            )
        }

        if (showTagSelectorDialog) {
            val pCleanId = person.technicalIdentityHash.ifBlank { person.id }.removePrefix("peer-").trim().uppercase()
            val assignedIds = contactTagAssignments[pCleanId] ?: emptySet()
            ContactTagSelectorDialog(
                person = person,
                allTags = contactTags,
                assignedTagIds = assignedIds,
                onToggleTag = { tagId -> onToggleContactTag(pCleanId, tagId) },
                onCreateTag = { name, colorHex -> onCreateContactTag(name, colorHex) },
                onDeleteTag = { tagId -> onDeleteContactTag(tagId) },
                onDismiss = { showTagSelectorDialog = false }
            )
        }

        if (showReportDialog) {
            var selectedViolationType by remember { mutableStateOf("HARASSMENT") }
            var reportReasonText by remember { mutableStateOf("") }
            var attachEvidence by remember { mutableStateOf(true) }

            val violationOptions = listOf(
                "HARASSMENT" to "Assédio ou Inconveniente",
                "SCAM_FRAUD" to "Golpe, Fraude ou Venda Ilícita",
                "PHISHING" to "Phishing (Tentativa de roubo de PIN / Chave)",
                "ILLICIT_CONTENT" to "Conteúdo Ilegal ou Perigoso",
                "SPAM" to "Spam ou Propaganda",
                "OTHER" to "Outro Motivo"
            )

            AlertDialog(
                onDismissRequest = { showReportDialog = false },
                title = {
                    Text(
                        text = "🚨 Denunciar e Bloquear",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Sua denúncia será enviada sigilosamente para a moderação. O usuário será bloqueado imediatamente.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Motivo da denúncia:",
                            color = EmeraldGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        violationOptions.forEach { (typeKey, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedViolationType = typeKey }
                                    .padding(vertical = 3.dp)
                            ) {
                                RadioButton(
                                    selected = (selectedViolationType == typeKey),
                                    onClick = { selectedViolationType = typeKey },
                                    colors = RadioButtonDefaults.colors(selectedColor = EmeraldGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = label, color = Color.White, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = reportReasonText,
                            onValueChange = { reportReasonText = it },
                            label = { Text("Detalhes (opcional)", color = Color.Gray, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldGreen,
                                unfocusedBorderColor = DarkCardElevated,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = attachEvidence,
                                onCheckedChange = { attachEvidence = it },
                                colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Anexar últimas mensagens como prova",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val evidenceList: List<ChatMessage> = if (attachEvidence) messages.takeLast(5) else emptyList()
                            val finalReason = if (reportReasonText.isNotBlank()) reportReasonText else "Denúncia enviada pelo usuário"
                            if (onReportUserWithDetails != null) {
                                onReportUserWithDetails(person.id, selectedViolationType, finalReason, evidenceList)
                            } else {
                                onReportUser(person.id)
                            }
                            Toast.makeText(context, "Denúncia enviada à moderação. Contato bloqueado.", Toast.LENGTH_SHORT).show()
                            showReportDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                    ) {
                        Text("Bloquear & Denunciar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showReportDialog = false }) {
                        Text("Cancelar", color = Color.Gray, fontSize = 12.sp)
                    }
                },
                containerColor = DarkSurface
            )
        }
    }
}

@Composable
fun FullscreenImageViewerDialog(
    bitmap: ImageBitmap,
    senderName: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
        ) {
            // Imagem com suporte a gestos de pinch-to-zoom, pan e duplo toque
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Visualização de Imagem em Tela Cheia",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentScale = ContentScale.Fit
                )
            }

            // Barra superior com controles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar",
                        tint = Color.White
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (senderName.isNotBlank()) "Foto • $senderName" else "Foto Cifrada",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Toque duplo ou use dois dedos para zoom (${String.format("%.1fx", scale)})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.LightGray
                        )
                    )
                }

                if (scale > 1.05f) {
                    IconButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomOutMap,
                            contentDescription = "Redefinir Zoom",
                            tint = Color.White
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(44.dp))
                }
            }
        }
    }
}

/**
 * Painel Interativo de Emojis Rápidos com Categorias e os Mais Usados.
 */
@Composable
fun EmojiPickerPanel(
    onEmojiSelected: (String) -> Unit,
    onBackspace: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableIntStateOf(0) }

    val categories = listOf(
        "⭐" to "Mais Usados",
        "😀" to "Carinhas",
        "👍" to "Gestos",
        "❤️" to "Corações",
        "🔥" to "Símbolos"
    )

    val emojis = remember(selectedCategory) {
        when (selectedCategory) {
            0 -> listOf(
                "😂", "❤️", "😍", "🙏", "👍", "🔥", "🥰", "😭", "😊", "👏",
                "🥳", "😎", "🤣", "🤝", "🚀", "✨", "💯", "😉", "😘", "🥺",
                "👀", "🙌", "💪", "✌️", "🎉", "💖", "💙", "😜", "🤔", "😴",
                "☕", "🍻", "📍", "🔒", "💡", "⚡", "🌟", "🤩", "🤗", "🤤"
            )
            1 -> listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥲", "☺️",
                "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗",
                "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓",
                "😎", "🥸", "🤩", "🥳", "😏", "😒", "😞", "😔", "😟", "😕",
                "🙁", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠",
                "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥",
                "😓", "🤗", "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬",
                "🙄", "😯", "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪",
                "😵", "🤐", "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕"
            )
            2 -> listOf(
                "👍", "👎", "👊", "✊", "🤛", "🤜", "🤞", "✌️", "🤟", "🤘",
                "👌", "🤌", "🤏", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚",
                "🖐️", "🖖", "👋", "🤙", "💪", "🦾", "✍️", "🙏", "🫵", "🤝",
                "👏", "🙌", "👐", "🤲", "🫶"
            )
            3 -> listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
                "❤️‍🔥", "❤️‍🩹", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝",
                "💟", "💌", "💋", "💐", "🌹", "🥀", "🌺", "🌷", "🌸", "💮"
            )
            else -> listOf(
                "🔥", "💯", "✨", "⭐", "🌟", "💥", "🎉", "🎊", "🎈", "🎁",
                "🏆", "🥇", "🚀", "🛸", "💡", "⏰", "⏳", "🔒", "🔑", "🛡️",
                "📡", "📍", "🎯", "💬", "☕", "🍻", "🍕", "🍔", "⚡", "☀️",
                "🌙", "☁️", "🌈", "🎵", "🎶", "🔔", "⚠️", "⛔", "✅", "❌"
            )
        }
    }

    Surface(
        color = DarkSurface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, DarkCardElevated),
        modifier = modifier
            .fillMaxWidth()
            .height(250.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Barra superior de categorias e controles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkCard)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Categorias
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    categories.forEachIndexed { index, (icon, label) ->
                        val isSelected = selectedCategory == index
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) RadarCyan.copy(alpha = 0.2f) else Color.Transparent,
                            border = if (isSelected) BorderStroke(1.dp, RadarCyan.copy(alpha = 0.6f)) else null,
                            modifier = Modifier
                                .clickable { selectedCategory = index }
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = icon,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Botão Backspace
                IconButton(
                    onClick = onBackspace,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Backspace,
                        contentDescription = "Apagar",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Botão Fechar
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar Emojis",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Grade de Emojis
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 42.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(emojis) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onEmojiSelected(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji,
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

