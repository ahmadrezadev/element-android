/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.Test

class OpenVpnTunnelManagerConfigTest {

    @Test
    fun `sanitizeUnsupportedAndroidOptions removes OpenVPN3-unsupported directives`() {
        val config = """
            client
            user nobody
            group nogroup
            persist-tun
            persist-key
            pull
            connect-retry 1
            auth-user-pass
        """.trimIndent()

        val (sanitized, removedDirectives) = sanitizeUnsupportedAndroidOptions(config)

        sanitized.contains("pull") shouldBeEqualTo false
        sanitized.contains("connect-retry 1") shouldBeEqualTo false
        sanitized shouldContain "auth-user-pass"
        removedDirectives shouldBeEqualTo listOf(
                "user nobody",
                "group nogroup",
                "persist-tun",
                "persist-key",
                "pull",
                "connect-retry 1"
        )
    }

    @Test
    fun `containsEncryptedPrivateKey returns false for unencrypted private key`() {
        val config = """
            <key>
            -----BEGIN PRIVATE KEY-----
            abc
            -----END PRIVATE KEY-----
            </key>
        """.trimIndent()

        containsEncryptedPrivateKey(config) shouldBeEqualTo false
    }

    @Test
    fun `containsEncryptedPrivateKey returns true for encrypted private key`() {
        val config = """
            <key>
            -----BEGIN ENCRYPTED PRIVATE KEY-----
            abc
            -----END ENCRYPTED PRIVATE KEY-----
            </key>
        """.trimIndent()

        containsEncryptedPrivateKey(config) shouldBeEqualTo true
    }

    @Test
    fun `resolvePrivateKeyPasswordForService ignores password for unencrypted key`() {
        val config = """
            <key>
            -----BEGIN PRIVATE KEY-----
            abc
            -----END PRIVATE KEY-----
            </key>
        """.trimIndent()

        resolvePrivateKeyPasswordForService(config, "123456") shouldBeEqualTo null
    }

    @Test
    fun `resolvePrivateKeyPasswordForService returns password for encrypted key`() {
        val config = """
            <key>
            -----BEGIN ENCRYPTED PRIVATE KEY-----
            abc
            -----END ENCRYPTED PRIVATE KEY-----
            </key>
        """.trimIndent()

        resolvePrivateKeyPasswordForService(config, "123456") shouldBeEqualTo "123456"
    }
}
