/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.members.MembershipService
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.ReadReceipt
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReadReceiptNamesTest {
    private var ambiguous = false
    private val membership: MembershipService = mockk {
        every { isDisplayNameAmbiguous(any()) } answers { ambiguous }
        every { disambiguateDisplayName(any(), any()) } answers { callOriginal() }
    }
    private val session: Session = mockk(relaxed = true) {
        every { roomService().getRoom("!room:example.org")?.membershipService() } returns membership
    }
    private val factory = ReadReceiptsItemFactory(mockk(relaxed = true), session, mockk(relaxed = true))

    @Test
    fun `receipt list names follow shared room disambiguation`() {
        val receipts = listOf(ReadReceipt(RoomMemberSummary(
                membership = Membership.JOIN, userId = "@alice:example.org", displayName = "Alice",
        ), 1L, null))
        fun name() = factory.create("\$message", "!room:example.org", receipts, null, false)!!.readReceipts.single().displayName

        assertEquals("Alice", name())
        ambiguous = true
        assertEquals("Alice (@alice:example.org)", name())
        ambiguous = false
        assertEquals("Alice", name())
    }
}
