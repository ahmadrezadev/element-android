/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import android.content.Context
import android.net.TrafficStats
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import com.tim.basevpn.state.ConnectionState
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.vpn.openvpn.ElementOpenVpnService
import im.vector.app.features.vpn.openvpn.ElementOpenVpnServiceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenVpnTunnelManager @Inject constructor(
        context: Context,
        private val vpnConnectionStatusTracker: VpnConnectionStatusTracker,
        private val vpnServerSelectionStore: VpnServerSelectionStore,
) {
    private val appContext = context.applicationContext
    private val speedMonitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var speedMonitorJob: Job? = null

    @Volatile
    private var latestConnectionState: ConnectionState = ConnectionState.IDLE

    private val serviceConnection = ElementOpenVpnServiceConnection(appContext) { state ->
        latestConnectionState = state
        vpnConnectionStatusTracker.onConnectionStateChanged(state)
        when (state) {
            ConnectionState.CONNECTED,
            ConnectionState.CONNECTING,
            ConnectionState.READYFORCONNECT -> startSpeedMonitoring()
            else -> stopSpeedMonitoring()
        }
    }

    suspend fun connectByPriority(servers: List<ProvisionedOpenVpnServer>): Result<ProvisionedOpenVpnServer> {
        val activeServers = servers
                .filter { it.isActive }
                .sortedBy { it.priority }

        if (activeServers.isEmpty()) {
            vpnConnectionStatusTracker.onFailed(null, "No active VPN server found")
            return Result.failure(VpnConnectionFailedException("No active VPN server found"))
        }

        val orderedServers = activeServers.prioritizeBySelection(vpnServerSelectionStore.getSelectedServerId())

        if (VpnService.prepare(appContext) != null) {
            vpnConnectionStatusTracker.onPermissionRequired(orderedServers.firstOrNull()?.displayName())
            return Result.failure(VpnPermissionRequiredException())
        }

        var lastError: Throwable? = null
        for (server in orderedServers) {
            repeat(CONNECTION_ATTEMPTS_PER_SERVER) {
                val attempt = connect(server)
                if (attempt.isSuccess) {
                    vpnServerSelectionStore.saveSelectedServerId(server.id)
                    return Result.success(server)
                }
                lastError = attempt.exceptionOrNull()
                if (lastError is VpnPermissionRequiredException) {
                    return Result.failure(lastError!!)
                }
                stop()
            }
        }

        return Result.failure(lastError ?: VpnConnectionFailedException("Could not connect to any VPN server"))
    }

    suspend fun isConnected(): Boolean {
        return latestConnectionState == ConnectionState.CONNECTED ||
                runCatching { serviceConnection.isConnected() }.getOrDefault(false)
    }

    suspend fun refreshConnectionState() {
        if (isConnected()) {
            vpnConnectionStatusTracker.onConnected(vpnServerSelectionStore.getSelectedServerId())
            startSpeedMonitoring()
        } else {
            stopSpeedMonitoring()
            vpnConnectionStatusTracker.onConnectionStateChanged(ConnectionState.DISCONNECTED)
        }
    }

    fun stop() {
        stopSpeedMonitoring()
        serviceConnection.stopServiceIfNeed(forceStop = true)
    }

    private suspend fun connect(server: ProvisionedOpenVpnServer): Result<Unit> {
        val serverName = server.displayName()
        return runCatching {
            vpnConnectionStatusTracker.onConnecting(serverName)
            VpnRuntimeDiagnostics.clearLastFailure(appContext)
            val preparedConfig = prepareConfig(server)
            val inlineConfig = preparedConfig.configuration.orEmpty()
            val remoteEndpoint = preparedConfig.configuration.extractPrimaryRemoteEndpoint()
            val keyEncrypted = containsEncryptedPrivateKey(inlineConfig)
            val privateKeyPasswordForService = resolvePrivateKeyPasswordForService(
                    configuration = inlineConfig,
                    privateKeyPassword = server.privateKeyPassword
            )
            val keyPassApplied = !privateKeyPasswordForService.isNullOrBlank()
            Log.d(
                    VPN_TUNNEL_TAG,
                    "Connecting server='${server.id}' display='${serverName}' timeoutMs=${server.connectionTimeoutMs} user=${server.username ?: "null"} pass=${server.password.maskForLog()} keyEncrypted=${keyEncrypted} keyPassApplied=${keyPassApplied} keyPass=${privateKeyPasswordForService.maskForLog()} remote=${remoteEndpoint ?: "unknown"} ovpnChars=${preparedConfig.configuration?.length ?: 0}"
            )
            validateRemoteEndpointReachability(
                    endpoint = remoteEndpoint,
                    serverId = server.id
            )
            ElementOpenVpnService.startService(
                    context = appContext,
                    config = preparedConfig,
                    notificationClass = HomeActivity::class.java.name,
                    username = server.username,
                    password = server.password,
                    privateKeyPassword = privateKeyPasswordForService,
            )
            serviceConnection.attachListener()

            waitForConnectedState(server, serverName, server.connectionTimeoutMs)
        }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = {
                    vpnConnectionStatusTracker.onFailed(serverName, it.localizedMessage)
                    when (it) {
                        is VpnPermissionRequiredException -> Result.failure(it)
                        else -> Result.failure(
                                VpnConnectionFailedException(
                                        "VPN connection failed for server '${server.id}': ${it.localizedMessage}"
                                )
                        )
                    }
                }
        )
    }

    private suspend fun waitForConnectedState(server: ProvisionedOpenVpnServer, serverName: String, timeoutMs: Long) {
        var hasStartedConnecting = false
        val attemptStartedAt = SystemClock.elapsedRealtime()

        val connected = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val connected = latestConnectionState == ConnectionState.CONNECTED ||
                        runCatching { serviceConnection.isConnected() }.getOrDefault(false)
                if (connected) {
                    vpnConnectionStatusTracker.onConnected(serverName)
                    return@withTimeoutOrNull true
                }

                when (latestConnectionState) {
                    ConnectionState.CONNECTING,
                    ConnectionState.READYFORCONNECT -> {
                        hasStartedConnecting = true
                    }
                    ConnectionState.IDLE -> {
                        if (hasStartedConnecting || hasExceededInitialStateGrace(attemptStartedAt)) {
                            throw VpnConnectionFailedException(
                                    "Server '${server.id}' returned to IDLE while connecting (transport dropped by remote endpoint)${lastRuntimeDiagnosticSuffix()}"
                            )
                        }
                    }
                    ConnectionState.PERMISSION_NOT_GRANTED -> {
                        throw VpnPermissionRequiredException()
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (hasStartedConnecting || hasExceededInitialStateGrace(attemptStartedAt)) {
                            throw VpnConnectionFailedException(
                                    "Server '${server.id}' disconnected before establishing the tunnel${lastRuntimeDiagnosticSuffix()}"
                            )
                        }
                    }
                    else -> Unit
                }
                delay(CONNECTION_POLL_INTERVAL_MS)
            }
        }

        if (connected != true) {
            throw VpnConnectionFailedException(
                    "Server '${server.id}' did not reach CONNECTED state within ${timeoutMs} ms${lastRuntimeDiagnosticSuffix()}"
            )
        }
    }

    private fun lastRuntimeDiagnosticSuffix(): String {
        val lastFailure = VpnRuntimeDiagnostics.readLastFailure(appContext) ?: return ""
        return ". Last OpenVPN reason: $lastFailure"
    }

    private fun hasExceededInitialStateGrace(attemptStartedAt: Long): Boolean {
        return (SystemClock.elapsedRealtime() - attemptStartedAt) >= STATE_FAIL_FAST_GRACE_MS
    }

    private suspend fun validateRemoteEndpointReachability(endpoint: RemoteEndpoint?, serverId: String) {
        if (endpoint == null) return

        withContext(Dispatchers.IO) {
            val addresses = runCatching { InetAddress.getAllByName(endpoint.host).toList() }
                    .getOrElse { failure ->
                        throw VpnConnectionFailedException(
                                "Cannot resolve VPN server host '${endpoint.host}' for server '$serverId': ${failure.localizedMessage}"
                        )
                    }

            val protocol = endpoint.protocol.lowercase()
            if (!protocol.startsWith("tcp")) return@withContext

            val firstAddress = addresses.firstOrNull()
            val privateNetworkHint = if (firstAddress?.isSiteLocalAddress == true) {
                " (server IP is private/local network)"
            } else {
                ""
            }

            runCatching {
                Socket().use { socket ->
                    socket.connect(
                            InetSocketAddress(endpoint.host, endpoint.port),
                            REMOTE_PROBE_TIMEOUT_MS
                    )
                }
            }.getOrElse { failure ->
                val reason = when (failure) {
                    is SocketTimeoutException -> "connection timed out"
                    else -> failure.localizedMessage ?: failure::class.java.simpleName
                }
                throw VpnConnectionFailedException(
                        "Cannot reach VPN server '${endpoint.host}:${endpoint.port}' for server '$serverId': $reason$privateNetworkHint"
                )
            }
        }
    }

    private fun startSpeedMonitoring() {
        if (speedMonitorJob?.isActive == true) return
        speedMonitorJob = speedMonitorScope.launch {
            var lastRxBytes = TrafficStats.getTotalRxBytes()
            var lastTxBytes = TrafficStats.getTotalTxBytes()

            while (isActive) {
                delay(CONNECTION_POLL_INTERVAL_MS)
                val currentRxBytes = TrafficStats.getTotalRxBytes()
                val currentTxBytes = TrafficStats.getTotalTxBytes()
                vpnConnectionStatusTracker.updateSpeed(
                        downloadBytesPerSecond = calculateBytesPerSecond(lastRxBytes, currentRxBytes),
                        uploadBytesPerSecond = calculateBytesPerSecond(lastTxBytes, currentTxBytes)
                )
                lastRxBytes = currentRxBytes
                lastTxBytes = currentTxBytes
            }
        }
    }

    private fun stopSpeedMonitoring() {
        speedMonitorJob?.cancel()
        speedMonitorJob = null
        vpnConnectionStatusTracker.updateSpeed(0L, 0L)
    }

    private fun calculateBytesPerSecond(previous: Long, current: Long): Long {
        if (previous < 0L || current < 0L || current < previous) return 0L
        return current - previous
    }

    private fun prepareConfig(server: ProvisionedOpenVpnServer): com.tim.openvpn.configuration.OpenVPNConfig {
        val config = server.toOpenVpnConfig()
        val configuration = config.configuration
                ?: throw VpnBootstrapConfigurationException("Missing .ovpn configuration for server '${server.id}'")
        val remoteEndpoint = configuration.extractPrimaryRemoteEndpoint()

        return config.copy(configuration = configuration
                .sanitizeUnsupportedAndroidOptions()
                .injectFullTunnelRouting(remoteEndpoint)
                .injectAuthUserPassIfRequired(server)
                .decryptEncryptedPrivateKeyIfPossible(server))
    }

    private fun String.sanitizeUnsupportedAndroidOptions(): String {
        val (sanitized, removedDirectives) = sanitizeUnsupportedAndroidOptions(this)
        if (removedDirectives.isNotEmpty()) {
            Log.w(
                    VPN_TUNNEL_TAG,
                    "Removed unsupported OpenVPN3 directives: ${removedDirectives.joinToString()}"
            )
        }
        return sanitized
    }

    private fun String.injectFullTunnelRouting(remoteEndpoint: RemoteEndpoint?): String {
        if (remoteEndpoint.isPrivateOrLocalLiteralAddress()) {
            Log.d(
                    VPN_TUNNEL_TAG,
                    "Skipping redirect-gateway injection for private/local remote endpoint '${remoteEndpoint?.host}'"
            )
            return this
        }

        return if (REDIRECT_GATEWAY_REGEX.containsMatchIn(this)) {
            this
        } else {
            "$this\n$REDIRECT_GATEWAY_DIRECTIVE"
        }
    }

    private fun String.injectAuthUserPassIfRequired(server: ProvisionedOpenVpnServer): String {
        val hasAuthDirective = AUTH_USER_PASS_REGEX.containsMatchIn(this)
        val username = server.username?.trim().orEmpty()
        val password = server.password?.trim().orEmpty()
        val hasCredentials = username.isNotEmpty() && password.isNotEmpty()

        if (hasAuthDirective && !hasCredentials) {
            throw VpnBootstrapConfigurationException("Missing username/password for server '${server.id}'")
        }

        if (!hasAuthDirective) {
            if (!hasCredentials) return this
            // OpenVPN3 should request credentials via provide_creds callback.
            return "$this\nauth-user-pass"
        }

        // Normalize any "auth-user-pass <path>" to bare directive so OpenVPN3 asks via provide_creds.
        val normalized = replace(AUTH_USER_PASS_REGEX, "auth-user-pass")
        if (normalized != this) {
            Log.d(VPN_TUNNEL_TAG, "Normalized auth-user-pass directive for server='${server.id}' to callback mode")
        }
        return normalized
    }

    private fun String.injectPrivateKeyPasswordIfRequired(server: ProvisionedOpenVpnServer): String {
        if (!containsEncryptedPrivateKey(this)) return this

        val privateKeyPassword = server.privateKeyPassword
                ?: throw VpnBootstrapConfigurationException("Missing private key password for server '${server.id}'")
        val askPassFile = writeSecretFile(
                fileName = "${server.id}_askpass.txt",
                content = "${privateKeyPassword.trim()}\n"
        )
        val askPassDirective = "askpass ${askPassFile.absolutePath}"
        val existingAskPass = ASK_PASS_REGEX.find(this)
        return if (existingAskPass == null) {
            "$this\n$askPassDirective"
        } else if (existingAskPass.groupValues[1].isNotBlank()) {
            this
        } else {
            replaceRange(existingAskPass.range, askPassDirective)
        }
    }

    private fun String.decryptEncryptedPrivateKeyIfPossible(server: ProvisionedOpenVpnServer): String {
        if (!containsEncryptedPrivateKey(this)) return this

        Log.w(
                VPN_TUNNEL_TAG,
                "Encrypted private key detected for server='${server.id}', using askpass/privateKeyPassword flow"
        )
        return injectPrivateKeyPasswordIfRequired(server)
    }

    private fun writeSecretFile(fileName: String, content: String): File {
        val dir = File(appContext.noBackupFilesDir, "vpn-secrets").apply { mkdirs() }
        return File(dir, fileName).apply {
            writeText(content)
        }
    }

    private companion object {
        private const val CONNECTION_POLL_INTERVAL_MS = 1_000L
        private const val STATE_FAIL_FAST_GRACE_MS = 1_500L
        private const val CONNECTION_ATTEMPTS_PER_SERVER = 2
        private const val REMOTE_PROBE_TIMEOUT_MS = 7_000
        private const val REDIRECT_GATEWAY_DIRECTIVE = "redirect-gateway def1"
        private val AUTH_USER_PASS_REGEX =
                Regex("(?im)^\\s*auth-user-pass(?:\\s+([^\\r\\n#;]+))?(?:\\s*[#;].*)?$")
        private val ASK_PASS_REGEX = Regex("(?m)^\\s*askpass(?:\\s+([^\\s#;]+))?\\s*$")
        private val REDIRECT_GATEWAY_REGEX = Regex("(?im)^\\s*redirect-gateway\\b.*$")
    }
}

