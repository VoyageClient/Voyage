/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.createroom

import im.vector.app.test.fixtures.aHomeServerCapabilities
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.homeserver.RoomVersionCapabilities
import org.matrix.android.sdk.api.session.homeserver.RoomVersionInfo
import org.matrix.android.sdk.api.session.homeserver.RoomVersionStatus

class CreatableRoomVersionsTest {

    private fun capabilities(default: String, available: Map<String, RoomVersionStatus>) = aHomeServerCapabilities(
            roomVersions = RoomVersionCapabilities(
                    defaultRoomVersion = default,
                    supportedVersion = available.map { RoomVersionInfo(it.key, it.value) },
                    capabilities = null
            )
    )

    @Test
    fun `stable versions are listed as a ladder up to the highest supported one`() {
        val versions = capabilities(
                default = "10",
                available = mapOf("1" to RoomVersionStatus.STABLE, "10" to RoomVersionStatus.STABLE, "11" to RoomVersionStatus.STABLE)
        ).creatableRoomVersions()

        versions shouldBeEqualTo (1..11).map { CreatableRoomVersion(it.toString(), stable = true) }
    }

    @Test
    fun `unstable versions are appended after the stable ladder`() {
        val versions = capabilities(
                default = "11",
                available = mapOf(
                        "11" to RoomVersionStatus.STABLE,
                        "org.matrix.msc4014" to RoomVersionStatus.UNSTABLE,
                        "12" to RoomVersionStatus.UNSTABLE
                )
        ).creatableRoomVersions()

        versions shouldBeEqualTo (1..11).map { CreatableRoomVersion(it.toString(), stable = true) } +
                listOf(
                        CreatableRoomVersion("12", stable = false),
                        CreatableRoomVersion("org.matrix.msc4014", stable = false)
                )
    }

    @Test
    fun `an unstable version already covered by the stable ladder is not repeated`() {
        val versions = capabilities(
                default = "5",
                available = mapOf("5" to RoomVersionStatus.STABLE, "3" to RoomVersionStatus.UNSTABLE)
        ).creatableRoomVersions()

        versions shouldBeEqualTo (1..5).map { CreatableRoomVersion(it.toString(), stable = true) }
    }

    @Test
    fun `version 1 is offered when the server advertises nothing`() {
        aHomeServerCapabilities().creatableRoomVersions() shouldBeEqualTo listOf(CreatableRoomVersion("1", stable = true))
    }
}
