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
import br.com.pessoasaqui.ui.theme.DeepBlack
import br.com.pessoasaqui.ui.theme.PessoasAquiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as? PessoasAquiApp
        val repository = app?.repository ?: PessoasAquiRepository()

        setContent {
            PessoasAquiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepBlack
                ) {
                    PessoasAquiNavHost(repository = repository)
                }
            }
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
fun PessoasAquiNavHost(repository: PessoasAquiRepository) {
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
            val livePerson = discoveredPeople.find {
                it.id == screen.person.id || it.technicalIdentityHash == screen.person.technicalIdentityHash
            } ?: screen.person

            val cleanTargetId = livePerson.id.removePrefix("peer-")
            val messages = privateChats[livePerson.id] 
                ?: privateChats[cleanTargetId] 
                ?: privateChats[livePerson.technicalIdentityHash] 
                ?: emptyList()

            ChatDetailScreen(
                person = livePerson,
                messages = messages,
                onBack = { currentScreen = Screen.Main },
                onSendMessage = { text ->
                    repository.sendPrivateMessage(livePerson.technicalIdentityHash, text)
                },
                onStartCall = { targetPerson, isVideo ->
                    repository.startCall(targetPerson, isVideo)
                },
                onSendAudioMessage = { targetPerson, duration ->
                    repository.sendAudioMessage(targetPerson.technicalIdentityHash, duration)
                },
                onSendImageMessage = { targetPerson, base64 ->
                    repository.sendImageMessage(targetPerson.technicalIdentityHash, base64)
                },
                onSendDocumentMessage = { targetPerson, fileName, base64, size ->
                    repository.sendDocumentMessage(targetPerson.technicalIdentityHash, fileName, base64, size)
                },
                onToggleMarkPerson = { targetPerson ->
                    repository.toggleMarkPerson(targetPerson.id)
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
            onToggleCamera = { repository.toggleCallCamera() }
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
}
