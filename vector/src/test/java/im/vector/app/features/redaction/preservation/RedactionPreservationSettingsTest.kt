/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.test.fakes.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.admin.ServerAdminStatus

private const val A_ROOM_ID = "!room:example.org"
private const val ANOTHER_ROOM_ID = "!other:example.org"
private const val A_USER_ID = "@alice:example.org"
private const val ANOTHER_USER_ID = "@bob:example.org"

class RedactionPreservationSettingsTest {

    private val preferences = InMemorySharedPreferences()
    private var currentUserId = A_USER_ID

    private val session = mockk<Session> { every { myUserId } answers { currentUserId } }
    private val activeSessionHolder = mockk<ActiveSessionHolder> { every { getSafeActiveSession() } returns session }

    private val settings = RedactionPreservationSettings(
            preferences = preferences,
            activeSessionHolder = { activeSessionHolder },
    )

    @Test
    fun `given nothing configured, then the defaults apply`() {
        settings.globalPreserveRedacted shouldBe false
        settings.globalPreserveMedia shouldBe true
        settings.globalWifiOnly shouldBe true
        settings.clearRedactionCacheWithAppCache shouldBe true
    }

    @Test
    fun `given no room override, then the room inherits the account value`() {
        settings.globalPreserveRedacted = true

        settings.roomPreserveRedactedOverride(A_ROOM_ID) shouldBe null
        settings.preserveRedactedFor(A_ROOM_ID) shouldBe true
    }

    @Test
    fun `given a room override, then it wins over the account value`() {
        settings.globalPreserveRedacted = true
        settings.setRoomPreserveRedactedOverride(A_ROOM_ID, false)

        settings.preserveRedactedFor(A_ROOM_ID) shouldBe false
        settings.preserveRedactedFor(ANOTHER_ROOM_ID) shouldBe true
    }

    @Test
    fun `given a room override is cleared, then the room inherits again`() {
        settings.globalPreserveRedacted = true
        settings.setRoomPreserveRedactedOverride(A_ROOM_ID, false)

        settings.setRoomPreserveRedactedOverride(A_ROOM_ID, null)

        settings.preserveRedactedFor(A_ROOM_ID) shouldBe true
    }

    @Test
    fun `given a room media override, then it wins over the account value and can be cleared`() {
        settings.globalPreserveMedia = false

        settings.preserveMediaFor(A_ROOM_ID) shouldBe false

        settings.setRoomPreserveMediaOverride(A_ROOM_ID, true)
        settings.preserveMediaFor(A_ROOM_ID) shouldBe true
        settings.preserveMediaFor(ANOTHER_ROOM_ID) shouldBe false

        settings.setRoomPreserveMediaOverride(A_ROOM_ID, null)
        settings.roomPreserveMediaOverride(A_ROOM_ID) shouldBe null
        settings.preserveMediaFor(A_ROOM_ID) shouldBe false
    }

    /**
     * The preserved event data has no clear button of its own, so this override is the only way to keep
     * one room's content through an app-cache clear.
     */
    @Test
    fun `given no room override, then clearing with the app cache follows the account setting`() {
        settings.roomClearWithAppCacheOverride(A_ROOM_ID) shouldBe null
        settings.clearsWithAppCache(A_ROOM_ID) shouldBe true

        settings.clearRedactionCacheWithAppCache = false

        settings.clearsWithAppCache(A_ROOM_ID) shouldBe false
    }

    @Test
    fun `given a room override, then it wins over the account clear-with-app-cache setting`() {
        settings.clearRedactionCacheWithAppCache = true

        settings.setRoomClearWithAppCacheOverride(A_ROOM_ID, false)
        settings.clearsWithAppCache(A_ROOM_ID) shouldBe false
        settings.clearsWithAppCache(ANOTHER_ROOM_ID) shouldBe true

        settings.setRoomClearWithAppCacheOverride(A_ROOM_ID, null)
        settings.roomClearWithAppCacheOverride(A_ROOM_ID) shouldBe null
        settings.clearsWithAppCache(A_ROOM_ID) shouldBe true
    }

    @Test
    fun `given the account opts out, then a room can still opt back in`() {
        settings.clearRedactionCacheWithAppCache = false

        settings.setRoomClearWithAppCacheOverride(A_ROOM_ID, true)

        settings.clearsWithAppCache(A_ROOM_ID) shouldBe true
        settings.clearsWithAppCache(ANOTHER_ROOM_ID) shouldBe false
    }

    @Test
    fun `given per-account scoping, then one account's room override does not leak into another`() {
        settings.setRoomClearWithAppCacheOverride(A_ROOM_ID, false)

        currentUserId = ANOTHER_USER_ID

        settings.roomClearWithAppCacheOverride(A_ROOM_ID) shouldBe null
        settings.clearsWithAppCache(A_ROOM_ID) shouldBe true
    }

    @Test
    fun `given the app cache is cleared, then the redaction cache follows it by default`() {
        settings.clearRedactionCacheWithAppCache shouldBe true

        settings.clearRedactionCacheWithAppCache = false

        settings.clearRedactionCacheWithAppCache shouldBe false
    }

    /** Settings are per-account, so signing in as someone else must not inherit the previous choices. */
    @Test
    fun `given another account, then it does not see the first account's settings`() {
        settings.globalPreserveRedacted = true
        settings.setRoomPreserveRedactedOverride(A_ROOM_ID, true)

        currentUserId = ANOTHER_USER_ID

        settings.globalPreserveRedacted shouldBe false
        settings.preserveRedactedFor(A_ROOM_ID) shouldBe false
    }

    /**
     * MSC2815 is not Synapse-only, but the admin probe is, so a server that can't answer must leave the
     * ability open to the capability flag rather than being read as a refusal.
     */
    @Test
    fun `given an unresolved admin probe, then admin ability is not ruled out`() {
        ServerAdminStatus.YES.mayBeAdmin shouldBe true
        ServerAdminStatus.UNKNOWN.mayBeAdmin shouldBe true
        ServerAdminStatus.NO.mayBeAdmin shouldBe false

        // isAdmin stays strict: it answers "are they", not "might they be".
        ServerAdminStatus.YES.isAdmin shouldBe true
        ServerAdminStatus.UNKNOWN.isAdmin shouldBe false
        ServerAdminStatus.NO.isAdmin shouldBe false
    }

    /** Only a definitive answer is worth remembering; a network blip must not write an event off. */
    @Test
    fun `given a failure reason, then only network failures are retryable`() {
        RevealFailure.NETWORK.isPermanent shouldBe false
        RevealFailure.CONTENT_DELETED.isPermanent shouldBe true
        RevealFailure.CONTENT_NOT_RECEIVED.isPermanent shouldBe true
        RevealFailure.FORBIDDEN.isPermanent shouldBe true
        RevealFailure.UNSUPPORTED.isPermanent shouldBe true
    }
}
