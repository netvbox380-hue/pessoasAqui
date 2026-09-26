package br.com.pessoasaqui.core.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Utilitário seguro para abrir documentos (PDF, XML, DOC, XLSX, TXT, etc.) recebidos
 * ou enviados pelo chat através do Android FileProvider e aplicativos nativos.
 */
object DocumentViewerHelper {

    private const val TAG = "DocumentViewerHelper"
    private const val SHARED_DOCS_DIR = "shared_docs"

    /**
     * Identifica o MIME type adequado baseado na extensão do arquivo.
     */
    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "pdf" -> "application/pdf"
            "xml" -> "text/xml"
            "txt", "log" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp3", "m4a", "wav" -> "audio/*"
            "mp4", "mkv", "webm" -> "video/*"
            else -> "*/*"
        }
    }

    /**
     * Grava os bytes do arquivo em Base64 no diretório seguro de cache compartilhado.
     */
    fun saveBase64ToFile(context: Context, fileName: String, base64Data: String): File? {
        return try {
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "documento.pdf" }
            val docsDir = File(context.cacheDir, SHARED_DOCS_DIR)
            if (!docsDir.exists()) {
                docsDir.mkdirs()
            }
            val targetFile = File(docsDir, safeName)

            // Remove prefixos comuns de data URL se existirem (ex: data:application/pdf;base64,)
            val cleanBase64 = if (base64Data.contains(",")) {
                base64Data.substringAfter(",")
            } else {
                base64Data
            }

            val decodedBytes = Base64.decode(cleanBase64.trim(), Base64.DEFAULT)
            FileOutputStream(targetFile).use { fos ->
                fos.write(decodedBytes)
                fos.flush()
            }
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao gravar arquivo em cache: ${e.message}", e)
            null
        }
    }

    /**
     * Abre o documento utilizando o aplicativo padrão do Android ou seletor do sistema.
     */
    fun openDocument(context: Context, fileName: String, base64Data: String?): Boolean {
        if (base64Data.isNullOrBlank()) {
            Toast.makeText(context, "Conteúdo do documento não encontrado.", Toast.LENGTH_SHORT).show()
            return false
        }

        try {
            val file = saveBase64ToFile(context, fileName, base64Data)
            if (file == null || !file.exists() || file.length() == 0L) {
                Toast.makeText(context, "Não foi possível preparar o arquivo para visualização.", Toast.LENGTH_SHORT).show()
                return false
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)
            val mimeType = getMimeType(fileName)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Abrir $fileName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooser)
            return true
        } catch (e: ActivityNotFoundException) {
            val ext = fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
            val msg = if (ext.isNotBlank()) {
                "Nenhum leitor instalado para abrir arquivos .$ext. Instale um aplicativo correspondente."
            } else {
                "Nenhum aplicativo compatível encontrado para abrir este arquivo."
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir documento: ${e.message}", e)
            Toast.makeText(context, "Erro ao abrir: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }
}
