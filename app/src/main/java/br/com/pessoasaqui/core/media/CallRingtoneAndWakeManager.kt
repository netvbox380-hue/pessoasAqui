package br.com.pessoasaqui.core.media

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import br.com.pessoasaqui.MainActivity
import kotlinx.coroutines.*

/**
 * Gerenciador nativo de Toque de Chamada (Ringtone), Tom de Chamando (Ringback),
 * Vibração, Despertar de Tela Apagada/Bloqueada (WakeLock + FullScreenIntent)
 * e Notificações Sonoras em Segundo Plano.
 */
class CallRingtoneAndWakeManager(private val context: Context) {

    companion object {
        private const val TAG = "CallRingtoneWakeMgr"
        const val CALL_CHANNEL_ID = "pessoasaqui_incoming_calls_v2"
        const val MSG_CHANNEL_ID = "pessoasaqui_messages_v2"
        const val INCOMING_CALL_NOTIFICATION_ID = 9001

        const val ACTION_INCOMING_CALL = "br.com.pessoasaqui.ACTION_INCOMING_CALL"
        const val ACTION_ANSWER_CALL = "br.com.pessoasaqui.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "br.com.pessoasaqui.ACTION_DECLINE_CALL"
        const val EXTRA_CALLER_HASH = "extra_caller_hash"
        const val EXTRA_CALLER_ALIAS = "extra_caller_alias"
        const val EXTRA_IS_VIDEO = "extra_is_video"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var ringtonePlayer: MediaPlayer? = null
    private var ringbackGenerator: ToneGenerator? = null
    private var ringbackJob: Job? = null
    private var timeoutJob: Job? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    var isAppInForeground: Boolean = true

    init {
        ensureNotificationChannels()
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val callAudioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                "Ligações Recebidas (Áudio e Vídeo)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Toque e alerta em tela cheia para ligações recebidas com tela bloqueada ou apagada"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 800, 1000)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(ringtoneUri, callAudioAttrs)
                setBypassDnd(true)
            }

            val msgUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val msgAudioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val msgChannel = NotificationChannel(
                MSG_CHANNEL_ID,
                "Mensagens Criptografadas e Conexões",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas sonoros de novas mensagens de texto, áudio, fotos e conexões mútuas"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(msgUri, msgAudioAttrs)
            }

