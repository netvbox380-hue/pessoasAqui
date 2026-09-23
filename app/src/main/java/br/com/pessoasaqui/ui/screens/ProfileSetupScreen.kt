package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.domain.model.UserIntent
import br.com.pessoasaqui.ui.theme.*

/**
 * Tela de Configuração de Perfil de Presença do Usuário.
 * Permite ao usuário escolher seu nome/apelido e sua intenção inicial antes de ativar o radar.
 */
@Composable
fun ProfileSetupScreen(
    initialAlias: String = "",
    onProfileCompleted: (alias: String, intent: UserIntent) -> Unit
) {
    var aliasInput by remember { mutableStateOf(if (initialAlias == "Eu") "" else initialAlias) }
    var selectedIntent by remember { mutableStateOf(UserIntent.QUERO_CONVERSAR) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Avatar Preview
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(RadarCyan.copy(alpha = 0.15f))
                        .border(2.dp, RadarCyan, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (aliasInput.isNotBlank()) {
                        Text(
                            text = aliasInput.trim().take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = RadarCyan
                            )
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = RadarCyan,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Título
                Text(
                    text = "Como quer ser chamado?",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    ),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Este é o nome que as pessoas a até 10 metros verão no radar.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        lineHeight = 18.sp
                    ),
                    textAlign = TextAlign.Center
                )

                // Campo de Nome / Apelido
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = {
                        aliasInput = it
                        if (it.isNotBlank()) errorMessage = null
                    },
                    label = { Text("Seu Nome ou Apelido") },
                    placeholder = { Text("Ex: Fábio, Ana, Lucas...") },
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RadarCyan,
                        unfocusedBorderColor = DarkCardElevated,
                        focusedLabelColor = RadarCyan,
                        cursorColor = RadarCyan
                    )
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = AlertRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Seletor de Intenção Inicial
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Sua intenção agora:",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    UserIntent.values().forEach { intent ->
                        val isSelected = intent == selectedIntent
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) RadarCyan.copy(alpha = 0.15f) else DarkCard,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, RadarCyan) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { selectedIntent = intent }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = intent.icon, fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = intent.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = if (isSelected) RadarCyan else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = RadarCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Card de Garantia de Identidade
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Sem senhas ou e-mails. Sua identidade técnica é gerada localmente neste aparelho.",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                        )
                    }
                }

                // Botão de Continuar
                Button(
                    onClick = {
                        val trimmed = aliasInput.trim()
                        if (trimmed.isBlank()) {
                            errorMessage = "Por favor, digite como prefere ser chamado."
                        } else {
                            onProfileCompleted(trimmed, selectedIntent)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RadarCyan,
                        contentColor = DeepBlack
                    )
                ) {
                    Text(
                        text = "Entrar no Radar",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
