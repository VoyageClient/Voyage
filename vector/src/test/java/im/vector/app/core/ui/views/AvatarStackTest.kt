/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class AvatarStackTest {

    @Test
    fun `up to three people all get an avatar`() {
        (0..3).forEach { total ->
            avatarStackFaces(total) shouldBeEqualTo total
            avatarStackOverflow(total) shouldBeEqualTo 0
        }
    }

    @Test
    fun `more than three people show two avatars and count the rest`() {
        avatarStackFaces(4) shouldBeEqualTo 2
        avatarStackOverflow(4) shouldBeEqualTo 2
        avatarStackFaces(10) shouldBeEqualTo 2
        avatarStackOverflow(10) shouldBeEqualTo 8
    }
}
