package br.com.pessoasaqui.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import br.com.pessoasaqui.ui.theme.*
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Proporções predefinidas para a ferramenta de corte de imagem convencional.
 */
enum class CropRatio(val label: String, val ratio: Float?) {
    FREE("Corte Livre", null),
    SQUARE("1:1 Quadrado", 1f),
    PORTRAIT("4:5 Retrato", 4f / 5f),
    STATUS("9:16 Status", 9f / 16f),
    LANDSCAPE("16:9 Paisagem", 16f / 9f)
}

/**
 * Alças de recorte do corte de imagem.
 */
enum class CropDragHandle {
    NONE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
    CENTER
}

/**
 * Formas geométricas interativas que podem ser arrastadas e coloridas totalmente ou como contorno.
 */
sealed class CanvasShape {
    abstract val id: String
    abstract var color: Color
    abstract var isFilled: Boolean
    abstract var strokeWidth: Float

    abstract fun contains(touch: Offset): Boolean
    abstract fun moveBy(delta: Offset)

    data class Circle(
        override val id: String = UUID.randomUUID().toString(),
        var center: Offset,
        var radius: Float,
        override var color: Color,
        override var isFilled: Boolean = false,
        override var strokeWidth: Float = 8f
    ) : CanvasShape() {
        override fun contains(touch: Offset): Boolean {
            val dist = (touch - center).getDistance()
            return if (isFilled) {
                dist <= radius + 25f
            } else {
                dist in (radius - strokeWidth - 25f).coerceAtLeast(0f)..(radius + strokeWidth + 25f)
            }
        }
        override fun moveBy(delta: Offset) {
            center = Offset(center.x + delta.x, center.y + delta.y)
        }
    }

    data class Rectangle(
        override val id: String = UUID.randomUUID().toString(),
        var topLeft: Offset,
        var size: Size,
        override var color: Color,
        override var isFilled: Boolean = false,
        override var strokeWidth: Float = 8f
    ) : CanvasShape() {
        override fun contains(touch: Offset): Boolean {
            val r = RectF(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height)
            return if (isFilled) {
                r.contains(touch.x, touch.y)
            } else {
                val margin = (strokeWidth + 25f).coerceAtLeast(30f)
                val outer = RectF(r.left - margin, r.top - margin, r.right + margin, r.bottom + margin)
                outer.contains(touch.x, touch.y)
            }
        }
        override fun moveBy(delta: Offset) {
            topLeft = Offset(topLeft.x + delta.x, topLeft.y + delta.y)
        }
    }

    data class Arrow(
        override val id: String = UUID.randomUUID().toString(),
        var start: Offset,
        var end: Offset,
        override var color: Color,
        override var isFilled: Boolean = false,
        override var strokeWidth: Float = 10f
    ) : CanvasShape() {
        override fun contains(touch: Offset): Boolean {
            val d = distanceToSegment(touch, start, end)
            val endDist = (touch - end).getDistance()
            val startDist = (touch - start).getDistance()
            return d <= (strokeWidth + 30f) || endDist <= 65f || startDist <= 65f
        }
        override fun moveBy(delta: Offset) {
            start = Offset(start.x + delta.x, start.y + delta.y)
            end = Offset(end.x + delta.x, end.y + delta.y)
        }

        fun rotateBy(degrees: Float) {
            val midX = (start.x + end.x) / 2f
            val midY = (start.y + end.y) / 2f
            val rad = Math.toRadians(degrees.toDouble())
            val cosA = cos(rad)
            val sinA = sin(rad)
            fun rot(p: Offset): Offset {
                val dx = p.x - midX
                val dy = p.y - midY
                return Offset(
                    (midX + dx * cosA - dy * sinA).toFloat(),
                    (midY + dx * sinA + dy * cosA).toFloat()
                )
            }
            start = rot(start)
            end = rot(end)
        }

        fun getAngleDegrees(): Float {
            val rad = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
            var deg = Math.toDegrees(rad).toFloat()
            if (deg < 0) deg += 360f
            return deg
        }

        fun setAngleDegrees(targetDegrees: Float) {
            val midX = (start.x + end.x) / 2f
            val midY = (start.y + end.y) / 2f
            val len = (end - start).getDistance().coerceAtLeast(60f)
            val half = len / 2f
            val rad = Math.toRadians(targetDegrees.toDouble())
            val dx = (half * cos(rad)).toFloat()
            val dy = (half * sin(rad)).toFloat()
            start = Offset(midX - dx, midY - dy)
            end = Offset(midX + dx, midY + dy)
        }
    }
}

enum class ArrowDragPart { NONE, TIP, BASE, BODY }

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val l2 = (b - a).getDistanceSquared()
    if (l2 == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2).coerceIn(0f, 1f)
    val projection = Offset(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
    return (p - projection).getDistance()
}

/**
 * Editor de Imagens Sem Inteligência Artificial.
 * - Formas interativas (Círculo, Quadrado, Seta) movíveis para qualquer lugar e coloridas completamente (fill) ou contorno (stroke).
 * - Corte de imagem com proporções predefinidas e corte livre sem deformação.
 * - Rotação e espelhamento (horizontal / vertical).
 * - Histórico completo de Desfazer (Undo), Refazer (Redo) e Restaurar original.
 * - Salvar na Galeria e Compartilhar via apps padrão.
 */
