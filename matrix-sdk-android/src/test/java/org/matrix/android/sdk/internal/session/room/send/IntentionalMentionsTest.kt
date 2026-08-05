/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class IntentionalMentionsTest {

    @Test
    fun `pills in the formatted body are mentioned, alongside the replied-to sender`() {
        val mentions = IntentionalMentions.build(
                body = "hey",
                formattedBody = """<a href="https://matrix.to/#/@alice:example.org">Alice</a> """ +
                        """<a href="https://matrix.to/#/@bob:example.org">Bob</a>""",
                extraUserIds = listOf("@carol:example.org"),
                selfUserId = "@me:example.org",
        )

        mentions?.userIds shouldBeEqualTo listOf("@carol:example.org", "@alice:example.org", "@bob:example.org")
        mentions?.room shouldBeEqualTo null
    }

    @Test
    fun `the sender is never mentioned by their own message`() {
        val mentions = IntentionalMentions.build(
                body = "hi me",
                formattedBody = """<a href="https://matrix.to/#/@me:example.org">me</a>""",
                selfUserId = "@me:example.org",
        )

        mentions shouldBeEqualTo null
    }

    @Test
    fun `matrix uri pills are mentioned`() {
        val mentions = IntentionalMentions.build(body = "hi", formattedBody = """<a href="matrix:u/alice:example.org">Alice</a>""")

        mentions?.userIds shouldBeEqualTo listOf("@alice:example.org")
    }

    @Test
    fun `room mention is detected in the plain body`() {
        IntentionalMentions.build(body = "@room heads up", formattedBody = null)?.room shouldBeEqualTo true
        IntentionalMentions.build(body = "mail me at a@roomservice.org", formattedBody = null) shouldBeEqualTo null
    }

    @Test
    fun `quoted content does not mention`() {
        val mentions = IntentionalMentions.build(
                body = "> <@alice:example.org> @room ping\n\nagreed",
                formattedBody = """<blockquote><a href="https://matrix.to/#/@alice:example.org">Alice</a> @room</blockquote>agreed""",
        )

        mentions shouldBeEqualTo null
    }

    @Test
    fun `plain links and room permalinks are not mentions`() {
        val mentions = IntentionalMentions.build(
                body = "see this",
                formattedBody = """<a href="https://example.org">link</a> <a href="https://matrix.to/#/!room:example.org">room</a>""",
        )

        mentions shouldBeEqualTo null
    }
}
