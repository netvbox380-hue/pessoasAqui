package br.com.pessoasaqui.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import br.com.pessoasaqui.core.qrcode.QrCodeGenerator
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.ui.theme.*

/**
 * Diálogo gerado pelo familiar autorizado (Seções 19, 20, 21 e 22 do Prompt).
 * Exibe o QR Code real gerado com ZXing e chave temporária criptográfica de uso único para recuperação.
 */
@Composable
fun FamilyRecoveryDialog(
    person: NearbyPerson,
    authorization: RecoveryAuthorization,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copiedNotice by remember { mutableStateOf(false) }

    val qrBitmap = remember(authorization.recoveryKey) {
        QrCodeGenerator.generateQrCodeBitmap(authorization.recoveryKey, 512, 512)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = RadarCyan,
                    modifier = Modifier.size(40.dp)
                )

                Text(
                    text = "Autorização de Recuperação",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    ),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Autorização temporária gerada para ${person.alias}. O novo aparelho precisará apontar a câmera ou inserir esta chave e o PIN pessoal.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                    textAlign = TextAlign.Center
                )

                // Representação visual do QR Code Criptográfico Real
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    modifier = Modifier
                        .size(190.dp)
                        .padding(8.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "QR Code Criptográfico de Recuperação",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.QrCode2,
                                contentDescription = null,
                                tint = DeepBlack,
                                modifier = Modifier.size(110.dp)
                            )
                        }
                    }
                }

                // Chave de Recuperação Alfanumérica com Clique para Copiar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkCard, RoundedCornerShape(10.dp))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(authorization.recoveryKey))
                            copiedNotice = true
                        }
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (copiedNotice) "✓ COPIADO PARA ÁREA DE TRANSFERÊNCIA" else "TOQUE PARA COPIAR A CHAVE:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (copiedNotice) EmeraldGreen else TextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copiar",
                            tint = if (copiedNotice) EmeraldGreen else TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = authorization.recoveryKey,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = RadarCyan,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = WarningAmber.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "⚠️ Válido por 15 minutos • Uso único • Você NÃO deve saber o PIN do usuário.",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = WarningAmber,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RadarCyan,
                        contentColor = DeepBlack
                    )
                ) {
                    Text(
                        text = "Fechar",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
