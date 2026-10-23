package br.com.pessoasaqui.domain.model

import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Frequência de repetição do lembrete ou mensagem agendada.
 */
enum class ReminderFrequency(val label: String) {
    UNICO("Único (Sem repetição)"),
    DIARIO("Diário"),
    SEMANAL("Semanal"),
    MENSAL("Mensal"),
    ANUAL("Anual")
}

/**
 * Representa um Lembrete ou Mensagem Agendada (Única ou Recorrente)
 * para contatos, familiares mútuos ou uso pessoal.
 */
data class RecurringReminder(
    val id: String = UUID.randomUUID().toString(),
    val targetId: String, // Identificador único da pessoa ou familiar
    val targetAlias: String, // Nome ou apelido
    val targetFamilyRole: FamilyRole? = null,
    val title: String, // Ex: "Tomar remédio", "Aniversário", "Reunião de família"
    val messageText: String, // Mensagem a ser enviada ou lembrada
    val frequency: ReminderFrequency = ReminderFrequency.UNICO,
    val hour: Int = 9, // 0..23
    val minute: Int = 0, // 0..59
    val dayOfWeek: Int? = null, // Calendar.SUNDAY..Calendar.SATURDAY (para SEMANAL)
    val dayOfMonth: Int? = null, // 1..31 (para UNICO, MENSAL ou ANUAL)
    val month: Int? = null, // Calendar.JANUARY..Calendar.DECEMBER (0..11) (para UNICO ou ANUAL)
    val year: Int? = null, // Ano específico (ex: 2026, 2027) para UNICO ou data de início
    val autoSend: Boolean = false, // True se deve enviar automaticamente no chat privado E2EE
    val isPersistentUntilConfirmed: Boolean = false, // Se verdadeiro, repete diariamente por até 3 dias consecutivos caso não confirmado
    val retryCount: Int = 0, // Contagem de repetições realizadas (0..3)
    val isConfirmed: Boolean = false, // True se o usuário confirmou/leu o lembrete
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
        obj.put("year", year ?: -1)
        obj.put("autoSend", autoSend)
        obj.put("isPersistentUntilConfirmed", isPersistentUntilConfirmed)
        obj.put("retryCount", retryCount)
        obj.put("isConfirmed", isConfirmed)
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

                val freqStr = obj.optString("frequency", ReminderFrequency.UNICO.name)
                val freq = try { ReminderFrequency.valueOf(freqStr) } catch (_: Exception) { ReminderFrequency.UNICO }

                val dow = obj.optInt("dayOfWeek", -1).takeIf { it != -1 }
                val dom = obj.optInt("dayOfMonth", -1).takeIf { it != -1 }
                val mon = obj.optInt("month", -1).takeIf { it != -1 }
                val yr = obj.optInt("year", -1).takeIf { it != -1 }
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
                    year = yr,
                    autoSend = obj.optBoolean("autoSend", false),
                    isPersistentUntilConfirmed = obj.optBoolean("isPersistentUntilConfirmed", false),
                    retryCount = obj.optInt("retryCount", 0),
                    isConfirmed = obj.optBoolean("isConfirmed", false),
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
         * Suporta:
         * - Disparos ÚNICOS (sem repetição) para qualquer data/hora futura arbitrária (amanhã, mês que vem, 2027, etc.).
         * - Disparos RECORRENTES (Diário, Semanal, Mensal, Anual) iniciando a partir de qualquer data futura (ex: 24/08/2027).
         */
        fun calculateNextTrigger(
            frequency: ReminderFrequency,
            hour: Int,
            minute: Int,
            dayOfWeek: Int? = null,
            dayOfMonth: Int? = null,
            month: Int? = null,
            year: Int? = null,
            afterEpochMs: Long = System.currentTimeMillis()
        ): Long {
            val nowCal = Calendar.getInstance().apply { timeInMillis = afterEpochMs }
            val currentYear = nowCal.get(Calendar.YEAR)
            val currentMonth = nowCal.get(Calendar.MONTH)
            val currentDom = nowCal.get(Calendar.DAY_OF_MONTH)

            val targetYear = year ?: currentYear
            val targetMonth = month ?: currentMonth
            val targetDom = dayOfMonth ?: currentDom

            // Constrói o calendário do início pretendido
            val targetCal = Calendar.getInstance().apply {
                set(Calendar.YEAR, targetYear)
                set(Calendar.MONTH, targetMonth)
                val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                set(Calendar.DAY_OF_MONTH, minOf(targetDom, maxDay))
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            when (frequency) {
                ReminderFrequency.UNICO -> {
                    // Disparo único: exatamente na data e hora selecionadas.
                    // Se a data já passou do momento de cálculo, retorna 0L (não há próximo disparo).
                    return if (targetCal.timeInMillis > afterEpochMs) targetCal.timeInMillis else 0L
                }

                ReminderFrequency.DIARIO -> {
                    // Se a data de início estiver no futuro (ex: a partir de 24/08/2027),
                    // o primeiro disparo ocorrerá exatamente nessa data futura.
                    if (targetCal.timeInMillis > afterEpochMs) {
                        return targetCal.timeInMillis
                    }
                    // Caso a data inicial já tenha chegado ou passado, repete hoje ou amanhã no horário especificado
                    val nextCal = Calendar.getInstance().apply {
                        timeInMillis = afterEpochMs
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= afterEpochMs) {
                            add(Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                    return nextCal.timeInMillis
                }

                ReminderFrequency.SEMANAL -> {
                    val targetDow = dayOfWeek ?: targetCal.get(Calendar.DAY_OF_WEEK)
                    if (targetCal.timeInMillis > afterEpochMs) {
                        // Data de início no futuro: avança a partir dela até o dia da semana desejado
                        while (targetCal.get(Calendar.DAY_OF_WEEK) != targetDow) {
                            targetCal.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        return targetCal.timeInMillis
                    } else {
                        val nextCal = Calendar.getInstance().apply {
                            timeInMillis = afterEpochMs
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                            while (timeInMillis <= afterEpochMs || get(Calendar.DAY_OF_WEEK) != targetDow) {
                                add(Calendar.DAY_OF_YEAR, 1)
                            }
                        }
                        return nextCal.timeInMillis
                    }
                }

                ReminderFrequency.MENSAL -> {
                    if (targetCal.timeInMillis > afterEpochMs) {
                        return targetCal.timeInMillis
                    }
                    val nextCal = Calendar.getInstance().apply {
                        timeInMillis = afterEpochMs
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        val maxDom = getActualMaximum(Calendar.DAY_OF_MONTH)
                        set(Calendar.DAY_OF_MONTH, minOf(targetDom, maxDom))
                        if (timeInMillis <= afterEpochMs) {
                            add(Calendar.MONTH, 1)
                            val nextMax = getActualMaximum(Calendar.DAY_OF_MONTH)
                            set(Calendar.DAY_OF_MONTH, minOf(targetDom, nextMax))
                        }
                    }
                    return nextCal.timeInMillis
                }

                ReminderFrequency.ANUAL -> {
                    if (targetCal.timeInMillis > afterEpochMs) {
                        return targetCal.timeInMillis
                    }
                    val nextCal = Calendar.getInstance().apply {
                        timeInMillis = afterEpochMs
                        set(Calendar.MONTH, targetMonth)
                        val maxDom = getActualMaximum(Calendar.DAY_OF_MONTH)
                        set(Calendar.DAY_OF_MONTH, minOf(targetDom, maxDom))
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        while (timeInMillis <= afterEpochMs) {
                            add(Calendar.YEAR, 1)
                            val nextMax = getActualMaximum(Calendar.DAY_OF_MONTH)
                            set(Calendar.DAY_OF_MONTH, minOf(targetDom, nextMax))
                        }
                    }
                    return nextCal.timeInMillis
                }
            }
        }
    }
}
