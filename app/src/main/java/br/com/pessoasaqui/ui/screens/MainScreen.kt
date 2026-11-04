package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import br.com.pessoasaqui.core.proximity.BleConstants
import br.com.pessoasaqui.core.qrcode.QrCodeGenerator
import br.com.pessoasaqui.core.qrcode.InviteLinkParser
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import br.com.pessoasaqui.core.subscription.SubscriptionTier
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: PessoasAquiRepository,
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    onOpenEntreNaSua: () -> Unit,
    onOpenChat: (NearbyPerson) -> Unit,
    onEnableBluetooth: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedTabIndex by remember(selectedTab) { mutableIntStateOf(selectedTab) }
    var showIntentSelector by remember { mutableStateOf(false) }
    var showPlayground by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var tempAliasInput by remember { mutableStateOf("") }
    var activeFamilyRecoveryAuth by remember { mutableStateOf<Pair<NearbyPerson, RecoveryAuthorization>?>(null) }
    var isCreatingInvite by remember { mutableStateOf(false) }
    var generatedInviteUrl by remember { mutableStateOf<String?>(null) }
    var inviteGenerationError by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    var showSupportDialog by remember { mutableStateOf(false) }

    val people by repository.discoveredPeople.collectAsState()
    val offers by repository.localOffers.collectAsState()
    val localMessages by repository.localChatMessages.collectAsState()
    val privateChats by repository.privateChats.collectAsState()
    val currentIntent by repository.currentIntent.collectAsState()
    val currentAlias by repository.currentAlias.collectAsState()
    val isDemoMode by repository.isDemoMode.collectAsState()
    val isBluetoothEnabled by repository.isBluetoothEnabled.collectAsState()
    val isSessionRevoked by repository.deviceSessionManager.isSessionRevoked.collectAsState()
    val revocationNotice by repository.deviceSessionManager.revocationNotice.collectAsState()
    val recurringReminders by repository.recurringReminders.collectAsState()
    val subscriptionTier by (repository.subscriptionManager?.currentTier ?: MutableStateFlow(SubscriptionTier.FREE)).collectAsState()
    val mySupportTickets by repository.mySupportTickets.collectAsState()
    val moderationWarning by repository.moderationWarningAlert.collectAsState()
    val accountBanned by repository.accountBannedAlert.collectAsState()
    val contactTags by repository.contactTags.collectAsState()
    val contactTagAssignments by repository.contactTagAssignments.collectAsState()
    val scope = rememberCoroutineScope()
    var showVipDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showMainBroadcastDialog by remember { mutableStateOf(false) }
    var showRemindersDialog by remember { mutableStateOf(false) }

    // Pessoas que são conexões mútuas ou familiares (garantindo unicidade de IDs)
    val connectedPeople = remember(people) {
        people.filter { it.isMutualConnection || it.isFamily }
            .distinctBy { it.technicalIdentityHash.ifBlank { it.id } }
    }

    // Pessoas estritamente dentro do Radar de Proximidade BLE (10m)
    // Se o Bluetooth estiver desligado, nenhuma pessoa é descoberta no radar
    val radarPeople = remember(people, isBluetoothEnabled) {
        if (!isBluetoothEnabled) {
            emptyList()
        } else {
            people.filter { it.estimatedDistanceMeters <= BleConstants.MAX_DISCOVERY_DISTANCE_METERS && !it.id.startsWith("sim-", ignoreCase = true) }
        }
    }

    // A adaptação considera os limites atuais da janela (multiwindow/dobráveis).
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val windowMaxWidth = maxWidth
    val useNavigationRail = windowMaxWidth >= 600.dp

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
                    // Badge indicadora de Assinante VIP
                    if ((useNavigationRail || windowMaxWidth >= 470.dp) &&
                        subscriptionTier == SubscriptionTier.PRO_VIP) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = WarningAmber.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .clickable {
                                    showVipDialog = true
                                }
                                .padding(end = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WorkspacePremium,
                                    contentDescription = null,
                                    tint = EmeraldGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "VIP",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldGreen
                                    )
                                )
                            }
                        }
                    }

                    // Em janelas estreitas a ação permanece no menu de opções;
                    // não comprimir o título nem cortar as ações da barra.
                    if (useNavigationRail || windowMaxWidth >= 390.dp) Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = RadarCyan.copy(alpha = 0.12f),
                        modifier = Modifier
                            .widthIn(max = if (useNavigationRail) 240.dp else 120.dp)
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
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                            if (selectedTabIndex == 1) {
                                // Recursos continuam acessíveis quando os chips
                                // não cabem na barra superior de um aparelho pequeno.
                                DropdownMenuItem(
                                    text = { Text("Editar Meu Apelido", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = RadarCyan) },
                                    onClick = {
                                        showTopMenu = false
                                        tempAliasInput = currentAlias
                                        showEditProfileDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Plano VIP", color = WarningAmber) },
                                    leadingIcon = { Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = WarningAmber) },
                                    onClick = {
                                        showTopMenu = false
                                        showVipDialog = true
                                    }
                                )
                                // Menu contextual da tela de Conversas: Lembretes e Transmissão
                                DropdownMenuItem(
                                    text = { Text("Agendar Lembrete", color = TextPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Schedule, contentDescription = null, tint = RadarCyan)
                                    },
                                    onClick = {
                                        showTopMenu = false
                                        showRemindersDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mensagem de Transmissão", color = RadarCyan) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Campaign, contentDescription = null, tint = RadarCyan)
                                    },
                                    onClick = {
                                        showTopMenu = false
                                        showMainBroadcastDialog = true
                                    }
                                )
                            } else {
                                // Menu contextual da tela de Pessoas / Radar: Perfil, Backup e Suporte
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
                                            if (subscriptionTier == SubscriptionTier.PRO_VIP) "Gerenciar Plano VIP ⭐" else "Assinar Plano VIP (Google Play)",
                                            color = if (subscriptionTier == SubscriptionTier.PRO_VIP) EmeraldGreen else WarningAmber
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.WorkspacePremium,
                                            contentDescription = null,
                                            tint = if (subscriptionTier == SubscriptionTier.PRO_VIP) EmeraldGreen else WarningAmber
                                        )
                                    },
                                    onClick = {
                                        showTopMenu = false
                                        showVipDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("Cofre & Backup Familiar 🔒", color = RadarCyan)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Shield,
                                            contentDescription = null,
                                            tint = RadarCyan
                                        )
                                    },
                                    onClick = {
                                        showTopMenu = false
                                        showBackupDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Petição & Suporte Oficial", color = TextPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Headset, contentDescription = null, tint = EmeraldGreen)
                                    },
                                    onClick = {
                                        showTopMenu = false
                                        showSupportDialog = true
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        bottomBar = {
            if (!useNavigationRail) NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = {
                        selectedTabIndex = 0
                        onTabSelected(0)
                    },
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
                    onClick = {
                        selectedTabIndex = 1
                        onTabSelected(1)
                    },
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
                    onClick = {
                        selectedTabIndex = 2
                        onTabSelected(2)
                    },
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
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (useNavigationRail) {
                NavigationRail(containerColor = DarkSurface) {
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        Triple(0, Icons.Default.People, "Pessoas"),
                        Triple(1, Icons.Default.ChatBubble, "Conversas"),
                        Triple(2, Icons.Default.Campaign, "Ofertas")
                    ).forEach { (tab, icon, title) ->
                        NavigationRailItem(
                            selected = selectedTabIndex == tab,
                            onClick = {
                                selectedTabIndex = tab
                                onTabSelected(tab)
                            },
                            icon = { Icon(icon, contentDescription = title) },
                            label = { Text(title) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = RadarCyan,
                                selectedTextColor = RadarCyan,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.TopCenter
            ) {
            // Limita a largura das áreas de conteúdo em tablets/desktop sem
            // impor zoom ou densidade artificial a telas pequenas.
            Box(
                modifier = Modifier
                    .widthIn(max = if (selectedTabIndex == 1) 980.dp else 1120.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
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
                        people = radarPeople,
                        currentIntent = currentIntent,
                        isDemoMode = isDemoMode,
                        isBluetoothEnabled = isBluetoothEnabled,
                        localMessages = localMessages,
                        onSendLocalMessage = { text ->
                            repository.sendLocalMessage(text)
                        },
                        onClearLocalMessages = {
                            repository.clearLocalMessages()
                        },
                        onToggleDemoMode = { repository.toggleDemoMode() },
                        onEnableBluetooth = onEnableBluetooth,
                        onOpenIntentSelector = { showIntentSelector = true },
                        onToggleMark = { personId ->
                            repository.toggleMarkPerson(personId)
                        },
                        onAcceptMutual = { personId ->
                            repository.acceptMutualConnection(personId)
                        },
                        onOpenChat = onOpenChat
                    )

                    1 -> ConversationsTab(
                        connectedPeople = connectedPeople,
                        privateChats = privateChats,
                        nearbyPeople = people,
                        onOpenChat = onOpenChat,
                        onToggleMarkPerson = { personId ->
                            repository.toggleMarkPerson(personId)
                        },
                        onDeleteConversations = { list ->
                            repository.deleteMultipleConversations(list)
                        },
                        isCreatingInvite = isCreatingInvite,
                        onCreateInviteLink = {
                            if (!isCreatingInvite) {
                                isCreatingInvite = true
                                repository.createOneTimeInviteLink { url ->
                                    isCreatingInvite = false
                                    if (url != null && InviteLinkParser.parse(url) != null) {
                                        generatedInviteUrl = url
                                        inviteGenerationError = null
                                    } else {
                                        inviteGenerationError = "Não foi possível criar um convite válido. Verifique a conexão com o servidor."
                                    }
                                }
                            }
                        },
                        onRedeemInviteLink = { rawInput, onResult ->
                            val invite = InviteLinkParser.parse(rawInput)
                            if (invite == null) {
                                onResult(false, "Convite inválido. Aceitamos somente links oficiais PessoasAqui.", null)
                            } else {
                                repository.redeemOneTimeInvite(
                                    inviteId = invite.id,
                                    token = invite.token,
                                    senderIdentity = invite.sender,
                                    senderAlias = invite.alias,
                                    signature = invite.signature,
                                    expiresAt = invite.expiresAt
                                ) { success, msg, peer ->
                                    onResult(success, msg, peer)
                                }
                            }
                        },
                        contactTags = contactTags,
                        contactTagAssignments = contactTagAssignments
                    )

                    2 -> OffersTab(
                        offers = offers,
                        myId = repository.getMyIdentity(),
                        myAlias = currentAlias,
                        onToggleMarkAuthor = { authorId ->
                            // Marcar é ato explícito, não pode desmarcar por engano.
                            // A identidade deve estar realmente listada no Radar BLE.
                            val peer = radarPeople.firstOrNull {
                                it.technicalIdentityHash.ifBlank { it.id }
                                    .removePrefix("peer-").equals(
                                        authorId.removePrefix("peer-"), ignoreCase = true
                                    ) && repository.hasFreshNativeBlePresence(
                                        it.technicalIdentityHash.ifBlank { it.id }
                                    )
                            }
                            if (peer != null) {
                                repository.setMarkPerson(peer.technicalIdentityHash.ifBlank { peer.id }, mark = true)
                            }
                        },
                        isAuthorNearby = { authorId ->
                            radarPeople.any {
                                it.technicalIdentityHash.ifBlank { it.id }
                                    .removePrefix("peer-").equals(
                                        authorId.removePrefix("peer-"), ignoreCase = true
                                    ) && repository.hasFreshNativeBlePresence(
                                        it.technicalIdentityHash.ifBlank { it.id }
                                    )
                            }
                        },
                        onOpenAuthorChat = { authorId ->
                            radarPeople.firstOrNull {
                                it.technicalIdentityHash.ifBlank { it.id }
                                    .removePrefix("peer-").equals(
                                        authorId.removePrefix("peer-"), ignoreCase = true
                                    ) && repository.hasFreshNativeBlePresence(
                                        it.technicalIdentityHash.ifBlank { it.id }
                                    )
                            }?.let { onOpenChat(it) }
                        },
                        onPostOffer = { prof, desc, img, link ->
                            repository.postLocalOffer(prof, desc, img, link)
                        },
                        onUpdateOffer = { offerId, prof, desc, img, link ->
                            repository.updateLocalOffer(offerId, prof, desc, img, link)
                        },
                        onDeleteOffer = { offerId ->
                            repository.deleteLocalOffer(offerId)
                        }
                    )
                }
            }

            // O QR contém exatamente o mesmo link de uso único.
            // O segredo não é salvo nem enviado a qualquer serviço adicional.
            generatedInviteUrl?.let { url ->
                val qr = remember(url) { QrCodeGenerator.generateQrCodeBitmap(url, 512, 512) }
                AlertDialog(
                    onDismissRequest = { generatedInviteUrl = null },
                    title = { Text("Convite: link ou QR", color = TextPrimary) },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 570.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (qr != null) {
                                Image(
                                    bitmap = qr,
                                    contentDescription = "Código QR do convite único",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.widthIn(max = 250.dp)
                                        .fillMaxWidth().aspectRatio(1f)
                                )
                            } else {
                                Text("Não foi possível desenhar o QR. Compartilhe o link.", color = WarningAmber)
                            }
                            Text("Mostre este QR a quem está perto. Para quem está longe, compartilhe o link. A conexão permanente requer acesso à internet para validar o convite.",
                                color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                            Text("Uso único: a primeira pessoa a resgatar estabelece o vínculo. Qualquer pessoa com este código ou link pode tentar usá-lo; compartilhe somente com alguém de confiança.",
                                color = WarningAmber, style = MaterialTheme.typography.bodySmall)
                            val expires = InviteLinkParser.parse(url)?.expiresAt
                            if (expires != null) {
                                Text("Expira: " + java.text.SimpleDateFormat("dd/MM/yyyy HH:mm",
                                    java.util.Locale.getDefault()).format(java.util.Date(expires)),
                                    color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("O link e o QR representam o mesmo convite; usar um invalida o outro. Fechar esta janela NÃO revoga o convite; ele continua válido até o resgate ou expiração.",
                                color = RadarCyan, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT,
                                    "Convite pessoal PessoasAqui (uso único):\\n$url")
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent,
                                "Compartilhar link de convite"))
                        }) {
                            Text("Compartilhar link", color = RadarCyan)
                        }
                    },
                    dismissButton = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                val clip = android.content.ClipData.newPlainText("Convite PessoasAqui", url)
                                if (android.os.Build.VERSION.SDK_INT >= 33) {
                                    clip.description.extras = android.os.PersistableBundle().apply {
                                        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                                    }
                                }
                                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager).setPrimaryClip(clip)
                            }) { Text("Copiar", color = WarningAmber) }
                            TextButton(onClick = { generatedInviteUrl = null }) { Text("Fechar") }
                        }
                    },
                    containerColor = DarkSurface
                )
            }

            if (inviteGenerationError != null) {
                AlertDialog(
                    onDismissRequest = { inviteGenerationError = null },
                    title = { Text("Convite indisponível") },
                    text = { Text(inviteGenerationError ?: "") },
                    confirmButton = {
                        TextButton(onClick = { inviteGenerationError = null }) { Text("Entendi") }
                    }
                )
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

            // Diálogo Oficial de Assinatura e Gerenciamento do Plano VIP
            if (showVipDialog) {
                VipSubscriptionDialog(
                    repository = repository,
                    onDismissRequest = { showVipDialog = false }
                )
            }

            // Diálogo de Gestão do Cofre P2P Distribuído entre Familiares
            if (showBackupDialog) {
                DistributedBackupDialog(
                    repository = repository,
                    onDismissRequest = { showBackupDialog = false }
                )
            }

            // Diálogo de Petição e Suporte Oficial
            if (showSupportDialog) {
                SupportTicketDialog(
                    tickets = mySupportTickets,
                    onRefreshTickets = { scope.launch { repository.refreshMySupportTickets() } },
                    onDismiss = { showSupportDialog = false },
                    onSubmitTicket = { category, subject, message ->
                        repository.openSupportTicket(category, subject, message)
                    }
                )
            }

            // Alerta Formal de Moderação (Strike / Advertência emitido pelo painel admin)
            moderationWarning?.let { warningText ->
                AlertDialog(
                    onDismissRequest = { repository.dismissModerationWarning() },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "Aviso da Moderação",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    text = {
                        Text(
                            text = warningText,
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { repository.dismissModerationWarning() },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = DeepBlack),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Entendido", fontWeight = FontWeight.Bold)
                        }
                    },
                    containerColor = DarkCardElevated
                )
            }

            // Alerta de Banimento / Bloqueio de Conta emitido pelo painel admin
            accountBanned?.let { banText ->
                AlertDialog(
                    onDismissRequest = { /* Bloqueante */ },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = null,
                            tint = AlertRed,
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "Conta Suspensa",
                            fontWeight = FontWeight.Bold,
                            color = AlertRed
                        )
                    },
                    text = {
                        Text(
                            text = banText,
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { showSupportDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AlertRed, contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Contestar Decisão / Suporte", fontWeight = FontWeight.Bold)
                        }
                    },
                    containerColor = DarkCardElevated
                )
            }

            // Diálogo de Mensagem de Transmissão (Acesso direto global)
            if (showMainBroadcastDialog) {
                BroadcastMessageDialog(
                    connectedPeople = connectedPeople,
                    isVip = subscriptionTier == SubscriptionTier.PRO_VIP,
                    maxAllowedRecipients = subscriptionTier.maxBroadcastRecipients,
                    remainingCooldownMs = repository.subscriptionManager?.getRemainingCooldownMs() ?: 0L,
                    contactTags = contactTags,
                    contactTagAssignments = contactTagAssignments,
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
                    onUpgradeToVip = {
                        showVipDialog = true
                    },
                    onDismiss = { showMainBroadcastDialog = false }
                )
            }

            // Diálogo de Agendamento e Lembretes (Recorrentes e Únicos)
            if (showRemindersDialog) {
                RecurringRemindersDialog(
                    reminders = recurringReminders,
                    connectedPeople = connectedPeople,
                    isVip = subscriptionTier == SubscriptionTier.PRO_VIP,
                    onSaveReminder = { reminder ->
                        repository.saveRecurringReminder(reminder)
                    },
                    onDeleteReminder = { reminderId ->
                        repository.deleteRecurringReminder(reminderId)
                    },
                    onToggleReminder = { reminderId, isEnabled ->
                        repository.toggleRecurringReminder(reminderId, isEnabled)
                    },
                    onConfirmReminder = { reminderId ->
                        repository.confirmRecurringReminder(reminderId)
                    },
                    onUpgradeToVip = {
                        showRemindersDialog = false
                        showVipDialog = true
                    },
                    onDismiss = { showRemindersDialog = false }
                )
            }
            }
            }
        }
    }
    }
}
