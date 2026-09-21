/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.text.Spanned
import android.widget.TextView
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.settings.VectorPreferences
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.user.model.User
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A mention sent as an `<a href>` renders as a pill wherever it is shown. A biography has no room
 * context, so [PillsPostProcessor] is the only thing pilling there — what the timeline's
 * [EventTextRenderer] pass would otherwise cover up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MentionPillRenderingTest {

    private val roomId = "!roomid:example.org"
    private val knownUser = "@alice:example.org"
    private val strangerUser = "@stranger:example.org"

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private val session = mockk<Session>(relaxed = true).also { session ->
        every { session.permalinkService().isPermalinkSupported(any(), any()) } answers {
            val url = secondArg<String>()
            url.startsWith("https://matrix.to/#/") || url.startsWith("matrix:", ignoreCase = true)
        }
        every { session.userService().getUser(any()) } answers {
            firstArg<String>().takeIf { it == knownUser }?.let { User(it, displayName = "Alice") }
        }
        every { session.roomService().getRoomSummary(any()) } returns null
        every { session.roomService().getRoomMember(any(), any()) } returns null
    }

    private val sessionHolder = mockk<ActiveSessionHolder>(relaxed = true).also {
        every { it.getActiveSession() } returns session
        every { it.getSafeActiveSession() } returns session
    }

    private val renderer = EventHtmlRenderer(
            MatrixHtmlPluginConfigure(ColorProvider(context), context.resources, sessionHolder),
            context,
            sessionHolder,
            mockk<VectorPreferences>(relaxed = true),
    )
    private val avatarRenderer = mockk<AvatarRenderer>(relaxed = true)

    @Test
    fun `given an anchor mention, when rendering in a biography, then it is a pill`() {
        val cases = listOf(
                """<a href="https://matrix.to/#/$knownUser">Alice</a>""",
                """<a href="matrix:u/alice:example.org">Alice</a>""",
                """<b>hi</b><a href="https://matrix.to/#/$knownUser">Alice</a>""",
                """<a href="https://matrix.to/#/$knownUser">Alice</a><br/>hi""",
                // Nobody we know: the pill is drawn from the text the mention was written as.
                """<a href="https://matrix.to/#/$strangerUser">Stranger</a>""",
                """<a href="matrix:u/stranger:example.org">Stranger</a>""",
                """<a href="matrix:roomid/roomid:example.org">The Room</a>""",
        )
        cases.forEach { html ->
            renderAsBio(html).pillNames() shouldBeEqualTo listOf(html.linkText())
        }
    }

    @Test
    fun `given a link whose text is its own url, when rendering, then it stays a link`() {
        val url = "https://matrix.to/#/$strangerUser"
        renderAsBio("""<a href="$url">$url</a>""").pillNames() shouldBeEqualTo emptyList()
    }

    @Test
    fun `given an event permalink, when rendering in a biography, then it stays a link`() {
        val url = "https://matrix.to/#/$roomId/\$event"
        renderAsBio("""<a href="$url">a message</a>""").pillNames() shouldBeEqualTo emptyList()
    }

    private fun renderAsBio(html: String): Spanned {
        val pills = PillsPostProcessor(null, context, avatarRenderer, sessionHolder, mockk(relaxed = true))
        val textRenderer = EventTextRenderer(null, context, avatarRenderer, sessionHolder, mockk(relaxed = true))
        val rendered = renderer.render(html, pills) as Spanned
        val textView = TextView(context)
        renderer.setTextWithPlugins(textView, textRenderer.render(rendered).linkify(null))
        return textView.text as Spanned
    }

    private fun Spanned.pillNames() = getSpans(0, length, PillImageSpan::class.java)
            .map { it.matrixItem.getBestName() }

    private fun String.linkText() = substringAfter("\">").substringBefore("</a>")
}
