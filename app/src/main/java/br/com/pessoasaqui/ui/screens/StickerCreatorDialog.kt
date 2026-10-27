package br.com.pessoasaqui.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.core.chat.StickerPersistenceManager
import br.com.pessoasaqui.ui.theme.*

enum class StickerShape {
    SQUARE,
    ROUNDED_SQUARE,
    CIRCLE
}

/**
 * Diálogo interativo completo para Criação e Customização de Figurinhas (Stickers).
 * Permite enquadrar com zoom/pan, escolher formato (quadrado, cantos arredondados, circular),
 * adicionar contorno branco de sticker, salvar no acervo de favoritos e enviar no chat.
 */
@Composable
fun StickerCreatorDialog(
    sourceBitmap: Bitmap,
    onStickerCreated: (Bitmap, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val stickerManager = remember { StickerPersistenceManager(context) }

    var selectedShape by remember { mutableStateOf(StickerShape.ROUNDED_SQUARE) }
    var withWhiteBorder by remember { mutableStateOf(true) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val sourceImageBitmap = remember(sourceBitmap) { sourceBitmap.asImageBitmap() }

    // Gera o Bitmap final da figurinha processado
    fun generateFinalSticker(): Bitmap {
        val size = 512
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Desenha a imagem aplicando a escala e o deslocamento
        val srcW = sourceBitmap.width.toFloat()
        val srcH = sourceBitmap.height.toFloat()

        val baseScale = maxOf(size / srcW, size / srcH)
        val finalScale = baseScale * scale

        val drawW = srcW * finalScale
        val drawH = srcH * finalScale
        val drawX = (size - drawW) / 2f + (offsetX * (size / 240f))
        val drawY = (size - drawH) / 2f + (offsetY * (size / 240f))

        val dstRect = RectF(drawX, drawY, drawX + drawW, drawY + drawH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(sourceBitmap, null, dstRect, paint)

        // Aplica a máscara de recorte de formato (Circular, Quadrado com cantos, etc.)
        val maskBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskBitmap)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }

        val cropMargin = if (withWhiteBorder) 14f else 4f
        val cropRect = RectF(cropMargin, cropMargin, size - cropMargin, size - cropMargin)

        when (selectedShape) {
            StickerShape.CIRCLE -> {
                maskCanvas.drawOval(cropRect, maskPaint)
            }
            StickerShape.ROUNDED_SQUARE -> {
                maskCanvas.drawRoundRect(cropRect, 48f, 48f, maskPaint)
            }
            StickerShape.SQUARE -> {
                maskCanvas.drawRect(cropRect, maskPaint)
            }
        }

        // Combina usando PorterDuff DST_IN para recortar a imagem com transparência
        val compositePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskBitmap, 0f, 0f, compositePaint)

        // Adiciona contorno branco de sticker se ativado
        if (withWhiteBorder) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 14f
            }
            when (selectedShape) {
                StickerShape.CIRCLE -> {
                    canvas.drawOval(cropRect, borderPaint)
                }
                StickerShape.ROUNDED_SQUARE -> {
                    canvas.drawRoundRect(cropRect, 48f, 48f, borderPaint)
                }
                StickerShape.SQUARE -> {
                    canvas.drawRect(cropRect, borderPaint)
                }
            }
        }

        return result
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.94f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Barra Superior do Criador
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = Color.White)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "⭐ Criar Figurinha",
                            color = WarningAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            text = "Ajuste o enquadramento com dois dedos",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Redefinir Enquadramento", tint = Color.White)
                    }
                }

                // Área Central: Enquadramento e Pré-visualização da Figurinha (240dp x 240dp)
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(
                            when (selectedShape) {
                                StickerShape.CIRCLE -> CircleShape
                                StickerShape.ROUNDED_SQUARE -> RoundedCornerShape(24.dp)
                                StickerShape.SQUARE -> RoundedCornerShape(4.dp)
                            }
                        )
                        .background(Color.Black.copy(alpha = 0.4f))
                        .then(
                            if (withWhiteBorder) {
                                Modifier.border(
                                    width = 3.dp,
                                    color = Color.White,
                                    shape = when (selectedShape) {
                                        StickerShape.CIRCLE -> CircleShape
                                        StickerShape.ROUNDED_SQUARE -> RoundedCornerShape(24.dp)
                                        StickerShape.SQUARE -> RoundedCornerShape(4.dp)
                                    }
                                )
                            } else Modifier
                        )
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.8f, 3.5f)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = sourceImageBitmap,
                        contentDescription = "Figurinha em criação",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            ),
                        contentScale = ContentScale.Crop
                    )
                }

                // Controles de Customização (Formatos e Borda Branca)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Seleção do Formato da Figurinha
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selectedShape == StickerShape.ROUNDED_SQUARE) RadarCyan else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.clickable { selectedShape = StickerShape.ROUNDED_SQUARE }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CropSquare,
                                    contentDescription = null,
                                    tint = if (selectedShape == StickerShape.ROUNDED_SQUARE) DeepBlack else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Arredondado", color = if (selectedShape == StickerShape.ROUNDED_SQUARE) DeepBlack else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selectedShape == StickerShape.CIRCLE) RadarCyan else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.clickable { selectedShape = StickerShape.CIRCLE }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Circle,
                                    contentDescription = null,
                                    tint = if (selectedShape == StickerShape.CIRCLE) DeepBlack else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Circular", color = if (selectedShape == StickerShape.CIRCLE) DeepBlack else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selectedShape == StickerShape.SQUARE) RadarCyan else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.clickable { selectedShape = StickerShape.SQUARE }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Square,
                                    contentDescription = null,
                                    tint = if (selectedShape == StickerShape.SQUARE) DeepBlack else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Quadrado", color = if (selectedShape == StickerShape.SQUARE) DeepBlack else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Alternar Contorno Branco de Adesivo
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { withWhiteBorder = !withWhiteBorder }
                    ) {
                        Checkbox(
                            checked = withWhiteBorder,
                            onCheckedChange = { withWhiteBorder = it },
                            colors = CheckboxDefaults.colors(checkedColor = RadarCyan)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Borda branca estilo sticker", color = Color.White, fontSize = 13.sp)
                    }

                    // Ações Finais: Salvar no Acervo / Enviar no Chat
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Salvar no Acervo de Figurinhas
                        OutlinedButton(
                            onClick = {
                                val stickerBmp = generateFinalSticker()
                                val id = stickerManager.saveSticker(stickerBmp)
                                Toast.makeText(context, "⭐ Figurinha salva no seu pacote de figurinhas!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber)
                        ) {
                            Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Salvar no Acervo", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        // Enviar Agora no Chat
                        Button(
                            onClick = {
                                val stickerBmp = generateFinalSticker()
                                val payload = stickerManager.bitmapToStickerPayload(stickerBmp)
                                stickerManager.saveSticker(stickerBmp) // Também salva no acervo para reutilizar
                                onStickerCreated(stickerBmp, payload)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Enviar Figurinha", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
