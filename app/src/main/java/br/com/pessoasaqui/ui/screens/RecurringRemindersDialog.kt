package br.com.pessoasaqui.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.RecurringReminder
import br.com.pessoasaqui.domain.model.ReminderFrequency
import br.com.pessoasaqui.ui.theme.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Diálogo de Agendamento e Lembretes (Mensagens Únicas e Recorrentes) do PessoasAqui.
 *
 * Suporta:
 * 1. Mensagens ÚNICAS para qualquer data (amanhã, semana que vem, ou anos à frente sem repetição).
 * 2. Mensagens RECORRENTES (Diária, Semanal, Mensal, Anual) com data de início personalizada
 *    (por exemplo, iniciando em 24 de Agosto de 2027).
 * 3. Envio automático ponta a ponta (E2EE) no chat privado no momento exato do disparo.
 */
@Composable
fun RecurringRemindersDialog(
    reminders: List<RecurringReminder>,
    connectedPeople: List<NearbyPerson>,
    initialRecipient: NearbyPerson? = null,
    initialMessageText: String = "",
    initialTitle: String = "",
    onSaveReminder: (RecurringReminder) -> Unit,
    onDeleteReminder: (String) -> Unit,
    onToggleReminder: (String, Boolean) -> Unit,
    onConfirmReminder: (String) -> Unit = {},
    isVip: Boolean = false,
    onUpgradeToVip: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(if (initialRecipient != null || initialMessageText.isNotBlank()) 1 else 0) }
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault()) }

    val nowCal = remember { Calendar.getInstance() }
    val currentYearNow = nowCal.get(Calendar.YEAR)

    // Form state para novo lembrete/agendamento ou edição de existente
    var editingReminderId by remember { mutableStateOf<String?>(null) }
    var selectedPerson by remember { mutableStateOf(initialRecipient ?: connectedPeople.firstOrNull()) }
    var title by remember { mutableStateOf(initialTitle.ifBlank { "" }) }
    var messageText by remember { mutableStateOf(initialMessageText.ifBlank { "" }) }
    var frequency by remember { mutableStateOf(ReminderFrequency.UNICO) }

    // Data de disparo / início (Ano, Mês, Dia)
    var selectedYear by remember { mutableIntStateOf(currentYearNow) }
    var selectedMonth by remember { mutableIntStateOf(nowCal.get(Calendar.MONTH)) }
    var selectedDayOfMonth by remember { mutableIntStateOf(nowCal.get(Calendar.DAY_OF_MONTH)) }

    // Horário de disparo (Hora, Minuto)
    var hour by remember { mutableIntStateOf((nowCal.get(Calendar.HOUR_OF_DAY) + 1) % 24) }
    var minute by remember { mutableIntStateOf(0) }
    var selectedDayOfWeek by remember { mutableIntStateOf(Calendar.MONDAY) }

    val context = LocalContext.current
    var attachedMediaBase64 by remember { mutableStateOf<String?>(null) }
    var attachedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var autoSend by remember { mutableStateOf(true) }
    var isPersistentUntilConfirmed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(stream)
                stream?.close()
                if (bmp != null) {
                    attachedBitmap = bmp
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    attachedMediaBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                errorMessage = "Falha ao carregar imagem: ${e.message}"
            }
        }
    }

    fun resetForm() {
        editingReminderId = null
        selectedPerson = initialRecipient ?: connectedPeople.firstOrNull()
        title = ""
        messageText = ""
        attachedMediaBase64 = null
        attachedBitmap = null
        frequency = ReminderFrequency.UNICO
        val c = Calendar.getInstance()
        selectedYear = c.get(Calendar.YEAR)
        selectedMonth = c.get(Calendar.MONTH)
        selectedDayOfMonth = c.get(Calendar.DAY_OF_MONTH)
        hour = (c.get(Calendar.HOUR_OF_DAY) + 1) % 24
        minute = 0
        selectedDayOfWeek = Calendar.MONDAY
        autoSend = true
        isPersistentUntilConfirmed = false
        errorMessage = null
    }

    fun startEditing(reminder: RecurringReminder) {
        editingReminderId = reminder.id
        selectedPerson = connectedPeople.find {
            it.technicalIdentityHash == reminder.targetId || it.id == reminder.targetId
        } ?: connectedPeople.find { it.alias.equals(reminder.targetAlias, ignoreCase = true) }
        title = reminder.title
        messageText = reminder.messageText
        attachedMediaBase64 = reminder.mediaBase64
        attachedBitmap = reminder.mediaBase64?.let { b64 ->
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
        }
        frequency = reminder.frequency
        reminder.year?.let { selectedYear = it }
        reminder.month?.let { selectedMonth = it }
        reminder.dayOfMonth?.let { selectedDayOfMonth = it }
        hour = reminder.hour
        minute = reminder.minute
        reminder.dayOfWeek?.let { selectedDayOfWeek = it }
        autoSend = reminder.autoSend
        isPersistentUntilConfirmed = reminder.isPersistentUntilConfirmed
        errorMessage = null
        selectedTab = 1
    }

    val daysOfWeek = remember {
        listOf(
            Calendar.MONDAY to "Seg",
            Calendar.TUESDAY to "Ter",
            Calendar.WEDNESDAY to "Qua",
            Calendar.THURSDAY to "Qui",
            Calendar.FRIDAY to "Sex",
            Calendar.SATURDAY to "Sáb",
            Calendar.SUNDAY to "Dom"
        )
    }

    val months = remember {
        listOf(
            Calendar.JANUARY to "Janeiro",
            Calendar.FEBRUARY to "Fevereiro",
            Calendar.MARCH to "Março",
            Calendar.APRIL to "Abril",
            Calendar.MAY to "Maio",
            Calendar.JUNE to "Junho",
            Calendar.JULY to "Julho",
            Calendar.AUGUST to "Agosto",
            Calendar.SEPTEMBER to "Setembro",
            Calendar.OCTOBER to "Outubro",
            Calendar.NOVEMBER to "Novembro",
            Calendar.DECEMBER to "Dezembro"
        )
    }

    val quickSuggestions = remember {
        listOf(
            "🎂 Aniversário" to "Parabéns pelo seu aniversário! Que Deus te abençoe com muita saúde, paz e realizações!",
            "💊 Tomar Remédio" to "Olá! Passando para te lembrar de tomar seu medicamento no horário certinho.",
            "☀️ Bom Dia" to "Bom dia! Desejo um dia maravilhoso, abençoado e produtivo para você.",
            "👨‍👩‍👧 Família" to "Oi! Lembrete da nossa conversa/reunião familiar programada.",
            "📅 Compromisso" to "Lembrete: temos um compromisso agendado conforme combinado."
        )
    }

    // Calcula dinamicamente o momento exato do primeiro disparo para pré-visualização em tempo real
    val liveNextTrigger = remember(frequency, hour, minute, selectedDayOfWeek, selectedDayOfMonth, selectedMonth, selectedYear) {
        RecurringReminder.calculateNextTrigger(
            frequency = frequency,
            hour = hour,
            minute = minute,
            dayOfWeek = if (frequency == ReminderFrequency.SEMANAL) selectedDayOfWeek else null,
            dayOfMonth = selectedDayOfMonth,
            month = selectedMonth,
            year = selectedYear,
            afterEpochMs = System.currentTimeMillis()
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 540.dp)
                .fillMaxWidth(0.95f)
                .heightIn(max = 700.dp)
                .fillMaxHeight(0.92f)
                .imePadding()
                .statusBarsPadding()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(24.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // ================= CABEÇALHO =================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = RadarCyan.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = RadarCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Agendamentos & Lembretes",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Mensagens únicas ou recorrentes com disparo E2EE",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ================= ABAS (LISTA VS NOVO) =================
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DarkCard,
                    contentColor = RadarCyan,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = "Meus Agendamentos (${reminders.size})",
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 0) RadarCyan else TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (editingReminderId != null) Icons.Default.Edit else Icons.Default.AddAlarm,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (editingReminderId != null) WarningAmber else if (selectedTab == 1) EmeraldGreen else TextSecondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (editingReminderId != null) "Editar Agendamento" else "Novo Agendamento",
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                    color = if (editingReminderId != null) WarningAmber else if (selectedTab == 1) EmeraldGreen else TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ================= CONTEÚDO DAS ABAS =================
                if (selectedTab == 0) {
                    // ================= ABA 0: LISTA DE LEMBRETES / AGENDAMENTOS =================
                    if (reminders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = RadarCyan.copy(alpha = 0.1f),
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.AlarmOn,
                                            contentDescription = null,
                                            tint = RadarCyan.copy(alpha = 0.6f),
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Nenhum agendamento ativo",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    text = "Agende uma mensagem única para amanhã, uma data futura (como 2027) ou configure lembretes diários, semanais, mensais e anuais.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        resetForm()
                                        selectedTab = 1
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RadarCyan, contentColor = DeepBlack),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Criar Agendamento", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(reminders, key = { it.id }) { reminder ->
                                val freqColor = when (reminder.frequency) {
                                    ReminderFrequency.UNICO -> RadarCyan
                                    ReminderFrequency.DIARIO -> RadarCyan
                                    ReminderFrequency.SEMANAL -> EmeraldGreen
                                    ReminderFrequency.MENSAL -> FamilyPurple
                                    ReminderFrequency.ANUAL -> WarningAmber
                                }

                                val isCompleted = reminder.frequency == ReminderFrequency.UNICO &&
                                        !reminder.isEnabled &&
                                        reminder.lastTriggerEpochMs != null

                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkCardElevated),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isCompleted) EmeraldGreen.copy(alpha = 0.5f)
                                        else if (reminder.isEnabled) freqColor.copy(alpha = 0.4f)
                                        else Color.Transparent
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        // Linha Superior: Frequência à esquerda, Ações (Editar, Switch, Excluir) à direita
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isCompleted) EmeraldGreen.copy(alpha = 0.2f) else freqColor.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = if (isCompleted) "CONCLUÍDO" else reminder.frequency.label.uppercase(),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = if (isCompleted) EmeraldGreen else freqColor,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 10.sp
                                                    ),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                IconButton(
                                                    onClick = { startEditing(reminder) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Edit,
                                                        contentDescription = "Editar",
                                                        tint = RadarCyan,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                if (!isCompleted) {
                                                    Switch(
                                                        checked = reminder.isEnabled,
                                                        onCheckedChange = { checked ->
                                                            onToggleReminder(reminder.id, checked)
                                                        },
                                                        modifier = Modifier.scale(0.75f),
                                                        colors = SwitchDefaults.colors(
                                                            checkedThumbColor = freqColor,
                                                            checkedTrackColor = freqColor.copy(alpha = 0.3f)
                                                        )
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { onDeleteReminder(reminder.id) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteOutline,
                                                        contentDescription = "Excluir",
                                                        tint = AlertRed.copy(alpha = 0.9f),
                                                        modifier = Modifier.size(19.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Linha 2: Badges Secundários (Chat Automático e Persistência) em linha dedicada
                                        if (reminder.autoSend || reminder.isPersistentUntilConfirmed) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (!reminder.mediaBase64.isNullOrBlank() || reminder.mediaFilePath != null) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = RadarCyan.copy(alpha = 0.15f)
                                                    ) {
                                                        Text(
                                                            text = "📷 Mídia Anexada",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = RadarCyan,
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold
                                                            ),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }

                                                if (reminder.autoSend) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = EmeraldGreen.copy(alpha = 0.15f)
                                                    ) {
                                                        Text(
                                                            text = "Chat Automático",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = EmeraldGreen,
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold
                                                            ),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                if (reminder.isPersistentUntilConfirmed) {
                                                    val (persBg, persText, persLabel) = when {
                                                        reminder.isConfirmed -> Triple(EmeraldGreen.copy(alpha = 0.2f), EmeraldGreen, "✓ Confirmado")
                                                        reminder.retryCount > 0 -> Triple(WarningAmber.copy(alpha = 0.25f), WarningAmber, "🔁 Tentativa ${reminder.retryCount + 1}/3 pendente")
                                                        else -> Triple(WarningAmber.copy(alpha = 0.15f), WarningAmber, "🔁 3 Dias Persistente")
                                                    }
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = persBg
                                                    ) {
                                                        Text(
                                                            text = persLabel,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = persText,
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold
                                                            ),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Título e Destinatário
                                        Text(
                                            text = reminder.title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = TextSecondary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Para: ${reminder.targetAlias}",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    color = TextSecondary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            )
                                            reminder.targetFamilyRole?.let { role ->
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = FamilyPurple.copy(alpha = 0.2f)
                                                ) {
                                                    Text(
                                                        text = role.label,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            color = FamilyPurple,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Trecho da Mensagem
                                        if (reminder.messageText.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = DeepBlack.copy(alpha = 0.4f),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "\"${reminder.messageText}\"",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = TextPrimary.copy(alpha = 0.9f)
                                                    ),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        }

                                        // Status de Disparo
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Default.AccessTime,
                                                contentDescription = null,
                                                tint = if (isCompleted) EmeraldGreen else freqColor,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isCompleted) {
                                                    "Enviado com sucesso em: ${dateFormatter.format(Date(reminder.lastTriggerEpochMs ?: 0L))}"
                                                } else if (reminder.isEnabled && reminder.nextTriggerEpochMs > 0) {
                                                    "Próximo disparo: ${dateFormatter.format(Date(reminder.nextTriggerEpochMs))}"
                                                } else {
                                                    "Agendamento pausado"
                                                },
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = if (isCompleted) EmeraldGreen else if (reminder.isEnabled) freqColor else TextSecondary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            )
                                        }

                                        // Ação rápida: Confirmar/Concluir lembrete persistente pendente
                                        if (reminder.isPersistentUntilConfirmed && !reminder.isConfirmed && reminder.isEnabled) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedButton(
                                                onClick = { onConfirmReminder(reminder.id) },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldGreen),
                                                border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.6f)),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Marcar como Concluído / Lido", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ================= ABA 1: FORMULÁRIO DE NOVO AGENDAMENTO OU EDIÇÃO =================
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Banner de Edição Ativa
                        if (editingReminderId != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = WarningAmber.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                            tint = WarningAmber,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Modo de Edição Ativo",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                color = WarningAmber,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            resetForm()
                                            selectedTab = 0
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Cancelar Edição", color = TextSecondary, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        // Alerta de Limite do Plano Free (3 Lembretes Ativos)
                        val activeCount = reminders.count { it.isEnabled }
                        if (!isVip && editingReminderId == null && activeCount >= 3) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = WarningAmber.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = WarningAmber,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Limite do Plano Gratuito Atingido (3/3)",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                color = WarningAmber,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Você já possui 3 lembretes ativos. Assine o VIP para lembretes ilimitados ou edite/exclua um dos existentes.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = onUpgradeToVip,
                                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber, contentColor = DeepBlack),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Desbloquear Lembretes Ilimitados", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        // 1. Seleção de Destinatário
                        Column {
                            Text(
                                text = "Destinatário (Contato ou Familiar)",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (connectedPeople.isEmpty() && initialRecipient == null) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = WarningAmber.copy(alpha = 0.15f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Nenhuma conexão mútua encontrada. O lembrete será registrado como nota pessoal para você.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = WarningAmber),
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            } else {
                                var expandedPersonDropdown by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedCard(
                                        onClick = { expandedPersonDropdown = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.outlinedCardColors(containerColor = DarkCardElevated),
                                        border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .background(RadarCyan.copy(alpha = 0.2f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = (selectedPerson?.alias ?: "Eu").take(1).uppercase(),
                                                        fontWeight = FontWeight.Bold,
                                                        color = RadarCyan,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = selectedPerson?.alias ?: "Meu Lembrete Pessoal (Eu)",
                                                    style = MaterialTheme.typography.titleSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    )
                                                )
                                                selectedPerson?.familyRole?.let { role ->
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = FamilyPurple.copy(alpha = 0.2f)
                                                    ) {
                                                        Text(
                                                            text = role.label,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = FamilyPurple,
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold
                                                            ),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = null,
                                                tint = TextSecondary
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = expandedPersonDropdown,
                                        onDismissRequest = { expandedPersonDropdown = false },
                                        modifier = Modifier.background(DarkSurface)
                                    ) {
                                        connectedPeople.forEach { person ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(person.alias, color = TextPrimary, fontWeight = FontWeight.Bold)
                                                        if (person.isFamily) {
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                "(${person.familyRole?.label ?: "Família"})",
                                                                color = FamilyPurple,
                                                                fontSize = 11.sp
                                                            )
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    selectedPerson = person
                                                    expandedPersonDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Frequência de Repetição (Pill Buttons)
                        Column {
                            Text(
                                text = "Tipo de Agendamento / Repetição",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ReminderFrequency.values().forEach { freq ->
                                    val isSelected = frequency == freq
                                    val col = when (freq) {
                                        ReminderFrequency.UNICO -> RadarCyan
                                        ReminderFrequency.DIARIO -> RadarCyan
                                        ReminderFrequency.SEMANAL -> EmeraldGreen
                                        ReminderFrequency.MENSAL -> FamilyPurple
                                        ReminderFrequency.ANUAL -> WarningAmber
                                    }
                                    val shortLabel = when (freq) {
                                        ReminderFrequency.UNICO -> "🎯 Única"
                                        ReminderFrequency.DIARIO -> "🔄 Diária"
                                        ReminderFrequency.SEMANAL -> "📅 Semanal"
                                        ReminderFrequency.MENSAL -> "🗓️ Mensal"
                                        ReminderFrequency.ANUAL -> "🎂 Anual"
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) col.copy(alpha = 0.22f) else DarkCardElevated,
                                        border = BorderStroke(1.dp, if (isSelected) col else DarkCardElevated),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { frequency = freq }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = shortLabel,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) col else TextSecondary,
                                                    fontSize = 10.sp
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Seleção de Data do Disparo / Início (Atalhos + Controles de Dia, Mês e Ano)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = DarkCardElevated,
                            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (frequency == ReminderFrequency.UNICO) "Data do Disparo" else "Data de Início da Recorrência",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = RadarCyan
                                    )
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                // Atalhos Rápidos de Data
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Hoje
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DarkSurface,
                                        border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.4f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                val c = Calendar.getInstance()
                                                selectedYear = c.get(Calendar.YEAR)
                                                selectedMonth = c.get(Calendar.MONTH)
                                                selectedDayOfMonth = c.get(Calendar.DAY_OF_MONTH)
                                            }
                                    ) {
                                        Text(
                                            text = "📅 Hoje",
                                            style = MaterialTheme.typography.labelSmall.copy(color = TextPrimary),
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }

                                    // Amanhã
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DarkSurface,
                                        border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
                                                selectedYear = c.get(Calendar.YEAR)
                                                selectedMonth = c.get(Calendar.MONTH)
                                                selectedDayOfMonth = c.get(Calendar.DAY_OF_MONTH)
                                            }
                                    ) {
                                        Text(
                                            text = "☀️ Amanhã",
                                            style = MaterialTheme.typography.labelSmall.copy(color = EmeraldGreen, fontWeight = FontWeight.Bold),
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }

                                    // Em 7 dias
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DarkSurface,
                                        border = BorderStroke(1.dp, FamilyPurple.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }
                                                selectedYear = c.get(Calendar.YEAR)
                                                selectedMonth = c.get(Calendar.MONTH)
                                                selectedDayOfMonth = c.get(Calendar.DAY_OF_MONTH)
                                            }
                                    ) {
                                        Text(
                                            text = "🗓️ +7 dias",
                                            style = MaterialTheme.typography.labelSmall.copy(color = FamilyPurple),
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }

                                    // Exemplo futuro: 24/Ago/2027
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DarkSurface,
                                        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                selectedYear = 2027
                                                selectedMonth = Calendar.AUGUST
                                                selectedDayOfMonth = 24
                                            }
                                    ) {
                                        Text(
                                            text = "🎂 24/Ago/27",
                                            style = MaterialTheme.typography.labelSmall.copy(color = WarningAmber, fontWeight = FontWeight.Bold),
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Controles Detalhados: Dia, Mês e Ano
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 1. Seletor de Dia
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .background(DarkSurface, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        IconButton(
                                            onClick = { if (selectedDayOfMonth > 1) selectedDayOfMonth-- else selectedDayOfMonth = 31 },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Remove, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(16.dp))
                                        }
                                        Text(
                                            text = String.format("%02d", selectedDayOfMonth),
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = TextPrimary),
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                        IconButton(
                                            onClick = { if (selectedDayOfMonth < 31) selectedDayOfMonth++ else selectedDayOfMonth = 1 },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    // 2. Seletor de Mês (Dropdown)
                                    var expandedMonthDropdown by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.weight(1f)) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = DarkSurface,
                                            border = BorderStroke(1.dp, DarkCard),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { expandedMonthDropdown = true }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = months.find { it.first == selectedMonth }?.second ?: "Mês",
                                                    color = TextPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    maxLines = 1
                                                )
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(16.dp))
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = expandedMonthDropdown,
                                            onDismissRequest = { expandedMonthDropdown = false },
                                            modifier = Modifier.background(DarkSurface)
                                        ) {
                                            months.forEach { (mCode, mName) ->
                                                DropdownMenuItem(
                                                    text = { Text(mName, color = TextPrimary, fontSize = 13.sp) },
                                                    onClick = {
                                                        selectedMonth = mCode
                                                        expandedMonthDropdown = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // 3. Seletor de Ano (Com suporte explícito para 2026, 2027, 2028...)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .background(DarkSurface, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        IconButton(
                                            onClick = { if (selectedYear > currentYearNow) selectedYear-- },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Remove, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(16.dp))
                                        }
                                        Text(
                                            text = "$selectedYear",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = TextPrimary),
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                        IconButton(
                                            onClick = { if (selectedYear < currentYearNow + 15) selectedYear++ },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Configuração para Dia da Semana (se SEMANAL)
                        if (frequency == ReminderFrequency.SEMANAL) {
                            Column {
                                Text(
                                    text = "Repetir no Dia da Semana",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    daysOfWeek.forEach { (dow, dowLabel) ->
                                        val isDowSelected = selectedDayOfWeek == dow
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isDowSelected) EmeraldGreen else DarkCardElevated,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { selectedDayOfWeek = dow }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = dowLabel,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = if (isDowSelected) DeepBlack else TextPrimary,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Horário do Disparo (Hora e Minuto)
                        Column {
                            Text(
                                text = "Horário do Disparo (Hora e Minuto)",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Hora
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(DarkCardElevated, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                    IconButton(
                                        onClick = { if (hour > 0) hour-- else hour = 23 },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = null, tint = RadarCyan)
                                    }
                                    Text(
                                        text = String.format("%02dh", hour),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        ),
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                    IconButton(
                                        onClick = { if (hour < 23) hour++ else hour = 0 },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = RadarCyan)
                                    }
                                }

                                Text(":", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 20.sp)

                                // Minuto
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(DarkCardElevated, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    IconButton(
                                        onClick = { if (minute >= 5) minute -= 5 else minute = 55 },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = null, tint = RadarCyan)
                                    }
                                    Text(
                                        text = String.format("%02dm", minute),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        ),
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                    IconButton(
                                        onClick = { if (minute <= 50) minute += 5 else minute = 0 },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = RadarCyan)
                                    }
                                }
                            }
                        }

                        // 5. Sugestões Rápidas de Título e Mensagem
                        Column {
                            Text(
                                text = "Sugestões Rápidas",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                quickSuggestions.take(3).forEach { (sugTitle, sugMsg) ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DarkCardElevated,
                                        border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.3f)),
                                        modifier = Modifier.clickable {
                                            title = sugTitle.substringAfter(" ")
                                            messageText = sugMsg
                                        }
                                    ) {
                                        Text(
                                            text = sugTitle,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = RadarCyan,
                                                fontSize = 10.sp
                                            ),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 6. Título do Lembrete
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Título / Assunto do Agendamento") },
                            placeholder = { Text("Ex: Aniversário, Tomar remédio, Parabéns...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarCyan,
                                unfocusedBorderColor = DarkCardElevated,
                                focusedContainerColor = DarkCardElevated,
                                unfocusedContainerColor = DarkCardElevated,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        // 7. Mensagem a ser enviada
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            label = { Text("Mensagem a ser enviada no chat ou notificada") },
                            placeholder = { Text("Digite a mensagem do agendamento...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            minLines = 2,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarCyan,
                                unfocusedBorderColor = DarkCardElevated,
                                focusedContainerColor = DarkCardElevated,
                                unfocusedContainerColor = DarkCardElevated,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        // 7.1 Anexo de Mídia Persistente (Garantido em disco físico para nunca falhar)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCardElevated,
                            border = BorderStroke(1.dp, if (attachedMediaBase64 != null) RadarCyan.copy(alpha = 0.5f) else Color.Transparent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Attachment,
                                            contentDescription = null,
                                            tint = if (attachedMediaBase64 != null) RadarCyan else TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Mídia / Foto Anexada",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        )
                                    }

                                    if (attachedMediaBase64 == null) {
                                        Button(
                                            onClick = { imagePicker.launch("image/*") },
                                            colors = ButtonDefaults.buttonColors(containerColor = RadarCyan.copy(alpha = 0.2f), contentColor = RadarCyan),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Anexar Foto", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        IconButton(
                                            onClick = {
                                                attachedMediaBase64 = null
                                                attachedBitmap = null
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Remover Mídia", tint = AlertRed)
                                        }
                                    }
                                }

                                if (attachedBitmap != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(DarkSurface, RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        Image(
                                            bitmap = attachedBitmap!!.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Foto protegida para o disparo",
                                                style = MaterialTheme.typography.labelMedium.copy(color = TextPrimary, fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                text = "Persistência permanente local • Sem risco de falha",
                                                style = MaterialTheme.typography.labelSmall.copy(color = EmeraldGreen, fontSize = 10.sp)
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Anexe uma foto para ser enviada ou notificada quando a data/hora chegar.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }

                        // 8. Opção de Envio Automático no Chat Privado
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCardElevated,
                            border = BorderStroke(1.dp, if (autoSend) EmeraldGreen.copy(alpha = 0.4f) else Color.Transparent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Send,
                                            contentDescription = null,
                                            tint = if (autoSend) EmeraldGreen else TextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Enviar Mensagem no Chat Automaticamente",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        )
                                    }
                                    Text(
                                        text = if (autoSend) {
                                            "No dia e horário marcado, a mensagem será enviada ponta a ponta (E2EE) no chat privado do contato."
                                        } else {
                                            "Exibirá alarme/notificação no seu aparelho para você revisar e enviar na hora."
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        ),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Switch(
                                    checked = autoSend,
                                    onCheckedChange = { autoSend = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = EmeraldGreen,
                                        checkedTrackColor = EmeraldGreen.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }

                        // 8.1. Persistência Inteligente (3 Dias Consecutivos se não visualizado/confirmado)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCardElevated,
                            border = BorderStroke(1.dp, if (isPersistentUntilConfirmed) WarningAmber.copy(alpha = 0.4f) else Color.Transparent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Repeat,
                                            contentDescription = null,
                                            tint = if (isPersistentUntilConfirmed) WarningAmber else TextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "🔁 Persistência Inteligente (3 Dias)",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        )
                                    }
                                    Text(
                                        text = if (isPersistentUntilConfirmed) {
                                            "Se o lembrete/mensagem não for confirmado ou visualizado, ele se repetirá no dia seguinte no mesmo horário por até 3 dias consecutivos."
                                        } else {
                                            "Disparo padrão no dia e horário agendado, sem repetição em caso de não visualização."
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        ),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Switch(
                                    checked = isPersistentUntilConfirmed,
                                    onCheckedChange = { isPersistentUntilConfirmed = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = WarningAmber,
                                        checkedTrackColor = WarningAmber.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }

                        // 9. Card de Pré-Visualização Dinâmica em Tempo Real
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (liveNextTrigger > 0L) EmeraldGreen.copy(alpha = 0.1f) else AlertRed.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, if (liveNextTrigger > 0L) EmeraldGreen.copy(alpha = 0.4f) else AlertRed.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (liveNextTrigger > 0L) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (liveNextTrigger > 0L) EmeraldGreen else AlertRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    if (liveNextTrigger > 0L) {
                                        Text(
                                            text = "Primeiro Disparo: ${dateFormatter.format(Date(liveNextTrigger))}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = EmeraldGreen,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        val repDesc = when (frequency) {
                                            ReminderFrequency.UNICO -> "🎯 Mensagem Única" + if (isPersistentUntilConfirmed) " (com repetição diária por até 3 dias se não confirmada)" else " (Não se repetirá após o envio)"
                                            ReminderFrequency.DIARIO -> "🔄 Repetirá todos os dias pontualmente às ${String.format("%02d:%02d", hour, minute)}"
                                            ReminderFrequency.SEMANAL -> "📅 Repetirá semanalmente no dia escolhido às ${String.format("%02d:%02d", hour, minute)}"
                                            ReminderFrequency.MENSAL -> "🗓️ Repetirá mensalmente no dia $selectedDayOfMonth às ${String.format("%02d:%02d", hour, minute)}"
                                            ReminderFrequency.ANUAL -> "🎂 Repetirá anualmente todo dia $selectedDayOfMonth de ${months.find { it.first == selectedMonth }?.second} às ${String.format("%02d:%02d", hour, minute)}"
                                        }
                                        Text(
                                            text = repDesc,
                                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                        )
                                    } else {
                                        Text(
                                            text = "A data ou horário selecionados já passaram. Por favor, escolha um momento futuro.",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = AlertRed,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Alerta de Erro
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage ?: "",
                                color = AlertRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 10. Botão de Salvar Agendamento
                        Button(
                            onClick = {
                                if (title.isBlank()) {
                                    errorMessage = "Por favor, digite um título para o agendamento."
                                    return@Button
                                }
                                if (messageText.isBlank() && attachedMediaBase64 == null) {
                                    errorMessage = "Por favor, digite o texto da mensagem ou anexe uma foto."
                                    return@Button
                                }
                                if (liveNextTrigger <= 0L) {
                                    errorMessage = "A data ou horário selecionados já passaram. Escolha um horário futuro."
                                    return@Button
                                }

                                val currentActiveCount = reminders.count { it.isEnabled }
                                if (!isVip && editingReminderId == null && currentActiveCount >= 3) {
                                    errorMessage = "Você atingiu o limite de 3 lembretes do plano gratuito. Faça upgrade para o VIP para criar novos agendamentos."
                                    return@Button
                                }

                                val targetId = selectedPerson?.technicalIdentityHash?.ifBlank { selectedPerson?.id } ?: "self"
                                val targetAlias = selectedPerson?.alias ?: "Eu"
                                val targetRole = selectedPerson?.familyRole

                                val reminder = if (editingReminderId != null) {
                                    val existing = reminders.find { it.id == editingReminderId }
                                    (existing ?: RecurringReminder(
                                        id = editingReminderId!!,
                                        targetId = targetId,
                                        targetAlias = targetAlias,
                                        title = title.trim(),
                                        messageText = messageText.trim()
                                    )).copy(
                                        targetId = targetId,
                                        targetAlias = targetAlias,
                                        targetFamilyRole = targetRole,
                                        title = title.trim(),
                                        messageText = messageText.trim(),
                                        frequency = frequency,
                                        hour = hour,
                                        minute = minute,
                                        dayOfWeek = if (frequency == ReminderFrequency.SEMANAL) selectedDayOfWeek else null,
                                        dayOfMonth = selectedDayOfMonth,
                                        month = selectedMonth,
                                        year = selectedYear,
                                        autoSend = autoSend,
                                        isPersistentUntilConfirmed = isPersistentUntilConfirmed,
                                        isConfirmed = false,
                                        retryCount = 0,
                                        isEnabled = true,
                                        mediaBase64 = attachedMediaBase64,
                                        mediaType = if (attachedMediaBase64 != null) "IMAGE" else null,
                                        nextTriggerEpochMs = liveNextTrigger
                                    )
                                } else {
                                    RecurringReminder(
                                        targetId = targetId,
                                        targetAlias = targetAlias,
                                        targetFamilyRole = targetRole,
                                        title = title.trim(),
                                        messageText = messageText.trim(),
                                        frequency = frequency,
                                        hour = hour,
                                        minute = minute,
                                        dayOfWeek = if (frequency == ReminderFrequency.SEMANAL) selectedDayOfWeek else null,
                                        dayOfMonth = selectedDayOfMonth,
                                        month = selectedMonth,
                                        year = selectedYear,
                                        autoSend = autoSend,
                                        isPersistentUntilConfirmed = isPersistentUntilConfirmed,
                                        isConfirmed = false,
                                        retryCount = 0,
                                        isEnabled = true,
                                        mediaBase64 = attachedMediaBase64,
                                        mediaType = if (attachedMediaBase64 != null) "IMAGE" else null,
                                        nextTriggerEpochMs = liveNextTrigger
                                    )
                                }

                                onSaveReminder(reminder)
                                resetForm()
                                selectedTab = 0
                            },
                            enabled = liveNextTrigger > 0L,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (editingReminderId != null) WarningAmber else RadarCyan,
                                contentColor = DeepBlack
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (editingReminderId != null) {
                                    "Salvar Alterações do Lembrete"
                                } else if (frequency == ReminderFrequency.UNICO) {
                                    "Salvar Mensagem Agendada"
                                } else {
                                    "Salvar Lembrete Recorrente"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
