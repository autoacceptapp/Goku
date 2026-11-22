package com.arif.goku.adb

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.KeyStore

/**
 * Pure-Kotlin cryptographic helper for Android ADB (TCP AUTH + TLS 1.3 Wireless Debugging).
 * Generates and persists a dedicated RSA-2048 keypair in app-private storage, encodes the
 * AOSP 524-byte ADB public key struct, signs AUTH tokens, and builds a self-signed X.509v3
 * certificate for Android 11+ TLS 1.3 sockets without external native/BouncyCastle dependencies.
 */
class AdbCrypto(context: Context) {

    private val keyDir = File(context.applicationContext.filesDir, "adb_keys").apply { mkdirs() }
    private val privateKeyFile = File(keyDir, "goku_adb_private.pk8")
    private val publicKeyFile = File(keyDir, "goku_adb_public.x509")

    val keyPair: KeyPair by lazy { loadOrCreateKeyPair() }
    val x509Certificate: X509Certificate by lazy { generateSelfSignedCert(keyPair) }

    private fun loadOrCreateKeyPair(): KeyPair {
        if (privateKeyFile.exists() && publicKeyFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privateKeyFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(publicKeyFile.readBytes()))
                return KeyPair(pub, priv)
            } catch (_: Exception) {
                // Regenerate cleanly if stored key files were corrupted
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val pair = kpg.generateKeyPair()
        privateKeyFile.writeBytes(pair.private.encoded)
        publicKeyFile.writeBytes(pair.public.encoded)
        return pair
    }

    /**
     * Produces the exact AOSP ADB RSA public key payload:
     * `<base64(524-byte RSAPublicKey struct)> goku@gokuagent\u0000`
     */
    fun getAdbPublicKeyPayload(nullTerminated: Boolean = true): ByteArray {
        val rsaPub = keyPair.public as RSAPublicKey
        val structBytes = encodeAospRsaPublicKey(rsaPub)
        val b64 = Base64.encodeToString(structBytes, Base64.NO_WRAP)
        val formatted = "$b64 goku@gokuagent" + if (nullTerminated) "\u0000" else ""
        return formatted.toByteArray(Charsets.UTF_8)
    }

    /**
     * Signs an ADB 20-byte AUTH_TOKEN using RSA PKCS#1 v1.5 (SHA-1 DigestInfo wrapped by adbd).
     * Note: AOSP adbd sends a raw 20-byte SHA-1-sized token and verifies raw PKCS#1 v1.5 padding
     * with SHA-1 DigestInfo prefix, which matches `NONEwithRSA` over `SHA1_PREFIX + token`.
     */
    fun signAuthToken(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initSign(keyPair.private)
        signature.update(SHA1_DIGEST_INFO_PREFIX)
        signature.update(token)
        return signature.sign()
    }

