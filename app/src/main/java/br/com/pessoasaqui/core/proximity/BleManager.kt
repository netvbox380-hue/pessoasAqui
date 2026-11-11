package br.com.pessoasaqui.core.proximity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import android.util.Log
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.UserIntent
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Gerenciador nativo de Bluetooth Low Energy (BLE) do PessoasAqui.
 * Permite que 2 aparelhos reais físicos descubram um ao outro no ar via rádio BLE,
 * aplicando o corte estrito de 10 metros pelo sinal RSSI.
 */
class BleManager(
    private val context: Context? = null,
    private val distanceEstimator: DistanceEstimator = DistanceEstimator(),
    private val customAdvertiserDriver: BleAdvertiserDriver? = null
) {
    private val tag = "PessoasAquiBLE"

    /**
     * Parâmetros de anúncio desacoplados para execução em dispositivos e testes de JVM.
     */
    data class AdvertisingParams(
        val isConnectable: Boolean = true,
        val mode: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
        val txPowerLevel: Int = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
        val timeoutMillis: Int = 0,
        val primaryPayload: ByteArray,
        val scanResponsePayload: ByteArray
    )

    /**
     * Interface de abstração do transmissor BLE para suporte a testes unitários e desacoplamento de rádio.
     */
    interface BleAdvertiserDriver {
        fun startAdvertising(
            params: AdvertisingParams,
            callback: (isSuccess: Boolean, errorCode: Int?) -> Unit
        )
        fun stopAdvertising()
    }

    private class DefaultAndroidAdvertiserDriver(
        private val getAdvertiser: () -> BluetoothLeAdvertiser?
    ) : BleAdvertiserDriver {
        private var currentCallback: AdvertiseCallback? = null

        @SuppressLint("MissingPermission")
        override fun startAdvertising(
            params: AdvertisingParams,
            callback: (isSuccess: Boolean, errorCode: Int?) -> Unit
        ) {
            val adv = getAdvertiser() ?: run {
                callback(false, -1)
                return
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(params.mode)
                .setTxPowerLevel(params.txPowerLevel)
                .setConnectable(params.isConnectable)
                .setTimeout(params.timeoutMillis)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), params.primaryPayload)
                .build()

            val scanResponse = if (params.scanResponsePayload.isNotEmpty()) {
                AdvertiseData.Builder()
                    .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), params.scanResponsePayload)
                    .build()
            } else {
                null
            }

            val cb = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    callback(true, null)
                }

                override fun onStartFailure(errorCode: Int) {
                    callback(false, errorCode)
                }
            }
            currentCallback = cb
            adv.startAdvertising(settings, data, scanResponse, cb)
        }

        @SuppressLint("MissingPermission")
        override fun stopAdvertising() {
            currentCallback?.let { cb ->
                getAdvertiser()?.stopAdvertising(cb)
                currentCallback = null
            }
        }
    }

    companion object {
        // Limites físicos de pacotes Legados Bluetooth Low Energy (Bluetooth Core Specification v4.0+)
        const val MAX_LEGACY_ADV_BYTES = 31
        const val FLAGS_OVERHEAD_BYTES = 3 // 1B Len (0x02) + 1B Type (0x01) + 1B Flags (0x06)
        const val SERVICE_DATA_16BIT_HEADER_BYTES = 4 // 1B Len + 1B Type (0x16) + 2B UUID16 (0xFA01)

        // Limites máximos de payload de dados úteis para respeitar estritamente o limite de 31 bytes
        const val MAX_CONNECTABLE_PRIMARY_PAYLOAD_BYTES = 24 // 31 - 3 (Flags) - 4 (Cabeçalho) = 24 bytes
        const val MAX_SCAN_RESPONSE_PAYLOAD_BYTES = 27       // 31 - 4 (Cabeçalho, sem Flags) = 27 bytes
        const val FALLBACK_ULTRA_COMPACT_PAYLOAD_BYTES = 18  // 16 hex + 1 ':' + 1 ordinal = 18 bytes
        // Retentativas apenas após falha real, limitadas para não atingir throttling.
        const val RECOVERY_MIN_INTERVAL_MS = 30_000L

        /**
         * Calcula o tamanho total em bytes do pacote físico de anúncio.
         */
        fun calculateTotalPacketSize(payloadSizeBytes: Int, isConnectable: Boolean): Int {
            val flags = if (isConnectable) FLAGS_OVERHEAD_BYTES else 0
            return flags + SERVICE_DATA_16BIT_HEADER_BYTES + payloadSizeBytes
        }

        /**
         * Trunca com segurança uma string preservando limites válidos de caracteres codificados em UTF-8
         * e integridade de pares substitutos (surrogate pairs / emojis).
         */
        fun takeUtf8Bytes(str: String, maxBytes: Int): String {
            val bytes = str.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size <= maxBytes) return str
            val sb = StringBuilder()
            var currentBytes = 0
            var i = 0
            while (i < str.length) {
                val cp = str.codePointAt(i)
                val charCount = Character.charCount(cp)
                val cpBytes = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8).size
                if (currentBytes + cpBytes > maxBytes) {
                    break
                }
                sb.appendCodePoint(cp)
                currentBytes += cpBytes
                i += charCount
            }
            return sb.toString()
        }

        /**
         * Constrói a carga de dados de anúncio garantindo conformidade com o limite de bytes do pacote BLE.
         * Para pacotes conectáveis primários (ADV_IND), o payload máximo suportado é 24 bytes.
         */
        fun buildAdvertisingPayload(
            identityHash: String,
            intent: UserIntent,
            alias: String,
            maxPayloadBytes: Int = MAX_CONNECTABLE_PRIMARY_PAYLOAD_BYTES
        ): ByteArray {
            val cleanId = identityHash.removePrefix("peer-").trim().uppercase()
            val prefix = "$cleanId:${intent.ordinal}"
            val prefixBytes = prefix.toByteArray(StandardCharsets.UTF_8)
            if (prefixBytes.size >= maxPayloadBytes) {
                return prefixBytes.take(maxPayloadBytes).toByteArray()
            }
            val remainingForAlias = maxPayloadBytes - prefixBytes.size - 1 // 1 byte para ':'
            if (remainingForAlias <= 0 || alias.isBlank()) {
                return prefixBytes
            }
            val safeAlias = takeUtf8Bytes(alias.trim(), remainingForAlias)
            return if (safeAlias.isNotEmpty()) {
                "$prefix:$safeAlias".toByteArray(StandardCharsets.UTF_8)
            } else {
                prefixBytes
            }
        }
    }

    private val bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val advertiserDriver: BleAdvertiserDriver = customAdvertiserDriver ?: DefaultAndroidAdvertiserDriver {
        try {
            bluetoothAdapter?.bluetoothLeAdvertiser
        } catch (e: Throwable) {
            Log.w(tag, "Não foi possível obter advertiser BLE: ${e.message}")
            null
        }
    }

    private var scanner: BluetoothLeScanner? = null

    private val _realDiscoveredPeers = MutableStateFlow<Map<String, NearbyPerson>>(emptyMap())
    val realDiscoveredPeers: StateFlow<Map<String, NearbyPerson>> = _realDiscoveredPeers.asStateFlow()

    // Histórico de RSSI suavizado por identidade (filtro EMA contra jitter e oscilação)
    private val peerRssiHistory = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    /** Estados explícitos: estar inscrito no scanner não equivale a receber anúncios. */
    enum class DiscoveryState { STOPPED, STARTING, SCANNING, FAILED, RADIO_OFF }
    @Volatile private var discoveryState = DiscoveryState.STOPPED
    @Volatile private var foreground = true
    @Volatile private var discoverySuspended = false
    @Volatile private var highAccuracyRequested = true
    @Volatile private var scanningActive = false
    @Volatile private var advertisingActive = false
    @Volatile private var lastRecoveryAttemptMs = 0L
    private val advertisingGeneration = AtomicLong(0L)
    // Retentativas só depois de callback de falha real; backoff limita consumo.
    private val recoveryHandler: Handler? = try {
        if (context != null) Handler(Looper.getMainLooper()) else null
    } catch (_: Throwable) {
        // JVM unit tests or unavailable Android Looper: manual retry path.
        null
    }
    private var scanRetryTask: Runnable? = null
    private var advertisingRetryTask: Runnable? = null
    private var scanFailureCount = 0
    private var advertisingFailureCount = 0

    private fun backoffMs(failures: Int): Long =
        (5_000L * (1L shl (failures.coerceIn(1, 5) - 1))).coerceAtMost(120_000L)

    private fun cancelScanRetry() {
        scanRetryTask?.let { recoveryHandler?.removeCallbacks(it) }
        scanRetryTask = null
    }

    private fun cancelAdvertisingRetry() {
        advertisingRetryTask?.let { recoveryHandler?.removeCallbacks(it) }
        advertisingRetryTask = null
    }

    private fun scheduleScanRecovery() {
        cancelScanRetry()
        if (!isBluetoothEnabled || discoverySuspended) return
        val cb = lastScanCallback ?: return
        val delayMs = backoffMs(++scanFailureCount)
        val task = Runnable {
            scanRetryTask = null
            if (isBluetoothEnabled && !discoverySuspended && discoveryState == DiscoveryState.FAILED) startScanning(cb)
        }
        scanRetryTask = task
        recoveryHandler?.postDelayed(task, delayMs)
    }

    private fun scheduleAdvertisingRecovery() {
        cancelAdvertisingRetry()
        if (!isBluetoothEnabled || discoverySuspended) return
        val id = lastIdentityHash ?: return
        val delayMs = backoffMs(++advertisingFailureCount)
        val task = Runnable {
            advertisingRetryTask = null
            if (isBluetoothEnabled && !discoverySuspended && !advertisingActive) {
                startAdvertising(id, lastAlias ?: "", lastIntent ?: UserIntent.QUERO_CONVERSAR)
            }
        }
        advertisingRetryTask = task
        recoveryHandler?.postDelayed(task, delayMs)
    }
    internal val currentDiscoveryState: DiscoveryState get() = discoveryState

    private var lastIdentityHash: String? = null
    private var lastAlias: String? = null
    private var lastIntent: UserIntent? = null
    private var lastScanCallback: ((NearbyPerson) -> Unit)? = null
    var onDeviceAddressDiscovered: ((identityHash: String, address: String) -> Unit)? = null
    var onAdvertisingStateChanged: ((isSuccess: Boolean, errorCode: Int?) -> Unit)? = null
    // Notifica o repositório para religar o servidor GATT, que o Android pode
    // ter encerrado ao desligar o Bluetooth.
    var onBluetoothStateChanged: ((enabled: Boolean) -> Unit)? = null

    internal val isScanningActive: Boolean get() = scanningActive
    internal val isAdvertisingActive: Boolean get() = advertisingActive
    val isDiscoverySuspended: Boolean get() = discoverySuspended

    /** Radar/chat: baixa latência; Ofertas e tolerância: modo econômico. */
    @Synchronized
    fun setDiscoveryHighAccuracy(enabled: Boolean) {
        if (highAccuracyRequested == enabled) return
        highAccuracyRequested = enabled
        if (!discoverySuspended && scanningActive) {
            lastScanCallback?.let { startScanning(it) }
        }
    }

    /**
     * Apenas discovery (scan + advertising) é pausada. A infraestrutura
     * GATT e conexões existentes são responsabilidade do coordenador.
     * Bloqueia também recuperações automáticas por callbacks antigos.
     */
    @Synchronized
    fun setDiscoverySuspended(suspended: Boolean) {
        if (discoverySuspended == suspended) return
        discoverySuspended = suspended
        if (suspended) {
            cancelScanRetry()
            cancelAdvertisingRetry()
            stopScanning()
            stopAdvertising()
            _realDiscoveredPeers.value = emptyMap()
        }
        // Retomar exige chamada explícita do repositório com identidade válida.
    }

    /**
     * Recuperação de estados com falha observada, não por reinícios periódicos.
     * Um scanner saudável pode ficar horas sem ver ninguém; silêncio não é erro.
     */
    @Synchronized
    fun ensureDiscoveryContinuity(nowMs: Long = System.currentTimeMillis()) {
        if (!isBluetoothEnabled || discoverySuspended) return
        if (lastIdentityHash != null && !advertisingActive &&
            (lastRecoveryAttemptMs == 0L || nowMs - lastRecoveryAttemptMs >= RECOVERY_MIN_INTERVAL_MS)) {
            lastRecoveryAttemptMs = nowMs
            startAdvertising(lastIdentityHash!!, lastAlias ?: "", lastIntent ?: UserIntent.QUERO_CONVERSAR)
        }
        if (lastScanCallback != null && discoveryState == DiscoveryState.FAILED &&
            (lastRecoveryAttemptMs == 0L || nowMs - lastRecoveryAttemptMs >= RECOVERY_MIN_INTERVAL_MS)) {
            lastRecoveryAttemptMs = nowMs
            startScanning(lastScanCallback!!)
        }
    }

    /**
     * O ciclo de vida da Activity troca o modo de descoberta exatamente uma vez.
     * O filtro de service data permite ao Android gerenciar o scan de tela apagada.
     */
    @Synchronized
    fun setAppForeground(isForeground: Boolean) {
        if (foreground == isForeground) return
        foreground = isForeground
        if (!isBluetoothEnabled || discoverySuspended) return
        lastScanCallback?.let { startScanning(it) }
        // Reconfigurar somente na transição efetiva de foreground/background;
        // manter anúncio conectável para sessões BLE GATT já autorizadas.
        if (advertisingActive) {
            lastIdentityHash?.let { startAdvertising(it, lastAlias ?: "", lastIntent ?: UserIntent.QUERO_CONVERSAR) }
        } else ensureDiscoveryContinuity()
    }

    /** Compatibilidade com a API do repositório: não reinicia rádio saudável. */
    fun refreshDiscoveryOnForeground(nowMs: Long = System.currentTimeMillis()) {
        setAppForeground(true)
        ensureDiscoveryContinuity(nowMs)
    }

    var isMockBluetoothEnabled: Boolean? = null

    val isBluetoothSupported: Boolean get() = bluetoothAdapter != null || customAdvertiserDriver != null
    val isBluetoothEnabled: Boolean
        get() = isMockBluetoothEnabled ?: try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: Throwable) {
            Log.w(tag, "Não foi possível verificar status do Bluetooth: ${e.message}")
            false
        }

    private val _isBluetoothEnabledFlow = MutableStateFlow(isBluetoothEnabled)
    val isBluetoothEnabledFlow: StateFlow<Boolean> = _isBluetoothEnabledFlow.asStateFlow()

    init {
        try {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            val stateReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        when (state) {
                            BluetoothAdapter.STATE_OFF -> {
                                _isBluetoothEnabledFlow.value = false
                                stopScanning()
                                discoveryState = DiscoveryState.RADIO_OFF
                                stopAdvertising()
                                cancelScanRetry()
                                cancelAdvertisingRetry()
                                onBluetoothStateChanged?.invoke(false)
                            }
                            BluetoothAdapter.STATE_ON -> {
                                _isBluetoothEnabledFlow.value = true
                                // O repositório reinicia os três elementos (GATT, anúncio e
                                // scanner). Em usos isolados, mantém o fallback local.
                                val listener = onBluetoothStateChanged
                                if (listener != null) {
                                    listener(true)
                                } else {
                                    if (discoverySuspended) return
                                    lastIdentityHash?.let { hash ->
                                        startAdvertising(hash, lastAlias ?: "", lastIntent ?: UserIntent.QUERO_CONVERSAR)
                                    }
                                    lastScanCallback?.let { cb -> startScanning(cb) }
                                }
                            }
                        }
                    }
                }
            }
            if (context != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(stateReceiver, filter)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Não foi possível registrar receiver de estado Bluetooth: ${e.message}")
        }
    }

    /**
     * Inicia a transmissão (Advertising) deste aparelho para que outros aparelhos a até 10m o vejam
     * e consigam estabelecer transporte físico GATT (anúncio conectável ADV_IND).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startAdvertising(
        myIdentityHash: String,
        myAlias: String,
        myIntent: UserIntent
    ) {
        lastIdentityHash = myIdentityHash
        lastAlias = myAlias
        lastIntent = myIntent
        if (!isBluetoothEnabled || discoverySuspended) return

        stopAdvertising()
        val generation = advertisingGeneration.get()

        val cleanMyId = myIdentityHash.removePrefix("peer-").trim().uppercase()

        // Carga primária limitada a 24 bytes para garantir tamanho total <= 31B (3B Flags + 4B Header + <=24B Payload)
        val primaryPayload = buildAdvertisingPayload(cleanMyId, myIntent, myAlias, MAX_CONNECTABLE_PRIMARY_PAYLOAD_BYTES)

        // Carga de Scan Response: não duplicamos o mesmo UUID 0xFA01 no Scan Response para evitar que
        // o parser de certos dispositivos (como Samsung One UI / Android 16) concatene os dados
        // gerando nomes corrompidos com hashes. O apelido completo é transmitido de forma confidencial
        // e íntegra via canal seguro GATT (AES-256-GCM).
        val scanResponsePayload = ByteArray(0)

        val params = AdvertisingParams(
            isConnectable = true, // <--- CONECTÁVEL: habilita canal de transporte GATT (ADV_IND)
            mode = if (foreground) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            txPowerLevel = if (foreground) AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            timeoutMillis = 0,
            primaryPayload = primaryPayload,
            scanResponsePayload = scanResponsePayload
        )

        try {
            advertiserDriver.startAdvertising(params) advertisingCallback@{ isSuccess, errorCode ->
                if (generation != advertisingGeneration.get()) return@advertisingCallback
                advertisingActive = isSuccess
                if (isSuccess) {
                    advertisingFailureCount = 0
                    cancelAdvertisingRetry()
                    Log.d(tag, "BLE Advertising conectável iniciado com sucesso para presença em 10m e transporte GATT.")
                    onAdvertisingStateChanged?.invoke(true, null)
                } else {
                    Log.w(tag, "Falha ao iniciar BLE Advertising. Código: $errorCode")
                    onAdvertisingStateChanged?.invoke(false, errorCode)
                    if (errorCode != AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                        scheduleAdvertisingRecovery()
                    }
                    if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                        // Fallback para carga ultra-compacta conectável (18 bytes -> 25 bytes total no pacote físico)
                        try {
                            val ultraCompact = "$cleanMyId:${myIntent.ordinal}".toByteArray(StandardCharsets.UTF_8)
                            val fallbackParams = params.copy(primaryPayload = ultraCompact)
                            advertiserDriver.startAdvertising(fallbackParams) fallbackCallback@{ fbSuccess, fbErr ->
                                if (generation != advertisingGeneration.get()) return@fallbackCallback
                                advertisingActive = fbSuccess
                                if (fbSuccess) {
                                    advertisingFailureCount = 0
                                    cancelAdvertisingRetry()
                                    Log.d(tag, "BLE Advertising compacto conectável iniciado com sucesso.")
                                } else {
                                    Log.e(tag, "Falha no fallback de BLE advertising: $fbErr")
                                    scheduleAdvertisingRecovery()
                                }
                                onAdvertisingStateChanged?.invoke(fbSuccess, fbErr)
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Erro no fallback de BLE advertising: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            advertisingActive = false
            scheduleAdvertisingRecovery()
            Log.e(tag, "Erro ao iniciar advertising BLE: ${e.message}")
            onAdvertisingStateChanged?.invoke(false, -1)
        }
    }

    /**
     * Encerra a transmissão BLE.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopAdvertising() {
        advertisingGeneration.incrementAndGet()
        advertisingActive = false
        cancelAdvertisingRetry()
        try {
            advertiserDriver.stopAdvertising()
        } catch (e: Exception) {
            Log.e(tag, "Erro ao parar advertising: ${e.message}")
        }
    }

    /**
     * Inicia o escaneamento BLE para detectar outros aparelhos com o app aberto a até 10 metros.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startScanning(onPeerDiscovered: (NearbyPerson) -> Unit) {
        lastScanCallback = onPeerDiscovered
        if (discoverySuspended) return
        if (!isBluetoothEnabled) {
            discoveryState = DiscoveryState.RADIO_OFF
            return
        }
        stopScanning()
        discoveryState = DiscoveryState.STARTING
        scanner = try {
            bluetoothAdapter?.bluetoothLeScanner
        } catch (e: Throwable) {
            Log.w(tag, "Não foi possível obter scanner BLE: ${e.message}")
            null
        } ?: run {
            discoveryState = DiscoveryState.FAILED
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(
                if (foreground && highAccuracyRequested) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED
            )
            // Em segundo plano, agrupar resultados; no Radar manter reação imediata.
            // onBatchScanResults já usa o mesmo validador de distância/identidade.
            .setReportDelay(if (foreground) 0L else 3_000L)
            .build()

        // Os anúncios do PessoasAqui carregam o UUID em SERVICE DATA, não na
        // lista de UUIDs anunciados. Filtro correspondente sem exigir payload
        // ou apelido específico, compatível com os pacotes legados existentes.
        val serviceFilter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(BleConstants.SERVICE_UUID), byteArrayOf())
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result ?: return
                if (scanCallback === this) processScanResult(result, onPeerDiscovered)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                if (scanCallback === this) results?.forEach { processScanResult(it, onPeerDiscovered) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (scanCallback === this) {
                    scanningActive = false
                    discoveryState = DiscoveryState.FAILED
                    Log.e(tag, "Falha no escaneamento BLE. Código: $errorCode")
                    scheduleScanRecovery()
                }
            }
        }

        try {
            scanner?.startScan(listOf(serviceFilter), settings, scanCallback)
            if (discoveryState != DiscoveryState.FAILED) {
                scanningActive = true
                discoveryState = DiscoveryState.SCANNING
                scanFailureCount = 0
                cancelScanRetry()
            }
            Log.d(tag, "BLE scan filtrado iniciado; foreground=$foreground")
        } catch (e: Exception) {
            scanningActive = false
            discoveryState = DiscoveryState.FAILED
            scheduleScanRecovery()
            Log.e(tag, "Erro ao iniciar scanner BLE: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopScanning() {
        val callback = scanCallback
        scanCallback = null
        scanningActive = false
        discoveryState = DiscoveryState.STOPPED
        callback?.let {
            try {
                scanner?.stopScan(it)
            } catch (e: Exception) {
                Log.e(tag, "Erro ao parar scanner: ${e.message}")
            }
        }
    }

    private fun processScanResult(result: ScanResult, onPeerDiscovered: (NearbyPerson) -> Unit) {
        val record = result.scanRecord ?: return
        var serviceData = record.getServiceData(ParcelUuid(BleConstants.SERVICE_UUID))
        if (serviceData == null || serviceData.isEmpty()) {
            val entry = record.serviceData.entries.firstOrNull { (uuid, _) ->
                uuid.uuid == BleConstants.SERVICE_UUID ||
                uuid.toString().contains("fa01", ignoreCase = true)
            }
            serviceData = entry?.value
        }
        val mac = try { result.device?.address } catch (_: SecurityException) { null }
        processScanRecordData(serviceData, mac, result.rssi, onPeerDiscovered)
    }

    internal fun processScanRecordData(
        serviceData: ByteArray?,
        macAddress: String?,
        rawRssi: Int,
        onPeerDiscovered: (NearbyPerson) -> Unit
    ) {
        // Rejeita estritamente qualquer pacote que não pertença ao serviço PessoasAqui (UUID 0xFA01)
        if (serviceData == null || serviceData.isEmpty()) {
            return
        }

        var identityHash = ""
        var intent = UserIntent.QUERO_CONVERSAR
        var peerAlias: String? = null

        try {
            val str = String(serviceData, StandardCharsets.UTF_8)
            val parts = str.split(":")
            if (parts.size < 2) return

            val rawId = parts[0].removePrefix("peer-").trim().uppercase()
            // Validação estrita: O ID técnico gerado pelo PessoasAqui DEVE ser hexadecimal legítimo (8 a 16 caracteres [0-9A-F])
            // Isso elimina 100% de dispositivos Bluetooth desconhecidos (fones, smart TVs, relógios, AirTags) que emitem dados binários arbitrários
            if (!rawId.matches(Regex("^[0-9A-F]{8,16}$"))) {
                return
            }
            identityHash = rawId

            val intentIdx = parts[1].toIntOrNull() ?: return
            if (intentIdx !in 0..UserIntent.values().size) return
            intent = UserIntent.values().getOrElse(intentIdx) { UserIntent.QUERO_CONVERSAR }

            // Sanitização e validação estrita do apelido preliminar de broadcast:
            // Rejeita qualquer candidato que contenha hash técnico embutido [0-9A-F]{8,16} ou formato inválido
            if (parts.size > 2 && parts[2].isNotBlank()) {
                val candidate = parts[2].trim()
                val containsEmbeddedHex = candidate.contains(Regex("[0-9A-F]{8,16}"))
                if (!containsEmbeddedHex && candidate.length <= 32) {
                    peerAlias = candidate
                }
            }
        } catch (e: Exception) {
            return
        }

        if (identityHash.isBlank()) return

        // Não adiciona o próprio aparelho (compara com hash técnico e apelido)
        val myCleanId = lastIdentityHash?.removePrefix("peer-")?.trim()?.uppercase()
        if (identityHash.equals(myCleanId, ignoreCase = true)) {
            return
        }
        // Apelidos não são identificadores únicos: duas pessoas podem usar o
        // mesmo nome. A supressão do próprio aparelho usa apenas a identidade.

        // Suavização do RSSI por aparelho via EMA (Exponential Moving Average):
        // Elimina oscilações instantâneas de radiofrequência que faziam contatos saltarem de posição
        val prevRssi = peerRssiHistory[identityHash]
        val smoothedRssi = distanceEstimator.smoothRssi(rawRssi, prevRssi)
        peerRssiHistory[identityHash] = smoothedRssi

        val distance = distanceEstimator.estimateDistanceMeters(smoothedRssi.roundToInt())

        // Regra de Proximidade Física Estrita (10 metros):
        // Se a distância estimada ultrapassar 10 metros pelo sinal suavizado, descarta
        if (!distanceEstimator.isWithin10Meters(distance)) {
            return
        }

        // Limpeza de cache de sinal se acumular mais de 300 aparelhos
        if (peerRssiHistory.size > 300) {
            peerRssiHistory.keys.take(50).forEach { peerRssiHistory.remove(it) }
        }

        if (!macAddress.isNullOrBlank()) {
            onDeviceAddressDiscovered?.invoke(identityHash, macAddress)
        }

        // Se o apelido de broadcast não estiver disponível ou for inválido, utiliza o identificador genérico
        // "Pessoa Próxima", sem expor fragmentos de hash técnico como nome de usuário.
        val finalAlias = if (!peerAlias.isNullOrBlank()) peerAlias else "Pessoa Próxima"

        val peer = NearbyPerson(
            id = identityHash,
            technicalIdentityHash = identityHash,
            alias = finalAlias,
            estimatedDistanceMeters = distance,
            proximityLabel = distanceEstimator.getProximityLabel(distance),
            intent = intent,
            isMarkedByMe = false,
            isMarkingMe = false,
            isMutualConnection = false,
            isFamily = false,
            isPhotoVisible = false,
            avatarColorHex = 0xFF00E5FF,
            lastSeenEpochMs = System.currentTimeMillis()
        )

        val updatedMap = _realDiscoveredPeers.value.toMutableMap()
        updatedMap[peer.id] = peer
        _realDiscoveredPeers.value = updatedMap

        onPeerDiscovered(peer)
    }
}
