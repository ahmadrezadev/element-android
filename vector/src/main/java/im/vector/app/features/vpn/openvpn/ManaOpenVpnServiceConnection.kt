/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn.openvpn

import android.content.Context
import com.tim.basevpn.connection.VpnServiceConnection
import com.tim.basevpn.state.ConnectionState

class ManaOpenVpnServiceConnection(
        context: Context,
        stateListener: ((ConnectionState) -> Unit)? = null,
) : VpnServiceConnection(
        context = context,
        clazz = ManaOpenVpnService::class.java,
        stateListener = stateListener
)

