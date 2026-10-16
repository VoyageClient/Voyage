/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.preview

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.room.detail.timeline.factory.PollItemViewStateFactory
import im.vector.app.features.home.room.detail.timeline.factory.PollOptionViewStateFactory
import im.vector.app.features.home.room.detail.timeline.format.EventDetailsFormatter
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.ForwardedMessagePresenter
import im.vector.app.features.home.room.detail.timeline.helper.MessageSuffixFormatter
import im.vector.app.features.home.room.detail.timeline.item.MediaPillPreview
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent.Media
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent.TextStyle
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.home.room.detail.timeline.tools.withoutTrailingLineBreak
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.translation.MessageTranslationStore
import im.vector.lib.strings.CommonStrings
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.room.model.EditAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessagePreviewContentBuilderTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private val htmlRenderer = mockk<EventHtmlRenderer>().also {
        every { it.render(any<String>(), *anyVararg()) } answers { "html:${firstArg<String>()}" }
    }
    private val textRenderer = mockk<EventTextRenderer>().also {
        every { it.render(any(), any()) } answers { firstArg() }
    }
    private val noticeEventFormatter = mockk<NoticeEventFormatter>(relaxed = true).also {
        every { it.formatRedactedEvent(any()) } returns "Message deleted"
        every { it.formatMalformedMessage() } returns "Malformed"
        every { it.formatLocationNotice(any(), any()) } returns "Alice shared a location"
        every { it.format(any<TimelineEvent>(), any()) } returns "Alice changed the topic"
    }
    private val translationStore = mockk<MessageTranslationStore>(relaxed = true).also {
        every { it.get(any<TimelineEvent>()) } returns null
    }
    private val restorer = mockk<RedactedContentRestorer>().also {
        every { it.restoreEvent(any()) } returns null
    }
    private val activeSessionHolder = mockk<ActiveSessionHolder>().also {
        every { it.getSafeActiveSession() } returns null
    }

    private val builder = MessagePreviewContentBuilder(
            activeSessionHolder = activeSessionHolder,
            stringProvider = mockk<StringProvider>(relaxed = true).also {
                every { it.getString(CommonStrings.message_reply_to_poll_preview) } returns "Poll"
            },
            vectorPreferences = mockk(relaxed = true),
            noticeEventFormatter = noticeEventFormatter,
            htmlRenderer = { htmlRenderer },
            htmlCompressor = mockk { every { compress(any()) } answers { firstArg() } },
            pillsPostProcessorFactory = mockk(relaxed = true),
            textRendererFactory = mockk { every { create(any()) } returns textRenderer },
            forwardedMessages = ForwardedMessagePresenter(context, { activeSessionHolder }, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
            messageTranslationStore = translationStore,
            pgpDecryptor = mockk { every { peekDecryptedBody(any()) } returns null },
            redactedContentRestorer = restorer,
            preservedAttachmentResolver = mockk { every { fileFor(any(), any()) } returns null },
            suffixFormatter = MessageSuffixFormatter(
                    mockk<StringProvider>().also {
                        every { it.getString(CommonStrings.edited_suffix) } returns "(edited)"
                        every { it.getString(CommonStrings.message_translated_suffix) } returns "(translated)"
                    },
                    ColorProvider(context),
                    DimensionConverter(context.resources),
            ),
            eventDetailsFormatter = EventDetailsFormatter(context),
            pollItemViewStateFactory = PollItemViewStateFactory(
                    mockk(relaxed = true),
                    PollOptionViewStateFactory(),
            ),
            pollResponseDataFactory = mockk { every { create(any()) } returns null },
            urlMapProvider = mockk { every { buildStaticMapUrl(any(), any(), any(), any(), any()) } returns "https://maps/static.png" },
            dimensionConverter = DimensionConverter(context.resources),
    )

    private val options = MessagePreviewContentBuilder.Options(
            thumbnailMaxWidth = 200,
            thumbnailMaxHeight = 100,
            galleryMaxWidth = 300,
            galleryMaxHeight = 300,
            linkify = false,
    )

    private fun eventOf(
            content: Content,
            type: String = EventType.MESSAGE,
            unsignedData: UnsignedData? = null,
            edited: Boolean = false,
    ) = TimelineEvent(
            root = Event(type = type, eventId = "\$event", roomId = "!room:x", senderId = "@alice:x", content = content, unsignedData = unsignedData),
            localId = 1L,
            eventId = "\$event",
            senderInfo = SenderInfo("@alice:x", "Alice", true, null),
            annotations = EventAnnotationsSummary(editSummary = EditAggregatedSummary(sourceEvents = listOf("\$edit"), localEchos = emptyList()))
                    .takeIf { edited },
    )

    private fun build(
            content: Content,
            type: String = EventType.MESSAGE,
            options: MessagePreviewContentBuilder.Options = this.options,
            edited: Boolean = false,
    ) = builder.build(eventOf(content, type, edited = edited), options)

    private val replyRelation = mapOf("m.in_reply_to" to mapOf("event_id" to "\$parent"))

    private fun html(body: String, formatted: String, msgType: String = "m.text", extra: Map<String, Any> = emptyMap()): Content =
            mapOf("msgtype" to msgType, "body" to body, "format" to "org.matrix.custom.html", "formatted_body" to formatted) + extra

    private val image: Content = mapOf(
            "msgtype" to "m.image",
            "body" to "cat.png",
            "url" to "mxc://x/cat",
            "info" to mapOf("w" to 400, "h" to 300, "mimetype" to "image/png"),
    )

    private fun galleryItem(name: String): Content = (image - "msgtype") + mapOf("itemtype" to "m.image", "body" to "$name.png", "url" to "mxc://x/$name")

    private val forwarded = mapOf("m.forwarded" to mapOf("origin_server_ts" to 1L))

    @Test
    fun `plain text previews its body`() {
        val preview = build(mapOf("msgtype" to "m.text", "body" to "hello"))
        preview.text?.charSequence.toString() shouldBeEqualTo "hello"
        preview.textStyle shouldBeEqualTo TextStyle.NORMAL
        preview.linkifiable shouldBe true
        preview.media shouldBe null
    }

    @Test
    fun `plain text omits only the final line break like CSS pre-wrap`() {
        listOf(
                "hello\n" to "hello",
                "hello\r\n" to "hello",
                "hello\r" to "hello",
                "hello\n\n" to "hello\n",
                "  hello\n\nworld\n" to "  hello\n\nworld",
                "hello  " to "hello  ",
                "\n" to "",
                "" to "",
        ).forEach { (body, expected) ->
            build(mapOf("msgtype" to "m.text", "body" to body)).text?.charSequence.toString() shouldBeEqualTo expected
        }
    }

    @Test
    fun `removing the final break preserves spans and the original body`() {
        val original = SpannableStringBuilder("hello\n")
        val span = StyleSpan(Typeface.ITALIC)
        original.setSpan(span, 0, original.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val rendered = original.withoutTrailingLineBreak() as Spanned
        rendered.toString() shouldBeEqualTo "hello"
        rendered.getSpanStart(span) shouldBeEqualTo 0
        rendered.getSpanEnd(span) shouldBeEqualTo 5
        original.toString() shouldBeEqualTo "hello\n"
        original.getSpanEnd(span) shouldBeEqualTo 6
    }

    @Test
    fun `plain bodies normalize before reply emote and edited decorations`() {
        build(mapOf("msgtype" to "m.notice", "body" to "hello\n")).text?.charSequence.toString() shouldBeEqualTo "hello"
        build(mapOf("msgtype" to "m.emote", "body" to "waves\n")).text?.charSequence.toString() shouldBeEqualTo "Alice waves"
        build(mapOf("msgtype" to "m.text", "body" to "hello\n"), edited = true)
                .text?.charSequence.toString() shouldBeEqualTo "hello (edited)"
        build(mapOf("msgtype" to "m.text", "body" to "> <@bob:x> original\n\nhello\n", "m.relates_to" to replyRelation))
                .text?.charSequence.toString() shouldBeEqualTo "hello"
    }

    @Test
    fun `plain captions normalize while HTML captions preserve their renderer output`() {
        build(image + mapOf("body" to "caption\n", "filename" to "cat.png"))
                .text?.charSequence.toString() shouldBeEqualTo "caption"
        build(image + mapOf("body" to "caption\n", "filename" to "cat.png", "format" to "org.matrix.custom.html", "formatted_body" to "<pre>code\n</pre>"))
                .text?.charSequence.toString() shouldBeEqualTo "html:<pre>code\n</pre>"
    }

    @Test
    fun `formatted text goes through the HTML renderer`() {
        build(html("hi", "<b>hi</b>")).text?.charSequence.toString() shouldBeEqualTo "html:<b>hi</b>"
    }

    @Test
    fun `a quoted reply never shows the message it replied to, even nested`() {
        val formatted = "<mx-reply><blockquote>outer<mx-reply><blockquote>inner</blockquote></mx-reply></blockquote></mx-reply>mine"
        build(html("> <@bob:x> outer\n\nmine", formatted, extra = mapOf("m.relates_to" to replyRelation)))
                .text?.charSequence.toString() shouldBeEqualTo "html:mine"
    }

    @Test
    fun `a reply fallback in the middle of the body is stripped too`() {
        build(html("mine", "before<mx-reply><blockquote>quoted</blockquote></mx-reply>after"))
                .text?.charSequence.toString() shouldBeEqualTo "html:beforeafter"
    }

    @Test
    fun `a plain reply drops its quoted fallback`() {
        val content = mapOf("msgtype" to "m.text", "body" to "> <@bob:x> original\n> more\n\nmine", "m.relates_to" to replyRelation)
        build(content).text?.charSequence.toString() shouldBeEqualTo "mine"
    }

    @Test
    fun `a captioned media reply shows only its own caption`() {
        val content = image + mapOf(
                "body" to "> <@bob:x> original\n\nmy caption",
                "filename" to "cat.png",
                "m.relates_to" to replyRelation,
        )
        val preview = build(content)
        preview.text?.charSequence.toString() shouldBeEqualTo "my caption"
        preview.media shouldBeInstanceOf Media.Image::class
    }

    @Test
    fun `a table renders as rich segments`() {
        build(html("t", "<table><tr><td>a</td></tr></table>")).richSegments shouldNotBe null
    }

    @Test
    fun `a notice message is notice-styled but still linkifiable`() {
        val preview = build(mapOf("msgtype" to "m.notice", "body" to "bot says"))
        preview.textStyle shouldBeEqualTo TextStyle.NOTICE
        preview.linkifiable shouldBe true
    }

    @Test
    fun `an emote reads as by its sender`() {
        build(mapOf("msgtype" to "m.emote", "body" to "waves")).text?.charSequence.toString() shouldBeEqualTo "Alice waves"
    }

    @Test
    fun `an uncaptioned image previews its thumbnail with an empty text and its file name`() {
        val preview = build(image)
        val media = preview.media as Media.Image
        media.data.maxWidth shouldBeEqualTo 200
        media.data.maxHeight shouldBeEqualTo 100
        media.isSticker shouldBe false
        preview.text?.charSequence.toString() shouldBeEqualTo ""
        preview.captionlessText?.charSequence.toString() shouldBeEqualTo ""
        preview.attachmentName shouldBeEqualTo "cat.png"
        preview.forwardFrame shouldBe null
    }

    @Test
    fun `a file previews as its pill, which already names it`() {
        val preview = build(mapOf("msgtype" to "m.file", "body" to "doc.pdf", "url" to "mxc://x/doc"))
        (preview.media as Media.Pill).pill shouldBeInstanceOf MediaPillPreview.File::class
        preview.attachmentName shouldBe null
    }

    @Test
    fun `a sticker shows no caption, but its name for the sheet`() {
        val sticker = mapOf("body" to "a happy cat", "url" to "mxc://x/s", "info" to mapOf("w" to 100, "h" to 100, "mimetype" to "image/png"))
        val preview = build(sticker, type = EventType.STICKER)
        (preview.media as Media.Image).isSticker shouldBe true
        preview.text?.charSequence.toString() shouldBeEqualTo ""
        preview.attachmentName shouldBeEqualTo "a happy cat"
    }

    @Test
    fun `a poll previews whole, as the timeline draws it`() {
        val poll = mapOf(
                "org.matrix.msc3381.poll.start" to mapOf(
                        "question" to mapOf("org.matrix.msc1767.text" to "Lunch?"),
                        "answers" to listOf(
                                mapOf("id" to "a", "org.matrix.msc1767.text" to "Yes"),
                                mapOf("id" to "b", "org.matrix.msc1767.text" to "No"),
                        ),
                ),
        )
        val preview = build(poll, type = "org.matrix.msc3381.poll.start", edited = true)
        val drawn = preview.poll!!
        drawn.question.charSequence.toString() shouldBeEqualTo "Lunch? (edited)"
        drawn.options.map { it.optionAnswer } shouldBeEqualTo listOf("Yes", "No")
        drawn.ended shouldBe false
        preview.text shouldBe null
    }

    @Test
    fun `media carries its details line`() {
        build(image).details shouldNotBe null
        build(mapOf("msgtype" to "m.text", "body" to "hello")).details shouldBe null
    }

    @Test
    fun `a gallery previews as its grid, or as one item when scoped to it`() {
        val gallery = mapOf(
                "msgtype" to "dm.filament.gallery",
                "body" to "two pictures",
                "itemtypes" to listOf(galleryItem("cat"), galleryItem("dog")),
        )
        val whole = build(gallery)
        whole.media shouldBeInstanceOf Media.Gallery::class
        whole.text?.charSequence.toString() shouldBeEqualTo "two pictures"
        build(gallery, options = options.copy(galleryItemIndex = 1)).media shouldBeInstanceOf Media.Image::class
    }

    @Test
    fun `a forwarded image frames its media, and a forwarded text frames its text`() {
        build(image + forwarded).forwardFrame shouldNotBe null
        val text = build(mapOf("msgtype" to "m.text", "body" to "hi") + forwarded)
        text.forwardFrame shouldBe null
        text.media shouldBe null
    }

    @Test
    fun `a redacted message previews the placeholder and no media`() {
        val event = eventOf(image, unsignedData = UnsignedData(age = null, redactedEvent = Event(type = EventType.REDACTION)))
        val preview = builder.build(event, options)
        preview.textStyle shouldBeEqualTo TextStyle.REDACTED
        preview.text?.charSequence.toString() shouldBeEqualTo "Message deleted"
        preview.media shouldBe null
    }

    @Test
    fun `a location previews its map, keeping its notice text for where no map can be drawn`() {
        val location = mapOf("msgtype" to "m.location", "body" to "here", "geo_uri" to "geo:1,2")
        val own = build(location)
        own.location?.mapUrl shouldBeEqualTo "https://maps/static.png"
        // No asset type means the sender's own location, which carries their avatar.
        own.location?.pinUser?.id shouldBeEqualTo "@alice:x"
        own.textStyle shouldBeEqualTo TextStyle.NOTICE
        own.text?.charSequence.toString() shouldBeEqualTo "Alice shared a location"
        build(location + mapOf("m.asset" to mapOf("type" to "m.pin"))).location?.pinUser shouldBe null
    }

    @Test
    fun `an unparseable message previews the malformed placeholder, unlinkified`() {
        val preview = build(mapOf("unexpected" to true))
        preview.text?.charSequence.toString() shouldBeEqualTo "Malformed"
        preview.linkifiable shouldBe false
    }

    @Test
    fun `a state event previews its notice`() {
        val preview = build(mapOf("topic" to "new"), type = EventType.STATE_ROOM_TOPIC)
        preview.text?.charSequence.toString() shouldBeEqualTo "Alice changed the topic"
        preview.textStyle shouldBeEqualTo TextStyle.NOTICE
        preview.linkifiable shouldBe false
    }

    @Test
    fun `a translation replaces the body`() {
        every { translationStore.get(any<TimelineEvent>()) } returns MessageTranslationStore.Translation("hola", null, "es")
        build(mapOf("msgtype" to "m.text", "body" to "hello")).text?.charSequence.toString() shouldBeEqualTo "hola (translated)"
    }

    @Test
    fun `an edited message carries the edited marker, as in the timeline`() {
        build(mapOf("msgtype" to "m.text", "body" to "hello"), edited = true).text?.charSequence.toString() shouldBeEqualTo "hello (edited)"
    }

    @Test
    fun `an edited caption is marked but uncaptioned media is not`() {
        build(image + mapOf("body" to "my caption", "filename" to "cat.png"), edited = true)
                .text?.charSequence.toString() shouldBeEqualTo "my caption (edited)"
        build(image, edited = true).text?.charSequence.toString() shouldBeEqualTo ""
    }

    @Test
    fun `a translated table carries its markers under the table`() {
        every { translationStore.get(any<TimelineEvent>()) } returns
                MessageTranslationStore.Translation("t", null, "es", formatted = "<table><tr><td>b</td></tr></table>")
        val preview = build(html("t", "<table><tr><td>a</td></tr></table>"), edited = true)
        preview.richSegments shouldNotBe null
        preview.richFooter?.charSequence.toString() shouldBeEqualTo "(edited) (translated)"
    }

    @Test
    fun `decrypted PGP plaintext replaces the armored body`() {
        val preview = build(mapOf("msgtype" to "m.text", "body" to "-----BEGIN PGP MESSAGE-----"), options = options.copy(pgpPlaintext = "secret"))
        preview.text?.charSequence.toString() shouldBeEqualTo "secret"
    }
}
