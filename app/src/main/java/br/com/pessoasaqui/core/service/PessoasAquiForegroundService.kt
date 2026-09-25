package br.com.pessoasaqui.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import br.com.pessoasaqui.MainActivity
import br.com.pessoasaqui.PessoasAquiApp

/**
 * Serviço em Primeiro Plano (Foreground Service) que mantém o WebSocket E2EE,
 * a escuta de chamadas de voz/vídeo e o radar ativos mesmo quando o celular
 * está bloqueado, com a tela apagada (Doze Mode) ou em segundo plano.
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

    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureServiceChannel()
        startForegroundCompat()
        acquireBackgroundLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        acquireBackgroundLocks()
        // Garante que o repositório singleton esteja inicializado
        (application as? PessoasAquiApp)?.repository
        return START_STICKY
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    @Suppress("DEPRECATION")
    private fun acquireBackgroundLocks() {
        try {
            if (cpuWakeLock == null || cpuWakeLock?.isHeld == false) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                cpuWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PessoasAqui::BackgroundCpuWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wifiLock == null || wifiLock?.isHeld == false) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, "PessoasAqui::RealtimeWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao adquirir locks de segundo plano: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            cpuWakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
