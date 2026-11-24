package com.example.crypto

import java.security.SecureRandom
import java.util.Arrays

/**
 * SecretSplitEngine (Часть Е):
 * True information-theoretic Shamir's Secret Sharing over GF(2^8) (AES field polynomial 0x11B).
 *
 * Splits a UTF-8 passphrase/key (e.g. .zvb backup password or stego passphrase) into N portable
 * formatted shares (`ZVS1-<threshold>-<xHex>-<crc8Hex>-<payloadHex>`), where any [threshold] shares
 * reconstruct the exact original secret and fewer than [threshold] reveal zero bits of information.
 */
object SecretSplitEngine {

    private const val PREFIX = "ZVS1"
    private const val GF_POLY = 0x11B
    private val secureRandom = SecureRandom()

    data class SecretShare(
        val index: Int, // 1..255 (x coordinate)
        val threshold: Int,
        val payload: ByteArray,
        val encoded: String
    )

    private val expTable = IntArray(512)
    private val logTable = IntArray(256)

    init {
        // Generator 0x03 is primitive in GF(2^8) with polynomial 0x11B
        var x = 1
        for (i in 0 until 255) {
            expTable[i] = x
            expTable[i + 255] = x
            logTable[x] = i
            x = mulNoTable(x, 0x03)
        }
        logTable[0] = 0
    }

    private fun mulNoTable(aIn: Int, bIn: Int): Int {
        var a = aIn and 0xFF
        var b = bIn and 0xFF
        var p = 0
        for (i in 0 until 8) {
            if ((b and 1) != 0) p = p xor a
            val hiBit = (a and 0x80) != 0
            a = (a shl 1) and 0xFF
            if (hiBit) a = a xor (GF_POLY and 0xFF)
            b = b ushr 1
        }
        return p and 0xFF
    }

    private fun gfMul(a: Int, b: Int): Int {
        val aa = a and 0xFF
        val bb = b and 0xFF
        if (aa == 0 || bb == 0) return 0
        return expTable[logTable[aa] + logTable[bb]]
    }

    private fun gfDiv(a: Int, b: Int): Int {
        val aa = a and 0xFF
        val bb = b and 0xFF
        require(bb != 0) { "Division by zero in GF(256)" }
        if (aa == 0) return 0
        return expTable[(logTable[aa] - logTable[bb] + 255) % 255]
    }

    fun splitSecret(
        secretText: String,
        totalShares: Int = 3,
        threshold: Int = 2
    ): List<SecretShare> {
        require(secretText.isNotEmpty()) { "Секрет не может быть пустым" }
        val secretBytes = secretText.toByteArray(Charsets.UTF_8)
        return try {
            splitSecretBytes(secretBytes, totalShares, threshold)
        } finally {
            Arrays.fill(secretBytes, 0.toByte())
        }
    }

    fun splitSecretBytes(
        secretBytes: ByteArray,
        totalShares: Int = 3,
        threshold: Int = 2
    ): List<SecretShare> {
        require(secretBytes.isNotEmpty()) { "Пустой массив секрета" }
        require(threshold in 2..16) { "Порог K должен быть от 2 до 16" }
        require(totalShares in threshold..32) { "Количество долей N должно быть от K до 32" }

        val sharePayloads = Array(totalShares) { ByteArray(secretBytes.size) }
        val coeffs = ByteArray(threshold)
        val randCoeffs = ByteArray(threshold - 1)

        try {
            for (byteIdx in secretBytes.indices) {
                coeffs[0] = secretBytes[byteIdx]
                secureRandom.nextBytes(randCoeffs)
                System.arraycopy(randCoeffs, 0, coeffs, 1, randCoeffs.size)

                for (s in 0 until totalShares) {
                    val x = s + 1
                    var y = 0
                    for (deg in coeffs.indices.reversed()) {
                        y = gfMul(y, x) xor (coeffs[deg].toInt() and 0xFF)
                    }
                    sharePayloads[s][byteIdx] = y.toByte()
                }
            }
        } finally {
            Arrays.fill(coeffs, 0.toByte())
            Arrays.fill(randCoeffs, 0.toByte())
        }

        return sharePayloads.mapIndexed { idx, payload ->
            val x = idx + 1
            val encoded = encodeShareString(threshold, x, payload)
            SecretShare(index = x, threshold = threshold, payload = payload, encoded = encoded)
        }
    }

