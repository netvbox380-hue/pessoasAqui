package br.com.pessoasaqui.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.UserIntent
import br.com.pessoasaqui.ui.theme.*

@Composable
fun PeopleRadarTab(
    people: List<NearbyPerson>,
    currentIntent: UserIntent,
    onOpenIntentSelector: () -> Unit,
    onToggleMark: (String) -> Unit,
    onOpenChat: (NearbyPerson) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radarPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 16.dp)
    ) {
        // Banner do Radar Ativo (10 metros)
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(RadarCyan.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp * pulseScale)
                                .clip(CircleShape)
                                .background(RadarCyan)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Quem está aqui?",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "Raio de 10 metros ativo • ${people.size} pessoas",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                        )
                    }
                }

                // Chip de Intenção do Usuário Atual
                AssistChip(
                    onClick = onOpenIntentSelector,
                    label = {
                        Text(
                            text = "${currentIntent.icon} Minha Intenção",
                            style = MaterialTheme.typography.labelSmall.copy(color = RadarCyan)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = RadarCyanGlow,
                        labelColor = RadarCyan
                    ),
                    border = null
                )
            }
        }

        if (people.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Ninguém a menos de 10 metros no momento.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(people, key = { it.id }) { person ->
                    PersonCard(
                        person = person,
                        onToggleMark = { onToggleMark(person.id) },
                        onOpenChat = { onOpenChat(person) }
                    )
                }
            }
        }
    }
}

@Composable
fun PersonCard(
    person: NearbyPerson,
    onToggleMark: () -> Unit,
    onOpenChat: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenChat() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar (Foto protegida por padrão para desconhecidos)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(person.avatarColorHex).copy(alpha = 0.2f))
                        .border(1.5.dp, Color(person.avatarColorHex), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (person.isPhotoVisible) {
                        Text(
                            text = person.alias.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(person.avatarColorHex)
                            )
                        )
                    } else {
                        // Ícone anônimo para proteger a privacidade de estranhos
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Avatar anônimo",
                            tint = Color(person.avatarColorHex),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = person.alias,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        if (person.isFamily) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = FamilyPurple.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "Família • ${person.familyRole?.label}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = FamilyPurple,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Indicador de proximidade (ex: "4 m" ou "Muito perto")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (person.estimatedDistanceMeters < 3.0) EmeraldGreen else RadarCyan)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = person.proximityLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Text(
                            text = " • ${person.intent.icon} ${person.intent.label}",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                        )
                    }
                }

                // Botão de Marcação (Marcação unilateral vs mútua)
                IconButton(
                    onClick = onToggleMark,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = when {
                            person.isMutualConnection -> EmeraldGreenGlow
                            person.isMarkedByMe -> RadarCyanGlow
                            else -> DarkCardElevated
                        }
                    )
                ) {
                    Icon(
                        imageVector = if (person.isMarkedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Marcar pessoa",
                        tint = when {
                            person.isMutualConnection -> EmeraldGreen
                            person.isMarkedByMe -> RadarCyan
                            else -> TextSecondary
                        }
                    )
                }
            }

            // Banner de Conexão Mútua Estabelecida
            AnimatedVisibility(visible = person.isMutualConnection) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = EmeraldGreen.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "✨ Conexão Mútua Estabelecida! Vínculo à distância liberado.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = EmeraldGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "Conversar",
                            tint = EmeraldGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
