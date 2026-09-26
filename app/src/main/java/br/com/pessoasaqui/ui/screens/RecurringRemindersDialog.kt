package br.com.pessoasaqui.ui.screens

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
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()) }

    // Form state para novo lembrete
    var selectedPerson by remember { mutableStateOf(initialRecipient ?: connectedPeople.firstOrNull()) }
    var title by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(ReminderFrequency.DIARIO) }
    var hour by remember { mutableIntStateOf(8) }
    var minute by remember { mutableIntStateOf(0) }
    var selectedDayOfWeek by remember { mutableIntStateOf(Calendar.MONDAY) }
    var selectedDayOfMonth by remember { mutableIntStateOf(1) }
    var selectedMonth by remember { mutableIntStateOf(Calendar.JANUARY) }
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
            "💊 Tomar Remédio" to "Oi! Lembrete para tomar o remédio no horário certinho.",
            "🎂 Aniversário" to "Parabéns pelo seu aniversário! Que Deus te abençoe com muita saúde e paz!",
            "👨‍👩‍👧 Reunião Familiar" to "Lembrete: nossa conversa/reunião de família está marcada!",
            "☀️ Bom Dia" to "Bom dia! Desejo um excelente e abençoado dia para você.",
            "📅 Compromisso" to "Passando para lembrar do nosso compromisso agendado."
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .statusBarsPadding()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(24.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Cabeçalho
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = RadarCyan.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = RadarCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Lembretes Recorrentes",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = "Diário • Semanal • Mensal • Anual",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Seletor de Abas (Ativos vs + Novo)
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Ativos (${reminders.size})",
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == 0) RadarCyan else TextSecondary
                                )
                            }
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
                                    modifier = Modifier.size(16.dp),
                                    tint = if (selectedTab == 1) EmeraldGreen else TextSecondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "+ Novo Lembrete",
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == 1) EmeraldGreen else TextSecondary
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Conteúdo da Aba Selecionada
                if (selectedTab == 0) {
                    // ================= ABA: LISTA DE LEMBRETES ATIVOS =================
                    if (reminders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Alarm,
                                    contentDescription = null,
                                    tint = TextSecondary.copy(alpha = 0.4f),
                                    modifier = Modifier.size(54.dp)
                                )
                                Text(
                                    text = "Nenhum lembrete recorrente agendado",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "Agende mensagens automáticas ou alertas periódicos (diários, semanais, mensais ou anuais) para você e seus familiares.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = TextSecondary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = { selectedTab = 1 },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = RadarCyan,
                                        contentColor = DeepBlack
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Criar Primeiro Lembrete", fontWeight = FontWeight.Bold)
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
                                    ReminderFrequency.DIARIO -> RadarCyan
                                    ReminderFrequency.SEMANAL -> EmeraldGreen
                                    ReminderFrequency.MENSAL -> FamilyPurple
                                    ReminderFrequency.ANUAL -> WarningAmber
                                }

                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkCardElevated),
                                    border = BorderStroke(1.dp, if (reminder.isEnabled) freqColor.copy(alpha = 0.4f) else Color.Transparent),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        // Top Row: Frequency Badge & AutoSend + Switch
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = freqColor.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = reminder.frequency.label.uppercase(),
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            color = freqColor,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 11.sp
                                                        ),
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                if (reminder.autoSend) {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = EmeraldGreen.copy(alpha = 0.15f)
                                                    ) {
                                                        Text(
                                                            text = "🤖 Envio no Chat",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = EmeraldGreen,
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 10.sp
                                                            ),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Switch(
                                                    checked = reminder.isEnabled,
                                                    onCheckedChange = { onToggleReminder(reminder.id, it) },
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = EmeraldGreen,
                                                        checkedTrackColor = EmeraldGreen.copy(alpha = 0.3f),
                                                        uncheckedThumbColor = TextSecondary,
                                                        uncheckedTrackColor = DarkCard
                                                    ),
                                                    modifier = Modifier.scale(0.8f)
                                                )
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

                                        Spacer(modifier = Modifier.height(6.dp))

                                        // Título e Destinatário
                                        Text(
                                            text = reminder.title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (reminder.isEnabled) TextPrimary else TextSecondary
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
                                                modifier = Modifier.size(14.dp)
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
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
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

                                        // Próximo Disparo
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.AccessTime,
                                                contentDescription = null,
                                                tint = freqColor,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (reminder.isEnabled && reminder.nextTriggerEpochMs > 0) {
                                                    "Próximo: ${dateFormatter.format(Date(reminder.nextTriggerEpochMs))}"
                                                } else {
                                                    "Lembrete pausado"
                                                },
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = if (reminder.isEnabled) freqColor else TextSecondary,
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
                    // ================= ABA: NOVO LEMBRETE RECORRENTE =================
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Seleção de Destinatário
                        Column {
                            Text(
                                text = "Destinatário (Contato ou Familiar)",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (connectedPeople.isEmpty() && initialRecipient == null) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = WarningAmber.copy(alpha = 0.15f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Nenhuma conexão mútua ou familiar encontrada. O lembrete será registrado como nota pessoal para você.",
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
                                                    text = selectedPerson?.alias ?: "Meu Lembrete Pessoal",
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

                        // Sugestões Rápidas de Título e Mensagem
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

                        // Título do Lembrete
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Título do Lembrete") },
                            placeholder = { Text("Ex: Tomar remédio, Aniversário...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarCyan,
                                unfocusedBorderColor = DarkCardElevated,
                                focusedContainerColor = DarkCardElevated,
                                unfocusedContainerColor = DarkCardElevated
                            )
                        )

                        // Mensagem do Lembrete
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            label = { Text("Mensagem a ser enviada ou lembrada") },
                            placeholder = { Text("Digite a mensagem do lembrete...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            minLines = 2,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarCyan,
                                unfocusedBorderColor = DarkCardElevated,
                                focusedContainerColor = DarkCardElevated,
                                unfocusedContainerColor = DarkCardElevated
                            )
                        )

                        // Frequência de Recorrência
                        Column {
                            Text(
                                text = "Frequência de Repetição",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ReminderFrequency.values().forEach { freq ->
                                    val isSelected = frequency == freq
                                    val col = when (freq) {
                                        ReminderFrequency.DIARIO -> RadarCyan
                                        ReminderFrequency.SEMANAL -> EmeraldGreen
                                        ReminderFrequency.MENSAL -> FamilyPurple
                                        ReminderFrequency.ANUAL -> WarningAmber
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) col.copy(alpha = 0.2f) else DarkCardElevated,
                                        border = BorderStroke(1.dp, if (isSelected) col else DarkCardElevated),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { frequency = freq }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(vertical = 10.dp)
                                        ) {
                                            Text(
                                                text = freq.label,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) col else TextSecondary
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Configuração Específica de Horário (Hora e Minuto)
                        Column {
                            Text(
                                text = "Horário do Disparo",
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
                                // Seletor de Hora
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

                                Text(":", color = TextSecondary, fontWeight = FontWeight.Bold)

                                // Seletor de Minuto
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

                        // Configurações Adicionais por Frequência
                        when (frequency) {
                            ReminderFrequency.DIARIO -> {
                                Text(
                                    text = "Repetirá todos os dias pontualmente às ${String.format("%02d:%02d", hour, minute)}.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = RadarCyan)
                                )
                            }

                            ReminderFrequency.SEMANAL -> {
                                Column {
                                    Text(
                                        text = "Dia da Semana",
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

                            ReminderFrequency.MENSAL -> {
                                Column {
                                    Text(
                                        text = "Dia do Mês: $selectedDayOfMonth",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Slider(
                                        value = selectedDayOfMonth.toFloat(),
                                        onValueChange = { selectedDayOfMonth = it.toInt() },
                                        valueRange = 1f..31f,
                                        steps = 29,
                                        colors = SliderDefaults.colors(
                                            thumbColor = FamilyPurple,
                                            activeTrackColor = FamilyPurple,
                                            inactiveTrackColor = DarkCardElevated
                                        )
                                    )
                                    Text(
                                        text = "Repetirá todo dia $selectedDayOfMonth de cada mês às ${String.format("%02d:%02d", hour, minute)}.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = FamilyPurple)
                                    )
                                }
                            }

                            ReminderFrequency.ANUAL -> {
                                Column {
                                    Text(
                                        text = "Data Anual (Mês e Dia)",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    var expandedMonth by remember { mutableStateOf(false) }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            OutlinedCard(
                                                onClick = { expandedMonth = true },
                                                colors = CardDefaults.outlinedCardColors(containerColor = DarkCardElevated),
                                                border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.5f)),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = months.find { it.first == selectedMonth }?.second ?: "Mês",
                                                        color = TextPrimary,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = WarningAmber)
                                                }
                                            }

                                            DropdownMenu(
                                                expanded = expandedMonth,
                                                onDismissRequest = { expandedMonth = false },
                                                modifier = Modifier.background(DarkSurface)
                                            ) {
                                                months.forEach { (monCode, monName) ->
                                                    DropdownMenuItem(
                                                        text = { Text(monName, color = TextPrimary) },
                                                        onClick = {
                                                            selectedMonth = monCode
                                                            expandedMonth = false
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .background(DarkCardElevated, RoundedCornerShape(10.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            IconButton(
                                                onClick = { if (selectedDayOfMonth > 1) selectedDayOfMonth-- },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Remove, contentDescription = null, tint = WarningAmber)
                                            }
                                            Text(
                                                text = "Dia $selectedDayOfMonth",
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                modifier = Modifier.padding(horizontal = 6.dp)
                                            )
                                            IconButton(
                                                onClick = { if (selectedDayOfMonth < 31) selectedDayOfMonth++ },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, tint = WarningAmber)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Opção de Envio Automático no Chat
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
                                            "Quando der o horário, a mensagem será enviada direto no chat criptografado e uma notificação de confirmação será exibida."
                                        } else {
                                            "Apenas exibirá notificação com alarme e botão para você enviar na hora."
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

                        if (errorMessage != null) {
                            Text(
                                text = errorMessage ?: "",
                                color = AlertRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Botão Salvar Lembrete
                        Button(
                            onClick = {
                                if (title.isBlank()) {
                                    errorMessage = "Por favor, digite um título para o lembrete."
                                    return@Button
                                }
                                if (messageText.isBlank()) {
                                    errorMessage = "Por favor, digite o texto da mensagem."
                                    return@Button
                                }

                                val targetId = selectedPerson?.technicalIdentityHash?.ifBlank { selectedPerson?.id } ?: "self"
                                val targetAlias = selectedPerson?.alias ?: "Eu"
                                val targetRole = selectedPerson?.familyRole

                                val nextTrigger = RecurringReminder.calculateNextTrigger(
                                    frequency = frequency,
                                    hour = hour,
                                    minute = minute,
                                    dayOfWeek = if (frequency == ReminderFrequency.SEMANAL) selectedDayOfWeek else null,
                                    dayOfMonth = if (frequency == ReminderFrequency.MENSAL || frequency == ReminderFrequency.ANUAL) selectedDayOfMonth else null,
                                    month = if (frequency == ReminderFrequency.ANUAL) selectedMonth else null,
                                    afterEpochMs = System.currentTimeMillis()
                                )

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
                                    dayOfMonth = if (frequency == ReminderFrequency.MENSAL || frequency == ReminderFrequency.ANUAL) selectedDayOfMonth else null,
                                    month = if (frequency == ReminderFrequency.ANUAL) selectedMonth else null,
                                    autoSend = autoSend,
                                    isEnabled = true,
                                    nextTriggerEpochMs = nextTrigger
                                )

                                onSaveReminder(reminder)
                                selectedTab = 0
                                title = ""
                                messageText = ""
                                errorMessage = null
                            },
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
                                text = "Agendar Lembrete Recorrente",
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
