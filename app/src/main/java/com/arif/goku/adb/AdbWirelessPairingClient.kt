package com.arif.goku.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * Pure-Kotlin Android 11+ Wireless Debugging Pairing Client (`_adb-tls-pairing._tcp`).
 *
 * Implements the AOSP `pairing_connection` + `pairing_auth` protocol:
 * 1) TLS 1.3 handshake to `127.0.0.1:<pairPort>` with client X.509 certificate.
 * 2) Extracts 64-byte TLS 1.3 exported keying material (`adb-label\0`) via Android Conscrypt.
 * 3) Runs Ed25519 SPAKE2 PAKE over `pairingCode || exportedKeyingMaterial`.
 * 4) Derives AES-128-GCM key via HKDF-SHA256 (`adb pairing_auth aes-128-gcm key`).
 * 5) Exchanges encrypted 8192-byte `PeerInfo` carrying GokuAgent's ADB RSA public key.
 */
class AdbWirelessPairingClient(private val crypto: AdbCrypto) {

    fun pair(host: String, pairPort: Int, pairingCode: String): Result<String> {
        var sslSocket: SSLSocket? = null
        return try {
            val sslContext = crypto.createTls13SslContext()
            val rawSocket = Socket().apply {
                connect(InetSocketAddress(host, pairPort), CONNECT_TIMEOUT_MS)
                soTimeout = IO_TIMEOUT_MS
            }
            sslSocket = (sslContext.socketFactory.createSocket(rawSocket, host, pairPort, true) as SSLSocket).apply {
                enabledProtocols = arrayOf("TLSv1.3")
                startHandshake()
            }

            val exportedKey = exportTlsKeyingMaterial(sslSocket)
                ?: return Result.failure(
                    IllegalStateException(
                        "TLS 1.3 keying material export is restricted on this ROM. Use the copied ADB pair command in Termux/Shizuku."
                    )
                )

            val password = pairingCode.toByteArray(Charsets.UTF_8) + exportedKey
            val input = DataInputStream(sslSocket.inputStream)
            val output = DataOutputStream(sslSocket.outputStream)

            // 1. SPAKE2-Ed25519 key exchange
            val spake = Spake2Ed25519Client(password)
            val ourMsg = spake.generateMessage()
            writePacket(output, TYPE_SPAKE2_MSG, ourMsg)

            val (srvType, srvMsg) = readPacket(input)
            if (srvType != TYPE_SPAKE2_MSG || srvMsg.size != 32) {
                return Result.failure(IllegalStateException("Unexpected SPAKE2 server response (type=$srvType, len=${srvMsg.size})."))
            }

            val sharedSecret = spake.processServerMessage(srvMsg)
                ?: return Result.failure(IllegalStateException("SPAKE2 key agreement failed. Check the 6-digit pairing code."))

            // 2. Derive AES-128-GCM session key via HKDF-SHA256
            val aesKey = hkdfSha256(
                ikm = sharedSecret,
                salt = ByteArray(0),
                info = HKDF_INFO.toByteArray(Charsets.UTF_8),
                length = 16
            )

            // 3. Encrypt and send 8192-byte PeerInfo carrying our ADB RSA Public Key
            val peerInfoPlain = ByteArray(PEER_INFO_SIZE)
            peerInfoPlain[0] = PEER_INFO_TYPE_ADB_RSA_PUB_KEY
            val pubKeyBytes = crypto.getAdbPublicKeyPayload(nullTerminated = true)
            System.arraycopy(pubKeyBytes, 0, peerInfoPlain, 1, minOf(pubKeyBytes.size, PEER_INFO_SIZE - 1))

            val encryptedPeerInfo = aesGcmEncrypt(aesKey, sequence = 0L, plaintext = peerInfoPlain)
            writePacket(output, TYPE_PEER_INFO, encryptedPeerInfo)

            // 4. Read and authenticate server PeerInfo
            val (peerType, encryptedSrvPeer) = readPacket(input)
            if (peerType != TYPE_PEER_INFO) {
                return Result.failure(IllegalStateException("Device rejected pairing code (packet type=$peerType)."))
            }
            val decryptedSrvPeer = aesGcmDecrypt(aesKey, sequence = 0L, ciphertext = encryptedSrvPeer)
            val serverName = extractPeerName(decryptedSrvPeer)

            Result.success("Paired successfully with ${serverName.ifBlank { "local Android device" }}!")
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                sslSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writePacket(out: DataOutputStream, type: Byte, payload: ByteArray) {
        out.writeByte(PROTOCOL_VERSION.toInt())
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Byte, ByteArray> {
        val version = input.readByte()
        val type = input.readByte()
        val size = input.readInt()
        if (version != PROTOCOL_VERSION || size < 0 || size > MAX_PACKET_SIZE) {
            throw IllegalStateException("Invalid ADB pairing packet header (v=$version, size=$size)")
        }
        val payload = ByteArray(size)
        input.readFully(payload)
        return type to payload
    }

    private fun exportTlsKeyingMaterial(sslSocket: SSLSocket): ByteArray? {
        val label = "adb-label\u0000"
        val classNames = listOf(
            "com.android.org.conscrypt.Conscrypt",
            "org.conscrypt.Conscrypt"
        )
        for (name in classNames) {
            try {
                val clazz = Class.forName(name)
                val method = clazz.getMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType
                )
                val result = method.invoke(null, sslSocket, label, null, EXPORTED_KEY_SIZE) as? ByteArray
                if (result != null && result.size == EXPORTED_KEY_SIZE) return result
            } catch (_: Exception) {
                // Try next Conscrypt class path
            }
        }
        return null
    }

    private fun aesGcmEncrypt(key: ByteArray, sequence: Long, plaintext: ByteArray): ByteArray {
        val nonce = buildNonce(sequence)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(key: ByteArray, sequence: Long, ciphertext: ByteArray): ByteArray {
        val nonce = buildNonce(sequence)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun buildNonce(sequence: Long): ByteArray {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(sequence)
        buf.putInt(0)
        return buf.array()
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(actualSalt, ikm)
        val result = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (result.size() < length) {
            val data = ByteArrayOutputStream().apply {
                write(previous)
                write(info)
                write(counter)
            }.toByteArray()
            previous = hmacSha256(prk, data)
            result.write(previous)
            counter++
        }
        return result.toByteArray().copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun extractPeerName(peerInfo: ByteArray): String {
        if (peerInfo.size < 2) return ""
        var end = 1
        while (end < peerInfo.size && peerInfo[end].toInt() != 0) end++
        return String(peerInfo, 1, end - 1, Charsets.UTF_8).trim()
    }

    /**
     * Pure-Kotlin SPAKE2 over Ed25519 matching BoringSSL's `SPAKE2_CTX` used by AOSP `adbd`.
     */
    private class Spake2Ed25519Client(private val password: ByteArray) {
        private val scalarX: BigInteger
        private val pwScalarW: BigInteger
        private lateinit var clientMsgT: ByteArray

        init {
            val rand = ByteArray(32).also { SecureRandom().nextBytes(it) }
            scalarX = fromLittleEndian(rand).mod(L)
            val pwHash = MessageDigest.getInstance("SHA-512").digest(password)
            pwScalarW = fromLittleEndian(pwHash).mod(L)
        }

        fun generateMessage(): ByteArray {
            // T = x*B + w*M
            val xB = scalarMult(BASE_POINT, scalarX)
            val wM = scalarMult(POINT_M, pwScalarW)
            val tPoint = pointAdd(xB, wM)
            clientMsgT = encodePoint(tPoint)
            return clientMsgT
        }

        fun processServerMessage(serverMsgS: ByteArray): ByteArray? {
            val sPoint = decodePoint(serverMsgS) ?: return null
            // K = h * x * (S - w*N)
            val wN = scalarMult(POINT_N, pwScalarW)
            val sMinusWN = pointAdd(sPoint, pointNegate(wN))
            val kPoint = scalarMult(sMinusWN, scalarX.multiply(BigInteger.valueOf(8)).mod(L))
            val kBytes = encodePoint(kPoint)

            // BoringSSL SPAKE2 transcript hash: SHA-512 over length-prefixed (uint64 LE) components
            val sha512 = MessageDigest.getInstance("SHA-512")
            updateLenPrefixed(sha512, CLIENT_NAME)
            updateLenPrefixed(sha512, SERVER_NAME)
            updateLenPrefixed(sha512, clientMsgT)
            updateLenPrefixed(sha512, serverMsgS)
            updateLenPrefixed(sha512, kBytes)
            updateLenPrefixed(sha512, password)
            return sha512.digest()
        }

        private fun updateLenPrefixed(md: MessageDigest, data: ByteArray) {
            val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(data.size.toLong()).array()
            md.update(lenBuf)
            md.update(data)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val IO_TIMEOUT_MS = 10000
        private const val PROTOCOL_VERSION: Byte = 1
        private const val TYPE_SPAKE2_MSG: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        private const val PEER_INFO_TYPE_ADB_RSA_PUB_KEY: Byte = 0
        private const val PEER_INFO_SIZE = 8192
        private const val MAX_PACKET_SIZE = 16384
        private const val EXPORTED_KEY_SIZE = 64
        private const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"

        private val CLIENT_NAME = "adb pair client".toByteArray(Charsets.UTF_8)
        private val SERVER_NAME = "adb pair server".toByteArray(Charsets.UTF_8)

        // Ed25519 Curve Parameters
        private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val L: BigInteger = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))
        private val D: BigInteger = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val SQRT_M1: BigInteger = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

        private data class EdPoint(val x: BigInteger, val y: BigInteger)

        private val IDENTITY = EdPoint(BigInteger.ZERO, BigInteger.ONE)

        // Ed25519 Base point B
        private val BASE_POINT: EdPoint by lazy {
            val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
            val x = recoverX(y, false)!!
            EdPoint(x, y)
        }

        // BoringSSL SPAKE2 Ed25519 generator points M & N
        private val POINT_M: EdPoint by lazy {
            decodePoint(hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        }
        private val POINT_N: EdPoint by lazy {
            decodePoint(hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!
        }

        private fun pointNegate(pt: EdPoint): EdPoint = EdPoint(P.subtract(pt.x).mod(P), pt.y)

        private fun pointAdd(p1: EdPoint, p2: EdPoint): EdPoint {
            val x1x2 = p1.x.multiply(p2.x).mod(P)
            val y1y2 = p1.y.multiply(p2.y).mod(P)
            val dxxyy = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P)

            val xNum = p1.x.multiply(p2.y).add(p1.y.multiply(p2.x)).mod(P)
            val xDen = BigInteger.ONE.add(dxxyy).mod(P)

            val yNum = y1y2.add(x1x2).mod(P)
            val yDen = BigInteger.ONE.subtract(dxxyy).mod(P)

            return EdPoint(
                x = xNum.multiply(xDen.modInverse(P)).mod(P),
                y = yNum.multiply(yDen.modInverse(P)).mod(P)
            )
        }

        private fun scalarMult(point: EdPoint, scalar: BigInteger): EdPoint {
            var result = IDENTITY
            var addend = point
            var k = scalar.mod(L)
            while (k > BigInteger.ZERO) {
                if (k.testBit(0)) {
                    result = pointAdd(result, addend)
                }
                addend = pointAdd(addend, addend)
                k = k.shiftRight(1)
            }
            return result
        }

        private fun encodePoint(pt: EdPoint): ByteArray {
            val out = toLittleEndian32(pt.y)
            if (pt.x.testBit(0)) {
                out[31] = (out[31].toInt() or 0x80).toByte()
            }
            return out
        }

        private fun decodePoint(encoded: ByteArray): EdPoint? {
            if (encoded.size != 32) return null
            val copy = encoded.copyOf()
            val xSign = (copy[31].toInt() and 0x80) != 0
            copy[31] = (copy[31].toInt() and 0x7F).toByte()
            val y = fromLittleEndian(copy)
            if (y >= P) return null
            val x = recoverX(y, xSign) ?: return null
            return EdPoint(x, y)
        }

        private fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val v3 = v.pow(3).mod(P)
            val v7 = v.pow(7).mod(P)
            var x = u.multiply(v3).mod(P)
                .multiply(u.multiply(v7).modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P))
                .mod(P)

            val check = v.multiply(x).mod(P).multiply(x).mod(P)
            if (check != u) {
                if (check == P.subtract(u).mod(P)) {
                    x = x.multiply(SQRT_M1).mod(P)
                } else {
                    return null
                }
            }
            if (x == BigInteger.ZERO && sign) return null
            if (x.testBit(0) != sign) {
                x = P.subtract(x).mod(P)
            }
            return x
        }

        private fun fromLittleEndian(bytes: ByteArray): BigInteger {
            val reversed = bytes.reversedArray()
            return BigInteger(1, reversed)
        }

        private fun toLittleEndian32(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            val out = ByteArray(32)
            var idx = 0
            for (i in raw.size - 1 downTo 0) {
                if (idx < 32) {
                    out[idx++] = raw[i]
                }
            }
            return out
        }

        private fun hexToBytes(hex: String): ByteArray {
            val result = ByteArray(hex.length / 2)
            for (i in result.indices) {
                result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return result
        }
    }
}
