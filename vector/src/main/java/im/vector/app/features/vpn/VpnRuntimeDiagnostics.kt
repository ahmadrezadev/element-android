/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import android.content.Context
import java.io.File

internal object VpnRuntimeDiagnostics {

    fun clearLastFailure(context: Context) {
        writeLastFailure(context, null)
    }

    fun writeLastFailure(context: Context, reason: String?) {
        val file = diagnosticsFile(context)
        runCatching {
            val normalized = reason
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(MAX_REASON_CHARS)
                    .orEmpty()
            file.parentFile?.mkdirs()
            file.writeText(normalized)
        }
    }

    fun readLastFailure(context: Context): String? {
        val file = diagnosticsFile(context)
        return runCatching {
            file.takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun diagnosticsFile(context: Context): File {
        val dir = File(context.noBackupFilesDir, DIAGNOSTICS_DIR)
        return File(dir, LAST_FAILURE_FILE)
    }

    private const val DIAGNOSTICS_DIR = "vpn-runtime"
    private const val LAST_FAILURE_FILE = "last_failure.txt"
    private const val MAX_REASON_CHARS = 800
}
