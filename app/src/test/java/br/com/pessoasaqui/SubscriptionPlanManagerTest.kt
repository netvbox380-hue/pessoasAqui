package br.com.pessoasaqui

import br.com.pessoasaqui.core.subscription.SubscriptionPlanManager
import br.com.pessoasaqui.core.subscription.SubscriptionTier
import org.junit.Assert.*
import org.junit.Test

class SubscriptionPlanManagerTest {

    @Test
    fun testDefaultTierIsFreeWithCorrectLimits() {
        val manager = SubscriptionPlanManager()
        assertEquals("Nível padrão deve ser FREE", SubscriptionTier.FREE, manager.currentTier.value)
        assertEquals("Limite Free é 3 destinatários", 3, manager.currentTier.value.maxBroadcastRecipients)
        assertEquals("Cooldown Free é 15 minutos", 15 * 60 * 1000L, manager.currentTier.value.broadcastCooldownMs)
        assertNull("SKU deve ser nulo no plano gratuito", manager.currentTier.value.playStoreSkuId)

        assertTrue("Free permite até 3 contatos", manager.canSendToRecipientCount(3))
        assertFalse("Free bloqueia 4 ou mais contatos", manager.canSendToRecipientCount(4))
        assertFalse("Não é VIP por padrão", manager.isVip())
    }

    @Test
    fun testUpgradeToVipTierAppliesLimits() {
        val manager = SubscriptionPlanManager()
        manager.setSubscriptionTier(SubscriptionTier.PRO_VIP)

        assertEquals("Nível agora é PRO_VIP", SubscriptionTier.PRO_VIP, manager.currentTier.value)
        assertTrue("isVip deve ser true", manager.isVip())
        assertEquals("Limite VIP é 100 destinatários", 100, manager.currentTier.value.maxBroadcastRecipients)
        assertEquals("Cooldown VIP é 3 minutos", 3 * 60 * 1000L, manager.currentTier.value.broadcastCooldownMs)
        assertEquals("SKU correto para Play Billing", "pessoasaqui_vip_monthly_v1", manager.currentTier.value.playStoreSkuId)

        assertTrue("VIP permite 50 contatos", manager.canSendToRecipientCount(50))
        assertTrue("VIP permite 100 contatos", manager.canSendToRecipientCount(100))
        assertFalse("VIP bloqueia mais de 100 contatos", manager.canSendToRecipientCount(101))
    }

    @Test
    fun testBroadcastCooldownEnforcement() {
        val manager = SubscriptionPlanManager()
        assertEquals("Sem transmissões anteriores, cooldown é zero", 0L, manager.getRemainingCooldownMs())

        // Executa uma transmissão
        manager.recordBroadcastExecuted()
        val remaining = manager.getRemainingCooldownMs()
        assertTrue("Deve haver cooldown ativo após o envio", remaining > 0L)
        assertTrue("Cooldown Free deve ser próximo de 15 minutos", remaining <= 15 * 60 * 1000L && remaining > 14 * 60 * 1000L)
    }

    @Test
    fun testVipHasShorterCooldown() {
        val manager = SubscriptionPlanManager()
        manager.setSubscriptionTier(SubscriptionTier.PRO_VIP)
        manager.recordBroadcastExecuted()

        val remaining = manager.getRemainingCooldownMs()
        assertTrue("Cooldown VIP deve ser menor ou igual a 3 minutos", remaining <= 3 * 60 * 1000L)
        assertTrue("Cooldown VIP é maior que 2 minutos logo após disparo", remaining > 2 * 60 * 1000L)
    }

    @Test
    fun testRemindersQuotaLimits() {
        val manager = SubscriptionPlanManager()
        // Free permite até 3 lembretes
        assertTrue(manager.canCreateMoreReminders(0))
        assertTrue(manager.canCreateMoreReminders(2))
        assertFalse(manager.canCreateMoreReminders(3))

        // VIP permite até 999 lembretes
        manager.setSubscriptionTier(SubscriptionTier.PRO_VIP)
        assertTrue(manager.canCreateMoreReminders(3))
        assertTrue(manager.canCreateMoreReminders(100))
        assertFalse(manager.canCreateMoreReminders(999))
    }
}
