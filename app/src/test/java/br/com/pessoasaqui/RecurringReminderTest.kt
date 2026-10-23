package br.com.pessoasaqui

import br.com.pessoasaqui.domain.model.RecurringReminder
import br.com.pessoasaqui.domain.model.ReminderFrequency
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class RecurringReminderTest {

    @Test
    fun testSerializationWithPersistenceAndConfirmation() {
        val original = RecurringReminder(
            id = "rem-test-001",
            targetId = "peer-abc-123",
            targetAlias = "Carlos",
            title = "Tomar Remédio",
            messageText = "Hora do medicamento diário",
            frequency = ReminderFrequency.UNICO,
            hour = 14,
            minute = 0,
            autoSend = true,
            isPersistentUntilConfirmed = true,
            retryCount = 1,
            isConfirmed = false,
            isEnabled = true,
            nextTriggerEpochMs = 1793638800000L
        )

        val json = original.toJson()
        val deserialized = RecurringReminder.fromJson(json)

        assertNotNull(deserialized)
        assertEquals(original.id, deserialized?.id)
        assertEquals(original.title, deserialized?.title)
        assertEquals(original.messageText, deserialized?.messageText)
        assertEquals(original.isPersistentUntilConfirmed, deserialized?.isPersistentUntilConfirmed)
        assertEquals(original.retryCount, deserialized?.retryCount)
        assertEquals(original.isConfirmed, deserialized?.isConfirmed)
        assertEquals(original.isEnabled, deserialized?.isEnabled)
        assertEquals(original.autoSend, deserialized?.autoSend)
    }

    @Test
    fun testBackwardCompatibilityDefaults() {
        val json = org.json.JSONObject().apply {
            put("id", "legacy-rem-1")
            put("targetId", "peer-999")
            put("targetAlias", "Ana")
            put("title", "Reunião")
            put("messageText", "Não esqueça a reunião")
            put("frequency", "DIARIO")
            put("hour", 10)
            put("minute", 30)
        }

        val deserialized = RecurringReminder.fromJson(json)
        assertNotNull(deserialized)
        assertFalse("isPersistentUntilConfirmed deve ser false por padrão", deserialized!!.isPersistentUntilConfirmed)
        assertEquals("retryCount deve ser 0 por padrão", 0, deserialized.retryCount)
        assertFalse("isConfirmed deve ser false por padrão", deserialized.isConfirmed)
    }

    @Test
    fun testNextTriggerCalculationFutureDate() {
        val futureYear = 2027
        val trigger = RecurringReminder.calculateNextTrigger(
            frequency = ReminderFrequency.UNICO,
            hour = 15,
            minute = 30,
            dayOfMonth = 10,
            month = Calendar.NOVEMBER,
            year = futureYear,
            afterEpochMs = System.currentTimeMillis()
        )

        assertTrue("Gatilho deve ser no futuro", trigger > System.currentTimeMillis())

        val cal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(futureYear, cal.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, cal.get(Calendar.MONTH))
        assertEquals(10, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }
}
