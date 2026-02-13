/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tim.basevpn.state.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.lazyViewModel
import im.vector.app.core.extensions.validateBackPressed
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.platform.lifecycleAwareLazy
import im.vector.app.databinding.ActivityLoginBinding
import im.vector.app.features.login.LoginConfig
import im.vector.app.features.pin.UnlockedActivity
import im.vector.app.features.vpn.VpnConnectionStatusTracker
import im.vector.app.features.vpn.VpnConnectionUiState
import im.vector.app.features.vpn.formatVpnSpeed
import im.vector.app.features.vpn.toLocalizedString
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingActivity : VectorBaseActivity<ActivityLoginBinding>(), UnlockedActivity {

    private val onboardingVariant by lifecycleAwareLazy {
        onboardingVariantFactory.create(this, views = views, onboardingViewModel = lazyViewModel())
    }
    private var hasInitializedUiAndData = false
    private var firstCreationAtInit = false

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        completeInitUiAndData()
    }

    @Inject lateinit var onboardingVariantFactory: OnboardingVariantFactory
    @Inject lateinit var vpnConnectionStatusTracker: VpnConnectionStatusTracker

    override fun getBinding() = ActivityLoginBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.coordinatorLayout

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onboardingVariant.onNewIntent(intent)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        validateBackPressed {
            super.onBackPressed()
        }
    }

    override fun initUiAndData() {
        firstCreationAtInit = isFirstCreation()
        maybeRequestVpnPermissionThenInit()
    }

    private fun maybeRequestVpnPermissionThenInit() {
        if (hasInitializedUiAndData) return
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            completeInitUiAndData()
        } else {
            vpnPermissionLauncher.launch(prepareIntent)
        }
    }

    private fun completeInitUiAndData() {
        if (hasInitializedUiAndData) return
        hasInitializedUiAndData = true
        observeVpnStatus()
        onboardingVariant.initUiAndData(firstCreationAtInit)
    }

    // Hack for AccountCreatedFragment
    fun setIsLoading(isLoading: Boolean) {
        onboardingVariant.setIsLoading(isLoading)
    }

    private fun observeVpnStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vpnConnectionStatusTracker.uiState.collect { renderVpnStatus(it) }
            }
        }
    }

    private fun renderVpnStatus(status: VpnConnectionUiState) {
        views.vpnStatusGroup.isVisible = status.isVisible
        if (!status.isVisible) return

        views.vpnStatusServerValue.text = status.serverName ?: getString(R.string.vpn_status_server_unknown)
        views.vpnStatusStateValue.text = buildString {
            append(status.connectionState.toLocalizedString(this@OnboardingActivity))
            status.details
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append('\n')
                        append(it)
                    }
        }
        views.vpnStatusSpeedValue.text = getString(
                R.string.vpn_status_speed_value,
                formatVpnSpeed(status.downloadBytesPerSecond),
                formatVpnSpeed(status.uploadBytesPerSecond)
        )
        views.vpnStatusProgress.isVisible = when (status.connectionState) {
            ConnectionState.CONNECTING,
            ConnectionState.READYFORCONNECT,
            ConnectionState.IDLE -> true
            else -> false
        }
    }

    companion object {
        const val EXTRA_CONFIG = "EXTRA_CONFIG"

        fun newIntent(context: Context, loginConfig: LoginConfig?): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                putExtra(EXTRA_CONFIG, loginConfig)
            }
        }

        fun redirectIntent(context: Context, data: Uri?): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                setData(data)
            }
        }
    }
}
