/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import com.airbnb.mvrx.test.MavericksTestRule
import com.airbnb.mvrx.withState
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test
import org.matrix.android.sdk.api.session.content.ContentAttachmentData

class AttachmentsPreviewCaptionTest {

    @get:Rule
    val mavericksTestRule = MavericksTestRule()

    private val attachments = listOf("one", "two", "three").map {
        ContentAttachmentData(
                queryUri = "content://$it",
                mimeType = "image/jpeg",
                type = ContentAttachmentData.Type.IMAGE,
        )
    }

    @Test
    fun `the composer's text captions the last attachment only`() {
        val state = AttachmentsPreviewViewState(AttachmentsPreviewArgs(attachments, "hello"))

        attachments.map { state.captionOf(it) } shouldBeEqualTo listOf("", "", "hello")
    }

    @Test
    fun `a single attachment still carries it`() {
        val state = AttachmentsPreviewViewState(AttachmentsPreviewArgs(attachments.take(1), "hello"))

        state.captionOf(attachments.first()) shouldBeEqualTo "hello"
    }

    @Test
    fun `a gallery send spreads the composer's text over every attachment`() {
        val viewModel = viewModel("hello")

        viewModel.handle(AttachmentsPreviewAction.SetSharesOneCaption(true))

        withState(viewModel) { state ->
            attachments.map { state.captionOf(it) } shouldBeEqualTo listOf("hello", "hello", "hello")
        }
    }

    @Test
    fun `a gallery caption is written against every attachment`() {
        val viewModel = viewModel(null)

        viewModel.handle(AttachmentsPreviewAction.SetSharesOneCaption(true))
        viewModel.handle(AttachmentsPreviewAction.SetCurrentAttachment(1))
        viewModel.handle(AttachmentsPreviewAction.SetCaption("shared"))

        withState(viewModel) { state ->
            attachments.map { state.captionOf(it) } shouldBeEqualTo listOf("shared", "shared", "shared")
        }
    }

    @Test
    fun `captioning one attachment leaves the others alone`() {
        val viewModel = viewModel("hello")

        viewModel.handle(AttachmentsPreviewAction.SetCurrentAttachment(0))
        viewModel.handle(AttachmentsPreviewAction.SetCaption("mine"))

        withState(viewModel) { state ->
            attachments.map { state.captionOf(it) } shouldBeEqualTo listOf("mine", "", "hello")
        }
    }

    @Test
    fun `rasterization is remembered separately for each attachment`() {
        val viewModel = viewModel(null)
        viewModel.handle(AttachmentsPreviewAction.SetRasterize(true))
        viewModel.handle(AttachmentsPreviewAction.SetCurrentAttachment(1))
        withState(viewModel) { state ->
            state.rasterize shouldBeEqualTo setOf(state.stableIdOf(attachments.first()))
        }
        viewModel.handle(AttachmentsPreviewAction.SetCurrentAttachment(0))
        viewModel.handle(AttachmentsPreviewAction.SetRasterize(false))
        withState(viewModel) { state -> state.rasterize shouldBeEqualTo emptySet<String>() }
    }

    @Test
    fun `moving the selected attachment preserves its caption and send settings`() {
        val viewModel = viewModel("hello")
        viewModel.handle(AttachmentsPreviewAction.SetCurrentAttachment(2))
        viewModel.handle(AttachmentsPreviewAction.SetSendAsSpoiler(true))
        viewModel.handle(AttachmentsPreviewAction.SetKeepOriginalSize(true))

        viewModel.handle(AttachmentsPreviewAction.MoveAttachment(2, 0))

        withState(viewModel) { state ->
            state.attachments shouldBeEqualTo listOf(attachments[2], attachments[0], attachments[1])
            state.currentAttachmentIndex shouldBeEqualTo 0
            state.attachments.map { state.captionOf(it) } shouldBeEqualTo listOf("hello", "", "")
            state.sendAsSpoiler shouldBeEqualTo setOf(state.stableIdOf(attachments[2]))
            state.keepOriginalSize shouldBeEqualTo setOf(state.stableIdOf(attachments[2]))
        }
    }

    @Test
    fun `moving across the selection keeps the same attachment selected in both directions`() {
        val viewModel = viewModel(null)
        viewModel.handle(AttachmentsPreviewAction.SetCurrentAttachment(1))

        viewModel.handle(AttachmentsPreviewAction.MoveAttachment(0, 2))
        withState(viewModel) { state ->
            state.currentAttachmentIndex shouldBeEqualTo 0
            state.attachments[state.currentAttachmentIndex] shouldBeEqualTo attachments[1]
        }

        viewModel.handle(AttachmentsPreviewAction.MoveAttachment(2, 0))
        withState(viewModel) { state ->
            state.currentAttachmentIndex shouldBeEqualTo 1
            state.attachments shouldBeEqualTo attachments
        }
    }

    private fun viewModel(caption: String?) =
            AttachmentsPreviewViewModel(AttachmentsPreviewViewState(AttachmentsPreviewArgs(attachments, caption)))
}
