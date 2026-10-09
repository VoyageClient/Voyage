/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.preview

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.method.MovementMethod
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.ruesga.rview.widget.ExpandableViewLayout
import im.vector.app.R
import im.vector.app.core.extensions.clearDrawables
import im.vector.app.core.extensions.setRedactedPreviewStyle
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.ui.views.FooteredTextView
import im.vector.app.core.ui.views.ForwardFrameLayout
import im.vector.app.core.ui.views.GalleryGridLayout
import im.vector.app.core.ui.views.RoundedCornerImageView
import im.vector.app.features.home.room.detail.timeline.helper.LocationPinProvider
import im.vector.app.features.home.room.detail.timeline.item.GalleryGridBinder
import im.vector.app.features.home.room.detail.timeline.item.MediaPillPreviewBinder
import im.vector.app.features.home.room.detail.timeline.item.MediaPillPreviewView
import im.vector.app.features.home.room.detail.timeline.item.PollLayout
import im.vector.app.features.home.room.detail.timeline.item.PollOptionView
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent.Media
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent.TextStyle
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.style.mediaPreviewCornerRadiusPx
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.bindEmoteImageSpans
import im.vector.app.features.html.bindPillImageSpans
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaPlaceholderDrawable
import im.vector.app.features.themes.ThemeUtils

/**
 * A [MessagePreviewContent]: its media (inside a forward's frame), then its text or table, faded out past
 * a height cap. The surfaces around it draw their own sender, avatar and controls.
 */
