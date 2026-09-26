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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.ui.theme.*

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

    var isSending by remember { mutableStateOf(false) }
    var progressSent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successNotice by remember { mutableStateOf<String?>(null) }

    // Seleciona automaticamente até o limite permitido ao abrir
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
            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // ================= CABEÇALHO =================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                )
                            )
                            Text(
                                text = "Disparo mútuo individual com criptografia E2EE",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        enabled = !isSending
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ================= AVISO DE COOLDOWN / ANTI-SPAM =================
                if (remainingCooldownMs > 0) {
                    val cooldownMins = (remainingCooldownMs / 60000) + 1
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = WarningAmber.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Proteção Anti-Spam: aguarde mais $cooldownMins minuto(s) para enviar nova transmissão.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = WarningAmber,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }

                // ================= BANNER DE PLANO (FREE VS VIP) =================
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isVip) EmeraldGreen.copy(alpha = 0.12f) else DarkCardElevated,
                    border = BorderStroke(1.dp, if (isVip) EmeraldGreen.copy(alpha = 0.4f) else RadarCyan.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
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
                                text = if (isVip) "Transmissões Ilimitadas" else "Até $maxAllowedRecipients contatos por envio",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }

                        if (!isVip) {
                            TextButton(
                                onClick = onUpgradeToVip,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Seja VIP (R$ 5/mês)",
                                    color = WarningAmber,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // ================= SELETOR DE DESTINATÁRIOS =================
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
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Selecionar Todos", fontSize = 11.sp, color = RadarCyan)
                        }

                        TextButton(
                            onClick = { selectedIds.clear() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Limpar", fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }

                // Lista de Contatos com Checkbox
                if (connectedPeople.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Nenhuma conexão mútua ou familiar encontrada para transmissão.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .background(DarkCard, RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(connectedPeople, key = { it.id }) { person ->
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
                                                errorMessage = "Limite do Plano Grátis atingido ($maxAllowedRecipients contatos). Assine o VIP para transmitir para todos!"
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
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (!isVip && selectedIds.size >= maxAllowedRecipients) {
                                                    errorMessage = "Limite do Plano Grátis atingido ($maxAllowedRecipients contatos). Assine o VIP para transmitir para todos!"
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
                                    Text(
                                        text = person.alias,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
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
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ================= CAMPO DE MENSAGEM =================
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Mensagem a transmitir") },
                    placeholder = { Text("Ex: Olá a todos! Comunicado importante...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 3,
                    maxLines = 5,
                    enabled = !isSending,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RadarCyan,
                        unfocusedBorderColor = DarkCardElevated,
                        focusedContainerColor = DarkCardElevated,
                        unfocusedContainerColor = DarkCardElevated
                    )
                )

                // Mensagens de Erro / Sucesso
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = AlertRed,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (successNotice != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = successNotice ?: "",
                        color = EmeraldGreen,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Barra de Progresso durante Envio
                if (isSending) {
                    Spacer(modifier = Modifier.height(8.dp))
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

                Spacer(modifier = Modifier.weight(1f))

                // ================= BOTÃO DE ENVIO =================
                Button(
                    onClick = {
                        if (remainingCooldownMs > 0) {
                            val cooldownMins = (remainingCooldownMs / 60000) + 1
                            errorMessage = "Aguarde mais $cooldownMins minuto(s) devido à proteção anti-spam."
                            return@Button
                        }

                        if (selectedIds.isEmpty()) {
                            errorMessage = "Selecione ao menos um destinatário."
                            return@Button
                        }

                        if (messageText.isBlank()) {
                            errorMessage = "Digite a mensagem a ser transmitida."
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
                                    successNotice = "✅ Transmissão concluída para $progressSent contato(s)!"
                                    messageText = ""
                                } else {
                                    errorMessage = msg
                                }
                            }
                        )
                    },
                    enabled = !isSending && selectedIds.isNotEmpty() && messageText.isNotBlank() && remainingCooldownMs <= 0,
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
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSending) "Enviando Transmissão..." else "Enviar Transmissão (${selectedIds.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
