package br.com.pessoasaqui.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.pessoasaqui.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class EditorTool {
    NONE,
    BRUSH,
    RECTANGLE,
    CIRCLE,
    ARROW,
    STICKER,
    TEXT
}

sealed class DrawingElement {
    data class PathElement(val points: List<Offset>, val color: Color, val strokeWidth: Float) : DrawingElement()
    data class RectElement(val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : DrawingElement()
    data class CircleElement(val center: Offset, val radius: Float, val color: Color, val strokeWidth: Float) : DrawingElement()
    data class ArrowElement(val start: Offset, val end: Offset, val color: Color, val strokeWidth: Float) : DrawingElement()
    data class StickerElement(val text: String, val position: Offset, val isContentSticker: Boolean = false) : DrawingElement()
    data class TextElement(val text: String, val position: Offset, val color: Color, val bgColor: Color) : DrawingElement()
}

@Composable
fun ImageEditorDialog(
    sourceBitmap: Bitmap,
    isVip: Boolean,
    onSendEditedImage: (Bitmap) -> Unit,
    onUpgradeToVip: () -> Unit,
    onDismiss: () -> Unit
) {
    var currentTool by remember { mutableStateOf(EditorTool.BRUSH) }
    var selectedColor by remember { mutableStateOf(Color(0xFF00E5FF)) } // Cyan padrão
    var selectedStrokeWidth by remember { mutableFloatStateOf(8f) }
    val elements = remember { mutableStateListOf<DrawingElement>() }
    var currentPathPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var shapeStart by remember { mutableStateOf<Offset?>(null) }
    var shapeCurrent by remember { mutableStateOf<Offset?>(null) }

    var showStickerSheet by remember { mutableStateOf(false) }
    var showTextInputDialog by remember { mutableStateOf(false) }
    var customTextInput by remember { mutableStateOf("") }
    var showVipLockedDialog by remember { mutableStateOf(false) }

    val colors = listOf(
        Color(0xFF00E5FF), // Cyan
        Color(0xFF00E676), // Emerald
        Color(0xFFFFD600), // Amarelo
        Color(0xFFFF1744), // Vermelho
        Color(0xFFFFFFFF), // Branco
        Color(0xFF121212)  // Preto
    )

    val standardStickers = listOf("🔥", "❤️", "⭐", "👍", "🎉", "😂", "👏", "🚀", "💡", "📍", "⚡", "💯")

    val currentTimeStr = remember {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }
    val currentDateStr = remember {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
    }

    val contentStickers = listOf(
        "🕒 $currentTimeStr",
        "📍 PessoasAqui • ~10m",
        "🔒 E2EE Protegido",
        "📅 $currentDateStr"
    )

    fun checkVipAccess(tool: EditorTool): Boolean {
        if (isVip) return true
        return when (tool) {
            EditorTool.RECTANGLE, EditorTool.CIRCLE, EditorTool.ARROW -> {
                showVipLockedDialog = true
                false
            }
            else -> true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Área de Desenho e Visualização
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 64.dp, bottom = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val canvasWidth = constraints.maxWidth.toFloat()
                    val canvasHeight = constraints.maxHeight.toFloat()

                    // Calcula o aspect ratio da imagem para desenhar perfeitamente
                    val imgAspect = sourceBitmap.width.toFloat() / sourceBitmap.height.toFloat()
                    val canvasAspect = canvasWidth / canvasHeight

                    val (drawWidth, drawHeight) = if (imgAspect > canvasAspect) {
                        canvasWidth to (canvasWidth / imgAspect)
                    } else {
                        (canvasHeight * imgAspect) to canvasHeight
                    }

                    val drawOffset = Offset(
                        (canvasWidth - drawWidth) / 2f,
                        (canvasHeight - drawHeight) / 2f
                    )

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentTool, selectedColor, selectedStrokeWidth) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        if (currentTool == EditorTool.BRUSH) {
                                            currentPathPoints = listOf(offset)
                                        } else if (currentTool in listOf(EditorTool.RECTANGLE, EditorTool.CIRCLE, EditorTool.ARROW)) {
                                            shapeStart = offset
                                            shapeCurrent = offset
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        if (currentTool == EditorTool.BRUSH) {
                                            currentPathPoints = currentPathPoints + change.position
                                        } else if (currentTool in listOf(EditorTool.RECTANGLE, EditorTool.CIRCLE, EditorTool.ARROW)) {
                                            shapeCurrent = change.position
                                        }
                                    },
                                    onDragEnd = {
                                        if (currentTool == EditorTool.BRUSH && currentPathPoints.size > 1) {
                                            elements.add(DrawingElement.PathElement(currentPathPoints, selectedColor, selectedStrokeWidth))
                                            currentPathPoints = emptyList()
                                        } else if (shapeStart != null && shapeCurrent != null) {
                                            val start = shapeStart!!
                                            val end = shapeCurrent!!
                                            when (currentTool) {
                                                EditorTool.RECTANGLE -> elements.add(DrawingElement.RectElement(start, end, selectedColor, selectedStrokeWidth))
                                                EditorTool.CIRCLE -> {
                                                    val radius = (end - start).getDistance() / 2f
                                                    val center = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                                                    elements.add(DrawingElement.CircleElement(center, radius, selectedColor, selectedStrokeWidth))
                                                }
                                                EditorTool.ARROW -> elements.add(DrawingElement.ArrowElement(start, end, selectedColor, selectedStrokeWidth))
                                                else -> {}
                                            }
                                            shapeStart = null
                                            shapeCurrent = null
                                        }
                                    }
                                )
                            }
                    ) {
                        // 1. Desenha a Imagem Fonte
                        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        val dstRect = android.graphics.Rect(
                            drawOffset.x.toInt(),
                            drawOffset.y.toInt(),
                            (drawOffset.x + drawWidth).toInt(),
                            (drawOffset.y + drawHeight).toInt()
                        )
                        drawContext.canvas.nativeCanvas.drawBitmap(sourceBitmap, null, dstRect, paint)

                        // 2. Desenha Elementos Finalizados
                        for (el in elements) {
                            when (el) {
                                is DrawingElement.PathElement -> {
                                    if (el.points.size > 1) {
                                        for (i in 0 until el.points.size - 1) {
                                            drawLine(
                                                color = el.color,
                                                start = el.points[i],
                                                end = el.points[i + 1],
                                                strokeWidth = el.strokeWidth,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                        }
                                    }
                                }
                                is DrawingElement.RectElement -> {
                                    val left = minOf(el.start.x, el.end.x)
                                    val top = minOf(el.start.y, el.end.y)
                                    val right = maxOf(el.start.x, el.end.x)
                                    val bottom = maxOf(el.start.y, el.end.y)
                                    drawRect(
                                        color = el.color,
                                        topLeft = Offset(left, top),
                                        size = Size(right - left, bottom - top),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = el.strokeWidth)
                                    )
                                }
                                is DrawingElement.CircleElement -> {
                                    drawCircle(
                                        color = el.color,
                                        center = el.center,
                                        radius = el.radius,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = el.strokeWidth)
                                    )
                                }
                                is DrawingElement.ArrowElement -> {
                                    drawLine(
                                        color = el.color,
                                        start = el.start,
                                        end = el.end,
                                        strokeWidth = el.strokeWidth,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                    // Ponta da seta
                                    val angle = atan2(el.end.y - el.start.y, el.end.x - el.start.x)
                                    val headLength = (el.strokeWidth * 3f).coerceAtLeast(20f)
                                    val arrowAngle = Math.PI / 6
                                    val p1 = Offset(
                                        (el.end.x - headLength * cos(angle - arrowAngle)).toFloat(),
                                        (el.end.y - headLength * sin(angle - arrowAngle)).toFloat()
                                    )
                                    val p2 = Offset(
                                        (el.end.x - headLength * cos(angle + arrowAngle)).toFloat(),
                                        (el.end.y - headLength * sin(angle + arrowAngle)).toFloat()
                                    )
                                    drawLine(color = el.color, start = el.end, end = p1, strokeWidth = el.strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    drawLine(color = el.color, start = el.end, end = p2, strokeWidth = el.strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                }
                                is DrawingElement.StickerElement -> {
                                    val nativePaint = android.graphics.Paint().apply {
                                        textSize = if (el.isContentSticker) 34f else 48f
                                        isAntiAlias = true
                                    }
                                    if (el.isContentSticker) {
                                        // Fundo estilizado para figurinhas de conteúdo
                                        val textBounds = android.graphics.Rect()
                                        nativePaint.getTextBounds(el.text, 0, el.text.length, textBounds)
                                        val bgPaint = android.graphics.Paint().apply {
                                            color = android.graphics.Color.argb(200, 10, 25, 40)
                                            isAntiAlias = true
                                        }
                                        val borderPaint = android.graphics.Paint().apply {
                                            color = android.graphics.Color.argb(255, 0, 229, 255)
                                            style = android.graphics.Paint.Style.STROKE
                                            strokeWidth = 3f
                                            isAntiAlias = true
                                        }
                                        val rect = RectF(
                                            el.position.x - 16f,
                                            el.position.y - textBounds.height() - 16f,
                                            el.position.x + textBounds.width() + 16f,
                                            el.position.y + 16f
                                        )
                                        drawContext.canvas.nativeCanvas.drawRoundRect(rect, 14f, 14f, bgPaint)
                                        drawContext.canvas.nativeCanvas.drawRoundRect(rect, 14f, 14f, borderPaint)
                                        nativePaint.color = android.graphics.Color.WHITE
                                        nativePaint.isFakeBoldText = true
                                    }
                                    drawContext.canvas.nativeCanvas.drawText(el.text, el.position.x, el.position.y, nativePaint)
                                }
                                is DrawingElement.TextElement -> {
                                    val nativePaint = android.graphics.Paint().apply {
                                        textSize = 38f
                                        color = el.color.toArgb()
                                        isFakeBoldText = true
                                        isAntiAlias = true
                                    }
                                    val textBounds = android.graphics.Rect()
                                    nativePaint.getTextBounds(el.text, 0, el.text.length, textBounds)
                                    val bgPaint = android.graphics.Paint().apply {
                                        color = el.bgColor.toArgb()
                                        isAntiAlias = true
                                    }
                                    val rect = RectF(
                                        el.position.x - 12f,
                                        el.position.y - textBounds.height() - 10f,
                                        el.position.x + textBounds.width() + 12f,
                                        el.position.y + 10f
                                    )
                                    drawContext.canvas.nativeCanvas.drawRoundRect(rect, 10f, 10f, bgPaint)
                                    drawContext.canvas.nativeCanvas.drawText(el.text, el.position.x, el.position.y, nativePaint)
                                }
                            }
                        }

                        // 3. Desenha elemento em arrasto ativo (Preview em tempo real)
                        if (currentTool == EditorTool.BRUSH && currentPathPoints.size > 1) {
                            for (i in 0 until currentPathPoints.size - 1) {
                                drawLine(
                                    color = selectedColor,
                                    start = currentPathPoints[i],
                                    end = currentPathPoints[i + 1],
                                    strokeWidth = selectedStrokeWidth,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            }
                        } else if (shapeStart != null && shapeCurrent != null) {
                            val start = shapeStart!!
                            val end = shapeCurrent!!
                            when (currentTool) {
                                EditorTool.RECTANGLE -> {
                                    val left = minOf(start.x, end.x)
                                    val top = minOf(start.y, end.y)
                                    val right = maxOf(start.x, end.x)
                                    val bottom = maxOf(start.y, end.y)
                                    drawRect(
                                        color = selectedColor,
                                        topLeft = Offset(left, top),
                                        size = Size(right - left, bottom - top),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = selectedStrokeWidth)
                                    )
                                }
                                EditorTool.CIRCLE -> {
                                    val radius = (end - start).getDistance() / 2f
                                    val center = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                                    drawCircle(
                                        color = selectedColor,
                                        center = center,
                                        radius = radius,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = selectedStrokeWidth)
                                    )
                                }
                                EditorTool.ARROW -> {
                                    drawLine(color = selectedColor, start = start, end = end, strokeWidth = selectedStrokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    val angle = atan2(end.y - start.y, end.x - start.x)
                                    val headLength = (selectedStrokeWidth * 3f).coerceAtLeast(20f)
                                    val arrowAngle = Math.PI / 6
                                    val p1 = Offset(
                                        (end.x - headLength * cos(angle - arrowAngle)).toFloat(),
                                        (end.y - headLength * sin(angle - arrowAngle)).toFloat()
                                    )
                                    val p2 = Offset(
                                        (end.x - headLength * cos(angle + arrowAngle)).toFloat(),
                                        (end.y - headLength * sin(angle + arrowAngle)).toFloat()
                                    )
                                    drawLine(color = selectedColor, start = end, end = p1, strokeWidth = selectedStrokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    drawLine(color = selectedColor, start = end, end = p2, strokeWidth = selectedStrokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                }
                                else -> {}
                            }
                        }
                    }
                }

                // TopBar com Ações de Controle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = Color.White)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Desfazer
                        IconButton(
                            onClick = {
                                if (elements.isNotEmpty()) {
                                    elements.removeAt(elements.size - 1)
                                }
                            },
                            enabled = elements.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Undo,
                                contentDescription = "Desfazer",
                                tint = if (elements.isNotEmpty()) Color.White else Color.Gray
                            )
                        }

                        // Limpar Tudo
                        IconButton(
                            onClick = { elements.clear() },
                            enabled = elements.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Limpar Tudo",
                                tint = if (elements.isNotEmpty()) Color.White else Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Botão Enviar com Criptografia E2EE
                        Button(
                            onClick = {
                                // Renderiza imagem final em alta resolução com os elementos aplicados
                                val finalBitmap = renderFinalBitmap(sourceBitmap, elements)
                                onSendEditedImage(finalBitmap)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RadarCyan),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = DeepBlack, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Enviar", color = DeepBlack, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                // Barra Inferior de Ferramentas e Seletor de Cores
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    // Paleta de Cores
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        colors.forEach { color ->
                            val isSelected = selectedColor == color
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .size(if (isSelected) 30.dp else 22.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color.DarkGray,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = color }
                            )
                        }
                    }

                    // Seletor de Ferramentas
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Pincel Livre
                        ToolButton(
                            icon = Icons.Default.Brush,
                            label = "Pincel",
                            isSelected = currentTool == EditorTool.BRUSH,
                            onClick = { currentTool = EditorTool.BRUSH }
                        )

                        // 2. Retângulo (VIP)
                        ToolButton(
                            icon = Icons.Default.CropSquare,
                            label = "Retângulo",
                            isSelected = currentTool == EditorTool.RECTANGLE,
                            isVipBadge = !isVip,
                            onClick = {
                                if (checkVipAccess(EditorTool.RECTANGLE)) {
                                    currentTool = EditorTool.RECTANGLE
                                }
                            }
                        )

                        // 3. Círculo (VIP)
                        ToolButton(
                            icon = Icons.Default.RadioButtonUnchecked,
                            label = "Círculo",
                            isSelected = currentTool == EditorTool.CIRCLE,
                            isVipBadge = !isVip,
                            onClick = {
                                if (checkVipAccess(EditorTool.CIRCLE)) {
                                    currentTool = EditorTool.CIRCLE
                                }
                            }
                        )

                        // 4. Seta (VIP)
                        ToolButton(
                            icon = Icons.Default.ArrowOutward,
                            label = "Seta",
                            isSelected = currentTool == EditorTool.ARROW,
                            isVipBadge = !isVip,
                            onClick = {
                                if (checkVipAccess(EditorTool.ARROW)) {
                                    currentTool = EditorTool.ARROW
                                }
                            }
                        )

                        // 5. Figurinhas & Carimbos
                        ToolButton(
                            icon = Icons.Default.EmojiEmotions,
                            label = "Figurinhas",
                            isSelected = showStickerSheet,
                            onClick = { showStickerSheet = true }
                        )

                        // 6. Texto / Legenda
                        ToolButton(
                            icon = Icons.Default.Title,
                            label = "Texto",
                            isSelected = currentTool == EditorTool.TEXT,
                            onClick = { showTextInputDialog = true }
                        )
                    }
                }

                // Modal de Figurinhas e Figurinhas de Conteúdo
                if (showStickerSheet) {
                    AlertDialog(
                        onDismissRequest = { showStickerSheet = false },
                        containerColor = DarkSurface,
                        title = {
                            Text("Figurinhas & Carimbos de Conteúdo", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Carimbos de Conteúdo (Estilo Stories)", color = RadarCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                contentStickers.forEach { stickerText ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                elements.add(
                                                    DrawingElement.StickerElement(
                                                        text = stickerText,
                                                        position = Offset(100f, 250f + elements.size * 50f),
                                                        isContentSticker = true
                                                    )
                                                )
                                                showStickerSheet = false
                                            },
                                        shape = RoundedCornerShape(10.dp),
                                        color = DarkCard,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = stickerText,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))
                                Text("Figurinhas Rápidas", color = EmeraldGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    standardStickers.take(6).forEach { emoji ->
                                        Text(
                                            text = emoji,
                                            fontSize = 28.sp,
                                            modifier = Modifier.clickable {
                                                elements.add(
                                                    DrawingElement.StickerElement(
                                                        text = emoji,
                                                        position = Offset(150f, 300f + elements.size * 30f),
                                                        isContentSticker = false
                                                    )
                                                )
                                                showStickerSheet = false
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    standardStickers.drop(6).take(6).forEach { emoji ->
                                        Text(
                                            text = emoji,
                                            fontSize = 28.sp,
                                            modifier = Modifier.clickable {
                                                elements.add(
                                                    DrawingElement.StickerElement(
                                                        text = emoji,
                                                        position = Offset(150f, 300f + elements.size * 30f),
                                                        isContentSticker = false
                                                    )
                                                )
                                                showStickerSheet = false
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showStickerSheet = false }) {
                                Text("Fechar", color = RadarCyan)
                            }
                        }
                    )
                }

                // Diálogo para Adicionar Texto / Legenda
                if (showTextInputDialog) {
                    AlertDialog(
                        onDismissRequest = { showTextInputDialog = false },
                        containerColor = DarkSurface,
                        title = { Text("Inserir Texto na Imagem", color = TextPrimary) },
                        text = {
                            OutlinedTextField(
                                value = customTextInput,
                                onValueChange = { customTextInput = it },
                                placeholder = { Text("Digite sua legenda...") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RadarCyan,
                                    unfocusedBorderColor = TextSecondary,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (customTextInput.isNotBlank()) {
                                        elements.add(
                                            DrawingElement.TextElement(
                                                text = customTextInput.trim(),
                                                position = Offset(120f, 250f + elements.size * 40f),
                                                color = Color.White,
                                                bgColor = Color.Black.copy(alpha = 0.75f)
                                            )
                                        )
                                        customTextInput = ""
                                        showTextInputDialog = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = RadarCyan)
                            ) {
                                Text("Adicionar", color = DeepBlack, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTextInputDialog = false }) {
                                Text("Cancelar", color = TextSecondary)
                            }
                        }
                    )
                }

                // Diálogo VIP Lock (Cobrança de Recursos Extras)
                if (showVipLockedDialog) {
                    AlertDialog(
                        onDismissRequest = { showVipLockedDialog = false },
                        containerColor = DarkSurface,
                        icon = {
                            Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(36.dp))
                        },
                        title = {
                            Text("Recurso Extra VIP ⭐", color = WarningAmber, fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Text(
                                "Formas geométricas (retângulos, círculos e setas direcionais) e carimbos de alta resolução são recursos exclusivos do Plano VIP PessoasAqui.",
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showVipLockedDialog = false
                                    onUpgradeToVip()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
                            ) {
                                Text("Desbloquear no VIP", color = DeepBlack, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showVipLockedDialog = false }) {
                                Text("Fechar", color = TextSecondary)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    isVipBadge: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isSelected) RadarCyan.copy(alpha = 0.25f) else Color.Transparent)
                .border(
                    width = if (isSelected) 1.5.dp else 0.dp,
                    color = if (isSelected) RadarCyan else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) RadarCyan else Color.White,
                modifier = Modifier.size(20.dp)
            )
            if (isVipBadge) {
                Surface(
                    shape = CircleShape,
                    color = WarningAmber,
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                ) {}
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (isSelected) RadarCyan else Color.LightGray,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Renderiza todos os traços, formas e stickers sobre o Bitmap original
 * em escala real sem perder qualidade.
 */
private fun renderFinalBitmap(source: Bitmap, elements: List<DrawingElement>): Bitmap {
    val result = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(result)
    val scaleX = result.width.toFloat() / 1000f
    val scaleY = result.height.toFloat() / 1000f

    for (el in elements) {
        when (el) {
            is DrawingElement.PathElement -> {
                if (el.points.size > 1) {
                    val paint = Paint().apply {
                        color = el.color.toArgb()
                        strokeWidth = el.strokeWidth * scaleX.coerceAtLeast(1f)
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                    }
                    val path = Path()
                    path.moveTo(el.points[0].x * scaleX, el.points[0].y * scaleY)
                    for (i in 1 until el.points.size) {
                        path.lineTo(el.points[i].x * scaleX, el.points[i].y * scaleY)
                    }
                    canvas.drawPath(path, paint)
                }
            }
            is DrawingElement.RectElement -> {
                val paint = Paint().apply {
                    color = el.color.toArgb()
                    strokeWidth = el.strokeWidth * scaleX.coerceAtLeast(1f)
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                }
                val left = minOf(el.start.x, el.end.x) * scaleX
                val top = minOf(el.start.y, el.end.y) * scaleY
                val right = maxOf(el.start.x, el.end.x) * scaleX
                val bottom = maxOf(el.start.y, el.end.y) * scaleY
                canvas.drawRect(left, top, right, bottom, paint)
            }
            is DrawingElement.CircleElement -> {
                val paint = Paint().apply {
                    color = el.color.toArgb()
                    strokeWidth = el.strokeWidth * scaleX.coerceAtLeast(1f)
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                }
                canvas.drawCircle(el.center.x * scaleX, el.center.y * scaleY, el.radius * scaleX, paint)
            }
            is DrawingElement.ArrowElement -> {
                val paint = Paint().apply {
                    color = el.color.toArgb()
                    strokeWidth = el.strokeWidth * scaleX.coerceAtLeast(1f)
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    isAntiAlias = true
                }
                val startX = el.start.x * scaleX
                val startY = el.start.y * scaleY
                val endX = el.end.x * scaleX
                val endY = el.end.y * scaleY
                canvas.drawLine(startX, startY, endX, endY, paint)

                val angle = atan2(endY - startY, endX - startX)
                val headLength = (el.strokeWidth * 3f * scaleX).coerceAtLeast(25f)
                val arrowAngle = Math.PI / 6
                val p1X = (endX - headLength * cos(angle - arrowAngle)).toFloat()
                val p1Y = (endY - headLength * sin(angle - arrowAngle)).toFloat()
                val p2X = (endX - headLength * cos(angle + arrowAngle)).toFloat()
                val p2Y = (endY - headLength * sin(angle + arrowAngle)).toFloat()
                canvas.drawLine(endX, endY, p1X, p1Y, paint)
                canvas.drawLine(endX, endY, p2X, p2Y, paint)
            }
            is DrawingElement.StickerElement -> {
                val paint = Paint().apply {
                    textSize = (if (el.isContentSticker) 36f else 54f) * scaleX.coerceAtLeast(1f)
                    isAntiAlias = true
                }
                if (el.isContentSticker) {
                    val textBounds = android.graphics.Rect()
                    paint.getTextBounds(el.text, 0, el.text.length, textBounds)
                    val bgPaint = Paint().apply {
                        color = android.graphics.Color.argb(220, 10, 25, 40)
                        isAntiAlias = true
                    }
                    val borderPaint = Paint().apply {
                        color = android.graphics.Color.argb(255, 0, 229, 255)
                        style = Paint.Style.STROKE
                        strokeWidth = 3f * scaleX.coerceAtLeast(1f)
                        isAntiAlias = true
                    }
                    val rect = RectF(
                        el.position.x * scaleX - 18f,
                        el.position.y * scaleY - textBounds.height() - 18f,
                        el.position.x * scaleX + textBounds.width() + 18f,
                        el.position.y * scaleY + 18f
                    )
                    canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
                    canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
                    paint.color = android.graphics.Color.WHITE
                    paint.isFakeBoldText = true
                }
                canvas.drawText(el.text, el.position.x * scaleX, el.position.y * scaleY, paint)
            }
            is DrawingElement.TextElement -> {
                val paint = Paint().apply {
                    textSize = 42f * scaleX.coerceAtLeast(1f)
                    color = el.color.toArgb()
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val textBounds = android.graphics.Rect()
                paint.getTextBounds(el.text, 0, el.text.length, textBounds)
                val bgPaint = Paint().apply {
                    color = el.bgColor.toArgb()
                    isAntiAlias = true
                }
                val rect = RectF(
                    el.position.x * scaleX - 16f,
                    el.position.y * scaleY - textBounds.height() - 14f,
                    el.position.x * scaleX + textBounds.width() + 16f,
                    el.position.y * scaleY + 14f
                )
                canvas.drawRoundRect(rect, 12f, 12f, bgPaint)
                canvas.drawText(el.text, el.position.x * scaleX, el.position.y * scaleY, paint)
            }
        }
    }
    return result
}
