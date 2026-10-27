package br.com.pessoasaqui.core.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Motor de Transmissão de Mídia em Tempo Real para Chamadas de Voz e Vídeo.
 *
 * - Áudio: Captura PCM 16kHz HD Wideband com cancelamento acústico de eco (AEC),
 *   supressão de ruído (NS), controle automático de ganho (AGC),
 *   compactação G.711 mu-law em tempo real (reduz banda em mais de 70%) e
 *   supressão de silêncio (VAD - Voice Activity Detection).
 * - Vídeo: Captura de quadros otimizada via Camera2 ImageReader e compressão JPEG HD (65%).
 * - Hardware: Controle de Mudo, Viva-Voz e Chaveamento de Câmera.
 */
class CallMediaEngine(
    private val context: Context,
    private val onSendAudioFrame: (base64Frame: String) -> Unit,
    private val onSendVideoFrame: (base64Frame: String) -> Unit
) {
    companion object {
        private const val TAG = "CallMediaEngine"
        private const val SAMPLE_RATE = 16000 // 16kHz HD Wideband Voice para chamadas nítidas
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    /**
     * Codec G.711 mu-law padrão ITU-T para compressão instantânea de áudio com zero latência.
     * Converte 16-bit linear PCM em 8-bit log PCM, reduzindo o tamanho pela metade
     * sem distorção perceptível para a fala humana.
     */
    object G711Codec {
        private const val BIAS = 0x84
        private const val CLIP = 32635

        fun linearToMuLaw(pcmSample: Short): Byte {
            var sample = pcmSample.toInt()
            val sign = if (sample < 0) 0x80 else 0
            if (sample < 0) sample = -sample
            if (sample > CLIP) sample = CLIP
            sample += BIAS

            var exponent = 7
            var expMask = 0x4000
            while ((sample and expMask) == 0 && exponent > 0) {
                exponent--
                expMask = expMask shr 1
            }
            val mantissa = (sample shr (exponent + 3)) and 0x0F
            val muLawByte = sign or (exponent shl 4) or mantissa
            return (muLawByte.inv() and 0xFF).toByte()
        }

        private val muLawToLinearTable = ShortArray(256) { i ->
            val mu = i.inv() and 0xFF
            val sign = if ((mu and 0x80) != 0) -1 else 1
            val exponent = (mu shr 4) and 0x07
            val mantissa = mu and 0x0F
            val sample = ((mantissa shl 3) + BIAS) shl exponent
            ((sample - BIAS) * sign).toShort()
        }

        fun muLawToLinear(muLawByte: Byte): Short {
            return muLawToLinearTable[muLawByte.toInt() and 0xFF]
        }

        fun compress(pcm16: ByteArray, length: Int): ByteArray {
            val numSamples = length / 2
            val compressed = ByteArray(numSamples)
            var pcmIdx = 0
            for (i in 0 until numSamples) {
                val low = pcm16[pcmIdx++].toInt() and 0xFF
                val high = pcm16[pcmIdx++].toInt()
                val sample = ((high shl 8) or low).toShort()
                compressed[i] = linearToMuLaw(sample)
            }
            return compressed
        }

        fun decompress(muLaw: ByteArray): ByteArray {
            val decompressed = ByteArray(muLaw.size * 2)
            var outIdx = 0
            for (b in muLaw) {
                val sample = muLawToLinear(b)
                decompressed[outIdx++] = (sample.toInt() and 0xFF).toByte()
                decompressed[outIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            return decompressed
        }
    }

    private fun calculateRms(pcm16: ByteArray, length: Int): Double {
        var sum = 0.0
        val numSamples = length / 2
        if (numSamples <= 0) return 0.0
        var pcmIdx = 0
        for (i in 0 until numSamples) {
            val low = pcm16[pcmIdx++].toInt() and 0xFF
            val high = pcm16[pcmIdx++].toInt()
            val sample = ((high shl 8) or low).toDouble()
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / numSamples)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var isCallActive = false
    private var isMuted = false
    private var isCameraOn = true
    private var isSpeakerOn = false

    // AudioRecord & AudioTrack
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecordJob: Job? = null
    private val mediaScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Efeitos de Hardware para Cancelamento de Eco e Ruído
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    // Camera2
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var lastVideoFrameSentMs = 0L

    /**
     * Inicia a transmissão de áudio e opcionalmente câmera para chamadas ativas.
     */
    @SuppressLint("MissingPermission")
    fun startCallMedia(isVideo: Boolean) {
        if (isCallActive) return
        isCallActive = true
        isMuted = false
        isCameraOn = isVideo

        configureAudioRouting(speaker = isVideo)
        startAudioPlayback()
        startAudioCapture()

        if (isVideo) {
            startCameraCapture()
        }
    }

    /**
     * Interrompe todos os recursos de mídia e desativa microfone/câmera.
     */
    fun stopCallMedia() {
        isCallActive = false
        stopAudioCapture()
        stopAudioPlayback()
        stopCameraCapture()
        resetAudioRouting()
        mediaScope.coroutineContext.cancelChildren()
    }

    /**
     * Ajusta mudo do microfone.
     */
    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    /**
     * Liga ou desliga transmissão de vídeo da câmera.
     */
    fun setCameraEnabled(enabled: Boolean) {
        isCameraOn = enabled
        if (enabled && isCallActive && cameraDevice == null) {
            startCameraCapture()
        } else if (!enabled) {
            stopCameraCapture()
        }
    }

    /**
     * Alterna viva-voz / fone auricular.
     */
    fun toggleSpeakerphone(): Boolean {
        isSpeakerOn = !isSpeakerOn
        audioManager?.let {
            it.isSpeakerphoneOn = isSpeakerOn
        }
        return isSpeakerOn
    }

    // --- PIPELINE DE ÁUDIO ---

    private fun configureAudioRouting(speaker: Boolean) {
        audioManager?.let { am ->
            try {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                isSpeakerOn = speaker
                am.isSpeakerphoneOn = speaker
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao configurar áudio routing: ${e.message}")
            }
        }
    }

    private fun resetAudioRouting() {
        audioManager?.let { am ->
            try {
                am.mode = AudioManager.MODE_NORMAL
                am.isSpeakerphoneOn = false
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao resetar áudio routing: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            val bufferSize = minBuf.coerceAtLeast(1024)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord não pôde ser inicializado")
                record.release()
                return
            }

            // Ativa Cancelamento Acústico de Eco (elimina microfonia e retorno em viva-voz)
            val sessionId = record.audioSessionId
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    Log.i(TAG, "AcousticEchoCanceler ativado com sucesso")
                }
            } catch (e: Exception) {
                Log.w(TAG, "AcousticEchoCanceler indisponível: ${e.message}")
            }

            // Ativa Supressão de Ruídos de Fundo
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                    Log.i(TAG, "NoiseSuppressor ativado com sucesso")
                }
            } catch (e: Exception) {
                Log.w(TAG, "NoiseSuppressor indisponível: ${e.message}")
            }

            // Ativa Controle Automático de Ganho
            try {
                if (AutomaticGainControl.isAvailable()) {
                    gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                    Log.i(TAG, "AutomaticGainControl ativado com sucesso")
                }
            } catch (e: Exception) {
                Log.w(TAG, "AutomaticGainControl indisponível: ${e.message}")
            }

            record.startRecording()
            audioRecord = record

            audioRecordJob = mediaScope.launch {
                // Fragmento de 40ms para voz HD em tempo real (16000 * 2 * 0.04 = 1280 bytes)
                val chunkSize = (SAMPLE_RATE * 2 * 0.04).toInt().coerceAtLeast(1024)
                val buffer = ByteArray(chunkSize)
                var silentFramesSkipped = 0

                while (isCallActive && isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0 && !isMuted) {
                        val activeChunk = if (read == buffer.size) buffer else buffer.copyOf(read)

                        // Voice Activity Detection (VAD): supressão de silêncio para economizar bateria e rede móvel
                        val rms = calculateRms(activeChunk, read)
                        val isSilence = rms < 180.0

                        if (isSilence) {
                            silentFramesSkipped++
                            // Envia 1 quadro a cada 15 (~600ms) como keepalive em períodos de escuta
                            if (silentFramesSkipped < 15) {
                                continue
                            }
                            silentFramesSkipped = 0
                        } else {
                            silentFramesSkipped = 0
                        }

                        // Compactação G.711 mu-law padrão internacional (reduz 50% de dados brutos)
                        val compressed = G711Codec.compress(activeChunk, read)
                        val b64 = "G711:" + Base64.encodeToString(compressed, Base64.NO_WRAP)
                        onSendAudioFrame(b64)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar captura de áudio", e)
        }
    }

    private fun stopAudioCapture() {
        audioRecordJob?.cancel()
        audioRecordJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try { echoCanceler?.release() } catch (_: Exception) {}
        echoCanceler = null
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        try { gainControl?.release() } catch (_: Exception) {}
        gainControl = null
    }

    private fun startAudioPlayback() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
            val bufferSize = minBuf.coerceAtLeast(1024)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .build()

            val track = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                audioTrack = track
            } else {
                track.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar AudioTrack", e)
        }
    }

    private fun stopAudioPlayback() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    /**
     * Recebe um quadro de áudio remoto em Base64 e o reproduz via AudioTrack.
     * Suporta quadros compactados G.711 mu-law ('G711:') e quadros legados PCM linear.
     */
    fun playRemoteAudioFrame(base64Pcm: String) {
        if (!isCallActive) return
        val track = audioTrack ?: return
        try {
            val pcmBytes = if (base64Pcm.startsWith("G711:")) {
                val clean = base64Pcm.removePrefix("G711:")
                val compressedBytes = Base64.decode(clean, Base64.DEFAULT)
                G711Codec.decompress(compressedBytes)
            } else {
                Base64.decode(base64Pcm, Base64.DEFAULT)
            }
            track.write(pcmBytes, 0, pcmBytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao reproduzir frame de voz: ${e.message}")
        }
    }

    // --- PIPELINE DE VÍDEO (CAMERA2) ---

    @SuppressLint("MissingPermission")
    private fun startCameraCapture() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            val frontCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return

            val chars = cameraManager.getCameraCharacteristics(frontCameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val chosenSize = sizes.find { it.width == 480 && it.height == 360 }
                ?: sizes.find { it.width == 640 && it.height == 480 }
                ?: sizes.find { it.width in 320..640 }
                ?: sizes.firstOrNull()
            val targetWidth = chosenSize?.width ?: 320
            val targetHeight = chosenSize?.height ?: 240

            val thread = HandlerThread("CameraBackgroundThread").apply { start() }
            cameraThread = thread
            val handler = Handler(thread.looper)
            cameraHandler = handler

            val reader = ImageReader.newInstance(targetWidth, targetHeight, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    // 8-10 FPS para vídeo nítido, fluido e responsivo em tempo real
                    if (isCallActive && isCameraOn && (now - lastVideoFrameSentMs >= 120)) {
                        lastVideoFrameSentMs = now
                        val jpegBytes = yuv420ToJpeg(image)
                        if (jpegBytes != null) {
                            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                            onSendVideoFrame(b64)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }, handler)
            imageReader = reader

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraSession(camera, reader, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, handler)

        } catch (e: Exception) {
            Log.e(TAG, "Falha ao abrir câmera frontal", e)
        }
    }

    private fun createCameraSession(camera: CameraDevice, reader: ImageReader, handler: Handler) {
        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao iniciar repeating request de vídeo", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w(TAG, "Falha ao configurar sessão de captura de câmera")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar sessão de câmera", e)
        }
    }

    private fun stopCameraCapture() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        try {
            cameraThread?.quitSafely()
        } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    /**
     * Converte imagem ImageFormat.YUV_420_888 para JPEG comprimido.
     */
    private fun yuv420ToJpeg(image: Image): ByteArray? {
        return try {
            val yBuffer: ByteBuffer = image.planes[0].buffer
            val uBuffer: ByteBuffer = image.planes[1].buffer
            val vBuffer: ByteBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            // Compressão JPEG a 65% para alta nitidez facial e transmissão fluida
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 65, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
