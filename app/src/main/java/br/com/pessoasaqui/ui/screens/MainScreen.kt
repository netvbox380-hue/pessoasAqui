package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.BorderStroke
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
import br.com.pessoasaqui.core.proximity.BleConstants
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
    onOpenEntreNaSua: () -> Unit,
    onOpenChat: (NearbyPerson) -> Unit,
    onEnableBluetooth: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showIntentSelector by remember { mutableStateOf(false) }
    var showPlayground by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var tempAliasInput by remember { mutableStateOf("") }
    var activeFamilyRecoveryAuth by remember { mutableStateOf<Pair<NearbyPerson, RecoveryAuthorization>?>(null) }
    var isCreatingInvite by remember { mutableStateOf(false) }
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
    // Se o Bluetooth estiver desligado (e fora de modo demo), nenhuma pessoa é descoberta no radar
    val radarPeople = remember(people, isBluetoothEnabled, isDemoMode) {
        if (!isBluetoothEnabled && !isDemoMode) {
            emptyList()
        } else {
            people.filter { it.estimatedDistanceMeters <= BleConstants.MAX_DISCOVERY_DISTANCE_METERS }
        }
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
                    // Badge indicadora de Assinante VIP
                    if (subscriptionTier == SubscriptionTier.PRO_VIP) {
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
                            if (selectedTabIndex == 1) {
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

                            // Extrai a URL caso o usuário tenha colado a mensagem completa do WhatsApp ou outros apps
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
                        contactTags = contactTags,
                        contactTagAssignments = contactTagAssignments
                    )

                    2 -> OffersTab(
                        offers = offers,
                        myId = repository.getMyIdentity(),
                        myAlias = currentAlias,
                        onToggleMarkAuthor = { authorId ->
                            repository.toggleMarkPerson(authorId)
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
