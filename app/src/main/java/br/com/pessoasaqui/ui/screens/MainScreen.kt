package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: PessoasAquiRepository,
    onOpenEntreNaSua: () -> Unit,
    onOpenChat: (NearbyPerson) -> Unit,
    onEnableBluetooth: () -> Unit = {}
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showIntentSelector by remember { mutableStateOf(false) }
    var showPlayground by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var tempAliasInput by remember { mutableStateOf("") }
    var activeFamilyRecoveryAuth by remember { mutableStateOf<Pair<NearbyPerson, RecoveryAuthorization>?>(null) }

    val people by repository.discoveredPeople.collectAsState()
    val offers by repository.localOffers.collectAsState()
    val localMessages by repository.localChatMessages.collectAsState()
    val currentIntent by repository.currentIntent.collectAsState()
    val currentAlias by repository.currentAlias.collectAsState()
    val isDemoMode by repository.isDemoMode.collectAsState()
    val isBluetoothEnabled by repository.isBluetoothEnabled.collectAsState()
    val isSessionRevoked by repository.deviceSessionManager.isSessionRevoked.collectAsState()
    val revocationNotice by repository.deviceSessionManager.revocationNotice.collectAsState()

    // Pessoas que são conexões mútuas ou familiares
    val connectedPeople = remember(people) {
        people.filter { it.isMutualConnection || it.isFamily }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PessoasAqui",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = RadarCyan
                            )
                        )
                        Text(
                            text = "converse com quem está perto.",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                        )
                    }
                },
                actions = {
                    // Chip com o perfil do usuário ativo neste aparelho
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = RadarCyan.copy(alpha = 0.12f),
                        modifier = Modifier
                            .clickable {
                                tempAliasInput = currentAlias
                                showEditProfileDialog = true
                            }
                            .padding(end = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = RadarCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentAlias,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = RadarCyan
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    // Menu dropdown com opções de restauração e demonstração
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Mais opções",
                                tint = TextSecondary
                            )
                        }

                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Editar Meu Apelido", color = TextPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = RadarCyan)
                                },
                                onClick = {
                                    showTopMenu = false
                                    tempAliasInput = currentAlias
                                    showEditProfileDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Restaurar em Outro Celular", color = TextPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = RadarCyan)
                                },
                                onClick = {
                                    showTopMenu = false
                                    onOpenEntreNaSua()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isDemoMode) "Desativar Modo Demonstração" else "Ativar Modo Demonstração",
                                        color = if (isDemoMode) WarningAmber else TextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Science,
                                        contentDescription = null,
                                        tint = if (isDemoMode) WarningAmber else TextSecondary
                                    )
                                },
                                onClick = {
                                    showTopMenu = false
                                    repository.toggleDemoMode()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.People,
                            contentDescription = "Pessoas"
                        )
                    },
                    label = { Text("Pessoas") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RadarCyan,
                        selectedTextColor = RadarCyan,
                        indicatorColor = RadarCyanGlow,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ChatBubble,
                            contentDescription = "Conversas"
                        )
                    },
                    label = { Text("Conversas") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RadarCyan,
                        selectedTextColor = RadarCyan,
                        indicatorColor = RadarCyanGlow,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = "Ofertas"
                        )
                    },
                    label = { Text("Ofertas") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RadarCyan,
                        selectedTextColor = RadarCyan,
                        indicatorColor = RadarCyanGlow,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
            }
        },
        containerColor = DeepBlack
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Se a identidade deste aparelho foi transferida para outro aparelho (Seção 18)
            if (isSessionRevoked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DeepBlack.copy(alpha = 0.95f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = AlertRed,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = revocationNotice ?: "Sessão Revogada",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                ),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Text(
                                text = "Regra fundamental: 1 Identidade = 1 Dispositivo Ativo.\nEste aparelho foi desconectado automaticamente.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            )
                        }
                    }
                }
            } else {
                when (selectedTabIndex) {
                    0 -> PeopleRadarTab(
                        people = people,
                        currentIntent = currentIntent,
                        isDemoMode = isDemoMode,
                        isBluetoothEnabled = isBluetoothEnabled,
                        onToggleDemoMode = { repository.toggleDemoMode() },
                        onEnableBluetooth = onEnableBluetooth,
                        onOpenIntentSelector = { showIntentSelector = true },
                        onToggleMark = { personId ->
                            repository.toggleMarkPerson(personId)
                        },
                        onOpenChat = onOpenChat
                    )

                    1 -> ConversationsTab(
                        localMessages = localMessages,
                        connectedPeople = connectedPeople,
                        onSendLocalMessage = { text ->
                            repository.sendLocalMessage(text)
                        },
                        onOpenChat = onOpenChat
                    )

                    2 -> OffersTab(
                        offers = offers,
                        onToggleMarkAuthor = { authorId ->
                            repository.toggleMarkPerson(authorId)
                        },
                        onPostOffer = { prof, desc ->
                            repository.postLocalOffer(prof, desc)
                        }
                    )
                }
            }

            // BottomSheet de Intenções do Usuário
            if (showIntentSelector) {
                IntentSelectorBottomSheet(
                    currentIntent = currentIntent,
                    onSelectIntent = { intent ->
                        repository.setIntent(intent)
                    },
                    onDismiss = { showIntentSelector = false }
                )
            }

            // Playground de Proximidade (Ajuste de distância ao vivo)
            if (showPlayground) {
                NearbyPlaygroundSheet(
                    simulator = repository.proximitySimulator,
                    onDismiss = { showPlayground = false }
                )
            }

            // Diálogo de Edição de Apelido
            if (showEditProfileDialog) {
                AlertDialog(
                    onDismissRequest = { showEditProfileDialog = false },
                    title = { Text("Meu Perfil de Presença", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Este é o nome visível para pessoas a até 10 metros.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                            OutlinedTextField(
                                value = tempAliasInput,
                                onValueChange = { tempAliasInput = it },
                                label = { Text("Seu Nome / Apelido") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkCard,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "ID Criptográfico: #${repository.cryptoIdentityManager.getTechnicalIdentity().take(8)}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (tempAliasInput.isNotBlank()) {
                                    repository.setAlias(tempAliasInput)
                                    showEditProfileDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RadarCyan, contentColor = DeepBlack)
                        ) {
                            Text("Salvar", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEditProfileDialog = false }) {
                            Text("Cancelar", color = TextSecondary)
                        }
                    },
                    containerColor = DarkSurface
                )
            }

            // Diálogo de Autorização de Recuperação Familiar
            activeFamilyRecoveryAuth?.let { (person, auth) ->
                FamilyRecoveryDialog(
                    person = person,
                    authorization = auth,
                    onDismiss = { activeFamilyRecoveryAuth = null }
                )
            }
        }
    }
}
