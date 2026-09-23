package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.pessoasaqui.core.proximity.ProximitySimulator
import br.com.pessoasaqui.ui.theme.*
import kotlin.math.roundToInt

/**
 * Painel de Testes e Simulação de Proximidade (Playground 10m).
 * Permite ajustar distâncias ao vivo e testar o limite de 10 metros e marcações.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyPlaygroundSheet(
    simulator: ProximitySimulator,
    onDismiss: () -> Unit
) {
    val discovered by simulator.discoveredPeople.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Science,
                    contentDescription = null,
                    tint = RadarCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Playground de Proximidade (10 Metros)",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            }

            Text(
                text = "Arraste a distância dos contatos para testar o limite de 10m ao vivo.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(discovered) { person ->
                    var distSlider by remember(person.id, person.estimatedDistanceMeters) {
                        mutableFloatStateOf(person.estimatedDistanceMeters.toFloat())
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = person.alias,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    text = "${distSlider.roundToInt()} metros (${if (distSlider <= 10f) "No raio" else "Fora do raio"})",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (distSlider <= 10f) EmeraldGreen else AlertRed,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }

                            Slider(
                                value = distSlider,
                                onValueChange = { distSlider = it },
                                onValueChangeFinished = {
                                    simulator.updateSimulatedDistance(person.id, distSlider.toDouble())
                                },
                                valueRange = 1f..15f,
                                colors = SliderDefaults.colors(
                                    thumbColor = RadarCyan,
                                    activeTrackColor = RadarCyan,
                                    inactiveTrackColor = DarkCardElevated
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
