package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.BorderStroke
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
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import androidx.compose.ui.platform.LocalContext
import br.com.pessoasaqui.core.crypto.PinSecurityManager
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Tela de Recuperação e Transferência de Identidade Criptográfica.
 * Arquitetura segura sem dados tradicionais (sem e-mail/senha).
 * Fluxo de recuperação com aprovação familiar assinada; PIN do backup é independente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntreNaSuaRecoveryScreen(
    pinSecurityManager: PinSecurityManager,
    initialRecoveryKey: String? = null,
    onFindAuthorization: (String) -> RecoveryAuthorization?,
    onBeginRecovery: (suspend (recoveryKey: String) -> Result<Boolean>)? = null,
    onClaimRecovery: (suspend (recoveryKey: String, pin: String) -> Result<String>)? = null,
    onCheckRecoveryStatus: (suspend (recoveryKey: String) -> Result<String>)? = null,
    onRestoreConversations: (suspend (pin: String, recoverySecret: String?) -> Result<Int>)? = null,
    onRecoverySuccess: (String) -> Unit,
    onBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val qrScanner = remember(context) {
        GmsBarcodeScanning.getClient(
            context, GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
        )
    }
    val coroutineScope = rememberCoroutineScope()
    val recoverySessionId = remember { "session-device-new" }

    var recoveryKeyInput by remember { mutableStateOf(initialRecoveryKey.orEmpty()) }
    LaunchedEffect(initialRecoveryKey) {
        if (!initialRecoveryKey.isNullOrBlank())
            recoveryKeyInput = initialRecoveryKey
    }
    var validatedAuth by remember { mutableStateOf<RecoveryAuthorization?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var vaultRecoverySecret by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var isReadingQr by remember { mutableStateOf(false) }

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
                // Passo 1: ler QR real ou inserir chave assinada emitida pelo familiar
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
                            placeholder = { Text("PA-REC- + 64 caracteres hexadecimais") },
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

                        OutlinedButton(
                            onClick = {
                                if (!isReadingQr) {
                                    isReadingQr = true
                                    qrScanner.startScan().addOnSuccessListener { barcode ->
                                        val key = barcode.rawValue.orEmpty().trim().uppercase()
                                        if (Regex("^PA-REC-[A-F0-9]{64}$").matches(key)) {
                                            recoveryKeyInput = key
                                            statusMessage = "QR reconhecido. A autorização será verificada no servidor."
                                        } else {
                                            statusMessage = "QR inválido. Use o código oficial de recuperação familiar."
                                        }
                                    }.addOnFailureListener {
                                        statusMessage = "Leitor indisponível. Utilize Colar ou digite a chave."
                                    }.addOnCompleteListener {
                                        isReadingQr = false
                                    }
                                }
                            },
                            enabled = !isReadingQr,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isReadingQr) "Abrindo leitor..." else "Ler QR de recuperação")
                        }

                        Button(
                            onClick = {
                                val key = recoveryKeyInput.trim().uppercase()
                                val auth = onFindAuthorization(key)
                                if (auth != null) {
                                    validatedAuth = auth
                                    statusMessage = "Autorização de ${auth.authorizedByFamilyName} validada!"
                                } else if (Regex("^PA-REC-[A-F0-9]{64}$").matches(key)) {
                                    validatedAuth = RecoveryAuthorization(
                                        targetIdentityHash = "REMOTE",
                                        authorizedByFamilyName = "Familiar Autorizado",
                                        recoveryKey = key,
                                        expiresAtEpochMs = System.currentTimeMillis() + 15 * 60 * 1000
                                    )
                                    statusMessage = "Formato reconhecido. A validade será conferida pelo servidor ao confirmar."
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
                            text = "O familiar não conhece seu PIN. Ele será usado somente para abrir o backup criptografado, se disponível. A transferência da identidade exige autorização familiar assinada, verificada pelo servidor.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )

                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it },
                            label = { Text("PIN do backup criptografado") },
                            singleLine = true,
                            enabled = !lockoutState.isLocked,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarCyan,
                                unfocusedBorderColor = DarkCardElevated
                            )
                        )

                        Text(
                            text = "Esta recuperação depende de dois familiares autorizados. Após iniciar, peça a outro familiar distinto para escolher Aprovar recuperação na aba Pessoas. LINK remoto exige 1 hora. O PIN nunca é enviado ao servidor.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        if (onBeginRecovery != null) {
                            Button(
                                enabled = !isLoading,
                                onClick = {
                                    isLoading = true
                                    coroutineScope.launch {
                                        val outcome=onBeginRecovery(recoveryKeyInput)
                                        isLoading=false
                                        statusMessage=if(outcome.isSuccess)
                                            "Solicitação iniciada neste aparelho. Agora peça ao segundo familiar autorizado para aprovar o mesmo QR/código. Após os avales, conclua a transferência."
                                        else outcome.exceptionOrNull()?.message ?: "Falha ao iniciar recuperação."
                                    }
                                }
                            ) { Text("1. Iniciar recuperação neste aparelho") }
                        }
                        if (onCheckRecoveryStatus != null) {
                            OutlinedButton(
                                enabled = !isLoading,
                                onClick = {
                                    isLoading = true
                                    coroutineScope.launch {
                                        val status = onCheckRecoveryStatus(recoveryKeyInput)
                                        isLoading = false
                                        statusMessage = status.fold(
                                            onSuccess = { when(it) {
                                                "READY" -> "Segundo familiar confirmou. Recuperação pronta para conclusão."
                                                "WAITING_SECOND_FAMILY" -> "Aguardando segundo familiar autorizado, diferente do emissor."
                                                "WAITING_SECURITY_WINDOW" -> "Segunda autorização confirmada. Aguarde o prazo de proteção do LINK (1 hora)."
                                                "SUSPENDED" -> "Recuperação suspensa após alerta de segurança."
                                                "USED" -> "Código já utilizado. Gere outro."
                                                "EXPIRED" -> "Código expirado. Gere outro."
                                                else -> "Estado da recuperação: $it"
                                            }},
                                            onFailure = { it.message ?: "Consulta indisponível." }
                                        )
                                    }
                                }
                            ) { Text("Consultar estado da recuperação") }
                        }

                        Button(
                            onClick = {
                                if (onClaimRecovery != null) {
                                    isLoading = true
                                    coroutineScope.launch {
                                        val result = onClaimRecovery(recoveryKeyInput, pinInput)
                                        isLoading = false
                                        if (result.isSuccess) {
                                            isSuccess = true
                                            // O PIN só é confirmado quando um cofre real
                                            // descriptografa corretamente; não sobrescrever
                                            // o PIN antigo antes dessa validação.
                                            onRecoverySuccess(result.getOrThrow())
                                        } else {
                                            statusMessage = result.exceptionOrNull()?.message
                                        }
                                    }
                                } else {
                                    statusMessage = "Recuperação local insegura desativada. Atualize o aplicativo."
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
                                Text("Confirmar autorização e transferir identidade", fontWeight = FontWeight.Bold)
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

            // Oferta de Restauração de Conversas a partir dos Familiares
            if (isSuccess && onRestoreConversations != null) {
                var isRestoringVault by remember { mutableStateOf(false) }
                var vaultStatus by remember { mutableStateOf<String?>(null) }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "💬 Restaurar Histórico de Conversas?",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = RadarCyan
                            )
                        )
                        Text(
                            text = "O cofre silencioso criptografado pode ser resgatado dos seus familiares de confiança agora.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = "Cofres V3 exigem, além do PIN, o segredo de recuperação que você guardou separadamente. Familiares armazenam o cofre cifrado, não esse segredo. Cofres V1/V2 continuam aceitando só PIN.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        OutlinedTextField(
                            value = vaultRecoverySecret,
                            onValueChange = { if (it.length <= 76) vaultRecoverySecret = it.trim().uppercase(java.util.Locale.ROOT) },
                            label = { Text("Segredo V3 (apenas para cofres novos)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (vaultStatus != null) {
                            Text(
                                text = vaultStatus ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(color = EmeraldGreen)
                            )
                        }
                        Button(
                            onClick = {
                                isRestoringVault = true
                                coroutineScope.launch {
                                    val res = onRestoreConversations(pinInput, vaultRecoverySecret.ifBlank { null })
                                    isRestoringVault = false
                                    if (res.isSuccess) {
                                        val count = res.getOrNull() ?: 0
                                        vaultStatus = "✨ $count mensagens restauradas dos familiares com sucesso!"
                                    } else {
                                        vaultStatus = res.exceptionOrNull()?.message ?: "Falha ao consultar familiares."
                                    }
                                }
                            },
                            enabled = !isRestoringVault,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = DeepBlack)
                        ) {
                            if (isRestoringVault) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = DeepBlack, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Buscando cofre nos familiares...", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Baixar e Restaurar Conversas dos Familiares", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
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
