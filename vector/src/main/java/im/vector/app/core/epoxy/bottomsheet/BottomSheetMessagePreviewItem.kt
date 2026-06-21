/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.epoxy.bottomsheet

import android.graphics.Outline
import android.os.Build
import android.text.method.MovementMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
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
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.action.LocationUiData
import im.vector.app.features.home.room.detail.timeline.item.BindingOptions
import im.vector.app.features.home.room.detail.timeline.tools.findPillsAndProcess
import im.vector.app.features.media.ImageContentRenderer
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
        // ViewOutlineProvider / clipToOutline are API 21+; pre-21 the preview renders square corners.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                holder.imagePreview.outlineProvider !== ROUNDED_OUTLINE_PROVIDER) {
            holder.imagePreview.outlineProvider = ROUNDED_OUTLINE_PROVIDER
            holder.imagePreview.clipToOutline = true
        }
        data?.let {
            if (hideMedia) {
                imageContentRenderer?.renderHidden(it, ImageContentRenderer.Mode.THUMBNAIL, holder.imagePreview, hideMediaSolidColor)
            } else {
                imageContentRenderer?.render(it, ImageContentRenderer.Mode.THUMBNAIL, holder.imagePreview)
            }
        }
        holder.imagePreview.isVisible = data != null
        holder.body.movementMethod = movementMethod
        holder.body.text = body.charSequence
        holder.bodyDetails.setTextOrHide(bodyDetails?.charSequence)
        body.charSequence.findPillsAndProcess(coroutineScope) { it.bind(holder.body) }
        holder.timestamp.setTextOrHide(time)

        holder.body.isVisible = locationUiData == null
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
        val bodyDetails by bind<TextView>(R.id.bottom_sheet_message_preview_body_details)
        val timestamp by bind<TextView>(R.id.bottom_sheet_message_preview_timestamp)
        val imagePreview by bind<ImageView>(R.id.bottom_sheet_message_preview_image)
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
