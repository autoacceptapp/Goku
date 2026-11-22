package com.arif.goku.adb

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import com.arif.goku.data.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

enum class LocalAdbState {
    DISCONNECTED,
    PROBING,
    PAIRING_READY,
    CONNECTED
}

data class LocalAdbStatus(
    val state: LocalAdbState = LocalAdbState.DISCONNECTED,
    val endpointLabel: String = "127.0.0.1:5555",
    val statusDetail: String = "Local ADB daemon (127.0.0.1:5555) not connected yet.",
    val discoveredPairPort: Int? = null,
    val discoveredConnectPort: Int? = null,
    val isMdnsScanning: Boolean = false,
    val permissionsAutoGranted: Boolean = false,
    val generatedCommands: List<String> = defaultCommands("", "", ""),
    val lastError: String? = null
) {
    companion object {
        fun defaultCommands(pairPort: String, pairCode: String, connectPort: String): List<String> {
            val pPort = pairPort.ifBlank { "<PAIR_PORT>" }
            val pCode = pairCode.ifBlank { "<6_DIGIT_CODE>" }
            val cPort = connectPort.ifBlank { "<CONNECT_PORT>" }
            return listOf(
                "adb pair 127.0.0.1:$pPort $pCode",
                "adb connect 127.0.0.1:$cPort",
                "adb -s 127.0.0.1:$cPort tcpip 5555",
                "adb connect 127.0.0.1:5555"
            )
        }
    }
}

/**
 * Phase 2 Full Pure-Kotlin Local Wireless ADB Manager.
 *
 * Features:
 * - Android 11+ SPAKE2 + TLS 1.3 Wireless Debugging pairing (`AdbWirelessPairingClient`).
 * - Full ADB TCP & TLS 1.3 wire protocol (`AdbProtocolClient`) for `connect`, `tcpip 5555`, and `shell`.
 * - mDNS (`NsdManager`) auto-discovery for `_adb-tls-connect._tcp` and `_adb-tls-pairing._tcp`.
 * - Automatic privileged permission grant (`WRITE_SECURE_SETTINGS`, `DUMP`, `PACKAGE_USAGE_STATS`)
 *   when connected to `127.0.0.1:5555`.
 */
