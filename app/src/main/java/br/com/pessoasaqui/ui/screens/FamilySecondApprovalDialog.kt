package br.com.pessoasaqui.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.pessoasaqui.domain.model.NearbyPerson
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.launch

/**
 * Segundo aval independente de um familiar. O QR/código é apenas referência
 * à sessão; nunca contém PIN ou parte em texto claro do segredo V3.
 */
@Composable
fun FamilySecondApprovalDialog(
    familyContacts: List<NearbyPerson>,
    onApprove: suspend (familyIdentity: String, recoveryKey: String) -> Result<Boolean>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val scanner = remember(context) {
        GmsBarcodeScanning.getClient(context,
            GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val families = remember(familyContacts) {
        familyContacts.filter { it.isFamily }.distinctBy {
            it.technicalIdentityHash.ifBlank { it.id }
        }
    }
    var selectedId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Segundo aval para recuperação familiar", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max=520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Escolha o familiar que está recuperando a conta. Você não pode ser a pessoa que emitiu a primeira autorização.")
                if (families.isEmpty()) Text("Nenhum familiar de confiança vinculado.")
                families.forEach { person ->
                    val id = person.technicalIdentityHash.ifBlank { person.id }
                    Row(modifier=Modifier.fillMaxWidth()) {
                        RadioButton(selected=selectedId==id,onClick={ selectedId=id })
                        TextButton(onClick={ selectedId=id }) { Text(person.alias) }
                    }
                }
                OutlinedTextField(
                    value=code,
                    onValueChange={ if(it.length<=80) code=it.trim().uppercase(java.util.Locale.ROOT) },
                    label={Text("Código PA-REC apresentado pelo familiar")},
                    modifier=Modifier.fillMaxWidth(),
                    singleLine=false,
                    maxLines=3
                )
                Row {
                    TextButton(onClick={
                        clipboard.getText()?.text?.let {
                            code=it.trim().uppercase(java.util.Locale.ROOT)
                        }
                    }) { Text("Colar") }
                    TextButton(onClick={
                        scanner.startScan().addOnSuccessListener { scan ->
                            val scanned=scan.rawValue.orEmpty().trim().uppercase(java.util.Locale.ROOT)
                            if (scanned.matches(Regex("^PA-REC-[A-F0-9]{64}$"))) code=scanned
                            else notice="QR não é uma autorização de recuperação válida."
                        }.addOnFailureListener { notice="Leitor indisponível; cole o código." }
                    }) { Text("Ler QR") }
                }
                Text(
                    "Confirme a identidade da pessoa fora do aplicativo. Sua assinatura será vinculada a esta sessão e não transfere o PIN ou o segredo do cofre.",
                    style=MaterialTheme.typography.bodySmall
                )
                notice?.let { Text(it, color=MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton={
            Button(
                enabled=!busy && selectedId.isNotBlank() &&
                    code.matches(Regex("^PA-REC-[A-F0-9]{64}$")),
                onClick={
                    busy=true
                    scope.launch {
                        val outcome=onApprove(selectedId,code)
                        busy=false
                        if(outcome.isSuccess) {
                            Toast.makeText(context,"Segundo aval confirmado no servidor.",Toast.LENGTH_LONG).show()
                            onDismiss()
                        } else {
                            notice=outcome.exceptionOrNull()?.message ?: "Aprovação recusada."
                        }
                    }
                }
            ) { Text(if(busy) "Verificando..." else "Assinar segundo aval") }
        },
        dismissButton={
            TextButton(onClick=onDismiss,enabled=!busy){Text("Cancelar")}
        }
    )
}
