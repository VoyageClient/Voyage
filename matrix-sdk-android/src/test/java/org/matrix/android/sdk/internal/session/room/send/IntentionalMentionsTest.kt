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

    @Test
    fun `explicit links mention nobody, whichever prefix and value they carry`() {
        listOf("data-mx-link", "data-org.matrix.msc4550.link", "data-mx-link=\"\"", "data-org.matrix.msc4550.link=\"true\"").forEach { marker ->
            IntentionalMentions.build(
                    body = "[DM me](https://matrix.to/#/@alice:example.org)",
                    formattedBody = """<a href="https://matrix.to/#/@alice:example.org" $marker>DM me</a>""",
            ) shouldBeEqualTo null
        }
    }

    @Test
    fun `an explicit link beside a pill of the same user still mentions them`() {
        val mentions = IntentionalMentions.build(
                body = "x",
                formattedBody = """<a href="https://matrix.to/#/@alice:example.org" data-mx-link>DM me</a> """ +
                        """<a href="https://matrix.to/#/@alice:example.org">Alice</a>""",
        )

        mentions?.userIds shouldBeEqualTo listOf("@alice:example.org")
    }

    @Test
    fun `@room inside an explicit link label does not notify the room`() {
        IntentionalMentions.build(
                body = "[ask **@room**](https://example.org)",
                formattedBody = """<a href="https://example.org" data-mx-link data-org.matrix.msc4550.link>ask <strong>@room</strong></a>""",
        ) shouldBeEqualTo null
        IntentionalMentions.build(
                body = "[ask](https://example.org) @room",
                formattedBody = """<a href="https://example.org" data-mx-link>ask</a> @room""",
        )?.room shouldBeEqualTo true
    }
}
