/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.SystemClock
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
import androidx.core.graphics.get
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.setMediaPillColorCompat
import im.vector.app.core.ui.PerformanceMode
import im.vector.app.core.utils.TextUtils
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.home.room.detail.timeline.helper.ContentDownloadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.bubbleContentMaxWidth
import im.vector.app.features.home.room.detail.timeline.style.drawsBubbleBackground
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlView
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlViewUpdater
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import io.noties.markwon.MarkwonPlugin
import org.matrix.android.sdk.api.session.room.model.message.AudioMetadata
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
     * What the sender said the track is (MSC4549). Believed over anything the file on this device
     * says about itself: the sender is describing what they sent.
     */
    @EpoxyAttribute
    var audioMetadata: AudioMetadata? = null

    /** Blocking; fetches the event's cover art image and hashes it, for when it sent no usable BlurHash. */
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var coverArtHashLoader: ((Context) -> AudioDetails.CoverArtHash?)? = null

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

    private var isUserSeeking = false

    private val previewUrlViewUpdater = PreviewUrlViewUpdater()

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
        val backgroundTint = if (attributes.informationData.messageLayout.drawsBubbleBackground) {
            Color.TRANSPARENT
        } else {
            ThemeUtils.getColor(holder.view.context, im.vector.lib.ui.styles.R.attr.vctr_content_quinary)
        }
        holder.mainLayout.setMediaPillColorCompat(backgroundTint)
    }

    /**
     * A music file usually knows more about itself than its name. The sender may have said what it
     * is (MSC4549); whatever it left out, the cover included, comes off the file once it is
     * downloaded. Until then it reads as it always did.
     */
    private fun bindFileDetails(holder: Holder, onlyIfSourceChanged: Boolean = false) {
        // Tracked by message rather than by where its bytes are: an upload's source changes under
        // it — the picked file becomes a cached download, and its local echo becomes a real event —
        // and a row that resets there flickers back to the file name and the plain pill mid-send.
        val id = attributes.informationData.stableId
        val changed = holder.mainLayout.tag != id
        holder.mainLayout.tag = id
        if (changed) {
            holder.detailsSource = null
            holder.loadingDetailsSource = null
            holder.coverFromFile = false
        }
        val metadata = audioMetadata
        if (metadata != null) {
            if (changed || !onlyIfSourceChanged) holder.coverFromFile = !bindEventDetails(holder, metadata)
            // The event's tags stand; only the backdrop comes off the file, when the event has none to give.
            if (!holder.coverFromFile) return
        }
        // Playing is what fetches a file that was never downloaded, so the provider is asked again
        // rather than trusting what was known when the row was built.
        val source = localSource ?: localSourceProvider?.invoke()
        if (onlyIfSourceChanged && (holder.detailsSource == source || holder.loadingDetailsSource == source)) return
        val known = source?.let { AudioDetails.cached(it, forTimeline = true) }
        showFileDetails(holder, known.withEventTags(), reset = changed)
        if (source == null) return
        if (known != null) {
            holder.detailsSource = source
            return
        }
        val uri = source
        holder.loadingDetailsSource = uri
        val context = holder.view.context.applicationContext
        detailsLoader.execute {
            val (details, fresh) = AudioDetails.loadTracked(context, uri, forTimeline = true)
            holder.mainLayout.post {
                // The row may have been recycled onto another message by now.
                if (holder.mainLayout.tag == id && holder.loadingDetailsSource == uri) {
                    holder.loadingDetailsSource = null
                    holder.detailsSource = uri
                    if (!details.isEmpty) showFileDetails(holder, details.withEventTags(), reset = true, fade = fresh)
                }
            }
        }
    }

    /**
     * What the event says. A known BlurHash is decoded here rather than handed off to a thread: at the
     * size a pill stretches it to that is a fraction of a frame, and a backdrop that arrives later
     * lands under a message the eye has already settled on. False when the event has no cover to
     * give, so the file's embedded art is used instead.
     */
    private fun bindEventDetails(holder: Holder, metadata: AudioMetadata): Boolean {
        val hash = metadata.coverArtBlurhash?.takeIf { it.isNotBlank() }
                ?: metadata.coverArtUrl?.let { AudioDetails.cachedCoverArtHash(it) }
        val backdrop = hash?.let { AudioDetails.coverArtBackdrop(it) }
        showFileDetails(holder, metadata.toDetails(backdrop), reset = true)
        if (backdrop != null) return true
        val loader = coverArtHashLoader?.takeIf { metadata.coverArtUrl != null } ?: return false
        loadCoverArt(holder, metadata, loader)
        return true
    }

    /** Falls back to the file's embedded art when the event's cover cannot be fetched or decoded. */
    private fun loadCoverArt(holder: Holder, metadata: AudioMetadata, loader: (Context) -> AudioDetails.CoverArtHash?) {
        val id = attributes.informationData.stableId
        val context = holder.view.context.applicationContext
        coverArtLoader.execute {
            val hash = loader(context)
            val backdrop = hash?.let { AudioDetails.coverArtBackdrop(it.hash) }
            holder.mainLayout.post {
                if (holder.mainLayout.tag != id) return@post
                if (backdrop != null) {
                    showFileDetails(holder, metadata.toDetails(backdrop), reset = true, fade = hash.fresh)
                } else {
                    holder.coverFromFile = true
                    bindFileDetails(holder, onlyIfSourceChanged = true)
                }
            }
        }
    }

    private fun AudioDetails.Details?.withEventTags(): AudioDetails.Details? =
            audioMetadata?.toDetails(this?.backdrop) ?: this

    private fun AudioMetadata.toDetails(backdrop: Bitmap?) = AudioDetails.Details(
            title = title?.takeIf { it.isNotBlank() },
            artist = artist?.takeIf { it.isNotBlank() },
            album = album?.takeIf { it.isNotBlank() },
            art = null,
            backdrop = backdrop,
    )

    /** [fade] is for a backdrop generated just now, so it arrives the way timeline media does. */
    private fun showFileDetails(holder: Holder, details: AudioDetails.Details?, reset: Boolean, fade: Boolean = false) {
        // Nothing to say and nothing to clear: leave the row showing what it already found.
        if (details == null && !reset) return
        holder.filenameView.text = (details?.title ?: filename).prepareForDisplay()
        holder.artistView.text = details?.credits?.prepareForDisplay()
        holder.artistView.isVisible = details?.credits != null
        applyBackdrop(holder, details?.backdrop, fade && !PerformanceMode.enabled)
    }

    /** The cover, blurred and darkened, as the message's own background. */
    private fun applyBackdrop(holder: Holder, backdrop: Bitmap?, fade: Boolean) {
        val context = holder.view.context
        if (backdrop == null) {
            holder.backdropKey = null
            holder.mainLayout.backgroundCompat =
                    ContextCompat.getDrawable(context, im.vector.lib.ui.styles.R.drawable.bg_media_pill)
            applyLayoutTint(holder)
            applyTextColors(holder, onBackdrop = false)
            return
        }
        applyTextColors(holder, onBackdrop = true, fade = fade)
        // Cut to the shape it will be drawn at, which is only known once the message is laid out.
        if (holder.mainLayout.width > 0) {
            setBackdrop(holder, backdrop, fade)
        } else {
            holder.mainLayout.doOnLayout { setBackdrop(holder, backdrop, fade) }
        }
    }

    private fun setBackdrop(holder: Holder, backdrop: Bitmap, fade: Boolean) {
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
        val previous = holder.mainLayout.background
        holder.mainLayout.backgroundCompat = if (fade && previous != null) {
            BackdropFadeInDrawable(previous, drawable, ImageContentRenderer.CROSSFADE_MS)
        } else {
            drawable
        }
    }

    /**
     * Over artwork the message's own text colours cannot be trusted — a light theme's near-black
     * on a darkened cover is unreadable — so everything on it goes white while it is there.
     */
    private fun applyTextColors(holder: Holder, onBackdrop: Boolean, fade: Boolean = false) {
        holder.textColorAnimator?.cancel()
        holder.textColorAnimator = null
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
        if (!fade) {
            setTextColors(holder, primary, secondary, tertiary)
            return
        }
        val fromPrimary = holder.filenameView.currentTextColor
        val fromSecondary = holder.audioPlaybackTime.currentTextColor
        val fromTertiary = holder.fileSize.currentTextColor
        holder.textColorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ImageContentRenderer.CROSSFADE_MS.toLong()
            addUpdateListener {
                val progress = it.animatedValue as Float
                setTextColors(
                        holder,
                        ColorUtils.blendARGB(fromPrimary, primary, progress),
                        ColorUtils.blendARGB(fromSecondary, secondary, progress),
                        ColorUtils.blendARGB(fromTertiary, tertiary, progress),
                )
            }
            start()
        }
    }

    private fun setTextColors(holder: Holder, primary: Int, secondary: Int, tertiary: Int) {
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
            drawColor(ColorUtils.setAlphaComponent(Color.BLACK, (scrimAlpha(backdrop) * 255).toInt()))
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
        val listener = AudioMessagePlaybackTracker.Listener { state ->
            if (state is AudioMessagePlaybackTracker.Listener.State.Playing ||
                    state is AudioMessagePlaybackTracker.Listener.State.Paused) {
                bindFileDetails(holder, onlyIfSourceChanged = true)
            }
            when (state) {
                is AudioMessagePlaybackTracker.Listener.State.Error,
                is AudioMessagePlaybackTracker.Listener.State.Idle -> renderIdleState(holder)
                is AudioMessagePlaybackTracker.Listener.State.Playing -> renderPlayingState(holder, state)
                is AudioMessagePlaybackTracker.Listener.State.Paused -> renderPausedState(holder, state)
                is AudioMessagePlaybackTracker.Listener.State.Recording -> Unit
            }
        }
        holder.playbackRegistration.track(audioMessagePlaybackTracker, attributes.informationData.stableId, listener)
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
        previewUrlViewUpdater.unbind()
        holder.cancelProgressAnimation()
        holder.textColorAnimator?.cancel()
        holder.textColorAnimator = null
        holder.mainLayout.tag = null
        holder.backdropKey = null
        holder.detailsSource = null
        holder.loadingDetailsSource = null
        super.unbind(holder)
        contentUploadStateTrackerBinder.unbind(attributes.informationData.stableId)
        contentDownloadStateTrackerBinder.unbind(mxcUrl)
        holder.playbackRegistration.release(audioMessagePlaybackTracker)
    }

    override fun getViewStubId() = STUB_ID

    // wrap_content inside a bubble squeezes the player down to its controls; give it the same width
    // media gets in a bubble, so the waveform/seek bar has room.
    override fun getViewStubMinimumWidth(holder: Holder): Int = bubbleContentMaxWidth(holder.view.resources)

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
        var textColorAnimator: ValueAnimator? = null
        var coverFromFile = false
        val playbackRegistration = AudioMessagePlaybackTracker.RowRegistration()
        var detailsSource: Uri? = null
        var loadingDetailsSource: Uri? = null

        fun cancelProgressAnimation() {
            progressAnimator?.cancel()
            progressAnimator = null
        }
        val captionView by bind<AppCompatTextView>(R.id.messageCaptionView)
        val previewUrlView by bind<PreviewUrlView>(R.id.messageUrlPreview)
    }

    companion object {
        private val STUB_ID = R.id.messageContentAudioStub

        /** Dark enough to read white text on, light enough to leave the artwork its colour. */
        private const val BACKDROP_SCRIM_ALPHA = 0.6f

        /** What the artwork may read at once the scrim is over it, before white stops being legible. */
        private const val TARGET_LUMINANCE = 0.25f
        private const val MAX_SCRIM_ALPHA = 0.85f

        /** Roughly this many pixels across is plenty to average a blur down to one number. */
        private const val LUMINANCE_SAMPLES = 24

        /**
         * How far to darken [backdrop] to read white text on it. Bright artwork — a white sleeve, a
         * washed-out photo — leaves nothing to tell the letters from under the flat veil that suits
         * an ordinary cover, so the veil deepens with the artwork.
         */
        private fun scrimAlpha(backdrop: Bitmap): Float {
            val luminance = averageLuminance(backdrop)
            if (luminance <= 0f) return BACKDROP_SCRIM_ALPHA
            return (1f - TARGET_LUMINANCE / luminance).coerceIn(BACKDROP_SCRIM_ALPHA, MAX_SCRIM_ALPHA)
        }

        private fun averageLuminance(backdrop: Bitmap): Float {
            val step = maxOf(1, maxOf(backdrop.width, backdrop.height) / LUMINANCE_SAMPLES)
            var total = 0f
            var count = 0
            var y = 0
            while (y < backdrop.height) {
                var x = 0
                while (x < backdrop.width) {
                    val pixel = backdrop[x, y]
                    total += 0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)
                    count++
                    x += step
                }
                y += step
            }
            return if (count == 0) 0f else total / count / 255f
        }

        /** Matches bg_media_pill, which the backdrop stands in for. */
        private const val PILL_CORNER_RADIUS_DP = 12f

        /** One row at a time reads a file, rather than a thread each on a fast scroll. */
        private val detailsLoader = Executors.newSingleThreadExecutor()

        /** Apart from [detailsLoader], so a slow download does not hold up reading files already on disk. */
        private val coverArtLoader = Executors.newSingleThreadExecutor()

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

/**
 * Fades [to] in over [from] once. Not a TransitionDrawable: the message's tinting mutate()s its
 * background, which a LayerDrawable holding a rounded bitmap does not survive.
 */
private class BackdropFadeInDrawable(
        private val from: Drawable,
        private val to: Drawable,
        private val durationMs: Int,
) : Drawable() {

    private var startMs = -1L
    private var baseAlpha = 255

    override fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (startMs < 0) startMs = now
        val progress = ((now - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        if (progress < 1f) {
            from.alpha = baseAlpha
            from.draw(canvas)
            to.alpha = (baseAlpha * progress).toInt()
            to.draw(canvas)
            invalidateSelf()
        } else {
            to.alpha = baseAlpha
            to.draw(canvas)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        from.bounds = bounds
        to.bounds = bounds
    }

    override fun getPadding(padding: Rect): Boolean = to.getPadding(padding)

    override fun setAlpha(alpha: Int) {
        baseAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        from.colorFilter = colorFilter
        to.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
