package br.com.pessoasaqui.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import br.com.pessoasaqui.core.chat.PrivateGifStudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Editor integralmente no dispositivo; não solicita permissões de rede ou serviços externos. */
@Composable
fun LocalGifEditorDialog(
    initialFrames: List<Bitmap>,
    onDismiss: () -> Unit,
    onSaved: (File) -> Unit
) {
    val context = LocalContext.current
    val studio = remember(context) { PrivateGifStudio(context) }
    val scope = rememberCoroutineScope()
    val frames = remember(initialFrames) { mutableStateListOf<Bitmap>().apply { addAll(initialFrames.take(PrivateGifStudio.MAX_FRAMES)) } }
    var selected by remember { mutableIntStateOf(0) }
    var delayMs by remember { mutableFloatStateOf(240f) }
    var caption by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playing, frames.size, delayMs) {
        while (playing && frames.size > 1) {
            delay(delayMs.toLong())
            selected = (selected + 1) % frames.size
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("🎞️ Criar GIF animado", style = MaterialTheme.typography.titleMedium)
                Text("Tudo é criado no aparelho. Até 18 quadros e 8 MB.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                if (frames.isNotEmpty()) {
                    Image(bitmap = frames[selected.coerceIn(0, frames.lastIndex)].asImageBitmap(),
                        contentDescription = "Prévia do quadro da animação",
                        modifier = Modifier.size(176.dp),
                        contentScale = ContentScale.Fit)
                    Text("Quadro ${selected + 1} de ${frames.size}",
                        style = MaterialTheme.typography.labelSmall)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(frames) { index, frame ->
                        Surface(
                            modifier = Modifier.size(46.dp).clickable { playing = false; selected = index },
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected == index) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Image(bitmap = frame.asImageBitmap(),
                                contentDescription = "Selecionar quadro ${index + 1}",
                                contentScale = ContentScale.Fit)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = selected > 0 && !busy, onClick = {
                        frames.add(selected - 1, frames.removeAt(selected)); selected--
                    }) { Text("←") }
                    TextButton(enabled = selected < frames.lastIndex && !busy, onClick = {
                        frames.add(selected + 1, frames.removeAt(selected)); selected++
                    }) { Text("→") }
                    TextButton(enabled = frames.size > 2 && !busy, onClick = {
                        frames.removeAt(selected); selected = selected.coerceAtMost(frames.lastIndex)
                    }) { Text("Excluir quadro") }
                    TextButton(enabled = !busy, onClick = { playing = !playing }) {
                        Text(if (playing) "Pausar" else "▶")
                    }
                }
                Text("Velocidade: ${delayMs.toInt()} ms/quadro", style = MaterialTheme.typography.labelMedium)
                Slider(value = delayMs, onValueChange = { delayMs = it },
                    valueRange = 80f..1000f, enabled = !busy)
                OutlinedTextField(value = caption, onValueChange = { caption = it.take(40) },
                    label = { Text("Legenda opcional (até 40 caracteres)") },
                    singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
                    Button(enabled = frames.size >= 2 && !busy, onClick = {
                        busy = true
                        playing = false
                        error = null
                        val snapshot = frames.toList()
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.Default) {
                                    studio.save(snapshot, delayMs.toInt(), caption)
                                }
                                onSaved(file)
                                Toast.makeText(context, "GIF salvo no acervo privado", Toast.LENGTH_SHORT).show()
                            } catch (t: Exception) {
                                error = t.message ?: "Falha ao criar GIF"
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(if (busy) "Criando..." else "Salvar GIF") }
                }
            }
        }
    }
}
