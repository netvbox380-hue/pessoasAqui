package br.com.pessoasaqui.data.remote

import br.com.pessoasaqui.domain.model.OfferItem
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
    private var lastOnE2ee: ((String, String, String, String) -> Unit)? = null
    private var lastOnRevoked: ((String) -> Unit)? = null
    private var lastOnPresenceSync: ((List<RemotePeer>) -> Unit)? = null
    private var lastOnPeerOnline: ((RemotePeer) -> Unit)? = null
    private var lastOnPeerOffline: ((String) -> Unit)? = null
    private var lastOnMutualConnection: ((String, String) -> Unit)? = null
    private var lastOnPeerMarkedYou: ((String, String) -> Unit)? = null

    var onOfferPublished: ((OfferItem) -> Unit)? = null
    var onOfferDeleted: ((String) -> Unit)? = null

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
                    onE2eeMessageReceived = lastOnE2ee ?: { _, _, _, _ -> },
                    onSessionRevoked = lastOnRevoked ?: {},
                    onPresenceSync = lastOnPresenceSync,
                    onPeerOnline = lastOnPeerOnline,
                    onPeerOffline = lastOnPeerOffline,
                    onMutualConnection = lastOnMutualConnection,
                    onPeerMarkedYou = lastOnPeerMarkedYou
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
        onE2eeMessageReceived: (senderHash: String, payload: String, iv: String, messageId: String) -> Unit,
        onSessionRevoked: (notice: String) -> Unit,
        onPresenceSync: ((List<RemotePeer>) -> Unit)? = null,
        onPeerOnline: ((RemotePeer) -> Unit)? = null,
        onPeerOffline: ((String) -> Unit)? = null,
        onMutualConnection: ((partnerIdentity: String, partnerAlias: String) -> Unit)? = null,
        onPeerMarkedYou: ((partnerIdentity: String, partnerAlias: String) -> Unit)? = null
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
        lastOnMutualConnection = onMutualConnection
        lastOnPeerMarkedYou = onPeerMarkedYou

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
                            val msgId = obj.optString("messageId", "")
                            onE2eeMessageReceived(sender, payload, iv, msgId)
                        }
                        "MUTUAL_CONNECTION_ESTABLISHED" -> {
                            val partnerId = obj.optString("partnerIdentity")
                            val partnerAlias = obj.optString("partnerAlias", "Conexão Mútua")
                            if (partnerId.isNotBlank()) {
                                onMutualConnection?.invoke(partnerId, partnerAlias)
                            }
                        }
                        "PEER_MARKED_YOU" -> {
                            val partnerId = obj.optString("partnerIdentity")
                            val partnerAlias = obj.optString("partnerAlias", "Alguém")
                            if (partnerId.isNotBlank()) {
                                onPeerMarkedYou?.invoke(partnerId, partnerAlias)
                            }
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
                                            intent = p.optString("intent", "QUERO_CONVERSAR"),
                                            isMutual = p.optBoolean("isMutual", false),
                                            isMarkedByMe = p.optBoolean("isMarkedByMe", false),
                                            isMarkingMe = p.optBoolean("isMarkingMe", false)
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
                                    intent = p.optString("intent", "QUERO_CONVERSAR"),
                                    isMutual = p.optBoolean("isMutual", false),
                                    isMarkedByMe = p.optBoolean("isMarkedByMe", false),
                                    isMarkingMe = p.optBoolean("isMarkingMe", false)
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
                        "OFFER_PUBLISHED" -> {
                            val o = obj.optJSONObject("offer")
                            if (o != null) {
                                val item = OfferItem(
                                    id = o.getString("id"),
                                    authorId = o.getString("authorId"),
                                    authorAlias = o.optString("authorAlias", "Profissional"),
                                    profession = o.optString("profession", ""),
                                    description = o.optString("description", ""),
                                    distanceMeters = o.optDouble("distanceMeters", 10.0),
                                    proximityLabel = o.optString("proximityLabel", "Profissional Próximo • 10m"),
                                    imageBase64 = o.optString("imageBase64").takeIf { it.isNotBlank() },
                                    externalLink = o.optString("externalLink").takeIf { it.isNotBlank() }
                                )
                                onOfferPublished?.invoke(item)
                            }
                        }
                        "OFFER_DELETED" -> {
                            val id = obj.optString("offerId")
                            if (id.isNotBlank()) {
                                onOfferDeleted?.invoke(id)
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
    fun sendE2eeEnvelope(recipientHash: String, ciphertext: String, ivNonce: String, messageId: String = java.util.UUID.randomUUID().toString()): Boolean {
        val ws = activeWebSocket ?: return false
        val envelope = JSONObject().apply {
            put("type", "E2EE_MESSAGE")
            put("recipientHash", recipientHash)
            put("ciphertextPayload", ciphertext)
            put("ivNonce", ivNonce)
            put("messageId", messageId)
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
    suspend fun fetchNearbyPresence(myIdentity: String, myAlias: String = ""): Result<List<RemotePeer>> = withContext(Dispatchers.IO) {
        try {
            val encodedAlias = java.net.URLEncoder.encode(myAlias, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/presence/nearby?myIdentity=$myIdentity&myAlias=$encodedAlias")
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
                                intent = p.optString("intent", "QUERO_CONVERSAR"),
                                isMutual = p.optBoolean("isMutual", false),
                                isMarkedByMe = p.optBoolean("isMarkedByMe", false),
                                isMarkingMe = p.optBoolean("isMarkingMe", false)
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
        senderAlias: String,
        messageId: String = java.util.UUID.randomUUID().toString()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("senderHash", senderHash)
                put("recipientHash", recipientHash)
                put("ciphertextPayload", ciphertext)
                put("senderAlias", senderAlias)
                put("ivNonce", java.util.UUID.randomUUID().toString().take(12))
                put("messageId", messageId)
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
                                ivNonce = m.optString("ivNonce", ""),
                                messageId = m.optString("messageId", "")
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

    /**
     * Consulta status das conexões (mútuas e marcações) diretamente da fonte da verdade
     */
    suspend fun fetchConnectionStatus(myIdentity: String): Result<ConnectionStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/connections/status?myIdentity=$myIdentity")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val obj = JSONObject(bodyStr)
                val markedArr = obj.optJSONArray("markedByMe")
                val markingArr = obj.optJSONArray("markingMe")
                val mutualArr = obj.optJSONArray("mutual")

                val markedList = mutableListOf<String>()
                markedArr?.let { for (i in 0 until it.length()) markedList.add(it.getString(i)) }

                val markingList = mutableListOf<String>()
                markingArr?.let { for (i in 0 until it.length()) markingList.add(it.getString(i)) }

                val mutualList = mutableListOf<String>()
                mutualArr?.let { for (i in 0 until it.length()) mutualList.add(it.getString(i)) }

                Result.success(ConnectionStatusResponse(markedList, markingList, mutualList))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Publica ou atualiza uma oferta/divulgação local no servidor
     */
    suspend fun publishOffer(offer: OfferItem): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("id", offer.id)
                put("authorId", offer.authorId)
                put("authorAlias", offer.authorAlias)
                put("profession", offer.profession)
                put("description", offer.description)
                put("imageBase64", offer.imageBase64 ?: "")
                put("externalLink", offer.externalLink ?: "")
                put("distanceMeters", offer.distanceMeters)
                put("proximityLabel", offer.proximityLabel)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/offers/publish")
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
     * Remove uma oferta/divulgação local do servidor
     */
    suspend fun deleteOffer(offerId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/offers/$offerId")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Busca ofertas ativas de outros usuários no servidor
     */
    suspend fun fetchNearbyOffers(): Result<List<OfferItem>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/offers/nearby")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val obj = JSONObject(bodyStr)
                val arr = obj.optJSONArray("offers")
                val list = mutableListOf<OfferItem>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            OfferItem(
                                id = o.getString("id"),
                                authorId = o.getString("authorId"),
                                authorAlias = o.optString("authorAlias", "Profissional"),
                                profession = o.optString("profession", ""),
                                description = o.optString("description", ""),
                                distanceMeters = o.optDouble("distanceMeters", 10.0),
                                proximityLabel = o.optString("proximityLabel", "Profissional Próximo • 10m"),
                                imageBase64 = o.optString("imageBase64").takeIf { it.isNotBlank() },
                                externalLink = o.optString("externalLink").takeIf { it.isNotBlank() }
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

    /**
     * Cria um convite criptografado de uso único no servidor
     */
    suspend fun createOneTimeInvite(senderIdentity: String, senderAlias: String): Result<CreateInviteResponse> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("senderIdentity", senderIdentity)
                put("senderAlias", senderAlias)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/invites/create")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    val obj = JSONObject(bodyStr)
                    Result.success(
                        CreateInviteResponse(
                            inviteId = obj.getString("inviteId"),
                            token = obj.getString("token"),
                            inviteUrl = obj.getString("inviteUrl"),
                            senderIdentity = obj.getString("senderIdentity"),
                            senderAlias = obj.getString("senderAlias"),
                            expiresAt = obj.optLong("expiresAt", 0)
                        )
                    )
                } else {
                    val err = try { JSONObject(bodyStr).optString("error", "Erro ao criar convite") } catch (_: Exception) { "Erro HTTP ${response.code}" }
                    Result.failure(IOException(err))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resgata o convite de uso único para estabelecer conexão mútua à distância
     */
    suspend fun redeemOneTimeInvite(
        inviteId: String,
        token: String,
        receiverIdentity: String,
        receiverAlias: String
    ): Result<RedeemInviteResponse> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("inviteId", inviteId)
                put("token", token)
                put("receiverIdentity", receiverIdentity)
                put("receiverAlias", receiverAlias)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/invites/redeem")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    val obj = JSONObject(bodyStr)
                    Result.success(
                        RedeemInviteResponse(
                            senderIdentity = obj.getString("senderIdentity"),
                            senderAlias = obj.getString("senderAlias"),
                            receiverIdentity = obj.getString("receiverIdentity"),
                            receiverAlias = obj.getString("receiverAlias"),
                            message = obj.optString("message", "Conexão mútua estabelecida com sucesso!")
                        )
                    )
                } else {
                    val err = try { JSONObject(bodyStr).optString("error", "Erro ao resgatar convite") } catch (_: Exception) { "Erro HTTP ${response.code}" }
                    Result.failure(IOException(err))
                }
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

data class CreateInviteResponse(
    val inviteId: String,
    val token: String,
    val inviteUrl: String,
    val senderIdentity: String,
    val senderAlias: String,
    val expiresAt: Long
)

data class RedeemInviteResponse(
    val senderIdentity: String,
    val senderAlias: String,
    val receiverIdentity: String,
    val receiverAlias: String,
    val message: String
)

data class ConnectionStatusResponse(
    val markedByMe: List<String>,
    val markingMe: List<String>,
    val mutual: List<String>
)

data class RemotePeer(
    val identityHash: String,
    val alias: String,
    val intent: String,
    val isMutual: Boolean = false,
    val isMarkedByMe: Boolean = false,
    val isMarkingMe: Boolean = false
)

data class PendingMessage(
    val senderHash: String,
    val senderAlias: String,
    val ciphertext: String,
    val ivNonce: String,
    val messageId: String = ""
)
