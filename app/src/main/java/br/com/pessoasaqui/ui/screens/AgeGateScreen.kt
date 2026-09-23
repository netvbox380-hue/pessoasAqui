package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.ui.theme.*
import java.util.Calendar

/**
 * Barreira de Proteção Etária (+18) do PessoasAqui.
 * Exige confirmação de maioridade e validação de data de nascimento.
 */
@Composable
fun AgeGateScreen(
    onAgeVerified: () -> Unit
) {
    var rawDateInput by remember { mutableStateOf("") }
    var isDeclarationChecked by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isAdult by remember { mutableStateOf(false) }

    fun applyDateMask(input: String): String {
        val digitsOnly = input.filter { it.isDigit() }.take(8)
        val sb = StringBuilder()
        for (i in digitsOnly.indices) {
            sb.append(digitsOnly[i])
            if ((i == 1 || i == 3) && i != digitsOnly.lastIndex) {
                sb.append("/")
            }
        }
        return sb.toString()
    }

    fun validateAge(formattedDate: String) {
        val digits = formattedDate.filter { it.isDigit() }
        if (digits.length != 8) {
            isAdult = false
            errorMessage = null
            return
        }

        try {
            val day = digits.substring(0, 2).toInt()
            val month = digits.substring(2, 4).toInt()
            val year = digits.substring(4, 8).toInt()

            if (day !in 1..31 || month !in 1..12 || year !in 1900..2026) {
                errorMessage = "Data de nascimento inválida."
                isAdult = false
                return
            }

            val today = Calendar.getInstance()
            val currentYear = today.get(Calendar.YEAR)
            val currentMonth = today.get(Calendar.MONTH) + 1
            val currentDay = today.get(Calendar.DAY_OF_MONTH)

            var age = currentYear - year
            if (currentMonth < month || (currentMonth == month && currentDay < day)) {
                age--
            }

            if (age >= 18) {
                isAdult = true
                errorMessage = null
            } else {
                isAdult = false
                errorMessage = "🔞 Acesso não permitido. O PessoasAqui é exclusivo para maiores de 18 anos."
            }
        } catch (e: Exception) {
            errorMessage = "Formato de data inválido."
            isAdult = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(20.dp),
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
                // Emblema de Proteção
                Surface(
                    shape = RoundedCornerShape(50),
                    color = AlertRed.copy(alpha = 0.15f),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "🔞",
                            fontSize = 34.sp
                        )
                    }
                }

                // Título
                Text(
                    text = "O PessoasAqui é exclusivo para maiores de 18 anos.",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    ),
                    textAlign = TextAlign.Center
                )

                // Subtítulo
                Text(
                    text = "Ao continuar, você declara que tem 18 anos ou mais e confirma sua data de nascimento.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextSecondary,
                        lineHeight = 20.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Campo de Data de Nascimento
                OutlinedTextField(
                    value = rawDateInput,
                    onValueChange = { novo ->
                        val masked = applyDateMask(novo)
                        rawDateInput = masked
                        validateAge(masked)
                    },
                    label = { Text("Data de nascimento:") },
                    placeholder = { Text("DD/MM/AAAA") },
                    supportingText = { Text("Exemplo: 24/07/1980") },
                    isError = errorMessage != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RadarCyan,
                        unfocusedBorderColor = DarkCardElevated,
                        focusedLabelColor = RadarCyan,
                        cursorColor = RadarCyan
                    )
                )

                // Mensagem de Erro / Bloqueio
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = AlertRed,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Checkbox de Declaração de Maioridade
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isDeclarationChecked,
                        onCheckedChange = { isDeclarationChecked = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = RadarCyan,
                            checkmarkColor = DeepBlack
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Declaro que tenho 18 anos ou mais e que as informações fornecidas são verdadeiras.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextPrimary,
                            lineHeight = 18.sp
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Botão [ Continuar ]
                Button(
                    onClick = {
                        if (isAdult && isDeclarationChecked) {
                            onAgeVerified()
                        }
                    },
                    enabled = isAdult && isDeclarationChecked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RadarCyan,
                        contentColor = DeepBlack,
                        disabledContainerColor = DarkCardElevated,
                        disabledContentColor = TextSecondary
                    )
                ) {
                    Text(
                        text = "Continuar",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    )
                }

                // Rodapé de Privacidade
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Privacidade",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Privacidade por padrão: sua data não é exposta.",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                    )
                }
            }
        }
    }
}
