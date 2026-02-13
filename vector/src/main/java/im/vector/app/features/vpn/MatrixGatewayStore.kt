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
class MatrixGatewayStore @Inject constructor(
        @DefaultPreferences
        private val preferences: SharedPreferences,
) {
    fun saveMatrixHomeserverUrl(url: String) {
        preferences.edit {
            putString(KEY_MATRIX_HOMESERVER_URL, url.trim())
        }
    }

    fun getMatrixHomeserverUrl(): String? {
        return preferences.getString(KEY_MATRIX_HOMESERVER_URL, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val KEY_MATRIX_HOMESERVER_URL = "KEY_MATRIX_HOMESERVER_URL"
    }
}

