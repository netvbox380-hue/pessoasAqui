package br.com.pessoasaqui

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import br.com.pessoasaqui.core.proximity.BleConstants
import br.com.pessoasaqui.core.proximity.BleManager
import br.com.pessoasaqui.core.proximity.DistanceEstimator
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.UserIntent
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Testes unitários para o Advertising BLE Conectável e Descoberta de Presença (FASE 3D - Correção).
 *
 * Valida:
 * 1. Configuração estrita de anúncio conectável (setConnectable = true) para suporte a transporte GATT.
 * 2. Cálculo matemático e conformidade dos pacotes de anúncio com o limite físico de 31 bytes (Bluetooth Low Energy).
 * 3. Truncamento seguro de strings UTF-8 para evitar corrupção de caracteres acentuados ou emojis.
 * 4. Tratamento de fallback quando ocorre erro ADVERTISE_FAILED_DATA_TOO_LARGE.
 * 5. Preservação integral do Radar de 10m e validação de formatos legítimos de identidade técnica.
 */
class BleAdvertisingAndDiscoveryTest {

    // Driver simulado de transmissor BLE para testes unitários em JVM
    class MockAdvertiserDriver : BleManager.BleAdvertiserDriver {
        var lastParams: BleManager.AdvertisingParams? = null
        var startAdvertisingCallCount = 0
        var stopAdvertisingCallCount = 0

        var simulateFailureCode: Int? = null

        override fun startAdvertising(
            params: BleManager.AdvertisingParams,
            callback: (isSuccess: Boolean, errorCode: Int?) -> Unit
        ) {
            startAdvertisingCallCount++
            lastParams = params

            val failure = simulateFailureCode
            if (failure != null && startAdvertisingCallCount == 1) {
                callback(false, failure)
            } else {
                callback(true, null)
            }
        }

        override fun stopAdvertising() {
            stopAdvertisingCallCount++
        }
    }

    @Test
    fun testMathematicalLimitsOfLegacyBleAdvertisingPacket() {
        // 1. Limite físico legado do Bluetooth Core Specification
        assertEquals(31, BleManager.MAX_LEGACY_ADV_BYTES)
        assertEquals(3, BleManager.FLAGS_OVERHEAD_BYTES)
        assertEquals(4, BleManager.SERVICE_DATA_16BIT_HEADER_BYTES)

        // 2. Quando conectável (ADV_IND), Flags consomem 3 bytes
        val maxConnectablePayload = BleManager.MAX_CONNECTABLE_PRIMARY_PAYLOAD_BYTES // 24 bytes
        val totalConnectableSize = BleManager.calculateTotalPacketSize(maxConnectablePayload, isConnectable = true)
        assertEquals(31, totalConnectableSize)
        assertTrue("Pacote conectável deve caber rigorosamente em 31 bytes", totalConnectableSize <= 31)

        // 3. Se payload fosse 25 bytes em anúncio conectável, excederia 31 bytes
        val oversizedPayload = 25
        val oversizedTotal = BleManager.calculateTotalPacketSize(oversizedPayload, isConnectable = true)
        assertEquals(32, oversizedTotal)
        assertTrue("25 bytes de payload em ADV_IND ultrapassa 31 bytes", oversizedTotal > 31)

        // 4. Scan Response não possui Flags, logo suporta até 27 bytes de payload
        val maxScanRespPayload = BleManager.MAX_SCAN_RESPONSE_PAYLOAD_BYTES // 27 bytes
        val totalScanRespSize = BleManager.calculateTotalPacketSize(maxScanRespPayload, isConnectable = false)
        assertEquals(31, totalScanRespSize)
        assertTrue("Scan response deve caber em 31 bytes", totalScanRespSize <= 31)
    }