    /**
     * Builds an Android TLS 1.3 SSLContext using our persistent client certificate.
     * Local loopback `127.0.0.1` adbd uses a self-signed ephemeral cert verified by SPAKE2.
     */
    fun createTls13SslContext(): SSLContext {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("goku_adb", keyPair.private, CharArray(0), arrayOf(x509Certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, CharArray(0))
        }
        val trustLocalAdbd = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLSv1.3").apply {
            init(kmf.keyManagers, trustLocalAdbd, SecureRandom())
        }
    }

    companion object {
        private const val KEY_LENGTH_WORDS = 64 // 2048 bits / 32 bits

        // ASN.1 DER DigestInfo prefix for SHA-1 (matches AOSP libcrypto_utils / adb_auth)
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        /**
         * Encodes a 2048-bit RSAPublicKey into AOSP's 524-byte little-endian Montgomery format:
         * uint32 len(64), uint32 n0inv, uint32 n[64], uint32 rr[64], uint32 exponent
         */
        fun encodeAospRsaPublicKey(publicKey: RSAPublicKey): ByteArray {
            val modulus = publicKey.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = modulus.remainder(r32).modInverse(r32).negate().and(r32.subtract(BigInteger.ONE))
            val r = BigInteger.ONE.shiftLeft(KEY_LENGTH_WORDS * 32)
            val rr = r.multiply(r).remainder(modulus)

            val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(KEY_LENGTH_WORDS)
            buf.putInt(n0inv.toInt())

            var nTemp = modulus
            for (i in 0 until KEY_LENGTH_WORDS) {
                buf.putInt(nTemp.and(r32.subtract(BigInteger.ONE)).toInt())
                nTemp = nTemp.shiftRight(32)
            }

            var rrTemp = rr
            for (i in 0 until KEY_LENGTH_WORDS) {
                buf.putInt(rrTemp.and(r32.subtract(BigInteger.ONE)).toInt())
                rrTemp = rrTemp.shiftRight(32)
            }

            buf.putInt(publicKey.publicExponent.toInt())
            return buf.array()
        }

        /**
         * Builds a minimal valid DER-encoded self-signed X.509v3 certificate signed with SHA256withRSA.
         */
        fun generateSelfSignedCert(pair: KeyPair): X509Certificate {
            val now = Date(System.currentTimeMillis() - 60_000L)
            val expiry = Date(System.currentTimeMillis() + 3650L * 24L * 60L * 60L * 1000L)

            val version = derExplicit(0, derInteger(BigInteger.valueOf(2))) // v3
            val serial = derInteger(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE))
            val sha256WithRsaAlgId = derSequence(
                derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11)), // sha256WithRSAEncryption
                byteArrayOf(0x05, 0x00) // NULL
            )
            val dn = derSequence(
                derSet(
                    derSequence(
                        derOid(intArrayOf(2, 5, 4, 3)), // CN
                        derUtf8String("GokuAgentADB")
                    )
                )
            )
            val validity = derSequence(derGeneralizedTime(now), derGeneralizedTime(expiry))
            val spki = pair.public.encoded // Already DER SubjectPublicKeyInfo

            val tbsCertificate = derSequence(
                version,
                serial,
                sha256WithRsaAlgId,
                dn, // issuer
                validity,
                dn, // subject
                spki
            )

            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(pair.private)
                update(tbsCertificate)
            }.sign()

            val certDer = derSequence(
                tbsCertificate,
                sha256WithRsaAlgId,
                derBitString(sig)
            )

            val cf = CertificateFactory.getInstance("X.509")
            return cf.generateCertificate(certDer.inputStream()) as X509Certificate
        }

        private fun derLength(len: Int): ByteArray {
            if (len < 128) return byteArrayOf(len.toByte())
            val out = ByteArrayOutputStream()
            var temp = len
            val bytes = mutableListOf<Byte>()
            while (temp > 0) {
                bytes.add(0, (temp and 0xFF).toByte())
                temp = temp ushr 8
            }
            out.write(0x80 or bytes.size)
            bytes.forEach { out.write(it.toInt()) }
            return out.toByteArray()
        }

        private fun derTag(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            out.write(derLength(content.size))
            out.write(content)
            return out.toByteArray()
        }

        private fun derSequence(vararg elements: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            elements.forEach { out.write(it) }
            return derTag(0x30, out.toByteArray())
        }

        private fun derSet(vararg elements: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            elements.forEach { out.write(it) }
            return derTag(0x31, out.toByteArray())
        }

        private fun derExplicit(tagNum: Int, content: ByteArray): ByteArray =
            derTag(0xA0 or tagNum, content)

        private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())

        private fun derBitString(bytes: ByteArray): ByteArray {
            val content = ByteArray(bytes.size + 1)
            content[0] = 0x00
            System.arraycopy(bytes, 0, content, 1, bytes.size)
            return derTag(0x03, content)
        }

        private fun derUtf8String(text: String): ByteArray =
            derTag(0x0C, text.toByteArray(Charsets.UTF_8))

        private fun derGeneralizedTime(date: Date): ByteArray {
            val sdf = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return derTag(0x18, sdf.format(date).toByteArray(Charsets.US_ASCII))
        }

        private fun derOid(components: IntArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(components[0] * 40 + components[1])
            for (i in 2 until components.size) {
                var v = components[i]
                val encoded = mutableListOf<Byte>()
                encoded.add((v and 0x7F).toByte())
                v = v ushr 7
                while (v > 0) {
                    encoded.add(0, ((v and 0x7F) or 0x80).toByte())
                    v = v ushr 7
                }
                encoded.forEach { out.write(it.toInt()) }
            }
            return derTag(0x06, out.toByteArray())
        }
    }
}
