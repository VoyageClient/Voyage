/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

import im.vector.app.features.home.room.detail.timeline.selection.BulkCopyFormatter.Entry
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class BulkCopyFormatterTest {

    private val me = "@me:example.org"
    private val alice = "@alice:example.org"

    @Test
    fun `names are written only when the sender changes`() {
        val text = BulkCopyFormatter.format(
                listOf(
                        Entry(alice, "Alice", "hi"),
                        Entry(alice, "Alice", "you there?"),
                        Entry(me, "Me", "yes"),
                        Entry(alice, "Alice", "great"),
                ),
                me,
        )

        text shouldBeEqualTo "Alice:\nhi\n\nyou there?\n\nMe:\nyes\n\nAlice:\ngreat"
    }

    @Test
    fun `no names when every message is mine`() {
        BulkCopyFormatter.format(listOf(Entry(me, "Me", "one"), Entry(me, "Me", "two")), me) shouldBeEqualTo "one\n\ntwo"
    }

    @Test
    fun `no name for a single message`() {
        BulkCopyFormatter.format(listOf(Entry(alice, "Alice", "hi")), me) shouldBeEqualTo "hi"
    }

    @Test
    fun `a sender shown under another name starts a new header`() {
        BulkCopyFormatter.format(listOf(Entry(alice, "Alice", "a"), Entry(alice, "Persona (@alice:example.org)", "b")), me) shouldBeEqualTo
                "Alice:\na\n\nPersona (@alice:example.org):\nb"
    }
}