    @Test
    fun testTakeUtf8BytesPreservesMultibyteIntegrity() {
        // String ASCII simples
        val ascii = "Carlos"
        assertEquals("Carl", BleManager.takeUtf8Bytes(ascii, 4))
        assertEquals("Carlos", BleManager.takeUtf8Bytes(ascii, 10))

        // String com caracteres acentuados (UTF-8 de 2 bytes: 'á' = 0xC3 0xA1)
        val accented = "Fábio"
        // 'F' (1B) + 'á' (2B) = 3B; 'b' (1B) = 4B.
        assertEquals("Fáb", BleManager.takeUtf8Bytes(accented, 4))
        // Se limite for 2B, 'Fá' precisaria de 3B, logo só cabe 'F'
        assertEquals("F", BleManager.takeUtf8Bytes(accented, 2))

        // String com emoji (UTF-8 de 4 bytes)
        val emoji = "🚀Mundo"
        // '🚀' consome 4 bytes. Se limite for 3B, não deve retornar meio emoji corrompido
        assertEquals("", BleManager.takeUtf8Bytes(emoji, 3))
        assertEquals("🚀", BleManager.takeUtf8Bytes(emoji, 4))
        assertEquals("🚀M", BleManager.takeUtf8Bytes(emoji, 5))
    }

    @Test
    fun testBuildAdvertisingPayloadLimits() {
        val identityHash = "A1B2C3D4E5F60718" // 16 caracteres hex
        val intent = UserIntent.QUERO_CONVERSAR

        // 1. Pacote Primário Conectável (máximo 24 bytes de payload)
        val primary = BleManager.buildAdvertisingPayload(
            identityHash = identityHash,
            intent = intent,
            alias = "Fábio Silva",
            maxPayloadBytes = BleManager.MAX_CONNECTABLE_PRIMARY_PAYLOAD_BYTES
        )
        val primaryStr = String(primary, StandardCharsets.UTF_8)
        assertTrue("Payload primário não pode exceder 24 bytes", primary.size <= 24)
        assertTrue("Deve conter ID e intenção", primaryStr.startsWith("A1B2C3D4E5F60718:0"))
        val totalPrimaryPacketSize = BleManager.calculateTotalPacketSize(primary.size, isConnectable = true)
        assertTrue("Tamanho físico total não pode ultrapassar 31 bytes", totalPrimaryPacketSize <= 31)

        // 2. Scan Response (máximo 27 bytes de payload)
        val scanResp = BleManager.buildAdvertisingPayload(
            identityHash = identityHash,
            intent = intent,
            alias = "Fábio Silva",
            maxPayloadBytes = BleManager.MAX_SCAN_RESPONSE_PAYLOAD_BYTES
        )
        val scanRespStr = String(scanResp, StandardCharsets.UTF_8)
        assertTrue("Payload de scan response não pode exceder 27 bytes", scanResp.size <= 27)
        assertTrue("Scan response deve conter mais caracteres de apelido", scanRespStr.length >= primaryStr.length)
        val totalScanRespSize = BleManager.calculateTotalPacketSize(scanResp.size, isConnectable = false)
        assertTrue("Tamanho físico total de scan response não pode ultrapassar 31 bytes", totalScanRespSize <= 31)

        // 3. Apelido Vazio
        val noAlias = BleManager.buildAdvertisingPayload(
            identityHash = identityHash,
            intent = intent,
            alias = ""
        )
        assertEquals("A1B2C3D4E5F60718:0", String(noAlias, StandardCharsets.UTF_8))
        assertEquals(18, noAlias.size)
    }

    @Test
    fun testStartAdvertisingConfiguresConnectableModeAndNotifiesSuccess() {
        val mockDriver = MockAdvertiserDriver()
        val bleManager = BleManager(
            context = null,
            distanceEstimator = DistanceEstimator(),
            customAdvertiserDriver = mockDriver
        )
        bleManager.isMockBluetoothEnabled = true

        var advertisedSuccess: Boolean? = null
        bleManager.onAdvertisingStateChanged = { success, _ ->
            advertisedSuccess = success
        }

        bleManager.startAdvertising(
            myIdentityHash = "1122334455667788",
            myAlias = "Alice",
            myIntent = UserIntent.QUERO_CONVERSAR
        )

        assertEquals("startAdvertising deve ter sido chamado", 1, mockDriver.startAdvertisingCallCount)
        assertNotNull("Params devem ter sido passados", mockDriver.lastParams)
        assertTrue("O anúncio DEVE ser configurado como conectável para transporte GATT", mockDriver.lastParams!!.isConnectable)
        assertEquals("AdvertiseMode deve ser LOW_LATENCY", AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY, mockDriver.lastParams!!.mode)
        assertEquals("TxPowerLevel deve ser HIGH", AdvertiseSettings.ADVERTISE_TX_POWER_HIGH, mockDriver.lastParams!!.txPowerLevel)
        assertTrue("Callback deve confirmar sucesso", advertisedSuccess == true)
    }

