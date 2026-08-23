/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.graphics.Color
import android.graphics.Paint
import android.text.method.MovementMethod
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setMediaPillColorCompat
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.ContentDownloadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.style.drawsBubbleBackground
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlView
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlViewUpdater
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import io.noties.markwon.MarkwonPlugin

@EpoxyModelClass
abstract class MessageFileItem : AbsMessageItem<MessageFileItem.Holder>() {

    @EpoxyAttribute
    var filename: String = ""

    @EpoxyAttribute
    var mxcUrl: String = ""

    @EpoxyAttribute
    @DrawableRes
    var iconRes: Int = 0

    @EpoxyAttribute
    var izLocalFile = false

    @EpoxyAttribute
    var izDownloaded = false

    @EpoxyAttribute
    lateinit var contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder

    @EpoxyAttribute
    lateinit var contentDownloadStateTrackerBinder: ContentDownloadStateTrackerBinder

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

    @EpoxyAttribute
    var previewUrlImageContentRenderer: ImageContentRenderer? = null

    private val previewUrlViewUpdater = PreviewUrlViewUpdater()

    override fun bind(holder: Holder) {
        super.bind(holder)
        renderSendState(holder.fileLayout, holder.filenameView)

        if (!attributes.informationData.sendState.hasFailed()) {
            contentUploadStateTrackerBinder.bind(attributes.informationData.stableId, izLocalFile, holder.progressLayout)
        } else {
            holder.fileImageView.setImageResource(R.drawable.ic_cross)
            holder.progressLayout.isVisible = false
        }

        holder.filenameView.text = filename.prepareForDisplay()

        if (attributes.informationData.sendState.isSending()) {
            holder.fileImageView.setImageResource(iconRes)
        } else {
            if (izDownloaded) {
                holder.fileImageView.setImageResource(iconRes)
                holder.fileDownloadProgress.progress = 0
            } else {
                contentDownloadStateTrackerBinder.bind(mxcUrl, holder)
                holder.fileImageView.setImageResource(R.drawable.ic_download)
            }
        }

        val backgroundTint = if (attributes.informationData.messageLayout.drawsBubbleBackground) {
            Color.TRANSPARENT
        } else {
            ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_content_quinary)
        }
        holder.mainLayout.setMediaPillColorCompat(backgroundTint)
        holder.filenameView.onClick(attributes.itemClickListener)
        holder.filenameView.setOnLongClickListener(attributes.itemLongClickListener)
        holder.fileImageWrapper.onClick(attributes.itemClickListener)
        holder.fileImageWrapper.setOnLongClickListener(attributes.itemLongClickListener)
        // The icon + filename only cover part of the row; make the whole pill (incl. its padding) open
        // the file, and the full-width row long-pressable, so a press beside the text still works.
        holder.mainLayout.onClick(attributes.itemClickListener)
        holder.mainLayout.setOnLongClickListener(attributes.itemLongClickListener)
        holder.fileLayout.setOnLongClickListener(attributes.itemLongClickListener)
        holder.filenameView.paintFlags = (holder.filenameView.paintFlags or Paint.UNDERLINE_TEXT_FLAG)

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
                imageContentRenderer = previewUrlImageContentRenderer,
                stableId = attributes.informationData.stableId,
                messageLayout = attributes.informationData.messageLayout,
        )
    }

    override fun unbind(holder: Holder) {
        previewUrlViewUpdater.unbind()
        super.unbind(holder)
        contentUploadStateTrackerBinder.unbind(attributes.informationData.stableId)
        contentDownloadStateTrackerBinder.unbind(mxcUrl)
    }

    override fun getViewStubId() = STUB_ID

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val mainLayout by bind<ViewGroup>(R.id.messageFileMainLayout)
        val progressLayout by bind<ViewGroup>(R.id.messageFileUploadProgressLayout)
        val fileLayout by bind<ViewGroup>(R.id.messageFileLayout)
        val fileImageView by bind<ImageView>(R.id.messageFileIconView)
        val fileImageWrapper by bind<ViewGroup>(R.id.messageFileImageView)
        val fileDownloadProgress by bind<ProgressBar>(R.id.messageFileProgressbar)
        val filenameView by bind<TextView>(R.id.messageFilenameView)
        val captionView by bind<AppCompatTextView>(R.id.messageCaptionView)
        val previewUrlView by bind<PreviewUrlView>(R.id.messageUrlPreview)
    }

    companion object {
        private val STUB_ID = R.id.messageContentFileStub
    }
}