@Composable
fun ImageEditorDialog(
    sourceBitmap: Bitmap,
    isVip: Boolean = true,
    onSendEditedImage: (Bitmap) -> Unit,
    onUpgradeToVip: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Imagem base atual
    var currentBitmap by remember { mutableStateOf(sourceBitmap) }

    // Pilhas de Histórico
    val undoStack = remember { mutableStateListOf<Bitmap>() }
    val redoStack = remember { mutableStateListOf<Bitmap>() }

    // Modo de Corte
    var isCropping by remember { mutableStateOf(false) }
    var selectedCropRatio by remember { mutableStateOf(CropRatio.FREE) }
    var cropBoxRect by remember { mutableStateOf(RectF()) }
    var activeCropHandle by remember { mutableStateOf(CropDragHandle.NONE) }
    var dragStartTouch by remember { mutableStateOf(Offset.Zero) }
    var initialRectAtDragStart by remember { mutableStateOf(RectF()) }

    // Lista de Formas Adicionadas (Círculos, Quadrados, Setas)
    val shapes = remember { mutableStateListOf<CanvasShape>() }
    var selectedShapeId by remember { mutableStateOf<String?>(null) }
    var shapeColor by remember { mutableStateOf(Color(0xFF00E676)) } // Padrão Verde Esmeralda vibrante
    var isShapeFilled by remember { mutableStateOf(true) } // Padrão preenchido completamente conforme solicitado!
    var shapesRevision by remember { mutableIntStateOf(0) }
    var activeArrowDragPart by remember { mutableStateOf(ArrowDragPart.NONE) }

    // Dimensões da imagem desenhada
    var drawOffset by remember { mutableStateOf(Offset.Zero) }
    var drawWidth by remember { mutableFloatStateOf(1000f) }
    var drawHeight by remember { mutableFloatStateOf(1000f) }

    // Cores disponíveis para as formas
    val availableColors = listOf(
        Color(0xFF00E676), // Verde Esmeralda (bolinha verde sólida)
        Color(0xFF00E5FF), // Ciano Radar
        Color(0xFFFFD600), // Amarelo Ouro
        Color(0xFFFF1744), // Vermelho Alerta
        Color(0xFFBB86FC), // Roxo Família
        Color(0xFFFFFFFF), // Branco
        Color(0xFF121212)  // Preto
    )

    // Renderiza as formas permanentemente sobre o Bitmap
    fun bakeShapesIntoBitmap(): Bitmap {
        if (shapes.isEmpty()) return currentBitmap

        val result = currentBitmap.copy(currentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = AndroidCanvas(result)

        // Fatores de escala entre a tela e o bitmap real
        val scaleX = result.width.toFloat() / drawWidth
        val scaleY = result.height.toFloat() / drawHeight

        shapes.forEach { shape ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shape.color.toArgb()
                style = if (shape.isFilled) Paint.Style.FILL else Paint.Style.STROKE
                strokeWidth = shape.strokeWidth * scaleX
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            when (shape) {
                is CanvasShape.Circle -> {
                    val realCenterX = (shape.center.x - drawOffset.x) * scaleX
                    val realCenterY = (shape.center.y - drawOffset.y) * scaleY
                    val realRadius = shape.radius * scaleX
                    canvas.drawCircle(realCenterX, realCenterY, realRadius, paint)
                }
                is CanvasShape.Rectangle -> {
                    val realL = (shape.topLeft.x - drawOffset.x) * scaleX
                    val realT = (shape.topLeft.y - drawOffset.y) * scaleY
                    val realR = realL + (shape.size.width * scaleX)
                    val realB = realT + (shape.size.height * scaleY)
                    canvas.drawRect(realL, realT, realR, realB, paint)
                }
                is CanvasShape.Arrow -> {
                    val sX = (shape.start.x - drawOffset.x) * scaleX
                    val sY = (shape.start.y - drawOffset.y) * scaleY
                    val eX = (shape.end.x - drawOffset.x) * scaleX
                    val eY = (shape.end.y - drawOffset.y) * scaleY

                    // Desenha o corpo da seta
                    canvas.drawLine(sX, sY, eX, eY, paint)

                    // Ponta da seta
                    val angle = atan2((eY - sY).toDouble(), (eX - sX).toDouble())
                    val arrowHeadSize = (shape.strokeWidth * 3.5f * scaleX).coerceAtLeast(30f)
                    val path = AndroidPath().apply {
                        moveTo(eX, eY)
                        lineTo(
                            (eX - arrowHeadSize * cos(angle - Math.PI / 6)).toFloat(),
                            (eY - arrowHeadSize * sin(angle - Math.PI / 6)).toFloat()
                        )
                        lineTo(
                            (eX - arrowHeadSize * cos(angle + Math.PI / 6)).toFloat(),
                            (eY - arrowHeadSize * sin(angle + Math.PI / 6)).toFloat()
                        )
                        close()
                    }
                    val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = shape.color.toArgb()
                        style = Paint.Style.FILL
                    }
                    canvas.drawPath(path, headPaint)
                }
            }
        }
        return result
    }

    // Registra alteração de Bitmap no histórico
    fun applyBitmapTransformation(newBitmap: Bitmap) {
        val bakedCurrent = bakeShapesIntoBitmap()
        undoStack.add(bakedCurrent)
        currentBitmap = newBitmap
        shapes.clear()
        selectedShapeId = null
        redoStack.clear()
    }

    // Gira 90 graus
    fun rotate(degrees: Float) {
        try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val baked = bakeShapesIntoBitmap()
            val rotated = Bitmap.createBitmap(baked, 0, 0, baked.width, baked.height, matrix, true)
            applyBitmapTransformation(rotated)
        } catch (e: Exception) {
            Toast.makeText(context, "Erro ao girar imagem: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Espelha Horizontalmente
    fun flipHorizontal() {
        try {
            val matrix = Matrix().apply { postScale(-1f, 1f) }
            val baked = bakeShapesIntoBitmap()
            val flipped = Bitmap.createBitmap(baked, 0, 0, baked.width, baked.height, matrix, true)
            applyBitmapTransformation(flipped)
        } catch (e: Exception) {
            Toast.makeText(context, "Erro ao espelhar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Espelha Verticalmente
    fun flipVertical() {
        try {
            val matrix = Matrix().apply { postScale(1f, -1f) }
            val baked = bakeShapesIntoBitmap()
            val flipped = Bitmap.createBitmap(baked, 0, 0, baked.width, baked.height, matrix, true)
            applyBitmapTransformation(flipped)
        } catch (e: Exception) {
            Toast.makeText(context, "Erro ao espelhar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Desfazer
    fun undo() {
        if (undoStack.isNotEmpty()) {
            val prev = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(bakeShapesIntoBitmap())
            currentBitmap = prev
            shapes.clear()
            selectedShapeId = null
        }
    }

    // Refazer
    fun redo() {
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(bakeShapesIntoBitmap())
            currentBitmap = next
            shapes.clear()
            selectedShapeId = null
        }
    }

    // Restaurar Imagem Original
    fun restoreOriginal() {
        if (currentBitmap != sourceBitmap || undoStack.isNotEmpty() || shapes.isNotEmpty()) {
            undoStack.add(bakeShapesIntoBitmap())
            currentBitmap = sourceBitmap.copy(sourceBitmap.config ?: Bitmap.Config.ARGB_8888, true)
            shapes.clear()
            selectedShapeId = null
            redoStack.clear()
            isCropping = false
            Toast.makeText(context, "Imagem original restaurada.", Toast.LENGTH_SHORT).show()
        }
    }

    // Inicializa a moldura de corte adaptando à proporção
    fun resetCropBox(ratio: CropRatio) {
        val targetRatio = ratio.ratio
        if (targetRatio == null) {
            val marginX = drawWidth * 0.04f
            val marginY = drawHeight * 0.04f
            cropBoxRect = RectF(
                drawOffset.x + marginX,
                drawOffset.y + marginY,
                drawOffset.x + drawWidth - marginX,
                drawOffset.y + drawHeight - marginY
            )
        } else {
            val maxW = drawWidth * 0.92f
            val maxH = drawHeight * 0.92f
            val (boxW, boxH) = if (maxW / maxH > targetRatio) {
                (maxH * targetRatio) to maxH
            } else {
                maxW to (maxW / targetRatio)
            }
            val left = drawOffset.x + (drawWidth - boxW) / 2f
            val top = drawOffset.y + (drawHeight - boxH) / 2f
            cropBoxRect = RectF(left, top, left + boxW, top + boxH)
        }
    }

    // Aplica o corte de pixels tradicional
    fun applyCrop() {
        try {
            val baked = bakeShapesIntoBitmap()
            val normL = ((cropBoxRect.left - drawOffset.x) / drawWidth).coerceIn(0f, 1f)
            val normT = ((cropBoxRect.top - drawOffset.y) / drawHeight).coerceIn(0f, 1f)
            val normW = (cropBoxRect.width() / drawWidth).coerceIn(0.01f, 1f - normL)
            val normH = (cropBoxRect.height() / drawHeight).coerceIn(0.01f, 1f - normT)

            val pxX = (normL * baked.width).toInt().coerceIn(0, baked.width - 1)
            val pxY = (normT * baked.height).toInt().coerceIn(0, baked.height - 1)
            val pxW = (normW * baked.width).toInt().coerceIn(1, baked.width - pxX)
            val pxH = (normH * baked.height).toInt().coerceIn(1, baked.height - pxY)

            val cropped = Bitmap.createBitmap(baked, pxX, pxY, pxW, pxH)
            applyBitmapTransformation(cropped)
            isCropping = false
            Toast.makeText(context, "Corte aplicado.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Falha ao cortar imagem: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Adiciona Círculo no centro da imagem
    fun addCircle() {
        val center = Offset(drawOffset.x + drawWidth / 2f, drawOffset.y + drawHeight / 2f)
        val shape = CanvasShape.Circle(
            center = center,
            radius = (drawWidth * 0.12f).coerceIn(40f, 120f),
            color = shapeColor,
            isFilled = isShapeFilled
        )
        shapes.add(shape)
        selectedShapeId = shape.id
    }

    // Adiciona Quadrado no centro da imagem
    fun addRectangle() {
        val side = (drawWidth * 0.22f).coerceIn(70f, 200f)
        val topL = Offset(drawOffset.x + (drawWidth - side) / 2f, drawOffset.y + (drawHeight - side) / 2f)
        val shape = CanvasShape.Rectangle(
            topLeft = topL,
            size = Size(side, side),
            color = shapeColor,
            isFilled = isShapeFilled
        )
        shapes.add(shape)
        selectedShapeId = shape.id
    }

    // Adiciona Seta no centro da imagem
    fun addArrow() {
        val midX = drawOffset.x + drawWidth / 2f
        val midY = drawOffset.y + drawHeight / 2f
        val len = (drawWidth * 0.22f).coerceIn(80f, 220f)
        val shape = CanvasShape.Arrow(
            start = Offset(midX - len / 2f, midY),
            end = Offset(midX + len / 2f, midY),
            color = shapeColor,
            isFilled = isShapeFilled
        )
        shapes.add(shape)
        selectedShapeId = shape.id
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ==========================================
                // 1. BARRA SUPERIOR
                // ==========================================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancelar", tint = Color.White)
                    }

                    // Histórico (Desfazer, Refazer e Restaurar)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = { undo() },
                            enabled = undoStack.isNotEmpty(),
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (undoStack.isNotEmpty()) Color.White.copy(alpha = 0.15f) else Color.Transparent)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Desfazer",
                                tint = if (undoStack.isNotEmpty()) Color.White else Color.Gray.copy(alpha = 0.4f)
                            )
                        }

                        IconButton(
                            onClick = { redo() },
                            enabled = redoStack.isNotEmpty(),
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (redoStack.isNotEmpty()) Color.White.copy(alpha = 0.15f) else Color.Transparent)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                contentDescription = "Refazer",
                                tint = if (redoStack.isNotEmpty()) Color.White else Color.Gray.copy(alpha = 0.4f)
                            )
                        }

                        IconButton(
                            onClick = { restoreOriginal() },
                            enabled = undoStack.isNotEmpty() || currentBitmap != sourceBitmap || shapes.isNotEmpty(),
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    if (undoStack.isNotEmpty() || currentBitmap != sourceBitmap || shapes.isNotEmpty())
                                        Color.White.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                        ) {
                            Icon(
                                Icons.Default.RestartAlt,
                                contentDescription = "Restaurar",
                                tint = if (undoStack.isNotEmpty() || currentBitmap != sourceBitmap || shapes.isNotEmpty())
                                    RadarCyan
                                else Color.Gray.copy(alpha = 0.4f)
                            )
                        }
                    }

                    // Compartilhar e Enviar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val finalBmp = bakeShapesIntoBitmap()
                                shareBitmap(context, finalBmp)
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Compartilhar", tint = Color.White)
                        }

                        Button(
                            onClick = {
                                val finalBmp = bakeShapesIntoBitmap()
                                onSendEditedImage(finalBmp)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Enviar",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // ==========================================
                // 2. REGIÃO CENTRAL: Imagem, Formas e Corte
                // ==========================================
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val canvasWidth = constraints.maxWidth.toFloat()
                    val canvasHeight = constraints.maxHeight.toFloat()

                    val imgAspect = currentBitmap.width.toFloat() / currentBitmap.height.toFloat().coerceAtLeast(1f)
                    val canvasAspect = canvasWidth / canvasHeight.coerceAtLeast(1f)

                    val (dw, dh) = if (imgAspect > canvasAspect) {
                        canvasWidth to (canvasWidth / imgAspect)
                    } else {
                        (canvasHeight * imgAspect) to canvasHeight
                    }

                    val offX = (canvasWidth - dw) / 2f
                    val offY = (canvasHeight - dh) / 2f
                    drawOffset = Offset(offX, offY)
                    drawWidth = dw
                    drawHeight = dh

                    LaunchedEffect(isCropping, drawWidth, drawHeight) {
                        if (isCropping) {
                            resetCropBox(selectedCropRatio)
                        }
                    }

                    val currentImageBitmap = remember(currentBitmap) { currentBitmap.asImageBitmap() }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(isCropping, shapes.size) {
                                if (isCropping) {
                                    // Modo Corte Interativo
                                    detectDragGestures(
                                        onDragStart = { startPos ->
                                            dragStartTouch = startPos
                                            initialRectAtDragStart = RectF(cropBoxRect)
                                            val touchRadius = 85f
                                            val cL = cropBoxRect.left
                                            val cT = cropBoxRect.top
                                            val cR = cropBoxRect.right
                                            val cB = cropBoxRect.bottom

                                            activeCropHandle = when {
                                                (startPos - Offset(cL, cT)).getDistance() < touchRadius -> CropDragHandle.TOP_LEFT
                                                (startPos - Offset(cR, cT)).getDistance() < touchRadius -> CropDragHandle.TOP_RIGHT
                                                (startPos - Offset(cL, cB)).getDistance() < touchRadius -> CropDragHandle.BOTTOM_LEFT
                                                (startPos - Offset(cR, cB)).getDistance() < touchRadius -> CropDragHandle.BOTTOM_RIGHT
                                                Math.abs(startPos.x - cL) < touchRadius && startPos.y in (cT - 10f)..(cB + 10f) -> CropDragHandle.LEFT
                                                Math.abs(startPos.x - cR) < touchRadius && startPos.y in (cT - 10f)..(cB + 10f) -> CropDragHandle.RIGHT
                                                Math.abs(startPos.y - cT) < touchRadius && startPos.x in (cL - 10f)..(cR + 10f) -> CropDragHandle.TOP
                                                Math.abs(startPos.y - cB) < touchRadius && startPos.x in (cL - 10f)..(cR + 10f) -> CropDragHandle.BOTTOM
                                                cropBoxRect.contains(startPos.x, startPos.y) -> CropDragHandle.CENTER
                                                else -> CropDragHandle.NONE
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val dx = change.position.x - dragStartTouch.x
                                            val dy = change.position.y - dragStartTouch.y
                                            val minSize = 90f
                                            val imgL = drawOffset.x
                                            val imgT = drawOffset.y
                                            val imgR = drawOffset.x + drawWidth
                                            val imgB = drawOffset.y + drawHeight

                                            val init = initialRectAtDragStart
                                            var newL = init.left
                                            var newT = init.top
                                            var newR = init.right
                                            var newB = init.bottom

                                            val fixedRatio = selectedCropRatio.ratio

                                            if (activeCropHandle == CropDragHandle.CENTER) {
                                                val boxW = init.width()
                                                val boxH = init.height()
                                                newL = (init.left + dx).coerceIn(imgL, (imgR - boxW).coerceAtLeast(imgL))
                                                newT = (init.top + dy).coerceIn(imgT, (imgB - boxH).coerceAtLeast(imgT))
                                                newR = newL + boxW
                                                newB = newT + boxH
                                            } else if (fixedRatio == null) {
                                                when (activeCropHandle) {
                                                    CropDragHandle.TOP_LEFT -> {
                                                        newL = (init.left + dx).coerceIn(imgL, init.right - minSize)
                                                        newT = (init.top + dy).coerceIn(imgT, init.bottom - minSize)
                                                    }
                                                    CropDragHandle.TOP_RIGHT -> {
                                                        newR = (init.right + dx).coerceIn(init.left + minSize, imgR)
                                                        newT = (init.top + dy).coerceIn(imgT, init.bottom - minSize)
                                                    }
                                                    CropDragHandle.BOTTOM_LEFT -> {
                                                        newL = (init.left + dx).coerceIn(imgL, init.right - minSize)
                                                        newB = (init.bottom + dy).coerceIn(init.top + minSize, imgB)
                                                    }
                                                    CropDragHandle.BOTTOM_RIGHT -> {
                                                        newR = (init.right + dx).coerceIn(init.left + minSize, imgR)
                                                        newB = (init.bottom + dy).coerceIn(init.top + minSize, imgB)
                                                    }
                                                    CropDragHandle.LEFT -> newL = (init.left + dx).coerceIn(imgL, init.right - minSize)
                                                    CropDragHandle.RIGHT -> newR = (init.right + dx).coerceIn(init.left + minSize, imgR)
                                                    CropDragHandle.TOP -> newT = (init.top + dy).coerceIn(imgT, init.bottom - minSize)
                                                    CropDragHandle.BOTTOM -> newB = (init.bottom + dy).coerceIn(init.top + minSize, imgB)
                                                    else -> {}
                                                }
                                            } else {
                                                when (activeCropHandle) {
                                                    CropDragHandle.BOTTOM_RIGHT, CropDragHandle.RIGHT, CropDragHandle.BOTTOM -> {
                                                        val desiredW = (init.width() + dx).coerceIn(minSize, imgR - init.left)
                                                        val desiredH = desiredW / fixedRatio
                                                        if (init.top + desiredH <= imgB) {
                                                            newR = init.left + desiredW
                                                            newB = init.top + desiredH
                                                        }
                                                    }
                                                    CropDragHandle.TOP_LEFT, CropDragHandle.LEFT, CropDragHandle.TOP -> {
                                                        val desiredW = (init.width() - dx).coerceIn(minSize, init.right - imgL)
                                                        val desiredH = desiredW / fixedRatio
                                                        if (init.bottom - desiredH >= imgT) {
                                                            newL = init.right - desiredW
                                                            newT = init.bottom - desiredH
                                                        }
                                                    }
                                                    CropDragHandle.TOP_RIGHT -> {
                                                        val desiredW = (init.width() + dx).coerceIn(minSize, imgR - init.left)
                                                        val desiredH = desiredW / fixedRatio
                                                        if (init.bottom - desiredH >= imgT) {
                                                            newR = init.left + desiredW
                                                            newT = init.bottom - desiredH
                                                        }
                                                    }
                                                    CropDragHandle.BOTTOM_LEFT -> {
                                                        val desiredW = (init.width() - dx).coerceIn(minSize, init.right - imgL)
                                                        val desiredH = desiredW / fixedRatio
                                                        if (init.top + desiredH <= imgB) {
                                                            newL = init.right - desiredW
                                                            newB = init.top + desiredH
                                                        }
                                                    }
                                                    else -> {}
                                                }
                                            }
                                            cropBoxRect = RectF(newL, newT, newR, newB)
                                        },
                                        onDragEnd = { activeCropHandle = CropDragHandle.NONE },
                                        onDragCancel = { activeCropHandle = CropDragHandle.NONE }
                                    )
                                } else {
                                    // Mover Formas Livremente e Girar Seta 360° com arrasto na ponta ou na base
                                    detectDragGestures(
                                        onDragStart = { touchOffset ->
                                            val touched = shapes.findLast { it.contains(touchOffset) }
                                            if (touched != null) {
                                                selectedShapeId = touched.id
                                                // Sincroniza cor e estilo com a forma tocada
                                                shapeColor = touched.color
                                                isShapeFilled = touched.isFilled
                                                if (touched is CanvasShape.Arrow) {
                                                    val distEnd = (touchOffset - touched.end).getDistance()
                                                    val distStart = (touchOffset - touched.start).getDistance()
                                                    activeArrowDragPart = when {
                                                        distEnd <= 70f -> ArrowDragPart.TIP
                                                        distStart <= 70f -> ArrowDragPart.BASE
                                                        else -> ArrowDragPart.BODY
                                                    }
                                                } else {
                                                    activeArrowDragPart = ArrowDragPart.NONE
                                                }
                                            } else {
                                                selectedShapeId = null
                                                activeArrowDragPart = ArrowDragPart.NONE
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            selectedShapeId?.let { sId ->
                                                val s = shapes.find { it.id == sId }
                                                if (s is CanvasShape.Arrow && activeArrowDragPart != ArrowDragPart.BODY && activeArrowDragPart != ArrowDragPart.NONE) {
                                                    if (activeArrowDragPart == ArrowDragPart.TIP) {
                                                        s.end = Offset(s.end.x + dragAmount.x, s.end.y + dragAmount.y)
                                                    } else if (activeArrowDragPart == ArrowDragPart.BASE) {
                                                        s.start = Offset(s.start.x + dragAmount.x, s.start.y + dragAmount.y)
                                                    }
                                                } else {
                                                    s?.moveBy(dragAmount)
                                                }
                                                shapesRevision++
                                            }
                                        },
                                        onDragEnd = { activeArrowDragPart = ArrowDragPart.NONE },
                                        onDragCancel = { activeArrowDragPart = ArrowDragPart.NONE }
                                    )
                                }
                            }
                    ) {
                        // Força recomposição reativa do Canvas a 60fps durante arrasto ou mudança de estilo das formas
                        val _rev = shapesRevision

                        // Desenha a imagem base
                        drawImage(
                            image = currentImageBitmap,
                            dstOffset = androidx.compose.ui.unit.IntOffset(drawOffset.x.toInt(), drawOffset.y.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(drawWidth.toInt(), drawHeight.toInt())
                        )

                        // Desenha todas as formas (círculos, retângulos, setas)
                        shapes.forEach { shape ->
                            val isSelected = shape.id == selectedShapeId

                            when (shape) {
                                is CanvasShape.Circle -> {
                                    if (shape.isFilled) {
                                        drawCircle(color = shape.color, radius = shape.radius, center = shape.center)
                                    } else {
                                        drawCircle(
                                            color = shape.color,
                                            radius = shape.radius,
                                            center = shape.center,
                                            style = Stroke(width = shape.strokeWidth)
                                        )
                                    }
                                    if (isSelected) {
                                        drawCircle(
                                            color = Color.White.copy(alpha = 0.8f),
                                            radius = shape.radius + 6.dp.toPx(),
                                            center = shape.center,
                                            style = Stroke(width = 2.dp.toPx())
                                        )
                                    }
                                }
                                is CanvasShape.Rectangle -> {
                                    if (shape.isFilled) {
                                        drawRect(color = shape.color, topLeft = shape.topLeft, size = shape.size)
                                    } else {
                                        drawRect(
                                            color = shape.color,
                                            topLeft = shape.topLeft,
                                            size = shape.size,
                                            style = Stroke(width = shape.strokeWidth)
                                        )
                                    }
                                    if (isSelected) {
                                        drawRect(
                                            color = Color.White.copy(alpha = 0.8f),
                                            topLeft = Offset(shape.topLeft.x - 6.dp.toPx(), shape.topLeft.y - 6.dp.toPx()),
                                            size = Size(shape.size.width + 12.dp.toPx(), shape.size.height + 12.dp.toPx()),
                                            style = Stroke(width = 2.dp.toPx())
                                        )
                                    }
                                }
                                is CanvasShape.Arrow -> {
                                    // Linha da seta
                                    drawLine(
                                        color = shape.color,
                                        start = shape.start,
                                        end = shape.end,
                                        strokeWidth = shape.strokeWidth
                                    )
                                    // Ponta da seta
                                    val angle = atan2((shape.end.y - shape.start.y).toDouble(), (shape.end.x - shape.start.x).toDouble())
                                    val headLen = (shape.strokeWidth * 3f).coerceAtLeast(24f)
                                    val p1 = Offset(
                                        (shape.end.x - headLen * cos(angle - Math.PI / 6)).toFloat(),
                                        (shape.end.y - headLen * sin(angle - Math.PI / 6)).toFloat()
                                    )
                                    val p2 = Offset(
                                        (shape.end.x - headLen * cos(angle + Math.PI / 6)).toFloat(),
                                        (shape.end.y - headLen * sin(angle + Math.PI / 6)).toFloat()
                                    )
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(shape.end.x, shape.end.y)
                                        lineTo(p1.x, p1.y)
                                        lineTo(p2.x, p2.y)
                                        close()
                                    }
                                    drawPath(path = path, color = shape.color)

                                    if (isSelected) {
                                        // Alça de rotação 360° interativa na ponta da seta
                                        drawCircle(
                                            color = Color.White,
                                            radius = 14.dp.toPx(),
                                            center = shape.end,
                                            style = Stroke(width = 2.dp.toPx())
                                        )
                                        drawCircle(
                                            color = EmeraldGreen,
                                            radius = 6.dp.toPx(),
                                            center = shape.end
                                        )
                                        // Alça na base da seta
                                        drawCircle(
                                            color = Color.White.copy(alpha = 0.85f),
                                            radius = 8.dp.toPx(),
                                            center = shape.start,
                                            style = Stroke(width = 2.dp.toPx())
                                        )
                                    }
                                }
                            }
                        }

                        // Modo Corte Ativo (Moldura, Grade e Alças)
                        if (isCropping) {
                            val cL = cropBoxRect.left
                            val cT = cropBoxRect.top
                            val cR = cropBoxRect.right
                            val cB = cropBoxRect.bottom
                            val dimColor = Color.Black.copy(alpha = 0.65f)

                            drawRect(dimColor, Offset(0f, 0f), Size(canvasWidth, cT))
                            drawRect(dimColor, Offset(0f, cB), Size(canvasWidth, canvasHeight - cB))
                            drawRect(dimColor, Offset(0f, cT), Size(cL, cB - cT))
                            drawRect(dimColor, Offset(cR, cT), Size(canvasWidth - cR, cB - cT))

                            drawRect(
                                color = Color.White,
                                topLeft = Offset(cL, cT),
                                size = Size(cR - cL, cB - cT),
                                style = Stroke(width = 2.dp.toPx())
                            )

                            val w = cR - cL
                            val h = cB - cT
                            val gridColor = Color.White.copy(alpha = 0.4f)
                            val gridStroke = Stroke(width = 1.dp.toPx())

                            drawLine(gridColor, Offset(cL + w / 3f, cT), Offset(cL + w / 3f, cB), strokeWidth = gridStroke.width)
                            drawLine(gridColor, Offset(cL + 2f * w / 3f, cT), Offset(cL + 2f * w / 3f, cB), strokeWidth = gridStroke.width)
                            drawLine(gridColor, Offset(cL, cT + h / 3f), Offset(cR, cT + h / 3f), strokeWidth = gridStroke.width)
                            drawLine(gridColor, Offset(cL, cT + 2f * h / 3f), Offset(cR, cT + 2f * h / 3f), strokeWidth = gridStroke.width)

                            val cornerLength = 22.dp.toPx()
                            val cornerStroke = 4.dp.toPx()
                            val cornerColor = RadarCyan

                            drawLine(cornerColor, Offset(cL, cT), Offset(cL + cornerLength, cT), strokeWidth = cornerStroke)
                            drawLine(cornerColor, Offset(cL, cT), Offset(cL, cT + cornerLength), strokeWidth = cornerStroke)
                            drawLine(cornerColor, Offset(cR, cT), Offset(cR - cornerLength, cT), strokeWidth = cornerStroke)
                            drawLine(cornerColor, Offset(cR, cT), Offset(cR, cT - cornerLength), strokeWidth = cornerStroke)
                            drawLine(cornerColor, Offset(cL, cB), Offset(cL + cornerLength, cB), strokeWidth = cornerStroke)
                            drawLine(cornerColor, Offset(cL, cB), Offset(cL, cB - cornerLength), strokeWidth = cornerStroke)
                            drawLine(cornerColor, Offset(cR, cB), Offset(cR - cornerLength, cB), strokeWidth = cornerStroke)
                            drawLine(cornerColor, Offset(cR, cB), Offset(cR, cB - cornerLength), strokeWidth = cornerStroke)
                        }
                    }
                }

                // ==========================================
                // 3. PARTE INFERIOR: Ferramentas e Formas
                // ==========================================
                Surface(
                    color = Color.Black.copy(alpha = 0.94f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                ) {
                    if (isCropping) {
                        // Painel de Corte
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CropRatio.values().forEach { ratio ->
                                    val isSelected = selectedCropRatio == ratio
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (isSelected) RadarCyan else Color.White.copy(alpha = 0.12f),
                                        modifier = Modifier.clickable {
                                            selectedCropRatio = ratio
                                            resetCropBox(ratio)
                                        }
                                    ) {
                                        Text(
                                            text = ratio.label,
                                            color = if (isSelected) DeepBlack else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { isCropping = false }) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = AlertRed, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Cancelar Corte", color = AlertRed, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { applyCrop() },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Aplicar Corte", color = TextPrimary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Painel Geral: Formas + Ferramentas
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp, horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Sub-barra superior de Formas e Cores (quando há forma ou para novas formas)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Alternador: Preenchido (Bolinha sólida) vs Contorno
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isShapeFilled) EmeraldGreen else Color.White.copy(alpha = 0.15f),
                                    modifier = Modifier.clickable {
                                        isShapeFilled = !isShapeFilled
                                        selectedShapeId?.let { id ->
                                            shapes.find { it.id == id }?.isFilled = isShapeFilled
                                        }
                                        shapesRevision++
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isShapeFilled) Icons.Default.Circle else Icons.Default.PanoramaFishEye,
                                            contentDescription = null,
                                            tint = if (isShapeFilled) DeepBlack else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isShapeFilled) "Preenchido (Sólido)" else "Contorno",
                                            color = if (isShapeFilled) DeepBlack else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Seletor de Cores da Forma
                                availableColors.forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .then(
                                                if (shapeColor == c) Modifier.border(2.dp, Color.White, CircleShape) else Modifier
                                            )
                                            .clickable {
                                                shapeColor = c
                                                selectedShapeId?.let { id ->
                                                    shapes.find { it.id == id }?.color = c
                                                }
                                                shapesRevision++
                                            }
                                    )
                                }

                                // Controles Interativos de Rotação 360° para Seta
                                val selectedArrow = shapes.find { it.id == selectedShapeId } as? CanvasShape.Arrow
                                if (selectedArrow != null) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = EmeraldGreen.copy(alpha = 0.2f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "↻ 360° (${selectedArrow.getAngleDegrees().toInt()}°)",
                                                color = EmeraldGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                shape = CircleShape,
                                                color = Color.White.copy(alpha = 0.15f),
                                                modifier = Modifier.size(26.dp).clickable {
                                                    selectedArrow.rotateBy(-15f)
                                                    shapesRevision++
                                                }
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "-15°", tint = Color.White, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            Surface(
                                                shape = CircleShape,
                                                color = Color.White.copy(alpha = 0.15f),
                                                modifier = Modifier.size(26.dp).clickable {
                                                    selectedArrow.rotateBy(15f)
                                                    shapesRevision++
                                                }
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "+15°", tint = Color.White, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color.White.copy(alpha = 0.15f),
                                                modifier = Modifier.clickable {
                                                    selectedArrow.rotateBy(45f)
                                                    shapesRevision++
                                                }
                                            ) {
                                                Text("+45°", color = EmeraldGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color.White.copy(alpha = 0.15f),
                                                modifier = Modifier.clickable {
                                                    selectedArrow.rotateBy(180f)
                                                    shapesRevision++
                                                }
                                            ) {
                                                Text("180°", color = EmeraldGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                                            }
                                        }
                                    }
                                }

                                // Excluir Forma Selecionada
                                if (selectedShapeId != null) {
                                    TextButton(
                                        onClick = {
                                            shapes.removeAll { it.id == selectedShapeId }
                                            selectedShapeId = null
                                            shapesRevision++
                                        }
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = AlertRed, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Excluir Forma", color = AlertRed, fontSize = 11.sp)
                                    }
                                }
                            }

                            // Barra Principal de Ações
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Cortar Imagem
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        isCropping = true
                                        resetCropBox(selectedCropRatio)
                                    }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = RadarCyan.copy(alpha = 0.25f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, RadarCyan),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Crop, contentDescription = "Cortar", tint = RadarCyan, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Cortar", color = RadarCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                // 2. Adicionar Círculo (Preenchido ou Contorno, Mover Livremente)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { addCircle() }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.12f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Circle, contentDescription = "Círculo", tint = EmeraldGreen, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Círculo", color = Color.White, fontSize = 10.sp)
                                }

                                // 3. Adicionar Quadrado (Preenchido ou Contorno, Mover Livremente)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { addRectangle() }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.12f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Square, contentDescription = "Quadrado", tint = RadarCyan, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Quadrado", color = Color.White, fontSize = 10.sp)
                                }

                                // 4. Adicionar Seta (Mover Livremente)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { addArrow() }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.12f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.AutoMirrored.Filled.TrendingFlat, contentDescription = "Seta", tint = WarningAmber, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Seta", color = Color.White, fontSize = 10.sp)
                                }

                                // 5. Girar 90° Esq.
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { rotate(-90f) }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.12f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "Girar Esq.", tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Girar Esq.", color = Color.White, fontSize = 10.sp)
                                }

                                // 6. Girar 90° Dir.
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { rotate(90f) }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.12f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Girar Dir.", tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Girar Dir.", color = Color.White, fontSize = 10.sp)
                                }

                                // 7. Espelhar Horizontal
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { flipHorizontal() }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.12f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.SwapHoriz, contentDescription = "Espelhar H.", tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Espelhar H.", color = Color.White, fontSize = 10.sp)
                                }

                                // 8. Espelhar Vertical
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { flipVertical() }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.12f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.SwapVert, contentDescription = "Espelhar V.", tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Espelhar V.", color = Color.White, fontSize = 10.sp)
                                }

                                // 9. Salvar na Galeria
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        val finalBmp = bakeShapesIntoBitmap()
                                        val ok = saveBitmapToGallery(context, finalBmp)
                                        Toast.makeText(context, if (ok) "✅ Imagem salva na Galeria com sucesso!" else "Falha ao salvar imagem", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.12f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Download, contentDescription = "Salvar", tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Salvar", color = Color.White, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
