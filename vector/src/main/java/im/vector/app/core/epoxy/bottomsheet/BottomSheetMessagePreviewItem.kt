/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.epoxy.bottomsheet

import android.text.method.MovementMethod
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.bumptech.glide.request.RequestOptions
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.clearDrawables
import im.vector.app.core.extensions.setRedactedPreviewStyle
import im.vector.app.core.extensions.setRedactedTint
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.ui.views.GalleryGridLayout
import im.vector.app.core.ui.views.RoundedCornerImageView
import im.vector.app.core.utils.nonScrolling
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.action.LocationUiData
import im.vector.app.features.home.room.detail.timeline.item.BindingOptions
import im.vector.app.features.home.room.detail.timeline.item.GalleryGridBinder
import im.vector.app.features.home.room.detail.timeline.item.MessageGalleryItem
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.style.mediaPreviewCornerRadiusPx
import im.vector.app.features.home.room.detail.timeline.tools.findPillsAndProcess
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.HtmlBodySegmenter
import im.vector.app.features.html.bindEmoteImageSpans
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import org.matrix.android.sdk.api.util.MatrixItem

/**
 * A message preview for bottom sheet.
 */
@EpoxyModelClass
abstract class BottomSheetMessagePreviewItem : VectorEpoxyModel<BottomSheetMessagePreviewItem.Holder>(R.layout.item_bottom_sheet_message_preview) {

    @EpoxyAttribute
    lateinit var avatarRenderer: AvatarRenderer

    @EpoxyAttribute
    lateinit var matrixItem: MatrixItem

    @EpoxyAttribute
    lateinit var body: EpoxyCharSequence

    @EpoxyAttribute
    var redacted: Boolean = false

    /** Marks the whole header as belonging to a deleted message, restored or not. */
    @EpoxyAttribute
    var redactedTint: Boolean = false

    @EpoxyAttribute
    var bindingOptions: BindingOptions? = null

    @EpoxyAttribute
    var bodyDetails: EpoxyCharSequence? = null

    @EpoxyAttribute
    var imageContentRenderer: ImageContentRenderer? = null

    @EpoxyAttribute
    var data: ImageContentRenderer.Data? = null

    @EpoxyAttribute
    var galleryTiles: List<MessageGalleryItem.TileData>? = null

    @EpoxyAttribute
    var galleryMaxWidth: Int = 0

    @EpoxyAttribute
    var hideMedia: Boolean = false

    @EpoxyAttribute
    var hideMediaSolidColor: Boolean = false

    @EpoxyAttribute
    var time: String? = null

    @EpoxyAttribute
    var locationUiData: LocationUiData? = null

    @EpoxyAttribute
    var movementMethod: MovementMethod? = null

