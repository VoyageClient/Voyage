/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.epoxy.bottomsheet

import android.text.method.MovementMethod
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setRedactedTint
import im.vector.app.core.extensions.setSenderNameEmphasis
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.utils.nonScrolling
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.helper.LocationPinProvider
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewView
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.themes.ThemeUtils
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
    var senderColor: Int? = null

    @EpoxyAttribute
    var senderIsColored: Boolean = true

    /** Null until the view model has built it. */
    @EpoxyAttribute
    var preview: MessagePreviewContent? = null

    /** Marks the whole header as belonging to a deleted message, restored or not. */
    @EpoxyAttribute
    var redactedTint: Boolean = false

    @EpoxyAttribute
    lateinit var imageContentRenderer: ImageContentRenderer

    @EpoxyAttribute
    var hideMedia: Boolean = false

    @EpoxyAttribute
    var hideMediaSolidColor: Boolean = false

    @EpoxyAttribute
    var time: String? = null

    @EpoxyAttribute
    var movementMethod: MovementMethod? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    lateinit var locationPinProvider: LocationPinProvider

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    lateinit var richBodyRenderer: RichMessageBodyRenderer

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
        senderColor?.let { holder.sender.setTextColor(it) }
        holder.sender.setSenderNameEmphasis(senderIsColored)

        val content = preview
        if (content == null) {
            holder.preview.unbind()
            holder.preview.isVisible = false
        } else {
            holder.preview.style = MessagePreviewView.Style(
                    mediaMarginTop = (MEDIA_MARGIN_TOP_DP * holder.view.resources.displayMetrics.density).toInt(),
                    movementMethod = movementMethod?.nonScrolling(),
            )
            // The bottom-sheet theme tree doesn't inherit the app theme's textColorHighlight, so give a pressed
            // link a translucent tint matching the link colour here instead of Material's stray teal default.
            holder.preview.textView.highlightColor = ColorUtils.setAlphaComponent(
                    ThemeUtils.getColor(holder.view.context, android.R.attr.textColorLink), 0x40
            )
            holder.preview.bind(
                    content,
                    hidden = hideMedia,
                    hiddenAsSolidColor = hideMediaSolidColor,
                    renderers = MessagePreviewView.Renderers(imageContentRenderer, richBodyRenderer, htmlPostProcessors, locationPinProvider),
            )
        }
        holder.timestamp.setTextOrHide(time)
    }

    override fun unbind(holder: Holder) {
        holder.preview.unbind()
        super.unbind(holder)
    }

    private companion object {
        const val MEDIA_MARGIN_TOP_DP = 6
    }

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.bottom_sheet_message_preview_avatar)
        val sender by bind<TextView>(R.id.bottom_sheet_message_preview_sender)
        val preview by bind<MessagePreviewView>(R.id.bottom_sheet_message_preview_content)
        val timestamp by bind<TextView>(R.id.bottom_sheet_message_preview_timestamp)
    }
}
