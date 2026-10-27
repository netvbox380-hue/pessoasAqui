package br.com.pessoasaqui.core.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Utilitário responsável pela Pesquisa Visual de Imagens na Internet (Reverse Image Search),
 * integrando com Google Lens, navegadores ou aplicativos compatíveis, sem IA interna ou servidores próprios.
 */
object ImageSearchHelper {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Prepara a imagem em arquivo JPEG seguro no cache temporário.
     */
    fun saveTempSearchImage(context: Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "search_images").apply { if (!exists()) mkdirs() }
        val file = File(dir, "search_${System.currentTimeMillis()}.jpg")

        // Redimensiona proporcionalmente para no máximo 1440px para otimização de upload
        val maxDim = 1440
        val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val (newW, newH) = if (ratio > 1f) {
                maxDim to (maxDim / ratio).toInt()
            } else {
                (maxDim * ratio).toInt() to maxDim
            }
            Bitmap.createScaledBitmap(bitmap, newW.coerceAtLeast(100), newH.coerceAtLeast(100), true)
        } else {
            bitmap
        }

        FileOutputStream(file).use { out ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file
    }

    /**
     * Executa a pesquisa visual completa:
     * 1. Tenta integração direta com Google Lens / Google App nativo se instalado.
     * 2. Se não houver app nativo, realiza o upload direto via multipart para o Google Lens Web
     *    e abre a página de resultados no navegador.
     * 3. Caso o upload falhe, oferece compartilhamento com aplicativos de busca visual instalados.
     */
    suspend fun performReverseImageSearch(
        context: Context,
        bitmap: Bitmap,
        onProgressUpdate: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = saveTempSearchImage(context, bitmap)
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            // 1. Tenta abrir diretamente com o aplicativo oficial Google Lens
            val lensIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.google.ar.lens")
                setDataAndType(contentUri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (lensIntent.resolveActivity(context.packageManager) != null) {
                withContext(Dispatchers.Main) {
                    context.startActivity(lensIntent)
                }
                return@withContext Result.success("Pesquisa iniciada no Google Lens nativo.")
            }

            // 2. Tenta envio direto para o Google App (GSA Lens)
            val gsaLensIntent = Intent(Intent.ACTION_SEND).apply {
                setPackage("com.google.android.googlequicksearchbox")
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (gsaLensIntent.resolveActivity(context.packageManager) != null) {
                withContext(Dispatchers.Main) {
                    context.startActivity(gsaLensIntent)
                }
                return@withContext Result.success("Pesquisa iniciada no aplicativo Google.")
            }

            // 3. Upload real para o serviço de pesquisa visual do Google Lens Web
            onProgressUpdate("Enviando imagem para pesquisa visual no Google...")

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            val imageBytes = stream.toByteArray()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "encoded_image",
                    "search_image.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("https://lens.google.com/upload")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .post(requestBody)
                .build()

            var resultsUrl: String? = null

            try {
                httpClient.newCall(request).execute().use { response ->
                    val locationHeader = response.header("Location")
                    if (!locationHeader.isNullOrBlank()) {
                        resultsUrl = if (locationHeader.startsWith("/")) {
                            "https://lens.google.com$locationHeader"
                        } else {
                            locationHeader
                        }
                    } else if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        // Procura URL de redirecionamento ou resultado no corpo
                        val regex = Regex("""https://lens\.google\.com/search\?[^"'\s<]+""")
                        val match = regex.find(bodyStr)
                        if (match != null) {
                            resultsUrl = match.value
                        }
                    }
                }
            } catch (e: Exception) {
                // Falha de rede ou timeout
            }

            // Se obteve a URL de resultados com a imagem processada, abre no navegador
            if (!resultsUrl.isNullOrBlank()) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(resultsUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(browserIntent)
                }
                return@withContext Result.success("Resultados da pesquisa visual abertos no navegador.")
            }

            // 4. Fallback inteligente: Seletor nativo do Android para apps compatíveis com imagem
            val shareSearchIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareSearchIntent, "Pesquisar imagem em...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            withContext(Dispatchers.Main) {
                try {
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    // Se nada mais estiver disponível, abre o site do Google Imagens
                    val fallbackWeb = Intent(Intent.ACTION_VIEW, Uri.parse("https://images.google.com")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackWeb)
                }
            }
            Result.success("Seletor de aplicativos aberto.")

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