    @Test
    fun testStartAdvertisingHandlesDataTooLargeFallback() {
        val mockDriver = MockAdvertiserDriver()
        mockDriver.simulateFailureCode = AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE

        val bleManager = BleManager(
            context = null,
            distanceEstimator = DistanceEstimator(),
            customAdvertiserDriver = mockDriver
        )
        bleManager.isMockBluetoothEnabled = true

        bleManager.startAdvertising(
            myIdentityHash = "AABBCCDDEEFF0011",
            myAlias = "ApelidoMuitoLongoComCaracteresExtras",
            myIntent = UserIntent.QUERO_CONVERSAR
        )

        // Deve ter chamado 2 vezes: primeira tentativa normal, segunda fallback ultra-compacto
        assertEquals("Fallback deve ter sido acionado após erro DATA_TOO_LARGE", 2, mockDriver.startAdvertisingCallCount)
        assertNotNull("Params de fallback devem ser conectáveis", mockDriver.lastParams)
        assertTrue("Fallback de anúncio deve manter isConnectable = true", mockDriver.lastParams!!.isConnectable)
        assertEquals("Payload de fallback deve ser ultra-compacto (18 bytes)", 18, mockDriver.lastParams!!.primaryPayload.size)
    }

    @Test
    fun testContinuityMonitorRestartsStalledAdvertiserWithoutTogglingBluetooth() {
        val driver = MockAdvertiserDriver()
        val ble = BleManager(context = null, customAdvertiserDriver = driver)
        ble.isMockBluetoothEnabled = true
        ble.startAdvertising("1122334455667788", "Teste", UserIntent.QUERO_CONVERSAR)

        assertTrue(ble.isAdvertisingActive)
        val started = driver.startAdvertisingCallCount
        ble.ensureDiscoveryContinuity()
        assertEquals("Monitor não deve reiniciar advertising saudável antes do limite",
            started, driver.startAdvertisingCallCount)

        ble.ensureDiscoveryContinuity(System.currentTimeMillis() + BleManager.ADVERTISING_REFRESH_INTERVAL_MS + 1000L)
        assertEquals(started + 1, driver.startAdvertisingCallCount)
        assertTrue(ble.isAdvertisingActive)
    }

    @Test
    fun testContinuityMonitorRetriesAdvertiserAfterStartFailure() {
        val driver = MockAdvertiserDriver()
        driver.simulateFailureCode = AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR
        val ble = BleManager(context = null, customAdvertiserDriver = driver)
        ble.isMockBluetoothEnabled = true
        ble.startAdvertising("1122334455667788", "Teste", UserIntent.QUERO_CONVERSAR)
        assertFalse(ble.isAdvertisingActive)
        ble.ensureDiscoveryContinuity()
        assertEquals(2, driver.startAdvertisingCallCount)
        assertTrue(ble.isAdvertisingActive)
    }

    @Test
    fun testStopAdvertisingInvokesDriver() {
        val mockDriver = MockAdvertiserDriver()
        val bleManager = BleManager(
            context = null,
            distanceEstimator = DistanceEstimator(),
            customAdvertiserDriver = mockDriver
        )
        bleManager.isMockBluetoothEnabled = true

        bleManager.startAdvertising("A1B2C3D4E5F60718", "Bob", UserIntent.QUERO_CONVERSAR)
        val countAfterStart = mockDriver.stopAdvertisingCallCount
        bleManager.stopAdvertising()

        assertEquals("stopAdvertising deve ter sido incrementado ao parar", countAfterStart + 1, mockDriver.stopAdvertisingCallCount)
    }

