/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.raw.wellknown

import im.vector.app.features.crypto.keysrequest.OutboundSessionKeySharingStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixPatterns.getServerName
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.raw.RawService

suspend fun RawService.getManaWellknown(sessionParams: SessionParams): ManaWellKnown? {
    // By default we use the domain of the userId to retrieve the .well-known data
    val domain = sessionParams.userId.getServerName()
    return tryOrNull { getWellknown(domain) }
            ?.let { ManaWellKnownMapper.from(it) }
}

fun ManaWellKnown.isE2EByDefault() = manaE2E?.e2eDefault ?: riotE2E?.e2eDefault ?: true

fun ManaWellKnown?.getOutboundSessionKeySharingStrategyOrDefault(fallback: OutboundSessionKeySharingStrategy): OutboundSessionKeySharingStrategy {
    return when (this?.manaE2E?.outboundsKeyPreSharingMode) {
        "on_room_opening" -> OutboundSessionKeySharingStrategy.WhenEnteringRoom
        "on_typing" -> OutboundSessionKeySharingStrategy.WhenTyping
        "disabled" -> OutboundSessionKeySharingStrategy.WhenSendingEvent
        else -> fallback
    }
}

fun RawService.withManaWellKnown(
        coroutineScope: CoroutineScope,
        sessionParams: SessionParams,
        block: ((ManaWellKnown?) -> Unit)
) = with(coroutineScope) {
    launch(Dispatchers.IO) {
        block(getManaWellknown(sessionParams))
    }
}

fun ManaWellKnown.isSecureBackupRequired() = manaE2E?.secureBackupRequired
        ?: riotE2E?.secureBackupRequired
        ?: false

fun ManaWellKnown?.secureBackupMethod(): SecureBackupMethod {
    val methodList = this?.manaE2E?.secureBackupSetupMethods
            ?: this?.riotE2E?.secureBackupSetupMethods
            ?: listOf("key", "passphrase")
    return if (methodList.contains("key") && methodList.contains("passphrase")) {
        SecureBackupMethod.KEY_OR_PASSPHRASE
    } else if (methodList.contains("key")) {
        SecureBackupMethod.KEY
    } else if (methodList.contains("passphrase")) {
        SecureBackupMethod.PASSPHRASE
    } else {
        SecureBackupMethod.KEY_OR_PASSPHRASE
    }
}
