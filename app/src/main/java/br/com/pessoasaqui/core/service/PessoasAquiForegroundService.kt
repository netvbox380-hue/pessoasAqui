package br.com.pessoasaqui.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import br.com.pessoasaqui.MainActivity
import br.com.pessoasaqui.PessoasAquiApp
import kotlinx.coroutines.*

/**
 * Serviço em Primeiro Plano (Foreground Service) que mantém o WebSocket E2EE,
 * notificações e chamadas em segundo plano. Sem WakeLocks permanentes,
 * a execução pode ser adiada pelo Doze; validar recebimento em tela apagada.
 */
class PessoasAquiForegroundService : Service() {

    companion object {
        private const val TAG = "PessoasAquiFgService"
        private const val CHANNEL_ID = "pessoasaqui_background_service_v1"
        private const val NOTIFICATION_ID = 8001

        fun startServiceSafely(context: Context) {
            try {
                val intent = Intent(context, PessoasAquiForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Aviso ao iniciar ForegroundService: ${e.message}")
            }
        }
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var reminderTickerJob: kotlinx.coroutines.Job? = null
    private var lastVaultCheckMs = 0L

    override fun onCreate() {
        super.onCreate()
        ensureServiceChannel()
        startForegroundCompat()
        startReminderTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        startReminderTicker()
        // Garante que o repositório singleton esteja inicializado
        (application as? PessoasAquiApp)?.repository
        return START_STICKY
    }

    private fun startReminderTicker() {
        if (reminderTickerJob?.isActive == true) return
        reminderTickerJob = serviceScope.launch {
            while (isActive) {
                try {
                    val repo = (application as? PessoasAquiApp)?.repository
                    repo?.triggerDueRemindersNow()

                    // Verifica se há backup silencioso de cofre pendente (a cada 15 minutos)
                    val now = System.currentTimeMillis()
                    if (now - lastVaultCheckMs > 15 * 60 * 1000L) {
                        lastVaultCheckMs = now
                        repo?.executeSilentBackgroundBackupIfDue()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Erro no ticker de lembretes e cofre: ${e.message}")
                }
                delay(30_000L) // Checa a cada 30 segundos
            }
        }
    }

    private fun ensureServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Conexão Permanente e Recebimento de Chamadas",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o aplicativo pronto para despertar e tocar em ligações recebidas com tela apagada"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        try {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                801,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("PessoasAqui • Proteção e Chamadas Ativas")
                .setContentText("Pronto para receber ligações e mensagens criptografadas (E2EE)")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: este serviço mantém presença e comunicação BLE.
                // O tipo conectado corresponde ao trabalho executado; não usar
                // dataSync como substituto para rádio Bluetooth.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro em startForegroundCompat: ${e.message}")
        }
    }

    // A execução do serviço não deve impedir o repouso da CPU e do Wi-Fi.
    // Se chamadas exigirem WakeLock, usar lock específico de duração limitada
    // com liberação em finally (nunca no ciclo inteiro do serviço).
    override fun onDestroy() {
        try {
            reminderTickerJob?.cancel()
            serviceScope.cancel()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
