/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.crosssigning

import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType

class HasCompleteMemberListTest {

    @Test
    fun `a fetched member list is complete whatever the counts say`() {
        hasCompleteMemberList(RoomMembersLoadStatusType.LOADED, 2, 40, 0) shouldBe true
    }

    @Test
    fun `a DM whose both members are known is complete before it is opened`() {
        hasCompleteMemberList(RoomMembersLoadStatusType.NONE, 2, 2, 0) shouldBe true
    }

    @Test
    fun `invited members count towards the total`() {
        hasCompleteMemberList(RoomMembersLoadStatusType.NONE, 3, 2, 1) shouldBe true
    }

    @Test
    fun `members who have not spoken lately leave the list incomplete`() {
        hasCompleteMemberList(RoomMembersLoadStatusType.NONE, 4, 260, 1) shouldBe false
    }

    @Test
    fun `without server counts the list is never assumed complete`() {
        hasCompleteMemberList(RoomMembersLoadStatusType.NONE, 2, null, null) shouldBe false
    }

    @Test
    fun `an empty member list never matches`() {
        hasCompleteMemberList(RoomMembersLoadStatusType.NONE, 0, 0, 0) shouldBe false
    }
}
