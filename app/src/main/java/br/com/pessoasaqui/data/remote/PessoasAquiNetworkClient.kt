package br.com.pessoasaqui.data.remote

import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    var baseUrl: String = "https://pessoasaqui.onrender.com",
    var wsUrl: String = "wss://pessoasaqui.onrender.com"
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var activeWebSocket: WebSocket? = null
    private var isWsConnected = false
    val isWebSocketConnected: Boolean get() = isWsConnected

    private var shouldReconnect = true
    private var lastIdentityHash: String? = null
    private var lastAlias: String? = null
    private var lastIntent: String? = null
    private var lastOnE2ee: ((String, String, String) -> Unit)? = null
    private var lastOnRevoked: ((String) -> Unit)? = null
    private var lastOnPresenceSync: ((List<RemotePeer>) -> Unit)? = null
    private var lastOnPeerOnline: ((RemotePeer) -> Unit)? = null
    private var lastOnPeerOffline: ((String) -> Unit)? = null

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        CoroutineScope(Dispatchers.IO).launch {
            delay(4000)
            if (!isWsConnected && shouldReconnect) {
                val id = lastIdentityHash ?: return@launch
                startRealtimeSocket(
                    myIdentityHash = id,
                    myAlias = lastAlias ?: "Eu",
                    myIntent = lastIntent ?: "QUERO_CONVERSAR",
                    onE2eeMessageReceived = lastOnE2ee ?: { _, _, _ -> },
                    onSessionRevoked = lastOnRevoked ?: {},
                    onPresenceSync = lastOnPresenceSync,
                    onPeerOnline = lastOnPeerOnline,
                    onPeerOffline = lastOnPeerOffline
                )
            }
        }
    }

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
     * Inicia conexão WebSocket em tempo real para Relay E2EE, Presença e Notificações de Revogação
     */
    fun startRealtimeSocket(
        myIdentityHash: String,
        myAlias: String = "Eu",
        myIntent: String = "QUERO_CONVERSAR",
        onE2eeMessageReceived: (senderHash: String, payload: String, iv: String) -> Unit,
        onSessionRevoked: (notice: String) -> Unit,
        onPresenceSync: ((List<RemotePeer>) -> Unit)? = null,
        onPeerOnline: ((RemotePeer) -> Unit)? = null,
        onPeerOffline: ((String) -> Unit)? = null
    ) {
        disconnectSocket()

        shouldReconnect = true
        lastIdentityHash = myIdentityHash
        lastAlias = myAlias
        lastIntent = myIntent
        lastOnE2ee = onE2eeMessageReceived
        lastOnRevoked = onSessionRevoked
        lastOnPresenceSync = onPresenceSync
        lastOnPeerOnline = onPeerOnline
        lastOnPeerOffline = onPeerOffline

        val request = Request.Builder().url(wsUrl).build()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isWsConnected = true
                // Autentica este dispositivo no socket com sua identidade, alias e intenção
                val authMsg = JSONObject().apply {
                    put("type", "AUTH")
                    put("identityHash", myIdentityHash)
                    put("alias", myAlias)
                    put("intent", myIntent)
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
                        "PRESENCE_SYNC" -> {
                            val peersArray = obj.optJSONArray("peers")
                            val peersList = mutableListOf<RemotePeer>()
                            if (peersArray != null) {
                                for (i in 0 until peersArray.length()) {
                                    val p = peersArray.getJSONObject(i)
                                    peersList.add(
                                        RemotePeer(
                                            identityHash = p.getString("identityHash"),
                                            alias = p.optString("alias", "Pessoa Próxima"),
                                            intent = p.optString("intent", "QUERO_CONVERSAR")
                                        )
                                    )
                                }
                            }
                            onPresenceSync?.invoke(peersList)
                        }
                        "PEER_ONLINE" -> {
                            val p = obj.optJSONObject("peer")
                            if (p != null) {
                                val peer = RemotePeer(
                                    identityHash = p.getString("identityHash"),
                                    alias = p.optString("alias", "Pessoa Próxima"),
                                    intent = p.optString("intent", "QUERO_CONVERSAR")
                                )
                                onPeerOnline?.invoke(peer)
                            }
                        }
                        "PEER_OFFLINE" -> {
                            val offHash = obj.optString("identityHash")
                            if (offHash.isNotBlank()) {
                                onPeerOffline?.invoke(offHash)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWsConnected = false
                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWsConnected = false
                scheduleReconnect()
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

    /**
     * Envia heartbeat de presença HTTP para o backend
     */
    suspend fun sendPresenceHeartbeat(identityHash: String, alias: String, intent: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("identityHash", identityHash)
                put("alias", alias)
                put("intent", intent)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/presence/heartbeat")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Busca usuários ativos recentemente no radar via API REST
     */
    suspend fun fetchNearbyPresence(myIdentity: String): Result<List<RemotePeer>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/presence/nearby?myIdentity=$myIdentity")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val obj = JSONObject(bodyStr)
                val peersArray = obj.optJSONArray("people")
                val peersList = mutableListOf<RemotePeer>()
                if (peersArray != null) {
                    for (i in 0 until peersArray.length()) {
                        val p = peersArray.getJSONObject(i)
                        peersList.add(
                            RemotePeer(
                                identityHash = p.getString("identityHash"),
                                alias = p.optString("alias", "Pessoa Próxima"),
                                intent = p.optString("intent", "QUERO_CONVERSAR")
                            )
                        )
                    }
                }
                Result.success(peersList)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Envia mensagem via REST HTTP (Fallback redundante para WebSockets)
     */
    suspend fun sendHttpMessage(
        senderHash: String,
        recipientHash: String,
        ciphertext: String,
        senderAlias: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("senderHash", senderHash)
                put("recipientHash", recipientHash)
                put("ciphertextPayload", ciphertext)
                put("senderAlias", senderAlias)
                put("ivNonce", java.util.UUID.randomUUID().toString().take(12))
            }
            val request = Request.Builder()
                .url("$baseUrl/api/messages/send")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Busca mensagens pendentes na fila do servidor
     */
    suspend fun fetchPendingMessages(identityHash: String): Result<List<PendingMessage>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/messages/pending?identityHash=$identityHash")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val obj = JSONObject(bodyStr)
                val msgsArray = obj.optJSONArray("messages")
                val list = mutableListOf<PendingMessage>()
                if (msgsArray != null) {
                    for (i in 0 until msgsArray.length()) {
                        val m = msgsArray.getJSONObject(i)
                        list.add(
                            PendingMessage(
                                senderHash = m.getString("senderHash"),
                                senderAlias = m.optString("senderAlias", "Usuário"),
                                ciphertext = m.getString("ciphertextPayload"),
                                ivNonce = m.optString("ivNonce", "")
                            )
                        )
                    }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun disconnectSocket() {
        try {
            activeWebSocket?.close(1000, "App closed")
        } catch (_: Exception) {}
        activeWebSocket = null
        isWsConnected = false
    }
}

data class RemotePeer(
    val identityHash: String,
    val alias: String,
    val intent: String
)

data class PendingMessage(
    val senderHash: String,
    val senderAlias: String,
    val ciphertext: String,
    val ivNonce: String
)
