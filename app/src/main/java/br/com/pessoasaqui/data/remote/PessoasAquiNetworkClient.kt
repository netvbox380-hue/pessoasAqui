package br.com.pessoasaqui.data.remote

import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente de Rede HTTP e WebSockets do PessoasAqui.
 * Conecta o aplicativo ao servidor backend (seja local ou hospedado no Render / Supabase).
 */
class PessoasAquiNetworkClient(
    var baseUrl: String = "http://10.0.2.2:3000",
    var wsUrl: String = "ws://10.0.2.2:3000"
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var activeWebSocket: WebSocket? = null
    private var isWsConnected = false

    /**
     * Registra a identidade técnica criptográfica no servidor
     */
    suspend fun registerIdentity(
        identityHash: String,
        publicKeyEd25519: String,
        publicKeyX25519: String,
        pin: String,
        alias: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("identityHash", identityHash)
                put("publicKeyEd25519", publicKeyEd25519)
                put("publicKeyX25519", publicKeyX25519)
                put("pin", pin)
                put("alias", alias)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/identities/register")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Erro no registro: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Marca uma pessoa e descobre se houve conexão mútua (à distância)
     */
    suspend fun markConnection(
        fromIdentity: String,
        toIdentity: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("fromIdentity", fromIdentity)
                put("toIdentity", toIdentity)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/connections/mark")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val resObj = JSONObject(bodyStr)
                if (response.isSuccessful) {
                    Result.success(resObj.optBoolean("isMutualConnection", false))
                } else {
                    Result.failure(IOException(resObj.optString("error", "Erro ao marcar")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Emite autorização de recuperação de identidade por um familiar
     */
    suspend fun issueFamilyRecovery(
        targetIdentity: String,
        familyIdentity: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("targetIdentity", targetIdentity)
                put("familyIdentity", familyIdentity)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/family/recovery/issue")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val resObj = JSONObject(bodyStr)
                if (response.isSuccessful) {
                    Result.success(resObj.getString("recoveryKey"))
                } else {
                    Result.failure(IOException(resObj.optString("error", "Erro ao emitir autorização")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reivindica a identidade no novo aparelho ("Entre na sua")
     */
    suspend fun claimFamilyRecovery(
        recoveryKey: String,
        pin: String,
        newDeviceFingerprint: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("recoveryKey", recoveryKey)
                put("pin", pin)
                put("newDeviceFingerprint", newDeviceFingerprint)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/family/recovery/claim")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val resObj = JSONObject(bodyStr)
                if (response.isSuccessful) {
                    Result.success(resObj.getString("targetIdentity"))
                } else {
                    val errMsg = resObj.optString("error", "Erro ao recuperar identidade")
                    val lockoutSec = resObj.optInt("remainingSeconds", resObj.optInt("lockoutSeconds", 0))
                    val fullErr = if (lockoutSec > 0) "$errMsg (Bloqueio de $lockoutSec s)" else errMsg
                    Result.failure(IOException(fullErr))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Inicia conexão WebSocket em tempo real para Relay E2EE e Notificações de Revogação de Sessão
     */
    fun startRealtimeSocket(
        myIdentityHash: String,
        onE2eeMessageReceived: (senderHash: String, payload: String, iv: String) -> Unit,
        onSessionRevoked: (notice: String) -> Unit
    ) {
        disconnectSocket()

        val request = Request.Builder().url(wsUrl).build()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isWsConnected = true
                // Autentica este dispositivo no socket com sua identidade
                val authMsg = JSONObject().apply {
                    put("type", "AUTH")
                    put("identityHash", myIdentityHash)
                }
                webSocket.send(authMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "E2EE_MESSAGE_RECEIVED" -> {
                            val sender = obj.getString("senderHash")
                            val payload = obj.getString("ciphertextPayload")
                            val iv = obj.getString("ivNonce")
                            onE2eeMessageReceived(sender, payload, iv)
                        }
                        "SESSION_REVOKED" -> {
                            val msg = obj.optString("message", "Sessão revogada: sua identidade foi resgatada em outro aparelho.")
                            onSessionRevoked(msg)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWsConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWsConnected = false
            }
        })
    }

    /**
     * Envia envelope cifrado E2EE pelo WebSocket
     */
    fun sendE2eeEnvelope(recipientHash: String, ciphertext: String, ivNonce: String): Boolean {
        val ws = activeWebSocket ?: return false
        val envelope = JSONObject().apply {
            put("type", "E2EE_MESSAGE")
            put("recipientHash", recipientHash)
            put("ciphertextPayload", ciphertext)
            put("ivNonce", ivNonce)
        }
        return ws.send(envelope.toString())
    }

    fun disconnectSocket() {
        try {
            activeWebSocket?.close(1000, "App closed")
        } catch (_: Exception) {}
        activeWebSocket = null
        isWsConnected = false
    }
}
