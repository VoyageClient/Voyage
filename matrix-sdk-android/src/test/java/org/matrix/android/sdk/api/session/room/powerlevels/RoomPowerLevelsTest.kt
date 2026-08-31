/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.powerlevels

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContent
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContentWithSender

private const val A_CREATOR = "@creator:example.org"
private const val AN_ADDITIONAL_CREATOR = "@owner:example.org"
private const val A_USER = "@user:example.org"

class RoomPowerLevelsTest {

    private fun aRoomPowerLevels(roomVersion: String?) = RoomPowerLevels(
            powerLevelsContent = PowerLevelsContent(users = mapOf(A_USER to 100, A_CREATOR to 150)),
            roomCreateContent = RoomCreateContentWithSender(
                    senderId = A_CREATOR,
                    inner = RoomCreateContent(roomVersion = roomVersion, additionalCreators = listOf(AN_ADDITIONAL_CREATOR))
            )
    )

    @Test
    fun `given a room version 12, when getting roles, then creators are owners`() {
        val powerLevels = aRoomPowerLevels("12")

        powerLevels.getUserPowerLevel(A_CREATOR) shouldBeEqualTo UserPowerLevel.Infinite
        powerLevels.getUserPowerLevel(AN_ADDITIONAL_CREATOR) shouldBeEqualTo UserPowerLevel.Infinite
        powerLevels.getSuggestedRole(A_CREATOR) shouldBeEqualTo Role.Creator
        powerLevels.getSuggestedRole(A_USER) shouldBeEqualTo Role.Admin
    }

    @Test
    fun `given a room version 11, when getting roles, then there is no owner`() {
        val powerLevels = aRoomPowerLevels("11")

        powerLevels.getUserPowerLevel(A_CREATOR) shouldBeEqualTo UserPowerLevel.Value(150)
        powerLevels.getSuggestedRole(A_CREATOR) shouldBeEqualTo Role.Admin
        powerLevels.getSuggestedRole(AN_ADDITIONAL_CREATOR) shouldBeEqualTo Role.User
    }
}
