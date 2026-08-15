/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.text.format.DateUtils
import android.text.method.MovementMethod
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setMediaPillColorCompat
import im.vector.app.core.utils.TextUtils
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.home.room.detail.timeline.helper.ContentDownloadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import io.noties.markwon.MarkwonPlugin
import java.util.concurrent.Executors
import kotlin.math.abs

@EpoxyModelClass
abstract class MessageAudioItem : AbsMessageItem<MessageAudioItem.Holder>() {

    @EpoxyAttribute
    var filename: String = ""

    @EpoxyAttribute
    var mxcUrl: String = ""

    @EpoxyAttribute
    var duration: Int = 0

    @EpoxyAttribute
    var fileSize: Long = 0

    @EpoxyAttribute
    var izLocalFile = false

    /**
     * Where the bytes are on this device: the file picked for a send that is still going out, or a
     * downloaded copy. Either can be read for tags and artwork.
     */
    @EpoxyAttribute
    var localSource: Uri? = null

    /** Asked again once playback starts, since that is what downloads the file in the first place. */
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var localSourceProvider: (() -> Uri?)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onSeek: ((percentage: Float) -> Unit)? = null

    @EpoxyAttribute
    lateinit var contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder

    @EpoxyAttribute
    lateinit var contentDownloadStateTrackerBinder: ContentDownloadStateTrackerBinder

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var playbackControlButtonClickListener: ClickListener? = null

    @EpoxyAttribute
    lateinit var audioMessagePlaybackTracker: AudioMessagePlaybackTracker

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

    private var isUserSeeking = false
    private var playbackTrackerListener: AudioMessagePlaybackTracker.Listener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        renderSendState(holder.rootLayout, null)
        bindViewAttributes(holder)
        bindUploadState(holder)
        applyLayoutTint(holder)
        // After the tint: the backdrop replaces the message's background outright, and tinting it
        // afterwards would paint the message's colour over the artwork — transparently, in a bubble.
        bindFileDetails(holder)
        bindSeekBar(holder)
        holder.audioPlaybackControlButton.setOnClickListener { playbackControlButtonClickListener?.invoke(it) }
        renderStateBasedOnAudioPlayback(holder)
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

    private fun bindUploadState(holder: Holder) {
        if (attributes.informationData.sendState.hasFailed()) {
            holder.audioPlaybackControlButton.setImageResource(R.drawable.ic_cross)
            holder.audioPlaybackControlButton.contentDescription =
                    holder.view.context.getString(CommonStrings.error_audio_message_unable_to_play, filename)
            holder.progressLayout.isVisible = false
        } else {
            contentUploadStateTrackerBinder.bind(attributes.informationData.stableId, izLocalFile, holder.progressLayout)
        }
    }

