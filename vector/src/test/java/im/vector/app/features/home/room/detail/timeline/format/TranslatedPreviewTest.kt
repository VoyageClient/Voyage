/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.translation.MessageTranslationStore
import im.vector.lib.strings.CommonStrings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageEmoteContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TranslatedPreviewTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private val htmlRenderer = mockk<EventHtmlRenderer>().also {
        every { it.render(any<String>(), *anyVararg()) } answers { "rendered:${firstArg<String>()}" }
    }
    private val translationStore = mockk<MessageTranslationStore>(relaxed = true)

    private val formatter = DisplayableEventFormatter(
            stringProvider = mockk<StringProvider>(relaxed = true).also {
                every { it.getString(CommonStrings.sent_an_image) } returns "Image."
            },
            colorProvider = ColorProvider(context),
            drawableProvider = mockk(relaxed = true),
            noticeEventFormatter = mockk(relaxed = true),
            reactionFormatter = mockk(relaxed = true),
            htmlRenderer = { htmlRenderer },
            pgpDecryptor = mockk(relaxed = true),
            vectorPreferences = mockk(relaxed = true),
            matrixItemColorProvider = mockk<im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider>(relaxed = true).also {
                every { it.changes } returns kotlinx.coroutines.flow.MutableStateFlow(0L)
            },
            messageTranslationStore = translationStore,
            pillsPostProcessorFactory = mockk(relaxed = true),
            textRendererFactory = mockk(relaxed = true),
    )

    // No room id, so the preview skips the per-room pill processors.
    private fun previewOf(content: MessageContent, translation: MessageTranslationStore.Translation): CharSequence {
        val event = Event(type = EventType.MESSAGE, eventId = "\$event", content = content.toContent())
        val timelineEvent = TimelineEvent(
                root = event,
                localId = 1L,
                eventId = "\$event",
                senderInfo = SenderInfo("@alice:example.org", "Alice", true, null),
        )
        every { translationStore.get(any<TimelineEvent>()) } returns translation
        return formatter.format(timelineEvent, isDm = false, appendAuthor = false)
    }

    @Test
    fun `a translated formatted message previews through the HTML renderer`() {
        val preview = previewOf(
                MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = "hello Bob"),
                MessageTranslationStore.Translation("hola Bob", "en", "es", formatted = "hola <a href=\"https://matrix.to/#/@bob:x\">Bob</a>"),
        )
        verify { htmlRenderer.render("hola <a href=\"https://matrix.to/#/@bob:x\">Bob</a>", *anyVararg()) }
        assertEquals("rendered:hola <a href=\"https://matrix.to/#/@bob:x\">Bob</a>", preview.toString())
    }

    @Test
    fun `a translated plain message previews the translated text`() {
        val preview = previewOf(
                MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = "hello"),
                MessageTranslationStore.Translation("hola", "en", "es"),
        )
        assertEquals("hola", preview.toString())
    }

    @Test
    fun `a translated emote keeps the emote form`() {
        val preview = previewOf(
                MessageEmoteContent(msgType = MessageType.MSGTYPE_EMOTE, body = "waves"),
                MessageTranslationStore.Translation("saluda", "en", "es"),
        )
        assertEquals("Alice saluda", preview.toString())
    }

    @Test
    fun `a translated image caption still previews as an image`() {
        val preview = previewOf(
                MessageImageContent(msgType = MessageType.MSGTYPE_IMAGE, body = "a cat", filename = "cat.png", url = "mxc://x/y"),
                MessageTranslationStore.Translation("un gato", "en", "es"),
        )
        assertEquals("Image.", preview.toString())
    }
}
