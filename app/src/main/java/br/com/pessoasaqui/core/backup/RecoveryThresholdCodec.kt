package br.com.pessoasaqui.core.backup

import java.security.SecureRandom

/**
 * Partes independentes de recuperação 2-de-N sobre GF(256), usando Shamir
 * com polinômio de grau 1. Uma única parte não revela o PIN nem o segredo.
 * A liberação das partes requer verificação criptográfica de sessão no backend.
 */
object RecoveryThresholdCodec {
    private const val DATA_SIZE = 41 // tamanho PIN + 8 dígitos ASCII + 32 bytes de segredo
    const val SHARE_SIZE = DATA_SIZE + 1
    private val rng = SecureRandom()

    data class RecoveryMaterial(val pin: String, val recoverySecret: String)

    private fun mul(a: Int, b: Int): Int {
        var x = a and 0xff
        var y = b and 0xff
        var out = 0
        repeat(8) {
            if ((y and 1) != 0) out = out xor x
            val hi = (x and 0x80) != 0
            x = (x shl 1) and 0xff
            if (hi) x = x xor 0x1b
            y = y ushr 1
        }
        return out
    }

    private fun inverse(value: Int): Int {
        require(value in 1..255) { "Divisão por zero em GF256" }
        var acc = 1
        var power = value
        var exponent = 254
        while (exponent != 0) {
            if ((exponent and 1) != 0) acc = mul(acc, power)
            power = mul(power, power)
            exponent = exponent ushr 1
        }
        return acc
    }

    fun split(pin: String, recoverySecret: String, numberOfShares: Int): List<ByteArray> {
        require(numberOfShares in 2..255) { "Exige 2 a 255 familiares" }
        require(pin.length in 4..8 && pin.all { it in '0'..'9' }) { "PIN inválido" }
        require(recoverySecret.matches(Regex("^PA-VAULT-V3-[0-9A-F]{64}$"))) {
            "Segredo V3 inválido"
        }
        val material = ByteArray(DATA_SIZE)
        material[0] = pin.length.toByte()
        pin.toByteArray(Charsets.US_ASCII).copyInto(material, 1)
        val prefixLength = "PA-VAULT-V3-".length
        repeat(32) { index ->
            material[9+index] = recoverySecret.substring(prefixLength+index*2,
                prefixLength+index*2+2).toInt(16).toByte()
        }
        val slopes = ByteArray(DATA_SIZE).also { rng.nextBytes(it) }
        try {
            return (1..numberOfShares).map { x ->
                ByteArray(SHARE_SIZE).also { share ->
                    share[0] = x.toByte()
                    for(i in 0 until DATA_SIZE) {
                        share[i+1] = ((material[i].toInt() and 255) xor
                            mul(slopes[i].toInt() and 255, x)).toByte()
                    }
                }
            }
        } finally {
            material.fill(0)
            slopes.fill(0)
        }
    }

    fun combine(first: ByteArray, second: ByteArray): RecoveryMaterial {
        require(first.size == SHARE_SIZE && second.size == SHARE_SIZE) { "Partes inválidas" }
        val x1 = first[0].toInt() and 255
        val x2 = second[0].toInt() and 255
        require(x1 in 1..255 && x2 in 1..255 && x1 != x2) {
            "Partes precisam ser distintas"
        }
        val inverseDiff = inverse(x1 xor x2)
        val material = ByteArray(DATA_SIZE)
        try {
            for(i in 0 until DATA_SIZE) {
                val y1 = first[i+1].toInt() and 255
                val y2 = second[i+1].toInt() and 255
                val slope = mul(y1 xor y2, inverseDiff)
                material[i] = (y1 xor mul(slope, x1)).toByte()
            }
            val length = material[0].toInt() and 255
            require(length in 4..8 && (1..length).all { material[it].toInt() in 48..57 }) {
                "Dados de recuperação inválidos"
            }
            require(((length+1)..8).all { material[it].toInt() == 0 }) {
                "Dados de recuperação adulterados"
            }
            val pin = String(material, 1, length, Charsets.US_ASCII)
            val secret = "PA-VAULT-V3-" + material.copyOfRange(9, 41)
                .joinToString("") { "%02X".format(it.toInt() and 255) }
            return RecoveryMaterial(pin, secret)
        } finally { material.fill(0) }
    }
}
