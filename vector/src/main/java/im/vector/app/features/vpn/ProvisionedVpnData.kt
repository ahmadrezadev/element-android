/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import com.tim.openvpn.configuration.OpenVPNConfig

data class ProvisionedVpnConfig(
        val matrixHomeServerUrl: String,
        val vpnServers: List<ProvisionedOpenVpnServer>,
)

data class ProvisionedOpenVpnServer(
        val id: String,
        val priority: Int,
        val isActive: Boolean,
        val connectionTimeoutMs: Long,
        val ovpnConfig: String?,
        val username: String?,
        val password: String?,
        val privateKeyPassword: String?,
        val host: String?,
        val port: Int?,
        val protocol: String?,
        val cipher: String?,
        val auth: String?,
        val ca: String?,
        val key: String?,
        val cert: String?,
        val tlsCrypt: String?,
) {
    fun toOpenVpnConfig(): OpenVPNConfig {
        return OpenVPNConfig(
                name = id,
                host = host,
                port = port ?: DEFAULT_OPENVPN_PORT,
                type = protocol ?: DEFAULT_OPENVPN_PROTOCOL,
                cipher = cipher ?: DEFAULT_CIPHER,
                auth = auth ?: DEFAULT_AUTH,
                ca = ca,
                key = key,
                cert = cert,
                tlsCrypt = tlsCrypt,
                configuration = ovpnConfig
        )
    }
}

class VpnBootstrapConfigurationException(message: String) : IllegalStateException(message)

class VpnPermissionRequiredException : SecurityException("VPN permission is required")

class VpnConnectionFailedException(message: String) : IllegalStateException(message)

private const val DEFAULT_OPENVPN_PORT = 1194
private const val DEFAULT_OPENVPN_PROTOCOL = "udp"
private const val DEFAULT_CIPHER = "AES-256-GCM"
private const val DEFAULT_AUTH = "SHA256"

fun ProvisionedOpenVpnServer.displayName(): String {
    return host ?: id
}
