/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.federation

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class ServerNameTest {

    @Test
    fun `explicit ports are detected on hostnames and IPv6 literals`() {
        ServerName.hasExplicitPort("matrix.org") shouldBeEqualTo false
        ServerName.hasExplicitPort("matrix.org:8448") shouldBeEqualTo true
        ServerName.hasExplicitPort("1.2.3.4:443") shouldBeEqualTo true
        ServerName.hasExplicitPort("[::1]") shouldBeEqualTo false
        ServerName.hasExplicitPort("[2001:db8::1]:8448") shouldBeEqualTo true
    }

    @Test
    fun `host drops only the port`() {
        ServerName.hostOf("matrix.org") shouldBeEqualTo "matrix.org"
        ServerName.hostOf("matrix.org:8448") shouldBeEqualTo "matrix.org"
        ServerName.hostOf("[2001:db8::1]") shouldBeEqualTo "[2001:db8::1]"
        ServerName.hostOf("[2001:db8::1]:8448") shouldBeEqualTo "[2001:db8::1]"
    }

    @Test
    fun `IP literals are recognized`() {
        ServerName.isIpLiteral("1.2.3.4") shouldBeEqualTo true
        ServerName.isIpLiteral("[2001:db8::1]") shouldBeEqualTo true
        ServerName.isIpLiteral("matrix.example.org") shouldBeEqualTo false
    }
}
