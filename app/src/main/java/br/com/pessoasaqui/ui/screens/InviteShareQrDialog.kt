package br.com.pessoasaqui.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.core.qrcode.InviteLinkParser
import br.com.pessoasaqui.core.qrcode.QrCodeGenerator
import br.com.pessoasaqui.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Link e QR transportam exatamente o mesmo convite: um token de posse
 * pessoal, assinado pelo servidor, que será consumido somente após
 * confirmação explícita pelo destinatário e validação no backend.
 *
 * O QR NÃO cria conexão local/offline; é uma alternativa à digitação do link.
 */
@Composable
fun InviteShareQrDialog(inviteUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val invite = remember(inviteUrl) { InviteLinkParser.parse(inviteUrl) }
    val bitmap = remember(inviteUrl) {
        if (invite != null) QrCodeGenerator.generateQrCodeBitmap(
            inviteUrl, width = 420, height = 420
        ) else null
    }
    val expiryLabel = remember(invite) {
        invite?.expiresAt?.let {
            SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "Prazo definido no servidor"
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 520.dp)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(22.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCode, contentDescription = null,
                        tint = RadarCyan, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Convite de uso único", color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar",
                            tint = TextSecondary)
                    }
                }

                Text(
                    "Para alguém perto: mostre o QR nesta tela. Para alguém distante: compartilhe o link.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                if (bitmap != null) {
                    Surface(color = Color.White,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.widthIn(max = 310.dp).fillMaxWidth()) {
                        Image(bitmap = bitmap, contentDescription = "QR Code do convite de conexão",
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp))
                    }
                    Text("A outra pessoa deve usar Conversas → Ler QR de convite.",
                        color = RadarCyan,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center)
                } else {
                    Text("Não foi possível gerar o QR. O link continua disponível.",
                        color = WarningAmber, style = MaterialTheme.typography.bodySmall)
                }

                Text("Validade até: $expiryLabel • 1 resgate",
                    color = WarningAmber, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center)

                Button(
                    onClick = {
                        try {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Convite de conexão — PessoasAqui")
                                putExtra(Intent.EXTRA_TEXT,
                                    "Convite pessoal de uso único para conectar no PessoasAqui. " +
                                    "Use apenas se você conhece a pessoa que enviou.\n\n$inviteUrl")
                            }
                            context.startActivity(Intent.createChooser(share,
                                "Compartilhar convite de conexão"))
                        } catch (_: Exception) {
                            Toast.makeText(context, "Compartilhamento indisponível",
                                Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Compartilhar link", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Convite PessoasAqui", inviteUrl))
                        Toast.makeText(context, "Link copiado. Compartilhe apenas com a pessoa desejada.",
                            Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = RadarCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Copiar link", color = RadarCyan)
                }
                Text(
                    "Privacidade: link e QR possuem o mesmo token secreto. " +
                    "Quem o receber poderá tentar utilizá-lo uma vez, antes do vencimento. " +
                    "O destinatário precisa de internet para validar o convite.",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
