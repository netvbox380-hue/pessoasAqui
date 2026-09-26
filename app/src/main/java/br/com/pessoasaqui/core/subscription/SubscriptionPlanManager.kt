package br.com.pessoasaqui.core.subscription

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Níveis de assinatura do PessoasAqui.
 */
enum class SubscriptionTier(
    val label: String,
    val maxBroadcastRecipients: Int,
    val maxReminders: Int,
    val broadcastCooldownMs: Long = if (maxBroadcastRecipients > 10) 3 * 60 * 1000L else 15 * 60 * 1000L,
    val playStoreSkuId: String? = if (maxBroadcastRecipients > 10) "pessoasaqui_vip_monthly_v1" else null
) {
    FREE("Gratuito", maxBroadcastRecipients = 3, maxReminders = 3),
    PRO_VIP("VIP / Pro", maxBroadcastRecipients = 100, maxReminders = 999)
}

/**
 * Gerenciador de Cotas, Políticas Anti-Abuso e Assinaturas (Play Store / In-App Purchases).
 * Preparado em 100% de conformidade com as regras do Google Play Console / Google Play Billing.
 */
class SubscriptionPlanManager(
    private val context: Context? = null,
    injectedPrefs: SharedPreferences? = null
) {

    companion object {
        private const val PREFS_KEY = "pessoasaqui_subscription_prefs"
        private const val KEY_TIER = "subscription_tier"
        private const val KEY_LAST_BROADCAST_MS = "last_broadcast_epoch_ms"
        private const val KEY_BROADCAST_COUNT_TODAY = "broadcast_count_today"
        private const val KEY_LAST_BROADCAST_DAY = "last_broadcast_day"

        // Cooldowns de proteção anti-abuso e anti-spam
        const val COOLDOWN_FREE_MS = 15 * 60 * 1000L // 15 minutos entre transmissões no Gratuito
        const val COOLDOWN_PRO_MS = 3 * 60 * 1000L   // 3 minutos de resfriamento de segurança no VIP

        // Identificador oficial do produto para Google Play Billing
        const val PLAY_PRODUCT_ID_VIP_MONTHLY = "pessoasaqui_vip_monthly_v1"
    }

    private val prefs: SharedPreferences? = injectedPrefs ?: context?.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)

    private var inMemoryLastBroadcastMs: Long = 0L

    private val _currentTier = MutableStateFlow(loadTier())
    val currentTier: StateFlow<SubscriptionTier> = _currentTier.asStateFlow()

    private fun loadTier(): SubscriptionTier {
        val name = prefs?.getString(KEY_TIER, SubscriptionTier.FREE.name) ?: SubscriptionTier.FREE.name
        return try {
            SubscriptionTier.valueOf(name)
        } catch (_: Exception) {
            SubscriptionTier.FREE
        }
    }

    fun isVip(): Boolean = _currentTier.value == SubscriptionTier.PRO_VIP

    /**
     * Define o plano (usado na ativação pós-compra do Google Play Billing ou em testes).
     */
    fun setSubscriptionTier(tier: SubscriptionTier) {
        prefs?.edit()?.putString(KEY_TIER, tier.name)?.apply()
        _currentTier.value = tier
    }

    /**
     * Retorna quantos milissegundos restam para o usuário poder enviar outra transmissão.
     * 0L significa que o envio está liberado.
     */
    fun getRemainingCooldownMs(): Long {
        val lastBroadcast = if (prefs != null) prefs.getLong(KEY_LAST_BROADCAST_MS, 0L) else inMemoryLastBroadcastMs
        if (lastBroadcast <= 0L) return 0L

        val cooldown = if (isVip()) COOLDOWN_PRO_MS else COOLDOWN_FREE_MS
        val elapsed = System.currentTimeMillis() - lastBroadcast
        val remaining = cooldown - elapsed
        return if (remaining > 0L) remaining else 0L
    }

    /**
     * Valida se a quantidade de destinatários pretendida está dentro do limite do plano.
     */
    fun canSendToRecipientCount(count: Int): Boolean {
        return count in 1.._currentTier.value.maxBroadcastRecipients
    }

    /**
     * Valida se o usuário pode criar mais um lembrete recorrente.
     */
    fun canCreateMoreReminders(currentActiveCount: Int): Boolean {
        return currentActiveCount < _currentTier.value.maxReminders
    }

    /**
     * Registra que uma transmissão foi concluída para ativar o resfriamento anti-spam.
     */
    fun recordBroadcastExecuted() {
        val now = System.currentTimeMillis()
        inMemoryLastBroadcastMs = now
        prefs?.edit()?.putLong(KEY_LAST_BROADCAST_MS, now)?.apply()
    }
}