class LocalAdbManager(
    private val context: Context,
    private val securePrefs: SecurePrefs
) {
    private val appContext = context.applicationContext
    private val crypto = AdbCrypto(appContext)
    private val pairingClient = AdbWirelessPairingClient(crypto)
    private val protocolClient = AdbProtocolClient(crypto)

    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null

    private val _status = MutableStateFlow(
        LocalAdbStatus(
            generatedCommands = LocalAdbStatus.defaultCommands(
                pairPort = securePrefs.getAdbPairPort(),
                pairCode = "",
                connectPort = securePrefs.getAdbConnectPort()
            )
        )
    )
    val status: StateFlow<LocalAdbStatus> = _status.asStateFlow()

    val isConnected: Boolean
        get() = _status.value.state == LocalAdbState.CONNECTED

    /**
     * Starts mDNS (`NsdManager`) discovery for `_adb-tls-connect._tcp.` and `_adb-tls-pairing._tcp.`
     * so Wireless Debugging ports are detected automatically after device reboot.
     */
    fun startMdnsDiscovery() {
        val manager = nsdManager ?: return
        stopMdnsDiscovery()

        val connectListener = createDiscoveryListener(isPairingService = false)
        val pairingListener = createDiscoveryListener(isPairingService = true)
        connectDiscoveryListener = connectListener
        pairingDiscoveryListener = pairingListener

        try {
            manager.discoverServices(SERVICE_ADB_TLS_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)
            manager.discoverServices(SERVICE_ADB_TLS_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener)
            _status.update { it.copy(isMdnsScanning = true) }
        } catch (_: Exception) {
            _status.update { it.copy(isMdnsScanning = false) }
        }
    }

    fun stopMdnsDiscovery() {
        val manager = nsdManager ?: return
        connectDiscoveryListener?.let {
            try {
                manager.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        pairingDiscoveryListener?.let {
            try {
                manager.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        connectDiscoveryListener = null
        pairingDiscoveryListener = null
        _status.update { it.copy(isMdnsScanning = false) }
    }

    @Suppress("DEPRECATION")
    private fun createDiscoveryListener(isPairingService: Boolean): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val info = serviceInfo ?: return
                val manager = nsdManager ?: return
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        manager.registerServiceInfoCallback(
                            info,
                            { runnable -> runnable.run() },
                            object : NsdManager.ServiceInfoCallback {
                                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                                override fun onServiceLost() = Unit
                                override fun onServiceInfoCallbackUnregistered() = Unit
                                override fun onServiceUpdated(updated: NsdServiceInfo) {
                                    recordDiscoveredPort(isPairingService, updated.port)
                                    try {
                                        manager.unregisterServiceInfoCallback(this)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        )
                    } else {
                        manager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
                            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                                resolved?.port?.let { port ->
                                    recordDiscoveredPort(isPairingService, port)
                                }
                            }
                        })
                    }
                } catch (_: Exception) {
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                _status.update {
                    if (isPairingService) it.copy(discoveredPairPort = null)
                    else it.copy(discoveredConnectPort = null)
                }
            }
        }
    }

    private fun recordDiscoveredPort(isPairing: Boolean, port: Int) {
        if (port !in 1..65535) return
        _status.update { current ->
            val nextPair = if (isPairing) port else current.discoveredPairPort
            val nextConnect = if (!isPairing) port else current.discoveredConnectPort
            val savedPair = nextPair?.toString() ?: securePrefs.getAdbPairPort()
            val savedConnect = nextConnect?.toString() ?: securePrefs.getAdbConnectPort()
            if (savedPair.isNotBlank() || savedConnect.isNotBlank()) {
                securePrefs.saveAdbConfig(savedPair, savedConnect)
            }
            current.copy(
                discoveredPairPort = nextPair,
                discoveredConnectPort = nextConnect,
                generatedCommands = LocalAdbStatus.defaultCommands(savedPair, "", savedConnect)
            )
        }
    }

    /**
     * Probes `127.0.0.1:5555` with a real ADB protocol handshake, and if disconnected, attempts
     * auto-reconnection via any mDNS-discovered Wireless Debugging TLS connect port -> `tcpip 5555`.
     */
    suspend fun probeLocalTcpip5555(): Boolean = withContext(Dispatchers.IO) {
        _status.update {
            it.copy(
                state = LocalAdbState.PROBING,
                statusDetail = "Checking ADB handshake on 127.0.0.1:5555…",
                lastError = null
            )
        }

        if (isLoopbackPortOpen(SecurePrefs.DEFAULT_ADB_PORT) &&
            protocolClient.verifyConnection(SecurePrefs.DEFAULT_ADB_HOST, SecurePrefs.DEFAULT_ADB_PORT, useTls = false)
        ) {
            val granted = autoGrantSelfPermissions()
            _status.update {
                it.copy(
                    state = LocalAdbState.CONNECTED,
                    endpointLabel = "127.0.0.1:5555",
                    permissionsAutoGranted = granted,
                    statusDetail = if (granted) {
                        "Connected to 127.0.0.1:5555 • Privileged permissions granted."
                    } else {
                        "Connected to 127.0.0.1:5555 • Ready for shell & Ghost Mode."
                    },
                    lastError = null
                )
            }
            return@withContext true
        }

        // Attempt auto-bridge if mDNS found an already-paired Wireless Debugging connect port
        val discoveredConnect = _status.value.discoveredConnectPort
            ?: securePrefs.getAdbConnectPort().toIntOrNull()
        if (discoveredConnect != null && isLoopbackPortOpen(discoveredConnect)) {
            val tcpipResult = protocolClient.switchToTcpip5555(SecurePrefs.DEFAULT_ADB_HOST, discoveredConnect)
            if (tcpipResult.isSuccess) {
                delay(900L)
                if (protocolClient.verifyConnection(SecurePrefs.DEFAULT_ADB_HOST, SecurePrefs.DEFAULT_ADB_PORT, useTls = false)) {
                    val granted = autoGrantSelfPermissions()
                    _status.update {
                        it.copy(
                            state = LocalAdbState.CONNECTED,
                            endpointLabel = "127.0.0.1:5555",
                            permissionsAutoGranted = granted,
                            statusDetail = "Auto-connected via TLS port $discoveredConnect -> 127.0.0.1:5555!",
                            lastError = null
                        )
                    }
                    return@withContext true
                }
            }
        }

        _status.update {
            it.copy(
                state = LocalAdbState.DISCONNECTED,
                endpointLabel = "127.0.0.1:5555",
                statusDetail = "127.0.0.1:5555 closed. Enable Wireless Debugging and pair in Local ADB tab.",
                lastError = null
            )
        }
        false
    }

    /**
     * Full native pure-Kotlin Wireless ADB sequence:
     * 1) `pair(127.0.0.1:pairPort, 6-digit code)` via SPAKE2 + TLS 1.3
     * 2) `connect(127.0.0.1:connectPort)` via TLS 1.3
     * 3) `tcpip 5555`
     * 4) `connect(127.0.0.1:5555)` + auto `pm grant com.arif.goku` permissions.
     */
    suspend fun prepareAndProbePairing(
        pairPortRaw: String,
        pairingCodeRaw: String,
        connectPortRaw: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val pairPortText = pairPortRaw.trim().ifBlank { _status.value.discoveredPairPort?.toString().orEmpty() }
        val pairingCode = pairingCodeRaw.trim()
        val connectPortText = connectPortRaw.trim().ifBlank { _status.value.discoveredConnectPort?.toString().orEmpty() }

        val pairPort = pairPortText.toIntOrNull()
        if (pairPort == null || pairPort !in 1..65535) {
            val err = "Valid Wireless Debugging Pairing Port (1–65535) enter karein."
            _status.update { it.copy(lastError = err) }
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        if (!PAIR_CODE_REGEX.matches(pairingCode)) {
            val err = "Exact 6-digit Pairing Code enter karein (e.g., 482910)."
            _status.update { it.copy(lastError = err) }
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        val connectPort = connectPortText.toIntOrNull()
        if (connectPortText.isNotEmpty() && (connectPort == null || connectPort !in 1..65535)) {
            val err = "Connect Port valid number (1–65535) hona chahiye."
            _status.update { it.copy(lastError = err) }
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        securePrefs.saveAdbConfig(pairPortText, connectPortText)
        val commands = LocalAdbStatus.defaultCommands(pairPortText, pairingCode, connectPortText)

        _status.update {
            it.copy(
                state = LocalAdbState.PROBING,
                generatedCommands = commands,
                statusDetail = "Running native SPAKE2 + TLS 1.3 pairing on 127.0.0.1:$pairPort…",
                lastError = null
            )
        }

        // Step 1: Native SPAKE2 + TLS 1.3 Pairing
        val pairResult = pairingClient.pair(SecurePrefs.DEFAULT_ADB_HOST, pairPort, pairingCode)
        if (pairResult.isFailure) {
            val reason = pairResult.exceptionOrNull()?.message ?: "Pairing handshake failed."
            val portOpen = isLoopbackPortOpen(pairPort)
            val detail = if (portOpen) {
                "Port $pairPort reachable, lekin native pairing fail hui: $reason"
            } else {
                "Port $pairPort closed hai. Split-screen mein 'Pair device with pairing code' dialog khula rakhein."
            }
            _status.update {
                it.copy(
                    state = if (portOpen) LocalAdbState.PAIRING_READY else LocalAdbState.DISCONNECTED,
                    statusDetail = detail,
                    generatedCommands = commands,
                    lastError = reason
                )
            }
            return@withContext Result.failure(IllegalStateException(detail))
        }

        // Step 2 & 3: Connect to Wireless Debugging TLS port and switch daemon to tcpip 5555
        val targetConnectPort = connectPort ?: _status.value.discoveredConnectPort
        if (targetConnectPort != null) {
            _status.update {
                it.copy(statusDetail = "Paired! Switching 127.0.0.1:$targetConnectPort to tcpip 5555…")
            }
            delay(500L)
            protocolClient.switchToTcpip5555(SecurePrefs.DEFAULT_ADB_HOST, targetConnectPort)
            delay(900L)
        }

        // Step 4: Verify 127.0.0.1:5555 and auto-grant permissions
        val connected5555 = probeLocalTcpip5555()
        if (connected5555) {
            Result.success("Paired & connected to 127.0.0.1:5555! Privileged permissions auto-granted.")
        } else {
            val pairedMsg = "Pairing succeeded! Enter Wireless Debugging main Connect Port and tap Probe to enable tcpip 5555."
            _status.update {
                it.copy(
                    state = LocalAdbState.PAIRING_READY,
                    statusDetail = pairedMsg,
                    generatedCommands = commands,
                    lastError = null
                )
            }
            Result.success(pairedMsg)
        }
    }

    /**
     * Executes a shell command on local ADB (`127.0.0.1:5555`, falling back to mDNS TLS port).
     * Rejects empty commands and blocks destructive wipe/format tokens.
     */
    suspend fun executeShellCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        val cleaned = command.trim()
        if (cleaned.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Shell command cannot be empty."))
        }
        if (FORBIDDEN_SHELL_PATTERNS.any { it.containsMatchIn(cleaned) }) {
            return@withContext Result.failure(
                SecurityException("Blocked unsafe destructive command by SafetyGuard.")
            )
        }

        // Primary: 127.0.0.1:5555 plain TCP
        if (isLoopbackPortOpen(SecurePrefs.DEFAULT_ADB_PORT)) {
            val res = protocolClient.executeShell(
                host = SecurePrefs.DEFAULT_ADB_HOST,
                port = SecurePrefs.DEFAULT_ADB_PORT,
                command = cleaned,
                useTls = false
            )
            if (res.isSuccess) {
                if (_status.value.state != LocalAdbState.CONNECTED) {
                    _status.update {
                        it.copy(
                            state = LocalAdbState.CONNECTED,
                            statusDetail = "Connected to 127.0.0.1:5555."
                        )
                    }
                }
                return@withContext res
            }
        }

        // Secondary: mDNS discovered TLS connect port
        val tlsPort = _status.value.discoveredConnectPort ?: securePrefs.getAdbConnectPort().toIntOrNull()
        if (tlsPort != null && isLoopbackPortOpen(tlsPort)) {
            val tlsRes = protocolClient.executeShell(
                host = SecurePrefs.DEFAULT_ADB_HOST,
                port = tlsPort,
                command = cleaned,
                useTls = true
            )
            if (tlsRes.isSuccess) return@withContext tlsRes
        }

        _status.update {
            it.copy(
                state = LocalAdbState.DISCONNECTED,
                statusDetail = "Local ADB 127.0.0.1:5555 disconnected."
            )
        }
        Result.failure(IllegalStateException("Local ADB 127.0.0.1:5555 is disconnected."))
    }

    /**
     * Automatically grants `WRITE_SECURE_SETTINGS`, `DUMP`, and `PACKAGE_USAGE_STATS`
     * to `com.arif.goku` over local ADB once connected.
     */
    suspend fun autoGrantSelfPermissions(): Boolean = withContext(Dispatchers.IO) {
        val pkg = appContext.packageName
        if (!PACKAGE_NAME_REGEX.matches(pkg)) return@withContext false

        val grants = listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "pm grant $pkg android.permission.DUMP",
            "appops set $pkg GET_USAGE_STATS allow"
        )
        var anySuccess = false
        for (cmd in grants) {
            val result = protocolClient.executeShell(
                host = SecurePrefs.DEFAULT_ADB_HOST,
                port = SecurePrefs.DEFAULT_ADB_PORT,
                command = cmd,
                useTls = false
            )
            if (result.isSuccess) anySuccess = true
        }
        anySuccess
    }

    fun openDeveloperOptions(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isLoopbackPortOpen(port: Int, timeoutMs: Int = 900): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(SecurePrefs.DEFAULT_ADB_HOST, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val SERVICE_ADB_TLS_CONNECT = "_adb-tls-connect._tcp."
        private const val SERVICE_ADB_TLS_PAIRING = "_adb-tls-pairing._tcp."
        private val PAIR_CODE_REGEX = Regex("^\\d{6}$")
        val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")

        private val FORBIDDEN_SHELL_PATTERNS = listOf(
            Regex("\\brm\\s+-[rf]+\\s+/"),
            Regex("\\bmkfs\\b"),
            Regex("\\bdd\\s+if="),
            Regex("\\breboot\\s+bootloader\\b"),
            Regex("\\bfactory_reset\\b"),
            Regex("\\bformat\\s+data\\b")
        )
    }
}
