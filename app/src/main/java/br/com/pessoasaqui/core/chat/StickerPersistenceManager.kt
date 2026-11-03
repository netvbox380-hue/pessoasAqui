package br.com.pessoasaqui.core.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Acervo privado de figurinhas. Arquivos pertencem apenas ao usuário local;
 * editar ou excluir uma figurinha nunca altera mensagens já enviadas.
 *
 * Compatibilidade: mantém user_stickers/ e pessoasaqui_stickers_v1/sticker_ids.
 * IDs antigos sticker_<timestamp> continuam válidos.
 */
class StickerPersistenceManager(private val context: Context) {
    companion object {
        const val STICKER_SIZE_PX = 512
        const val MAX_STICKERS = 180
        private const val MAX_STORED_BYTES = 8L * 1024 * 1024
        private val SAFE_ID = Regex("^sticker_[A-Za-z0-9_-]{1,80}$")
    }

    private val stickersDir: File by lazy {
        File(context.filesDir, "user_stickers").apply { if (!exists()) mkdirs() }
    }
    private val prefs = context.getSharedPreferences("pessoasaqui_stickers_v1", Context.MODE_PRIVATE)

    private fun stickerFile(id: String): File {
        require(SAFE_ID.matches(id)) { "Identificador de figurinha inválido" }
        return File(stickersDir, "$id.png")
    }

    private fun normalize(bitmap: Bitmap): Bitmap {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "Imagem inválida"
        }
        if (bitmap.width == STICKER_SIZE_PX && bitmap.height == STICKER_SIZE_PX) return bitmap
        val target = Bitmap.createBitmap(STICKER_SIZE_PX, STICKER_SIZE_PX, Bitmap.Config.ARGB_8888)
        val scale = minOf(STICKER_SIZE_PX.toFloat() / bitmap.width, STICKER_SIZE_PX.toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        Canvas(target).drawBitmap(
            bitmap, null,
            android.graphics.RectF(
                (STICKER_SIZE_PX - width) / 2f, (STICKER_SIZE_PX - height) / 2f,
                (STICKER_SIZE_PX + width) / 2f, (STICKER_SIZE_PX + height) / 2f
            ),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return target
    }

    private fun toPng(bitmap: Bitmap): ByteArray {
        val normalized = normalize(bitmap)
        return ByteArrayOutputStream().use { stream ->
            check(normalized.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Não foi possível codificar a figurinha"
            }
            val bytes = stream.toByteArray()
            check(bytes.isNotEmpty() && bytes.size <= MAX_STORED_BYTES) {
                "Arquivo de figurinha fora do limite seguro"
            }
            bytes
        }
    }

    /** Troca atômica com backup; em falha, preserva a versão anterior. */
    private fun writeSticker(id: String, bytes: ByteArray): Boolean {
        val target = stickerFile(id)
        val temp = File(stickersDir, "$id.tmp")
        val backup = File(stickersDir, "$id.bak")
        return try {
            if (temp.exists()) temp.delete()
            temp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (backup.exists()) backup.delete()
            if (target.exists() && !target.renameTo(backup)) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                temp.delete()
                return false
            }
            if (backup.exists()) backup.delete()
            true
        } catch (_: Exception) {
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            temp.delete()
            false
        }
    }

    @Synchronized
    fun saveSticker(bitmap: Bitmap): String {
        val ids = getStickerIds()
        require(ids.size < MAX_STICKERS) { "Acervo cheio. Exclua figurinhas que não usa." }
        val id = "sticker_${UUID.randomUUID()}"
        check(writeSticker(id, toPng(bitmap))) { "Falha ao salvar figurinha" }
        val updated = listOf(id) + ids
        if (!prefs.edit().putString("sticker_ids", updated.joinToString(",")).commit()) {
            stickerFile(id).delete()
            error("Falha ao registrar figurinha")
        }
        return id
    }

    /**
     * Substitui somente a figurinha local com o mesmo ID. Mensagens enviadas
     * já contêm payload independente e não devem ser reescritas.
     */
    @Synchronized
    fun updateSticker(id: String, bitmap: Bitmap): Boolean =
        SAFE_ID.matches(id) && getStickerIds().contains(id) &&
            stickerFile(id).exists() && writeSticker(id, toPng(bitmap))

    fun getStickerIds(): List<String> {
        val raw = prefs.getString("sticker_ids", "") ?: ""
        return raw.split(',').filter { SAFE_ID.matches(it) }.distinct()
    }

    fun loadAllStickers(): List<Pair<String, Bitmap>> = getStickerIds()
        .take(MAX_STICKERS)
        .mapNotNull { id ->
            try {
                val file = stickerFile(id)
                if (!file.isFile || file.length() <= 0L || file.length() > MAX_STORED_BYTES) return@mapNotNull null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@mapNotNull null
                val sampleSize = maxOf(1, (maxOf(bounds.outWidth, bounds.outHeight) / STICKER_SIZE_PX))
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeFile(file.absolutePath, options)?.let { id to it }
            } catch (_: Exception) { null }
        }

    fun bitmapToStickerPayload(bitmap: Bitmap): String =
        "[STICKER:${Base64.encodeToString(toPng(bitmap), Base64.NO_WRAP)}]"

    @Synchronized
    fun deleteSticker(id: String): Boolean {
        if (!SAFE_ID.matches(id) || id !in getStickerIds()) return false
        val target = stickerFile(id)
        val previous = getStickerIds()
        val next = previous.filterNot { it == id }
        // Só remover o índice após exclusão confirmada, evitando IDs órfãos.
        if (target.exists() && !target.delete()) return false
        return prefs.edit().putString("sticker_ids", next.joinToString(",")).commit()
    }
}
