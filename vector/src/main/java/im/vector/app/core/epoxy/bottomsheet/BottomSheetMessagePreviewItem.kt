/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.epoxy.bottomsheet

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.method.MovementMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.bumptech.glide.request.RequestOptions
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.ui.views.RoundedCornerImageView
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.action.LocationUiData
import im.vector.app.features.home.room.detail.timeline.item.BindingOptions
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.tools.findPillsAndProcess
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.HtmlBodySegmenter
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
    var bindingOptions: BindingOptions? = null

    @EpoxyAttribute
    var bodyDetails: EpoxyCharSequence? = null

    @EpoxyAttribute
    var imageContentRenderer: ImageContentRenderer? = null

    @EpoxyAttribute
    var data: ImageContentRenderer.Data? = null

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
        avatarRenderer.render(matrixItem, holder.avatar)
        holder.avatar.onClick(userClicked)
        holder.sender.onClick(userClicked)
        holder.sender.setTextOrHide(matrixItem.getBestName())
        // Static outline clip — Glide's RoundedCorners only applies to Bitmap output, so a
        // blurhash placeholder (Drawable) renders with square corners without this clip.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // ViewOutlineProvider / clipToOutline are API 21+ (anti-aliased).
            if (holder.imagePreview.outlineProvider !== ROUNDED_OUTLINE_PROVIDER) {
                holder.imagePreview.outlineProvider = ROUNDED_OUTLINE_PROVIDER
                holder.imagePreview.clipToOutline = true
            }
        } else {
            // Pre-Lollipop: RoundedCornerImageView clips via canvas path instead.
            val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, holder.imagePreview.resources.displayMetrics)
            holder.imagePreview.setCornerRadii(r, r, r, r)
        }
        data?.let {
            if (hideMedia) {
                imageContentRenderer?.renderHidden(it, ImageContentRenderer.Mode.THUMBNAIL, holder.imagePreview, hideMediaSolidColor)
            } else {
                imageContentRenderer?.render(it, ImageContentRenderer.Mode.THUMBNAIL, holder.imagePreview)
            }
        }
        holder.imagePreview.isVisible = data != null
        // Fade the capped preview into the bottom sheet's surface background, matching the reply
        // header / composer previews, rather than clipping with a trailing ellipsis.
        if (holder.bodyFade.background == null) {
            val surfaceColor = ThemeUtils.getColor(holder.bodyFade.context, com.google.android.material.R.attr.colorSurface)
            holder.bodyFade.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, surfaceColor)
            )
        }
        holder.body.movementMethod = movementMethod
        val safeRenderer = richBodyRenderer
        if (safeRenderer != null) {
            safeRenderer.setTextWithPlugins(holder.body, body.charSequence)
        } else {
            holder.body.text = body.charSequence
        }
        holder.bodyDetails.setTextOrHide(bodyDetails?.charSequence)
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
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.bottom_sheet_message_preview_avatar)
        val sender by bind<TextView>(R.id.bottom_sheet_message_preview_sender)
        val body by bind<TextView>(R.id.bottom_sheet_message_preview_body)
        val richBody by bind<LinearLayout>(R.id.bottom_sheet_message_preview_rich_body)
        val bodyFade by bind<View>(R.id.bottom_sheet_message_preview_fade)
        val bodyDetails by bind<TextView>(R.id.bottom_sheet_message_preview_body_details)
        val timestamp by bind<TextView>(R.id.bottom_sheet_message_preview_timestamp)
        val imagePreview by bind<RoundedCornerImageView>(R.id.bottom_sheet_message_preview_image)
        val mapViewContainer by bind<FrameLayout>(R.id.mapViewContainer)
        val staticMapImageView by bind<ImageView>(R.id.staticMapImageView)
        val staticMapPinImageView by bind<ImageView>(R.id.staticMapPinImageView)
    }

    companion object {
        // lazy so the ViewOutlineProvider subclass (API 21+) is never loaded pre-21.
        private val ROUNDED_OUTLINE_PROVIDER by lazy {
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, view.resources.displayMetrics)
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
        }
    }
}
