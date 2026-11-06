/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class RoomAvatarBadgesTest {

    private val defaults = RoomBadgeConfig(showEncryption = true, showPublic = true)

    @Test
    fun `shield shows on encrypted rooms`() {
        shouldShowRoomShield(defaults, hasShield = true, publicShown = false) shouldBeEqualTo true
    }

    @Test
    fun `no shield without encryption`() {
        shouldShowRoomShield(defaults, hasShield = false, publicShown = false) shouldBeEqualTo false
    }

    @Test
    fun `disabled shield stays hidden`() {
        shouldShowRoomShield(defaults.copy(showEncryption = false), hasShield = true, publicShown = false) shouldBeEqualTo false
    }

    @Test
    fun `globe hides the shield`() {
        shouldShowRoomShield(defaults, hasShield = true, publicShown = true) shouldBeEqualTo false
    }
}
