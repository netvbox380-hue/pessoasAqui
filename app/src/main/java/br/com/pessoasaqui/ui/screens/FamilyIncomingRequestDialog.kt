package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.pessoasaqui.domain.model.FamilyRole
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.ui.theme.*

@Composable
fun FamilyIncomingRequestDialog(
    request: Pair<NearbyPerson, FamilyRole>,
    onAccept: (NearbyPerson, FamilyRole) -> Unit,
    onReject: () -> Unit
) {
    val (person, role) = request

    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = FamilyPurple,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Solicitação de Vínculo Familiar",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "✨ ${person.alias} convidou você para o papel de \"${role.label}\" na Rede Familiar Protegida.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Benefícios liberados mutuamente:",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = RadarCyan
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Chamadas de voz e vídeo criptografadas (E2EE)\n• Mensagens de voz/áudio gravadas\n• Conversas em qualquer distância\n• Suporte a recuperação de identidade",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAccept(person, role) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = FamilyPurple,
                    contentColor = TextPrimary
                )
            ) {
                Text("Aceitar Vínculo", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Recusar", color = TextSecondary)
            }
        },
        containerColor = DarkSurface
    )
}
