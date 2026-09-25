package br.com.pessoasaqui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.ui.screens.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.pessoasaqui.ui.theme.*

data class IncomingInvite(
    val inviteId: String,
    val token: String,
    val senderIdentity: String,
    val senderAlias: String,
    val signature: String? = null,
    val expiresAt: Long? = null
)

class MainActivity : ComponentActivity() {

    private val incomingInvite = mutableStateOf<IncomingInvite?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLockscreenWakeupFlags()
        parseDeepLink(intent)

        val app = application as? PessoasAquiApp
        val repository = app?.repository ?: PessoasAquiRepository()
        handleIncomingCallIntent(intent, repository)

        // Solicita POST_NOTIFICATIONS em aparelhos Android 13+ que já haviam passado pelo onboarding
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 905)
            }
        }

        setContent {
            PessoasAquiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepBlack
                ) {
                    PessoasAquiNavHost(
                        repository = repository,
                        incomingInvite = incomingInvite.value,
                        onClearIncomingInvite = { incomingInvite.value = null }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as? PessoasAquiApp)?.repository?.callRingtoneWakeManager?.isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        (application as? PessoasAquiApp)?.repository?.callRingtoneWakeManager?.isAppInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enableLockscreenWakeupFlags()
        parseDeepLink(intent)
        val repository = (application as? PessoasAquiApp)?.repository
        if (repository != null) {
            handleIncomingCallIntent(intent, repository)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableLockscreenWakeupFlags() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        } catch (_: Exception) {}
    }

    private fun handleIncomingCallIntent(intent: Intent?, repository: PessoasAquiRepository) {
        val action = intent?.action ?: return
        when (action) {
            br.com.pessoasaqui.core.media.CallRingtoneAndWakeManager.ACTION_ANSWER_CALL -> {
                repository.answerCall()
            }
            br.com.pessoasaqui.core.media.CallRingtoneAndWakeManager.ACTION_DECLINE_CALL -> {
                repository.endCall()
            }
        }
    }

    private fun parseDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val id = uri.getQueryParameter("id") ?: uri.getQueryParameter("inviteId")
        val token = uri.getQueryParameter("token")
        val sender = uri.getQueryParameter("sender") ?: uri.getQueryParameter("senderIdentity")
        val alias = uri.getQueryParameter("alias") ?: uri.getQueryParameter("senderAlias") ?: "Usuário"
        val sig = uri.getQueryParameter("sig") ?: uri.getQueryParameter("signature")
        val exp = uri.getQueryParameter("exp")?.toLongOrNull() ?: uri.getQueryParameter("expiresAt")?.toLongOrNull()

        if (!id.isNullOrBlank() && !token.isNullOrBlank() && !sender.isNullOrBlank()) {
            incomingInvite.value = IncomingInvite(
                inviteId = id.trim(),
                token = token.trim(),
                senderIdentity = sender.trim(),
                senderAlias = alias.trim(),
                signature = sig?.trim(),
                expiresAt = exp
            )
        }
    }
}

sealed class Screen {
    object AgeGate : Screen()
    object ProfileSetup : Screen()
    object Permissions : Screen()
    object Main : Screen()
    data class Chat(val person: NearbyPerson) : Screen()
    object EntreNaSua : Screen()
}

