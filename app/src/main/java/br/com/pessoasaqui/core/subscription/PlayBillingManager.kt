package br.com.pessoasaqui.core.subscription

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Estados da conexão com o Google Play Billing.
 */
enum class PlayBillingState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    UNAVAILABLE
}

/**
 * Gerenciador Oficial do Google Play Billing para o PessoasAqui.
 *
 * Gerencia o ciclo completo de assinaturas (Google Play Subscriptions):
 * 1. Conexão assíncrona ao serviço do Google Play.
 * 2. Consulta de detalhes do produto (preço na moeda local do usuário).
 * 3. Abertura da folha de pagamento oficial (launchBillingFlow).
 * 4. Confirmação obrigatória de 3 dias (acknowledgePurchase) conforme as regras do Google Play.
 * 5. Verificação automática de renovação, cancelamento e restauração de compras.
 */
class PlayBillingManager(
    private val context: Context,
    private val subscriptionPlanManager: SubscriptionPlanManager
) : PurchasesUpdatedListener, BillingClientStateListener {

    companion object {
        private const val TAG = "PlayBillingManager"
        const val VIP_PRODUCT_ID = "pessoasaqui_vip_monthly_v1"
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    private val _connectionState = MutableStateFlow(PlayBillingState.DISCONNECTED)
    val connectionState: StateFlow<PlayBillingState> = _connectionState.asStateFlow()

    private val _vipProductDetails = MutableStateFlow<ProductDetails?>(null)
    val vipProductDetails: StateFlow<ProductDetails?> = _vipProductDetails.asStateFlow()

    private val _formattedPrice = MutableStateFlow("R$ 4,99 / mês")
    val formattedPrice: StateFlow<String> = _formattedPrice.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
        .enableOneTimeProducts()
        .build()

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(pendingPurchasesParams)
        .build()

    init {
        startBillingConnection()
    }

    /**
     * Inicia a conexão com o Google Play Services.
     */
    fun startBillingConnection(onSetupComplete: ((Boolean) -> Unit)? = null) {
        if (_connectionState.value == PlayBillingState.CONNECTING || _connectionState.value == PlayBillingState.CONNECTED) {
            onSetupComplete?.invoke(true)
            return
        }

        _connectionState.value = PlayBillingState.CONNECTING
        try {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.i(TAG, "Conexão com Google Play Billing estabelecida com sucesso.")
                        _connectionState.value = PlayBillingState.CONNECTED
                        queryProductDetails()
                        queryActivePurchases()
                        onSetupComplete?.invoke(true)
                    } else {
                        Log.w(TAG, "Falha na conexão com Google Play Billing: ${billingResult.debugMessage} (código ${billingResult.responseCode})")
                        _connectionState.value = PlayBillingState.UNAVAILABLE
                        onSetupComplete?.invoke(false)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Google Play Billing desconectado. Tentará reconectar quando necessário.")
                    _connectionState.value = PlayBillingState.DISCONNECTED
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao conectar no Google Play Billing", e)
            _connectionState.value = PlayBillingState.UNAVAILABLE
            onSetupComplete?.invoke(false)
        }
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        // Implementado no callback anônimo acima
    }

    override fun onBillingServiceDisconnected() {
        _connectionState.value = PlayBillingState.DISCONNECTED
    }

    /**
     * Consulta os detalhes e o preço do Plano VIP na moeda local do usuário.
     */
    fun queryProductDetails() {
        if (!billingClient.isReady) return

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(VIP_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val details = productDetailsList.firstOrNull { it.productId == VIP_PRODUCT_ID }
                if (details != null) {
                    _vipProductDetails.value = details
                    val pricingPhase = details.subscriptionOfferDetails?.firstOrNull()
                        ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                    val price = pricingPhase?.formattedPrice
                    if (!price.isNullOrBlank()) {
                        _formattedPrice.value = "$price / mês"
                        Log.i(TAG, "Preço oficial Google Play obtido: $price")
                    }
                }
            } else {
                Log.w(TAG, "Falha ao consultar detalhes do produto: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Consulta as assinaturas ativas na conta do usuário no Google Play.
     */
    fun queryActivePurchases(onResult: ((Boolean) -> Unit)? = null) {
        if (!billingClient.isReady) {
            onResult?.invoke(false)
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var hasActiveVip = false
                for (purchase in purchases) {
                    if (purchase.products.contains(VIP_PRODUCT_ID) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        hasActiveVip = true
                        handlePurchase(purchase)
                        break
                    }
                }

                if (!hasActiveVip) {
                    // Se não há compra ativa e não estamos em modo de teste forçado, reverte para FREE
                    if (!subscriptionPlanManager.isTestOverrideActive()) {
                        subscriptionPlanManager.setSubscriptionTier(SubscriptionTier.FREE)
                    }
                }
                onResult?.invoke(hasActiveVip)
            } else {
                Log.w(TAG, "Erro ao consultar compras ativas: ${billingResult.debugMessage}")
                onResult?.invoke(false)
            }
        }
    }

    /**
     * Lança a folha de pagamento oficial do Google Play.
     */
    fun launchPurchaseFlow(activity: Activity): Result<Unit> {
        if (!billingClient.isReady) {
            startBillingConnection { ready ->
                if (ready) launchPurchaseFlow(activity)
            }
            return Result.failure(Exception("Google Play Billing conectando. Tente novamente em instantes."))
        }

        val details = _vipProductDetails.value
        if (details == null) {
            queryProductDetails()
            return Result.failure(Exception("Obtendo dados do plano VIP no Google Play. Tente novamente em segundos."))
        }

        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: return Result.failure(Exception("Oferta de assinatura não disponível no momento."))

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()
        )

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val response = billingClient.launchBillingFlow(activity, flowParams)
        return if (response.responseCode == BillingClient.BillingResponseCode.OK) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Falha ao abrir Google Play: ${response.debugMessage}"))
        }
    }

    /**
     * Callback acionado pelo Google Play após tentativa de compra.
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                    _statusMessage.value = "Assinatura VIP confirmada com sucesso!"
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Usuário cancelou a assinatura no Google Play.")
                _statusMessage.value = "Operação cancelada pelo usuário."
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.i(TAG, "Item já pertence ao usuário. Restaurando VIP...")
                subscriptionPlanManager.setSubscriptionTier(SubscriptionTier.PRO_VIP)
                _statusMessage.value = "Você já possui o Plano VIP ativo!"
            }
            else -> {
                Log.w(TAG, "Erro de compra no Google Play: ${billingResult.debugMessage}")
                _statusMessage.value = "Falha no Google Play: ${billingResult.debugMessage}"
            }
        }
    }

    /**
     * Processa a compra e executa a confirmação obrigatória de 3 dias (Acknowledge) exigida pelo Google Play.
     */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.products.contains(VIP_PRODUCT_ID) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        ) {
            // Libera imediatamente o plano VIP
            subscriptionPlanManager.setSubscriptionTier(SubscriptionTier.PRO_VIP)

            // Confirma a compra no Google Play se ainda não foi confirmada
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.i(TAG, "Compra VIP confirmada com sucesso (Acknowledge OK).")
                    } else {
                        Log.w(TAG, "Falha ao confirmar compra no Google Play: ${billingResult.debugMessage}")
                    }
                }
            }
        }
    }

    /**
     * Restaura compras anteriores associadas à conta Google ativa no dispositivo.
     */
    fun restorePurchases(onFinished: (success: Boolean, message: String) -> Unit) {
        if (!billingClient.isReady) {
            startBillingConnection { ready ->
                if (ready) {
                    restorePurchases(onFinished)
                } else {
                    onFinished(false, "Serviço do Google Play indisponível no aparelho.")
                }
            }
            return
        }

        queryActivePurchases { hasVip ->
            if (hasVip) {
                onFinished(true, "Plano VIP restaurado com sucesso!")
            } else {
                onFinished(false, "Nenhuma assinatura ativa encontrada para esta conta Google.")
            }
        }
    }

    /**
     * Limpa mensagens de status exibidas na UI.
     */
    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /**
     * Encerra a conexão com o Google Play ao destruir o aplicativo.
     */
    fun destroy() {
        try {
            billingClient.endConnection()
        } catch (_: Exception) {}
    }
}
