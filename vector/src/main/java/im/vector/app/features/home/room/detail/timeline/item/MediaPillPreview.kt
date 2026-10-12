/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import im.vector.app.R
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.setMediaPillColorCompat
import im.vector.app.core.utils.TextUtils
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.app.features.home.room.detail.timeline.helper.bubbleContentMaxWidth
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.voice.AudioWaveformView
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.message.AudioMetadata
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import kotlin.math.ceil

/**
 * A file, audio or voice message as the timeline draws it, for the places that show a message outside
 * the timeline (long-press sheet, reply header, composer). Static: nothing in it plays or downloads.
 */
sealed interface MediaPillPreview {

    data class File(val filename: String, val downloaded: Boolean) : MediaPillPreview

    data class Audio(
            val filename: String,
            val durationMs: Int,
            val size: Long,
            val metadata: AudioMetadata?,
            val localSource: String?,
    ) : MediaPillPreview

    data class Voice(val durationMs: Int, val waveform: List<Int>) : MediaPillPreview

    companion object {
        fun of(content: MessageContent?, session: Session?): MediaPillPreview? = when (content) {
            is MessageFileContent -> File(
                    filename = content.getFileName(),
                    downloaded = session?.fileService()?.isFileInCache(content) == true,
            )
            // Voice is KitKat+ in the timeline too (MessageItemFactory.buildAudioContent).
            is MessageAudioContent -> if (content.voiceMessageIndicator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                Voice(
                        durationMs = content.audioWaveformInfo?.duration ?: 0,
                        waveform = content.audioWaveformInfo?.waveform.orEmpty().filterNotNull()
                                .map { it * AudioWaveformView.MAX_FFT / 1024 },
                )
            } else {
                Audio(
                        filename = content.getFileName(),
                        durationMs = content.audioInfo?.duration ?: 0,
                        size = content.audioInfo?.size ?: 0L,
                        metadata = content.audioInfo?.metadata,
                        localSource = localAudioSource(content, session),
                )
            }
            else -> null
        }

        private fun localAudioSource(content: MessageAudioContent, session: Session?): String? {
            val url = content.getFileUrl() ?: return null
            if (!url.startsWith("mxc://")) return url
            return session?.fileService()?.getLocalFileFor(
                    mxcUrl = url,
                    fileName = content.getFileName(),
                    mimeType = content.mimeType,
                    isEncrypted = content.encryptedFileInfo != null,
            )?.let { Uri.fromFile(it).toString() }
        }
    }
}

/**
 * Hosts a [MediaPillPreview]. A preview is a picture of the message, so its seek bar and buttons never
 * see a touch: they all fall through to the surface around it, as a tap anywhere else there would.
 *
 * A player is laid out at the width the timeline gives it — the whole column, and never less than a
 * bubble's media width — then scaled down whole, at most to [playerScale], so a narrow host shrinks it
 * in proportion instead of squeezing its seek bar / waveform into nothing.
 */
