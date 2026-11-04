package br.com.pessoasaqui.core.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/**
 * Decodifica apenas imagens de mensagens dentro de limites seguros.
 * O conteúdo pode ser controlado pelo remetente: nunca confiar em dimensões
 * ou Base64 externos, mesmo dentro de uma conexão mútua autenticada.
 */
object SafeChatImageDecoder {
    const val MAX_BASE64_CHARS = 24 * 1024 * 1024
    const val MAX_PIXEL_DIMENSION = 8192
    const val MAX_RENDER_DIMENSION = 2048

    fun decode(base64: String?): Bitmap? {
        if (base64.isNullOrBlank() || base64.length > MAX_BASE64_CHARS) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isEmpty() || bytes.size > (MAX_BASE64_CHARS * 3 / 4)) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width !in 1..MAX_PIXEL_DIMENSION ||
                height !in 1..MAX_PIXEL_DIMENSION) return null
            var sample = 1
            while (maxOf(width, height) / sample > MAX_RENDER_DIMENSION) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