private data class RemoteEndpoint(
        val host: String,
        val port: Int,
        val protocol: String,
) {
    override fun toString(): String = "$host:$port/$protocol"
}

private fun String?.extractPrimaryRemoteEndpoint(): RemoteEndpoint? {
    if (this.isNullOrBlank()) return null
    val match = REMOTE_DIRECTIVE_REGEX.find(this) ?: return null
    val host = match.groupValues[1].trim()
    val port = match.groupValues[2].toIntOrNull() ?: return null
    val protocol = match.groupValues.getOrNull(3)?.trim().orEmpty().ifBlank { "tcp" }
    return RemoteEndpoint(host = host, port = port, protocol = protocol)
}

private val REMOTE_DIRECTIVE_REGEX = Regex("(?im)^\\s*remote\\s+([^\\s#;]+)\\s+(\\d+)(?:\\s+([^\\s#;]+))?.*$")
private val IPV4_LITERAL_REGEX = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
private const val VPN_TUNNEL_TAG = "ElementVpnTunnel"
private val UNSUPPORTED_DIRECTIVE_REGEX =
        Regex("(?im)^\\s*(user|group|persist-tun|persist-key|pull|connect-retry)\\b.*$")

private fun RemoteEndpoint?.isPrivateOrLocalLiteralAddress(): Boolean {
    val host = this?.host?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return false
    val looksLikeIpLiteral = IPV4_LITERAL_REGEX.matches(host) || host.contains(":")
    if (!looksLikeIpLiteral) return false

    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress
}

