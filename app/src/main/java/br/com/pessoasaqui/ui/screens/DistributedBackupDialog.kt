package br.com.pessoasaqui.ui.screens

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diálogo de Configuração e Gestão do Cofre P2P Distribuído entre Familiares.
 *
 * Oferece controle completo sobre:
 * - Ativação do backup silencioso em segundo plano.
 * - Frequência de replicação (24h, 48h, 7 dias).
 * - Disparo manual imediato com replicação em até 3 aparelhos familiares.
 * - Exportação de arquivo de segurança (.backup) para contingência pessoal.
 * - Restauração a partir de arquivo local.
 */
@Composable
fun DistributedBackupDialog(
    repository: PessoasAquiRepository,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vaultManager = repository.p2pVaultManager

    var isEnabled by remember { mutableStateOf(vaultManager?.isDistributedBackupEnabled() ?: true) }
    var frequencyHours by remember { mutableStateOf(vaultManager?.getBackupFrequencyHours() ?: 24) }

    var isBackingUp by remember { mutableStateOf(false) }
    var backupStatusMessage by remember { mutableStateOf<String?>(null) }
    var showPinPromptForBackup by remember { mutableStateOf(false) }
    var showPinPromptForExport by remember { mutableStateOf(false) }
    var showRestoreFromTextDialog by remember { mutableStateOf(false) }

    var pinInput by remember { mutableStateOf("") }
    var restoreTextInput by remember { mutableStateOf("") }

    val lastEpoch = vaultManager?.getLastBackupEpochMs() ?: 0L
    val lastVersion = vaultManager?.getLastBackupVersion() ?: 0L
    val lastReplicated = vaultManager?.getLastReplicatedCount() ?: 0
    val lastMsgs = vaultManager?.getLastMessagesCount() ?: 0

    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault()) }
    val lastBackupStr = if (lastEpoch > 0L) dateFormat.format(Date(lastEpoch)) else "Nenhum backup realizado"

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(24.dp)),
            color = DarkCard,
            border = BorderStroke(1.5.dp, RadarCyan.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(RadarCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = RadarCyan,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Cofre Distribuído P2P",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = "Backup Seguro entre Familiares",
                                style = MaterialTheme.typography.bodySmall.copy(color = RadarCyan)
                            )
                        }
                    }

                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mensagem de Status em Tempo Real
                if (backupStatusMessage != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurface,
                        border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = backupStatusMessage ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Card de Status do Último Backup
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, DarkCardElevated)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Status da Replicação Redundante",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            if (lastEpoch > 0L) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = EmeraldGreen.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "v$lastVersion Ativa",
                                        color = EmeraldGreen,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = RadarCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Último envio: $lastBackupStr",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (lastReplicated > 0) "Salvo silenciosamente em $lastReplicated familiar(es)" else "Aguardando conexão com familiares mútuos",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = if (lastReplicated > 0) EmeraldGreen else TextSecondary
                                )
                            )
                        }

                        if (lastMsgs > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Chat,
                                    contentDescription = null,
                                    tint = FamilyPurple,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "$lastMsgs mensagens compactadas no cofre",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Switch: Backup Automático nos Familiares
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, if (isEnabled) RadarCyan.copy(alpha = 0.4f) else DarkCardElevated)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Backup Automático nos Familiares",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = "Cifra com AES-256 e envia silenciosamente em background sem notificações.",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            )
                        }

                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                isEnabled = checked
                                vaultManager?.setDistributedBackupEnabled(checked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DeepBlack,
                                checkedTrackColor = RadarCyan
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Frequência do Backup
                Text(
                    text = "FREQUÊNCIA DE REPLICAÇÃO",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = RadarCyan,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FrequencyChip(
                        label = "24 Horas",
                        hours = 24,
                        selected = frequencyHours == 24,
                        modifier = Modifier.weight(1f)
                    ) {
                        frequencyHours = 24
                        vaultManager?.setBackupFrequencyHours(24)
                    }
                    FrequencyChip(
                        label = "48 Horas",
                        hours = 48,
                        selected = frequencyHours == 48,
                        modifier = Modifier.weight(1f)
                    ) {
                        frequencyHours = 48
                        vaultManager?.setBackupFrequencyHours(48)
                    }
                    FrequencyChip(
                        label = "7 Dias",
                        hours = 168,
                        selected = frequencyHours == 168,
                        modifier = Modifier.weight(1f)
                    ) {
                        frequencyHours = 168
                        vaultManager?.setBackupFrequencyHours(168)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Botão Primário: Fazer Backup Agora
                Button(
                    onClick = {
                        pinInput = ""
                        showPinPromptForBackup = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RadarCyan),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isBackingUp
                ) {
                    if (isBackingUp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = DeepBlack,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Replicando cofre silencioso...", color = DeepBlack)
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = DeepBlack,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Fazer Backup Agora (Silencioso)",
                            color = DeepBlack,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Botão Secundário: Exportar Arquivo .backup (Contingência Pessoal)
                OutlinedButton(
                    onClick = {
                        pinInput = ""
                        showPinPromptForExport = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = BorderStroke(1.dp, DarkCardElevated)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Exportar Cópia Pessoal (.backup)", color = TextPrimary)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Botão Terciário: Restaurar de Arquivo Local
                TextButton(
                    onClick = {
                        restoreTextInput = ""
                        pinInput = ""
                        showRestoreFromTextDialog = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = null,
                        tint = EmeraldGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restaurar de Arquivo / Código de Backup", color = EmeraldGreen)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Explicação de Segurança Militar
                Text(
                    text = "🔒 Criptografia Zero-Knowledge: O cofre é compactado e trancado com chave AES-256 derivada exclusivamente do seu PIN. Nem os aparelhos familiares nem terceiros conseguem ler qualquer mensagem.",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // Modal para digitar o PIN antes de gerar o Backup Distribuído
    if (showPinPromptForBackup) {
        AlertDialog(
            onDismissRequest = { showPinPromptForBackup = false },
            title = { Text("Chave de Criptografia do Cofre", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Digite seu PIN de segurança de 6 dígitos. Este PIN será a única chave capaz de abrir o cofre no futuro:",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 8) pinInput = it },
                        label = { Text("PIN de Segurança") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RadarCyan)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput.length < 4) {
                            Toast.makeText(context, "Digite um PIN de pelo menos 4 dígitos.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        showPinPromptForBackup = false
                        isBackingUp = true
                        scope.launch {
                            val result = repository.executeDistributedBackup(pinInput)
                            isBackingUp = false
                            if (result.isSuccess) {
                                val count = result.getOrNull() ?: 0
                                backupStatusMessage = "Cofre cifrado replicado com sucesso em $count aparelho(s) de confiança!"
                                Toast.makeText(context, backupStatusMessage, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    result.exceptionOrNull()?.message ?: "Falha ao enviar cofre.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RadarCyan, contentColor = DeepBlack)
                ) {
                    Text("Cifrar & Replicar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinPromptForBackup = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Modal para digitar PIN e Exportar Arquivo (.backup)
    if (showPinPromptForExport) {
        AlertDialog(
            onDismissRequest = { showPinPromptForExport = false },
            title = { Text("Exportar Cópia Cifrada", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Digite seu PIN para gerar o arquivo criptografado de contingência pessoal (.backup):",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 8) pinInput = it },
                        label = { Text("PIN de Segurança") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = WarningAmber)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput.length < 4) {
                            Toast.makeText(context, "Digite um PIN válido.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        showPinPromptForExport = false
                        val exportRes = repository.exportLocalBackupJson(pinInput)
                        if (exportRes.isSuccess) {
                            val jsonStr = exportRes.getOrNull() ?: ""
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_SUBJECT, "Cofre Seguro PessoasAqui (.backup)")
                                putExtra(Intent.EXTRA_TEXT, jsonStr)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Salvar Cópia do Cofre Cifrado"))
                        } else {
                            Toast.makeText(context, "Falha ao gerar cofre: ${exportRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningAmber, contentColor = DeepBlack)
                ) {
                    Text("Exportar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinPromptForExport = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Modal para Restaurar a partir do Conteúdo/JSON de Backup
    if (showRestoreFromTextDialog) {
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showRestoreFromTextDialog = false },
            title = { Text("Restaurar Conversas de Backup", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Cole o código do cofre exportado e informe o PIN que foi usado para protegê-lo:",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = restoreTextInput,
                        onValueChange = { restoreTextInput = it },
                        label = { Text("Conteúdo do Cofre (JSON Cifrado)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let { restoreTextInput = it }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Colar", tint = RadarCyan)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 8) pinInput = it },
                        label = { Text("PIN de Descriptografia") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = EmeraldGreen)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (restoreTextInput.isBlank() || pinInput.isBlank()) {
                            Toast.makeText(context, "Preencha o cofre e o PIN.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val restoreRes = repository.restoreFromLocalBackupJson(restoreTextInput, pinInput)
                        if (restoreRes.isSuccess) {
                            val count = restoreRes.getOrNull() ?: 0
                            showRestoreFromTextDialog = false
                            backupStatusMessage = "Sucesso! $count mensagens restauradas com perfeição!"
                            Toast.makeText(context, backupStatusMessage, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(
                                context,
                                restoreRes.exceptionOrNull()?.message ?: "Falha na restauração. Verifique o PIN.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = DeepBlack)
                ) {
                    Text("Restaurar Conversas", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreFromTextDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun FrequencyChip(
    label: String,
    hours: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) RadarCyan.copy(alpha = 0.2f) else DarkSurface,
        border = BorderStroke(1.dp, if (selected) RadarCyan else DarkCardElevated)
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) RadarCyan else TextSecondary
                )
            )
        }
    }
}
