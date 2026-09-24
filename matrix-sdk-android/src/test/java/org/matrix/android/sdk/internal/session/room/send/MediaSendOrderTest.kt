/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class MediaSendOrderTest {

    private val order = MediaSendOrder()

    @Test
    fun `each send waits for the earlier undispatched media of its room`() {
        order.enqueue(listOf(LocalEchoIdentifiers("!a", "\$1"))) shouldBeEqualTo emptyList()
        order.enqueue(listOf(LocalEchoIdentifiers("!a", "\$2"))) shouldBeEqualTo listOf("\$1")
        order.enqueue(listOf(LocalEchoIdentifiers("!a", "\$3"))) shouldBeEqualTo listOf("\$1", "\$2")
    }

    @Test
    fun `media in another room is not waited for`() {
        order.enqueue(listOf(LocalEchoIdentifiers("!a", "\$1")))
        order.enqueue(listOf(LocalEchoIdentifiers("!b", "\$2"))) shouldBeEqualTo emptyList()
    }

    @Test
    fun `a multi-room send waits for each of its rooms`() {
        order.enqueue(listOf(LocalEchoIdentifiers("!a", "\$1")))
        order.enqueue(listOf(LocalEchoIdentifiers("!b", "\$2")))
        order.enqueue(listOf(LocalEchoIdentifiers("!a", "\$3"), LocalEchoIdentifiers("!b", "\$4"))) shouldBeEqualTo listOf("\$1", "\$2")
    }

    @Test
    fun `dispatched media is no longer waited for`() {
        order.enqueue(listOf(LocalEchoIdentifiers("!a", "\$1")))
        order.enqueue(listOf(LocalEchoIdentifiers("!a", "\$2")))
        order.isUndispatched("\$1") shouldBeEqualTo true
        order.markDispatched(listOf("\$1"))
        order.isUndispatched("\$1") shouldBeEqualTo false
        order.enqueue(listOf(LocalEchoIdentifiers("!a", "\$3"))) shouldBeEqualTo listOf("\$2")
    }
}
