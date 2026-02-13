/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class VpnConnectionTimeoutTest {

    @Test
    fun `normalizeConnectionTimeoutMs uses default when missing`() {
        normalizeConnectionTimeoutMs(rawTimeoutMs = null) shouldBeEqualTo DEFAULT_CONNECTION_TIMEOUT_MS
    }

    @Test
    fun `normalizeConnectionTimeoutMs clamps to allowed range`() {
        normalizeConnectionTimeoutMs(rawTimeoutMs = 1000) shouldBeEqualTo MIN_CONNECTION_TIMEOUT_MS
        normalizeConnectionTimeoutMs(rawTimeoutMs = 500000) shouldBeEqualTo MAX_CONNECTION_TIMEOUT_MS
    }
}
