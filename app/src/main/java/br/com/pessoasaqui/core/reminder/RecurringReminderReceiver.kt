package br.com.pessoasaqui.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.pessoasaqui.PessoasAquiApp

/**
 * BroadcastReceiver acionado pelo AlarmManager quando um lembrete vence.
 */
class RecurringReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PessoasAquiApp ?: return
        val repository = app.repository
        repository.triggerDueRemindersNow()
    }
}
