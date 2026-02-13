/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import android.content.Context
import com.tim.basevpn.state.ConnectionState
import im.vector.app.R
import kotlin.math.roundToInt

fun ConnectionState.toLocalizedString(context: Context): String {
    return when (this) {
        ConnectionState.CONNECTING -> context.getString(R.string.vpn_status_state_connecting)
        ConnectionState.CONNECTED -> context.getString(R.string.vpn_status_state_connected)
        ConnectionState.DISCONNECTED -> context.getString(R.string.vpn_status_state_disconnected)
        ConnectionState.DISCONNECTING -> context.getString(R.string.vpn_status_state_disconnecting)
        ConnectionState.PERMISSION_NOT_GRANTED -> context.getString(R.string.vpn_status_state_permission_required)
        ConnectionState.READYFORCONNECT -> context.getString(R.string.vpn_status_state_ready)
        ConnectionState.IDLE -> context.getString(R.string.vpn_status_state_idle)
    }
}

fun formatVpnSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0L) return "0 B/s"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytesPerSecond >= gb -> "${(bytesPerSecond / gb * 10.0).roundToInt() / 10.0} GB/s"
        bytesPerSecond >= mb -> "${(bytesPerSecond / mb * 10.0).roundToInt() / 10.0} MB/s"
        bytesPerSecond >= kb -> "${(bytesPerSecond / kb * 10.0).roundToInt() / 10.0} KB/s"
        else -> "$bytesPerSecond B/s"
    }
}

