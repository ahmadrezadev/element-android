/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.core.di.DefaultPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnServerSelectionStore @Inject constructor(
        @DefaultPreferences private val preferences: SharedPreferences,
) {

    fun saveSelectedServerId(serverId: String) {
        preferences.edit { putString(KEY_SELECTED_VPN_SERVER_ID, serverId.trim()) }
    }

    fun getSelectedServerId(): String? {
        return preferences.getString(KEY_SELECTED_VPN_SERVER_ID, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val KEY_SELECTED_VPN_SERVER_ID = "KEY_SELECTED_VPN_SERVER_ID"
    }
}
