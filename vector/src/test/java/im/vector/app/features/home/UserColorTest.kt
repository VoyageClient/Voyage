/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import im.vector.app.core.ui.colorpicker.PeopleColorPalette
import im.vector.app.core.ui.colorpicker.RoomColorPalette
import im.vector.lib.ui.styles.R
import org.junit.Assert.assertEquals
import org.junit.Test

class UserColorTest {

    private fun legacyName(userId: String?) = PeopleColorPalette.LEGACY.colorFor(userId, true)

    @Test
    fun testNull() {
        assertEquals(R.color.element_name_01, legacyName(null))
    }

    @Test
    fun testEmpty() {
        assertEquals(R.color.element_name_01, legacyName(""))
    }

    @Test
    fun testName() {
        assertEquals(R.color.element_name_01, legacyName("@ganfra:matrix.org"))
        assertEquals(R.color.element_name_04, legacyName("@benoit0816:matrix.org"))
        assertEquals(R.color.element_name_05, legacyName("@hubert:uhoreg.ca"))
        assertEquals(R.color.element_name_07, legacyName("@nadonomy:matrix.org"))
    }

    @Test
    fun testModernMatchesElementWebHash() {
        // element-web's useIdColorHash: sum of char codes, modulo 6.
        val userId = "@alice:example.org"
        assertEquals(userId.sumOf { it.code } % 6, PeopleColorPalette.MODERN.indexOf(userId))
        assertEquals(PeopleColorPalette.MODERN.indexOf(userId), RoomColorPalette.MODERN.indexOf(userId))
    }

    @Test
    fun testRoomPalettesUseCharCodeSum() {
        val roomId = "!AbCdEf:matrix.org"
        assertEquals(roomId.sumOf { it.code } % 3, RoomColorPalette.LEGACY.indexOf(roomId))
        assertEquals(roomId.sumOf { it.code } % 3, RoomColorPalette.RIOT_ALPHA.indexOf(roomId))
    }
}
