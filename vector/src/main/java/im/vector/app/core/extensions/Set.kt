/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

// Create a new Set including the provided mana if not already present, or removing the mana if already present
fun <T> Set<T>.toggle(mana: T, singleMana: Boolean = false): Set<T> {
    return if (contains(mana)) {
        if (singleMana) {
            emptySet()
        } else {
            minus(mana)
        }
    } else {
        if (singleMana) {
            setOf(mana)
        } else {
            plus(mana)
        }
    }
}
