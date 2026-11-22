package com.arif.goku.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * Pure-Kotlin ADB Wire Protocol Client supporting both plain TCP (`127.0.0.1:5555`) and
 * Android 11+ TLS 1.3 Wireless Debugging (`_adb-tls-connect._tcp`).
 *
 * Implements ADB packet framing: CNXN, AUTH (TOKEN -> SIGNATURE -> RSAPUBLICKEY), OPEN, OKAY, WRTE, CLSE.
 */
class AdbProtocolClient(private val crypto: AdbCrypto) {

    data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    /**
     * Connects to a local ADB daemon, authenticates via RSA keypair, opens a single service
     * stream (e.g., `"shell:<cmd>"` or `"tcpip:5555"`), collects its output, and closes cleanly.
     */
    fun runServiceCommand(
        host: String,
        port: Int,
        service: String,
        useTls: Boolean = false,
        timeoutMs: Int = DEFAULT_IO_TIMEOUT_MS
    ): Result<String> {
        var socket: Socket? = null
        return try {
            val raw = Socket().apply {
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                soTimeout = timeoutMs
            }

            socket = if (useTls) {
                val ctx = crypto.createTls13SslContext()
                (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
                    enabledProtocols = arrayOf("TLSv1.3")
                    startHandshake()
                }
            } else {
                raw
            }

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // 1. Handshake: CNXN (+ AUTH challenge if required)
            val handshakeOk = performHandshake(input, output)
            if (!handshakeOk) {
                return Result.failure(
                    IllegalStateException("ADB authorization rejected. Confirm USB/Wireless Debugging prompt or re-pair.")
                )
            }

            // 2. Open service stream (e.g. "shell:pm list packages -3\0" or "tcpip:5555\0")
            val localId = 1
            val servicePayload = "$service\u0000".toByteArray(Charsets.UTF_8)
            writePacket(output, CMD_OPEN, localId, 0, servicePayload)

            var remoteId = -1
            val responseBuffer = ByteArrayOutputStream()

            while (true) {
                val packet = readPacket(input) ?: break
                when (packet.command) {
                    CMD_OKAY -> {
                        if (remoteId == -1 && packet.arg1 == localId) {
                            remoteId = packet.arg0
                        }
                    }

                    CMD_WRTE -> {
                        if (packet.arg1 == localId) {
                            if (remoteId == -1) remoteId = packet.arg0
                            responseBuffer.write(packet.payload)
                            writePacket(output, CMD_OKAY, localId, remoteId, ByteArray(0))
                        }
                    }

                    CMD_CLSE -> {
                        if (packet.arg1 == localId) {
                            writePacket(output, CMD_CLSE, localId, packet.arg0, ByteArray(0))
                            break
                        }
                    }
                }
            }

            Result.success(responseBuffer.toString(Charsets.UTF_8.name()).trim())
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Switches a Wireless Debugging TLS connection (`_adb-tls-connect._tcp`) into persistent
     * local TCP/IP mode on port 5555 (`tcpip:5555`).
     */
    fun switchToTcpip5555(host: String, tlsConnectPort: Int): Result<String> {
        return runServiceCommand(
            host = host,
            port = tlsConnectPort,
            service = "tcpip:5555",
            useTls = true,
            timeoutMs = 6000
        )
    }

    /**
     * Executes a shell command on an ADB endpoint (typically `127.0.0.1:5555`).
     */
    fun executeShell(
        host: String,
        port: Int,
        command: String,
        useTls: Boolean = false
    ): Result<String> {
        return runServiceCommand(
            host = host,
            port = port,
            service = "shell:$command",
            useTls = useTls,
            timeoutMs = DEFAULT_IO_TIMEOUT_MS
        )
    }

    /**
     * Verifies full ADB protocol handshake (not just a bare TCP open port).
     */
    fun verifyConnection(host: String, port: Int, useTls: Boolean = false): Boolean {
        return executeShell(host, port, "echo goku_ok", useTls)
            .map { it.contains("goku_ok") }
            .getOrDefault(false)
    }

    private fun performHandshake(input: DataInputStream, output: DataOutputStream): Boolean {
        val banner = "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.UTF_8)
        writePacket(output, CMD_CNXN, ADB_VERSION, MAX_DATA, banner)

        var sentSignature = false
        var sentPubKey = false

        for (attempt in 0 until 8) {
            val packet = readPacket(input) ?: return false
            when (packet.command) {
                CMD_CNXN -> return true
                CMD_AUTH -> {
                    if (packet.arg0 != AUTH_TOKEN) return false
                    if (!sentSignature) {
                        val sig = crypto.signAuthToken(packet.payload)
                        writePacket(output, CMD_AUTH, AUTH_SIGNATURE, 0, sig)
                        sentSignature = true
                    } else if (!sentPubKey) {
                        val pubKey = crypto.getAdbPublicKeyPayload(nullTerminated = true)
                        writePacket(output, CMD_AUTH, AUTH_RSAPUBLICKEY, 0, pubKey)
                        sentPubKey = true
                    } else {
                        return false
                    }
                }
            }
        }
        return false
    }

    private fun writePacket(
        out: DataOutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(payload.size)
            putInt(payloadChecksum(payload))
            putInt(command xor -1)
        }.array()
        out.write(header)
        if (payload.isNotEmpty()) {
            out.write(payload)
        }
        out.flush()
    }

    private fun readPacket(input: DataInputStream): AdbPacket? {
        val headerBytes = ByteArray(24)
        try {
            input.readFully(headerBytes)
        } catch (_: Exception) {
            return null
        }
        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLength = buf.int
        buf.int // dataCrc
        val magic = buf.int
        if (magic != (command xor -1) || dataLength < 0 || dataLength > MAX_PACKET_PAYLOAD) {
            return null
        }
        val payload = ByteArray(dataLength)
        if (dataLength > 0) {
            input.readFully(payload)
        }
        return AdbPacket(command, arg0, arg1, payload)
    }

    private fun payloadChecksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val DEFAULT_IO_TIMEOUT_MS = 10000
        private const val ADB_VERSION = 0x01000000
        private const val MAX_DATA = 256 * 1024
        private const val MAX_PACKET_PAYLOAD = 2 * 1024 * 1024

        private const val CMD_CNXN = 0x4E584E43 // "CNXN"
        private const val CMD_AUTH = 0x48545541 // "AUTH"
        private const val CMD_OPEN = 0x4E45504F // "OPEN"
        private const val CMD_OKAY = 0x59414B4F // "OKAY"
        private const val CMD_CLSE = 0x45534C43 // "CLSE"
        private const val CMD_WRTE = 0x45545257 // "WRTE"

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
    }
}
