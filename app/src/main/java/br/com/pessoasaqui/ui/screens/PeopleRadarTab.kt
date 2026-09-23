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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.UserIntent
import br.com.pessoasaqui.ui.theme.*

/**
 * Aba Principal do Radar de Proximidade (Pessoas).
 * Design moderno em nível de produção: responsivo, com tipografia refinada e sem quebras de layout.
 */
@Composable
fun PeopleRadarTab(
    people: List<NearbyPerson>,
    currentIntent: UserIntent,
    isDemoMode: Boolean = false,
    isBluetoothEnabled: Boolean = true,
    onToggleDemoMode: () -> Unit = {},
    onEnableBluetooth: () -> Unit = {},
    onOpenIntentSelector: () -> Unit,
    onToggleMark: (String) -> Unit,
    onOpenChat: (NearbyPerson) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radarPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
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
        if (!isBluetoothEnabled) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = WarningAmber.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Bluetooth Desligado",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Ative para achar pessoas no raio de 10m",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                        }
                    }
                    Button(
                        onClick = onEnableBluetooth,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarningAmber,
                            contentColor = DeepBlack
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Ativar",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }

        if (isDemoMode) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = WarningAmber.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "🧪", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Modo Demonstração (Contatos Simulados)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = WarningAmber,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    TextButton(
                        onClick = onToggleDemoMode,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "Modo Real",
                            color = WarningAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Card de Status do Radar & Seletor de Intenção
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Linha superior: Status do radar e contagem de pessoas
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(RadarCyan.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp * pulseScale)
                                    .clip(CircleShape)
                                    .background(RadarCyan)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Radar Hiperlocal",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = if (people.isEmpty()) "Escaneando presença no ar..." else "${people.size} pessoa(s) no seu alcance",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = RadarCyan.copy(alpha = 0.12f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldGreen)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "10 Metros",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = RadarCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }

                // Linha inferior: Seletor de Intenção do Usuário
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkCardElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenIntentSelector() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = currentIntent.icon,
                                fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Minha intenção agora:",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                )
                                Text(
                                    text = currentIntent.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DarkCard,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = "Alterar",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = RadarCyan,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Lista de Pessoas ou Estado Vazio
        if (people.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier.size(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(150.dp * pulseScale)
                                .clip(CircleShape)
                                .background(RadarCyan.copy(alpha = 0.04f))
                        )
                        Box(
                            modifier = Modifier
                                .size(110.dp * pulseScale)
                                .clip(CircleShape)
                                .background(RadarCyan.copy(alpha = 0.08f))
                        )
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(RadarCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BluetoothSearching,
                                contentDescription = null,
                                tint = RadarCyan,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Text(
                        text = "Procurando pessoas no raio de 10m...",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Qualquer pessoa que estiver fisicamente perto com o PessoasAqui aberto aparecerá automaticamente aqui no seu radar.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        onClick = onToggleDemoMode,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarCyan)
                    ) {
                        Text(
                            text = if (isDemoMode) "Desativar Modo Demonstração" else "🧪 Testar com Contatos de Exemplo",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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

/**
 * Card individual de pessoa próxima com layout responsivo e profissional.
 */
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
                // Avatar com anel colorido de presença
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(person.avatarColorHex).copy(alpha = 0.15f))
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
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color(person.avatarColorHex),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Coluna central com dados da pessoa
                Column(modifier = Modifier.weight(1f)) {
                    // Nome e Selo de Família
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = person.alias,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (person.isFamily) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = FamilyPurple.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "Família • ${person.familyRole?.label ?: "Membro"}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = FamilyPurple,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Linha com distância e intenção com formatação limpa e tags separadas
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Badge de Distância
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (person.estimatedDistanceMeters < 3.0) EmeraldGreen.copy(alpha = 0.15f) else RadarCyan.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "• ${person.estimatedDistanceMeters.toInt()}m",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (person.estimatedDistanceMeters < 3.0) EmeraldGreen else RadarCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Intenção com ícone e texto truncado de forma segura
                        Text(
                            text = "${person.intent.icon} ${person.intent.label}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 12.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Botão de Marcação (Coração)
                IconButton(
                    onClick = onToggleMark,
                    modifier = Modifier.size(40.dp),
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
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Banner elegante de Conexão Mútua Estabelecida
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
                            text = "✨ Conexão Mútua Estabelecida! Vínculo liberado.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = EmeraldGreen,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ChatBubble,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
