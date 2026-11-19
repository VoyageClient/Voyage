/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.read

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

private const val ROOM_ID = "!room:example.org"

class PendingReadReceiptMergeTest {

    @Test
    fun `nothing pending keeps the new read as is`() {
        val read = receipt("\$b")

        read.mergedOnto(null) shouldBeEqualTo read
    }

    @Test
    fun `a receipt queued after a marker keeps the marker`() {
        val merged = receipt("\$b").mergedOnto(marker("\$a"))

        merged.fullyReadEventId shouldBeEqualTo "\$a"
        merged.readReceiptEventId shouldBeEqualTo "\$b"
    }

    @Test
    fun `a marker queued after a receipt keeps the receipt with its type and thread`() {
        val merged = marker("\$b").mergedOnto(receipt("\$a", type = "m.read", threadId = "main"))

        merged.fullyReadEventId shouldBeEqualTo "\$b"
        merged.readReceiptEventId shouldBeEqualTo "\$a"
        merged.readReceiptType shouldBeEqualTo "m.read"
        merged.threadId shouldBeEqualTo "main"
    }

    @Test
    fun `newer halves replace older ones`() {
        val older = PendingReadReceipt(ROOM_ID, "\$a", "\$a", "m.read", "main")
        val newer = PendingReadReceipt(ROOM_ID, "\$b", "\$b", "m.read.private", null)

        newer.mergedOnto(older) shouldBeEqualTo newer
    }

    private fun marker(eventId: String) =
            PendingReadReceipt(ROOM_ID, fullyReadEventId = eventId, readReceiptType = "m.read.private")

    private fun receipt(eventId: String, type: String = "m.read.private", threadId: String? = null) =
            PendingReadReceipt(ROOM_ID, readReceiptEventId = eventId, readReceiptType = type, threadId = threadId)
}
