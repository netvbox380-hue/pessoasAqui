package br.com.pessoasaqui.core.reminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.pessoasaqui.PessoasAquiApp

/**
 * BroadcastReceiver acionado pelo AlarmManager quando um lembrete vence
 * ou quando o usuário clica em "Concluído" diretamente na notificação.
 */
class RecurringReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIRM_REMINDER = "br.com.pessoasaqui.ACTION_CONFIRM_REMINDER"
        const val EXTRA_REMINDER_ID = "EXTRA_REMINDER_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PessoasAquiApp ?: return
        val repository = app.repository

        if (intent.action == ACTION_CONFIRM_REMINDER) {
            val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
            repository.confirmRecurringReminder(reminderId)

            // Cancela a notificação de sistema correspondente
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notificationId = RecurringReminderManager.NOTIFICATION_BASE_ID + Math.abs(reminderId.hashCode() % 1000)
            nm?.cancel(notificationId)
        } else {
            repository.triggerDueRemindersNow()
        }
    }
}
