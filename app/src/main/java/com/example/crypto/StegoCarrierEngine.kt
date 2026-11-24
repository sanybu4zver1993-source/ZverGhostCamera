package com.example.crypto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * StegoCarrierEngine (Часть Д — PixelKnot-style Steganography):
 * Hides an AES-256-GCM encrypted UTF-8 secret note inside the 2 least-significant bits
 * of RGB pixels of an ordinary cover Bitmap, outputting a lossless, metadata-free PNG.
 *
 * Frame layout inside RGB LSB stream (MSB-first bit pairs per byte):
 *   MAGIC (4B = "ZVST") | VERSION (1B = 1) | SALT (16B) | IV (12B) | CIPHERTEXT_LEN (4B big-endian) | CIPHERTEXT+TAG
 * Capacity: (width * height * 3 * 2) / 8 bytes = 0.75 bytes per pixel (~690 KB on a 1 MP cover).
 */
object StegoCarrierEngine {

    private val MAGIC = byteArrayOf('Z'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val HEADER_OVERHEAD = 4 + 1 + SALT_LEN + IV_LEN + 4 // 37 bytes + 16B GCM tag = 53B min
    private const val PBKDF2_ITERS = 120_000
    private val secureRandom = SecureRandom()

    data class StegoEmbedResult(
        val stegoBitmap: Bitmap,
        val pngBytes: ByteArray,
        val payloadBytesCount: Int,
        val maxCapacityBytes: Int
    )

    fun maxCapacityBytes(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val totalBits = width.toLong() * height.toLong() * 6L // 2 LSBs across R, G, B
        val totalBytes = (totalBits / 8L).toInt()
        return (totalBytes - HEADER_OVERHEAD - 16).coerceAtLeast(0)
    }

    fun embedSecretInBitmap(
        coverBitmap: Bitmap,
        secretText: String,
        passphrase: CharArray
    ): StegoEmbedResult {
        require(secretText.isNotEmpty()) { "Секретный текст пуст" }
        require(passphrase.isNotEmpty()) { "Задай пароль для стего-контейнера" }

        val plainBytes = secretText.toByteArray(Charsets.UTF_8)
        val capacity = maxCapacityBytes(coverBitmap.width, coverBitmap.height)
        require(plainBytes.size <= capacity) {
            "Секрет (${plainBytes.size} Б) не влезает в фото (лимит $capacity Б). Возьми кадр покрупнее."
        }

        val salt = ByteArray(SALT_LEN).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }
        val cipherBytes: ByteArray = try {
            val key = deriveAesKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(plainBytes)
        } finally {
            passphrase.fill('\u0000')
            plainBytes.fill(0)
        }

        val packet = ByteBuffer.allocate(4 + 1 + SALT_LEN + IV_LEN + 4 + cipherBytes.size).apply {
            put(MAGIC)
            put(VERSION)
            put(salt)
            put(iv)
            putInt(cipherBytes.size)
            put(cipherBytes)
        }.array()

        val w = coverBitmap.width
        val h = coverBitmap.height
        val pixels = IntArray(w * h)
        coverBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var bitIndex = 0
        val totalPacketBits = packet.size * 8

        for (i in pixels.indices) {
            if (bitIndex >= totalPacketBits) break
            val argb = pixels[i]
            val a = (argb ushr 24) and 0xFF
            var r = (argb ushr 16) and 0xFF
            var g = (argb ushr 8) and 0xFF
            var b = argb and 0xFF

            if (bitIndex < totalPacketBits) {
                r = (r and 0xFC) or readTwoBits(packet, bitIndex)
                bitIndex += 2
            }
            if (bitIndex < totalPacketBits) {
                g = (g and 0xFC) or readTwoBits(packet, bitIndex)
                bitIndex += 2
            }
            if (bitIndex < totalPacketBits) {
                b = (b and 0xFC) or readTwoBits(packet, bitIndex)
                bitIndex += 2
            }
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Lossless ARGB_8888 bitmap + PNG keeps RGB pixels intact without chroma subsampling
        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
        val baos = ByteArrayOutputStream()
        outBmp.compress(Bitmap.CompressFormat.PNG, 100, baos)

        return StegoEmbedResult(
            stegoBitmap = outBmp,
            pngBytes = baos.toByteArray(),
            payloadBytesCount = plainBytes.size,
            maxCapacityBytes = capacity
        )
    }

    fun extractSecretFromPngBytes(pngBytes: ByteArray, passphrase: CharArray): String {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts)
            ?: throw IllegalArgumentException("Не удалось декодировать изображение")
        return try {
            extractSecretFromBitmap(bmp, passphrase)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    fun extractSecretFromBitmap(stegoBitmap: Bitmap, passphrase: CharArray): String {
        try {
            require(passphrase.isNotEmpty()) { "Введи пароль для расшифровки" }
            val w = stegoBitmap.width
            val h = stegoBitmap.height
            val pixels = IntArray(w * h)
            stegoBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val maxReadableBytes = (pixels.size.toLong() * 6L / 8L).toInt()
            require(maxReadableBytes >= HEADER_OVERHEAD + 16) { "Изображение слишком мало для стего-контейнера" }

            // First decode header (37 bytes)
            val header = readBytesFromPixels(pixels, 0, HEADER_OVERHEAD)
            if (header[0] != MAGIC[0] || header[1] != MAGIC[1] || header[2] != MAGIC[2] || header[3] != MAGIC[3]) {
                throw IllegalStateException("Скрытый контейнер Zver Stego не обнаружен (возможен JPEG-пережим)")
            }
            if (header[4] != VERSION) {
                throw IllegalStateException("Неподдерживаемая версия стего-формата: ${header[4]}")
            }

            val buf = ByteBuffer.wrap(header, 5, HEADER_OVERHEAD - 5)
            val salt = ByteArray(SALT_LEN).also { buf.get(it) }
            val iv = ByteArray(IV_LEN).also { buf.get(it) }
            val cipherLen = buf.int

            if (cipherLen <= 16 || HEADER_OVERHEAD + cipherLen > maxReadableBytes) {
                throw IllegalStateException("Повреждённый заголовок стего-контейнера")
            }

            val fullPacket = readBytesFromPixels(pixels, 0, HEADER_OVERHEAD + cipherLen)
            val cipherBytes = fullPacket.copyOfRange(HEADER_OVERHEAD, HEADER_OVERHEAD + cipherLen)

            val key = deriveAesKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = try {
                cipher.doFinal(cipherBytes)
            } catch (_: Exception) {
                throw IllegalStateException("Неверный пароль или битый стего-контейнер")
            }
            return try {
                String(plain, Charsets.UTF_8)
            } finally {
                plain.fill(0)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun readTwoBits(packet: ByteArray, bitIndex: Int): Int {
        val byteIdx = bitIndex ushr 3
        val shift = 6 - (bitIndex and 7)
        return (packet[byteIdx].toInt() ushr shift) and 0x03
    }

    private fun readBytesFromPixels(pixels: IntArray, startByte: Int, count: Int): ByteArray {
        val out = ByteArray(count)
        var outByteIdx = 0
        var bitOffsetInByte = 0
        var curByteVal = 0

        val startBit = startByte * 8
        val endBit = (startByte + count) * 8
        var bitCursor = 0

        for (argb in pixels) {
            if (bitCursor >= endBit) break
            val channels = intArrayOf((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)
            for (ch in channels) {
                if (bitCursor >= endBit) break
                if (bitCursor >= startBit && outByteIdx < count) {
                    val twoBits = ch and 0x03
                    curByteVal = (curByteVal shl 2) or twoBits
                    bitOffsetInByte += 2
                    if (bitOffsetInByte == 8) {
                        out[outByteIdx++] = curByteVal.toByte()
                        curByteVal = 0
                        bitOffsetInByte = 0
                    }
                }
                bitCursor += 2
            }
        }
        return out.copyOf(min(count, out.size))
    }

    private fun deriveAesKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES").also { raw.fill(0) }
    }
}
