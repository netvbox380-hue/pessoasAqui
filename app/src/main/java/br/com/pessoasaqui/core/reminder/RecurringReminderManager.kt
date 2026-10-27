package br.com.pessoasaqui.core.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import br.com.pessoasaqui.MainActivity
import br.com.pessoasaqui.domain.model.RecurringReminder
import br.com.pessoasaqui.domain.model.ReminderFrequency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class RecurringReminderManager(private val context: Context) {

    companion object {
        private const val TAG = "RecurringReminderMgr"
        private const val PREFS_KEY = "saved_recurring_reminders_v1"
        const val CHANNEL_ID = "pessoasaqui_reminders_channel"
        const val NOTIFICATION_BASE_ID = 9000
    }

    private val prefs = context.getSharedPreferences("pessoasaqui_prefs", Context.MODE_PRIVATE)

    private val _reminders = MutableStateFlow<List<RecurringReminder>>(emptyList())
    val reminders: StateFlow<List<RecurringReminder>> = _reminders.asStateFlow()

    init {
        ensureNotificationChannel()
        loadRemindersFromPrefs()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lembretes e Mensagens Recorrentes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações de lembretes diários, semanais, mensais e anuais para você e seus familiares"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 150, 350)
                setShowBadge(true)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun loadRemindersFromPrefs() {
        val jsonStr = prefs.getString(PREFS_KEY, null) ?: return
        try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<RecurringReminder>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val item = RecurringReminder.fromJson(obj)
                if (item != null) {
                    list.add(item)
                }
            }
            _reminders.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar lembretes persistidos: ${e.message}")
        }
    }

    private fun saveRemindersToPrefs() {
        try {
            val array = JSONArray()
            _reminders.value.forEach { item ->
                array.put(item.toJson())
            }
            prefs.edit().putString(PREFS_KEY, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar lembretes persistidos: ${e.message}")
        }
    }

    private fun persistMediaToDisk(reminderId: String, mediaBase64: String): String? {
        return try {
            val dir = context.filesDir.resolve("reminder_media")
            if (!dir.exists()) dir.mkdirs()
            val file = dir.resolve("${reminderId}.b64")
            file.writeText(mediaBase64, Charsets.UTF_8)
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gravar mídia do lembrete no disco: ${e.message}")
            null
        }
    }

    fun readMediaForReminder(reminder: RecurringReminder): String? {
        if (!reminder.mediaBase64.isNullOrBlank()) return reminder.mediaBase64
        if (reminder.mediaFilePath != null) {
            val f = java.io.File(reminder.mediaFilePath)
            if (f.exists()) {
                return try { f.readText(Charsets.UTF_8) } catch (_: Exception) { null }
            }
        }
        val defaultFile = context.filesDir.resolve("reminder_media/${reminder.id}.b64")
        if (defaultFile.exists()) {
            return try { defaultFile.readText(Charsets.UTF_8) } catch (_: Exception) { null }
        }
        return null
    }

    fun saveReminder(reminder: RecurringReminder) {
        val currentList = _reminders.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == reminder.id }

        // Persiste fisicamente o payload de mídia em arquivo permanente local
        val localFilePath = if (!reminder.mediaBase64.isNullOrBlank()) {
            persistMediaToDisk(reminder.id, reminder.mediaBase64)
        } else {
            reminder.mediaFilePath
        }

        // Garante que o nextTriggerEpochMs esteja calculado estritamente no futuro
        val updatedReminder = if (reminder.nextTriggerEpochMs <= System.currentTimeMillis()) {
            val next = RecurringReminder.calculateNextTrigger(
                frequency = reminder.frequency,
                hour = reminder.hour,
                minute = reminder.minute,
                dayOfWeek = reminder.dayOfWeek,
                dayOfMonth = reminder.dayOfMonth,
                month = reminder.month,
                year = reminder.year,
                afterEpochMs = System.currentTimeMillis()
            )
            reminder.copy(nextTriggerEpochMs = next, mediaFilePath = localFilePath)
        } else {
            reminder.copy(mediaFilePath = localFilePath)
        }

        if (index != -1) {
            currentList[index] = updatedReminder
        } else {
            currentList.add(updatedReminder)
        }

        _reminders.value = currentList
        saveRemindersToPrefs()
        scheduleNextAlarm()
    }

    fun deleteReminder(reminderId: String) {
        _reminders.value = _reminders.value.filter { it.id != reminderId }
        try {
            val f = context.filesDir.resolve("reminder_media/${reminderId}.b64")
            if (f.exists()) f.delete()
        } catch (_: Exception) {}
        saveRemindersToPrefs()
        scheduleNextAlarm()
    }

    fun toggleReminder(reminderId: String, isEnabled: Boolean) {
        val currentList = _reminders.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == reminderId }
        if (index != -1) {
            val item = currentList[index]
            val updated = if (isEnabled) {
                val next = RecurringReminder.calculateNextTrigger(
                    frequency = item.frequency,
                    hour = item.hour,
                    minute = item.minute,
                    dayOfWeek = item.dayOfWeek,
                    dayOfMonth = item.dayOfMonth,
                    month = item.month,
                    year = item.year,
                    afterEpochMs = System.currentTimeMillis()
                )
                item.copy(isEnabled = next > 0L, nextTriggerEpochMs = next)
            } else {
                item.copy(isEnabled = false)
            }
            currentList[index] = updated
            _reminders.value = currentList
            saveRemindersToPrefs()
            scheduleNextAlarm()
        }
    }

    /**
     * Confirma / conclui um lembrete (acionado pelo usuário ou pela notificação).
     * Se for um lembrete persistente ou de disparo único, encerra as repetições futuras.
     */
    fun confirmReminder(reminderId: String) {
        val currentList = _reminders.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == reminderId }
        if (index != -1) {
            val item = currentList[index]
            val updated = item.copy(
                isConfirmed = true,
                isEnabled = if (item.isPersistentUntilConfirmed || item.frequency == ReminderFrequency.UNICO) false else item.isEnabled
            )
            currentList[index] = updated
            _reminders.value = currentList
            saveRemindersToPrefs()
            scheduleNextAlarm()
            Log.i(TAG, "Lembrete confirmado com sucesso: ${item.title} ($reminderId)")
        }
    }

    /**
     * Verifica e dispara lembretes vencidos. Pode ser chamado periodicamente pelo serviço
     * em segundo plano ou pelo Receiver acionado pelo AlarmManager.
     */
    fun checkAndTriggerDueReminders(onSendAutoMessage: ((targetId: String, text: String, mediaBase64: String?, mediaType: String?) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        val currentList = _reminders.value.toMutableList()
        var hasChanges = false

        for (i in currentList.indices) {
            val reminder = currentList[i]
            if (reminder.isEnabled && reminder.nextTriggerEpochMs in 1..now) {
                // Dispara a notificação de sistema
                showReminderNotification(reminder)

                // Se envio automático de mensagem estiver ativo, envia no chat privado (com suporte a mídia persistida)
                if (reminder.autoSend && onSendAutoMessage != null && (reminder.messageText.isNotBlank() || !reminder.mediaBase64.isNullOrBlank() || reminder.mediaFilePath != null)) {
                    try {
                        val media = readMediaForReminder(reminder)
                        onSendAutoMessage(reminder.targetId, reminder.messageText, media, reminder.mediaType)
                        Log.i(TAG, "Mensagem de lembrete enviada automaticamente para ${reminder.targetAlias}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao enviar mensagem automática de lembrete: ${e.message}")
                    }
                }

                // Lógica de Persistência Inteligente (Repete diariamente por até 3 dias se não confirmado)
                if (reminder.isPersistentUntilConfirmed && !reminder.isConfirmed) {
                    val nextRetry = reminder.retryCount + 1
                    if (nextRetry < 3) {
                        // Agenda para o dia seguinte no mesmo horário (24 horas após o gatilho)
                        val nextDayCal = java.util.Calendar.getInstance().apply {
                            timeInMillis = reminder.nextTriggerEpochMs
                            add(java.util.Calendar.DAY_OF_YEAR, 1)
                        }
                        while (nextDayCal.timeInMillis <= now) {
                            nextDayCal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        }
                        currentList[i] = reminder.copy(
                            lastTriggerEpochMs = now,
                            retryCount = nextRetry,
                            nextTriggerEpochMs = nextDayCal.timeInMillis
                        )
                        hasChanges = true
                        Log.i(TAG, "Lembrete persistente pendente: agendada tentativa ${nextRetry + 1}/3 para ${nextDayCal.time}")
                    } else {
                        // 3 tentativas consecutivas concluídas sem confirmação
                        currentList[i] = reminder.copy(
                            lastTriggerEpochMs = now,
                            retryCount = 3,
                            nextTriggerEpochMs = 0L,
                            isEnabled = false
                        )
                        hasChanges = true
                        Log.i(TAG, "Lembrete persistente encerrou o ciclo de 3 tentativas sem confirmação.")
                    }
                } else if (reminder.frequency == ReminderFrequency.UNICO) {
                    // Mensagem/lembrete único disparado com sucesso: marca como concluído
                    currentList[i] = reminder.copy(
                        lastTriggerEpochMs = now,
                        nextTriggerEpochMs = 0L,
                        isEnabled = false
                    )
                    hasChanges = true
                } else {
                    // Calcula o próximo disparo para a recorrência contínua
                    val nextTrigger = RecurringReminder.calculateNextTrigger(
                        frequency = reminder.frequency,
                        hour = reminder.hour,
                        minute = reminder.minute,
                        dayOfWeek = reminder.dayOfWeek,
                        dayOfMonth = reminder.dayOfMonth,
                        month = reminder.month,
                        year = reminder.year,
                        afterEpochMs = now
                    )

                    currentList[i] = reminder.copy(
                        lastTriggerEpochMs = now,
                        nextTriggerEpochMs = nextTrigger
                    )
                    hasChanges = true
                }
            }
        }

        if (hasChanges) {
            _reminders.value = currentList
            saveRemindersToPrefs()
            scheduleNextAlarm()
        }
    }

    private fun showReminderNotification(reminder: RecurringReminder) {
        try {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_TARGET_PEER_ID", reminder.targetId)
                putExtra("OPEN_TARGET_PEER_ALIAS", reminder.targetAlias)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                reminder.id.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Intent de confirmação/conclusão direta pela notificação
            val confirmIntent = Intent(context, RecurringReminderReceiver::class.java).apply {
                action = RecurringReminderReceiver.ACTION_CONFIRM_REMINDER
                putExtra(RecurringReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
            }
            val confirmPendingIntent = PendingIntent.getBroadcast(
                context,
                ("confirm_" + reminder.id).hashCode(),
                confirmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val autoSentInfo = if (reminder.autoSend) " • Enviada no chat" else ""
            val roleInfo = reminder.targetFamilyRole?.let { " (${it.label})" } ?: ""
            val retryInfo = if (reminder.isPersistentUntilConfirmed && reminder.retryCount > 0) {
                " [Tentativa ${reminder.retryCount + 1}/3]"
            } else ""
            val hasMedia = !reminder.mediaBase64.isNullOrBlank() || reminder.mediaFilePath != null
            val mediaInfo = if (hasMedia) " [📷 Mídia Anexada]" else ""
            val notifContent = if (reminder.messageText.isNotBlank()) reminder.messageText else "Mídia agendada"

            val notifTitle = if (reminder.frequency == ReminderFrequency.UNICO) {
                "⏰ ${retryInfo}Mensagem Agendada: ${reminder.title}"
            } else {
                "⏰ ${retryInfo}Lembrete: ${reminder.title}"
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(notifTitle)
                .setContentText("Para: ${reminder.targetAlias}$roleInfo - $notifContent$mediaInfo")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("🔔 ${reminder.frequency.label}$autoSentInfo$retryInfo$mediaInfo\nDestinatário: ${reminder.targetAlias}$roleInfo\n\n\"$notifContent\"")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.checkbox_on_background,
                    "✓ Concluído",
                    confirmPendingIntent
                )

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notificationId = NOTIFICATION_BASE_ID + Math.abs(reminder.id.hashCode() % 1000)
            nm?.notify(notificationId, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao exibir notificação de lembrete: ${e.message}")
        }
    }

    /**
     * Agenda o próximo alarme com AlarmManager para despertar o sistema mesmo em Doze Mode.
     */
    fun scheduleNextAlarm() {
        val nextReminder = _reminders.value
            .filter { it.isEnabled && it.nextTriggerEpochMs > System.currentTimeMillis() }
            .minByOrNull { it.nextTriggerEpochMs }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, RecurringReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            7701,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (nextReminder != null) {
            val triggerAt = nextReminder.nextTriggerEpochMs
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                Log.d(TAG, "Próximo alarme de lembrete agendado para $triggerAt (${nextReminder.title})")
            } catch (e: Exception) {
                Log.w(TAG, "Aviso ao agendar alarme exato: ${e.message}")
            }
        } else {
            alarmManager.cancel(pendingIntent)
        }
    }
}
