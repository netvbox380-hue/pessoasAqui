package br.com.pessoasaqui.core.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Gerenciador de persistência local para Figurinhas (Stickers) criadas ou favoritadas pelo usuário.
 * Armazena as figurinhas em armazenamento privado (filesDir/user_stickers/) e permite rápido acesso
 * para envio no chat e reutilização em qualquer conversa.
 */
class StickerPersistenceManager(private val context: Context) {

    private val stickersDir: File by lazy {
        File(context.filesDir, "user_stickers").apply { if (!exists()) mkdirs() }
    }

    private val prefs = context.getSharedPreferences("pessoasaqui_stickers_v1", Context.MODE_PRIVATE)

    /**
     * Salva uma nova figurinha no acervo permanente e retorna seu ID / hash.
     */
    fun saveSticker(bitmap: Bitmap): String {
        val id = "sticker_${System.currentTimeMillis()}"
        val file = File(stickersDir, "$id.png")

        // Garante formato 512x512 padrão com canal alfa para transparência
        val targetSize = 512
        val scaled = if (bitmap.width != targetSize || bitmap.height != targetSize) {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        } else {
            bitmap
        }

        FileOutputStream(file).use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        // Atualiza a lista ordenada de stickers nas preferências
        val currentList = getStickerIds().toMutableList()
        currentList.add(0, id)
        prefs.edit().putString("sticker_ids", currentList.joinToString(",")).apply()

        return id
    }

    /**
     * Retorna a lista de IDs de figurinhas salvas.
     */
    fun getStickerIds(): List<String> {
        val raw = prefs.getString("sticker_ids", "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(",").filter { it.isNotBlank() }
    }

    /**
     * Carrega todos os bitmaps de figurinhas salvas.
     */
    fun loadAllStickers(): List<Pair<String, Bitmap>> {
        val ids = getStickerIds()
        val list = mutableListOf<Pair<String, Bitmap>>()
        ids.forEach { id ->
            val file = File(stickersDir, "$id.png")
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    list.add(id to bmp)
                }
            }
        }
        return list
    }

    /**
     * Converte o Bitmap de uma figurinha em Base64 para envio na mensagem.
     */
    fun bitmapToStickerPayload(bitmap: Bitmap): String {
        val targetSize = 512
        val scaled = if (bitmap.width != targetSize || bitmap.height != targetSize) {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        } else {
            bitmap
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "[STICKER:$b64]"
    }

    /**
     * Remove uma figurinha do acervo.
     */
    fun deleteSticker(id: String) {
        val file = File(stickersDir, "$id.png")
        if (file.exists()) file.delete()
        val currentList = getStickerIds().toMutableList()
        currentList.remove(id)
        prefs.edit().putString("sticker_ids", currentList.joinToString(",")).apply()
    }
}
