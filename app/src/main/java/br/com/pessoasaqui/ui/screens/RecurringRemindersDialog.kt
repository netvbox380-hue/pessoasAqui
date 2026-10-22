package br.com.pessoasaqui.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
    onSaveReminder: (RecurringReminder) -> Unit,
    onDeleteReminder: (String) -> Unit,
    onToggleReminder: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(if (initialRecipient != null) 1 else 0) }
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault()) }

    val nowCal = remember { Calendar.getInstance() }
    val currentYearNow = nowCal.get(Calendar.YEAR)

    // Form state para novo lembrete/agendamento
    var selectedPerson by remember { mutableStateOf(initialRecipient ?: connectedPeople.firstOrNull()) }
    var title by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(ReminderFrequency.UNICO) }

    // Data de disparo / início (Ano, Mês, Dia)
    var selectedYear by remember { mutableIntStateOf(currentYearNow) }
    var selectedMonth by remember { mutableIntStateOf(nowCal.get(Calendar.MONTH)) }
    var selectedDayOfMonth by remember { mutableIntStateOf(nowCal.get(Calendar.DAY_OF_MONTH)) }

    // Horário de disparo (Hora, Minuto)
    var hour by remember { mutableIntStateOf((nowCal.get(Calendar.HOUR_OF_DAY) + 1) % 24) }
    var minute by remember { mutableIntStateOf(0) }
    var selectedDayOfWeek by remember { mutableIntStateOf(Calendar.MONDAY) }

    var autoSend by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                                    imageVector = Icons.Default.AddAlarm,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (selectedTab == 1) EmeraldGreen else TextSecondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Novo Agendamento",
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == 1) EmeraldGreen else TextSecondary,
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
                                    onClick = { selectedTab = 1 },
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
                                        // Linha Superior: Badge de Frequência, Status e Ações
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                                Spacer(modifier = Modifier.width(6.dp))
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
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (!isCompleted) {
                                                    Switch(
                                                        checked = reminder.isEnabled,
                                                        onCheckedChange = { checked ->
                                                            onToggleReminder(reminder.id, checked)
                                                        },
                                                        modifier = Modifier.scale(0.8f),
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
                                                        tint = AlertRed.copy(alpha = 0.8f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
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
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ================= ABA 1: FORMULÁRIO DE NOVO AGENDAMENTO =================
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                                            ReminderFrequency.UNICO -> "🎯 Mensagem Única (Não se repetirá após o envio)"
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
                                if (messageText.isBlank()) {
                                    errorMessage = "Por favor, digite o texto da mensagem."
                                    return@Button
                                }
                                if (liveNextTrigger <= 0L) {
                                    errorMessage = "A data ou horário selecionados já passaram. Escolha um horário futuro."
                                    return@Button
                                }

                                val targetId = selectedPerson?.technicalIdentityHash?.ifBlank { selectedPerson?.id } ?: "self"
                                val targetAlias = selectedPerson?.alias ?: "Eu"
                                val targetRole = selectedPerson?.familyRole

                                val reminder = RecurringReminder(
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
                                    isEnabled = true,
                                    nextTriggerEpochMs = liveNextTrigger
                                )

                                onSaveReminder(reminder)
                                selectedTab = 0
                                title = ""
                                messageText = ""
                                errorMessage = null
                            },
                            enabled = liveNextTrigger > 0L,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RadarCyan,
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
                                text = if (frequency == ReminderFrequency.UNICO) "Salvar Mensagem Agendada" else "Salvar Lembrete Recorrente",
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
