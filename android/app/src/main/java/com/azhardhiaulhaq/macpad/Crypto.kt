package com.azhardhiaulhaq.macpad

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** P-256 ECDH, HKDF-SHA256 and SAS derivation as specified in docs/PROTOCOL.md. */
internal object Crypto {
    const val POINT_SIZE = 65

    fun newKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    /** Uncompressed X9.63 point: 0x04 || X || Y, coordinates as 32-byte big-endian. */
    fun encodePoint(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        val out = ByteArray(POINT_SIZE)
        out[0] = 4
        putCoordinate(point.affineX, out, 1)
        putCoordinate(point.affineY, out, 33)
        return out
    }

    private fun putCoordinate(value: BigInteger, out: ByteArray, offset: Int) {
        val bytes = value.toByteArray()
        val len = minOf(bytes.size, 32)
        System.arraycopy(bytes, bytes.size - len, out, offset + 32 - len, len)
    }

    fun decodePoint(data: ByteArray, offset: Int, params: ECParameterSpec): PublicKey {
        require(data[offset] == 4.toByte()) { "not an uncompressed point" }
        val x = BigInteger(1, data.copyOfRange(offset + 1, offset + 33))
        val y = BigInteger(1, data.copyOfRange(offset + 33, offset + 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    /** The 32-byte X coordinate of the shared point. */
    fun ecdh(privateKey: PrivateKey, peer: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        val secret = agreement.generateSecret()
        require(secret.size == 32) { "unexpected ECDH secret size ${secret.size}" }
        return secret
    }

    /** RFC 5869 HKDF with HMAC-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val n = minOf(block.size, length - pos)
            System.arraycopy(block, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun sas(shared: ByteArray, transcript: ByteArray): Int {
        val b = hkdf(shared, "macpad-sas-v1".toByteArray(), transcript, 4)
        val v = ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or
            ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
        return (v % 1_000_000L).toInt()
    }

    fun pairingKey(shared: ByteArray, transcript: ByteArray): ByteArray =
        hkdf(shared, "macpad-pair-v1".toByteArray(), transcript, 32)

    fun sessionKey(shared: ByteArray, pairingKey: ByteArray, label: String, transcript: ByteArray): ByteArray =
        hkdf(shared, pairingKey, label.toByteArray() + transcript, 32)
}

/** AES-256-GCM for one direction. Nonce = u32 direction || u64 counter. Not thread-safe. */
internal class Gcm(key: ByteArray, direction: Int, private val mode: Int) {
    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val nonce = ByteArray(12)
    private var counter = 0L

    init {
        nonce[0] = (direction ushr 24).toByte()
        nonce[1] = (direction ushr 16).toByte()
        nonce[2] = (direction ushr 8).toByte()
        nonce[3] = direction.toByte()
    }

    /** Encrypts or decrypts [len] bytes of [src] into [dst]; returns the output length. */
    fun process(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        for (i in 0 until 8) nonce[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        counter++
        cipher.init(mode, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(src, off, len, dst, dstOff)
    }

    companion object {
        const val TAG_SIZE = 16
    }
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(digits[(b.toInt() ushr 4) and 0xf]).append(digits[b.toInt() and 0xf])
    }
    return sb.toString()
}