class MediaPillPreviewView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var playerScale = 1f
        set(value) {
            field = value
            requestLayout()
        }

    private var pillScale = 1f

    val pill: View? get() = getChildAt(0)

    fun setPill(view: View?) {
        removeAllViews()
        view?.let { addView(it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pill = pill
        if (pill == null) {
            setMeasuredDimension(0, 0)
            return
        }
        val chrome = paddingLeft + paddingRight
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val available = (MeasureSpec.getSize(widthMeasureSpec) - chrome).coerceAtLeast(0)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        if (pill.layoutParams?.width == LayoutParams.MATCH_PARENT) {
            val minimum = bubbleContentMaxWidth(resources)
            val natural = if (bounded) maxOf(available, minimum) else minimum
            pill.measure(MeasureSpec.makeMeasureSpec(natural, MeasureSpec.EXACTLY), unspecified)
            val fit = if (bounded) available.toFloat() / natural else 1f
            pillScale = minOf(playerScale, fit, 1f)
        } else {
            pill.measure(if (bounded) MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST) else unspecified, unspecified)
            pillScale = 1f
        }
        setMeasuredDimension(
                resolveSize(ceil(pill.measuredWidth * pillScale).toInt() + chrome, widthMeasureSpec),
                resolveSize(ceil(pill.measuredHeight * pillScale).toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pill = pill ?: return
        val rtl = ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL
        val left = if (rtl) width - paddingRight - pill.measuredWidth else paddingLeft
        pill.layout(left, paddingTop, left + pill.measuredWidth, paddingTop + pill.measuredHeight)
        pill.pivotX = if (rtl) pill.measuredWidth.toFloat() else 0f
        pill.pivotY = 0f
        pill.scaleX = pillScale
        pill.scaleY = pillScale
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

object MediaPillPreviewBinder {

    /** Fills [container] with [preview]'s pill, or empties and hides it for null. */
    fun bind(container: MediaPillPreviewView, preview: MediaPillPreview?) {
        if (preview == null) {
            container.setPill(null)
            container.isVisible = false
            return
        }
        container.isVisible = true
        val pill = inflate(container, layoutFor(preview))
        pill.setMediaPillColorCompat(ThemeUtils.getColor(container.context, im.vector.lib.ui.styles.R.attr.vctr_content_quinary))
        when (preview) {
            is MediaPillPreview.File -> bindFile(pill, preview)
            is MediaPillPreview.Audio -> bindAudio(pill, preview)
            is MediaPillPreview.Voice -> bindVoice(pill, preview)
        }
    }

    @LayoutRes
    private fun layoutFor(preview: MediaPillPreview) = when (preview) {
        is MediaPillPreview.File -> R.layout.view_timeline_file_pill
        is MediaPillPreview.Audio -> R.layout.view_timeline_audio_pill
        is MediaPillPreview.Voice -> R.layout.view_timeline_voice_pill
    }

    private fun inflate(container: MediaPillPreviewView, @LayoutRes layout: Int): View {
        val existing = container.pill
        if (existing != null && existing.getTag(R.id.media_pill_preview_layout) == layout) return existing
        return LayoutInflater.from(container.context).inflate(layout, container, false).also {
            it.setTag(R.id.media_pill_preview_layout, layout)
            container.setPill(it)
        }
    }

    private fun bindFile(pill: View, preview: MediaPillPreview.File) {
        val filename = pill.findViewById<TextView>(R.id.messageFilenameView)
        filename.text = preview.filename.prepareForDisplay()
        filename.paintFlags = filename.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        pill.findViewById<ProgressBar>(R.id.messageFileProgressbar).progress = 0
        pill.findViewById<ImageView>(R.id.messageFileIconView).setImageDrawable(
                AppCompatResources.getDrawable(pill.context, if (preview.downloaded) R.drawable.ic_paperclip else R.drawable.ic_material_save)
        )
    }

    private fun bindAudio(pill: View, preview: MediaPillPreview.Audio) {
        val context = pill.context
        val filenameView = pill.findViewById<TextView>(R.id.messageFilenameView)
        val artistView = pill.findViewById<TextView>(R.id.messageAudioArtistView)
        val playbackTime = pill.findViewById<TextView>(R.id.audioPlaybackTime)
        val playbackDuration = pill.findViewById<TextView>(R.id.audioPlaybackDuration)
        val fileSize = pill.findViewById<TextView>(R.id.fileSize)
        val button = pill.findViewById<ImageButton>(R.id.audioPlaybackControlButton)
        val seekBar = pill.findViewById<SeekBar>(R.id.audioSeekBar)

        button.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_play_pause_play))
        playbackTime.text = formatPlaybackTime(0)
        playbackDuration.text = formatPlaybackTime(preview.durationMs)
        fileSize.text = context.getString(CommonStrings.audio_message_file_size, TextUtils.formatFileSize(context, preview.size, true))
        seekBar.max = preview.durationMs.coerceAtLeast(1)
        seekBar.progress = 0

        // As MessageAudioItem: the sender's tags win, else whatever the timeline already read off the file.
        val fromFile = preview.localSource?.let { AudioDetails.cached(it.toUri(), forTimeline = true) }
        val metadata = preview.metadata
        val title = metadata?.title?.takeIf { it.isNotBlank() } ?: fromFile?.title.takeIf { metadata == null }
        val credits = if (metadata != null) {
            AudioDetails.Details(null, metadata.artist?.takeIf { it.isNotBlank() }, metadata.album?.takeIf { it.isNotBlank() }, null, null).credits
        } else {
            fromFile?.credits
        }
        val eventHash = metadata?.coverArtBlurhash?.takeIf { it.isNotBlank() }
                ?: metadata?.coverArtUrl?.let { AudioDetails.cachedCoverArtHash(it) }
        val backdrop = eventHash?.let { AudioDetails.coverArtBackdrop(it) } ?: fromFile?.backdrop

        filenameView.text = (title ?: preview.filename).prepareForDisplay()
        artistView.text = credits?.prepareForDisplay()
        artistView.isVisible = credits != null
        applyBackdrop(pill, backdrop)

        val onBackdrop = backdrop != null
        val primary = if (onBackdrop) Color.WHITE else ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        val secondary = if (onBackdrop) {
            MessageAudioItem.ON_BACKDROP_SECONDARY
        } else {
            ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        }
        val tertiary = if (onBackdrop) {
            MessageAudioItem.ON_BACKDROP_SECONDARY
        } else {
            ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_tertiary)
        }
        filenameView.setTextColor(primary)
        artistView.setTextColor(secondary)
        playbackDuration.setTextColor(tertiary)
        fileSize.setTextColor(tertiary)
        playbackTime.setTextColor(secondary)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(secondary))
    }

    private fun applyBackdrop(pill: View, backdrop: Bitmap?) {
        // A rebind before the pending layout lands must not have the old cover painted over it.
        pill.setTag(R.id.media_pill_preview_backdrop, backdrop)
        if (backdrop == null) {
            pill.backgroundCompat = ContextCompat.getDrawable(pill.context, im.vector.lib.ui.styles.R.drawable.bg_media_pill)
            pill.setMediaPillColorCompat(ThemeUtils.getColor(pill.context, im.vector.lib.ui.styles.R.attr.vctr_content_quinary))
            return
        }
        pill.doOnLayout {
            if (pill.getTag(R.id.media_pill_preview_backdrop) !== backdrop) return@doOnLayout
            ViewCompat.setBackgroundTintList(pill, null)
            pill.backgroundCompat = MessageAudioItem.backdropDrawable(pill.context, backdrop, pill.width, pill.height)
        }
    }

    private fun bindVoice(pill: View, preview: MediaPillPreview.Voice) {
        val context = pill.context
        pill.findViewById<ImageButton>(R.id.voicePlaybackControlButton)
                .setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_play_pause_play))
        pill.findViewById<TextView>(R.id.voicePlaybackTime).text = formatPlaybackTime(preview.durationMs)
        val waveformView = pill.findViewById<AudioWaveformView>(R.id.voicePlaybackWaveform)
        val idle = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
        val played = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        // The waveform sizes its bars off its own width, which only exists once laid out.
        waveformView.doOnPreDraw {
            waveformView.clear()
            preview.waveform.forEach { waveformView.add(AudioWaveformView.FFT(it.toFloat(), idle)) }
            waveformView.summarize()
            waveformView.updateColors(0f, played, idle)
        }
    }

    private fun formatPlaybackTime(time: Int) = DateUtils.formatElapsedTime((time / 1000).toLong())
}
