package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.core.crypto.PinSecurityManager
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Tela de Recuperação e Transferência de Identidade Criptográfica.
 * Arquitetura segura sem dados tradicionais (sem e-mail/senha).
 * Fluxo de recuperação e transferência de identidade via autorização de familiar + PIN pessoal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntreNaSuaRecoveryScreen(
    pinSecurityManager: PinSecurityManager,
    onFindAuthorization: (String) -> RecoveryAuthorization?,
    onClaimRecovery: (suspend (recoveryKey: String, pin: String) -> Result<String>)? = null,
    onRecoverySuccess: (String) -> Unit,
    onBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val recoverySessionId = remember { "session-device-new" }

    var recoveryKeyInput by remember { mutableStateOf("") }
    var validatedAuth by remember { mutableStateOf<RecoveryAuthorization?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }

    val lockoutState = pinSecurityManager.getLockoutState(recoverySessionId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "🔐 Entre na sua",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DeepBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card explicativo sobre a ausência de login tradicional
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Recuperação de Identidade Criptográfica",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = RadarCyan
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "O PessoasAqui não possui login com telefone, e-mail ou SMS. Para restaurar ou transferir sua identidade para este aparelho, utilize a autorização gerada por um familiar conectado.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            lineHeight = 18.sp
                        )
                    )
                }
            }

            if (isSuccess) {
                // Sucesso na recuperação
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "Identidade Restaurada com Sucesso!",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = EmeraldGreen
                            ),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "1 Identidade = 1 Dispositivo Ativo.\nO aparelho antigo foi revogado com segurança e este agora é seu dispositivo ativo.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextPrimary,
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = DeepBlack),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Acessar Minhas Pessoas e Conversas", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (validatedAuth == null) {
                // Passo 1: Inserir a Chave ou Simular Leitura do QR Code emitido pelo familiar
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Passo 1: Autorização Familiar",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "Peça para um familiar conectado (ex: Maria) abrir a conversa com você no app dele e selecionar: ⋮ → Recuperar identidade.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )

                        OutlinedTextField(
                            value = recoveryKeyInput,
                            onValueChange = { recoveryKeyInput = it.uppercase() },
                            label = { Text("Chave de Recuperação / QR Payload") },
                            placeholder = { Text("Ex: PA-REC-994A1F82") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            trailingIcon = {
                                IconButton(onClick = {
                                    val text = clipboardManager.getText()?.text
                                    if (!text.isNullOrBlank()) {
                                        recoveryKeyInput = text.trim().uppercase()
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Colar chave copiada",
                                        tint = RadarCyan
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarCyan,
                                unfocusedBorderColor = DarkCardElevated
                            )
                        )

                        Button(
                            onClick = {
                                val key = recoveryKeyInput.trim().uppercase()
                                val auth = onFindAuthorization(key)
                                if (auth != null) {
                                    validatedAuth = auth
                                    statusMessage = "Autorização de ${auth.authorizedByFamilyName} validada!"
                                } else if (key.startsWith("PA-REC-") || key.length >= 6) {
                                    validatedAuth = RecoveryAuthorization(
                                        targetIdentityHash = "REMOTE",
                                        authorizedByFamilyName = "Familiar Autorizado",
                                        recoveryKey = key,
                                        expiresAtEpochMs = System.currentTimeMillis() + 15 * 60 * 1000
                                    )
                                    statusMessage = "Chave de autorização identificada. Prossiga para o PIN."
                                } else {
                                    statusMessage = "Chave inválida ou expirada. Verifique se o familiar gerou a chave recentemente."
                                }
                            },
                            enabled = recoveryKeyInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RadarCyan,
                                contentColor = DeepBlack
                            )
                        ) {
                            Text("Validar Autorização", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Passo 2: Inserir o PIN pessoal do usuário (desconhecido pelo familiar)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = EmeraldGreen.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = EmeraldGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Autorizado por: ${validatedAuth?.authorizedByFamilyName}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = EmeraldGreen, fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        Text(
                            text = "Passo 2: Digite seu PIN Pessoal",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )

                        Text(
                            text = "O familiar NÃO conhece o seu PIN. Digite seu PIN pessoal cadastrado nesta identidade.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )

                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it },
                            label = { Text("Seu PIN Pessoal") },
                            singleLine = true,
                            enabled = !lockoutState.isLocked,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarCyan,
                                unfocusedBorderColor = DarkCardElevated
                            )
                        )

                        Button(
                            onClick = {
                                if (onClaimRecovery != null) {
                                    isLoading = true
                                    coroutineScope.launch {
                                        val result = onClaimRecovery(recoveryKeyInput, pinInput)
                                        isLoading = false
                                        if (result.isSuccess) {
                                            isSuccess = true
                                            onRecoverySuccess(result.getOrThrow())
                                        } else {
                                            statusMessage = result.exceptionOrNull()?.message
                                        }
                                    }
                                } else {
                                    val result = pinSecurityManager.verifyPinForRecovery(recoverySessionId, pinInput)
                                    if (result.isSuccess) {
                                        isSuccess = true
                                        onRecoverySuccess(validatedAuth!!.targetIdentityHash)
                                    } else {
                                        statusMessage = result.exceptionOrNull()?.message
                                    }
                                }
                            },
                            enabled = pinInput.isNotBlank() && !lockoutState.isLocked && !isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RadarCyan,
                                contentColor = DeepBlack
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = DeepBlack,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Restaurando...", fontWeight = FontWeight.Bold)
                            } else {
                                Text("Confirmar PIN e Restaurar Identidade", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Exibição de mensagens de status / erros / bloqueios progressivos
            if (statusMessage != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSuccess) EmeraldGreenGlow else AlertRed.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusMessage ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isSuccess) EmeraldGreen else AlertRed,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Alerta de Garantia de Isolamento de Dispositivo
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DarkCard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Regra de Segurança: Tentativas incorretas bloqueiam somente este novo aparelho. O seu aparelho antigo permanece intacto e conectado normalmente.",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                    )
                }
            }
        }
    }
}
