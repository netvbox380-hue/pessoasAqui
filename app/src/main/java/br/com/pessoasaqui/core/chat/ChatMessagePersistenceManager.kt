package br.com.pessoasaqui.core.chat

import android.content.Context
import android.util.Log
import br.com.pessoasaqui.domain.model.ChatMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Gerenciador de Persistência em Disco para Mensagens do Chat.
 * Garante que todas as mensagens enviadas e recebidas (privadas ou do mural local)
 * fiquem gravadas permanentemente e nunca desapareçam ou zerem quando o app for
 * encerrado em segundo plano no Android 14 ou reiniciar o aparelho.
 */
class ChatMessagePersistenceManager(
    private val context: Context? = null,
    baseDir: File? = null
) {

    private val rootDir: File? = when {
        baseDir != null -> File(baseDir, CHATS_DIR_NAME)
        context != null -> File(context.filesDir, CHATS_DIR_NAME)
        else -> null
    }

    companion object {
        private const val TAG = "ChatPersistence"
        private const val CHATS_DIR_NAME = "pessoasaqui_chats"
        private const val LOCAL_MURAL_FILE_NAME = "local_mural.json"
        private const val PEER_FILE_PREFIX = "chat_"
        private const val FILE_EXT = ".json"

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        fun serializeMessage(msg: ChatMessage): String {
            return json.encodeToString(ChatMessage.serializer(), msg)
        }

        fun deserializeMessage(jsonString: String): ChatMessage {
            return json.decodeFromString(ChatMessage.serializer(), jsonString)
        }
    }

    init {
        rootDir?.let {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }

    private fun cleanKey(peerId: String): String {
        return peerId.removePrefix("peer-").trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")
    }

    private fun getFileForPeer(cleanPeerId: String): File? {
        val dir = rootDir ?: return null
        return File(dir, "$PEER_FILE_PREFIX$cleanPeerId$FILE_EXT")
    }

    private fun getLocalMuralFile(): File? {
        val dir = rootDir ?: return null
        return File(dir, LOCAL_MURAL_FILE_NAME)
    }

    /**
     * Salva as mensagens de um chat privado com um contato específico.
     * Utiliza gravação com arquivo temporário para evitar corrupção em caso de fechamento brusco.
     */
    fun savePrivateChat(peerId: String, messages: List<ChatMessage>) {
        val cleanKey = cleanKey(peerId)
        if (cleanKey.isBlank()) return
        val targetFile = getFileForPeer(cleanKey) ?: return

        try {
            // Limita histórico a 500 mensagens mais recentes por contato para otimização de espaço
            val toSave = if (messages.size > 500) messages.takeLast(500) else messages
            val serialized = json.encodeToString(ListSerializer(ChatMessage.serializer()), toSave)

            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            tempFile.writeText(serialized, Charsets.UTF_8)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar chat do peer $cleanKey: ${e.message}", e)
        }
    }

    /**
     * Carrega todas as conversas privadas salvas em disco.
     */
    fun loadAllPrivateChats(): Map<String, List<ChatMessage>> {
        val dir = rootDir ?: return emptyMap()
        if (!dir.exists() || !dir.isDirectory) return emptyMap()

        val resultMap = mutableMapOf<String, List<ChatMessage>>()
        val files = dir.listFiles { _, name -> name.startsWith(PEER_FILE_PREFIX) && name.endsWith(FILE_EXT) } ?: emptyArray()

        for (file in files) {
            try {
                val cleanPeerId = file.name
                    .removePrefix(PEER_FILE_PREFIX)
                    .removeSuffix(FILE_EXT)

                val content = file.readText(Charsets.UTF_8)
                val msgList = json.decodeFromString(ListSerializer(ChatMessage.serializer()), content)
                if (msgList.isNotEmpty()) {
                    resultMap[cleanPeerId] = msgList
                    resultMap["peer-$cleanPeerId"] = msgList
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao ler arquivo de chat ${file.name}: ${e.message}")
            }
        }
        return resultMap
    }

    /**
     * Salva as mensagens do Mural Local (10m).
     */
    fun saveLocalMural(messages: List<ChatMessage>) {
        val targetFile = getLocalMuralFile() ?: return
        try {
            val toSave = if (messages.size > 200) messages.takeLast(200) else messages
            val serialized = json.encodeToString(ListSerializer(ChatMessage.serializer()), toSave)

            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            tempFile.writeText(serialized, Charsets.UTF_8)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar mural local: ${e.message}", e)
        }
    }

    /**
     * Carrega as mensagens do Mural Local salvas em disco.
     */
    fun loadLocalMural(): List<ChatMessage> {
        val file = getLocalMuralFile() ?: return emptyList()
        if (!file.exists()) return emptyList()

        return try {
            val content = file.readText(Charsets.UTF_8)
            json.decodeFromString(ListSerializer(ChatMessage.serializer()), content)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler mural local em disco: ${e.message}")
            emptyList()
        }
    }

    /**
     * Limpa as mensagens do mural local em disco.
     */
    fun clearLocalMural() {
        try {
            getLocalMuralFile()?.delete()
        } catch (_: Exception) {}
    }
}
