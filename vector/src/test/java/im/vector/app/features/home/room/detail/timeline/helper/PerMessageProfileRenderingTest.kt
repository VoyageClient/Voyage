/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class PerMessageProfileRenderingTest {

    @Test
    fun `given no fallback display name then the body is untouched`() {
        "Alice: hello".withoutPerMessageProfileFallback(null) shouldBeEqualTo "Alice: hello"
    }

    @Test
    fun `given a plaintext fallback prefix then it is stripped`() {
        "Alice: hello".withoutPerMessageProfileFallback("Alice") shouldBeEqualTo "hello"
    }

    @Test
    fun `given a plaintext fallback for another name then the body is untouched`() {
        "Alice: hello".withoutPerMessageProfileFallback("Bob") shouldBeEqualTo "Alice: hello"
    }

    @Test
    fun `given a mid-body name mention then it is not stripped`() {
        "say Alice: hello".withoutPerMessageProfileFallback("Alice") shouldBeEqualTo "say Alice: hello"
    }

    @Test
    fun `given an html fallback then the marker element is stripped`() {
        """<strong data-mx-profile-fallback>Alice: </strong>hello"""
                .withoutPerMessageProfileFallback("Alice") shouldBeEqualTo "hello"
    }

    @Test
    fun `given an html fallback with an empty attribute value then the marker element is stripped`() {
        """<strong data-mx-profile-fallback="">Alice: </strong >hello"""
                .withoutPerMessageProfileFallback("Alice") shouldBeEqualTo "hello"
    }
}
