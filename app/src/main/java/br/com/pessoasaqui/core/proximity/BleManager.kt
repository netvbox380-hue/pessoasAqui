package br.com.pessoasaqui.core.proximity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
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

    val isBluetoothSupported: Boolean get() = bluetoothAdapter != null
    val isBluetoothEnabled: Boolean get() = bluetoothAdapter?.isEnabled == true

    /**
     * Inicia a transmissão (Advertising) deste aparelho para que outros aparelhos a até 10m o vejam.
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(
        myIdentityHash: String,
        myAlias: String,
        myIntent: UserIntent
    ) {
        if (!isBluetoothEnabled) return
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return

        stopAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        // Monta carga de dados: Hash de identidade (8 chars) + ordinal de intenção (1 byte)
        val payload = "$myIdentityHash:${myIntent.ordinal}".toByteArray(StandardCharsets.UTF_8)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
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
            advertiser?.startAdvertising(settings, data, advertiseCallback)
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
        if (!isBluetoothEnabled) return
        scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        stopScanning()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
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
            scanner?.startScan(listOf(filter), settings, scanCallback)
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
        val rssi = result.rssi
        val distance = distanceEstimator.estimateDistanceMeters(rssi)

        // REGRA DE OURO (Seção 3 do Prompt):
        // Se a distância estimada ultrapassar 10 metros, descarta imediatamente!
        if (!distanceEstimator.isWithin10Meters(distance)) {
            return
        }

        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(ParcelUuid(BleConstants.SERVICE_UUID))
        var identityHash = result.device.address.replace(":", "").take(8)
        var intent = UserIntent.QUERO_CONVERSAR

        if (serviceData != null) {
            try {
                val str = String(serviceData, StandardCharsets.UTF_8)
                val parts = str.split(":")
                if (parts.isNotEmpty()) identityHash = parts[0]
                if (parts.size > 1) {
                    val intentIdx = parts[1].toIntOrNull() ?: 0
                    intent = UserIntent.values().getOrElse(intentIdx) { UserIntent.QUERO_CONVERSAR }
                }
            } catch (e: Exception) {
                // Fallback gracioso
            }
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
