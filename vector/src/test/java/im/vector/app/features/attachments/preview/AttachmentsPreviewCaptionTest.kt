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

    private fun viewModel(caption: String?) =
            AttachmentsPreviewViewModel(AttachmentsPreviewViewState(AttachmentsPreviewArgs(attachments, caption)))
}
