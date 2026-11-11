package br.com.pessoasaqui

import br.com.pessoasaqui.core.qrcode.InviteLinkParser

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import br.com.pessoasaqui.core.subscription.SubscriptionTier
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    private val incomingRecoveryKey = mutableStateOf<String?>(null)
    private val targetPeerToOpen = mutableStateOf<NearbyPerson?>(null)
    // Enquanto uma chamada puder aparecer sobre o Keyguard, nunca compor conversas sob ela.
    private val callWindowMode = mutableStateOf(false)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enableLockscreenWakeupFlags()
        parseDeepLink(intent)

        val app = application as? PessoasAquiApp
        val repository = app?.repository ?: PessoasAquiRepository()
        handleIncomingCallIntent(intent, repository)
        handleReminderIntent(intent, repository)

        // A permissão de aparecer sobre o bloqueio dura apenas durante a chamada.
        // Observa também encerramentos remotos e timeouts, não apenas botões da UI.
        lifecycleScope.launch {
            repository.activeCallState.collect { call ->
                if (call == null && callWindowMode.value) {
                    revokeCallWindowPrivileges()
                }
            }
        }

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
                    if (callWindowMode.value) {
                        // Não renderizar telas privadas sob a chamada visível no bloqueio.
                        val call by repository.activeCallState.collectAsState()
                        call?.let { active ->
                            CallOverlayDialog(
                                callState = active,
                                onAnswer = { repository.answerCall() },
                                onEnd = { repository.endCall() },
                                onToggleMute = { repository.toggleCallMute() },
                                onToggleCamera = { repository.toggleCallCamera() },
                                onToggleSpeakerphone = { repository.toggleCallSpeakerphone() }
                            )
                        }
                    } else {
                        PessoasAquiNavHost(
                            repository = repository,
                            incomingInvite = incomingInvite.value,
                            onClearIncomingInvite = { incomingInvite.value = null },
                            incomingRecoveryKey = incomingRecoveryKey.value,
                            onClearIncomingRecovery = { incomingRecoveryKey.value = null },
                            targetPeerToOpen = targetPeerToOpen.value,
                            onClearTargetPeerToOpen = { targetPeerToOpen.value = null }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Transição real de visibilidade, sem timer, nem reinicializar GATT ativo.
        (application as? PessoasAquiApp)?.repository?.setBleAppForeground(true)
    }

    override fun onStop() {
        (application as? PessoasAquiApp)?.repository?.setBleAppForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Ao desbloquear e voltar ao app, restaura a UI apenas quando não há chamada.
        if (callWindowMode.value &&
            (application as? PessoasAquiApp)?.repository?.activeCallState?.value == null) {
            revokeCallWindowPrivileges()
        }
        val repo = (application as? PessoasAquiApp)?.repository
        repo?.callRingtoneWakeManager?.isAppInForeground = true
        repo?.playBillingManager?.queryActivePurchases()
        repo?.ensureBleHealthOnForeground()
        if (repo?.hasGrantedPermissions?.value == true) {
            br.com.pessoasaqui.core.service.PessoasAquiForegroundService.startServiceSafely(this)
        }
    }

    override fun onPause() {
        super.onPause()
        (application as? PessoasAquiApp)?.repository?.callRingtoneWakeManager?.isAppInForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as? PessoasAquiApp)?.repository?.playBillingManager?.destroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enableLockscreenWakeupFlags()
        parseDeepLink(intent)
        val repository = (application as? PessoasAquiApp)?.repository
        if (repository != null) {
            handleIncomingCallIntent(intent, repository)
            handleReminderIntent(intent, repository)
        }
    }

    /**
     * A tela principal não pode acordar ou atravessar a tela de bloqueio
     * durante aberturas comuns. Essas permissões de janela são necessárias
     * apenas para exibir a interface de uma chamada recebida.
     */
    @Suppress("DEPRECATION")
    private fun enableLockscreenWakeupFlags() {
        val action = intent?.action
        val incomingCall = action ==
            br.com.pessoasaqui.core.media.CallRingtoneAndWakeManager.ACTION_INCOMING_CALL ||
            action == br.com.pessoasaqui.core.media.CallRingtoneAndWakeManager.ACTION_ANSWER_CALL
        if (!incomingCall && callWindowMode.value) {
            revokeCallWindowPrivileges()
            return
        }
        callWindowMode.value = incomingCall
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(incomingCall)
            setTurnScreenOn(incomingCall)
        } else {
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (incomingCall) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    private fun isSystemKeyguardShowing(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun revokeCallWindowPrivileges() {
        // Mantém a superfície privada desmontada enquanto o Keyguard ainda está visível.
        if (isSystemKeyguardShowing()) {
            moveTaskToBack(true)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            window.clearFlags(flags)
        }
        // A ação antiga não pode conceder acesso ao Keyguard em um ciclo futuro.
        setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
        if (!isSystemKeyguardShowing()) callWindowMode.value = false
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

    private fun handleReminderIntent(intent: Intent?, repository: PessoasAquiRepository) {
        val targetId = intent?.getStringExtra("OPEN_TARGET_PEER_ID") ?: return
        val targetAlias = intent.getStringExtra("OPEN_TARGET_PEER_ALIAS") ?: "Contato"
        if (targetId.isNotBlank() && targetId != "self") {
            val livePerson = repository.getLivePerson(
                NearbyPerson(
                    id = targetId,
                    technicalIdentityHash = targetId,
                    alias = targetAlias,
                    estimatedDistanceMeters = 500.0,
                    proximityLabel = "Contato Salvo",
                    isMutualConnection = true
                )
            )
            targetPeerToOpen.value = livePerson
        }
    }

    private fun parseDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        // LINK de recuperação: somente domínio oficial ou esquema do app.
        // Em HTTPS o código fica no fragmento e nunca é enviado no GET.
        val recoveryUrl = (uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("pessoasaqui3.onrender.com", ignoreCase = true) &&
            uri.path?.trimEnd('/') == "/recover") ||
            (uri.scheme.equals("pessoasaqui", ignoreCase = true) &&
             uri.host.equals("recover", ignoreCase = true))
        if (recoveryUrl) {
            val source = if (uri.scheme.equals("https", ignoreCase = true))
                uri.fragment.orEmpty() else uri.query.orEmpty()
            val match = Regex("(?:^|&)key=(PA-REC-[A-F0-9]{64})(?:&|$)",
                RegexOption.IGNORE_CASE).find(source)
            incomingRecoveryKey.value=match?.groupValues?.get(1)
                ?.uppercase(java.util.Locale.ROOT)
            return
        }
        // Deep links externos são entrada não confiável: aplicar a mesma
        // política estrita de domínio, ID e token usada em QR/clipboard.
        val invite = InviteLinkParser.parse(uri.toString()) ?: return
        incomingInvite.value = IncomingInvite(
            inviteId = invite.id,
            token = invite.token,
            senderIdentity = invite.sender,
            senderAlias = invite.alias,
            signature = invite.signature,
            expiresAt = invite.expiresAt
        )
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
    onClearIncomingInvite: () -> Unit = {},
    incomingRecoveryKey: String? = null,
    onClearIncomingRecovery: () -> Unit = {},
    targetPeerToOpen: NearbyPerson? = null,
    onClearTargetPeerToOpen: () -> Unit = {}
) {
    val isAgeVerified by repository.isAgeVerified.collectAsState()
    val hasCompletedProfile by repository.hasCompletedProfile.collectAsState()
    val hasGrantedPermissions by repository.hasGrantedPermissions.collectAsState()
    val currentAlias by repository.currentAlias.collectAsState()
    val privateChats by repository.privateChats.collectAsState()
    val discoveredPeople by repository.discoveredPeople.collectAsState()
    val activeCallState by repository.activeCallState.collectAsState()
    val pendingFamilyRequest by repository.pendingFamilyRequest.collectAsState()
    val pendingFamilyShareRelease by repository.pendingFamilyShareRelease.collectAsState()
    val recurringReminders by repository.recurringReminders.collectAsState()
    val subscriptionTier by (repository.subscriptionManager?.currentTier ?: MutableStateFlow(SubscriptionTier.FREE)).collectAsState()
    val availableAppUpdate by repository.availableAppUpdate.collectAsState()
    val contactTags by repository.contactTags.collectAsState()
    val contactTagAssignments by repository.contactTagAssignments.collectAsState()
    val scope = rememberCoroutineScope()
    val connectedPeople = remember(discoveredPeople) {
        discoveredPeople
            .filter { it.isMutualConnection || it.isFamily }
            .distinctBy { it.technicalIdentityHash.ifBlank { it.id } }
    }
    val context = LocalContext.current

    // Verificação de atualização oficial do Google Play ao iniciar
    LaunchedEffect(Unit) {
        val (curCode, curName) = try {
            val pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            Pair(code, pInfo.versionName ?: "1.2.4")
        } catch (_: Exception) { Pair(20L, "1.2.4") }
        repository.checkForAppUpdates(curCode, curName)
    }

    var currentScreen by remember { mutableStateOf<Screen>(Screen.AgeGate) }
    // Abertura em Conversas: evita entrar no Radar visual por padrão.
    // A política de suspensão do scanner BLE será aplicada separadamente,
    // pois o transporte de sessões temporárias ativas não pode ser interrompido.
    var currentMainTab by remember { mutableIntStateOf(1) }
    var activeFamilyRecoveryAuth by remember { mutableStateOf<Pair<NearbyPerson, RecoveryAuthorization>?>(null) }
    var familyRecoveryError by remember { mutableStateOf<String?>(null) }
    var isCreatingInvite by remember { mutableStateOf(false) }
    var showVipDialog by remember { mutableStateOf(false) }

    // Navegação é a fonte de verdade para manter ou suspender discovery BLE.
    // O coordenador GATT continua separado, preservando sessões já abertas.
    // Reavaliar apenas quando o estado temporário/permanente mudar,
    // não em cada pacote do scanner (isso reiniciaria timers de economia).
    val temporaryChatOpen = (currentScreen as? Screen.Chat)?.person?.let { person ->
        val current = repository.getLivePerson(person)
        !current.isMutualConnection && !current.isFamily
    } == true
    LaunchedEffect(currentScreen, currentMainTab, temporaryChatOpen) {
        val onMain = currentScreen is Screen.Main
        repository.onRadioUiChanged(
            radarVisible = onMain && currentMainTab == 0,
            chatOpen = temporaryChatOpen,
            offersVisible = onMain && currentMainTab == 2
        )
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            repository.restartBleHardware()
        }
    }

    LaunchedEffect(targetPeerToOpen) {
        targetPeerToOpen?.let { person ->
            currentScreen = Screen.Chat(person)
            onClearTargetPeerToOpen()
        }
    }

    LaunchedEffect(incomingRecoveryKey, isAgeVerified, hasCompletedProfile, hasGrantedPermissions) {
        if (incomingRecoveryKey != null && isAgeVerified &&
            hasCompletedProfile && hasGrantedPermissions) {
            currentScreen = Screen.EntreNaSua
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
                selectedTab = currentMainTab,
                onTabSelected = { currentMainTab = it },
                onOpenEntreNaSua = { currentScreen = Screen.EntreNaSua },
                onOpenChat = { person ->
                    currentMainTab = 1
                    currentScreen = Screen.Chat(person)
                },
                onEnableBluetooth = {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableBtIntent)
                }
            )
        }

        is Screen.Chat -> {
            BackHandler {
                currentMainTab = 1
                currentScreen = Screen.Main
            }
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
                onBack = {
                    currentMainTab = 1
                    currentScreen = Screen.Main
                },
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
                onRevokeFamilyRecovery = { targetPerson ->
                    scope.launch {
                        repository.revokeFamilyForRecovery(
                            targetPerson.technicalIdentityHash.ifBlank { targetPerson.id }
                        ).onSuccess {
                            familyRecoveryError = "Autorização familiar revogada no servidor. Os códigos pendentes foram invalidados."
                        }.onFailure { error ->
                            familyRecoveryError = error.message
                                ?: "Não foi possível confirmar a revogação."
                        }
                    }
                },
                onAuthorizeFamilyRecovery = { targetPerson ->
                    scope.launch {
                        repository.authorizeFamilyForRecovery(
                            targetPerson.technicalIdentityHash.ifBlank { targetPerson.id }
                        ).onSuccess {
                            familyRecoveryError = "Autorização familiar registrada com assinatura digital no servidor."
                        }.onFailure { error ->
                            familyRecoveryError = error.message
                                ?: "Não foi possível autorizar este familiar para recuperação."
                        }
                    }
                },
                onGenerateFamilyRecovery = { targetPerson, method ->
                    // Autorizações locais simuladas não transferem identidades.
                    scope.launch {
                        val result = repository.issueFamilyRecoveryRemote(
                            targetIdentityHash = targetPerson.technicalIdentityHash,
                            method = method
                        )
                        result.onSuccess { auth ->
                            activeFamilyRecoveryAuth = Pair(targetPerson, auth)
                        }.onFailure { error ->
                            familyRecoveryError = error.message
                                ?: "Não foi possível autenticar a autorização familiar."
                        }
                    }
                },
                onBlockUser = { userId ->
                    repository.moderationManager.blockUser(userId)
                    currentMainTab = 1
                    currentScreen = Screen.Main
                },
                onReportUser = { userId ->
                    repository.reportAndBlockUser(
                        accusedIdentity = userId,
                        violationType = "ABUSE",
                        reason = "Denúncia de abuso enviada pelo usuário"
                    )
                    currentScreen = Screen.Main
                },
                onReportUserWithDetails = { accusedId, vType, reason, evidence ->
                    repository.reportAndBlockUser(
                        accusedIdentity = accusedId,
                        violationType = vType,
                        reason = reason,
                        evidenceMessages = evidence
                    )
                    currentScreen = Screen.Main
                },
                recurringReminders = recurringReminders,
                onSaveReminder = { reminder -> repository.saveRecurringReminder(reminder) },
                onDeleteReminder = { id -> repository.deleteRecurringReminder(id) },
                onToggleReminder = { id, enabled -> repository.toggleRecurringReminder(id, enabled) },
                onConfirmReminder = { id -> repository.confirmRecurringReminder(id) },
                allConnectedPeople = connectedPeople,
                maxAllowedBroadcastRecipients = subscriptionTier.maxBroadcastRecipients,
                remainingBroadcastCooldownMs = repository.subscriptionManager?.getRemainingCooldownMs() ?: 0L,
                onSendBroadcast = { targetIds, message, onProgress, onResult ->
                    scope.launch {
                        val res = repository.sendBroadcastMessage(targetIds, message, onProgress)
                        if (res.isSuccess) {
                            onResult(true, "")
                        } else {
                            onResult(false, res.exceptionOrNull()?.message ?: "Falha no envio da transmissão")
                        }
                    }
                },
                onSendBroadcastWithMedia = { targetIds, message, media, onProgress, onResult ->
                    scope.launch {
                        val res = repository.sendBroadcastMessage(targetIds, message, media, onProgress)
                        if (res.isSuccess) {
                            onResult(true, "")
                        } else {
                            onResult(false, res.exceptionOrNull()?.message ?: "Falha no envio da transmissão")
                        }
                    }
                },
                isCreatingInvite = isCreatingInvite,
                onCreateInviteLink = {
                    if (!isCreatingInvite) {
                        isCreatingInvite = true
                        repository.createOneTimeInviteLink { url ->
                            isCreatingInvite = false
                            if (url != null) {
                                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Convite Seguro - PessoasAqui")
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "Olá! Estou te convidando para nos conectarmos no PessoasAqui com conversa privada, chamadas de voz e vídeo criptografadas à distância.\n\nToque no link abaixo para aceitar meu convite de conexão mútua (link seguro de uso único):\n$url"
                                    )
                                    type = "text/plain"
                                }
                                val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartilhar Convite Seguro")
                                context.startActivity(shareIntent)
                            }
                        }
                    }
                },
                onRedeemInviteLink = { rawInput, onResult ->
                    val text = rawInput.trim()
                    val urlPattern = Regex("""(https?://[^\s]+|pessoasaqui://[^\s]+)""")
                    val matchedUrl = urlPattern.find(text)?.value ?: text
                    val parsedUri = try { android.net.Uri.parse(matchedUrl) } catch (_: Exception) { null }

                    val id = parsedUri?.getQueryParameter("id")
                        ?: parsedUri?.getQueryParameter("inviteId")
                        ?: Regex("""[?&](?:id|inviteId)=([a-zA-Z0-9_\-]+)""").find(text)?.groupValues?.get(1)

                    val token = parsedUri?.getQueryParameter("token")
                        ?: Regex("""[?&]token=([a-zA-Z0-9_\-]+)""").find(text)?.groupValues?.get(1)

                    val sender = parsedUri?.getQueryParameter("sender")
                        ?: parsedUri?.getQueryParameter("senderIdentity")
                        ?: Regex("""[?&](?:sender|senderIdentity)=([a-zA-Z0-9_\-]+)""").find(text)?.groupValues?.get(1)

                    val rawAlias = parsedUri?.getQueryParameter("alias")
                        ?: parsedUri?.getQueryParameter("senderAlias")
                        ?: Regex("""[?&](?:alias|senderAlias)=([^&\s]+)""").find(text)?.groupValues?.get(1)
                    val alias = rawAlias?.let {
                        try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                    } ?: "Usuário"

                    val sig = parsedUri?.getQueryParameter("sig")
                        ?: parsedUri?.getQueryParameter("signature")
                        ?: Regex("""[?&](?:sig|signature)=([a-zA-Z0-9_\-]+)""").find(text)?.groupValues?.get(1)

                    val exp = parsedUri?.getQueryParameter("exp")?.toLongOrNull()
                        ?: parsedUri?.getQueryParameter("expiresAt")?.toLongOrNull()
                        ?: Regex("""[?&](?:exp|expiresAt)=([0-9]+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()

                    if (!id.isNullOrBlank() && !token.isNullOrBlank() && !sender.isNullOrBlank()) {
                        repository.redeemOneTimeInvite(
                            inviteId = id.trim(),
                            token = token.trim(),
                            senderIdentity = sender.trim(),
                            senderAlias = alias.trim(),
                            signature = sig?.trim(),
                            expiresAt = exp
                        ) { success, msg, peer ->
                            onResult(success, msg, peer)
                        }
                    } else {
                        onResult(false, "Link ou formato de convite inválido.", null)
                    }
                },
                isVip = true, // Recursos VIP pré-ativados conforme diretriz do usuário
                onUpgradeToVip = { showVipDialog = true },
                contactTags = contactTags,
                contactTagAssignments = contactTagAssignments,
                onToggleContactTag = { contactId, tagId -> repository.toggleTagForContact(contactId, tagId) },
                onCreateContactTag = { name, colorHex -> repository.createContactTag(name, colorHex) },
                onDeleteContactTag = { tagId -> repository.deleteContactTag(tagId) },
                onMarkMessagesAsRead = { id -> repository.markMessagesAsRead(id) },
                onToggleImportantMessage = { peerId, msgId -> repository.toggleImportantMessage(peerId, msgId) },
                onEditMessage = { peerId, msgId, newText -> repository.editPrivateMessage(peerId, msgId, newText) },
                onDeleteMessageForMe = { peerId, msgId -> repository.deleteMessageForMe(peerId, msgId) },
                onDeleteMessageForEveryone = { peerId, msgId -> repository.deleteMessageForEveryone(peerId, msgId) }
            )

            familyRecoveryError?.let { error ->
                AlertDialog(
                    onDismissRequest = { familyRecoveryError = null },
                    title = { Text("Recuperação familiar segura") },
                    text = { Text(error + "\nVerifique se o titular autorizou este familiar enquanto a conta estava ativa.") },
                    confirmButton = {
                        TextButton(onClick = { familyRecoveryError = null }) {
                            Text("Entendi")
                        }
                    }
                )
            }
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
                initialRecoveryKey = incomingRecoveryKey,
                // Somente o servidor valida o código v2. Nunca aceitar
                // autorização local do simulador como prova de titularidade.
                onFindAuthorization = { _ -> null },
                onBeginRecovery = { key ->
                    repository.beginFamilyRecovery(key)
                },
                onClaimRecovery = { key, pin ->
                    repository.claimRecovery(key, pin)
                },
                onCheckRecoveryStatus = { key ->
                    repository.checkPreparedFamilyRecoveryStatus(key)
                },
                onRecoverVaultMaterial = { repository.recoverV3MaterialFromFamily() },
                onRestoreConversations = { pin, secret ->
                    if (secret.isNullOrBlank()) repository.queryAndRestoreFromFamilyVault(pin)
                    else repository.queryAndRestoreFromFamilyVaultWithSecret(pin, secret)
                },
                onRecoverySuccess = { recoveredHash ->
                    repository.transferIdentityToThisDevice(recoveredHash)
                },
                onBack = {
                    onClearIncomingRecovery()
                    currentScreen = Screen.Main
                }
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

    pendingFamilyShareRelease?.let { request ->
        AlertDialog(
            onDismissRequest = { repository.rejectFamilyShareRelease() },
            title = { Text("Recuperação familiar V3") },
            text = { Text("O titular ${request.targetIdentity.take(8)} solicitou sua parte. Confira com ele pessoalmente antes de autorizar.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result=repository.approvePendingFamilyShareRelease()
                        familyRecoveryError=result.exceptionOrNull()?.message
                            ?: "Parte liberada com autorização criptográfica."
                    }
                }) { Text("Autorizar parte") }
            },
            dismissButton = {
                TextButton(onClick={ repository.rejectFamilyShareRelease() }) {
                    Text("Recusar")
                }
            }
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
                        text = "Identidade informada: ${invite.senderIdentity}",
                        style = MaterialTheme.typography.labelSmall.copy(color = RadarCyan)
                    )
                    Text(
                        text = "Confirme com a pessoa por outro canal antes de aceitar. O nome exibido no link não comprova a identidade do emissor. Este convite é de uso único e requer conexão com o servidor.",
                        style = MaterialTheme.typography.bodySmall.copy(color = WarningAmber)
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

    // Diálogo de Atualização do App (Google Play)
    availableAppUpdate?.let { updateInfo ->
        AppUpdateAvailableDialog(
            updateInfo = updateInfo,
            onUpdateClick = { url ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=br.com.pessoasaqui")).apply {
                        setPackage("com.android.vending")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url.ifBlank { "https://play.google.com/store/apps/details?id=br.com.pessoasaqui" })))
                    } catch (_: Exception) {}
                }
            },
            onDismiss = { repository.dismissAppUpdate() }
        )
    }

    if (showVipDialog) {
        VipSubscriptionDialog(
            repository = repository,
            onDismissRequest = { showVipDialog = false }
        )
    }
}
