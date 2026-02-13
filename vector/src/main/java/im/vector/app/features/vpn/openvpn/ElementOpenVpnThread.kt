/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn.openvpn

import android.content.Context
import android.content.ContentResolver
import android.util.Log
import im.vector.app.features.vpn.VpnRuntimeDiagnostics
import com.tim.openvpn.OpenVPNThreadv3
import com.tim.openvpn.service.IOpenVPNService
import com.tim.openvpn.utils.NetworkUtils
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_ProvideCreds
import java.net.InetAddress

internal class ElementOpenVpnThread(
        service: IOpenVPNService,
        private val appContext: Context,
        private val inlineConfig: String,
        private val contentResolver: ContentResolver,
        private val username: String?,
        private val password: String?,
        private val privateKeyPassword: String?,
) : OpenVPNThreadv3(service, inlineConfig) {
    private val requiresUserPassword = AUTH_USER_PASS_REGEX.containsMatchIn(inlineConfig)

    fun configure(): Boolean {
        val remoteHost = inlineConfig.extractPrimaryRemoteHost()
        val allowLocalLanAccess = remoteHost.isPrivateOrLocalLiteralAddress()

        val config = ClientAPI_Config().apply {
            setContent(inlineConfig)
            setTunPersist(true)
            setExternalPkiAlias("extpki")
            setCompressionMode("asym")
            setHwAddrOverride(NetworkUtils.getFakeMacAddrFromSAAID(contentResolver))
            setInfo(true)
            setAllowLocalLanAccess(allowLocalLanAccess)
            setRetryOnAuthFailed(false)
            // Prefer compatibility for self-hosted/older OpenVPN servers.
            setEnableLegacyAlgorithms(true)
            setEnableNonPreferredDCAlgorithms(true)
            setEnableRouteEmulation(false)
            privateKeyPassword
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { setPrivateKeyPassword(it) }
        }

        val evalResult = eval_config(config)
        Log.d(
                TAG,
                "configure(): error=${evalResult.error} externalPki=${evalResult.externalPki} message=${evalResult.message} hasCredentials=${!username.isNullOrBlank() && !password.isNullOrBlank()} hasPrivateKeyPassword=${!privateKeyPassword.isNullOrBlank()} allowLocalLanAccess=${allowLocalLanAccess} remoteHost=${remoteHost ?: "unknown"}"
        )
        if (evalResult.error) {
            VpnRuntimeDiagnostics.writeLastFailure(
                    appContext,
                    "eval_config error: ${evalResult.message}"
            )
            return false
        }

        // Keep parity with upstream setConfig() sequence from OpenVPNThreadv3.
        config.setContent(inlineConfig)
        if (!submitUserPasswordCredentialsIfRequired()) {
            return false
        }
        return true
    }

    private fun submitUserPasswordCredentialsIfRequired(): Boolean {
        if (!requiresUserPassword) return true
        val trimmedUsername = username?.trim().orEmpty()
        val trimmedPassword = password?.trim().orEmpty()
        val hasCredentials = trimmedUsername.isNotEmpty() && trimmedPassword.isNotEmpty()

        if (!hasCredentials) {
            val message = "OpenVPN auth-user-pass requires username/password but provisioning credentials are missing"
            Log.e(TAG, "submitUserPasswordCredentialsIfRequired(): $message")
            VpnRuntimeDiagnostics.writeLastFailure(appContext, message)
            return false
        }

        val creds = ClientAPI_ProvideCreds().apply {
            setUsername(trimmedUsername)
            setPassword(trimmedPassword)
            setCachePassword(false)
            setReplacePasswordWithSessionID(false)
        }
        val status = super.provide_creds(creds)
        if (status.error) {
            val message = "provide_creds failed: status='${status.status}' message='${status.message}'"
            Log.e(TAG, "submitUserPasswordCredentialsIfRequired(): $message")
            VpnRuntimeDiagnostics.writeLastFailure(appContext, message)
            return false
        }

        Log.d(
                TAG,
                "submitUserPasswordCredentialsIfRequired(): hasCredentials=$hasCredentials user=${trimmedUsername.maskForLog()} pass=${trimmedPassword.maskForLog()}"
        )
        return true
    }

    override fun log(logInfo: ClientAPI_LogInfo?) {
        if (logInfo != null) {
            val line = logInfo.text.trimEnd()
            if (line.isNotEmpty()) {
                Log.d(TAG, "ovpn-log: $line")
                maybeCaptureFailureFromLogLine(line)
            }
        }
        super.log(logInfo)
    }

    override fun event(event: ClientAPI_Event?) {
        if (event != null) {
            Log.d(
                    TAG,
                    "ovpn-event: name=${event.name} info=${event.info} error=${event.error} fatal=${event.fatal}"
            )
            if (event.error || event.fatal) {
                VpnRuntimeDiagnostics.writeLastFailure(
                        appContext,
                        "event '${event.name}': ${event.info}"
                )
            }
        }
        super.event(event)
    }

    override fun pause_on_connection_timeout(): Boolean {
        // Returning false forces OpenVPN core to fail instead of waiting forever in paused state.
        Log.w(TAG, "pause_on_connection_timeout(): false")
        return false
    }

    private companion object {
        private const val TAG = "ElementOpenVpnDiag"
        private val AUTH_USER_PASS_REGEX = Regex("(?im)^\\s*auth-user-pass\\b.*$")
    }

    private fun maybeCaptureFailureFromLogLine(line: String) {
        val lower = line.lowercase()
        val isFailureSignal = lower.contains("auth_failed") ||
                lower.contains("tls error") ||
                lower.contains("verify error") ||
                lower.contains("fatal") ||
                lower.contains("error")
        if (isFailureSignal) {
            VpnRuntimeDiagnostics.writeLastFailure(appContext, line)
        }
    }
}

private fun String.maskForLog(): String {
    if (isEmpty()) return "null"
    if (length <= 2) return "*".repeat(length)
    return "${first()}***${last()}(len=$length)"
}

private fun String.extractPrimaryRemoteHost(): String? {
    val match = REMOTE_DIRECTIVE_REGEX.find(this) ?: return null
    return match.groupValues[1].trim().removePrefix("[").removeSuffix("]")
}

private fun String?.isPrivateOrLocalLiteralAddress(): Boolean {
    val host = this?.trim().orEmpty()
    if (host.isEmpty()) return false

    val looksLikeIpLiteral = IPV4_LITERAL_REGEX.matches(host) || host.contains(":")
    if (!looksLikeIpLiteral) return false

    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress
}

private val REMOTE_DIRECTIVE_REGEX = Regex("(?im)^\\s*remote\\s+([^\\s#;]+)\\s+\\d+(?:\\s+([^\\s#;]+))?.*$")
private val IPV4_LITERAL_REGEX = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
