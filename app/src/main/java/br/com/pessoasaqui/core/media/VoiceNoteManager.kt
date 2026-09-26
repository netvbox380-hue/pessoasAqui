package br.com.pessoasaqui.core.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Gerenciador de Áudio Real para Mensagens de Voz (Voice Notes).
 *
 * Realiza gravação de microfone utilizando MediaRecorder (formato MPEG_4 / AAC de alta compressão)
 * e reprodução com MediaPlayer nativo através da saída de áudio do dispositivo.
 */
class VoiceNoteManager {

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var recordingStartTimeMs: Long = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingId: String? = null

    /**
     * Inicia a gravação de áudio do microfone.
     */
    fun startRecording(context: Context): Boolean {
        return try {
            cancelRecording() // Limpa e libera gravações anteriores de forma segura

            val tempFile = File.createTempFile("voice_note_", ".m4a", context.cacheDir)
            currentRecordingFile = tempFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartTimeMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Falha ao iniciar gravação de áudio", e)
            cancelRecording()
            false
        }
    }

    /**
     * Finaliza a gravação e retorna a duração em segundos e o áudio codificado em Base64.
     * Inclui proteção contra RuntimeException: stop failed em gravações rápidas (< 800ms) no Android 14/16.
     */
    fun stopRecording(): Pair<Int, String>? {
        val recorder = mediaRecorder ?: return null
        val file = currentRecordingFile ?: return null

        return try {
            val elapsedMs = System.currentTimeMillis() - recordingStartTimeMs
            if (elapsedMs < 800L) {
                try {
                    Thread.sleep(800L - elapsedMs)
                } catch (_: Exception) {}
            }

            recorder.stop()
            recorder.release()
            mediaRecorder = null

            val elapsedSecs = ((System.currentTimeMillis() - recordingStartTimeMs) / 1000).toInt().coerceAtLeast(1)
            if (file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                file.delete()
                currentRecordingFile = null
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Pair(elapsedSecs, base64)
            } else {
                cancelRecording()
                null
            }
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Erro ao finalizar gravação", e)
            cancelRecording()
            null
        }
    }

    /**
     * Retorna a amplitude máxima de som para animações de onda de áudio.
     */
    fun getMaxAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Cancela a gravação descartando arquivos temporários.
     */
    fun cancelRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        currentRecordingFile?.let {
            if (it.exists()) it.delete()
        }
        currentRecordingFile = null
    }

    /**
     * Reproduz o áudio recebido em Base64.
     */
    fun playVoiceNote(
        context: Context,
        messageId: String,
        base64Audio: String,
        onCompletion: () -> Unit
    ): Boolean {
        stopPlayback()

        return try {
            val audioBytes = Base64.decode(base64Audio.trim(), Base64.DEFAULT)
            val tempPlayFile = File.createTempFile("play_voice_", ".m4a", context.cacheDir)
            FileOutputStream(tempPlayFile).use { it.write(audioBytes) }

            val player = MediaPlayer()
            player.setDataSource(tempPlayFile.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                stopPlayback()
                tempPlayFile.delete()
                onCompletion()
            }
            player.start()

            mediaPlayer = player
            currentPlayingId = messageId
            true
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Falha ao reproduzir áudio", e)
            stopPlayback()
            false
        }
    }

    /**
     * Para a reprodução atual se estiver ativa.
     */
    fun stopPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentPlayingId = null
    }

    fun isPlaying(messageId: String): Boolean {
        return currentPlayingId == messageId && mediaPlayer?.isPlaying == true
    }
}
