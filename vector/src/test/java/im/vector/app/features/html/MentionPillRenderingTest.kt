/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.widget.TextView
import com.bumptech.glide.request.target.Target
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.glide.GlideRequests
import im.vector.app.core.resources.ColorProvider
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.format.DisplayableEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.ForwardedMessagePresenter
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.home.room.detail.timeline.render.RoomMentionSource
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.pgp.PgpDecryptor
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.translation.MessageTranslationStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.MatrixItem
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
            noCodePalette(),
    )
    private val avatarRenderer = mockk<AvatarRenderer>(relaxed = true)
    private val resolver = mockk<PillItemResolver>(relaxed = true)

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

    @Test
    fun `event and room permalink pills fit a room list preview`() {
        val urls = listOf(
                "https://matrix.to/#/!elsewhere:example.org/\$event",
                "https://matrix.to/#/!elsewhere:example.org",
        )
        urls.forEach { url ->
            val rendered = render("""<a href="$url">A long room name</a>""", roomId)
            val pill = rendered.getSpans(0, rendered.length, PillImageSpan::class.java).single()
            val start = rendered.getSpanStart(pill)
            val end = rendered.getSpanEnd(pill)
            val paint = Paint()
            assertTrue(pill.getSize(paint, rendered, start, end, null) > 48)
            pill.limitWidthTo(48)
            assertEquals(48, pill.getSize(paint, rendered, start, end, null))
        }
    }

    @Test
    fun `a long room pill is capped to the pill max width`() {
        val name = "A very long room name ".repeat(20)
        val rendered = render("""<a href="https://matrix.to/#/!elsewhere:example.org">$name</a>""", roomId)
        val pill = rendered.getSpans(0, rendered.length, PillImageSpan::class.java).single()
        val width = pill.getSize(Paint(), rendered, rendered.getSpanStart(pill), rendered.getSpanEnd(pill), null)
        val cap = minOf(
                context.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.pill_max_width),
                (context.resources.displayMetrics.widthPixels * 0.7f).toInt()
        )
        assertTrue(width in cap / 2..cap)
    }

    @Test
    fun `given an explicit link, when rendering, then it stays a link with its own text`() {
        val hrefs = listOf(
                "https://matrix.to/#/$knownUser",
                "https://matrix.to/#/$strangerUser",
                "https://matrix.to/#/#support:example.org",
                "https://matrix.to/#/$roomId/\$event",
                "matrix:u/alice:example.org",
                "matrix:r/support:example.org",
        )
        val markers = listOf("data-mx-link", "data-org.matrix.msc4550.link", "data-mx-link=\"\"", "data-org.matrix.msc4550.link=\"ignored\"")
        hrefs.forEach { href ->
            markers.forEach { marker ->
                listOf(null, roomId).forEach { room ->
                    val rendered = render("""Questions? <a $marker href="$href">DM me</a>""", room)
                    rendered.pillNames() shouldBeEqualTo emptyList()
                    rendered.toString() shouldBeEqualTo "Questions? DM me"
                }
            }
        }
    }

    @Test
    fun `given @room inside an explicit link, when rendering in a room, then only the one outside is a pill`() {
        render("""<a href="https://example.org" data-mx-link>ask <b>@room</b></a>""", roomId).pillNames() shouldBeEqualTo emptyList()
        render("""<a href="https://example.org" data-mx-link>ask @room</a> @room""", roomId).pillNames().size shouldBeEqualTo 1
    }

    @Test
    fun `given a mention in an old message, when rendering in a room, then the pill shows the profile from then`() {
        val mentionedAt = 1_000L
        every { session.roomService().getRoomMember(knownUser, roomId) } returns
                RoomMemberSummary(Membership.JOIN, knownUser, displayName = "Alice Now", avatarUrl = "mxc://example.org/now")
        every { session.roomService().getRoomMemberAt(knownUser, roomId, mentionedAt) } returns
                RoomMemberSummary(Membership.JOIN, knownUser, displayName = "Alice Then", avatarUrl = "mxc://example.org/then")
        val html = """<a href="https://matrix.to/#/$knownUser">Alice</a>"""

        render(html, roomId, mentionedAt).pills().map { it.getBestName() to it.avatarUrl } shouldBeEqualTo
                listOf("Alice Then" to "mxc://example.org/then")
        render(html, roomId, mentionedAt, showLatestProfile = true).pills().map { it.getBestName() to it.avatarUrl } shouldBeEqualTo
                listOf("Alice Now" to "mxc://example.org/now")
    }

    @Test
    fun `given a mention of someone with no name or avatar then, when rendering in a room, then the pill stays without them`() {
        val mentionedAt = 1_000L
        every { session.roomService().getRoomMember(knownUser, roomId) } returns
                RoomMemberSummary(Membership.JOIN, knownUser, displayName = "Alice Now", avatarUrl = "mxc://example.org/now")
        every { session.roomService().getRoomMemberAt(knownUser, roomId, mentionedAt) } returns RoomMemberSummary(Membership.JOIN, knownUser)
        val laterMessage = SenderInfo(knownUser, "Alice Now", true, "mxc://example.org/now")

        val rendered = render("""<a href="https://matrix.to/#/$knownUser">alice</a>""", roomId, mentionedAt, senders = listOf(laterMessage))
        TextView(context).apply {
            text = rendered
            bindPillImageSpans()
        }

        rendered.pills().map { it.getBestName() to it.avatarUrl } shouldBeEqualTo listOf("alice" to null)
        verify(exactly = 0) { resolver.resolve(any(), any()) }
    }

    @Test
    fun `given a plain-text mention of someone with no name or avatar then, when rendering in a room, then the pill is not looked up`() {
        val mentionedAt = 1_000L
        every { session.roomService().getRoomMember(knownUser, roomId) } returns
                RoomMemberSummary(Membership.JOIN, knownUser, displayName = "Alice Now", avatarUrl = "mxc://example.org/now")
        every { session.roomService().getRoomMemberAt(knownUser, roomId, mentionedAt) } returns RoomMemberSummary(Membership.JOIN, knownUser)
        val textRenderer = EventTextRenderer(roomId, context, avatarRenderer, sessionHolder, mockk(relaxed = true), mockk(relaxed = true), resolver)

        val rendered = textRenderer.render("hi https://matrix.to/#/$knownUser", RoomMentionSource(null, false, mentionedAt)) as Spanned
        TextView(context).apply {
            text = rendered
            bindPillImageSpans()
        }

        rendered.pills().map { it.getBestName() to it.avatarUrl } shouldBeEqualTo listOf(knownUser to null)
        verify(exactly = 0) { resolver.resolve(any(), any()) }
    }

    @Test
    fun `given a mention nothing local knows, when rendering in a room, then only that pill is looked up`() {
        val nobody = "@nobody:example.org"
        val html = """<a href="https://matrix.to/#/$knownUser">Alice</a> """ +
                """<a href="https://matrix.to/#/$strangerUser">$strangerUser</a> https://matrix.to/#/$nobody"""
        val rendered = render(html, roomId)
        val textView = TextView(context).apply { text = rendered }
        rendered.getSpans(0, rendered.length, PillImageSpan::class.java).forEach { it.bind(textView) }

        verify { resolver.resolve(match { it.id == strangerUser }, any()) }
        verify { resolver.resolve(match { it.id == nobody }, any()) }
        verify(exactly = 0) { resolver.resolve(match { it.id == knownUser }, any()) }
    }

    @Test
    fun `given avatars are hidden, when a pill is looked up, then it takes the name but not the avatar`() {
        every { resolver.resolve(any(), any()) } answers {
            secondArg<(MatrixItem) -> Unit>()(MatrixItem.UserItem(strangerUser, "Stranger", "mxc://example.org/avatar"))
        }
        val rendered = render("""<a href="https://matrix.to/#/$strangerUser">$strangerUser</a>""", roomId)

        TextView(context).apply {
            text = rendered
            bindPillImageSpans(loadAvatars = false)
        }

        rendered.pills().map { it.getBestName() to it.avatarUrl } shouldBeEqualTo listOf("Stranger" to null)
        verify(exactly = 0) { avatarRenderer.render(any<GlideRequests>(), any<MatrixItem>(), any<Target<Drawable>>(), any()) }
    }

    @Test
    fun `given a mention nothing local knows, when previewing it in the room list, then the pill is looked up`() {
        val preferences = mockk<VectorPreferences>(relaxed = true)
        val formatter = DisplayableEventFormatter(
                stringProvider = mockk(relaxed = true),
                colorProvider = ColorProvider(context),
                drawableProvider = mockk(relaxed = true),
                noticeEventFormatter = mockk(relaxed = true),
                decryptionFailureFormatter = mockk(relaxed = true),
                reactionFormatter = mockk(relaxed = true),
                htmlRenderer = { renderer },
                pgpDecryptor = mockk<PgpDecryptor>(relaxed = true).also { every { it.peekDecryptedBody(any()) } returns null },
                vectorPreferences = preferences,
                matrixItemColorProvider = mockk<MatrixItemColorProvider>(relaxed = true).also { every { it.changes } returns MutableStateFlow(0L) },
                messageTranslationStore = mockk<MessageTranslationStore>(relaxed = true).also { every { it.get(any<TimelineEvent>()) } returns null },
                pillsPostProcessorFactory = mockk<PillsPostProcessor.Factory>().also {
                    every { it.create(any()) } answers { PillsPostProcessor(firstArg(), context, avatarRenderer, sessionHolder, resolver, preferences) }
                },
                textRendererFactory = mockk<EventTextRenderer.Factory>().also {
                    every { it.create(any()) } answers {
                        EventTextRenderer(firstArg(), context, avatarRenderer, sessionHolder, mockk(relaxed = true), preferences, resolver)
                    }
                },
                forwardedMessages = ForwardedMessagePresenter(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
                codeBlockHighlighter = mockk(relaxed = true),
        )
        val content = MessageTextContent(
                msgType = MessageType.MSGTYPE_TEXT,
                body = strangerUser,
                format = MessageFormat.FORMAT_MATRIX_HTML,
                formattedBody = """<a href="https://matrix.to/#/$strangerUser">$strangerUser</a>""",
        )
        val event = Event(type = EventType.MESSAGE, eventId = "\$event", roomId = roomId, content = content.toContent())
        val timelineEvent = TimelineEvent(root = event, localId = 1L, eventId = "\$event", senderInfo = SenderInfo(knownUser, "Alice", true, null))

        TextView(context).apply {
            text = formatter.format(timelineEvent, isDm = false, appendAuthor = true)
            bindPillImageSpans()
        }

        verify { resolver.resolve(match { it.id == strangerUser }, any()) }
    }

    private fun renderAsBio(html: String): Spanned = render(html, null)

    private fun render(
            html: String,
            roomId: String?,
            mentionedAt: Long? = null,
            showLatestProfile: Boolean = false,
            senders: List<SenderInfo> = emptyList(),
    ): Spanned {
        val preferences = mockk<VectorPreferences>(relaxed = true).also { every { it.showLiveSenderInfo() } returns showLatestProfile }
        val pills = PillsPostProcessor(roomId, context, avatarRenderer, sessionHolder, resolver, preferences)
        pills.rememberSenders(senders)
        val textRenderer = EventTextRenderer(roomId, context, avatarRenderer, sessionHolder, mockk(relaxed = true), preferences, resolver)
        val rendered = renderer.render(html, pills.forEvent(mentionedAt)) as Spanned
        val textView = TextView(context)
        renderer.setTextWithPlugins(textView, textRenderer.render(rendered).linkify(null))
        return textView.text as Spanned
    }

    private fun Spanned.pills() = getSpans(0, length, PillImageSpan::class.java).map { it.matrixItem }

    private fun Spanned.pillNames() = pills().map { it.getBestName() }

    private fun String.linkText() = substringAfter("\">").substringBefore("</a>")
}
