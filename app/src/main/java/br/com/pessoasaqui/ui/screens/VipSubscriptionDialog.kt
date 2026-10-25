package br.com.pessoasaqui.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.core.subscription.PlayBillingState
import br.com.pessoasaqui.core.subscription.SubscriptionTier
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Diálogo Completo de Assinatura e Gerenciamento do Plano VIP.
 *
 * Totalmente conectado ao Google Play Billing para compras em produção
 * e equipado com botão de restauração e modo de teste/homologação.
 */
@Composable
fun VipSubscriptionDialog(
    repository: PessoasAquiRepository,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val subscriptionTier by (repository.subscriptionManager?.currentTier
        ?: MutableStateFlow(SubscriptionTier.FREE)).collectAsState()

    val isVip = subscriptionTier == SubscriptionTier.PRO_VIP
    val isTestOverride = remember { mutableStateOf(repository.subscriptionManager?.isTestOverrideActive() ?: false) }

    val billingState by (repository.playBillingManager?.connectionState
        ?: MutableStateFlow(PlayBillingState.DISCONNECTED)).collectAsState()

    val formattedPrice by (repository.playBillingManager?.formattedPrice
        ?: MutableStateFlow("R$ 4,99 / mês")).collectAsState()

    val billingMessage by (repository.playBillingManager?.statusMessage
        ?: MutableStateFlow<String?>(null)).collectAsState()

    var isRestoring by remember { mutableStateOf(false) }
    var restoreFeedbackMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(24.dp)),
            color = DarkCard,
            border = BorderStroke(1.5.dp, WarningAmber.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header com Badge Dourado
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(WarningAmber.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.WorkspacePremium,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "PessoasAqui VIP",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = if (isVip) "Plano Ativo no Aparelho ⭐" else "Desbloqueie Todos os Recursos",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = if (isVip) EmeraldGreen else WarningAmber
                                )
                            )
                        }
                    }

                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mensagens de status ou feedback
                if (restoreFeedbackMessage != null || billingMessage != null) {
                    val msg = restoreFeedbackMessage ?: billingMessage
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurface,
                        border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = RadarCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = msg ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Card de Preço Oficial do Google Play
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Assinatura Mensal",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formattedPrice,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = WarningAmber,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Sem fidelidade • Cancele quando quiser no Google Play",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontSize = 11.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Lista de Benefícios VIP
                Text(
                    text = "VANTAGENS EXCLUSIVAS DO VIP",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = RadarCyan,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                VipBenefitItem(
                    icon = Icons.Default.Campaign,
                    title = "Transmissão para até 100 Contatos",
                    description = "Envie uma mensagem simultânea para até 100 pessoas mútuas (no Gratuito o limite é de 3).",
                    tint = RadarCyan
                )

                VipBenefitItem(
                    icon = Icons.Default.Timer,
                    title = "Resfriamento Rápido de 3 Minutos",
                    description = "Intervalo reduzido para apenas 3 minutos entre transmissões (no Gratuito são 15 minutos).",
                    tint = WarningAmber
                )

                VipBenefitItem(
                    icon = Icons.Default.NotificationsActive,
                    title = "Lembretes Recorrentes Ilimitados",
                    description = "Crie quantos lembretes diários, semanais, mensais e anuais precisar sem travas.",
                    tint = EmeraldGreen
                )

                VipBenefitItem(
                    icon = Icons.Default.Brush,
                    title = "Editor Avançado de Fotos & Formas",
                    description = "Desenhe retângulos, círculos, setas direcionais e figurinhas de conteúdo estilo Stories sobre suas fotos.",
                    tint = RadarCyan
                )

                VipBenefitItem(
                    icon = Icons.Default.Edit,
                    title = "Edição de Mensagens (até 30 min)",
                    description = "Corrija qualquer mensagem de texto enviada em até 30 minutos com integridade garantida.",
                    tint = WarningAmber
                )

                VipBenefitItem(
                    icon = Icons.Default.Star,
                    title = "Caixinha de Mensagens Importantes",
                    description = "Guarde e filtre mensagens essenciais isoladas na caixinha de cada conversa.",
                    tint = EmeraldGreen
                )

                VipBenefitItem(
                    icon = Icons.Default.VerifiedUser,
                    title = "Selo Dourado VIP & Criptografia E2EE",
                    description = "Badge exclusivo visível no seu perfil mantendo 100% da sua privacidade e anonimato.",
                    tint = WarningAmber
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Botão Primário: Assinar com Google Play
                if (!isVip) {
                    Button(
                        onClick = {
                            if (activity != null) {
                                val result = repository.launchVipPurchaseFlow(activity)
                                if (result.isFailure) {
                                    Toast.makeText(
                                        context,
                                        result.exceptionOrNull()?.message ?: "Falha ao iniciar Google Play Billing",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else {
                                Toast.makeText(context, "Atividade indisponível para faturamento.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarningAmber
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShoppingCart,
                            contentDescription = null,
                            tint = DeepBlack,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Assinar no Google Play",
                            color = DeepBlack,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = EmeraldGreen.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldGreen,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Você é um Membro VIP Ativo!",
                                color = EmeraldGreen,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Botão Secundário: Restaurar Compras (Google Play)
                OutlinedButton(
                    onClick = {
                        isRestoring = true
                        restoreFeedbackMessage = "Consultando Google Play..."
                        repository.restoreVipPurchases { success, msg ->
                            isRestoring = false
                            restoreFeedbackMessage = msg
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = BorderStroke(1.dp, DarkCardElevated)
                ) {
                    if (isRestoring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = RadarCyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verificando compras...", color = TextSecondary)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            tint = RadarCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restaurar Assinatura", color = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Modo de Teste / Homologação (Bypass para testes locais pré-Play Store)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, if (isTestOverride.value) WarningAmber.copy(alpha = 0.5f) else DarkCardElevated)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Modo Teste / Simulação (Dev)",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isTestOverride.value) WarningAmber else TextSecondary
                                )
                            )
                            Text(
                                text = "Permite testar recursos VIP antes da aprovação no Google Play Console.",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextSecondary.copy(alpha = 0.8f),
                                    fontSize = 10.sp
                                )
                            )
                        }

                        Switch(
                            checked = isTestOverride.value,
                            onCheckedChange = { checked ->
                                isTestOverride.value = checked
                                repository.setVipTestOverride(checked)
                                Toast.makeText(
                                    context,
                                    if (checked) "Modo VIP de Teste Ativado!" else "Modo de Teste Desativado (Plano Gratuito)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DeepBlack,
                                checkedTrackColor = WarningAmber
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Aviso de Conformidade com o Google Play Console
                Text(
                    text = "A assinatura é renovada automaticamente todo mês pelo Google Play. O cancelamento pode ser feito a qualquer momento acessando o Google Play > Ícone de Perfil > Pagamentos e assinaturas.",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun VipBenefitItem(
    icon: ImageVector,
    title: String,
    description: String,
    tint: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            )
        }
    }
}
