/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import com.tim.basevpn.state.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class VpnConnectionUiState(
        val isVisible: Boolean = false,
        val serverName: String? = null,
        val connectionState: ConnectionState = ConnectionState.IDLE,
        val details: String? = null,
        val downloadBytesPerSecond: Long = 0L,
        val uploadBytesPerSecond: Long = 0L,
)

@Singleton
class VpnConnectionStatusTracker @Inject constructor() {

    private val _uiState = MutableStateFlow(VpnConnectionUiState())
    val uiState: StateFlow<VpnConnectionUiState> = _uiState.asStateFlow()

    fun onConnecting(serverName: String) {
        _uiState.update {
            it.copy(
                    isVisible = true,
                    serverName = serverName,
                    connectionState = ConnectionState.CONNECTING,
                    details = null,
                    downloadBytesPerSecond = 0L,
                    uploadBytesPerSecond = 0L
            )
        }
    }

    fun onConnected(serverName: String?) {
        _uiState.update {
            it.copy(
                    isVisible = false,
                    serverName = serverName ?: it.serverName,
                    connectionState = ConnectionState.CONNECTED,
                    details = null,
                    downloadBytesPerSecond = 0L,
                    uploadBytesPerSecond = 0L
            )
        }
    }

    fun onPermissionRequired(serverName: String?) {
        _uiState.update {
            it.copy(
                    isVisible = true,
                    serverName = serverName ?: it.serverName,
                    connectionState = ConnectionState.PERMISSION_NOT_GRANTED,
                    details = null,
                    downloadBytesPerSecond = 0L,
                    uploadBytesPerSecond = 0L
            )
        }
    }

    fun onFailed(serverName: String?, reason: String?) {
        _uiState.update {
            it.copy(
                    isVisible = true,
                    serverName = serverName ?: it.serverName,
                    connectionState = ConnectionState.DISCONNECTED,
                    details = reason,
                    downloadBytesPerSecond = 0L,
                    uploadBytesPerSecond = 0L
            )
        }
    }

    fun onConnectionStateChanged(connectionState: ConnectionState) {
        _uiState.update {
            val shouldShow = when (connectionState) {
                ConnectionState.CONNECTED -> false
                else -> it.isVisible || it.serverName != null
            }
            it.copy(
                    isVisible = shouldShow,
                    connectionState = connectionState,
                    details = if (connectionState == ConnectionState.CONNECTED) null else it.details
            )
        }
    }

    fun updateSpeed(downloadBytesPerSecond: Long, uploadBytesPerSecond: Long) {
        _uiState.update {
            it.copy(
                    downloadBytesPerSecond = downloadBytesPerSecond.coerceAtLeast(0L),
                    uploadBytesPerSecond = uploadBytesPerSecond.coerceAtLeast(0L)
            )
        }
    }
}
