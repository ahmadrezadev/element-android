/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnBootstrapCoordinator @Inject constructor(
        private val provisioningClient: VpnProvisioningClient,
        private val openVpnTunnelManager: OpenVpnTunnelManager,
        private val matrixGatewayStore: MatrixGatewayStore,
        private val vpnConnectionStatusTracker: VpnConnectionStatusTracker,
        private val vpnServerSelectionStore: VpnServerSelectionStore,
) {
    private val mutex = Mutex()

    suspend fun ensureVpnAndResolveHomeserver(): Result<String> = mutex.withLock {
        runCatching {
            val provisionedConfig = provisioningClient.fetchProvisionedConfig()
            if (!openVpnTunnelManager.isConnected()) {
                openVpnTunnelManager.connectByPriority(provisionedConfig.vpnServers).getOrThrow()
            } else {
                val selectedServerId = vpnServerSelectionStore.getSelectedServerId()
                        ?: provisionedConfig.vpnServers.firstOrNull()?.id
                selectedServerId?.let(vpnServerSelectionStore::saveSelectedServerId)
                vpnConnectionStatusTracker.onConnected(selectedServerId)
            }
            matrixGatewayStore.saveMatrixHomeserverUrl(provisionedConfig.matrixHomeServerUrl)
            provisionedConfig.matrixHomeServerUrl
        }
    }
}