class MessagePreviewView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** What differs between the surfaces that show a preview. */
    data class Style(
            /** Fill the parent's width rather than hug the content. */
            val fillWidth: Boolean = true,
            val mediaMarginTop: Int = 0,
            /** Players are laid out at timeline width and scaled down whole. */
            val playerScale: Float = 1f,
            /** Stretch block code to the full width, as the timeline does outside bubbles. */
            val fullWidthBlockCode: Boolean = false,
            /** Hidden thumbnails drop their caption, which would be illegible beside a blur. */
            val hideCaptionWithHiddenMedia: Boolean = false,
            /** Null leaves links, pills and spoilers inert, so a tap reaches the host view. */
            val movementMethod: MovementMethod? = null,
            /** The file name and details lines under the preview. */
            val showAttachmentInfo: Boolean = true,
            /** Size a poll to its content rather than the preview's width. */
            val compactPolls: Boolean = false,
    )

    class Renderers(
            val imageContentRenderer: ImageContentRenderer,
            val richBodyRenderer: RichMessageBodyRenderer,
            val htmlPostProcessors: Array<EventHtmlRenderer.PostProcessor>,
            val locationPinProvider: LocationPinProvider,
    )

    private val media: ForwardFrameLayout
    private val thumbnail: RoundedCornerImageView
    private val gallery: GalleryGridLayout
    private val pill: MediaPillPreviewView
    private val expandable: ExpandableViewLayout
    private val column: LinearLayout
    val textView: FooteredTextView
    private val rich: LinearLayout
    private val poll: PollLayout
    private val pollQuestion: TextView
    private val pollEnded: TextView
    private val pollIcon: View
    private val pollOptions: LinearLayout
    private val pollVotes: TextView
    private val attachmentName: TextView
    private val details: TextView
    private val map: View
    private val mapImage: RoundedCornerImageView
    private val mapPin: ImageView

    private var boundImageRenderer: ImageContentRenderer? = null

    var onRichClick: ((View) -> Unit)? = null
    var onRichLongClick: ((View) -> Boolean)? = null

    var style: Style = Style()
        set(value) {
            field = value
            applyStyle()
        }

    init {
        orientation = VERTICAL
        inflate(context, R.layout.view_message_preview, this)
        media = findViewById(R.id.messagePreviewMedia)
        thumbnail = findViewById(R.id.messagePreviewThumbnail)
        gallery = findViewById(R.id.messagePreviewGallery)
        pill = findViewById(R.id.messagePreviewPill)
        expandable = findViewById(R.id.messagePreviewExpandable)
        column = findViewById(R.id.messagePreviewColumn)
        textView = findViewById(R.id.messagePreviewText)
        rich = findViewById(R.id.messagePreviewRich)
        poll = findViewById(R.id.messagePreviewPoll)
        pollQuestion = poll.findViewById(R.id.questionTextView)
        pollEnded = poll.findViewById(R.id.endedPollTextView)
        pollIcon = poll.findViewById(R.id.timelinePollIcon)
        pollOptions = poll.findViewById(R.id.optionsContainer)
        pollVotes = poll.findViewById(R.id.optionsVotesStatusTextView)
        attachmentName = findViewById(R.id.messagePreviewAttachmentName)
        details = findViewById(R.id.messagePreviewDetails)
        map = findViewById(R.id.messagePreviewMap)
        mapImage = findViewById(R.id.messagePreviewMapImage)
        mapPin = findViewById(R.id.messagePreviewMapPin)
        // The views round the pictures: Glide's corners only reach a loaded bitmap, never the placeholder.
        thumbnail.setCornerRadius(mediaPreviewCornerRadiusPx(context).toFloat())
        mapImage.setCornerRadius(mediaPreviewCornerRadiusPx(context).toFloat())
        applyStyle()
    }

    private fun applyStyle() {
        val width = if (style.fillWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        listOf(media, pill, expandable, column, textView).forEach { view -> view.updateLayoutParams { this.width = width } }
        listOf(thumbnail, gallery, pill).forEach { view -> view.updateLayoutParams<MarginLayoutParams> { topMargin = style.mediaMarginTop } }
        pill.playerScale = style.playerScale
        textView.fullWidthBlockCode = style.fullWidthBlockCode
    }

    /** [hidden]: the media is hidden per the room's media-preview setting, as a blurhash or a [hiddenAsSolidColor] fill. */
    fun bind(content: MessagePreviewContent, hidden: Boolean, hiddenAsSolidColor: Boolean, renderers: Renderers) {
        isVisible = true
        bindMedia(content, hidden, hiddenAsSolidColor, renderers.imageContentRenderer, crossFade = false)
        val showsMedia = thumbnail.isVisible || gallery.isVisible || pill.isVisible
        media.setForwardFrame(content.forwardFrame?.takeIf { showsMedia })

        val segments = content.richSegments
        val pollContent = content.poll
        poll.isVisible = pollContent != null
        val location = content.location
        map.isVisible = location != null
        if (location != null) {
            setText(null)
            clearRich()
            bindMap(location, renderers.locationPinProvider)
        } else if (pollContent != null) {
            setText(null)
            clearRich()
            bindPoll(pollContent)
        } else if (segments != null) {
            setText(null)
            rich.isVisible = true
            renderers.richBodyRenderer.render(
                    container = rich,
                    segments = segments,
                    postProcessors = renderers.htmlPostProcessors,
                    movementMethod = style.movementMethod,
                    onClick = { onRichClick?.invoke(it) },
                    onLongClick = { onRichLongClick?.invoke(it) ?: false },
                    noticeStyle = content.textStyle == TextStyle.NOTICE,
                    footer = content.richFooter?.charSequence,
                    interactive = false,
            )
        } else {
            clearRich()
            val dropsCaption = hidden && style.hideCaptionWithHiddenMedia && content.media is Media.Image
            val text = (if (dropsCaption) content.captionlessText else content.text)?.charSequence
            if (showsMedia && text.isNullOrEmpty()) {
                setText(null)
            } else {
                textView.isVisible = true
                renderers.richBodyRenderer.setTextWithPlugins(textView, text?.prepareForDisplay())
                // Markwon installs its own LinkMovementMethod when the view has none.
                textView.movementMethod = style.movementMethod
                textView.bindPillImageSpans()
                textView.bindEmoteImageSpans()
                applyTextStyle(content.textStyle)
            }
        }
        if (location == null) clearMap()
        expandable.isVisible = textView.isVisible || rich.isVisible || poll.isVisible || map.isVisible
        // A map is small and fixed in size, and cut short it would lose its pin.
        expandable.setExpanded(location != null)
        // On their own lines, so a name like "Screenshot-…@2x.png" is never linkified as an e-mail address.
        attachmentName.setTextOrHide(content.attachmentName?.takeIf { style.showAttachmentInfo }?.prepareForDisplay())
        details.setTextOrHide(content.details?.takeIf { style.showAttachmentInfo })
    }

    // As PollItem binds the same layout in the timeline, minus voting.
    private fun bindPoll(content: MessagePreviewContent.Poll) {
        poll.setSizing(compact = style.compactPolls)
        pollQuestion.text = content.question.charSequence.prepareForDisplay()
        pollEnded.isVisible = content.ended
        pollIcon.isVisible = false
        pollVotes.setTextOrHide(content.votesStatus)
        while (pollOptions.childCount < content.options.size) pollOptions.addView(PollOptionView(context))
        while (pollOptions.childCount > content.options.size) pollOptions.removeViewAt(0)
        content.options.forEachIndexed { index, option -> (pollOptions.getChildAt(index) as PollOptionView).render(option) }
    }

    // As the timeline's location item does: the media placeholder until the map lands, then the pin.
    private fun bindMap(location: MessagePreviewContent.Location, pinProvider: LocationPinProvider) {
        if (mapImage.getTag(R.id.static_map_location_url) == location.mapUrl) return
        mapImage.setTag(R.id.static_map_location_url, location.mapUrl)
        mapPin.setImageDrawable(null)
        GlideApp.with(mapImage)
                .load(location.mapUrl)
                .placeholder(MediaPlaceholderDrawable(context, showGlyph = false))
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean) = false

                    override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean,
                    ): Boolean {
                        pinProvider.create(location.pinUser) { pin ->
                            if (mapImage.getTag(R.id.static_map_location_url) == location.mapUrl) mapPin.setImageDrawable(pin)
                        }
                        return false
                    }
                })
                .into(mapImage)
    }

    private fun clearMap() {
        if (mapImage.getTag(R.id.static_map_location_url) == null) return
        mapImage.setTag(R.id.static_map_location_url, null)
        GlideApp.with(mapImage).clear(mapImage)
        mapPin.setImageDrawable(null)
        map.isVisible = false
    }

    private fun hideExtras() {
        clearMap()
        poll.isVisible = false
        attachmentName.isVisible = false
        details.isVisible = false
    }

    /** Re-renders only the media, e.g. once it is revealed, cross-fading from its placeholder. */
    fun refreshMedia(content: MessagePreviewContent, hidden: Boolean, hiddenAsSolidColor: Boolean, imageContentRenderer: ImageContentRenderer) {
        bindMedia(content, hidden, hiddenAsSolidColor, imageContentRenderer, crossFade = true)
    }

    /** A line of text in place of a message, such as a loading or error notice. */
    fun showText(text: CharSequence, style: TextStyle) {
        isVisible = true
        clearMedia()
        hideExtras()
        clearRich()
        textView.isVisible = true
        textView.movementMethod = null
        textView.text = text
        applyTextStyle(style)
        expandable.isVisible = true
        expandable.setExpanded(false)
    }

    fun unbind() {
        clearMedia()
        hideExtras()
        setText(null)
        clearRich()
    }

    private fun clearRich() {
        rich.isVisible = false
        rich.removeAllViews()
    }

    private fun bindMedia(
            content: MessagePreviewContent,
            hidden: Boolean,
            hiddenAsSolidColor: Boolean,
            renderer: ImageContentRenderer,
            crossFade: Boolean,
    ) {
        if (boundImageRenderer != null && boundImageRenderer !== renderer) clearMedia()
        boundImageRenderer = renderer
        when (val item = content.media) {
            is Media.Image -> {
                thumbnail.isVisible = true
                // Full image for transparent-capable content: server thumbnails can bake in a background.
                val mode = ImageContentRenderer.previewMode(item.isSticker, item.data.mimeType)
                if (hidden) {
                    renderer.renderHidden(item.data, mode, thumbnail, hiddenAsSolidColor)
                } else {
                    // The view rounds the picture; a bitmap-baked radius would scale with the decode size.
                    renderer.render(item.data, mode, thumbnail, cornerTransformation = null, crossFade = crossFade)
                }
            }
            else -> {
                renderer.clear(thumbnail)
                thumbnail.isVisible = false
            }
        }
        val galleryItem = content.media as? Media.Gallery
        if (galleryItem != null) {
            gallery.isVisible = true
            GalleryGridBinder.bind(
                    grid = gallery,
                    tiles = galleryItem.tiles,
                    maxWidth = galleryItem.maxWidth,
                    cornerRadiusPx = (GALLERY_CORNER_RADIUS_DP * resources.displayMetrics.density).toInt(),
                    imageContentRenderer = renderer,
                    hideMedia = hidden,
                    hideMediaSolidColor = hiddenAsSolidColor,
            )
        } else {
            GalleryGridBinder.unbind(gallery, renderer)
            gallery.isVisible = false
        }
        MediaPillPreviewBinder.bind(pill, (content.media as? Media.Pill)?.pill)
    }

    private fun clearMedia() {
        boundImageRenderer?.let { renderer ->
            renderer.clear(thumbnail)
            GalleryGridBinder.unbind(gallery, renderer)
        }
        boundImageRenderer = null
        thumbnail.isVisible = false
        gallery.isVisible = false
        MediaPillPreviewBinder.bind(pill, null)
        media.setForwardFrame(null)
    }

    private fun setText(text: CharSequence?) {
        // Cleared, not just hidden: the expandable measures this child even while it is gone, so stale
        // text would bring the fade back over a thumbnail.
        textView.text = text
        textView.isVisible = text != null
        textView.clearDrawables()
    }

    private fun applyTextStyle(style: TextStyle) {
        when (style) {
            TextStyle.REDACTED -> textView.setRedactedPreviewStyle()
            TextStyle.NOTICE -> {
                textView.setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
                textView.clearDrawables()
            }
            TextStyle.NORMAL -> {
                textView.setTextColor(ThemeUtils.getMessageTextColor(context))
                textView.clearDrawables()
            }
        }
    }

    private companion object {
        const val GALLERY_CORNER_RADIUS_DP = 8
    }
}