            notificationManager.createNotificationChannel(callChannel)
            notificationManager.createNotificationChannel(msgChannel)
        }
    }

    /**
     * Acorda o aparelho imediatamente quando uma chamada chega (mesmo com tela apagada/bloqueada),
     * toca o Ringtone padrão de telefone do Android, vibra continuamente e abre a interface de chamada.
     */
    fun onIncomingCallReceived(
        callerHash: String,
        callerAlias: String,
        isVideo: Boolean,
        onAutoTimeout: () -> Unit
    ) {
        Log.i(TAG, "Acionando despertar de chamada recebida de $callerAlias (video=$isVideo)")
        stopOutgoingRingbackTone()

        // 1. Acorda a CPU e acende a tela física imediatamente
        wakeUpScreenForIncomingCall()

        // 2. Inicia o toque de telefone (Ringtone nativo do Android) + Vibração contínua
        startIncomingRingtoneAndVibration()

        // 3. Dispara FullScreenIntent Notification (acorda sobre a tela de bloqueio)
        showIncomingCallNotification(callerHash, callerAlias, isVideo)

        // 4. Se o celular estiver com a tela apagada ou app em background, traz a MainActivity para frente
        launchCallActivityOverLockscreen(callerHash, callerAlias, isVideo)

        // 5. Timeout de segurança de 45 segundos caso ninguém atenda
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(45_000L)
            stopAllCallAlerts()
            onAutoTimeout()
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpScreenForIncomingCall() {
        try {
            releaseScreenWakeLock()
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE
            screenWakeLock = powerManager.newWakeLock(flags, "PessoasAqui::IncomingCallWakeLock").apply {
                setReferenceCounted(false)
                acquire(45_000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao adquirir WakeLock de tela: ${e.message}")
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            screenWakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        screenWakeLock = null
    }

    private fun startIncomingRingtoneAndVibration() {
        stopIncomingRingtoneAndVibration()

        // Inicia o som do toque de chamada (Ringtone oficial configurado no Android do usuário)
        try {
            val ringerMode = audioManager.ringerMode
            if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

                if (ringtoneUri != null) {
                    ringtonePlayer = MediaPlayer().apply {
                        setDataSource(context, ringtoneUri)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        isLooping = true
                        prepare()
                        start()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback para ToneGenerator no toque de chamada: ${e.message}")
            startFallbackRingtoneBeep()
        }

        // Inicia a vibração contínua (se não estiver no modo totalmente silencioso)
        try {
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                val vibrator = getVibrator()
                val pattern = longArrayOf(0, 1000, 800, 1000, 800)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao vibrar na chamada: ${e.message}")
        }
    }

    private fun startFallbackRingtoneBeep() {
        ringbackJob?.cancel()
        ringbackJob = scope.launch(Dispatchers.Default) {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_RING, 100)
                ringbackGenerator = tg
                while (isActive) {
                    tg.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1200)
                    delay(2200L)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Toca o som clássico de "chamando..." (tuuu... tuuu...) no alto-falante auricular
     * para quem iniciou a chamada enquanto aguarda o outro aparelho atender.
     */
    fun startOutgoingRingbackTone() {
        stopOutgoingRingbackTone()
        ringbackJob = scope.launch(Dispatchers.Default) {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 75)
                ringbackGenerator = tg
                while (isActive) {
                    tg.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000)
                    delay(3000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao reproduzir ringback tone: ${e.message}")
            }
        }
    }

    fun stopOutgoingRingbackTone() {
        ringbackJob?.cancel()
        ringbackJob = null
        try {
            ringbackGenerator?.stopTone()
            ringbackGenerator?.release()
        } catch (_: Exception) {}
        ringbackGenerator = null
    }

    private fun stopIncomingRingtoneAndVibration() {
        try {
            ringtonePlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        ringtonePlayer = null

        try {
            getVibrator()?.cancel()
        } catch (_: Exception) {}
    }

    /**
     * Para imediatamente todos os toques, vibrações, wake locks e notificações de chamada
     * (chamado ao atender, recusar ou encerrar a ligação).
     */
    fun stopAllCallAlerts() {
        timeoutJob?.cancel()
        timeoutJob = null
        stopIncomingRingtoneAndVibration()
        stopOutgoingRingbackTone()
        cancelIncomingCallNotification()
        releaseScreenWakeLock()
    }

    private fun showIncomingCallNotification(callerHash: String, callerAlias: String, isVideo: Boolean) {
        try {
            val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_INCOMING_CALL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CALLER_HASH, callerHash)
                putExtra(EXTRA_CALLER_ALIAS, callerAlias)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                101,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val answerIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_ANSWER_CALL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CALLER_HASH, callerHash)
                putExtra(EXTRA_CALLER_ALIAS, callerAlias)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
            val answerPendingIntent = PendingIntent.getActivity(
                context,
                102,
                answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val declineIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_DECLINE_CALL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CALLER_HASH, callerHash)
            }
            val declinePendingIntent = PendingIntent.getActivity(
                context,
                103,
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val callTypeLabel = if (isVideo) "Chamada de Vídeo Criptografada" else "Chamada de Voz Criptografada"

            val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("📞 $callerAlias está ligando")
                .setContentText("$callTypeLabel (E2EE) • Toque para atender")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .addAction(android.R.drawable.sym_action_call, "Atender", answerPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Recusar", declinePendingIntent)
                .build()

            notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao exibir notificação FullScreen de chamada: ${e.message}")
        }
    }

    private fun launchCallActivityOverLockscreen(callerHash: String, callerAlias: String, isVideo: Boolean) {
        try {
            val isScreenOffOrLocked = !powerManager.isInteractive || isDeviceLocked()
            if (!isAppInForeground || isScreenOffOrLocked) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_INCOMING_CALL
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra(EXTRA_CALLER_HASH, callerHash)
                    putExtra(EXTRA_CALLER_ALIAS, callerAlias)
                    putExtra(EXTRA_IS_VIDEO, isVideo)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao abrir Activity diretamente sobre tela de bloqueio: ${e.message}")
        }
    }

    fun cancelIncomingCallNotification() {
        try {
            notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    /**
     * Emite notificação sonora padrão do sistema para novas mensagens de texto, áudio ou foto
     * quando o celular está com a tela bloqueada/apagada ou o app está em segundo plano.
     */
    fun notifyIncomingMessageIfBackground(senderAlias: String, previewText: String) {
        try {
            val isScreenOffOrLocked = !powerManager.isInteractive || isDeviceLocked()
            if (isAppInForeground && !isScreenOffOrLocked) return

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                201,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cleanPreview = when {
                previewText.startsWith("[AUDIO:") -> "🎤 Mensagem de voz criptografada"
                previewText.startsWith("[IMAGE:") -> "📷 Foto criptografada"
                previewText.startsWith("[DOC:") -> "📎 Documento criptografado"
                else -> previewText
            }

            val notification = NotificationCompat.Builder(context, MSG_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("🔒 $senderAlias")
                .setContentText(cleanPreview)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify((senderAlias.hashCode() and 0x7FFFFFFF) % 1000 + 3000, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao emitir notificação de mensagem: ${e.message}")
        }
    }

    private fun isDeviceLocked(): Boolean {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isKeyguardLocked == true
        } catch (_: Exception) {
            false
        }
    }

    private fun getVibrator(): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }
}
