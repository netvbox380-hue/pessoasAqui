package br.com.pessoasaqui.core.proximity

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import br.com.pessoasaqui.PessoasAquiApp

/**
 * Recebe resultados de scan BLE filtrado mesmo se o Android recriar o processo.
 * Nenhum startForegroundService() é executado pelo broadcast: Android 12+
 * restringe iniciar FGS a partir de segundo plano.
 */
class BleScanResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_BLE_SCAN = "br.com.pessoasaqui.action.BLE_SCAN"
        private val deliveryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BLE_SCAN) return
        val repository = (context.applicationContext as? PessoasAquiApp)?.repository ?: return
        val manager = repository.bleManager ?: return
        try {
            val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java
                )
            } else {
                intent.getParcelableArrayListExtra<ScanResult>(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
                )
            } ?: emptyList()
            val error = if (intent.hasExtra(BluetoothLeScanner.EXTRA_ERROR_CODE)) {
                intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
            } else null
            // BroadcastReceiver roda na thread principal. goAsync() evita ANR
            // e executor serial preserva a ordem dos anúncios de vários peers.
            val pending = goAsync()
            try {
                deliveryExecutor.execute {
                    try {
                        manager.onBackgroundScanResults(results, error)
                    } catch (e: Exception) {
                        Log.w("PessoasAquiBLE", "Erro ao entregar scan BLE: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            } catch (e: Exception) {
                pending.finish()
                Log.w("PessoasAquiBLE", "Falha ao enfileirar broadcast BLE: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w("PessoasAquiBLE", "Resultado BLE em segundo plano inválido: ${e.message}")
        }
    }
}
