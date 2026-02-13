/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.crypto.verification

import im.vector.app.core.hardware.HardwareInfo
import org.matrix.android.sdk.api.session.crypto.verification.VerificationMethod
import timber.log.Timber
import javax.inject.Inject

class SupportedVerificationMethodsProvider @Inject constructor(
        private val hardwareInfo: HardwareInfo
) {
    /**
     * Provide the list of supported method by Mana, with or without the QR_CODE_SCAN, depending if a back camera
     * is available.
     */
    fun provide(): List<VerificationMethod> {
        return mutableListOf(
                // Mana supports SAS verification
                VerificationMethod.SAS,
                // Mana is able to show QR codes
                VerificationMethod.QR_CODE_SHOW
        )
                .apply {
                    if (hardwareInfo.hasBackCamera()) {
                        // Mana is able to scan QR codes, and a Camera is available
                        add(VerificationMethod.QR_CODE_SCAN)
                    } else {
                        // This quite uncommon
                        Timber.w("No back Camera detected")
                    }
                }
    }
}
