package br.com.pessoasaqui.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.data.remote.SupportTicketItem
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Diálogo no aplicativo para abertura de Petições e Chamados de Suporte com a equipe PessoasAqui,
 * além de visualização das respostas oficiais da moderação.
 * Preserva o modelo de identidade criptográfica (sem exigir e-mail ou telefone).
 */
@Composable
fun SupportTicketDialog(
    tickets: List<SupportTicketItem> = emptyList(),
    onRefreshTickets: () -> Unit = {},
    onDismiss: () -> Unit,
    onSubmitTicket: suspend (category: String, subject: String, message: String) -> Result<Boolean>
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(if (tickets.isNotEmpty()) 0 else 1) }

    var selectedCategory by remember { mutableStateOf("DUVIDA") }
    var subjectText by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    val categories = listOf(
        "DUVIDA" to "Dúvida Geral sobre o App",
        "RECUPERACAO" to "Ajuda com Recuperação / PIN",
        "BUG" to "Relato de Problema Técnico / Falha",
        "SUGESTAO" to "Sugestão de Melhoria",
        "OUTRO" to "Outro Assunto"
    )

    val answeredCount = remember(tickets) { tickets.count { it.status == "ANSWERED" } }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.Headset,
                contentDescription = null,
                tint = EmeraldGreen,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Petição & Suporte Oficial",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Abas de navegação: Minhas Petições vs Nova Petição
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DarkCard,
                    contentColor = RadarCyan,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (tickets.isNotEmpty()) "Petições (${tickets.size})" else "Petições",
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == 0) RadarCyan else TextSecondary,
                                    fontSize = 12.sp
                                )
                                if (answeredCount > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = EmeraldGreen
                                    ) {
                                        Text(
                                            text = "$answeredCount",
                                            color = DeepBlack,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = "+ Nova Petição",
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 1) RadarCyan else TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    )
                }

                if (selectedTab == 0) {
                    // Aba 0: Minhas Petições e Respostas dos Moderadores
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Histórico de Chamados",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        IconButton(
                            onClick = {
                                isRefreshing = true
                                onRefreshTickets()
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(1000)
                                    isRefreshing = false
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = RadarCyan, strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Recarregar chamados",
                                    tint = RadarCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (tickets.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Headset,
                                contentDescription = null,
                                tint = Color.Gray.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Nenhuma petição aberta ainda.",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { selectedTab = 1 },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarCyan)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Abrir Petição Agora", fontSize = 12.sp)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            tickets.forEach { ticket ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (ticket.status == "ANSWERED") EmeraldGreen.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        // Cabeçalho: Status + Categoria
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (ticket.status == "ANSWERED") EmeraldGreen.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f),
                                                border = BorderStroke(1.dp, if (ticket.status == "ANSWERED") EmeraldGreen.copy(alpha = 0.5f) else WarningAmber.copy(alpha = 0.5f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = if (ticket.status == "ANSWERED") Icons.Default.CheckCircle else Icons.Default.HourglassEmpty,
                                                        contentDescription = null,
                                                        tint = if (ticket.status == "ANSWERED") EmeraldGreen else WarningAmber,
                                                        modifier = Modifier.size(11.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = if (ticket.status == "ANSWERED") "RESPONDIDO" else "AGUARDANDO MODERAÇÃO",
                                                        color = if (ticket.status == "ANSWERED") EmeraldGreen else WarningAmber,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }

                                            Text(
                                                text = ticket.category,
                                                color = RadarCyan,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        // Assunto
                                        Text(
                                            text = ticket.subject,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )

                                        // Mensagem enviada pelo usuário
                                        Text(
                                            text = ticket.message,
                                            color = Color.LightGray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 2.dp),
                                            lineHeight = 15.sp
                                        )

                                        // Resposta da Moderação (se houver)
                                        if (!ticket.moderatorReply.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = EmeraldGreen.copy(alpha = 0.12f),
                                                border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.4f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(8.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.Headset,
                                                            contentDescription = null,
                                                            tint = EmeraldGreen,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "Resposta Oficial da Equipe PessoasAqui:",
                                                            color = EmeraldGreen,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = ticket.moderatorReply,
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        lineHeight = 16.sp
                                                    )
                                                    if (!ticket.repliedAt.isNullOrBlank()) {
                                                        Text(
                                                            text = "Data: ${ticket.repliedAt}",
                                                            color = Color.Gray,
                                                            fontSize = 9.sp,
                                                            modifier = Modifier.padding(top = 4.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Aba 1: Formulário de Nova Petição
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Envie sua solicitação diretamente para os moderadores do PessoasAqui. A resposta chegará neste aparelho e na aba Petições.",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Text(
                            text = "Categoria da Petição:",
                            color = EmeraldGreen,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )

                        categories.forEach { (catKey, catLabel) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedCategory = catKey }
                                    .padding(vertical = 1.dp)
                            ) {
                                RadioButton(
                                    selected = (selectedCategory == catKey),
                                    onClick = { selectedCategory = catKey },
                                    colors = RadioButtonDefaults.colors(selectedColor = EmeraldGreen)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = catLabel, color = Color.White, fontSize = 12.sp)
                            }
                        }

                        OutlinedTextField(
                            value = subjectText,
                            onValueChange = { subjectText = it },
                            label = { Text("Assunto do Chamado", color = Color.Gray, fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldGreen,
                                unfocusedBorderColor = DarkCardElevated,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            label = { Text("Descreva sua petição ou dúvida", color = Color.Gray, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldGreen,
                                unfocusedBorderColor = DarkCardElevated,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (selectedTab == 1) {
                Button(
                    onClick = {
                        if (subjectText.isBlank() || messageText.isBlank()) {
                            Toast.makeText(context, "Preencha o assunto e a mensagem.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSubmitting = true
                        coroutineScope.launch {
                            val result = onSubmitTicket(selectedCategory, subjectText.trim(), messageText.trim())
                            isSubmitting = false
                            if (result.isSuccess) {
                                Toast.makeText(context, "Petição enviada com sucesso! A moderação responderá em breve.", Toast.LENGTH_LONG).show()
                                subjectText = ""
                                messageText = ""
                                onRefreshTickets()
                                selectedTab = 0 // Muda para a aba de histórico
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "Falha ao enviar."
                                Toast.makeText(context, "Erro: $err", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = DeepBlack),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = DeepBlack, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Icon(imageVector = Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Enviar Petição", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { selectedTab = 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen, contentColor = DeepBlack),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Nova Petição", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("Fechar", color = Color.Gray)
            }
        },
        containerColor = DarkCardElevated
    )
}