    private fun applyLayoutTint(holder: Holder) {
        val backgroundTint = if (attributes.informationData.messageLayout is TimelineMessageLayout.Bubble) {
            Color.TRANSPARENT
        } else {
            ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_content_quinary)
        }
        holder.mainLayout.setMediaPillColorCompat(backgroundTint)
    }

    /**
     * A music file usually knows more about itself than its name: the tags and the cover come off
     * the file once it is downloaded, and until then it reads as it always did.
     */
    private fun bindFileDetails(holder: Holder) {
        // Playing is what fetches a file that was never downloaded, so the provider is asked again
        // rather than trusting what was known when the row was built.
        val source = localSource ?: localSourceProvider?.invoke()
        // Tracked by message rather than by where its bytes are: an upload's source changes under
        // it — the picked file becomes a cached download, and its local echo becomes a real event —
        // and a row that resets there flickers back to the file name and the plain pill mid-send.
        val id = attributes.informationData.stableId
        val changed = holder.mainLayout.tag != id
        holder.mainLayout.tag = id
        val known = source?.let { AudioDetails.cached(it) }
        showFileDetails(holder, known, reset = changed)
        if (source == null || known != null) return
        val uri = source
        val context = holder.view.context.applicationContext
        detailsLoader.execute {
            val details = AudioDetails.load(context, uri)
            if (details.isEmpty) return@execute
            holder.mainLayout.post {
                // The row may have been recycled onto another message by now.
                if (holder.mainLayout.tag == id) showFileDetails(holder, details, reset = true)
            }
        }
    }

    private fun showFileDetails(holder: Holder, details: AudioDetails.Details?, reset: Boolean) {
        // Nothing to say and nothing to clear: leave the row showing what it already found.
        if (details == null && !reset) return
        holder.filenameView.text = (details?.title ?: filename).prepareForDisplay()
        holder.artistView.text = details?.credits?.prepareForDisplay()
        holder.artistView.isVisible = details?.credits != null
        applyBackdrop(holder, details?.backdrop)
    }

    /** The cover, blurred and darkened, as the message's own background. */
    private fun applyBackdrop(holder: Holder, backdrop: Bitmap?) {
        val context = holder.view.context
        if (backdrop == null) {
            holder.backdropKey = null
            holder.mainLayout.background =
                    ContextCompat.getDrawable(context, im.vector.lib.ui.styles.R.drawable.bg_media_pill)
            applyLayoutTint(holder)
            applyTextColors(holder, onBackdrop = false)
            return
        }
        applyTextColors(holder, onBackdrop = true)
        // Cut to the shape it will be drawn at, which is only known once the message is laid out.
        if (holder.mainLayout.width > 0) setBackdrop(holder, backdrop) else holder.mainLayout.doOnLayout { setBackdrop(holder, backdrop) }
    }

    private fun setBackdrop(holder: Holder, backdrop: Bitmap) {
        val context = holder.view.context
        // Setting a background lays the message out again, which would ask for another backdrop:
        // composing one only when the art or the shape has really changed is what stops that from
        // feeding itself a frame at a time.
        val key = "${System.identityHashCode(backdrop)}-${holder.mainLayout.width}x${holder.mainLayout.height}"
        if (holder.backdropKey == key) return
        holder.backdropKey = key
        // One drawable rather than art with a colour layered over it: the message's own tinting
        // calls mutate() on whatever background it finds, and a LayerDrawable holding a rounded
        // bitmap does not survive that.
        val drawable = RoundedBitmapDrawableFactory
                .create(context.resources, compose(backdrop, holder.mainLayout.width, holder.mainLayout.height))
                .apply { cornerRadius = PILL_CORNER_RADIUS_DP * context.resources.displayMetrics.density }
        // Cleared first: setting a background re-applies whatever tint the view is carrying, and
        // the pill's own tint would paint a flat colour over the artwork.
        ViewCompat.setBackgroundTintList(holder.mainLayout, null)
        holder.mainLayout.background = drawable
    }

    /**
     * Over artwork the message's own text colours cannot be trusted — a light theme's near-black
     * on a darkened cover is unreadable — so everything on it goes white while it is there.
     */
    private fun applyTextColors(holder: Holder, onBackdrop: Boolean) {
        val context = holder.view.context
        val primary = if (onBackdrop) {
            Color.WHITE
        } else {
            ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        }
        val secondary = if (onBackdrop) {
            ON_BACKDROP_SECONDARY
        } else {
            ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        }
        val tertiary = if (onBackdrop) {
            ON_BACKDROP_SECONDARY
        } else {
            ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_tertiary)
        }
        holder.filenameView.setTextColor(primary)
        holder.artistView.setTextColor(secondary)
        holder.audioPlaybackDuration.setTextColor(tertiary)
        holder.fileSize.setTextColor(tertiary)
        holder.audioPlaybackTime.setTextColor(secondary)
        ImageViewCompat.setImageTintList(holder.audioPlaybackControlButton, ColorStateList.valueOf(secondary))
    }

    /**
     * The blurred cover cut to [width] x [height]'s shape — the middle of it, scaled to cover the
     * whole message as a wallpaper would — darkened enough to read white text on.
     */
    private fun compose(backdrop: Bitmap, width: Int, height: Int): Bitmap {
        val aspect = if (width > 0 && height > 0) width.toFloat() / height else DEFAULT_BACKDROP_ASPECT
        val outputWidth = backdrop.width
        val outputHeight = (outputWidth / aspect).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val scale = maxOf(outputWidth.toFloat() / backdrop.width, outputHeight.toFloat() / backdrop.height)
        val scaledWidth = backdrop.width * scale
        val scaledHeight = backdrop.height * scale
        val destination = RectF(
                (outputWidth - scaledWidth) / 2f,
                (outputHeight - scaledHeight) / 2f,
                (outputWidth + scaledWidth) / 2f,
                (outputHeight + scaledHeight) / 2f,
        )
        Canvas(output).apply {
            drawBitmap(backdrop, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
            drawColor(ColorUtils.setAlphaComponent(Color.BLACK, (BACKDROP_SCRIM_ALPHA * 255).toInt()))
        }
        return output
    }

    private fun bindViewAttributes(holder: Holder) {
        val formattedDuration = formatPlaybackTime(duration)
        val formattedFileSize = TextUtils.formatFileSize(holder.rootLayout.context, fileSize, true)
        val durationContentDescription = getPlaybackTimeContentDescription(holder.rootLayout.context, duration)

        holder.filenameView.onClick(attributes.itemClickListener)
        // Set here rather than left to the first playback report, which arrives a beat later and
        // leaves the row with a gap where its time should be.
        holder.audioPlaybackTime.text = formatPlaybackTime(0)
        holder.audioPlaybackDuration.text = formattedDuration
        holder.fileSize.text = holder.rootLayout.context.getString(
                CommonStrings.audio_message_file_size, formattedFileSize
        )
        holder.mainLayout.contentDescription = holder.rootLayout.context.getString(
                CommonStrings.a11y_audio_message_item, filename, durationContentDescription, formattedFileSize
        )
    }

    @Suppress("ClickableViewAccessibility")
    private fun bindSeekBar(holder: Holder) {
        // In milliseconds rather than percent: a hundred steps across a five-minute song is a jump
        // of three seconds at a time, and nothing can glide between those.
        holder.audioSeekBar.max = duration.coerceAtLeast(1)
        // The timeline swipes to reply and the list scrolls, and both will take a drag that began
        // on the bar unless they are told this one is spoken for.
        holder.audioSeekBar.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        holder.audioSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) holder.audioPlaybackTime.text = formatPlaybackTime(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true
                holder.cancelProgressAnimation()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = false
                onSeek?.invoke(seekBar.progress.toFloat() / seekBar.max)
            }
        })
    }

    private fun renderStateBasedOnAudioPlayback(holder: Holder) {
        playbackTrackerListener = AudioMessagePlaybackTracker.Listener { state ->
            when (state) {
                is AudioMessagePlaybackTracker.Listener.State.Error,
                is AudioMessagePlaybackTracker.Listener.State.Idle -> renderIdleState(holder)
                is AudioMessagePlaybackTracker.Listener.State.Playing -> renderPlayingState(holder, state)
                is AudioMessagePlaybackTracker.Listener.State.Paused -> renderPausedState(holder, state)
                is AudioMessagePlaybackTracker.Listener.State.Recording -> Unit
            }
        }.also { audioMessagePlaybackTracker.track(attributes.informationData.stableId, it) }
    }

    private fun renderIdleState(holder: Holder) {
        holder.audioPlaybackControlButton.setImageResource(R.drawable.ic_play_pause_play)
        holder.audioPlaybackControlButton.contentDescription =
                holder.view.context.getString(CommonStrings.a11y_play_audio_message, filename)
        // How long the file is already reads above the bar; this one counts through it.
        holder.audioPlaybackTime.text = formatPlaybackTime(0)
        holder.cancelProgressAnimation()
        holder.audioSeekBar.progress = 0
    }

    private fun renderPlayingState(holder: Holder, state: AudioMessagePlaybackTracker.Listener.State.Playing) {
        holder.audioPlaybackControlButton.setImageResource(R.drawable.ic_play_pause_pause)
        holder.audioPlaybackControlButton.contentDescription =
                holder.view.context.getString(CommonStrings.a11y_pause_audio_message, filename)

        holder.audioPlaybackTime.text = formatPlaybackTime(state.playbackTime)
        renderProgress(holder, state.playbackTime, state.percentage, playing = true)
    }

    private fun renderPausedState(holder: Holder, state: AudioMessagePlaybackTracker.Listener.State.Paused) {
        holder.audioPlaybackControlButton.setImageResource(R.drawable.ic_play_pause_play)
        holder.audioPlaybackControlButton.contentDescription =
                holder.view.context.getString(CommonStrings.a11y_play_audio_message, filename)
        holder.audioPlaybackTime.text = formatPlaybackTime(state.playbackTime)
        renderProgress(holder, state.playbackTime, state.percentage, playing = false)
    }

    /**
     * The media viewer's scrubber, in a message: millisecond resolution plus a linear glide between
     * the reports, the way Telegram interpolates its own; jumps (seeks, loops) snap.
     */
    private fun renderProgress(holder: Holder, positionMs: Int, percentage: Float, playing: Boolean) {
        if (isUserSeeking) return
        val bar = holder.audioSeekBar
        holder.cancelProgressAnimation()
        // The player's own length, for a file that turns out not to be as long as the message
        // claimed. Adopted only when it really differs: the quotient moves by a millisecond or two
        // every report, and a bar whose range keeps changing can never glide across it.
        val reported = if (percentage > 0f) (positionMs / percentage).toInt() else duration
        if (abs(bar.max - reported) > DURATION_TOLERANCE_MS) {
            bar.max = reported.coerceAtLeast(1)
            bar.progress = positionMs
            return
        }
        val delta = positionMs - bar.progress
        if (!playing || delta !in 0..MAX_GLIDE_MS) {
            bar.progress = positionMs
            return
        }
        holder.progressAnimator = ObjectAnimator.ofInt(bar, "progress", positionMs).apply {
            duration = PROGRESS_GLIDE_MS
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun formatPlaybackTime(time: Int) = DateUtils.formatElapsedTime((time / 1000).toLong())

    private fun getPlaybackTimeContentDescription(context: Context, time: Int): String {
        val formattedPlaybackTime = formatPlaybackTime(time)
        val (minutes, seconds) = formattedPlaybackTime.split(":").map { it.toIntOrNull() ?: 0 }
        return context.getString(CommonStrings.a11y_audio_playback_duration, minutes, seconds)
    }

    override fun unbind(holder: Holder) {
        holder.cancelProgressAnimation()
        holder.backdropKey = null
        super.unbind(holder)
        contentUploadStateTrackerBinder.unbind(attributes.informationData.stableId)
        contentDownloadStateTrackerBinder.unbind(mxcUrl)
        playbackTrackerListener?.let { audioMessagePlaybackTracker.untrack(attributes.informationData.stableId, it) }
        playbackTrackerListener = null
    }

    override fun getViewStubId() = STUB_ID

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val rootLayout by bind<ViewGroup>(R.id.messageRootLayout)
        val mainLayout by bind<ViewGroup>(R.id.messageMainInnerLayout)
        val filenameView by bind<TextView>(R.id.messageFilenameView)
        val artistView by bind<TextView>(R.id.messageAudioArtistView)
        val audioPlaybackControlButton by bind<ImageButton>(R.id.audioPlaybackControlButton)
        val audioPlaybackTime by bind<TextView>(R.id.audioPlaybackTime)
        val progressLayout by bind<ViewGroup>(R.id.messageFileUploadProgressLayout)
        val fileSize by bind<TextView>(R.id.fileSize)
        val audioPlaybackDuration by bind<TextView>(R.id.audioPlaybackDuration)
        val audioSeekBar by bind<SeekBar>(R.id.audioSeekBar)
        var progressAnimator: ObjectAnimator? = null
        var backdropKey: String? = null

        fun cancelProgressAnimation() {
            progressAnimator?.cancel()
            progressAnimator = null
        }
        val captionView by bind<AppCompatTextView>(R.id.messageCaptionView)
    }

    companion object {
        private val STUB_ID = R.id.messageContentAudioStub

        /** Dark enough to read white text on, light enough to leave the artwork its colour. */
        private const val BACKDROP_SCRIM_ALPHA = 0.6f

        /** Matches bg_media_pill, which the backdrop stands in for. */
        private const val PILL_CORNER_RADIUS_DP = 12f

        /** One row at a time reads a file, rather than a thread each on a fast scroll. */
        private val detailsLoader = Executors.newSingleThreadExecutor()

        /** White is the text; this is everything under it. */
        private const val ON_BACKDROP_SECONDARY = 0xCCFFFFFF.toInt()

        /** One report's worth of travel, so the bar arrives just as the next one lands. */
        private const val PROGRESS_GLIDE_MS = 120L

        /** Further than a report apart is a jump rather than playback, and jumps snap. */
        private const val MAX_GLIDE_MS = 1_200

        /** How far the length may be out before the bar's range is worth changing. */
        private const val DURATION_TOLERANCE_MS = 1_000

        /** What the message is roughly shaped like before it has been laid out. */
        private const val DEFAULT_BACKDROP_ASPECT = 3.3f
    }
}