    @Test
    fun testProcessScanRecordDataWithin10MetersAcceptsPeer() {
        val bleManager = BleManager(
            context = null,
            distanceEstimator = DistanceEstimator()
        )

        val discoveredPeers = mutableListOf<NearbyPerson>()
        var recordedMac: Pair<String, String>? = null
        bleManager.onDeviceAddressDiscovered = { hash, mac ->
            recordedMac = Pair(hash, mac)
        }

        // Payload válido de outro aparelho a ~1 metro (RSSI -58 dBm)
        val validPayload = "9988776655443322:0:Carol".toByteArray(StandardCharsets.UTF_8)
        bleManager.processScanRecordData(
            serviceData = validPayload,
            macAddress = "AA:BB:CC:DD:EE:FF",
            rawRssi = -58,
            onPeerDiscovered = { discoveredPeers.add(it) }
        )

        assertEquals("Deve ter descoberto 1 pessoa próxima", 1, discoveredPeers.size)
        val peer = discoveredPeers[0]
        assertEquals("9988776655443322", peer.technicalIdentityHash)
        assertEquals("Carol", peer.alias)
        assertEquals(UserIntent.QUERO_CONVERSAR, peer.intent)
        assertTrue("Distância deve ser <= 10m", peer.estimatedDistanceMeters <= 10.0)
        assertEquals(Pair("9988776655443322", "AA:BB:CC:DD:EE:FF"), recordedMac)
    }

    @Test
    fun testProcessScanRecordDataAbove10MetersDiscardsPeer() {
        val bleManager = BleManager(
            context = null,
            distanceEstimator = DistanceEstimator()
        )

        val discoveredPeers = mutableListOf<NearbyPerson>()

        // RSSI de -95 dBm corresponde a mais de 25 metros de distância
        val farPayload = "9988776655443322:0:Carol".toByteArray(StandardCharsets.UTF_8)
        bleManager.processScanRecordData(
            serviceData = farPayload,
            macAddress = "AA:BB:CC:DD:EE:FF",
            rawRssi = -95,
            onPeerDiscovered = { discoveredPeers.add(it) }
        )

        assertEquals("Aparelho a mais de 10 metros deve ser sumariamente descartado pelo Radar", 0, discoveredPeers.size)
    }

    @Test
    fun testProcessScanRecordDataRejectsNonHexArbitraryDevices() {
        val bleManager = BleManager(
            context = null,
            distanceEstimator = DistanceEstimator()
        )

        val discoveredPeers = mutableListOf<NearbyPerson>()

        // Simula broadcast arbitrário de fone de ouvido ou relógio inteligente
        val foreignPayload = "not-a-hex-id:0:Headphones".toByteArray(StandardCharsets.UTF_8)
        bleManager.processScanRecordData(
            serviceData = foreignPayload,
            macAddress = "11:22:33:44:55:66",
            rawRssi = -50,
            onPeerDiscovered = { discoveredPeers.add(it) }
        )

        assertEquals("Dispositivos que não contenham ID hexadecimal de 8 a 16 caracteres devem ser rejeitados", 0, discoveredPeers.size)
    }

    @Test
    fun testDistinctPeerWithSameAliasStillAppearsInRadar() {
        val driver = MockAdvertiserDriver()
        val ble = BleManager(context = null, customAdvertiserDriver = driver)
        ble.isMockBluetoothEnabled = true
        ble.startAdvertising("1111222233334444", "Mesmo Nome", UserIntent.QUERO_CONVERSAR)

        val discovered = mutableListOf<NearbyPerson>()
        ble.processScanRecordData(
            serviceData = "AAAA222233334444:0:Mesmo Nome".toByteArray(StandardCharsets.UTF_8),
            macAddress = "11:22:33:44:55:66",
            rawRssi = -55,
            onPeerDiscovered = { discovered.add(it) }
        )
        assertEquals("Só o ID técnico identifica o próprio aparelho", 1, discovered.size)
        assertEquals("AAAA222233334444", discovered.first().technicalIdentityHash)
    }

    @Test
    fun testProcessScanRecordDataRejectsSelfBroadcast() {
        val bleManager = BleManager(
            context = null,
            distanceEstimator = DistanceEstimator()
        )

        // Configura meu próprio ID
        bleManager.startAdvertising(
            myIdentityHash = "1234567890ABCDEF",
            myAlias = "EuMesmo",
            myIntent = UserIntent.QUERO_CONVERSAR
        )

        val discoveredPeers = mutableListOf<NearbyPerson>()
        val selfPayload = "1234567890ABCDEF:0:EuMesmo".toByteArray(StandardCharsets.UTF_8)

        bleManager.processScanRecordData(
            serviceData = selfPayload,
            macAddress = "00:11:22:33:44:55",
            rawRssi = -40,
            onPeerDiscovered = { discoveredPeers.add(it) }
        )

        assertEquals("O próprio aparelho não pode aparecer como contato próximo", 0, discoveredPeers.size)
    }
}
