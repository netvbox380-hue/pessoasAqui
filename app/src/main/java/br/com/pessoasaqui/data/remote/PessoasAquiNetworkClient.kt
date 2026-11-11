package br.com.pessoasaqui.data.remote

import br.com.pessoasaqui.domain.model.AppVersionInfo
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente de Rede HTTP e WebSockets do PessoasAqui.
 * Conecta o aplicativo ao servidor backend (seja local ou hospedado no Render / Supabase).
 */
open class PessoasAquiNetworkClient(
    var baseUrl: String = "https://pessoasaqui3.onrender.com",
    var wsUrl: String = "wss://pessoasaqui3.onrender.com"
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var activeWebSocket: WebSocket? = null
    private var isWsConnected = false
    val isWebSocketConnected: Boolean get() = isWsConnected

    private var shouldReconnect = true
    private var lastIdentityHash: String? = null
    private var lastAlias: String? = null
    private var lastIntent: String? = null
    private var lastPublicKeyBase64: String? = null
    private var lastSignChallenge: ((ByteArray) -> String)? = null
    private var lastOnE2ee: ((String, String, String, String) -> Unit)? = null
    private var lastOnRevoked: ((String) -> Unit)? = null
    private var lastOnPresenceSync: ((List<RemotePeer>) -> Unit)? = null
    private var lastOnPeerOnline: ((RemotePeer) -> Unit)? = null
    private var lastOnPeerOffline: ((String) -> Unit)? = null
    private var lastOnMutualConnection: ((String, String) -> Unit)? = null
    private var lastOnMutualConnectionBroken: ((String, String) -> Unit)? = null
    private var lastOnPeerMarkedYou: ((String, String) -> Unit)? = null

    var onOfferPublished: ((OfferItem) -> Unit)? = null
    var onOfferDeleted: ((String) -> Unit)? = null
    var onSupportTicketReplied: ((ticketId: String, subject: String, reply: String, timestamp: Long) -> Unit)? = null
    var onRecoverySecurityAlert: ((method: String, eligibleAtEpochMs: Long) -> Unit)? = null
    var onAuthenticatedKeyRotation: ((identityHash: String) -> Unit)? = null
    var onModerationWarning: ((message: String) -> Unit)? = null
    var onAccountBanned: ((message: String) -> Unit)? = null
    var onMessageError: ((messageId: String, errorReason: String) -> Unit)? = null

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
                    onMutualConnectionBroken = lastOnMutualConnectionBroken,
                    onPeerMarkedYou = lastOnPeerMarkedYou,
                    myPublicKeyBase64 = lastPublicKeyBase64,
                    signChallenge = lastSignChallenge
                )
            }
        }
    }

    /**
     * Disponibiliza a chave de identidade antes do PRIMEIRO heartbeat HTTP.
     * Não depende de abrir WebSocket: evita deadlock no bootstrap seguro.
     */
    fun configureIdentityCredentials(
        publicKeyBase64: String, signChallenge: (ByteArray) -> String
    ) {
        require(publicKeyBase64.isNotBlank()) { "Chave pública inválida" }
        lastPublicKeyBase64 = publicKeyBase64
        lastSignChallenge = signChallenge
    }

    /** Provas de posse assinadas para operações HTTP sensíveis. */
    private fun signedHttpProof(operation: String, identity: String, alias: String = "", hash: String = ""): JSONObject {
        val signer = lastSignChallenge ?: throw SecurityException("Chave privada indisponível")
        val pub = lastPublicKeyBase64?.takeIf { it.isNotBlank() }
            ?: throw SecurityException("Chave pública indisponível")
        return br.com.pessoasaqui.core.crypto.InviteIdentityProof.create(
            operation = operation, identity = identity, alias = alias,
            publicKeyBase64 = pub, signChallenge = signer, signedHash = hash
        ).toJson()
    }

    /**
     * Registra a identidade técnica criptográfica no servidor
     */
    suspend fun registerIdentity(
        identityHash: String,
        publicKeyEd25519: String,
        publicKeyX25519: String,
        pin: String,
        alias: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("identityHash", identityHash)
                put("publicKeyEd25519", publicKeyEd25519)
                put("publicKeyX25519", publicKeyX25519)
                // Nunca transmitir PIN pessoal neste endpoint.
                put("alias", alias)
                put("identityProof", identityProof.toJson())
            }
            val request = Request.Builder()
                .url("$baseUrl/api/identities/register")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    val body = response.body?.string().orEmpty()
                    val reason = runCatching { JSONObject(body).optString("error") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "Registro não confirmado"
                    Result.failure(IOException("$reason (HTTP ${response.code})"))
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
            val signer = lastSignChallenge ?: throw SecurityException("Assinatura indisponível")
            val publicKey = lastPublicKeyBase64?.takeIf { it.isNotBlank() }
                ?: throw SecurityException("Chave pública indisponível")
            val proof = br.com.pessoasaqui.core.crypto.InviteIdentityProof.create(
                operation = "CONNECTION_MARK", identity = fromIdentity,
                alias = toIdentity.removePrefix("peer-").trim().uppercase(java.util.Locale.ROOT),
                publicKeyBase64 = publicKey, signChallenge = signer
            )
            val json = JSONObject().apply {
                put("fromIdentity", fromIdentity)
                put("toIdentity", toIdentity)
                put("identityProof", proof.toJson())
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
     * Desfaz a marcação de uma pessoa e cancela vínculo mútuo no backend
     */
    suspend fun unmarkConnection(
        fromIdentity: String,
        toIdentity: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val signer = lastSignChallenge ?: throw SecurityException("Assinatura indisponível")
            val publicKey = lastPublicKeyBase64?.takeIf { it.isNotBlank() }
                ?: throw SecurityException("Chave pública indisponível")
            val proof = br.com.pessoasaqui.core.crypto.InviteIdentityProof.create(
                operation = "CONNECTION_UNMARK", identity = fromIdentity,
                alias = toIdentity.removePrefix("peer-").trim().uppercase(java.util.Locale.ROOT),
                publicKeyBase64 = publicKey, signChallenge = signer
            )
            val json = JSONObject().apply {
                put("fromIdentity", fromIdentity)
                put("toIdentity", toIdentity)
                put("identityProof", proof.toJson())
            }
            val request = Request.Builder()
                .url("$baseUrl/api/connections/unmark")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val resObj = JSONObject(bodyStr)
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException(resObj.optString("error", "Erro ao desmarcar")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Contestação autenticada pelo aparelho que ainda detém a chave ativa. */
    suspend fun contestFamilyRecovery(
        ownerIdentity: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val body=JSONObject().apply {
                put("targetIdentity",ownerIdentity)
                put("reason","Tentativa de recuperação não reconhecida pelo titular.")
                put("identityProof",identityProof.toJson())
            }
            val request=Request.Builder().url("$baseUrl/api/family/recovery/contest")
                .post(body.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val data=JSONObject(response.body?.string()?:"{}")
                if(response.isSuccessful&&data.optBoolean("success"))
                    Result.success(data.optInt("cancelled",0))
                else Result.failure(IOException(data.optString("error","Contestação não confirmada.")))
            }
        }catch(e:Exception){Result.failure(e)}
    }

    /** Verificação persistente de dois familiares autorizados, assinada pelo titular. */
    suspend fun fetchFamilyRecoveryReadiness(
        ownerIdentity: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val json=JSONObject().apply {
                put("targetIdentity",ownerIdentity)
                put("identityProof",identityProof.toJson())
            }
            val request=Request.Builder().url("$baseUrl/api/family/recovery/readiness")
                .post(json.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val obj=JSONObject(response.body?.string()?:"{}")
                if(response.isSuccessful&&obj.optBoolean("success")) {
                    Result.success(obj.getInt("authorizedFamilyCount"))
                } else {
                    Result.failure(IOException(obj.optString("error","Rede familiar indisponível.")))
                }
            }
        }catch(e:Exception){Result.failure(e)}
    }

    /** As partes V3 somente podem ir aos familiares com delegação
     * ativa confirmada pela fonte autoritativa PostgreSQL. */
    suspend fun fetchAuthorizedFamilyGuardians(
        ownerIdentity: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val payload=JSONObject().put("targetIdentity",ownerIdentity)
                .put("identityProof",identityProof.toJson())
            val req=Request.Builder().url("$baseUrl/api/family/recovery/delegates")
                .post(payload.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(req).execute().use { response ->
                val json=JSONObject(response.body?.string() ?: "{}")
                if(!response.isSuccessful || !json.optBoolean("success"))
                    Result.failure(SecurityException(
                        json.optString("error","Delegações familiares indisponíveis.")
                    ))
                else {
                    val active=json.getJSONArray("authorizedFamilyIdentities")
                    Result.success((0 until active.length()).map { active.getString(it) })
                }
            }
        } catch(e:Exception){Result.failure(e)}
    }

    /**
     * Credenciamento assinado: proprietário autoriza uma chave familiar.
     * Requer PostgreSQL e prova ECDSA válida da identidade titular.
     */
    suspend fun registerFamilyRecoveryDelegate(
        targetIdentity: String,
        familyIdentity: String,
        familyPublicKey: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("targetIdentity", targetIdentity)
                put("familyIdentity", familyIdentity)
                put("familyPublicKey", familyPublicKey)
                put("identityProof", identityProof.toJson())
            }
            val request = Request.Builder().url("$baseUrl/api/family/recovery/delegate")
                .post(json.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val obj = JSONObject(response.body?.string() ?: "{}")
                if (response.isSuccessful && obj.optBoolean("success")) Result.success(true)
                else Result.failure(IOException(obj.optString("error", "Falha ao autorizar familiar")))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Revoga autorização familiar e todos os códigos pendentes. */
    suspend fun revokeFamilyRecoveryDelegate(
        targetIdentity: String,
        familyIdentity: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("targetIdentity", targetIdentity)
                put("familyIdentity", familyIdentity)
                put("identityProof", identityProof.toJson())
            }
            val request = Request.Builder().url("$baseUrl/api/family/recovery/revoke")
                .post(json.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val obj = JSONObject(response.body?.string() ?: "{}")
                if (response.isSuccessful && obj.optBoolean("success")) Result.success(true)
                else Result.failure(IOException(obj.optString("error", "Não foi possível revogar a autorização")))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /** A assinatura do familiar vincula autorização ao alvo. */
    suspend fun issueFamilyRecovery(
        targetIdentity: String,
        familyIdentity: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof,
        method: String = "QR"
    ): Result<RecoveryIssueResponse> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("targetIdentity", targetIdentity)
                put("familyIdentity", familyIdentity)
                put("method", method)
                put("identityProof", identityProof.toJson())
            }
            val request = Request.Builder().url("$baseUrl/api/family/recovery/issue")
                .post(json.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val obj = JSONObject(response.body?.string() ?: "{}")
                if (response.isSuccessful && obj.optBoolean("success")) {
                    Result.success(RecoveryIssueResponse(
                        recoveryKey = obj.getString("recoveryKey"),
                        targetIdentity = obj.getString("targetIdentity"),
                        expiresAtEpochMs = obj.getLong("expiresAtEpochMs"),
                        method = obj.optString("method", method),
                        eligibleAtEpochMs = obj.optLong("eligibleAtEpochMs", 0L)
                    ))
                } else Result.failure(IOException(obj.optString("error", "Falha ao gerar autorização")))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Registra a solicitação assinada pelo aparelho novo ANTES do segundo aval. */
    suspend fun prepareFamilyRecovery(
        recoveryKey: String, newDeviceIdentity: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json=JSONObject().apply {
                put("recoveryKey",recoveryKey)
                put("newDeviceIdentity",newDeviceIdentity)
                put("identityProof",identityProof.toJson())
            }
            val request=Request.Builder().url("$baseUrl/api/family/recovery/prepare")
                .post(json.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val obj=JSONObject(response.body?.string()?:"{}")
                if(response.isSuccessful&&obj.optBoolean("success"))Result.success(true)
                else Result.failure(IOException(obj.optString("error","Sessão não iniciada.")))
            }
        }catch(e:Exception){Result.failure(e)}
    }

    /** Status consultado somente pela chave do aparelho que preparou a sessão. */
    suspend fun getFamilyRecoveryStatus(
        recoveryKey: String,
        newDeviceIdentity: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json=JSONObject().apply {
                put("recoveryKey",recoveryKey)
                put("newDeviceIdentity",newDeviceIdentity)
                put("identityProof",identityProof.toJson())
            }
            val request=Request.Builder().url("$baseUrl/api/family/recovery/status")
                .post(json.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val body=JSONObject(response.body?.string()?:"{}")
                if(response.isSuccessful&&body.optBoolean("success"))
                    Result.success(body.getString("status"))
                else Result.failure(IOException(body.optString("error","Não foi possível consultar recuperação.")))
            }
        }catch(e:Exception){Result.failure(e)}
    }

    /** Segunda autorização: identidade distinta da que emitiu o QR/LINK. */
    suspend fun approveFamilyRecovery(
        targetIdentity: String, familyIdentity: String, recoveryKey: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("targetIdentity", targetIdentity)
                put("familyIdentity", familyIdentity)
                put("recoveryKey", recoveryKey)
                put("identityProof", identityProof.toJson())
            }
            val request = Request.Builder().url("$baseUrl/api/family/recovery/approve")
                .post(json.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val obj = JSONObject(response.body?.string() ?: "{}")
                if (response.isSuccessful && obj.optBoolean("success")) Result.success(true)
                else Result.failure(IOException(obj.optString("error", "Segunda autorização recusada")))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Novo dispositivo prova posse da nova chave; o PIN não viaja para o
     * servidor e é reservado à descriptografia posterior do cofre.
     */
    suspend fun claimFamilyRecovery(
        recoveryKey: String,
        newDeviceIdentity: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("recoveryKey", recoveryKey)
                put("newDeviceIdentity", newDeviceIdentity)
                put("identityProof", identityProof.toJson())
            }
            val request = Request.Builder().url("$baseUrl/api/family/recovery/claim")
                .post(json.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val obj = JSONObject(response.body?.string() ?: "{}")
                if (response.isSuccessful && obj.optBoolean("success")) {
                    Result.success(obj.getString("targetIdentity"))
                } else {
                    Result.failure(IOException(obj.optString("error", "Recuperação não autorizada")))
                }
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun listV3RecoveryGuardians(
        targetIdentity: String, recoveryKey: String,
        proof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val body=JSONObject().put("targetIdentity",targetIdentity)
                .put("recoveryKey",recoveryKey).put("identityProof",proof.toJson())
            val req=Request.Builder().url("$baseUrl/api/family/recovery/guardians")
                .post(body.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(req).execute().use { response ->
                val json=JSONObject(response.body?.string()?:"{}")
                if(!response.isSuccessful || !json.optBoolean("success"))
                    Result.failure(SecurityException(json.optString("error","Sem guardiões")))
                else {
                    val arr=json.getJSONArray("guardianIdentities")
                    Result.success((0 until arr.length()).map { arr.getString(it) })
                }
            }
        } catch(e:Exception){Result.failure(e)}
    }

    suspend fun authorizeV3ShareRelease(
        targetIdentity: String,
        familyIdentity: String,
        recoveryKey: String,
        newDeviceIdentity: String,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("targetIdentity", targetIdentity)
                put("familyIdentity", familyIdentity)
                put("recoveryKey", recoveryKey)
                put("newDeviceIdentity", newDeviceIdentity)
                put("identityProof", identityProof.toJson())
            }
            val request = Request.Builder().url("$baseUrl/api/family/recovery/share-release")
                .post(payload.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val obj = JSONObject(response.body?.string() ?: "{}")
                if(response.isSuccessful && obj.optBoolean("authorized")) Result.success(true)
                else Result.failure(SecurityException(obj.optString("error", "Autorização negada")))
            }
        } catch(e: Exception) { Result.failure(e) }
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
        onMutualConnectionBroken: ((partnerIdentity: String, partnerAlias: String) -> Unit)? = null,
        onPeerMarkedYou: ((partnerIdentity: String, partnerAlias: String) -> Unit)? = null,
        myPublicKeyBase64: String? = null,
        signChallenge: ((ByteArray) -> String)? = null
    ) {
        disconnectSocket()

        shouldReconnect = true
        lastIdentityHash = myIdentityHash
        lastAlias = myAlias
        lastIntent = myIntent
        // A abertura de WS não deve apagar credenciais HTTP já configuradas.
        lastPublicKeyBase64 = myPublicKeyBase64 ?: lastPublicKeyBase64
        lastSignChallenge = signChallenge ?: lastSignChallenge
        lastOnE2ee = onE2eeMessageReceived
        lastOnRevoked = onSessionRevoked
        lastOnPresenceSync = onPresenceSync
        lastOnPeerOnline = onPeerOnline
        lastOnPeerOffline = onPeerOffline
        lastOnMutualConnection = onMutualConnection
        lastOnMutualConnectionBroken = onMutualConnectionBroken
        lastOnPeerMarkedYou = onPeerMarkedYou

        val request = Request.Builder().url(wsUrl).build()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isWsConnected = false // Aguardar AUTH_SUCCESS do servidor
                val authNonce = java.util.UUID.randomUUID().toString()
                val authSignature = signChallenge?.invoke("$myIdentityHash:$authNonce".toByteArray(Charsets.UTF_8))
                // Autentica este dispositivo no socket com sua identidade, alias, intenção e prova criptográfica
                val authMsg = JSONObject().apply {
                    put("type", "AUTH")
                    put("identityHash", myIdentityHash)
                    put("alias", myAlias)
                    put("intent", myIntent)
                    myPublicKeyBase64?.let { put("publicKey", it) }
                    put("authNonce", authNonce)
                    if (!authSignature.isNullOrBlank()) {
                        put("signature", authSignature)
                    }
                }
                webSocket.send(authMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "AUTH_SUCCESS" -> { isWsConnected = true }
                        "AUTH_CHALLENGE" -> {
                            val challenge = obj.optString("nonce", "")
                            if (challenge.matches(Regex("^[a-f0-9]{48}$"))) {
                                val payload = "PA-WS-AUTH-V2|${myIdentityHash.uppercase(java.util.Locale.ROOT)}|$challenge"
                                val signature = signChallenge?.invoke(payload.toByteArray(Charsets.UTF_8))
                                if (!signature.isNullOrBlank() && !signature.startsWith("sig-")) {
                                    webSocket.send(JSONObject().apply {
                                        put("type","AUTH")
                                        put("identityHash",myIdentityHash)
                                        put("alias",myAlias)
                                        put("intent",myIntent)
                                        put("publicKey",myPublicKeyBase64)
                                        put("authNonce",challenge)
                                        put("challengeNonce",challenge)
                                        put("signature",signature)
                                    }.toString())
                                }
                            }
                        }
                        "MESSAGE_ERROR" -> {
                            val msgId = obj.optString("messageId")
                            val error = obj.optString("error", "Falha no envio da mensagem")
                            onMessageError?.invoke(msgId, error)
                        }
                        "E2EE_MESSAGE_RECEIVED", "E2EE_MESSAGE" -> {
                            val sender = obj.getString("senderHash")
                            val payload = obj.getString("ciphertextPayload")
                            val iv = obj.optString("ivNonce", "")
                            val msgId = obj.optString("messageId", "")
                            onE2eeMessageReceived(sender, payload, iv, msgId)
                        }
                        "MUTUAL_CONNECTION_ESTABLISHED" -> {
                            val partnerId = obj.optString("partnerIdentity")
                            val partnerAlias = obj.optString("partnerAlias", "Conexão Mútua")
                            val partnerPublicKey = obj.optString("partnerPublicKey").takeIf { it.isNotBlank() }
                            if (partnerId.isNotBlank()) {
                                if (partnerPublicKey != null) {
                                    val cleanPartner = partnerId.removePrefix("peer-").trim().uppercase(java.util.Locale.ROOT)
                                    br.com.pessoasaqui.core.crypto.TrustedIdentityKeys.acceptAuthenticatedBinding(cleanPartner, partnerPublicKey, 1L)
                                    br.com.pessoasaqui.core.crypto.E2eeCryptoEngine.registerPeerPublicKey(cleanPartner, partnerPublicKey)
                                }
                                onMutualConnection?.invoke(partnerId, partnerAlias)
                            }
                        }
                        "MUTUAL_CONNECTION_BROKEN" -> {
                            val partnerId = obj.optString("partnerIdentity")
                            val partnerAlias = obj.optString("partnerAlias", "Contato")
                            if (partnerId.isNotBlank()) onMutualConnectionBroken?.invoke(partnerId, partnerAlias)
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
                                            isMarkingMe = p.optBoolean("isMarkingMe", false),
                                            publicKeyBase64 = p.optString("publicKey").takeIf { it.isNotBlank() }
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
                                    isMarkingMe = p.optBoolean("isMarkingMe", false),
                                    publicKeyBase64 = p.optString("publicKey").takeIf { it.isNotBlank() }
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
                                    externalLink = o.optString("externalLink").takeIf { it.isNotBlank() },
                                    createdAtMs = o.optLong("createdAt", System.currentTimeMillis())
                                )
                                onOfferPublished?.invoke(item)
                            }
                        }
                        "OFFER_DELETED", "OFFER_REMOVED" -> {
                            val id = obj.optString("offerId")
                            if (id.isNotBlank()) {
                                onOfferDeleted?.invoke(id)
                            }
                        }
                        "IDENTITY_KEY_ROTATED" -> {
                            val identity=obj.optString("identityHash")
                            if (identity.matches(Regex("^[A-Fa-f0-9]{16}$"))) {
                                onAuthenticatedKeyRotation?.invoke(identity.uppercase())
                            }
                        }
                        "RECOVERY_ALERT" -> {
                            val method = obj.optString("method", "QR")
                            val eligibleAt = obj.optLong("eligibleAtEpochMs", 0L)
                            onRecoverySecurityAlert?.invoke(method, eligibleAt)
                        }
                        "SUPPORT_TICKET_REPLIED" -> {
                            val tId = obj.optString("ticketId")
                            val subj = obj.optString("subject", "Suporte")
                            val rep = obj.optString("reply")
                            val ts = obj.optLong("timestamp", System.currentTimeMillis())
                            if (rep.isNotBlank()) {
                                onSupportTicketReplied?.invoke(tId, subj, rep, ts)
                            }
                        }
                        "MODERATION_WARNING" -> {
                            val msg = obj.optString("message", "⚠️ Alerta da Moderação: Você recebeu uma advertência formal.")
                            onModerationWarning?.invoke(msg)
                        }
                        "ACCOUNT_BANNED" -> {
                            val msg = obj.optString("message", "Sua conta foi permanentemente banida por descumprimento das regras de segurança e convivência.")
                            onAccountBanned?.invoke(msg)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWsConnected = false
                if (activeWebSocket === webSocket) activeWebSocket = null
                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWsConnected = false
                if (activeWebSocket === webSocket) activeWebSocket = null
                scheduleReconnect()
            }
        })
    }

    /**
     * Envia envelope cifrado E2EE pelo WebSocket (assinatura clássica de 4 argumentos para compatibilidade de testes/subclasses)
     */
    open fun sendE2eeEnvelope(
        recipientHash: String,
        ciphertext: String,
        ivNonce: String,
        messageId: String = java.util.UUID.randomUUID().toString()
    ): Boolean {
        if (!isWsConnected) return false
        val ws = activeWebSocket ?: return false
        val envelope = JSONObject().apply {
            put("type", "E2EE_MESSAGE")
            put("recipientHash", recipientHash)
            put("ciphertextPayload", ciphertext)
            put("ivNonce", ivNonce)
            put("messageId", messageId)
            put("operation", "MESSAGE_SEND")
        }
        return ws.send(envelope.toString())
    }

    /**
     * Envia envelope cifrado E2EE pelo WebSocket com operação explícita
     */
    open fun sendE2eeEnvelope(
        recipientHash: String,
        ciphertext: String,
        ivNonce: String,
        messageId: String,
        operation: String
    ): Boolean {
        if (operation == "MESSAGE_SEND") {
            return sendE2eeEnvelope(recipientHash, ciphertext, ivNonce, messageId)
        }
        if (!isWsConnected) return false
        val ws = activeWebSocket ?: return false
        val envelope = JSONObject().apply {
            put("type", "E2EE_MESSAGE")
            put("recipientHash", recipientHash)
            put("ciphertextPayload", ciphertext)
            put("ivNonce", ivNonce)
            put("messageId", messageId)
            put("operation", operation)
        }
        return ws.send(envelope.toString())
    }

    open fun sendE2eeEnvelope(
        recipientHash: String,
        ciphertext: String,
        ivNonce: String,
        messageId: String,
        isEphemeral: Boolean
    ): Boolean {
        return sendE2eeEnvelope(
            recipientHash,
            ciphertext,
            ivNonce,
            messageId,
            isEphemeral,
            if (isEphemeral) "CALL_SIGNAL" else "MESSAGE_SEND"
        )
    }

    open fun sendE2eeEnvelope(
        recipientHash: String,
        ciphertext: String,
        ivNonce: String,
        messageId: String,
        isEphemeral: Boolean,
        operation: String
    ): Boolean {
        if (!isEphemeral && operation == "MESSAGE_SEND") {
            return sendE2eeEnvelope(recipientHash, ciphertext, ivNonce, messageId)
        }
        if (!isWsConnected) return false
        val ws = activeWebSocket ?: return false
        val envelope = JSONObject().apply {
            put("type", "E2EE_MESSAGE")
            put("recipientHash", recipientHash)
            put("ciphertextPayload", ciphertext)
            put("ivNonce", ivNonce)
            put("messageId", messageId)
            put("operation", operation)
            if (isEphemeral) put("isEphemeral", true)
        }
        return ws.send(envelope.toString())
    }

    /**
     * O servidor e o PostgreSQL são a fonte autoritativa de vínculos.
     * Este protocolo legado não deve mais permitir sincronização unilateral.
     */
    @Deprecated("Use o estado bilateral do servidor, não o cache unilateral")
    fun sendMutualSyncMessage(@Suppress("UNUSED_PARAMETER") mutualPeers: List<String>): Boolean = false

    /**
     * Envia heartbeat de presença HTTP para o backend
     */
    suspend fun sendPresenceHeartbeat(identityHash: String, alias: String, intent: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("identityHash", identityHash)
                val cleanAlias = alias.trim().take(64).ifBlank { "Usuário PessoasAqui" }
                put("alias", cleanAlias)
                put("intent", intent)
                put("identityProof", signedHttpProof(
                    "PRESENCE_HEARTBEAT", identityHash, cleanAlias,
                    br.com.pessoasaqui.core.crypto.InviteIdentityProof.digestParts(intent)
                ))
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
     * Presença visual separada da conexão WS: fechar a interface não cancela
     * mensagens pendentes, chamadas ou processamento E2EE em segundo plano.
     */
    suspend fun sendPresenceVisibility(identityHash: String, alias: String, visible: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val safeAlias = alias.trim().take(64)
                val json = JSONObject().apply {
                    put("identityHash", identityHash)
                    put("alias", safeAlias)
                    put("visible", visible)
                    put("identityProof", signedHttpProof(
                        "PRESENCE_VISIBILITY", identityHash, safeAlias,
                        br.com.pessoasaqui.core.crypto.InviteIdentityProof.digestParts(visible.toString())
                    ))
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/presence/visibility")
                    .post(json.toString().toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IOException("Visibilidade: HTTP ${response.code}"))
                    }
                    Result.success(JSONObject(response.body?.string() ?: "{}").optBoolean("success"))
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
            val cleanAlias = myAlias.trim().take(64)
            val json = JSONObject().apply {
                put("myIdentity",myIdentity)
                put("myAlias",cleanAlias)
                put("identityProof",signedHttpProof("PRESENCE_NEARBY",myIdentity,cleanAlias))
            }
            val request = Request.Builder()
                .url("$baseUrl/api/presence/nearby")
                .post(json.toString().toRequestBody(jsonMediaType))
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
                                isMarkingMe = p.optBoolean("isMarkingMe", false),
                                publicKeyBase64 = p.optString("publicKey").takeIf { it.isNotBlank() }
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
        messageId: String = java.util.UUID.randomUUID().toString(),
        ivNonce: String = "",
        isEphemeral: Boolean = false,
        operation: String = if (isEphemeral) "CALL_SIGNAL" else "MESSAGE_SEND"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ephemContract = if (isEphemeral) "ephemeral" else "durable"
            val json = JSONObject().apply {
                put("senderHash", senderHash)
                put("recipientHash", recipientHash)
                put("ciphertextPayload", ciphertext)
                put("senderAlias", senderAlias)
                put("ivNonce", ivNonce)
                put("messageId", messageId)
                put("operation", operation)
                if (isEphemeral) put("isEphemeral", true)
                val digest = br.com.pessoasaqui.core.crypto.InviteIdentityProof.digestParts(
                    senderHash, recipientHash, ciphertext, ivNonce, messageId, senderAlias,
                    ephemContract, operation
                )
                put("identityProof", signedHttpProof(
                    "MESSAGE_SEND", senderHash,
                    recipientHash.removePrefix("peer-").trim().uppercase(java.util.Locale.ROOT), digest
                ))
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
            val json = JSONObject().apply {
                put("identityHash", identityHash)
                put("identityProof", signedHttpProof("MESSAGE_PENDING", identityHash))
            }
            val request = Request.Builder()
                .url("$baseUrl/api/messages/pending")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val obj = JSONObject(bodyStr)
                val msgsArray = obj.optJSONArray("messages")
                val list = mutableListOf<PendingMessage>()
                if (msgsArray != null) {
                    for (i in 0 until msgsArray.length()) {
                        val m = msgsArray.getJSONObject(i)
                        val sHash = m.optString("senderHash")
                        val cPayload = m.optString("ciphertextPayload")
                        if (sHash.isNotBlank() && cPayload.isNotBlank()) {
                            list.add(
                                PendingMessage(
                                    senderHash = sHash,
                                    senderAlias = m.optString("senderAlias", "Usuário"),
                                    ciphertext = cPayload,
                                    ivNonce = m.optString("ivNonce", ""),
                                    messageId = m.optString("messageId", "")
                                )
                            )
                        }
                    }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Atualiza o pin persistente da chave ECDH de um contato usando a
     * fonte de verdade autenticada do PostgreSQL. Nunca usar somente
     * a chave não autenticada de um anúncio WS para aceitar rotação.
     */
    
    /**
     * Confirma o recebimento de mensagem (ACK de transporte) para o servidor via WebSocket.
     */
    open fun sendServerMessageAck(messageId: String) {
        if (messageId.isBlank()) return
        try {
            val json = org.json.JSONObject().apply {
                put("type", "MESSAGE_ACK")
                put("messageId", messageId)
            }
            activeWebSocket?.send(json.toString())
        } catch (e: Exception) {
            android.util.Log.w("PessoasAqui", "Falha ao enviar MESSAGE_ACK via WebSocket: ${e.message}")
        }
    }

    /**
     * Confirmação de recebimento (ACK de transporte) de mensagem pendente via HTTP.
     */
    suspend fun sendHttpMessageAck(identityHash: String, messageId: String): Result<Boolean> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (messageId.isBlank()) return@withContext Result.success(true)
        try {
            val json = org.json.JSONObject().apply {
                put("identityHash", identityHash)
                put("messageId", messageId)
                put("identityProof", signedHttpProof("MESSAGE_ACK", identityHash))
            }
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/api/messages/ack")
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
     * Confirmação em lote de mensagens recebidas via HTTP (/api/messages/pending).
     */
    suspend fun sendHttpBatchAcks(identityHash: String, messageIds: List<String>): Result<Boolean> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext Result.success(true)
        try {
            val json = org.json.JSONObject().apply {
                put("identityHash", identityHash)
                put("ackMessageIds", org.json.JSONArray(messageIds))
                put("identityProof", signedHttpProof("MESSAGE_PENDING", identityHash))
            }
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/api/messages/pending")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshAuthenticatedPeerKey(
        requesterIdentity: String, targetIdentity: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("requesterIdentity", requesterIdentity)
                put("targetIdentity", targetIdentity)
                put("identityProof", signedHttpProof(
                    "IDENTITY_KEY_LOOKUP", requesterIdentity, targetIdentity))
            }
            val req = Request.Builder()
                .url("$baseUrl/api/identities/key")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful)
                    return@withContext Result.failure(IOException("Consulta de chave: HTTP ${response.code}"))
                val body = JSONObject(response.body?.string() ?: "{}")
                val id = body.optString("identityHash").uppercase(java.util.Locale.ROOT)
                val target = targetIdentity.removePrefix("peer-").trim().uppercase(java.util.Locale.ROOT)
                val pub = body.optString("publicKey")
                val generation = body.optLong("generation", 0L)
                if (!body.optBoolean("success") || id != target ||
                    !br.com.pessoasaqui.core.crypto.TrustedIdentityKeys
                        .acceptAuthenticatedBinding(id, pub, generation))
                    return@withContext Result.failure(SecurityException("Chave recebida rejeitada pelo pinning"))
                br.com.pessoasaqui.core.crypto.E2eeCryptoEngine.registerPeerPublicKey(id, pub)
                Result.success(true)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Consulta status das conexões (mútuas e marcações) diretamente da fonte da verdade
     */
    suspend fun fetchConnectionStatus(myIdentity: String): Result<ConnectionStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("myIdentity",myIdentity)
                put("identityProof",signedHttpProof("CONNECTION_STATUS",myIdentity))
            }
            val request = Request.Builder()
                .url("$baseUrl/api/connections/status")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val obj = JSONObject(bodyStr)
                if (!response.isSuccessful || !obj.optBoolean("success", false)) {
                    return@withContext Result.failure(IOException("Falha ao consultar status de conexões: HTTP ${response.code}"))
                }
                // HTTP 200 sem as três listas NÃO é um snapshot válido.
                // Nunca converter omissão/erro de serialização em revogação.
                if (!obj.has("markedByMe") || !obj.has("markingMe") || !obj.has("mutual")) {
                    return@withContext Result.failure(IOException("Snapshot de conexões incompleto"))
                }
                val markedArr = obj.optJSONArray("markedByMe")
                val markingArr = obj.optJSONArray("markingMe")
                val mutualArr = obj.optJSONArray("mutual")
                if (markedArr == null || markingArr == null || mutualArr == null) {
                    return@withContext Result.failure(IOException("Formato inválido do snapshot de conexões"))
                }
                fun readIdentities(array: org.json.JSONArray): List<String> {
                    return (0 until array.length()).map { index ->
                        val id = array.getString(index).trim().uppercase(java.util.Locale.ROOT)
                        if (!Regex("^[A-F0-9]{16}$").matches(id)) {
                            throw IOException("Identidade inválida no snapshot de conexões")
                        }
                        id
                    }.distinct()
                }
                Result.success(ConnectionStatusResponse(
                    readIdentities(markedArr),
                    readIdentities(markingArr),
                    readIdentities(mutualArr)
                ))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sincroniza ativamente a lista de conexões mútuas via REST HTTP
     */
    suspend fun syncMutualConnections(myIdentity: String, mutualPeers: List<String>): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("myIdentity", myIdentity)
                val arr = JSONArray()
                mutualPeers.forEach { arr.put(it) }
                put("mutualPeers", arr)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/connections/sync")
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
     * Publica ou atualiza uma oferta/divulgação local no servidor
     */
    suspend fun publishOffer(offer: OfferItem, identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof): Result<Boolean> = withContext(Dispatchers.IO) {
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
                put("identityProof", identityProof.toJson())
            }
            val request = Request.Builder()
                .url("$baseUrl/api/offers/publish")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(true)
                else {
                    val error = runCatching {
                        JSONObject(response.body?.string() ?: "{}").optString("error")
                    }.getOrDefault("Falha HTTP ${response.code}")
                    Result.failure(IOException(error))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove uma oferta/divulgação local do servidor
     */
    suspend fun deleteOffer(offerId: String, authorId: String, identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("offerId", offerId)
                put("authorId", authorId)
                put("identityProof", identityProof.toJson())
            }
            val request = Request.Builder()
                .url("$baseUrl/api/offers/remove")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(true)
                else {
                    val error = runCatching {
                        JSONObject(response.body?.string() ?: "{}").optString("error")
                    }.getOrDefault("Falha HTTP ${response.code}")
                    Result.failure(IOException(error))
                }
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
                                externalLink = o.optString("externalLink").takeIf { it.isNotBlank() },
                                createdAtMs = o.optLong("createdAt", System.currentTimeMillis())
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
    suspend fun createOneTimeInvite(senderIdentity: String, senderAlias: String, identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof): Result<CreateInviteResponse> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("senderIdentity", senderIdentity)
                put("senderAlias", senderAlias)
                put("identityProof", identityProof.toJson())
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
                            signature = obj.optString("signature", ""),
                            inviteUrl = obj.getString("inviteUrl"),
                            senderIdentity = obj.getString("senderIdentity"),
                            senderAlias = obj.getString("senderAlias"),
                            expiresAt = obj.optLong("expiresAt", 0)
                        )
                    )
                } else {
                    // Preservar a categoria HTTP para diagnóstico sem registrar
                    // resposta integral, LINK ou token em logs externos.
                    val err = try {
                        JSONObject(bodyStr).optString("error", "Erro ao criar convite")
                    } catch (_: Exception) {
                        "Erro ao criar convite"
                    }
                    Result.failure(IOException("HTTP ${response.code}: ${err.take(180)}"))
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
        receiverAlias: String,
        senderIdentity: String? = null,
        senderAlias: String? = null,
        signature: String? = null,
        expiresAt: Long? = null,
        identityProof: br.com.pessoasaqui.core.crypto.InviteIdentityProof
    ): Result<RedeemInviteResponse> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("inviteId", inviteId)
                put("token", token)
                put("receiverIdentity", receiverIdentity)
                put("receiverAlias", receiverAlias)
                put("identityProof", identityProof.toJson())
                if (!senderIdentity.isNullOrBlank()) put("senderIdentity", senderIdentity)
                if (!senderAlias.isNullOrBlank()) put("senderAlias", senderAlias)
                if (!signature.isNullOrBlank()) put("signature", signature)
                if (expiresAt != null && expiresAt > 0) put("expiresAt", expiresAt)
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
                            senderIdentity = obj.optString("senderIdentity", senderIdentity ?: ""),
                            senderAlias = obj.optString("senderAlias", senderAlias?.ifBlank { "Conexão Convidada" } ?: "Conexão Convidada"),
                            senderPublicKey = obj.optString("senderPublicKey").takeIf { it.isNotBlank() },
                            senderGeneration = obj.optLong("senderGeneration", 1L),
                            receiverIdentity = obj.optString("receiverIdentity", receiverIdentity),
                            receiverAlias = obj.optString("receiverAlias", receiverAlias),
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

    /**
     * Envia formalmente uma denúncia de abuso ou violação para o servidor de moderação
     */
    suspend fun submitReport(
        reporterIdentity: String,
        accusedIdentity: String,
        violationType: String,
        reason: String,
        evidenceMessages: List<Map<String, String>> = emptyList()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("reporterIdentity", reporterIdentity)
                put("accusedIdentity", accusedIdentity)
                put("violationType", violationType)
                put("reason", reason)
                val arr = org.json.JSONArray()
                for (ev in evidenceMessages) {
                    val evObj = JSONObject()
                    ev.forEach { (k, v) -> evObj.put(k, v) }
                    arr.put(evObj)
                }
                put("evidenceMessages", arr)
                put("identityProof", signedHttpProof(
                    "REPORT_CREATE", reporterIdentity, accusedIdentity,
                    br.com.pessoasaqui.core.crypto.InviteIdentityProof.digestParts(
                        reporterIdentity, accusedIdentity, violationType, reason, arr.toString()
                    )
                ))
            }
            val request = Request.Builder()
                .url("$baseUrl/api/reports")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Erro HTTP ${response.code} ao enviar denúncia."))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Reporta incidente em conta de familiar previamente credenciado pelo titular. */
    suspend fun reportFamilySecurityIncident(
        authorIdentity: String, authorAlias: String, affectedIdentity: String,
        category: String, message: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("authorIdentity", authorIdentity)
                put("authorAlias", authorAlias)
                put("affectedIdentity", affectedIdentity)
                put("category", category)
                put("message", message)
                put("identityProof", signedHttpProof(
                    "FAMILY_INCIDENT", authorIdentity, affectedIdentity,
                    br.com.pessoasaqui.core.crypto.InviteIdentityProof.digestParts(
                        authorIdentity, affectedIdentity, authorAlias, category, message
                    )
                ))
            }
            val request = Request.Builder().url("$baseUrl/api/family/support/report")
                .post(json.toString().toRequestBody(jsonMediaType)).build()
            client.newCall(request).execute().use { response ->
                val body = JSONObject(response.body?.string() ?: "{}")
                if (response.isSuccessful && body.optBoolean("success")) Result.success(true)
                else Result.failure(IOException(body.optString("error", "Falha ao registrar incidente")))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Abre uma petição ou chamado de suporte com a equipe do PessoasAqui
     */
    suspend fun submitSupportTicket(
        authorIdentity: String,
        authorAlias: String,
        category: String,
        subject: String,
        message: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("authorIdentity", authorIdentity)
                put("authorAlias", authorAlias)
                put("category", category)
                put("subject", subject)
                put("message", message)
                put("identityProof", signedHttpProof(
                    "TICKET_CREATE", authorIdentity, "",
                    br.com.pessoasaqui.core.crypto.InviteIdentityProof.digestParts(
                        authorIdentity, authorAlias, category, subject, message
                    )
                ))
            }
            val request = Request.Builder()
                .url("$baseUrl/api/tickets")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Erro HTTP ${response.code} ao abrir chamado de suporte."))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Consulta todos os chamados e petições do usuário para recuperar respostas da moderação
     */
    suspend fun fetchMySupportTickets(identityHash: String): Result<List<SupportTicketItem>> = withContext(Dispatchers.IO) {
        try {
            val clean = identityHash.trim()
            val payload = JSONObject().apply {
                put("identity",clean)
                put("identityProof",signedHttpProof("TICKET_READ",clean))
            }
            val request = Request.Builder()
                .url("$baseUrl/api/tickets/my")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val obj = JSONObject(bodyStr)
                val ticketsArray = obj.optJSONArray("tickets")
                val list = mutableListOf<SupportTicketItem>()
                if (ticketsArray != null) {
                    for (i in 0 until ticketsArray.length()) {
                        val t = ticketsArray.getJSONObject(i)
                        list.add(
                            SupportTicketItem(
                                id = t.getString("id"),
                                authorIdentity = t.optString("author_identity"),
                                authorAlias = t.optString("author_alias", "Usuário"),
                                category = t.optString("category", "GERAL"),
                                subject = t.optString("subject", "Suporte"),
                                message = t.optString("message", ""),
                                status = t.optString("status", "OPEN"),
                                moderatorReply = t.optString("moderator_reply").takeIf { it.isNotBlank() && it != "null" },
                                repliedAt = t.optString("replied_at").takeIf { it.isNotBlank() && it != "null" },
                                createdAt = t.optString("created_at")
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

    suspend fun checkAppVersion(): Result<AppVersionInfo> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/app/version-check")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                val body = resp.body?.string() ?: return@withContext Result.failure(IOException("Empty body"))
                val json = JSONObject(body)
                val info = AppVersionInfo(
                    latestVersionCode = json.optLong("latestVersionCode", 10),
                    latestVersionName = json.optString("latestVersionName", "1.0.9"),
                    minSupportedVersionCode = json.optLong("minSupportedVersionCode", 8),
                    forceUpdate = json.optBoolean("forceUpdate", false),
                    playStoreUrl = json.optString("playStoreUrl", "https://play.google.com/store/apps/details?id=br.com.pessoasaqui"),
                    releaseNotes = json.optString("releaseNotes", "")
                )
                Result.success(info)
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

data class SupportTicketItem(
    val id: String,
    val authorIdentity: String,
    val authorAlias: String,
    val category: String,
    val subject: String,
    val message: String,
    val status: String,
    val moderatorReply: String?,
    val repliedAt: String?,
    val createdAt: String?
)

data class CreateInviteResponse(
    val inviteId: String,
    val token: String,
    val signature: String = "",
    val inviteUrl: String,
    val senderIdentity: String,
    val senderAlias: String,
    val expiresAt: Long
)

data class RedeemInviteResponse(
    val senderIdentity: String,
    val senderAlias: String,
    val senderPublicKey: String? = null,
    val senderGeneration: Long = 1L,
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
    val isMarkingMe: Boolean = false,
    val publicKeyBase64: String? = null
)

data class PendingMessage(
    val senderHash: String,
    val senderAlias: String,
    val ciphertext: String,
    val ivNonce: String,
    val messageId: String = ""
)
