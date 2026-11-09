package br.com.pessoasaqui.core.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * GIFs locais são dados privados do aparelho, nunca enviados a um serviço de conversão.
 * Acervo separado dos PNGs legados; exportações não alteram mensagens já enviadas.
 */
class PrivateGifStudio(private val context: Context) {
    companion object {
        const val MAX_GIF_BYTES = 8 * 1024 * 1024
        const val MAX_FRAMES = 18
        const val FRAME_SIZE = 240
        const val MAX_LIBRARY = 80
        private val VALID_NAME = Regex("^gif_[a-f0-9-]{36}\\.gif$")
    }

    private val directory: File get() = File(context.filesDir, "private_gif_studio").apply { mkdirs() }

    fun list(): List<File> = directory.listFiles()
        ?.filter { VALID_NAME.matches(it.name) && it.length() in 14..MAX_GIF_BYTES.toLong() }
        ?.sortedByDescending { it.lastModified() }?.take(MAX_LIBRARY) ?: emptyList()

    fun delete(file: File): Boolean =
        file.parentFile?.canonicalPath == directory.canonicalPath && VALID_NAME.matches(file.name) && file.delete()

    fun payload(file: File): Pair<String, Long>? {
        if (file.parentFile?.canonicalPath != directory.canonicalPath ||
            !VALID_NAME.matches(file.name) || file.length() !in 14..MAX_GIF_BYTES.toLong()
        ) return null
        val bytes = file.readBytes()
        if (!isGif(bytes)) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP) to bytes.size.toLong()
    }

    fun save(frames: List<Bitmap>, delayMs: Int, text: String): File {
        require(frames.size in 2..MAX_FRAMES) { "Selecione entre 2 e 18 quadros" }
        require(delayMs in 80..1000) { "Duração inválida" }
        require(text.length <= 40) { "Texto excede o limite" }
        require(list().size < MAX_LIBRARY) { "Acervo de GIFs cheio" }
        val encoded = Gif332Encoder.encode(frames, delayMs, text, MAX_GIF_BYTES)
        val target = File(directory, "gif_${UUID.randomUUID()}.gif")
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(encoded)
                stream.fd.sync()
            }
            check(temporary.renameTo(target)) { "Falha ao salvar GIF" }
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun loadPictures(uris: List<Uri>): List<Bitmap> =
        uris.take(MAX_FRAMES).mapNotNull { uri -> decodeSampled(uri) }

    fun loadGif(uri: Uri): List<Bitmap> {
        val bytes = readBounded(uri, MAX_GIF_BYTES) ?: return emptyList()
        if (!isGif(bytes)) return emptyList()
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        if (movie.width() !in 1..2048 || movie.height() !in 1..2048) return emptyList()
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        val count = (duration / 120).coerceIn(2, MAX_FRAMES)
        return (0 until count).map { index ->
            val bitmap = Bitmap.createBitmap(FRAME_SIZE, FRAME_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val scale = minOf(FRAME_SIZE.toFloat() / movie.width(), FRAME_SIZE.toFloat() / movie.height())
            canvas.save()
            canvas.translate((FRAME_SIZE - movie.width() * scale) / 2f, (FRAME_SIZE - movie.height() * scale) / 2f)
            canvas.scale(scale, scale)
            movie.setTime(index * duration / count)
            movie.draw(canvas, 0f, 0f)
            canvas.restore()
            bitmap
        }
    }

    fun loadVideo(uri: Uri): List<Bitmap> {
        val resolver = context.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > 40L * 1024 * 1024) return emptyList()
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return emptyList()
            if (duration !in 200..15_000) return emptyList()
            (0 until 12).mapNotNull { i ->
                retriever.getFrameAtTime(i * duration * 1000L / 12L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { normalize(it) }
            }
        } catch (_: Exception) { emptyList() }
        finally { retriever.release() }
    }

    private fun decodeSampled(uri: Uri): Bitmap? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth !in 1..8192 || options.outHeight !in 1..8192) return null
        val sample = generateSequence(1) { it * 2 }
            .first { options.outWidth / it <= 480 && options.outHeight / it <= 480 }
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?.let { normalize(it) }
    } catch (_: Exception) { null }

    private fun normalize(source: Bitmap): Bitmap {
        val target = Bitmap.createBitmap(FRAME_SIZE, FRAME_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        val s = minOf(FRAME_SIZE.toFloat() / source.width, FRAME_SIZE.toFloat() / source.height)
        val w = source.width * s
        val h = source.height * s
        canvas.drawBitmap(source, null,
            android.graphics.RectF((FRAME_SIZE-w)/2, (FRAME_SIZE-h)/2, (FRAME_SIZE+w)/2, (FRAME_SIZE+h)/2),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return target
    }

    private fun readBounded(uri: Uri, limit: Int): ByteArray? = try {
        val out = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) return null
                out.write(buffer, 0, n)
            }
        } ?: return null
        out.toByteArray()
    } catch (_: Exception) { null }

    private fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 14 && (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a" ||
            String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a") &&
            bytes[bytes.lastIndex] == 0x3b.toByte()
}

