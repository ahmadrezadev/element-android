/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnProvisioningClient @Inject constructor(
        context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun fetchProvisionedConfig(): ProvisionedVpnConfig = withContext(Dispatchers.IO) {
        Log.d(VPN_PROVISIONING_TAG, "Loading VPN bootstrap from asset '$LOCAL_BOOTSTRAP_ASSET_PATH'")
        val json = JSONObject(readAssetText(LOCAL_BOOTSTRAP_ASSET_PATH))
        val defaultUsername = json.readNonBlankString("vpn_username", "username")
        val defaultPassword = json.readNonBlankString("vpn_password", "password")
        val defaultPrivateKeyPassword = json.readNonBlankString("vpn_private_key_password", "private_key_password", "key_password")

        val matrixHomeServerUrl = json.readNonBlankString(
                "matrix_homeserver_url",
                "matrix_url",
                "matrix_link",
                "matrixBaseUrl",
                "matrixHomeServerUrl"
        ) ?: throw VpnBootstrapConfigurationException("Missing matrix homeserver URL in VPN bootstrap response")
        Log.d(VPN_PROVISIONING_TAG, "Extracted homeserver URL: $matrixHomeServerUrl")

        val serversArray = json.readArray(
                "vpn_servers",
                "openvpn_servers",
                "servers"
        ) ?: throw VpnBootstrapConfigurationException("Missing VPN servers list in VPN bootstrap response")
        Log.d(
                VPN_PROVISIONING_TAG,
                "Found ${serversArray.length()} configured VPN servers (defaultUser=${defaultUsername ?: "null"}, defaultPass=${defaultPassword.maskForLog()}, defaultKeyPass=${defaultPrivateKeyPassword.maskForLog()})"
        )

        val servers = buildList {
            for (index in 0 until serversArray.length()) {
                val value = serversArray.opt(index)
                when (value) {
                    is JSONObject -> add(
                            value.toProvisionedServer(
                                    index = index,
                                    readAsset = ::readAssetText,
                                    defaultUsername = defaultUsername,
                                    defaultPassword = defaultPassword,
                                    defaultPrivateKeyPassword = defaultPrivateKeyPassword
                            )
                    )
                    is String -> {
                        Log.d(VPN_PROVISIONING_TAG, "Server[$index] declared as string value: ${value.take(120)}")
                        val ovpn = value.trim().readOvpnConfig(readAsset = ::readAssetText)
                        if (ovpn.isNotEmpty()) {
                            val remoteEndpoint = ovpn.extractRemoteEndpointForLog()
                            Log.d(
                                    VPN_PROVISIONING_TAG,
                                    "Server[$index] ovpn loaded from string source (length=${ovpn.length}, remote=${remoteEndpoint ?: "unknown"})"
                            )
                            add(
                                    ProvisionedOpenVpnServer(
                                            id = "server_$index",
                                            priority = index,
                                            isActive = true,
                                            connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS,
                                            ovpnConfig = ovpn,
                                            username = defaultUsername,
                                            password = defaultPassword,
                                            privateKeyPassword = defaultPrivateKeyPassword,
                                            host = null,
                                            port = null,
                                            protocol = null,
                                            cipher = null,
                                            auth = null,
                                            ca = null,
                                            key = null,
                                            cert = null,
                                            tlsCrypt = null
                                    )
                            )
                        }
                    }
                }
            }
        }

        val activeServers = servers
                .filter { it.isActive }
                .sortedBy { it.priority }

        if (activeServers.isEmpty()) {
            throw VpnBootstrapConfigurationException("No active OpenVPN server found in bootstrap response")
        }
        Log.d(VPN_PROVISIONING_TAG, "Active VPN servers count=${activeServers.size}: ${activeServers.joinToString { it.id }}")

        ProvisionedVpnConfig(
                matrixHomeServerUrl = matrixHomeServerUrl,
                vpnServers = activeServers
        )
    }

    private fun readAssetText(assetPath: String): String {
        return runCatching {
            appContext.assets.open(assetPath).bufferedReader().use { reader ->
                reader.readText().also { text ->
                    Log.d(VPN_PROVISIONING_TAG, "Loaded asset '$assetPath' (chars=${text.length})")
                }
            }
        }.getOrElse { failure ->
            throw VpnBootstrapConfigurationException(
                    "Unable to read VPN asset '$assetPath': ${failure.localizedMessage}"
            )
        }
    }
}

