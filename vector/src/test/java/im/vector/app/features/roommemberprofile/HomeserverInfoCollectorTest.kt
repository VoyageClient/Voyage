/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class HomeserverInfoCollectorTest {

    @Test
    fun `explicit ports are detected on hostnames and IPv6 literals`() {
        HomeserverInfoCollector.hasExplicitPort("matrix.org") shouldBeEqualTo false
        HomeserverInfoCollector.hasExplicitPort("matrix.org:8448") shouldBeEqualTo true
        HomeserverInfoCollector.hasExplicitPort("1.2.3.4:443") shouldBeEqualTo true
        HomeserverInfoCollector.hasExplicitPort("[::1]") shouldBeEqualTo false
        HomeserverInfoCollector.hasExplicitPort("[2001:db8::1]:8448") shouldBeEqualTo true
    }

    @Test
    fun `host drops only the port`() {
        HomeserverInfoCollector.hostOf("matrix.org") shouldBeEqualTo "matrix.org"
        HomeserverInfoCollector.hostOf("matrix.org:8448") shouldBeEqualTo "matrix.org"
        HomeserverInfoCollector.hostOf("[2001:db8::1]") shouldBeEqualTo "[2001:db8::1]"
        HomeserverInfoCollector.hostOf("[2001:db8::1]:8448") shouldBeEqualTo "[2001:db8::1]"
    }
}
