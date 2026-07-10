/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.graphics.Outline
import android.text.method.MovementMethod
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.ui.PerformanceMode
import im.vector.app.core.files.LocalFilesHelper
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.ui.views.RoundedCornerImageView
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.style.granularRoundedCorners
import im.vector.app.core.ui.views.AbstractFooteredTextView
import im.vector.app.features.home.room.detail.timeline.view.ScMessageBubbleWrapView
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import io.noties.markwon.MarkwonPlugin
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import im.vector.app.core.extensions.backgroundCompat

@EpoxyModelClass
abstract class MessageImageVideoItem : AbsMessageItem<MessageImageVideoItem.Holder>() {

    @EpoxyAttribute
    lateinit var mediaData: ImageContentRenderer.Data

    @EpoxyAttribute
    var playable: Boolean = false

    @EpoxyAttribute
    var mode = ImageContentRenderer.Mode.THUMBNAIL

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var clickListener: ClickListener? = null

    @EpoxyAttribute
    lateinit var imageContentRenderer: ImageContentRenderer

    @EpoxyAttribute
    var hideMedia: Boolean = false

    @EpoxyAttribute
    var hiddenMediaSolidColor: Boolean = false

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    lateinit var mediaRevealManager: MediaContentRevealManager

    @EpoxyAttribute
    lateinit var contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder

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

