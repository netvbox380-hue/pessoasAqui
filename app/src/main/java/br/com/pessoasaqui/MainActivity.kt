package br.com.pessoasaqui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
    object Permissions : Screen()
    object Main : Screen()
    data class Chat(val person: NearbyPerson) : Screen()
    object EntreNaSua : Screen()
}

@Composable
fun PessoasAquiNavHost(repository: PessoasAquiRepository) {
    val isAgeVerified by repository.isAgeVerified.collectAsState()
    val hasGrantedPermissions by repository.hasGrantedPermissions.collectAsState()
    val privateChats by repository.privateChats.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.AgeGate) }
    var activeFamilyRecoveryAuth by remember { mutableStateOf<Pair<NearbyPerson, RecoveryAuthorization>?>(null) }

    // Sincroniza estado de onboarding inicial
    LaunchedEffect(isAgeVerified, hasGrantedPermissions) {
        if (!isAgeVerified) {
            currentScreen = Screen.AgeGate
        } else if (!hasGrantedPermissions) {
            currentScreen = Screen.Permissions
        } else if (currentScreen is Screen.AgeGate || currentScreen is Screen.Permissions) {
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
                onOpenChat = { person -> currentScreen = Screen.Chat(person) }
            )
        }

        is Screen.Chat -> {
            BackHandler { currentScreen = Screen.Main }
            val messages = privateChats[screen.person.id] ?: emptyList()

            ChatDetailScreen(
                person = screen.person,
                messages = messages,
                onBack = { currentScreen = Screen.Main },
                onSendMessage = { text ->
                    repository.sendPrivateMessage(screen.person.id, text)
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
                onRecoverySuccess = { recoveredHash ->
                    repository.transferIdentityToThisDevice(recoveredHash)
                },
                onBack = { currentScreen = Screen.Main }
            )
        }
    }
}
