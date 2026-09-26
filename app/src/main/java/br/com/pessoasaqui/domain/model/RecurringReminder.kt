package br.com.pessoasaqui.domain.model

import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Frequência de repetição do lembrete de mensagem.
 */
enum class ReminderFrequency(val label: String) {
    DIARIO("Diário"),
    SEMANAL("Semanal"),
    MENSAL("Mensal"),
    ANUAL("Anual")
}

/**
 * Representa um Lembrete de Mensagem Recorrente para contatos ou familiares mútuos.
 */
data class RecurringReminder(
    val id: String = UUID.randomUUID().toString(),
    val targetId: String, // Identificador único da pessoa ou familiar
    val targetAlias: String, // Nome ou apelido
    val targetFamilyRole: FamilyRole? = null,
    val title: String, // Ex: "Tomar remédio", "Aniversário", "Reunião de família"
    val messageText: String, // Mensagem a ser enviada ou lembrada
    val frequency: ReminderFrequency = ReminderFrequency.DIARIO,
    val hour: Int = 9, // 0..23
    val minute: Int = 0, // 0..59
    val dayOfWeek: Int? = null, // Calendar.SUNDAY..Calendar.SATURDAY (para SEMANAL)
    val dayOfMonth: Int? = null, // 1..31 (para MENSAL ou ANUAL)
    val month: Int? = null, // Calendar.JANUARY..Calendar.DECEMBER (para ANUAL)
    val autoSend: Boolean = false, // True se deve enviar automaticamente no chat privado E2EE
    val isEnabled: Boolean = true,
    val nextTriggerEpochMs: Long = 0L,
    val lastTriggerEpochMs: Long? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("targetId", targetId)
        obj.put("targetAlias", targetAlias)
        obj.put("targetFamilyRole", targetFamilyRole?.name ?: "")
        obj.put("title", title)
        obj.put("messageText", messageText)
        obj.put("frequency", frequency.name)
        obj.put("hour", hour)
        obj.put("minute", minute)
        obj.put("dayOfWeek", dayOfWeek ?: -1)
        obj.put("dayOfMonth", dayOfMonth ?: -1)
        obj.put("month", month ?: -1)
        obj.put("autoSend", autoSend)
        obj.put("isEnabled", isEnabled)
        obj.put("nextTriggerEpochMs", nextTriggerEpochMs)
        obj.put("lastTriggerEpochMs", lastTriggerEpochMs ?: -1L)
        obj.put("createdAtEpochMs", createdAtEpochMs)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): RecurringReminder? {
            return try {
                val roleStr = obj.optString("targetFamilyRole", "")
                val role = if (roleStr.isNotBlank()) {
                    try { FamilyRole.valueOf(roleStr) } catch (_: Exception) { null }
                } else null

                val freqStr = obj.optString("frequency", ReminderFrequency.DIARIO.name)
                val freq = try { ReminderFrequency.valueOf(freqStr) } catch (_: Exception) { ReminderFrequency.DIARIO }

                val dow = obj.optInt("dayOfWeek", -1).takeIf { it != -1 }
                val dom = obj.optInt("dayOfMonth", -1).takeIf { it != -1 }
                val mon = obj.optInt("month", -1).takeIf { it != -1 }
                val lastTrig = obj.optLong("lastTriggerEpochMs", -1L).takeIf { it != -1L }

                RecurringReminder(
                    id = obj.getString("id"),
                    targetId = obj.getString("targetId"),
                    targetAlias = obj.optString("targetAlias", "Contato"),
                    targetFamilyRole = role,
                    title = obj.optString("title", "Lembrete"),
                    messageText = obj.optString("messageText", ""),
                    frequency = freq,
                    hour = obj.optInt("hour", 9),
                    minute = obj.optInt("minute", 0),
                    dayOfWeek = dow,
                    dayOfMonth = dom,
                    month = mon,
                    autoSend = obj.optBoolean("autoSend", false),
                    isEnabled = obj.optBoolean("isEnabled", true),
                    nextTriggerEpochMs = obj.optLong("nextTriggerEpochMs", 0L),
                    lastTriggerEpochMs = lastTrig,
                    createdAtEpochMs = obj.optLong("createdAtEpochMs", System.currentTimeMillis())
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Calcula o próximo momento de disparo estritamente no futuro (após [afterEpochMs]).
         */
        fun calculateNextTrigger(
            frequency: ReminderFrequency,
            hour: Int,
            minute: Int,
            dayOfWeek: Int? = null,
            dayOfMonth: Int? = null,
            month: Int? = null,
            afterEpochMs: Long = System.currentTimeMillis()
        ): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = afterEpochMs
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            when (frequency) {
                ReminderFrequency.DIARIO -> {
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    if (cal.timeInMillis <= afterEpochMs) {
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                    }
                }

                ReminderFrequency.SEMANAL -> {
                    val targetDow = dayOfWeek ?: Calendar.MONDAY
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    // Avança dia a dia até encontrar o dia da semana correto estritamente no futuro
                    while (cal.timeInMillis <= afterEpochMs || cal.get(Calendar.DAY_OF_WEEK) != targetDow) {
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                    }
                }

                ReminderFrequency.MENSAL -> {
                    val targetDom = dayOfMonth ?: 1
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    val maxDom = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, minOf(targetDom, maxDom))
                    if (cal.timeInMillis <= afterEpochMs) {
                        cal.add(Calendar.MONTH, 1)
                        val nextMaxDom = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        cal.set(Calendar.DAY_OF_MONTH, minOf(targetDom, nextMaxDom))
                    }
                }

                ReminderFrequency.ANUAL -> {
                    val targetMonth = month ?: Calendar.JANUARY
                    val targetDom = dayOfMonth ?: 1
                    cal.set(Calendar.MONTH, targetMonth)
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    val maxDom = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, minOf(targetDom, maxDom))
                    if (cal.timeInMillis <= afterEpochMs) {
                        cal.add(Calendar.YEAR, 1)
                        val nextMaxDom = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        cal.set(Calendar.DAY_OF_MONTH, minOf(targetDom, nextMaxDom))
                    }
                }
            }
            return cal.timeInMillis
        }
    }
}
