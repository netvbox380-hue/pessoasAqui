package br.com.pessoasaqui.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Diálogo no aplicativo para abertura de Petições e Chamados de Suporte com a equipe PessoasAqui.
 * Preserva o modelo de identidade criptográfica (sem exigir e-mail ou telefone).
 */
@Composable
fun SupportTicketDialog(
    onDismiss: () -> Unit,
    onSubmitTicket: suspend (category: String, subject: String, message: String) -> Result<Boolean>
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedCategory by remember { mutableStateOf("DUVIDA") }
    var subjectText by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    val categories = listOf(
        "DUVIDA" to "Dúvida Geral sobre o App",
        "RECUPERACAO" to "Ajuda com Recuperação / PIN",
        "BUG" to "Relato de Problema Técnico / Falha",
        "SUGESTAO" to "Sugestão de Melhoria",
        "OUTRO" to "Outro Assunto"
    )

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
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Envie sua solicitação diretamente para os moderadores do PessoasAqui. A resposta chegará neste aparelho.",
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
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = (selectedCategory == catKey),
                            onClick = { selectedCategory = catKey },
                            colors = RadioButtonDefaults.colors(selectedColor = EmeraldGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
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
        },
        confirmButton = {
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
                            Toast.makeText(context, "Petição enviada com sucesso! Aguarde resposta da equipe.", Toast.LENGTH_LONG).show()
                            onDismiss()
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
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text("Cancelar", color = Color.Gray)
            }
        },
        containerColor = DarkCardElevated
    )
}
