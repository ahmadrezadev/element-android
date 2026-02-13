/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.login

import im.vector.app.features.vpn.MatrixGatewayStore
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.network.ssl.Fingerprint
import timber.log.Timber
import javax.inject.Inject

class HomeServerConnectionConfigFactory @Inject constructor(
        private val matrixGatewayStore: MatrixGatewayStore,
) {

    fun create(url: String?, fingerprints: List<Fingerprint>? = null): HomeServerConnectionConfig? {
        val pinnedHomeserverUrl = matrixGatewayStore.getMatrixHomeserverUrl()
        if (pinnedHomeserverUrl == null) {
            Timber.w("Pinned homeserver URL is missing")
            return null
        }

        if (url != null && !url.isSameHomeserverAs(pinnedHomeserverUrl)) {
            Timber.w("Ignoring non-pinned homeserver URL: $url")
        }

        return try {
            HomeServerConnectionConfig.Builder()
                    .withHomeServerUri(pinnedHomeserverUrl)
                    .withAllowedFingerPrints(fingerprints)
                    .build()
        } catch (t: Throwable) {
            Timber.e(t)
            null
        }
    }
}

private fun String.isSameHomeserverAs(other: String): Boolean {
    return trim().trimEnd('/').equals(other.trim().trimEnd('/'), ignoreCase = true)
}
