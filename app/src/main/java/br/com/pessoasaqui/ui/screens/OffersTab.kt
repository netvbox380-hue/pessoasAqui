package br.com.pessoasaqui.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.domain.model.OfferItem
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun OffersTab(
    offers: List<OfferItem>,
    myId: String = "",
    myAlias: String = "",
    onToggleMarkAuthor: (String) -> Unit,
    onPostOffer: (profession: String, description: String, imageBase64: String?, externalLink: String?) -> Unit = { _, _, _, _ -> },
    onUpdateOffer: (offerId: String, profession: String, description: String, imageBase64: String?, externalLink: String?) -> Unit = { _, _, _, _, _ -> },
    onDeleteOffer: (offerId: String) -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()

    var showPostDialog by remember { mutableStateOf(false) }
    var editingOffer by remember { mutableStateOf<OfferItem?>(null) }
    var deletingOffer by remember { mutableStateOf<OfferItem?>(null) }

    // Estado do formulário de criação
    var professionInput by remember { mutableStateOf("") }
    var descriptionInput by remember { mutableStateOf("") }
    var linkInput by remember { mutableStateOf("") }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }

    // Launcher para escolher imagem no Criar
    val postImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                val b64 = uriToBase64Image(context, it)
                if (b64 != null) {
                    selectedImageBase64 = b64
                }
            }
        }
    }

    // Launcher para escolher imagem no Editar
    var editImageBase64 by remember { mutableStateOf<String?>(null) }
    val editImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                val b64 = uriToBase64Image(context, it)
                if (b64 != null) {
                    editImageBase64 = b64
                }
            }
        }
    }

    fun isMine(offer: OfferItem): Boolean {
        val cleanMyId = myId.removePrefix("peer-").trim().uppercase()
        val cleanAuthorId = offer.authorId.removePrefix("peer-").trim().uppercase()
        return (cleanMyId.isNotBlank() && cleanMyId == cleanAuthorId) ||
                offer.proximityLabel.contains("Seu anúncio") ||
                (myAlias.isNotBlank() && offer.authorAlias.trim().equals(myAlias.trim(), ignoreCase = true))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Cabeçalho informativo
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Campaign,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Serviços e Divulgações Locais",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "Aparece a até 10m de você. Marque o profissional para manter contato sem limites de distância!",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                        )
                    }
                    IconButton(
                        onClick = {
                            professionInput = ""
                            descriptionInput = ""
                            linkInput = ""
                            selectedImageBase64 = null
                            showPostDialog = true
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = WarningAmber.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Publicar serviço",
                            tint = WarningAmber
                        )
                    }
                }
            }

            if (offers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = null,
                            tint = WarningAmber.copy(alpha = 0.6f),
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "Nenhuma divulgação ou serviço local no momento.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                professionInput = ""
                                descriptionInput = ""
                                linkInput = ""
                                selectedImageBase64 = null
                                showPostDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RadarCyan, contentColor = DeepBlack),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Divulgar meu trabalho agora", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(offers, key = { it.id }) { offer ->
                        val ownOffer = isMine(offer)

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            border = if (ownOffer) BorderStroke(1.dp, WarningAmber.copy(alpha = 0.5f)) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Topo do Card: Autor, Profissão e Distância/Badge
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = offer.authorAlias,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = offer.profession,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = WarningAmber,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    if (ownOffer) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = WarningAmber.copy(alpha = 0.18f),
                                            border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f))
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = null,
                                                    tint = WarningAmber,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Seu anúncio",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = WarningAmber,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = DarkCardElevated
                                        ) {
                                            Text(
                                                text = "10m",
                                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                // Imagem da Oferta (se houver)
                                if (!offer.imageBase64.isNullOrBlank()) {
                                    val bitmap = remember(offer.imageBase64) {
                                        try {
                                            val decoded = Base64.decode(offer.imageBase64, Base64.DEFAULT)
                                            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)?.asImageBitmap()
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                    if (bitmap != null) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = "Foto do serviço",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 140.dp, max = 220.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Descrição do Serviço
                                Text(
                                    text = offer.description,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = TextPrimary,
                                        lineHeight = 20.sp
                                    )
                                )

                                // Link Externo (se houver)
                                if (!offer.externalLink.isNullOrBlank()) {
                                    val rawLink = offer.externalLink.trim()
                                    val isWa = rawLink.contains("wa.me") || rawLink.contains("whatsapp")
                                    val isInsta = rawLink.contains("instagram.com")
                                    val label = when {
                                        isWa -> "Conversar via WhatsApp"
                                        isInsta -> "Ver Perfil no Instagram"
                                        else -> "Acessar Link Externo / Portfólio"
                                    }
                                    val linkColor = if (isWa) EmeraldGreen else if (isInsta) WarningAmber else RadarCyan

                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedButton(
                                        onClick = {
                                            val cleanUrl = if (!rawLink.startsWith("http://") && !rawLink.startsWith("https://")) {
                                                "https://$rawLink"
                                            } else {
                                                rawLink
                                            }
                                            try {
                                                uriHandler.openUri(cleanUrl)
                                            } catch (_: Exception) {}
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = linkColor
                                        ),
                                        border = BorderStroke(1.dp, linkColor.copy(alpha = 0.5f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInNew,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Ações no Card:
                                // Se for o anúncio do próprio usuário: botões de Editar e Excluir
                                // Se for anúncio de outro profissional: botão de Marcar Profissional
                                if (ownOffer) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                editingOffer = offer
                                                editImageBase64 = offer.imageBase64
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = RadarCyan
                                            ),
                                            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.6f))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Editar", fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                deletingOffer = offer
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = AlertRed
                                            ),
                                            border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.6f))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Excluir", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = { onToggleMarkAuthor(offer.authorId) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = RadarCyanGlow,
                                            contentColor = RadarCyan
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Marcar Profissional para Conversar",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ================= DIÁLOGO: PUBLICAR NOVO SERVIÇO =================
        if (showPostDialog) {
            AlertDialog(
                onDismissRequest = { showPostDialog = false },
                title = {
                    Text("Divulgar Meu Trabalho", fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Seu anúncio ficará visível para qualquer pessoa em até 10 metros.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )

                        OutlinedTextField(
                            value = professionInput,
                            onValueChange = { professionInput = it },
                            label = { Text("Profissão / Especialidade") },
                            placeholder = { Text("Ex: Mecânico, Eletricista, IPTV, Bolos...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = descriptionInput,
                            onValueChange = { descriptionInput = it },
                            label = { Text("Descrição do que você faz") },
                            placeholder = { Text("Ex: Faço orçamentos rápidos, garantia e qualidade...") },
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = linkInput,
                            onValueChange = { linkInput = it },
                            label = { Text("Link Externo (WhatsApp, Instagram, Site)") },
                            placeholder = { Text("Ex: https://wa.me/55... ou instagram.com/...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Seletor e Pré-visualização de Foto
                        if (selectedImageBase64 != null) {
                            val bmp = remember(selectedImageBase64) {
                                try {
                                    val dec = Base64.decode(selectedImageBase64, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(dec, 0, dec.size)?.asImageBitmap()
                                } catch (_: Exception) { null }
                            }
                            if (bmp != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                ) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "Pré-visualização",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    IconButton(
                                        onClick = { selectedImageBase64 = null },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(28.dp)
                                            .background(DeepBlack.copy(alpha = 0.7f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remover foto",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { postImagePicker.launch("image/*") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarCyan)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Adicionar Foto do Trabalho")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (professionInput.isNotBlank() && descriptionInput.isNotBlank()) {
                                onPostOffer(
                                    professionInput.trim(),
                                    descriptionInput.trim(),
                                    selectedImageBase64,
                                    linkInput.trim().takeIf { it.isNotBlank() }
                                )
                                professionInput = ""
                                descriptionInput = ""
                                linkInput = ""
                                selectedImageBase64 = null
                                showPostDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RadarCyan, contentColor = DeepBlack),
                        enabled = professionInput.isNotBlank() && descriptionInput.isNotBlank()
                    ) {
                        Text("Publicar", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPostDialog = false }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }

        // ================= DIÁLOGO: EDITAR SERVIÇO =================
        editingOffer?.let { offerToEdit ->
            var editProfession by remember(offerToEdit.id) { mutableStateOf(offerToEdit.profession) }
            var editDescription by remember(offerToEdit.id) { mutableStateOf(offerToEdit.description) }
            var editLink by remember(offerToEdit.id) { mutableStateOf(offerToEdit.externalLink ?: "") }

            AlertDialog(
                onDismissRequest = { editingOffer = null },
                title = {
                    Text("Editar Divulgação", fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = editProfession,
                            onValueChange = { editProfession = it },
                            label = { Text("Profissão / Especialidade") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = editDescription,
                            onValueChange = { editDescription = it },
                            label = { Text("Descrição do trabalho") },
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = editLink,
                            onValueChange = { editLink = it },
                            label = { Text("Link Externo (WhatsApp, Instagram, Site)") },
                            placeholder = { Text("Ex: https://wa.me/55... ou instagram.com/...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Foto atual ou nova foto
                        if (editImageBase64 != null) {
                            val bmp = remember(editImageBase64) {
                                try {
                                    val dec = Base64.decode(editImageBase64, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(dec, 0, dec.size)?.asImageBitmap()
                                } catch (_: Exception) { null }
                            }
                            if (bmp != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                ) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "Foto da divulgação",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    IconButton(
                                        onClick = { editImageBase64 = null },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(28.dp)
                                            .background(DeepBlack.copy(alpha = 0.7f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remover foto",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { editImagePicker.launch("image/*") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarCyan)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Adicionar Foto do Trabalho")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (editProfession.isNotBlank() && editDescription.isNotBlank()) {
                                onUpdateOffer(
                                    offerToEdit.id,
                                    editProfession.trim(),
                                    editDescription.trim(),
                                    editImageBase64,
                                    editLink.trim().takeIf { it.isNotBlank() }
                                )
                                editingOffer = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RadarCyan, contentColor = DeepBlack),
                        enabled = editProfession.isNotBlank() && editDescription.isNotBlank()
                    ) {
                        Text("Salvar Alterações", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingOffer = null }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }

        // ================= DIÁLOGO: EXCLUIR SERVIÇO =================
        deletingOffer?.let { offerToDelete ->
            AlertDialog(
                onDismissRequest = { deletingOffer = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = AlertRed,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text("Excluir Divulgação?", fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        text = "Tem certeza de que deseja remover \"${offerToDelete.profession}\"? Esta divulgação não aparecerá mais para pessoas a até 10 metros.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeleteOffer(offerToDelete.id)
                            deletingOffer = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed, contentColor = TextPrimary)
                    ) {
                        Text("Excluir Definitivamente", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletingOffer = null }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }
    }
}
