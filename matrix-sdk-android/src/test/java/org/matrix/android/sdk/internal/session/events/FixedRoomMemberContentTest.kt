/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.events

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType

class FixedRoomMemberContentTest {

    private fun memberEvent(content: Map<String, Any>, prevContent: Map<String, Any>? = null) = Event(
            type = EventType.STATE_ROOM_MEMBER,
            eventId = "\$event",
            stateKey = "@alice:example.org",
            content = content,
            prevContent = prevContent,
    )

    @Test
    fun `knock keeps the knocker's own profile`() {
        val content = memberEvent(mapOf("membership" to "knock", "displayname" to "Alice", "avatar_url" to "mxc://example.org/a"))
                .getFixedRoomMemberContent()

        content?.displayName shouldBeEqualTo "Alice"
        content?.avatarUrl shouldBeEqualTo "mxc://example.org/a"
    }

    @Test
    fun `leave takes the profile from the previous membership`() {
        val content = memberEvent(
                content = mapOf("membership" to "leave"),
                prevContent = mapOf("membership" to "join", "displayname" to "Alice", "avatar_url" to "mxc://example.org/a"),
        ).getFixedRoomMemberContent()

        content?.displayName shouldBeEqualTo "Alice"
        content?.avatarUrl shouldBeEqualTo "mxc://example.org/a"
    }
}
