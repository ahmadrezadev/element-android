/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.vpn

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.preference.VectorPreference
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.app.features.vpn.OpenVpnTunnelManager
import im.vector.app.features.vpn.ProvisionedOpenVpnServer
import im.vector.app.features.vpn.VpnConnectionStatusTracker
import im.vector.app.features.vpn.VpnConnectionUiState
import im.vector.app.features.vpn.VpnProvisioningClient
import im.vector.app.features.vpn.VpnServerSelectionStore
import im.vector.app.features.vpn.displayName
import im.vector.app.features.vpn.formatVpnSpeed
import im.vector.app.features.vpn.toLocalizedString
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsVpnFragment : VectorSettingsBaseFragment() {

    @Inject lateinit var vpnProvisioningClient: VpnProvisioningClient
    @Inject lateinit var openVpnTunnelManager: OpenVpnTunnelManager
    @Inject lateinit var vpnConnectionStatusTracker: VpnConnectionStatusTracker
    @Inject lateinit var vpnServerSelectionStore: VpnServerSelectionStore

    override var titleRes: Int = R.string.vpn_settings_title
    override val preferenceXmlRes: Int = R.xml.vector_settings_vpn

    private val activeServerPreference by lazy {
        findPreference<VectorPreference>(KEY_VPN_ACTIVE_SERVER)!!
    }
    private val connectionStatusPreference by lazy {
        findPreference<VectorPreference>(KEY_VPN_CONNECTION_STATUS)!!
    }
    private val speedPreference by lazy {
        findPreference<VectorPreference>(KEY_VPN_SPEED)!!
    }
    private val selectedServerPreference by lazy {
        findPreference<VectorPreference>(KEY_VPN_SELECTED_SERVER)!!
    }
    private val reconnectPreference by lazy {
        findPreference<VectorPreference>(KEY_VPN_RECONNECT)!!
    }

    private var availableServers: List<ProvisionedOpenVpnServer> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeVpnStatus()
    }

    override fun onResume() {
        super.onResume()
        loadProvisionedServers()
        refreshVpnState()
    }

    override fun bindPref() {
        selectedServerPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showServerPicker()
            true
        }
        reconnectPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            reconnectVpn()
            true
        }
    }

    private fun observeVpnStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vpnConnectionStatusTracker.uiState.collect { renderVpnStatus(it) }
            }
        }
    }

    private fun refreshVpnState() {
        viewLifecycleOwner.lifecycleScope.launch {
            openVpnTunnelManager.refreshConnectionState()
        }
    }

    private fun loadProvisionedServers() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                vpnProvisioningClient.fetchProvisionedConfig().vpnServers
            }.fold(
                    onSuccess = { servers ->
                        availableServers = servers
                        val selectedServerId = vpnServerSelectionStore.getSelectedServerId()
                        if (selectedServerId == null && servers.isNotEmpty()) {
                            vpnServerSelectionStore.saveSelectedServerId(servers.first().id)
                        }
                        updateSelectedServerSummary()
                    },
                    onFailure = {
                        availableServers = emptyList()
                        selectedServerPreference.summary = getString(R.string.vpn_settings_server_load_error)
                        reconnectPreference.isEnabled = false
                    }
            )
        }
    }

    private fun showServerPicker() {
        if (availableServers.isEmpty()) {
            Toast.makeText(requireContext(), R.string.vpn_settings_no_servers_available, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = availableServers.map { it.displayName() }.toTypedArray()
        val currentSelectedId = vpnServerSelectionStore.getSelectedServerId()
        var selectedIndex = availableServers.indexOfFirst { it.id == currentSelectedId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.vpn_settings_select_server_title)
                .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                    selectedIndex = which
                }
                .setPositiveButton(CommonStrings.ok) { _, _ ->
                    val selected = availableServers.getOrNull(selectedIndex) ?: return@setPositiveButton
                    vpnServerSelectionStore.saveSelectedServerId(selected.id)
                    updateSelectedServerSummary()
                    reconnectVpn()
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun reconnectVpn() {
        if (availableServers.isEmpty()) {
            Toast.makeText(requireContext(), R.string.vpn_settings_no_servers_available, Toast.LENGTH_SHORT).show()
            return
        }

        displayLoadingView()
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                openVpnTunnelManager.connectByPriority(availableServers).getOrThrow()
            }.fold(
                    onSuccess = { server ->
                        hideLoadingView()
                        Toast.makeText(
                                requireContext(),
                                getString(R.string.vpn_settings_reconnect_success, server.displayName()),
                                Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = {
                        hideLoadingView()
                        displayErrorDialog(it)
                    }
            )
        }
    }

    private fun updateSelectedServerSummary() {
        val selectedServerId = vpnServerSelectionStore.getSelectedServerId()
        val selectedServer = availableServers.firstOrNull { it.id == selectedServerId }
        selectedServerPreference.summary = selectedServer?.displayName()
                ?: getString(R.string.vpn_settings_server_not_selected)
        reconnectPreference.isEnabled = availableServers.isNotEmpty()
    }

    private fun renderVpnStatus(state: VpnConnectionUiState) {
        val selectedServer = availableServers.firstOrNull { it.id == vpnServerSelectionStore.getSelectedServerId() }
        activeServerPreference.summary = state.serverName
                ?: selectedServer?.displayName()
                ?: getString(R.string.vpn_status_server_unknown)

        connectionStatusPreference.summary = buildString {
            append(state.connectionState.toLocalizedString(requireContext()))
            state.details
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append('\n')
                        append(it)
                    }
        }

        speedPreference.summary = getString(
                R.string.vpn_status_speed_value,
                formatVpnSpeed(state.downloadBytesPerSecond),
                formatVpnSpeed(state.uploadBytesPerSecond)
        )
    }

    private companion object {
        private const val KEY_VPN_ACTIVE_SERVER = "SETTINGS_VPN_ACTIVE_SERVER_KEY"
        private const val KEY_VPN_CONNECTION_STATUS = "SETTINGS_VPN_CONNECTION_STATUS_KEY"
        private const val KEY_VPN_SPEED = "SETTINGS_VPN_SPEED_KEY"
        private const val KEY_VPN_SELECTED_SERVER = "SETTINGS_VPN_SELECTED_SERVER_KEY"
        private const val KEY_VPN_RECONNECT = "SETTINGS_VPN_RECONNECT_KEY"
    }
}