private fun JSONObject.toProvisionedServer(
        index: Int,
        readAsset: (String) -> String,
        defaultUsername: String?,
        defaultPassword: String?,
        defaultPrivateKeyPassword: String?,
): ProvisionedOpenVpnServer {
    val serverId = readNonBlankString("id", "name", "server_id") ?: "server_$index"
    val inlineConfig = readNonBlankString("ovpn_config", "config", "openvpn_config")
    val assetPath = readNonBlankString("ovpn_asset", "ovpn_asset_path", "ovpn_file", "file")
    val ovpnConfig = (inlineConfig ?: assetPath).orEmpty().readOvpnConfig(readAsset)
    if (ovpnConfig.isBlank()) {
        throw VpnBootstrapConfigurationException("Missing OpenVPN config for server '$serverId'")
    }

    val username = readNonBlankString("username", "user", "login") ?: defaultUsername
    val password = readNonBlankString("password", "pass") ?: defaultPassword
    val privateKeyPassword = readNonBlankString("private_key_password", "key_password", "privateKeyPassword")
            ?: defaultPrivateKeyPassword
    val connectionTimeoutMs = normalizeConnectionTimeoutMs(readInt("connection_timeout_ms"))
    val remoteEndpoint = ovpnConfig.extractRemoteEndpointForLog()
    Log.d(
            VPN_PROVISIONING_TAG,
            "Server[$index] id='$serverId' source=${if (assetPath != null) "asset:$assetPath" else "inline"} active=${readBoolean(defaultValue = true, "active", "enabled", "is_active")} priority=${readInt("priority") ?: index} timeoutMs=${connectionTimeoutMs} user=${username ?: "null"} pass=${password.maskForLog()} keyPass=${privateKeyPassword.maskForLog()} ovpnChars=${ovpnConfig.length} remote=${remoteEndpoint ?: "unknown"}"
    )

    return ProvisionedOpenVpnServer(
            id = serverId,
            priority = readInt("priority") ?: index,
            isActive = readBoolean(defaultValue = true, "active", "enabled", "is_active"),
            connectionTimeoutMs = connectionTimeoutMs,
            ovpnConfig = ovpnConfig,
            username = username,
            password = password,
            privateKeyPassword = privateKeyPassword,
            host = readNonBlankString("host", "ip", "server"),
            port = readInt("port"),
            protocol = readNonBlankString("protocol", "type", "transport"),
            cipher = readNonBlankString("cipher"),
            auth = readNonBlankString("auth"),
            ca = readNonBlankString("ca"),
            key = readNonBlankString("key"),
            cert = readNonBlankString("cert"),
            tlsCrypt = readNonBlankString("tls_crypt", "tlsCrypt")
    )
}

private fun String.readOvpnConfig(readAsset: (String) -> String): String {
    val candidate = trim()
    if (candidate.isEmpty()) return ""
    return if (candidate.endsWith(".ovpn", ignoreCase = true) && !candidate.contains('\n')) {
        readAsset(candidate).trim()
    } else {
        candidate
    }
}

private fun JSONObject.readArray(vararg keys: String): JSONArray? {
    keys.forEach { key ->
        val array = optJSONArray(key)
        if (array != null) return array
    }
    return null
}

private fun JSONObject.readNonBlankString(vararg keys: String): String? {
    keys.forEach { key ->
        val value = opt(key)
        if (value is String) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
    }
    return null
}

private fun JSONObject.readInt(vararg keys: String): Int? {
    keys.forEach { key ->
        val value = opt(key)
        when (value) {
            is Number -> return value.toInt()
            is String -> value.toIntOrNull()?.let { return it }
        }
    }
    return null
}

private fun JSONObject.readBoolean(defaultValue: Boolean, vararg keys: String): Boolean {
    keys.forEach { key ->
        val value = opt(key)
        when (value) {
            is Boolean -> return value
            is String -> when (value.lowercase()) {
                "true", "1", "yes", "on" -> return true
                "false", "0", "no", "off" -> return false
            }
            is Number -> return value.toInt() != 0
        }
    }
    return defaultValue
}

private const val LOCAL_BOOTSTRAP_ASSET_PATH = "vpn/bootstrap.json"
private const val VPN_PROVISIONING_TAG = "ManaVpnProvisioning"
private val LOG_REMOTE_DIRECTIVE_REGEX = Regex("(?im)^\\s*remote\\s+([^\\s#;]+)\\s+(\\d+)(?:\\s+([^\\s#;]+))?.*$")

private fun String?.maskForLog(): String {
    if (this.isNullOrEmpty()) return "null"
    if (length <= 2) return "*".repeat(length)
    return "${first()}***${last()}(len=$length)"
}

private fun String.extractRemoteEndpointForLog(): String? {
    val match = LOG_REMOTE_DIRECTIVE_REGEX.find(this) ?: return null
    val host = match.groupValues[1].trim()
    val port = match.groupValues[2].trim()
    val protocol = match.groupValues.getOrNull(3)?.trim().orEmpty().ifBlank { "tcp/udp?" }
    return "$host:$port/$protocol"
}
