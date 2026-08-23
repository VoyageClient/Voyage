/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.text.method.MovementMethod
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.files.LocalFilesHelper
import im.vector.app.core.ui.views.GalleryGridLayout
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.style.mediaCornerRadiusPx
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlView
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlViewUpdater
import im.vector.app.features.home.room.detail.timeline.view.ScMessageBubbleWrapView
import im.vector.app.features.media.ImageContentRenderer
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import io.noties.markwon.MarkwonPlugin

@EpoxyModelClass
abstract class MessageGalleryItem : AbsMessageItem<MessageGalleryItem.Holder>() {

    enum class TileType { IMAGE, VIDEO, FILE, AUDIO }

    data class TileData(
            val type: TileType,
            /** Thumbnail render data; null for file/audio placeholder tiles. */
            val mediaData: ImageContentRenderer.Data?,
            val aspect: Float,
            val filename: String?,
    )

    @EpoxyAttribute
    var tiles: List<TileData> = emptyList()

    @EpoxyAttribute
    var maxWidth: Int = 0

    @EpoxyAttribute
    lateinit var imageContentRenderer: ImageContentRenderer

    @EpoxyAttribute
    lateinit var contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var tileClickListener: ((Int, View) -> Unit)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var tileLongClickListener: ((Int) -> Boolean)? = null

    @EpoxyAttribute
    var hideMedia: Boolean = false

    @EpoxyAttribute
    var hideMediaSolidColor: Boolean = false

    @EpoxyAttribute
    var caption: EpoxyCharSequence? = null

    @EpoxyAttribute
    var captionBindingOptions: BindingOptions? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var captionMovementMethod: MovementMethod? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var captionMarkwonPlugins: (List<MarkwonPlugin>)? = null

    @EpoxyAttribute
    var captionUseBigFont: Boolean = false

    @EpoxyAttribute
    var previewUrlRetriever: PreviewUrlRetriever? = null

    @EpoxyAttribute
    var previewUrlCallback: TimelineEventController.PreviewUrlCallback? = null

    private val previewUrlViewUpdater = PreviewUrlViewUpdater()

    override fun bind(holder: Holder) {
        super.bind(holder)
        val context = holder.view.context

        GalleryGridBinder.bind(
                grid = holder.grid,
                tiles = tiles,
                maxWidth = maxWidth,
                cornerRadiusPx = baseAttributes.informationData.messageLayout.mediaCornerRadiusPx(context),
                imageContentRenderer = imageContentRenderer,
                hideMedia = hideMedia,
                hideMediaSolidColor = hideMediaSolidColor,
                tileClickListener = tileClickListener,
                tileLongClickListener = tileLongClickListener,
        )

        holder.grid.setOnLongClickListener(attributes.itemLongClickListener)

        if (!attributes.informationData.sendState.hasFailed()) {
            val firstLocalUrl = tiles.firstNotNullOfOrNull { it.mediaData?.url }
            contentUploadStateTrackerBinder.bind(
                    attributes.informationData.stableId,
                    LocalFilesHelper(context).isLocalFile(firstLocalUrl),
                    holder.progressLayout
            )
        } else {
            holder.progressLayout.isVisible = false
        }

        // A caption longer than the grid would widen the bubble past it, leaving a gap beside the tiles.
        holder.captionView.maxWidth = maxWidth.takeIf { it > 0 } ?: Int.MAX_VALUE

        MediaCaptionBinder.bind(
                view = holder.captionView,
                caption = caption,
                bindingOptions = captionBindingOptions,
                movementMethod = captionMovementMethod,
                itemLongClickListener = attributes.itemLongClickListener,
                markwonPlugins = captionMarkwonPlugins,
                useBigFont = captionUseBigFont,
        )

        previewUrlViewUpdater.bind(
                view = holder.previewUrlView,
                retriever = previewUrlRetriever,
                callback = previewUrlCallback,
                imageContentRenderer = imageContentRenderer,
                stableId = attributes.informationData.stableId,
                messageLayout = attributes.informationData.messageLayout,
        )
    }

    override fun unbind(holder: Holder) {
        previewUrlViewUpdater.unbind()
        GalleryGridBinder.unbind(holder.grid, imageContentRenderer)
        contentUploadStateTrackerBinder.unbind(attributes.informationData.stableId)
        holder.grid.setOnLongClickListener(null)
        super.unbind(holder)
    }

    override fun getViewStubId() = STUB_ID

    // Media sits in a real bubble, so the timestamp goes under it like a text message's does.
    override fun allowFooterOverlay(holder: Holder, bubbleWrapView: ScMessageBubbleWrapView): Boolean = false

    override fun allowFooterBelow(holder: Holder): Boolean = true

    override fun needsFooterReservation(): Boolean = false

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val grid by bind<GalleryGridLayout>(R.id.messageGalleryGrid)
        val progressLayout by bind<ViewGroup>(R.id.messageMediaUploadProgressLayout)
        val captionView by bind<AppCompatTextView>(R.id.messageCaptionView)
        val previewUrlView by bind<PreviewUrlView>(R.id.messageUrlPreview)
    }

    companion object {
        private val STUB_ID = R.id.messageContentGalleryStub
    }
}