internal fun sanitizeUnsupportedAndroidOptions(configuration: String): Pair<String, List<String>> {
    val removedDirectives = mutableListOf<String>()
    val sanitized = configuration.lineSequence()
            .filterNot { line ->
                val shouldRemove = UNSUPPORTED_DIRECTIVE_REGEX.containsMatchIn(line)
                if (shouldRemove) {
                    removedDirectives += line.trim()
                }
                shouldRemove
            }
            .joinToString(separator = "\n")
            .trim()
    return sanitized to removedDirectives
}

internal fun containsEncryptedPrivateKey(configuration: String): Boolean {
    return configuration.contains("BEGIN ENCRYPTED PRIVATE KEY") ||
            configuration.contains("Proc-Type: 4,ENCRYPTED", ignoreCase = true)
}

internal fun resolvePrivateKeyPasswordForService(configuration: String, privateKeyPassword: String?): String? {
    return privateKeyPassword?.takeIf { containsEncryptedPrivateKey(configuration) }
}

private fun String?.maskForLog(): String {
    if (this.isNullOrEmpty()) return "null"
    if (length <= 2) return "*".repeat(length)
    return "${first()}***${last()}(len=$length)"
}

private fun List<ProvisionedOpenVpnServer>.prioritizeBySelection(selectedServerId: String?): List<ProvisionedOpenVpnServer> {
    if (selectedServerId.isNullOrBlank()) return this

    val selected = firstOrNull { it.id == selectedServerId } ?: return this
    return buildList(capacity = size) {
        add(selected)
        addAll(this@prioritizeBySelection.filterNot { it.id == selectedServerId })
    }
}
