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

        // Monta carga de dados: Hash de identidade (8 chars) + ordinal de intenção (1 byte)
        val payload = "$myIdentityHash:${myIntent.ordinal}".toByteArray(StandardCharsets.UTF_8)

        // Pacote Principal de Anúncio (<= 31 bytes)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        // Pacote de Resposta de Varredura (Scan Response) com os dados de presença
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), payload)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(tag, "BLE Advertising iniciado com sucesso para presença em 10m.")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(tag, "Falha ao iniciar BLE Advertising. Código: $errorCode")
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
        val serviceData = record.getServiceData(ParcelUuid(BleConstants.SERVICE_UUID))
        val hasServiceUuid = record.serviceUuids?.contains(ParcelUuid(BleConstants.SERVICE_UUID)) == true
        if (serviceData == null && !hasServiceUuid) {
            return
        }

        val rssi = result.rssi
        val distance = distanceEstimator.estimateDistanceMeters(rssi)

        // Regra de Proximidade Física Estrita (10 metros):
        // Se a distância estimada ultrapassar 10 metros, descarta imediatamente!
        if (!distanceEstimator.isWithin10Meters(distance)) {
            return
        }

        var identityHash = result.device.address.replace(":", "").take(8)
        var intent = UserIntent.QUERO_CONVERSAR

        if (serviceData != null) {
            try {
                val str = String(serviceData, StandardCharsets.UTF_8)
                val parts = str.split(":")
                if (parts.isNotEmpty() && parts[0].isNotBlank()) identityHash = parts[0]
                if (parts.size > 1) {
                    val intentIdx = parts[1].toIntOrNull() ?: 0
                    intent = UserIntent.values().getOrElse(intentIdx) { UserIntent.QUERO_CONVERSAR }
                }
            } catch (e: Exception) {
                // Fallback gracioso
            }
        }

        // Não adiciona o próprio aparelho
        if (identityHash.isNotBlank() && identityHash.equals(lastIdentityHash, ignoreCase = true)) {
            return
        }

        val peer = NearbyPerson(
            id = identityHash,
            technicalIdentityHash = identityHash,
            alias = "Pessoa Próxima #${identityHash.take(4)}",
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
