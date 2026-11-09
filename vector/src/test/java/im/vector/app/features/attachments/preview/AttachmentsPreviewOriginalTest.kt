/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import com.airbnb.mvrx.test.MavericksTestRule
import com.airbnb.mvrx.withState
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Rule
import org.junit.Test
import org.matrix.android.sdk.api.session.content.ContentAttachmentData

class AttachmentsPreviewOriginalTest {

    @get:Rule
    val mavericksTestRule = MavericksTestRule()

    private val photo = attachment("photo", "image/jpeg", ContentAttachmentData.Type.IMAGE)
    private val video = attachment("video", "video/mp4", ContentAttachmentData.Type.VIDEO)
    private val document = attachment("document", "application/pdf", ContentAttachmentData.Type.FILE)
    private val drawing = attachment("drawing", "image/svg+xml", ContentAttachmentData.Type.IMAGE)

    private fun attachment(name: String, mimeType: String, type: ContentAttachmentData.Type) = ContentAttachmentData(
            queryUri = "content://$name",
            mimeType = mimeType,
            type = type,
            width = 4000,
            height = 3000,
    )

    private fun state(vararg attachments: ContentAttachmentData) = AttachmentsPreviewViewState(AttachmentsPreviewArgs(attachments.toList()))

    @Test
    fun `an original ignores the compression chosen before it was switched on`() {
        val state = state(photo).copy(
                keepOriginalSize = setOf(photo.queryUri),
                compressionSettings = mapOf(photo.queryUri to CompressionSettings(quality = 30, width = 800, height = 600)),
        )

        val outgoing = state.outgoingOf(photo, rasterizing = false)

        outgoing.keepOriginalSize shouldBe true
        outgoing.hasCustomCompression shouldBe false
        outgoing.compressionHeight.shouldBeNull()
    }

    @Test
    fun `switching original off brings the chosen compression back`() {
        val state = state(photo).copy(
                compressionSettings = mapOf(photo.queryUri to CompressionSettings(quality = 30, width = 800, height = 600)),
        )

        val outgoing = state.outgoingOf(photo, rasterizing = false)

        outgoing.keepOriginalSize shouldBe false
        outgoing.compressionQuality shouldBeEqualTo 30
        outgoing.compressionWidth shouldBeEqualTo 800
    }

    @Test
    fun `boxes left at the source size are not a resize`() {
        val state = state(photo).copy(
                compressionSettings = mapOf(photo.queryUri to CompressionSettings(width = 4000, height = 3000)),
        )

        state.outgoingOf(photo, rasterizing = false).hasCustomCompression shouldBe false
    }

    @Test
    fun `send all as original only marks what can be compressed`() {
        val viewModel = AttachmentsPreviewViewModel(state(photo, document, video, drawing))

        viewModel.handle(AttachmentsPreviewAction.SetKeepOriginalSizeForAll(true))

        withState(viewModel) { state ->
            state.keepOriginalSize shouldBeEqualTo setOf(photo.queryUri, video.queryUri)
        }
    }

    @Test
    fun `compression off by default starts compressible items as originals`() {
        val viewModel = AttachmentsPreviewViewModel(state(photo, document))

        viewModel.handle(AttachmentsPreviewAction.ApplyCompressionDefault(compress = false))

        withState(viewModel) { state ->
            state.keepOriginalSize shouldBeEqualTo setOf(photo.queryUri)
        }
    }

    @Test
    fun `the default is only applied once, so a recreated view keeps the choices made`() {
        val viewModel = AttachmentsPreviewViewModel(state(photo, video))
        viewModel.handle(AttachmentsPreviewAction.ApplyCompressionDefault(compress = false))
        viewModel.handle(AttachmentsPreviewAction.SetKeepOriginalSize(false))

        viewModel.handle(AttachmentsPreviewAction.ApplyCompressionDefault(compress = false))

        withState(viewModel) { state ->
            state.keepOriginalSize shouldBeEqualTo setOf(video.queryUri)
        }
    }

    @Test
    fun `compress all clears every original`() {
        val viewModel = AttachmentsPreviewViewModel(state(photo, video))
        viewModel.handle(AttachmentsPreviewAction.SetKeepOriginalSize(true))
        viewModel.handle(AttachmentsPreviewAction.SetKeepOriginalSizeForAll(true))

        viewModel.handle(AttachmentsPreviewAction.SetKeepOriginalSizeForAll(false))

        withState(viewModel) { state ->
            state.keepOriginalSize shouldBeEqualTo emptySet()
        }
    }
}