    fun combineShares(rawShareLines: List<String>): String {
        val parsed = rawShareLines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseShare(it) }
            .distinctBy { it.index }

        require(parsed.isNotEmpty()) { "Не передано ни одной валидной доли" }

        val k = parsed.first().threshold
        val len = parsed.first().payload.size
        require(parsed.all { it.threshold == k }) { "Доли относятся к разным схемам порога (K)" }
        require(parsed.all { it.payload.size == len }) { "Доли имеют разную длину данных" }
        require(parsed.size >= k) { "Недостаточно долей: передано ${parsed.size}, требуется минимум $k" }

        val subset = parsed.take(k)
        val recovered = ByteArray(len)
        return try {
            for (byteIdx in 0 until len) {
                var acc = 0
                for (i in subset.indices) {
                    val xi = subset[i].index
                    val yi = subset[i].payload[byteIdx].toInt() and 0xFF
                    var num = 1
                    var den = 1
                    for (j in subset.indices) {
                        if (i == j) continue
                        val xj = subset[j].index
                        // Lagrange basis evaluated at x = 0 in GF(2^8): prod(0 - xj) / prod(xi - xj)
                        num = gfMul(num, xj)
                        den = gfMul(den, xi xor xj)
                    }
                    val li = gfDiv(num, den)
                    acc = acc xor gfMul(yi, li)
                }
                recovered[byteIdx] = acc.toByte()
            }
            String(recovered, Charsets.UTF_8)
        } finally {
            Arrays.fill(recovered, 0.toByte())
        }
    }

    fun parseShare(raw: String): SecretShare {
        val parts = raw.trim().split("-")
        require(parts.size == 5 && parts[0] == PREFIX) {
            "Неверный формат доли (ожидается $PREFIX-K-XX-CRC-PAYLOAD)"
        }
        val k = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Неверный порог K")
        val x = parts[2].toIntOrNull(16) ?: throw IllegalArgumentException("Неверный индекс X")
        val crc = parts[3].toIntOrNull(16) ?: throw IllegalArgumentException("Неверная CRC-сумма")
        val payloadHex = parts[4]
        require(x in 1..255 && k in 2..16) { "Недопустимые параметры доли" }
        require(payloadHex.length >= 2 && payloadHex.length % 2 == 0) { "Повреждённый hex полезной нагрузки" }

        val payload = hexToBytes(payloadHex)
        val expectedCrc = computeCrc8(k, x, payload)
        require(crc == expectedCrc) { "Ошибка контрольной суммы CRC-8 в доле #$x (проверь опечатку)" }
        return SecretShare(index = x, threshold = k, payload = payload, encoded = raw.trim())
    }

    private fun encodeShareString(threshold: Int, index: Int, payload: ByteArray): String {
        val crc = computeCrc8(threshold, index, payload)
        return buildString {
            append(PREFIX)
            append('-')
            append(threshold)
            append('-')
            append("%02X".format(index))
            append('-')
            append("%02X".format(crc))
            append('-')
            for (b in payload) {
                append("%02X".format(b))
            }
        }
    }

    private fun computeCrc8(threshold: Int, index: Int, payload: ByteArray): Int {
        var crc = 0x5A
        crc = crcStep(crc, threshold and 0xFF)
        crc = crcStep(crc, index and 0xFF)
        for (b in payload) {
            crc = crcStep(crc, b.toInt() and 0xFF)
        }
        return crc and 0xFF
    }

    private fun crcStep(crcIn: Int, data: Int): Int {
        var crc = (crcIn xor data) and 0xFF
        for (i in 0 until 8) {
            crc = if ((crc and 0x80) != 0) {
                ((crc shl 1) xor 0x07) and 0xFF
            } else {
                (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Недопустимый hex-символ в доле" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