    override fun bind(holder: Holder) {
        super.bind(holder)
        val messageLayout = baseAttributes.informationData.messageLayout
        val dimensionConverter = DimensionConverter(holder.view.resources)
        val isBubble = messageLayout is TimelineMessageLayout.Bubble
        // Round the image to the same radius as its surrounding bubble border, else (e.g. SC's 3dp
        // border vs a hardcoded 8dp image) the corners don't match and leave a gap. Falls back to 8dp
        // outside bubbles.
        val cornerPx = (messageLayout as? TimelineMessageLayout.ScBubble)?.bubbleAppearance?.getBubbleRadiusPx(holder.view.context)
                ?: dimensionConverter.dpToPx(8)
        val imageCornerTransformation = if (isBubble) {
            (messageLayout as TimelineMessageLayout.Bubble).cornersRadius.granularRoundedCorners()
        } else {
            RoundedCorners(cornerPx)
        }
        // Bubble layout already clips at the MessageBubbleView level. For non-bubble we apply a
        // view-level outline clip too, so animated drawables (FrameAnimationDrawable / animated
        // WebP / APNG / GIF) get the same rounded corners — Glide's RoundedCorners is a Bitmap-only
        // Transformation and is silently skipped for those.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            if (!isBubble) {
                val r = cornerPx.toFloat()
                holder.imageView.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, r)
                    }
                }
                holder.imageView.clipToOutline = true
                holder.imageView.tag = r
            } else {
                holder.imageView.outlineProvider = ViewOutlineProvider.BACKGROUND
                holder.imageView.clipToOutline = false
                holder.imageView.tag = 0f
            }
        } else if (PerformanceMode.enabled) {
            // The pre-Lollipop clip runs on a per-frame software layer. In performance mode skip it:
            // static thumbnails are already rounded by Glide; animated ones just show square corners.
            holder.imageView.setCornerRadii(0f, 0f, 0f, 0f)
        } else {
            // clipToOutline is API 21+: backport the same view-level clip with identical radii so
            // animated drawables round on KitKat too (static bitmaps are already rounded by Glide).
            if (isBubble) {
                val radius = (messageLayout as TimelineMessageLayout.Bubble).cornersRadius
                holder.imageView.setCornerRadii(radius.topStartRadius, radius.topEndRadius, radius.bottomEndRadius, radius.bottomStartRadius)
            } else {
                val r = cornerPx.toFloat()
                holder.imageView.setCornerRadii(r, r, r, r)
            }
        }
        val isImageMessage = attributes.informationData.messageType in listOf(MessageType.MSGTYPE_IMAGE, MessageType.MSGTYPE_STICKER_LOCAL)
        val hidden = hideMedia && !mediaRevealManager.isRevealed(mediaData.eventId)
        if (hidden) {
            imageContentRenderer.renderHidden(mediaData, mode, holder.imageView, hiddenMediaSolidColor)
        } else {
            imageContentRenderer.render(mediaData, mode, holder.imageView, imageCornerTransformation)
        }
        holder.mediaHiddenScrim.isVisible = hidden
        holder.mediaHiddenScrim.alpha = 1f
        holder.mediaShowButton.isVisible = hidden
        if (hidden) {
            val reveal = View.OnClickListener {
                mediaRevealManager.reveal(mediaData.eventId)
                holder.mediaShowButton.isVisible = false
                // Render the real content underneath, then fade the dark scrim away to it.
                imageContentRenderer.render(mediaData, mode, holder.imageView, imageCornerTransformation)
                ViewCompat.animate(holder.mediaHiddenScrim)
                        .alpha(0f)
                        .setDuration(SCRIM_FADE_OUT_MS)
                        .withEndAction {
                            holder.mediaHiddenScrim.isVisible = false
                            holder.mediaHiddenScrim.alpha = 1f
                        }
                bindPlayButton(holder, isImageMessage, hidden = false)
            }
            holder.mediaShowButton.setOnClickListener(reveal)
            holder.mediaHiddenScrim.setOnClickListener(reveal)
        } else {
            holder.mediaShowButton.setOnClickListener(null)
            holder.mediaHiddenScrim.setOnClickListener(null)
        }
        if (!attributes.informationData.sendState.hasFailed()) {
            contentUploadStateTrackerBinder.bind(
                    attributes.informationData.eventId,
                    LocalFilesHelper(holder.view.context).isLocalFile(mediaData.url),
                    holder.progressLayout
            )
        } else {
            holder.progressLayout.isVisible = false
        }
        holder.imageView.onClick(clickListener)
        holder.imageView.setOnLongClickListener(attributes.itemLongClickListener)
        ViewCompat.setTransitionName(holder.imageView, "imagePreview_${id()}")
        holder.mediaContentView.onClick(attributes.itemClickListener)
        holder.mediaContentView.setOnLongClickListener(attributes.itemLongClickListener)

        bindPlayButton(holder, isImageMessage, hidden)

        MediaCaptionBinder.bind(
                view = holder.captionView,
                caption = caption,
                bindingOptions = captionBindingOptions,
                movementMethod = captionMovementMethod,
                itemLongClickListener = attributes.itemLongClickListener,
                markwonPlugins = captionMarkwonPlugins,
                useBigFont = captionUseBigFont,
        )
    }

    private fun bindPlayButton(holder: Holder, isImageMessage: Boolean, hidden: Boolean) {
        holder.playContentView.visibility = when {
            hidden -> View.GONE
            playable && isImageMessage && attributes.autoplayAnimatedImages -> View.GONE
            playable -> View.VISIBLE
            else -> View.GONE
        }
    }

    override fun unbind(holder: Holder) {
        GlideApp.with(holder.view.context.applicationContext).clear(holder.imageView)
        imageContentRenderer.clear(holder.imageView)
        contentUploadStateTrackerBinder.unbind(attributes.informationData.eventId)
        holder.imageView.setOnClickListener(null)
        holder.imageView.setOnLongClickListener(null)
        holder.mediaShowButton.setOnClickListener(null)
        holder.mediaHiddenScrim.setOnClickListener(null)
        holder.mediaHiddenScrim.animate().cancel()
        holder.mediaHiddenScrim.alpha = 1f
        super.unbind(holder)
    }

    override fun getViewStubId() = STUB_ID

    // No caption: overlay the timestamp chip on the media. With a caption: overlay it inline on the caption
    // text (reserving space) so it sits next to short captions and drops below long ones.
    override fun allowFooterOverlay(holder: Holder, bubbleWrapView: ScMessageBubbleWrapView): Boolean = true

    override fun allowFooterBelow(holder: Holder): Boolean = false

    override fun needsFooterReservation(): Boolean = caption != null

    // No caption: the timestamp overlays the image, so anchor it to the image's right edge (the bubble
    // can be wider when a reply header is). With a caption the footer overlays the caption instead.
    override fun footerOverlayAnchorView(holder: Holder): android.view.View? = if (caption == null) holder.imageView else null

    override fun reserveFooterSpace(holder: Holder, width: Int, height: Int) {
        (holder.captionView as? AbstractFooteredTextView)?.apply {
            footerWidth = width
            footerHeight = height
            getAppCompatTextView().requestLayout()
        }
    }

    override fun applyScBubbleStyle(messageLayout: TimelineMessageLayout.ScBubble, holder: Holder) {
        if ((messageLayout.isRealBubble || messageLayout.isPseudoBubble) && mode == ImageContentRenderer.Mode.THUMBNAIL) {
            if (attributes.informationData.sentByMe) {
                holder.mediaContentView.setBackgroundResource(messageLayout.bubbleAppearance.imageBorderOutgoing)
            } else {
                holder.mediaContentView.setBackgroundResource(messageLayout.bubbleAppearance.imageBorderIncoming)
            }
        } else {
            holder.mediaContentView.backgroundCompat = null
        }
    }

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val progressLayout by bind<ViewGroup>(R.id.messageMediaUploadProgressLayout)
        val imageView by bind<RoundedCornerImageView>(R.id.messageThumbnailView)
        val playContentView by bind<ImageView>(R.id.messageMediaPlayView)
        val mediaHiddenScrim by bind<View>(R.id.messageMediaHiddenScrim)
        val mediaShowButton by bind<AppCompatTextView>(R.id.messageMediaShowButton)
        val mediaContentView by bind<ViewGroup>(R.id.messageContentMedia)
        val captionView by bind<AppCompatTextView>(R.id.messageCaptionView)
    }

    companion object {
        private const val SCRIM_FADE_OUT_MS = 200L
        private val STUB_ID = R.id.messageContentMediaStub
    }
}
