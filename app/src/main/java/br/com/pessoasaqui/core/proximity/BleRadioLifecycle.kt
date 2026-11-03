package br.com.pessoasaqui.core.proximity

/**
 * Estados explícitos das operações de rádio. Um timeout de ausência de pessoas
 * NÃO significa scanner quebrado: falhas vêm de callbacks/erros da pilha.
 */
internal enum class BleRadioState { STOPPED, STARTING, ACTIVE, FAILED }

internal class BleRadioLifecycle {
    var state: BleRadioState = BleRadioState.STOPPED
        private set
    var failures: Int = 0
        private set
    private var nextRetryAtMs: Long = 0L

    @Synchronized
    fun starting() {
        state = BleRadioState.STARTING
    }

    @Synchronized
    fun started() {
        state = BleRadioState.ACTIVE
        failures = 0
        nextRetryAtMs = 0L
    }

    @Synchronized
    fun failed(now: Long = System.currentTimeMillis()) {
        state = BleRadioState.FAILED
        failures = (failures + 1).coerceAtMost(8)
        val delay = (2_000L shl (failures - 1)).coerceAtMost(120_000L)
        nextRetryAtMs = now + delay
    }

    @Synchronized
    fun stopped() {
        state = BleRadioState.STOPPED
        failures = 0
        nextRetryAtMs = 0L
    }

    @Synchronized
    fun shouldRetry(now: Long = System.currentTimeMillis()): Boolean =
        state == BleRadioState.FAILED && now >= nextRetryAtMs
}
