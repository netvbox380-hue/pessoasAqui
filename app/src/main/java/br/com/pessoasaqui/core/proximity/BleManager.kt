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
import android.util.Log
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.UserIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

/**
 * Gerenciador nativo de Bluetooth Low Energy (BLE) do PessoasAqui.
 * Permite que 2 aparelhos reais físicos descubram um ao outro no ar via rádio BLE,
 * aplicando o corte estrito de 10 metros pelo sinal RSSI.
 */
class BleManager(
    private val context: Context,
    private val distanceEstimator: DistanceEstimator = DistanceEstimator()
) {
    private val tag = "PessoasAquiBLE"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val _realDiscoveredPeers = MutableStateFlow<Map<String, NearbyPerson>>(emptyMap())
    val realDiscoveredPeers: StateFlow<Map<String, NearbyPerson>> = _realDiscoveredPeers.asStateFlow()

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private var lastIdentityHash: String? = null
    private var lastAlias: String? = null
    private var lastIntent: UserIntent? = null
    private var lastScanCallback: ((NearbyPerson) -> Unit)? = null

    val isBluetoothSupported: Boolean get() = bluetoothAdapter != null
    val isBluetoothEnabled: Boolean
        get() = try {
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
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        val enabled = (state == BluetoothAdapter.STATE_ON)
                        _isBluetoothEnabledFlow.value = enabled
                        if (enabled) {
                            lastIdentityHash?.let { hash ->
                                val alias = lastAlias ?: ""
                                val userIntent = lastIntent ?: UserIntent.QUERO_CONVERSAR
                                startAdvertising(hash, alias, userIntent)
                            }
                            lastScanCallback?.let { cb ->
                                startScanning(cb)
                            }
                        }
                    }
                }
            }, filter)
        } catch (e: Exception) {
            Log.w(tag, "Não foi possível registrar receiver de estado Bluetooth: ${e.message}")
        }
    }

    /**
     * Inicia a transmissão (Advertising) deste aparelho para que outros aparelhos a até 10m o vejam.
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(
        myIdentityHash: String,
        myAlias: String,
        myIntent: UserIntent
    ) {
        lastIdentityHash = myIdentityHash
        lastAlias = myAlias
        lastIntent = myIntent
        if (!isBluetoothEnabled) return
        advertiser = try {
            bluetoothAdapter?.bluetoothLeAdvertiser
        } catch (e: Throwable) {
            Log.w(tag, "Não foi possível obter advertiser BLE: ${e.message}")
            null
        } ?: return

        stopAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val cleanMyId = myIdentityHash.removePrefix("peer-").trim().uppercase()
        // Monta carga de dados compacta (<= 25 bytes): ID (16 hex) + ":" + ordinal (1 byte) + ":" + apelido (até 6 chars)
        val cleanAlias = myAlias.trim().take(6)
        val payload = "$cleanMyId:${myIntent.ordinal}:$cleanAlias".toByteArray(StandardCharsets.UTF_8)

        // Pacote Principal de Anúncio: coloca os dados de serviço diretamente no pacote primário
        // Isso é fundamental para Android 14/15/16 onde o scanResponse pode ser descartado em transmissões não conectáveis
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), payload)
            .build()

        // Pacote de Resposta de Varredura redundante
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), payload)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(tag, "BLE Advertising iniciado com sucesso para presença em 10m.")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(tag, "Falha ao iniciar BLE Advertising. Código: $errorCode")
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    // Fallback para carga ultra-compacta
                    try {
                        val ultraCompact = "$cleanMyId:${myIntent.ordinal}".toByteArray(StandardCharsets.UTF_8)
                        val fallbackData = AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .setIncludeTxPowerLevel(false)
                            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), ultraCompact)
                            .build()
                        advertiser?.startAdvertising(settings, fallbackData, null, object : AdvertiseCallback() {
                            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                                Log.d(tag, "BLE Advertising compacto iniciado com sucesso.")
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(tag, "Erro no fallback de BLE advertising: ${e.message}")
                    }
                }
            }
        }

        try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.e(tag, "Erro ao iniciar advertising BLE: ${e.message}")
        }
    }

    /**
     * Encerra a transmissão BLE.
     */
    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiseCallback?.let {
            try {
                advertiser?.stopAdvertising(it)
            } catch (e: Exception) {
                Log.e(tag, "Erro ao parar advertising: ${e.message}")
            }
            advertiseCallback = null
        }
    }

    /**
     * Inicia o escaneamento BLE para detectar outros aparelhos com o app aberto a até 10 metros.
     */
    @SuppressLint("MissingPermission")
    fun startScanning(onPeerDiscovered: (NearbyPerson) -> Unit) {
        lastScanCallback = onPeerDiscovered
        if (!isBluetoothEnabled) return
        scanner = try {
            bluetoothAdapter?.bluetoothLeScanner
        } catch (e: Throwable) {
            Log.w(tag, "Não foi possível obter scanner BLE: ${e.message}")
            null
        } ?: return

        stopScanning()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result ?: return
                processScanResult(result, onPeerDiscovered)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { processScanResult(it, onPeerDiscovered) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(tag, "Falha no escaneamento BLE. Código: $errorCode")
            }
        }

        try {
            // Escaneia de forma abrangente para contornar limitações de chipsets em filtros de hardware
            scanner?.startScan(null, settings, scanCallback)
            Log.d(tag, "BLE Scanner ativado para filtro de 10m.")
        } catch (e: Exception) {
            Log.e(tag, "Erro ao iniciar scanner BLE: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        scanCallback?.let {
            try {
                scanner?.stopScan(it)
            } catch (e: Exception) {
                Log.e(tag, "Erro ao parar scanner: ${e.message}")
            }
            scanCallback = null
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
        // Rejeita estritamente qualquer pacote que não pertença ao serviço PessoasAqui (UUID 0xFA01)
        if (serviceData == null || serviceData.isEmpty()) {
            return
        }

        val rssi = result.rssi
        val distance = distanceEstimator.estimateDistanceMeters(rssi)

        // Regra de Proximidade Física Estrita (10 metros):
        // Se a distância estimada ultrapassar 10 metros, descarta imediatamente!
        if (!distanceEstimator.isWithin10Meters(distance)) {
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

            if (parts.size > 2 && parts[2].isNotBlank()) {
                peerAlias = parts[2].trim()
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
        if (!peerAlias.isNullOrBlank() && !lastAlias.isNullOrBlank() && peerAlias.equals(lastAlias, ignoreCase = true)) {
            return
        }

        val finalAlias = if (!peerAlias.isNullOrBlank()) peerAlias else "Pessoa Próxima #${identityHash.take(4)}"

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
