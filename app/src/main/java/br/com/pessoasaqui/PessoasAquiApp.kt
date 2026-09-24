package br.com.pessoasaqui

import android.app.Application
import br.com.pessoasaqui.data.repository.PessoasAquiRepository

import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import br.com.pessoasaqui.core.proximity.BleManager

class PessoasAquiApp : Application() {

    // Repositório singleton compartilhado na aplicação
    lateinit var repository: PessoasAquiRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val crypto = CryptoIdentityManager(this)
        val ble = BleManager(this)
        repository = PessoasAquiRepository(context = this, cryptoIdentityManager = crypto, bleManager = ble)
    }
}
