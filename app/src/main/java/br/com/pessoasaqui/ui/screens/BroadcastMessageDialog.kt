package br.com.pessoasaqui.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Diálogo Altamente Responsivo de Mensagens de Transmissão (Broadcast) do PessoasAqui.
 * Permite disparar mensagens com criptografia E2EE para conexões mútuas e familiares.
 *
 * Características de Responsividade:
 * - Adapta-se perfeitamente ao teclado virtual (IME) sem quebrar o layout nem ocultar botões.
 * - Cabeçalho e Botão de Ação ancorados, com corpo deslizante (verticalScroll).
 * - Temporizador regressivo em tempo real para proteção anti-spam.
 * - Campo de busca inteligente para listas com múltiplos contatos mútuos.
 * - Suporta telas compactas (320dp+) até tablets/dobráveis com largura máxima controlada.
 */
@Composable
fun BroadcastMessageDialog(
    connectedPeople: List<NearbyPerson>,
    isVip: Boolean = false,
    maxAllowedRecipients: Int = 3,
    remainingCooldownMs: Long = 0L,
    onSendBroadcast: (targetIds: List<String>, message: String, onProgress: (Int, Int) -> Unit, onResult: (Boolean, String) -> Unit) -> Unit,
    onUpgradeToVip: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }

    var isSending by remember { mutableStateOf(false) }
    var progressSent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successNotice by remember { mutableStateOf<String?>(null) }

    // Temporizador regressivo ativo anti-spam (atualiza a cada 1 segundo)
    var currentCooldownMs by remember(remainingCooldownMs) { mutableLongStateOf(remainingCooldownMs) }
    LaunchedEffect(remainingCooldownMs) {
        currentCooldownMs = remainingCooldownMs
        while (currentCooldownMs > 0L) {
            delay(1000L)
            currentCooldownMs = (currentCooldownMs - 1000L).coerceAtLeast(0L)
        }
    }

    // Filtra pessoas conectadas pela busca
    val filteredPeople = remember(connectedPeople, searchQuery) {
        if (searchQuery.isBlank()) {
            connectedPeople
        } else {
            connectedPeople.filter {
                it.alias.contains(searchQuery.trim(), ignoreCase = true) ||
                (it.familyRole?.label ?: "").contains(searchQuery.trim(), ignoreCase = true)
            }
        }
    }

    // Seleciona automaticamente até o limite permitido ao abrir o diálogo
    LaunchedEffect(connectedPeople) {
        if (selectedIds.isEmpty()) {
            val initial = if (isVip) {
                connectedPeople.map { it.technicalIdentityHash.ifBlank { it.id } }
            } else {
                connectedPeople.take(maxAllowedRecipients).map { it.technicalIdentityHash.ifBlank { it.id } }
            }
            selectedIds.addAll(initial)
        }
    }

    Dialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 520.dp)
                .fillMaxWidth(0.95f)
                .heightIn(max = 680.dp)
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
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                // ================= 1. CABEÇALHO FIXO =================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
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
                                    imageVector = Icons.Default.Campaign,
                                    contentDescription = null,
                                    tint = RadarCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Mensagem de Transmissão",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Disparo ponta a ponta com criptografia E2EE",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        enabled = !isSending,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = TextSecondary
                        )
                    }
                }

                HorizontalDivider(color = DarkCardElevated, thickness = 1.dp)

                // ================= 2. CORPO ROLÁVEL (RESPONSIVO COM TECLADO) =================
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Temporizador Regressivo Anti-Spam (se ativo)
                    if (currentCooldownMs > 0L) {
                        val totalSeconds = (currentCooldownMs / 1000L).coerceAtLeast(1L)
                        val mins = totalSeconds / 60
                        val secs = totalSeconds % 60
                        val timeStr = String.format("%02d:%02d", mins, secs)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = WarningAmber.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = WarningAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Anti-Spam ativo: aguarde $timeStr para nova transmissão.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = WarningAmber,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }

                    // Banner do Plano (Free vs VIP)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isVip) EmeraldGreen.copy(alpha = 0.12f) else DarkCardElevated,
                        border = BorderStroke(1.dp, if (isVip) EmeraldGreen.copy(alpha = 0.4f) else RadarCyan.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (isVip) "👑 VIP" else "Plano Grátis",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = if (isVip) EmeraldGreen else RadarCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isVip) "Transmissões Ilimitadas" else "Até $maxAllowedRecipients por envio",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (!isVip) {
                                TextButton(
                                    onClick = onUpgradeToVip,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Virar VIP (R$ 5/mês)",
                                        color = WarningAmber,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    // Seletor de Destinatários e Ações Rápidas
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Destinatários: ${selectedIds.size} de ${connectedPeople.size}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )

                        Row {
                            TextButton(
                                onClick = {
                                    selectedIds.clear()
                                    val toAdd = if (isVip) {
                                        connectedPeople.map { it.technicalIdentityHash.ifBlank { it.id } }
                                    } else {
                                        connectedPeople.take(maxAllowedRecipients).map { it.technicalIdentityHash.ifBlank { it.id } }
                                    }
                                    selectedIds.addAll(toAdd)
                                    if (!isVip && connectedPeople.size > maxAllowedRecipients) {
                                        errorMessage = "Plano Grátis: selecionados os primeiros $maxAllowedRecipients contatos. Assine o VIP para ilimitado!"
                                    } else {
                                        errorMessage = null
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("Selecionar Todos", fontSize = 11.sp, color = RadarCyan)
                            }

                            TextButton(
                                onClick = {
                                    selectedIds.clear()
                                    errorMessage = null
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("Limpar", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }

                    // Campo de Busca Rápida (quando houver mais de 4 conexões)
                    if (connectedPeople.size > 4) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filtrar contato mútuo...", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Limpar busca", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else null,
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarCyan,
                                unfocusedBorderColor = DarkCardElevated,
                                focusedContainerColor = DarkCard,
                                unfocusedContainerColor = DarkCard
                            )
                        )
                    }

                    // Lista de Contatos Mútuos com Checkbox
                    if (connectedPeople.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PeopleOutline,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Nenhuma conexão mútua ou familiar encontrada.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Marque contatos com a estrela ★ para torná-los mútuos.",
                                    style = MaterialTheme.typography.labelSmall.copy(color = EmeraldGreen),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkCard,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                filteredPeople.forEach { person ->
                                    val cleanId = person.technicalIdentityHash.ifBlank { person.id }
                                    val isSelected = selectedIds.contains(cleanId)

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) RadarCyan.copy(alpha = 0.12f) else Color.Transparent,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isSelected) {
                                                    selectedIds.remove(cleanId)
                                                } else {
                                                    if (!isVip && selectedIds.size >= maxAllowedRecipients) {
                                                        errorMessage = "Limite do Plano Grátis atingido ($maxAllowedRecipients contatos). Seja VIP para transmitir para todos!"
                                                    } else {
                                                        selectedIds.add(cleanId)
                                                        errorMessage = null
                                                    }
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    if (checked) {
                                                        if (!isVip && selectedIds.size >= maxAllowedRecipients) {
                                                            errorMessage = "Limite do Plano Grátis atingido ($maxAllowedRecipients contatos). Seja VIP para transmitir para todos!"
                                                        } else {
                                                            selectedIds.add(cleanId)
                                                            errorMessage = null
                                                        }
                                                    } else {
                                                        selectedIds.remove(cleanId)
                                                    }
                                                },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = RadarCyan,
                                                    uncheckedColor = TextSecondary
                                                ),
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))

                                            // Avatar compacto com inicial
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(person.avatarColorHex).copy(alpha = 0.25f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = person.alias.take(1).uppercase(),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = Color(person.avatarColorHex),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Text(
                                                text = person.alias,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                ),
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            if (person.isFamily) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = FamilyPurple.copy(alpha = 0.2f)
                                                ) {
                                                    Text(
                                                        text = person.familyRole?.label ?: "Família",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            color = FamilyPurple,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            } else {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = EmeraldGreen.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "Mútuo",
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
                                    }
                                }
                            }
                        }
                    }

                    // Campo de Mensagem de Transmissão
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = {
                            if (it.length <= 500) {
                                messageText = it
                                errorMessage = null
                            }
                        },
                        label = { Text("Mensagem a transmitir") },
                        placeholder = { Text("Digite sua mensagem para envio mútuo simultâneo...") },
                        supportingText = {
                            Text(
                                text = "${messageText.length} / 500 caracteres",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 3,
                        maxLines = 4,
                        enabled = !isSending,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RadarCyan,
                            unfocusedBorderColor = DarkCardElevated,
                            focusedContainerColor = DarkCardElevated,
                            unfocusedContainerColor = DarkCardElevated,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    // Alertas de Sucesso ou Erro
                    AnimatedVisibility(visible = errorMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AlertRed.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = AlertRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    AnimatedVisibility(visible = successNotice != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = EmeraldGreen.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = successNotice ?: "",
                                color = EmeraldGreen,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Progresso Visual durante o envio E2EE
                    if (isSending) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Transmitindo com criptografia E2EE...",
                                    style = MaterialTheme.typography.labelSmall.copy(color = RadarCyan)
                                )
                                Text(
                                    text = "$progressSent / $progressTotal",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextPrimary, fontWeight = FontWeight.Bold)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { if (progressTotal > 0) progressSent.toFloat() / progressTotal.toFloat() else 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = RadarCyan,
                                trackColor = DarkCard
                            )
                        }
                    }
                }

                HorizontalDivider(color = DarkCardElevated, thickness = 1.dp)

                // ================= 3. BOTÃO DE AÇÃO ANCORADO (SEMPRE ACESSÍVEL) =================
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (currentCooldownMs > 0L) {
                            val mins = ((currentCooldownMs / 1000L) / 60) + 1
                            errorMessage = "Aguarde mais $mins minuto(s) devido à proteção anti-spam."
                            return@Button
                        }

                        if (selectedIds.isEmpty()) {
                            errorMessage = "Selecione ao menos um contato destinatário."
                            return@Button
                        }

                        if (messageText.isBlank()) {
                            errorMessage = "Digite o texto da mensagem a ser transmitida."
                            return@Button
                        }

                        isSending = true
                        errorMessage = null
                        successNotice = null
                        progressSent = 0
                        progressTotal = selectedIds.size

                        onSendBroadcast(
                            selectedIds.toList(),
                            messageText.trim(),
                            { sent, total ->
                                progressSent = sent
                                progressTotal = total
                            },
                            { success, msg ->
                                isSending = false
                                if (success) {
                                    successNotice = "✅ Transmissão enviada com sucesso para $progressSent contato(s)!"
                                    messageText = ""
                                } else {
                                    errorMessage = msg
                                }
                            }
                        )
                    },
                    enabled = !isSending && selectedIds.isNotEmpty() && messageText.isNotBlank() && currentCooldownMs <= 0L,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RadarCyan,
                        contentColor = DeepBlack
                    )
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            color = DeepBlack,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Transmitindo E2EE ($progressSent/$progressTotal)...",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedIds.isNotEmpty()) "Enviar Transmissão (${selectedIds.size})" else "Selecione Destinatários",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}