@Composable
fun PessoasAquiNavHost(
    repository: PessoasAquiRepository,
    incomingInvite: IncomingInvite? = null,
    onClearIncomingInvite: () -> Unit = {}
) {
    val isAgeVerified by repository.isAgeVerified.collectAsState()
    val hasCompletedProfile by repository.hasCompletedProfile.collectAsState()
    val hasGrantedPermissions by repository.hasGrantedPermissions.collectAsState()
    val currentAlias by repository.currentAlias.collectAsState()
    val privateChats by repository.privateChats.collectAsState()
    val discoveredPeople by repository.discoveredPeople.collectAsState()
    val activeCallState by repository.activeCallState.collectAsState()
    val pendingFamilyRequest by repository.pendingFamilyRequest.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.AgeGate) }
    var activeFamilyRecoveryAuth by remember { mutableStateOf<Pair<NearbyPerson, RecoveryAuthorization>?>(null) }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            repository.restartBleHardware()
        }
    }

    // Sincroniza estado de onboarding inicial em ordem lógica de produção:
    // 1. Barreira +18 -> 2. Perfil (Nome & Intenção) -> 3. Permissões de Rádio -> 4. Radar Ativo
    LaunchedEffect(isAgeVerified, hasCompletedProfile, hasGrantedPermissions) {
        if (!isAgeVerified) {
            currentScreen = Screen.AgeGate
        } else if (!hasCompletedProfile) {
            currentScreen = Screen.ProfileSetup
        } else if (!hasGrantedPermissions) {
            currentScreen = Screen.Permissions
        } else if (currentScreen is Screen.AgeGate || currentScreen is Screen.ProfileSetup || currentScreen is Screen.Permissions) {
            currentScreen = Screen.Main
        }
    }

    when (val screen = currentScreen) {
        is Screen.AgeGate -> {
            AgeGateScreen(
                onAgeVerified = {
                    repository.completeAgeVerification()
                }
            )
        }

        is Screen.ProfileSetup -> {
            ProfileSetupScreen(
                initialAlias = currentAlias,
                onProfileCompleted = { alias, intent ->
                    repository.completeProfile(alias, intent)
                }
            )
        }

        is Screen.Permissions -> {
            PermissionScreen(
                onPermissionsGranted = {
                    repository.completePermissions()
                }
            )
        }

        is Screen.Main -> {
            MainScreen(
                repository = repository,
                onOpenEntreNaSua = { currentScreen = Screen.EntreNaSua },
                onOpenChat = { person -> currentScreen = Screen.Chat(person) },
                onEnableBluetooth = {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableBtIntent)
                }
            )
        }

        is Screen.Chat -> {
            BackHandler { currentScreen = Screen.Main }
            val livePerson = repository.getLivePerson(screen.person)

            val cleanTargetId = repository.cleanId(livePerson.id)
            val messages = privateChats[livePerson.id] 
                ?: privateChats[cleanTargetId] 
                ?: privateChats[livePerson.technicalIdentityHash] 
                ?: privateChats.entries.firstOrNull { 
                    val k = repository.cleanId(it.key)
                    k == cleanTargetId || 
                    k == repository.cleanId(livePerson.technicalIdentityHash) ||
                    (!livePerson.alias.startsWith("Pessoa Próxima #") && it.key.equals(livePerson.alias, ignoreCase = true))
                }?.value 
                ?: emptyList()

            ChatDetailScreen(
                person = livePerson,
                messages = messages,
                onBack = { currentScreen = Screen.Main },
                onSendMessage = { text ->
                    repository.sendPrivateMessage(livePerson.technicalIdentityHash.ifBlank { livePerson.id }, text)
                },
                onStartCall = { targetPerson, isVideo ->
                    repository.startCall(targetPerson, isVideo)
                },
                onSendAudioMessage = { targetPerson, duration, audioBase64 ->
                    repository.sendAudioMessage(targetPerson.technicalIdentityHash.ifBlank { targetPerson.id }, duration, audioBase64)
                },
                onSendImageMessage = { targetPerson, base64 ->
                    repository.sendImageMessage(targetPerson.technicalIdentityHash.ifBlank { targetPerson.id }, base64)
                },
                onSendDocumentMessage = { targetPerson, fileName, base64, size ->
                    repository.sendDocumentMessage(targetPerson.technicalIdentityHash.ifBlank { targetPerson.id }, fileName, base64, size)
                },
                onToggleMarkPerson = { targetPerson ->
                    repository.toggleMarkPerson(targetPerson.id)
                },
                onAcceptMutualConnection = { targetPerson ->
                    repository.acceptMutualConnection(targetPerson.id)
                },
                onRequestFamilyRole = { targetPerson, role ->
                    repository.requestFamilyRole(targetPerson, role)
                },
                onGenerateFamilyRecovery = { targetPerson ->
                    val auth = repository.generateFamilyRecovery(
                        targetIdentityHash = targetPerson.technicalIdentityHash,
                        familyName = targetPerson.alias
                    )
                    activeFamilyRecoveryAuth = Pair(targetPerson, auth)
                },
                onBlockUser = { userId ->
                    repository.moderationManager.blockUser(userId)
                    currentScreen = Screen.Main
                },
                onReportUser = { userId ->
                    repository.moderationManager.blockUser(userId)
                    currentScreen = Screen.Main
                }
            )

            activeFamilyRecoveryAuth?.let { (person, auth) ->
                FamilyRecoveryDialog(
                    person = person,
                    authorization = auth,
                    onDismiss = { activeFamilyRecoveryAuth = null }
                )
            }
        }

        is Screen.EntreNaSua -> {
            BackHandler { currentScreen = Screen.Main }

            EntreNaSuaRecoveryScreen(
                pinSecurityManager = repository.pinSecurityManager,
                onFindAuthorization = { key ->
                    repository.proximitySimulator.findActiveRecoveryAuthorization(key)
                },
                onClaimRecovery = { key, pin ->
                    repository.claimRecovery(key, pin)
                },
                onRecoverySuccess = { recoveredHash ->
                    repository.transferIdentityToThisDevice(recoveredHash)
                },
                onBack = { currentScreen = Screen.Main }
            )
        }
    }

    // Sobreposição de Chamada Ativa (Voz ou Vídeo)
    activeCallState?.let { call ->
        CallOverlayDialog(
            callState = call,
            onAnswer = { repository.answerCall() },
            onEnd = { repository.endCall() },
            onToggleMute = { repository.toggleCallMute() },
            onToggleCamera = { repository.toggleCallCamera() },
            onToggleSpeakerphone = { repository.toggleCallSpeakerphone() }
        )
    }

    // Diálogo de Solicitação Familiar Recebida com Confirmação Mútua
    pendingFamilyRequest?.let { req ->
        FamilyIncomingRequestDialog(
            request = req,
            onAccept = { person, role -> repository.acceptFamilyRole(person, role) },
            onReject = { repository.rejectFamilyRequest() }
        )
    }

    // Diálogo de Convite de Conexão Mútua Recebido via Deep Link (Uso Único)
    incomingInvite?.let { invite ->
        var isRedeeming by remember(invite.inviteId) { mutableStateOf(false) }
        var errorMessage by remember(invite.inviteId) { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                if (!isRedeeming) onClearIncomingInvite()
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = EmeraldGreen,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Convite de Conexão Segura",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${invite.senderAlias} convidou você para uma conexão mútua à distância!",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = "Este convite é de uso único. Ao aceitar, vocês poderão conversar livremente a qualquer distância com mensagens, chamadas e fotos criptografadas.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = AlertRed,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isRedeeming = true
                        errorMessage = null
                        repository.redeemOneTimeInvite(
                            inviteId = invite.inviteId,
                            token = invite.token,
                            senderIdentity = invite.senderIdentity,
                            senderAlias = invite.senderAlias,
                            signature = invite.signature,
                            expiresAt = invite.expiresAt
                        ) { success, msg, peer ->
                            isRedeeming = false
                            if (success && peer != null) {
                                onClearIncomingInvite()
                                currentScreen = Screen.Chat(peer)
                            } else {
                                errorMessage = msg
                            }
                        }
                    },
                    enabled = !isRedeeming,
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Validando...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("★ Aceitar Conexão", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onClearIncomingInvite,
                    enabled = !isRedeeming
                ) {
                    Text("Recusar", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
