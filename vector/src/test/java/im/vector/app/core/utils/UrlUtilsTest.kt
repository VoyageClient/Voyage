/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun `given a web url, when checking it is tappable, then it is`() {
        "https://matrix.to/#/@alice:example.org".isTappableLink() shouldBeEqualTo true
        "https://example.org".isTappableLink() shouldBeEqualTo true
    }

    @Test
    fun `given a matrix uri, when checking it is tappable, then it is`() {
        // Opaque, so not a URL: the click handling must not key off isValidUrl for these.
        "matrix:u/alice:example.org".isValidUrl() shouldBeEqualTo false
        "matrix:u/alice:example.org".isTappableLink() shouldBeEqualTo true
        "MATRIX:r/room:example.org/e/\$abcdef".isTappableLink() shouldBeEqualTo true
    }

    @Test
    fun `given plain text, when checking it is tappable, then it is not`() {
        "not a link".isTappableLink() shouldBeEqualTo false
        "".isTappableLink() shouldBeEqualTo false
    }
}