/** Encoder GIF89a local: paleta RGB332 fixa, LZW de 8 bits e loop infinito. */
internal object Gif332Encoder {
    fun encode(frames: List<Bitmap>, delayMs: Int, caption: String, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun byte(v: Int) { out.write(v and 255); check(out.size() <= maxBytes) { "GIF excedeu 8 MB" } }
        fun short(v: Int) { byte(v); byte(v shr 8) }
        "GIF89a".toByteArray(Charsets.US_ASCII).forEach { byte(it.toInt()) }
        short(240); short(240); byte(0xF7); byte(0); byte(0)
        for (i in 0..255) {
            byte((i shr 5) * 255 / 7)
            byte(((i shr 2) and 7) * 255 / 7)
            byte((i and 3) * 255 / 3)
        }
        // Netscape looping extension
        byte(0x21); byte(0xFF); byte(11)
        "NETSCAPE2.0".toByteArray(Charsets.US_ASCII).forEach { byte(it.toInt()) }
        byte(3); byte(1); short(0); byte(0)
        for (source in frames) {
            val scaled = Bitmap.createScaledBitmap(source, 240, 240, true)
            val canvasBitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            if (caption.isNotBlank()) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 21f
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(3f, 1f, 1f, Color.BLACK)
                }
                canvas.drawText(caption, 120f, 222f, paint)
            }
            val pixels = IntArray(240 * 240)
            canvasBitmap.getPixels(pixels, 0, 240, 0, 0, 240, 240)
            val indices = ByteArray(pixels.size) { i ->
                val color = pixels[i]
                (((Color.red(color) shr 5) shl 5) or
                    ((Color.green(color) shr 5) shl 2) or (Color.blue(color) shr 6)).toByte()
            }
            // Graphic Control Extension
            byte(0x21); byte(0xF9); byte(4); byte(0); short((delayMs / 10).coerceAtLeast(2)); byte(0); byte(0)
            byte(0x2C); short(0); short(0); short(240); short(240); byte(0)
            byte(8)
            val compressed = lzw(indices)
            for (part in compressed.asList().chunked(255)) {
                byte(part.size); part.forEach { byte(it.toInt()) }
            }
            byte(0)
            if (scaled !== source) scaled.recycle()
            canvasBitmap.recycle()
        }
        byte(0x3B)
        return out.toByteArray()
    }

    private fun lzw(data: ByteArray): ByteArray {
        val clear = 256
        val end = 257
        val output = ByteArrayOutputStream()
        var bitBuffer = 0L
        var bits = 0
        var codeSize = 9
        var nextCode = 258
        val table = HashMap<Int, Int>(4096)
        fun write(code: Int) {
            bitBuffer = bitBuffer or (code.toLong() shl bits)
            bits += codeSize
            while (bits >= 8) {
                output.write((bitBuffer and 255).toInt())
                bitBuffer = bitBuffer ushr 8
                bits -= 8
            }
        }
        write(clear)
        var prefix = data[0].toInt() and 255
        for (i in 1 until data.size) {
            val symbol = data[i].toInt() and 255
            val key = (prefix shl 8) or symbol
            val existing = table[key]
            if (existing != null) {
                prefix = existing
            } else {
                write(prefix)
                if (nextCode < 4096) {
                    table[key] = nextCode++
                    if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    write(clear)
                    table.clear()
                    codeSize = 9
                    nextCode = 258
                }
                prefix = symbol
            }
        }
        write(prefix)
        write(end)
        if (bits > 0) output.write((bitBuffer and 255).toInt())
        return output.toByteArray()
    }
}