    @EpoxyAttribute
    var tableHtml: String? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var richBodyRenderer: RichMessageBodyRenderer? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var htmlPostProcessors: Array<EventHtmlRenderer.PostProcessor> = emptyArray()

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var userClicked: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.setRedactedTint(redactedTint)
        avatarRenderer.render(matrixItem, holder.avatar)
        holder.avatar.onClick(userClicked)
        holder.sender.onClick(userClicked)
        holder.sender.setTextOrHide(matrixItem.getBestName().prepareForDisplay())
        // Glide's RoundedCorners only applies to Bitmap output, so a blurhash placeholder (Drawable)
        // renders square without the view shaping it.
        holder.imagePreview.setCornerRadius(mediaPreviewCornerRadiusPx(holder.imagePreview.context).toFloat())
        data?.let {
            // Full image for transparent-capable content (server thumbnails can bake in a background).
            val mode = ImageContentRenderer.previewMode(isSticker = false, mimeType = it.mimeType)
            if (hideMedia) {
                imageContentRenderer?.renderHidden(it, mode, holder.imagePreview, hideMediaSolidColor)
            } else {
                // The view rounds the picture; a bitmap-baked radius would scale with the decode size.
                imageContentRenderer?.render(it, mode, holder.imagePreview, cornerTransformation = null)
            }
        }
        holder.imagePreview.isVisible = data != null
        val tiles = galleryTiles.orEmpty()
        holder.galleryPreview.isVisible = tiles.isNotEmpty()
        val galleryRenderer = imageContentRenderer
        if (tiles.isNotEmpty() && galleryRenderer != null) {
            val cornerPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, holder.galleryPreview.resources.displayMetrics).toInt()
            GalleryGridBinder.bind(
                    grid = holder.galleryPreview,
                    tiles = tiles,
                    maxWidth = galleryMaxWidth,
                    cornerRadiusPx = cornerPx,
                    imageContentRenderer = galleryRenderer,
                    hideMedia = hideMedia,
                    hideMediaSolidColor = hideMediaSolidColor,
            )
        }
        holder.body.movementMethod = movementMethod?.nonScrolling()
        // The bottom-sheet theme tree doesn't inherit the app theme's textColorHighlight, so give a pressed
        // link a translucent tint matching the link colour here instead of Material's stray teal default.
        holder.body.highlightColor = ColorUtils.setAlphaComponent(
                ThemeUtils.getColor(holder.body.context, android.R.attr.textColorLink), 0x40
        )
        val safeRenderer = richBodyRenderer
        if (safeRenderer != null) {
            safeRenderer.setTextWithPlugins(holder.body, body.charSequence)
        } else {
            holder.body.text = body.charSequence
        }
        if (redacted) {
            holder.body.setRedactedPreviewStyle()
        } else {
            holder.body.setTextColor(ThemeUtils.getColor(holder.body.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
            holder.body.clearDrawables()
        }
        holder.bodyDetails.setTextOrHide(bodyDetails?.charSequence)
        holder.body.bindEmoteImageSpans()
        body.charSequence.findPillsAndProcess(coroutineScope) { it.bind(holder.body) }
        holder.timestamp.setTextOrHide(time)

        // Render a table-containing body as real tables instead of the flattened plaintext the body
        // TextView would show.
        val renderer = richBodyRenderer
        val table = tableHtml
        val showTable = table != null && renderer != null && locationUiData == null
        if (showTable) {
            holder.richBody.isVisible = true
            renderer!!.render(
                    container = holder.richBody,
                    segments = HtmlBodySegmenter.segment(table!!),
                    postProcessors = htmlPostProcessors,
                    movementMethod = movementMethod,
                    onClick = {},
                    onLongClick = { false },
                    noticeStyle = true,
                    interactive = false,
            )
        } else {
            holder.richBody.isVisible = false
            holder.richBody.removeAllViews()
        }

        holder.body.isVisible = locationUiData == null && !showTable
        holder.mapViewContainer.isVisible = locationUiData != null
        locationUiData?.let { safeLocationUiData ->
            GlideApp.with(holder.staticMapImageView)
                    .load(safeLocationUiData.locationUrl)
                    .apply(RequestOptions.centerCropTransform())
                    .into(holder.staticMapImageView)

            val pinMatrixItem = matrixItem.takeIf { safeLocationUiData.locationOwnerId != null }
            safeLocationUiData.locationPinProvider.create(pinMatrixItem) { pinDrawable ->
                // we are not using Glide since it does not display it correctly when there is no user photo
                holder.staticMapPinImageView.setImageDrawable(pinDrawable)
            }
        }
    }

    override fun unbind(holder: Holder) {
        imageContentRenderer?.clear(holder.imagePreview)
        imageContentRenderer?.let { GalleryGridBinder.unbind(holder.galleryPreview, it) }
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.bottom_sheet_message_preview_avatar)
        val sender by bind<TextView>(R.id.bottom_sheet_message_preview_sender)
        val body by bind<TextView>(R.id.bottom_sheet_message_preview_body)
        val richBody by bind<LinearLayout>(R.id.bottom_sheet_message_preview_rich_body)
        val bodyDetails by bind<TextView>(R.id.bottom_sheet_message_preview_body_details)
        val timestamp by bind<TextView>(R.id.bottom_sheet_message_preview_timestamp)
        val imagePreview by bind<RoundedCornerImageView>(R.id.bottom_sheet_message_preview_image)
        val galleryPreview by bind<GalleryGridLayout>(R.id.bottom_sheet_message_preview_gallery)
        val mapViewContainer by bind<FrameLayout>(R.id.mapViewContainer)
        val staticMapImageView by bind<ImageView>(R.id.staticMapImageView)
        val staticMapPinImageView by bind<ImageView>(R.id.staticMapPinImageView)
    }
}
