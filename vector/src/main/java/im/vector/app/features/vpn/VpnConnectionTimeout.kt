/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

internal const val DEFAULT_CONNECTION_TIMEOUT_MS = 10_000L
internal const val MIN_CONNECTION_TIMEOUT_MS = 2_000L
internal const val MAX_CONNECTION_TIMEOUT_MS = 120_000L

internal fun normalizeConnectionTimeoutMs(rawTimeoutMs: Int?): Long {
    val value = rawTimeoutMs?.toLong() ?: DEFAULT_CONNECTION_TIMEOUT_MS
    return value.coerceIn(MIN_CONNECTION_TIMEOUT_MS, MAX_CONNECTION_TIMEOUT_MS)
}
