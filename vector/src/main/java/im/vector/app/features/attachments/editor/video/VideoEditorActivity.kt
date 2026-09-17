/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.thumbCompat
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityVideoEditorBinding
import im.vector.app.features.attachments.editor.AspectRatioPicker
import im.vector.app.features.attachments.editor.restoreOriginalResult
import im.vector.app.features.attachments.preview.PlaybackPosition
import im.vector.app.features.attachments.preview.VIDEO_PROGRESS_INTERVAL_MS
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.core.utils.audio.LoudnessBoost
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.mediatranscode.MediaSourceInfo
import im.vector.lib.mediatranscode.VideoEditException
import im.vector.lib.mediatranscode.VideoEditProgressListener
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.debug.DebugLog
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * Editor for both videos and animated images. Only the video half needs API 18, and it is only
 * reachable through `isVideoEditable()`, which checks for it; an animated image opens this from 14,
 * so the two members that call the API-18 exporter are suppressed one at a time rather than the
 * whole class being exempted from lint.
 */
@AndroidEntryPoint
class VideoEditorActivity : VectorBaseActivity<ActivityVideoEditorBinding>() {

    private lateinit var sourceUri: Uri
    private var displayName: String? = null
    private var initialEdits: VideoEditorEdits? = null

    private var player: EditorPreviewPlayer? = null
    private var audioPlayer: MediaPlayer? = null
    private var audioReady = false
    private var surface: Surface? = null
    private var durationUs = 0L
    private var frameRate = 30f

    private var startUs = 0L
    private var endUs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var exportJob: Job? = null
    private var fineMode = false
    private var scrubbing = false
    private var playheadAnimator: ValueAnimator? = null
    private var playheadUs = 0L
    private var lastReportedPositionUs = 0L

    // How much clip the player got through per millisecond of wall time, against what was asked for.
    private var observedMediaUs = 0L
    private var observedWallMs = 0L
    private var lastTickAt = 0L
    private var draggingScrubber = false
    private var restartingUntilMs = 0L
    private var resumeAfterScrub = false
    private var lastSeekAt = 0L
    private var pendingEditedUs: Long? = null
    private var audioBurstActive = false
    private var pendingAudioUs: Long? = null
    private var volume = PlaybackVolume()
    private var reversed = false
    private var lastCustomAspectRatio: Pair<Int, Int>? = null
    private var playerBoost: LoudnessBoost? = null
    private var audioBoost: LoudnessBoost? = null
    private var playbackSpeed = PlaybackSpeed()
    private var warnedAboutSpeedPreview = false
    private var speedAwaitingPlayback = false
    private var exporting = false

    /** Where playback was when the surface went away, so coming back does not start over. */
    private var resumePositionUs = 0L
    private var resumePlaying = false
    private var pauseOnFirstFrame = false
    private var activityPaused = false

    /** An animated image has no audio and no codec: a frame ticker plays it, and the export writes WebP. */
    private var animatedFormat: AnimatedImageFormat? = null
    private var animatedSource: File? = null
    private var animatedPlayer: AnimatedFramePlayer? = null

    private val isAnimated get() = animatedFormat != null

    override val drawUnderSystemBars = true

    override fun getOtherThemes() = ActivityOtherThemes.AttachmentsPreview

    override fun getBinding() = ActivityVideoEditorBinding.inflate(layoutInflater)

    override val rootView: View
        get() = views.coordinatorLayout

    override fun initUiAndData() {
        makeSystemBarsTransparent()
        sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.toUri() ?: run { finish(); return }
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        initialEdits = intent.getParcelableExtraCompat(EXTRA_EDITS)
        animatedFormat = intent.getStringExtra(EXTRA_ANIMATED_FORMAT)?.let { name ->
            runCatching { AnimatedImageFormat.valueOf(name) }.getOrNull()
        }

        applyInsets()
        setupToolbar(views.videoEditorToolbar).allowBack()
        views.videoEditorToolbar.setTitle(
                if (isAnimated) CommonStrings.animated_image_editor_title else CommonStrings.video_editor_title
        )
        views.videoEditorExportLabel.setText(
                if (isAnimated) CommonStrings.animated_image_editor_exporting else CommonStrings.video_editor_exporting
        )

        val accent = ThemeUtils.getColorFromContextTheme(this, com.google.android.material.R.attr.colorAccent)
        val (fill, onFill) = ThemeUtils.accentFillOnDarkSurface(this)
        views.videoEditorSaveButton.backgroundTintList = ColorStateList.valueOf(fill)
        ImageViewCompat.setImageTintList(views.videoEditorSaveButton, ColorStateList.valueOf(onFill))
        tintProgressBar(accent)
        views.videoEditorSaveButton.setOnClickListener { save() }
        views.videoEditorExportCancel.setOnClickListener { exportJob?.cancel() }
        views.videoEditorCropOverlay.onTransform = {
            views.videoEditorTextureView.setTransform(it)
            views.videoEditorTextureView.invalidate()
        }
        views.videoEditorCropOverlay.snapToCenter = vectorPreferences.imageEditorSnapToCenter()
        setupPlaybackControls(accent)

        views.videoEditorTimeline.listener = VideoTimelineStripView.Listener { start, end, dragging ->
            // Null means neither edge moved, and a mere grab must not disturb playback.
            val edited = when {
                start != startUs -> start
                end != endUs -> end
                else -> null
            }
            DebugLog.i { "MEDIADBG VEXP trim start=$start end=$end dragging=$dragging fine=$fineMode edited=$edited" }
            startUs = start
            endUs = end
            applyTrimToAnimatedPlayer()
            updateScrubberRange()
            updateDurationLabel()
            if (dragging) {
                beginScrubbing()
                edited?.let {
                    pendingEditedUs = it
                    seekThrottled(it)
                }
            } else {
                // The last drag update may have been throttled out, so land the exact frame here.
                endScrubbing(edited ?: pendingEditedUs, resume = false)
                pendingEditedUs = null
            }
        }
        views.videoEditorTimeline.onHandleHeld = { positionUs, held ->
            if (held) {
                beginScrubbing()
                seekTo(positionUs)
            } else {
                // Left where the handle was: the frame it cuts on is what the user was looking at.
                endScrubbing(positionUs, resume = false)
            }
        }
        views.videoEditorTimeline.onFineModeChanged = { fine, positionUs ->
            DebugLog.i { "MEDIADBG VEXP fineMode=$fine at ${positionUs}us startUs=$startUs endUs=$endUs" }
            fineMode = fine
            // Zooming in is the edit gesture starting, so park playback now, not on first movement.
            if (fine) {
                beginScrubbing()
                seekTo(positionUs)
            } else {
                endScrubbing(positionUs, resume = false)
            }
            updateDurationLabel()
        }
        views.videoEditorTimeline.onScrub = { us, dragging ->
            if (dragging) {
                beginScrubbing()
                seekThrottled(us)
            } else {
                endScrubbing(us)
            }
        }

        views.videoEditorTextureView.surfaceTextureListener = surfaceListener
        loadMetadata()
    }

    /** The backdrop runs to the edges of the screen; the controls stay clear of the system bars. */
    private fun applyInsets() {
        val saveMargin = views.videoEditorSaveButton.marginBottom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // On the views themselves rather than the root, whose listener belongs to the activity.
            ViewCompat.setOnApplyWindowInsetsListener(views.videoEditorAppBar) { v, insets ->
                v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
                insets
            }
            ViewCompat.setOnApplyWindowInsetsListener(views.videoEditorSaveButton) { v, insets ->
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = saveMargin + insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                }
                insets
            }
        } else {
            // Pre-21 has no window-insets dispatch, and the navigation bar is not overlapped there.
            views.videoEditorAppBar.updatePadding(top = statusBarHeightPx())
        }
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_video_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.videoEditorVolumeAction)?.apply {
            // An animated image has no sound to set.
            isVisible = !isAnimated
            setIcon(volumeIcon())
        }
        menu.findItem(R.id.videoEditorReverseAction)?.isChecked = reversed
        menu.findItem(R.id.videoEditorSnapAction)?.isChecked = views.videoEditorCropOverlay.snapToCenter
        // Changing an edit while it is being written would export something nobody asked for.
        for (index in 0 until menu.size()) {
            menu.getItem(index).isEnabled = !exporting
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.videoEditorResetAction -> {
                resetEdits()
                true
            }
            R.id.videoEditorRotateAction -> {
                rotateClockwise()
                true
            }
            R.id.videoEditorVolumeAction -> {
                showVolumeDialog()
                true
            }
            R.id.videoEditorReverseAction -> {
                setReversed(!reversed)
                true
            }
            R.id.videoEditorAspectAction -> {
                showAspectRatioPicker()
                true
            }
            R.id.videoEditorSnapAction -> {
                val enabled = !views.videoEditorCropOverlay.snapToCenter
                views.videoEditorCropOverlay.snapToCenter = enabled
                vectorPreferences.setImageEditorSnapToCenter(enabled)
                invalidateOptionsMenu()
                true
            }
            R.id.videoEditorSpeedAction -> {
                showSpeedDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAspectRatioPicker() {
        AspectRatioPicker.show(
                context = ContextThemeWrapper(this, ThemeUtils.getApplicationThemeRes(this)),
                current = views.videoEditorCropOverlay.aspectRatio,
                suggested = lastCustomAspectRatio ?: views.videoEditorCropOverlay.displayedAspectRatio(),
        ) { ratio, custom ->
            custom?.let { lastCustomAspectRatio = it }
            views.videoEditorCropOverlay.aspectRatio = ratio
        }
    }

    /** The preview only follows the speed on API 23+; the export applies it either way. */
    private fun showSpeedDialog() {
        PlaybackSpeedDialog(
                context = ContextThemeWrapper(this, ThemeUtils.getApplicationThemeRes(this)),
                initial = playbackSpeed,
                // Pitch is meaningless without a sound track.
                allowPitchChoice = !isAnimated,
                onChanged = { speed ->
                    playbackSpeed = speed
                    applyPlaybackSpeed()
                    updateDurationLabel()
                }
        ).show()
    }

    /** How long the export will be: the trimmed range at the chosen speed. */
    private fun outputDurationUs(): Long = ((endUs - startUs).coerceAtLeast(0) / playbackSpeed.speed).toLong()

    private fun applyPlaybackSpeed() {
        animatedPlayer?.let {
            it.speed = playbackSpeed.speed
            return
        }
        val player = player ?: return
        if (!player.enforcesRange && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (!playbackSpeed.isDefault && !warnedAboutSpeedPreview) {
                warnedAboutSpeedPreview = true
                Toast.makeText(this, getString(CommonStrings.video_editor_speed_preview_unsupported), Toast.LENGTH_LONG).show()
            }
            return
        }
        // MediaPlayer starts on being given parameters, and the sound it gets out before it can be
        // paused again is a click — one per step of the speed dialog, which is a crackle. So on that
        // player the speed waits for playback, which is the only time it means anything anyway.
        if (!player.enforcesRange && !player.isPlaying) {
            speedAwaitingPlayback = true
            return
        }
        pushPlaybackSpeed(player)
    }

    private fun pushPlaybackSpeed(player: EditorPreviewPlayer) {
        speedAwaitingPlayback = false
        player.setSpeed(playbackSpeed.speed, playbackSpeed.changePitch)
    }

    private fun resetEdits() {
        if (durationUs <= 0) return
        startUs = 0
        endUs = durationUs
        updateScrubberRange()
        setVolume(PlaybackVolume())
        setReversed(false, announce = false)
        playbackSpeed = PlaybackSpeed()
        applyPlaybackSpeed()
        views.videoEditorCropOverlay.resetEdits()
        views.videoEditorTimeline.setTrim(startUs, endUs)
        applyTrimToAnimatedPlayer()
        updateDurationLabel()
        seekTo(startUs)
    }

    /** progressTintList is API 21+, and this screen runs from 18. */
    @Suppress("DEPRECATION")
    private fun tintProgressBar(accent: Int) {
        views.videoEditorExportProgress.progressDrawable?.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
    }

    /** The probe is API 16, and only the video path — which needs 18 anyway — reaches it. */
    @SuppressLint("NewApi")
    private fun loadMetadata() {
        if (isAnimated) {
            loadAnimatedMetadata()
            return
        }
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { MediaSourceInfo.probe(this@VideoEditorActivity, sourceUri) }
            if (info == null || info.durationUs <= 0) {
                Toast.makeText(this@VideoEditorActivity, getString(CommonStrings.video_editor_load_failed), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            durationUs = info.durationUs
            frameRate = info.frameRate
            views.videoEditorTimeline.durationUs = durationUs
            views.videoEditorTimeline.frameRate = info.frameRate
            val edits = initialEdits
            startUs = edits?.startUs ?: 0L
            endUs = edits?.endUs?.takeIf { it > 0 } ?: durationUs
            edits?.volume?.let { setVolume(it) }
            if (edits?.reversed == true) setReversed(true, announce = false)
            edits?.speed?.let { playbackSpeed = it }
            // The probe's dimensions, not the player's: MediaPlayer reports the coded frame rather
            // than the displayed one on some devices, which shows a portrait clip stretched.
            views.videoEditorCropOverlay.setVideoSize(info.displayWidth, info.displayHeight)
            // Whatever shape the previewer's compression settings will send it at.
            applyTargetSizeOverride()
            views.videoEditorCropOverlay.restoreEdits(edits?.rotationDegrees ?: 0, edits?.crop)
            views.videoEditorTimeline.setTrim(startUs, endUs)
            updateScrubberRange()
            updateDurationLabel()
            extractThumbnails()
        }
    }

    /**
     * The frame readers work from a file, and the source is a content:// uri, so it is copied out
     * once here and reused by the export.
     */
    private fun loadAnimatedMetadata() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val file = copySourceToCache() ?: return@withContext null
                AnimatedImageSource.load(file, animatedFormat)?.let { file to it }
            }
            if (loaded == null) {
                Toast.makeText(
                        this@VideoEditorActivity, getString(CommonStrings.animated_image_editor_load_failed), Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            val (file, source) = loaded
            animatedSource = file
            durationUs = source.durationUs
            frameRate = source.frameRate
            views.videoEditorTimeline.durationUs = durationUs
            views.videoEditorTimeline.frameRate = frameRate
            val edits = initialEdits
            startUs = edits?.startUs ?: 0L
            endUs = edits?.endUs?.takeIf { it > 0 } ?: durationUs
            edits?.speed?.let { playbackSpeed = it }
            if (edits?.reversed == true) setReversed(true, announce = false)
            views.videoEditorCropOverlay.setVideoSize(source.width, source.height)
            applyTargetSizeOverride()
            views.videoEditorCropOverlay.restoreEdits(edits?.rotationDegrees ?: 0, edits?.crop)
            views.videoEditorTimeline.setTrim(startUs, endUs)
            updateScrubberRange()
            updateDurationLabel()
            addAnimatedThumbnails(source)
            animatedPlayer = AnimatedFramePlayer(source, views.videoEditorTextureView, handler) { positionUs ->
                setPlayhead(positionUs)
            }
            applyPlaybackSpeed()
            animatedPlayer?.reversed = reversed
            applyTrimToAnimatedPlayer()
            startPlayback()
        }
    }

    private fun addAnimatedThumbnails(source: AnimatedImageSource) = views.videoEditorTimeline.doOnLayout {
        val count = THUMBNAIL_COUNT.coerceAtMost(source.frames.size)
        if (count <= 0) return@doOnLayout
        views.videoEditorTimeline.prepareThumbnails(count)
        val targetHeight = views.videoEditorTimeline.height
        for (index in 0 until count) {
            val frame = source.frames[index * source.frames.size / count].bitmap
            val scale = targetHeight.toFloat() / frame.height
            val scaled = runCatching {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), targetHeight, true)
            }.getOrNull() ?: continue
            views.videoEditorTimeline.addThumbnail(scaled)
        }
    }

    private fun copySourceToCache(): File? = runCatching {
        val destination = File(cacheDir, "animated-edit-source")
        contentResolver.openInputStream(sourceUri)?.use { input ->
            destination.outputStream().use { input.copyTo(it) }
        } ?: return null
        destination
    }.onFailure { Timber.w(it, "VideoEditor: cannot read $sourceUri") }.getOrNull()

    private fun applyTargetSizeOverride() {
        val targetWidth = intent.getIntExtra(EXTRA_TARGET_WIDTH, 0)
        val targetHeight = intent.getIntExtra(EXTRA_TARGET_HEIGHT, 0)
        if (targetWidth > 0 && targetHeight > 0) {
            views.videoEditorCropOverlay.contentSizeOverride = targetWidth to targetHeight
        }
    }

    private fun applyTrimToAnimatedPlayer() {
        animatedPlayer?.apply {
            loopStartUs = startUs
            loopEndUs = if (endUs > 0) endUs else durationUs
        }
    }

    /** getFrameAtTime costs 100-300ms and a full-size bitmap each, so scale as we go. */
    private fun extractThumbnails() = views.videoEditorTimeline.doOnLayout {
        val count = THUMBNAIL_COUNT
        views.videoEditorTimeline.prepareThumbnails(count)
        lifecycleScope.launch {
            val targetHeight = views.videoEditorTimeline.height
            val retriever = MediaMetadataRetriever()
            try {
                withContext(Dispatchers.IO) { retriever.setDataSource(this@VideoEditorActivity, sourceUri) }
                for (index in 0 until count) {
                    val timeUs = durationUs * index / count
                    val frame = withContext(Dispatchers.IO) {
                        runCatching {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                                val scale = targetHeight.toFloat() / full.height
                                val scaled = Bitmap.createScaledBitmap(
                                        full, (full.width * scale).toInt().coerceAtLeast(1), targetHeight, true
                                )
                                if (scaled != full) full.recycle()
                                scaled
                            }
                        }.getOrNull()
                    } ?: continue
                    views.videoEditorTimeline.addThumbnail(frame)
                }
            } finally {
                withContext(Dispatchers.IO) { runCatching { retriever.release() } }
            }
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if (isAnimated) {
                // The surface only exists now, and an editor opened paused would show nothing.
                animatedPlayer?.draw()
                return
            }
            surface = Surface(texture)
            preparePlayer()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            // Leaving the editor tears the surface down and with it the player, so where playback
            // had got to has to be remembered here or coming back starts the clip again. Guarded:
            // a player still preparing throws on both reads.
            player?.let {
                runCatching {
                    resumePositionUs = it.positionUs
                    resumePlaying = it.isPlaying
                }
            }
            releasePlayer()
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            // A seek alone does not reliably paint a frame, so a player that should be paused is
            // started and stopped again the moment it has shown one.
            if (pauseOnFirstFrame) {
                pauseOnFirstFrame = false
                pausePlayback()
            }
        }
    }

    /** Only ever seeked and briefly started, to sound out the frame under the finger. */
    private fun prepareAudioPlayer() {
        audioReady = false
        audioPlayer = MediaPlayer().apply {
            setOnPreparedListener {
                audioReady = true
                applyVolume()
            }
            setOnSeekCompleteListener {
                if (!scrubbing) {
                    audioBurstActive = false
                    return@setOnSeekCompleteListener
                }
                runCatching { start() }
                handler.removeCallbacks(stopBlipRunnable)
                handler.postDelayed(stopBlipRunnable, AUDIO_BLIP_MS)
            }
            setOnErrorListener { _, _, _ ->
                audioReady = false
                true
            }
            runCatching {
                setDataSource(this@VideoEditorActivity, sourceUri)
                prepareAsync()
            }.onFailure { Timber.w(it, "VideoEditor: no scrub audio for $sourceUri") }
        }
    }

    private fun preparePlayer() {
        releasePlayer()
        val surface = surface ?: return
        player = EditorPreviewPlayer.create().also { preview ->
            preview.open(
                    context = this,
                    uri = sourceUri,
                    surface = surface,
                    startPositionUs = resumePositionUs.takeIf { it > startUs } ?: startUs,
                    // A seek before playback starts does not reliably render a frame, so an idle editor
                    // would show nothing at all: play either way, and stop on the first frame when
                    // playback was not running when we left — or when preparation outlived the activity
                    // being on screen, which must not start sound in the background.
                    playWhenReady = true,
                    listener = previewListener,
            )
        }
    }

    private val previewListener = object : EditorPreviewPlayer.Listener {
        override fun onReady() {
            applyVolume()
            applyPlaybackSpeed()
            pauseOnFirstFrame = activityPaused || (resumePositionUs > 0 && !resumePlaying)
            startPlayback()
        }

        override fun onEnded() = stopAtEnd()

        override fun onError(message: String) {
            Timber.w("VideoEditor: player error $message")
            Toast.makeText(this@VideoEditorActivity, getString(CommonStrings.video_editor_load_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun rotateClockwise() = views.videoEditorCropOverlay.rotateClockwise()

    /** Only the animated player can run backwards; a video is reversed in the export alone. */
    private fun setReversed(next: Boolean, announce: Boolean = true) {
        reversed = next
        invalidateOptionsMenu()
        animatedPlayer?.reversed = reversed
        if (announce && reversed && !isAnimated) {
            Toast.makeText(this, getString(CommonStrings.video_editor_reverse_preview_unsupported), Toast.LENGTH_LONG).show()
        }
    }

    private fun showVolumeDialog() {
        PlaybackVolumeDialog(
                context = ContextThemeWrapper(this, ThemeUtils.getApplicationThemeRes(this)),
                initial = volume,
                canPreviewBoost = { playerBoost != null },
                cappedMessage = CommonStrings.video_editor_volume_preview_capped,
                onChanged = ::setVolume
        ).show()
    }

    private fun setVolume(next: PlaybackVolume) {
        volume = next
        invalidateOptionsMenu()
        applyVolume()
    }

    private fun volumeIcon() = when {
        volume.effectiveGain <= 0f -> R.drawable.ic_volume_off
        volume.effectiveGain > PlaybackVolume.NORMAL -> R.drawable.ic_volume_boost
        volume.effectiveGain < QUIET_GAIN -> R.drawable.ic_volume_down
        else -> R.drawable.ic_volume_up
    }

    /** Sets the preview too: the export is what the editor is showing. */
    private fun applyVolume() {
        // MediaPlayer's own scalar tops out at 1; anything above it comes from the boost.
        val scalar = volume.effectiveGain.coerceIn(0f, 1f)
        player?.setVolume(scalar)
        runCatching { audioPlayer?.setVolume(scalar, scalar) }
        applyBoost()
    }

    /** Anything above 100% is out of MediaPlayer's reach, and only KitKat has the effect for it. */
    private fun applyBoost() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        playerBoost = tuneBoost(playerBoost, player?.audioSessionId)
        audioBoost = tuneBoost(audioBoost, audioPlayer?.audioSessionId)
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun tuneBoost(existing: LoudnessBoost?, sessionId: Int?): LoudnessBoost? {
        val boost = existing ?: sessionId?.takeIf { it != 0 }?.let { LoudnessBoost.attachTo(it) } ?: return null
        boost.setGain(volume.effectiveGain)
        return boost
    }

    private fun setupPlaybackControls(accent: Int) {
        // progressTintList is API 21+, and this fork runs from 14.
        @Suppress("DEPRECATION")
        views.videoEditorSeekBar.apply {
            progressDrawable?.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
            thumbCompat?.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        views.videoEditorPlayPause.setOnClickListener { togglePlayback() }
        views.videoEditorSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) seekThrottled(scrubberPositionUs(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                draggingScrubber = true
                beginScrubbing()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                draggingScrubber = false
                endScrubbing(scrubberPositionUs(seekBar.progress))
            }
        })
    }

    /**
     * Whether there is nothing left to play before the cut. Not a plain `>= endUs`: the player reports
     * milliseconds while the cut is in microseconds, so a playhead parked exactly on it reads as a
     * fraction short and playback would resume only to stop on its very next tick.
     */
    private fun isAtCut(positionUs: Long): Boolean =
            TrimmedScrubber.isAtCut(positionUs, endUs, VIDEO_PROGRESS_INTERVAL_MS * 1000L)

    private fun keptRangeUs(): Long = TrimmedScrubber.rangeUs(startUs, endUs, durationUs)

    private fun scrubberPositionUs(progress: Int): Long = TrimmedScrubber.positionUs(startUs, progress)

    private fun updateScrubberRange() {
        val max = TrimmedScrubber.maxMs(startUs, endUs, durationUs)
        if (views.videoEditorSeekBar.max == max) return
        // A glide in flight is aimed at a position on the old scale.
        cancelScrubberGlide()
        views.videoEditorSeekBar.max = max
    }

    /**
     * Everything that moves the playhead goes through here so the strip, bar and label agree — and all
     * three are carried between reports rather than stepping on them, which is what made the bar read
     * as smooth while the strip's playhead stuttered beside it.
     */
    private fun setPlayhead(us: Long) {
        cancelScrubberGlide()
        val glides = !scrubbing && isPlaying() &&
                PlayheadGlide.glides(playheadUs, us, VIDEO_PROGRESS_INTERVAL_MS.toLong(), playbackSpeed.speed, SCRUBBER_GLIDE_MAX_STEP_US)
        if (!glides) {
            applyPlayhead(us)
            return
        }
        val aim = PlayheadGlide.aimUs(us, endUs, VIDEO_PROGRESS_INTERVAL_MS.toLong(), playbackSpeed.speed, notBeforeUs = playheadUs)
        // From where the playhead already is, not from the report: an audio sink that stalls (Bluetooth
        // spinning up) repeats a position, and starting each glide at it walked the playhead back a
        // report's worth every time — the same sliver of clip covered over and over in place.
        playheadAnimator = ValueAnimator.ofFloat(playheadUs.toFloat(), aim.toFloat()).apply {
            duration = VIDEO_PROGRESS_INTERVAL_MS.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { applyPlayhead((it.animatedValue as Float).toLong()) }
            start()
        }
    }

    private fun applyPlayhead(us: Long) {
        playheadUs = us
        views.videoEditorTimeline.playheadUs = us
        // The finger owns the bar while it is on it — but only a finger on the bar itself. Trimming
        // scrubs too, and the bar has to follow the cut there, pinned to whichever end is being moved.
        if (!draggingScrubber) {
            val bar = views.videoEditorSeekBar
            bar.progress = TrimmedScrubber.progressMs(us, startUs).coerceIn(0, bar.max)
        }
        views.videoEditorPlaybackTime.text = getString(
                CommonStrings.video_position_of_duration,
                DateUtils.formatElapsedTime((us - startUs).coerceAtLeast(0) / 1_000_000),
                DateUtils.formatElapsedTime(keptRangeUs() / 1_000_000)
        )
    }

    private fun cancelScrubberGlide() {
        playheadAnimator?.cancel()
        playheadAnimator = null
    }

    private fun updatePlayPauseIcon() {
        views.videoEditorPlayPause.setImageResource(if (isPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    }

    private fun togglePlayback() {
        if (isPlaying()) pausePlayback() else startPlayback()
    }

    private fun isPlaying() = animatedPlayer?.isPlaying ?: (player?.isPlaying == true)

    private fun startPlayback() {
        animatedPlayer?.let {
            it.start()
            updatePlayPauseIcon()
            return
        }
        val player = player ?: return
        if (isAtCut(player.positionUs)) {
            // Seeking is asynchronous, so the first ticks after this can still report a position past
            // the cut — which used to stop playback again immediately, taking a second press to start.
            restartingUntilMs = SystemClock.uptimeMillis() + RESTART_SEEK_GRACE_MS
            seekTo(startUs)
        }
        // Restores what stopping at the cut silenced (the players that need silencing).
        applyVolume()
        // The trim is the player's own business from here: where it can, it plays the clip and stops at
        // its end, picture and sound together, rather than being watched and paused from the outside.
        player.setPlaybackRange(startUs, endUs.takeIf { it > startUs } ?: durationUs)
        player.play()
        if (speedAwaitingPlayback) pushPlaybackSpeed(player)
        handler.post(playbackTicker)
        updatePlayPauseIcon()
    }

    private fun pausePlayback() {
        restartingUntilMs = 0L
        cancelScrubberGlide()
        animatedPlayer?.let {
            it.pause()
            updatePlayPauseIcon()
            return
        }
        handler.removeCallbacks(playbackTicker)
        player?.pause()
        updatePlayPauseIcon()
    }

    /** Playback runs the trimmed window and stops at its end, where the export would. */
    private val playbackTicker = object : Runnable {
        override fun run() {
            val player = player ?: return
            // MediaPlayer's position walks backwards between reports; taken as read it stutters the bar.
            // Same smoothing, on the same cadence, as the upload previewer's scrubber.
            val positionUs = PlaybackPosition.smooth(
                    rawMs = (player.positionUs / 1000).toInt(),
                    lastMs = (lastReportedPositionUs / 1000).toInt(),
                    durationMs = (durationUs / 1000).toInt(),
                    playing = true,
                    loopRestartMs = (startUs / 1000).toInt(),
            ) * 1000L
            val tickAt = SystemClock.uptimeMillis()
            if (lastTickAt > 0 && positionUs > lastReportedPositionUs) {
                val wallMs = tickAt - lastTickAt
                if (wallMs > 0) {
                    observedMediaUs += positionUs - lastReportedPositionUs
                    observedWallMs += wallMs
                }
            }
            lastTickAt = tickAt
            lastReportedPositionUs = positionUs
            setPlayhead(positionUs)
            if (player.enforcesRange) {
                // The clip's end is the player's, so the ticker only moves the playhead; stopAtEnd()
                // arrives from the player itself, on the frame it actually finished on.
                handler.postDelayed(this, VIDEO_PROGRESS_INTERVAL_MS.toLong())
                return
            }
            // endUs is zero until the metadata probe lands, and stopping against it then would
            // park playback at the start every tick.
            if (endUs > 0 && positionUs >= stopThresholdUs()) {
                // Still waiting for a restart's seek to land; stopping here would park playback at the
                // cut it is trying to leave.
                if (SystemClock.uptimeMillis() < restartingUntilMs) {
                    handler.postDelayed(this, VIDEO_PROGRESS_INTERVAL_MS.toLong())
                    return
                }
                stopAtEnd()
                return
            }
            restartingUntilMs = 0L
            handler.postDelayed(this, nextTickDelayMs(positionUs))
        }
    }

    /**
     * A tick lands on the cut rather than after it. Checking every 100ms of wall time means the media
     * advances 100ms times the playback speed between checks, so playback ran that far past the cut
     * before anything noticed — the per-frame view and the export stop exactly there, and the sped-up
     * preview showed a sliver of the clip that would never be exported.
     */
    private fun nextTickDelayMs(positionUs: Long): Long {
        val interval = VIDEO_PROGRESS_INTERVAL_MS.toLong()
        if (endUs <= 0) return interval
        // Near the cut, look every frame rather than working out when to look from the speed that was
        // asked for. What a player actually does with a playback speed is its own business — a device
        // that runs a little fast turns any such arithmetic into an overrun, and that grows with speed.
        val remainingUs = stopThresholdUs() - positionUs
        return if (remainingUs <= NEAR_CUT_US) MIN_TICK_MS else interval
    }

    /** The cut is where this clip ends; playing again starts it over from the other cut. */
    private fun stopAtEnd() {
        DebugLog.i { "MEDIADBG VEXP preview stopped at ${player?.positionUs}us endUs=$endUs startUs=$startUs" +
                        " speed=${playbackSpeed.speed} ranged=${player?.enforcesRange}" +
                        " observedRate=${if (observedWallMs > 0) (observedMediaUs / 1000.0 / observedWallMs) else -1.0}" }
        observedMediaUs = 0
        observedWallMs = 0
        lastTickAt = 0
        if (player?.enforcesRange == true) {
            // Playback already ran out at the cut, picture and sound on the same frame, so there is
            // nothing to silence and nothing to seek back to.
            pausePlayback()
            setPlayhead(endUs)
            return
        }
        // Silence first: sound already handed to the audio sink is heard after pause() whatever the
        // picture does, so the clip could be seen to end on the cut and still be heard past it.
        // The user's volume comes back when playback next starts.
        player?.setVolume(0f)
        pausePlayback()
        // Pausing does not stop the picture where the decision was made: frames already in flight keep
        // being drawn, and at 3x a few milliseconds of that is a tenth of a second of clip past the cut.
        // Landing on the cut leaves exactly the frame the per-frame view and the export end on.
        if (endUs > 0 && canSeekPrecisely()) seekTo(endUs) else setPlayhead(endUs)
    }

    /**
     * Plain seekTo lands on the previous sync frame, which can be seconds earlier — worse than the
     * overrun it would be correcting. SEEK_CLOSEST is API 26+.
     */
    private fun canSeekPrecisely() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || animatedPlayer != null

    /** One frame of the clip, the granularity the cut and the export are accurate to. */
    private fun frameDurationUs(): Long =
            if (frameRate > 0f) (1_000_000f / frameRate).toLong().coerceAtLeast(1) else DEFAULT_FRAME_DURATION_US

    /**
     * Where the ticker calls it a day, which is before the cut rather than at it. The frames between
     * there and the cut are the ones already on their way to the screen when pause is issued — and each
     * of those is worth a frame times the playback speed in clip time, which is how a tenth of a second
     * of picture ran past the cut at 3x. Stopping early costs nothing: [stopAtEnd] then seeks to the cut,
     * so the frame left on screen is the one the per-frame view and the export end on either way.
     */
    private fun stopThresholdUs(): Long {
        val speed = playbackSpeed.speed.coerceAtLeast(1f)
        // Two parts, both in clip time and both proportional to the speed: the tick that notices can be
        // a whole tick late, and the frames already queued for the screen keep being drawn after pause()
        // — which is the picture running past the cut. Stopping this far ahead costs nothing visible,
        // since the seek below lands the final frame on the cut either way.
        val queuedFrames = frameDurationUs() * QUEUED_FRAMES * speed
        val tickLag = MIN_TICK_MS * 1000f * speed
        return (endUs - (queuedFrames + tickLag).toLong()).coerceAtLeast(startUs)
    }

    /**
     * Playback fights a drag: the ticker keeps writing the (lagging) player position back into the
     * playhead. Pause for the duration of the gesture and resume afterwards if we were playing.
     */
    private fun beginScrubbing() {
        cancelScrubberGlide()
        if (scrubbing) return
        scrubbing = true
        resumeAfterScrub = isPlaying()
        handler.removeCallbacks(playbackTicker)
        pausePlayback()
    }

    /**
     * A short burst of sound at the new position, on a second surface-less player: audio only comes
     * out of a *running* player, and running the visible one would advance the picture off the frame
     * being set.
     */
    private fun blipAudio(us: Long) {
        // Deferred to the first scrub: opened alongside the video player it is a second decoder on
        // the same clip, and that contention (plus whatever the previewer still held) could cost
        // the video player its codec — the editor would open on a black frame until reopened.
        if (audioPlayer == null) {
            prepareAudioPlayer()
            return
        }
        val audioPlayer = audioPlayer?.takeIf { audioReady } ?: return
        // One burst at a time, always to the newest position: queued seeks fall further behind the
        // finger the faster it moves.
        if (audioBurstActive) {
            pendingAudioUs = us
            return
        }
        audioBurstActive = true
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioPlayer.seekTo(us / 1000, MediaPlayer.SEEK_CLOSEST)
            } else {
                audioPlayer.seekTo((us / 1000).toInt())
            }
            // A seek that threw never completes, and the flag would latch scrub audio off for good.
        }.onFailure { audioBurstActive = false }
    }

    private val stopBlipRunnable = Runnable {
        audioPlayer?.takeIf { it.isPlaying }?.pause()
        audioBurstActive = false
        val next = pendingAudioUs
        pendingAudioUs = null
        if (scrubbing && next != null) blipAudio(next)
    }

    /**
     * @param resume whether playback picks up again. A trim edit stays paused on the frame it
     * landed on — playing away from it immediately hides the very thing being adjusted.
     */
    private fun endScrubbing(us: Long?, resume: Boolean = true) {
        // Leaving per-frame mode and releasing the handle both end the same gesture.
        if (!scrubbing) return
        scrubbing = false
        handler.removeCallbacks(stopBlipRunnable)
        audioPlayer?.takeIf { it.isPlaying }?.pause()
        audioBurstActive = false
        pendingAudioUs = null
        us?.let { seekTo(it) }
        if (resume && resumeAfterScrub) {
            resumeAfterScrub = false
            startPlayback()
        } else {
            resumeAfterScrub = false
            pausePlayback()
        }
    }

    /** Seeks are expensive enough that one per touch-move event stutters, on a player that lets them. */
    private fun seekThrottled(us: Long) {
        setPlayhead(us)
        blipAudio(us)
        if (player?.coalescesSeeks == true) {
            // Mid-drag the keyframe is enough and it keeps up with the finger; letting go lands the frame.
            seekTo(us, precise = fineMode)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekAt < SEEK_THROTTLE_MS) return
        lastSeekAt = now
        seekTo(us)
    }

    private fun seekTo(us: Long, precise: Boolean = true) {
        lastReportedPositionUs = us
        setPlayhead(us)
        animatedPlayer?.let {
            it.seekTo(us)
            return
        }
        val player = player ?: return
        // Any frame of the source has to be reachable while scrubbing or stepping, which a clipped
        // player cannot do — so the bounds come off until playback starts again.
        player.clearPlaybackRange()
        player.seekTo(us, precise)
    }

    private fun updateDurationLabel() {
        views.videoEditorDurationLabel.text = if (playbackSpeed.isDefault) {
            getString(CommonStrings.video_editor_range, formatTime(startUs), formatTime(endUs))
        } else {
            // The resulting length is the useful number once the speed is no longer 1x.
            getString(
                        CommonStrings.video_editor_range_output,
                    formatTime(startUs), formatTime(endUs), formatTime(outputDurationUs())
            )
        }
    }

    /** Per-frame trimming reads against frames, not fractions of a second: m:ss:frame. */
    private fun formatTime(us: Long): String {
        val totalSeconds = us / 1_000_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (fineMode) {
            val frame = (us % 1_000_000) * frameRate.toLong() / 1_000_000
            String.format(Locale.US, "%d:%02d:%02d", minutes, seconds, frame)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun save() {
        val edits = VideoEditorEdits(
                startUs = startUs,
                endUs = endUs,
                durationUs = durationUs,
                rotationDegrees = views.videoEditorCropOverlay.rotationDegrees,
                volume = volume,
                reversed = reversed,
                crop = views.videoEditorCropOverlay.currentCrop(),
                speed = playbackSpeed
        )
        // Left exactly as it was opened: the attachment already is this export.
        if (edits == initialEdits) {
            finish()
            return
        }
        if (!edits.hasChanges) {
            setResult(RESULT_OK, restoreOriginalResult())
            finish()
            return
        }
        pausePlayback()
        showExportOverlay(true)
        exportJob = lifecycleScope.launch {
            val result = try {
                runCatching { runExport(edits) }
            } finally {
                showExportOverlay(false)
            }
            // Cancelling comes back as a failed Result; complaining about it would be nonsense.
            if (result.exceptionOrNull() is CancellationException) return@launch
            result.fold(
                    onSuccess = { output ->
                        if (output.audioDropped) {
                            Toast.makeText(
                                    this@VideoEditorActivity, getString(CommonStrings.video_editor_audio_dropped), Toast.LENGTH_SHORT
                            ).show()
                        }
                        setResult(RESULT_OK, Intent().apply {
                            putExtra(EXTRA_RESULT_URI, output.uri.toString())
                            putExtra(EXTRA_RESULT_WIDTH, output.width)
                            putExtra(EXTRA_RESULT_HEIGHT, output.height)
                            putExtra(EXTRA_RESULT_SIZE, output.size)
                            putExtra(EXTRA_RESULT_DURATION, output.durationMs)
                            putExtra(EXTRA_RESULT_MIME_TYPE, output.mimeType)
                            putExtra(EXTRA_RESULT_EDITS, edits)
                        })
                        finish()
                    },
                    onFailure = { error ->
                        Timber.w(error, "VideoEditor: export failed")
                        Toast.makeText(this@VideoEditorActivity, messageFor(error), Toast.LENGTH_LONG).show()
                    }
            )
        }
    }

    /** The video branch needs API 18, and is only reached when there is no animated source. */
    @SuppressLint("NewApi")
    private suspend fun runExport(edits: VideoEditorEdits): VideoEditorExporter.Result {
        val progress = VideoEditProgressListener { percent ->
            handler.post { views.videoEditorExportProgress.progress = percent }
        }
        val animatedSource = animatedSource
        return if (animatedSource != null) {
            val targetWidth = intent.getIntExtra(EXTRA_TARGET_WIDTH, 0)
            val targetHeight = intent.getIntExtra(EXTRA_TARGET_HEIGHT, 0)
            AnimatedImageExporter.export(
                    context = this,
                    source = animatedSource,
                    format = animatedFormat,
                    displayName = displayName,
                    edits = edits,
                    targetSize = (targetWidth to targetHeight).takeIf { targetWidth > 0 && targetHeight > 0 },
                    progressListener = progress
            )
        } else {
            VideoEditorExporter.export(this, sourceUri, displayName, edits, progress)
        }
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is VideoEditException.NotEnoughSpace -> getString(CommonStrings.video_editor_no_space)
        is VideoEditException.UnsupportedCodec -> getString(CommonStrings.video_editor_unsupported)
        is AnimatedImageExporter.TransparencyUnsupportedException -> getString(CommonStrings.animated_image_editor_no_transparency)
        is AnimatedImageExporter.AnimatedImageException -> getString(CommonStrings.animated_image_editor_export_failed)
        else -> getString(CommonStrings.video_editor_export_failed)
    }

    private fun showExportOverlay(visible: Boolean) {
        exporting = visible
        views.videoEditorExportOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        // The overlay covers everything, but the save button and the app bar are raised above it on
        // API 21+, where elevation decides what a touch lands on rather than the order in the layout.
        ViewCompat.setElevation(views.videoEditorExportOverlay, if (visible) EXPORT_OVERLAY_ELEVATION else 0f)
        views.videoEditorSaveButton.isEnabled = !visible
        invalidateOptionsMenu()
        views.videoEditorExportProgress.progress = 0
        if (visible) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onResume() {
        super.onResume()
        activityPaused = false
    }

    override fun onPause() {
        activityPaused = true
        super.onPause()
        pausePlayback()
        // The previewer underneath re-creates its player in onResume, before this onDestroy —
        // release the decoders first or it can lose the codec race.
        if (isFinishing) {
            handler.removeCallbacks(playbackTicker)
            releasePlayer()
            animatedPlayer?.release()
            animatedPlayer = null
        }
    }

    override fun onDestroy() {
        cancelScrubberGlide()
        handler.removeCallbacks(playbackTicker)
        releasePlayer()
        animatedPlayer?.release()
        animatedPlayer = null
        // Only ever a copy of the source; the export has already read what it needs.
        animatedSource?.delete()
        surface?.release()
        surface = null
        super.onDestroy()
    }

    /** Never releases the surface: it belongs to the SurfaceTexture callbacks, not the player. */
    private fun releasePlayer() {
        handler.removeCallbacks(stopBlipRunnable)
        // Only ever attached on KitKat+, where LoudnessEnhancer exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            playerBoost?.release()
            audioBoost?.release()
        }
        playerBoost = null
        audioBoost = null
        player?.release()
        player = null
        audioPlayer?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioPlayer = null
        audioReady = false
    }

    data class Output(
            val uri: Uri,
            val width: Int,
            val height: Int,
            val size: Long,
            val durationMs: Long,
            val mimeType: String,
            val edits: VideoEditorEdits
    )

    companion object {
        /** Below this the icon shows a quieter speaker. */
        private const val QUIET_GAIN = 0.5f

        private const val THUMBNAIL_COUNT = 10

        // Past this the playhead has some catching up to do — a seek, a loop or a stall — and snapping
        // reads better than gliding there.
        private const val SCRUBBER_GLIDE_MAX_STEP_US = 1_200_000L

        // How long a restart's seek is given to land before the end of the clip counts again.
        private const val RESTART_SEEK_GRACE_MS = 1_000L

        // A floor for the shortened final tick — a frame — so a player whose position momentarily
        // stops advancing cannot spin the handler.
        private const val MIN_TICK_MS = 16L

        /** Frames a paused pipeline can still paint, each worth a frame times the speed in clip time. */
        private const val QUEUED_FRAMES = 3

        /** Within this much of the cut, every frame is checked rather than scheduled for. */
        private const val NEAR_CUT_US = 400_000L

        /** Frame duration assumed where the probe gave no frame rate: 30fps. */
        private const val DEFAULT_FRAME_DURATION_US = 33_333L
        private const val SEEK_THROTTLE_MS = 40L
        private const val AUDIO_BLIP_MS = 120L

        /** Above the save button's 6dp and the app bar's 4dp, so neither takes a touch. */
        private const val EXPORT_OVERLAY_ELEVATION = 16f

        private const val EXTRA_SOURCE_URI = "EXTRA_SOURCE_URI"
        private const val EXTRA_DISPLAY_NAME = "EXTRA_DISPLAY_NAME"
        private const val EXTRA_EDITS = "EXTRA_EDITS"
        private const val EXTRA_ANIMATED_FORMAT = "EXTRA_ANIMATED_FORMAT"
        private const val EXTRA_TARGET_WIDTH = "EXTRA_TARGET_WIDTH"
        private const val EXTRA_TARGET_HEIGHT = "EXTRA_TARGET_HEIGHT"
        private const val EXTRA_RESULT_URI = "EXTRA_RESULT_URI"
        private const val EXTRA_RESULT_WIDTH = "EXTRA_RESULT_WIDTH"
        private const val EXTRA_RESULT_HEIGHT = "EXTRA_RESULT_HEIGHT"
        private const val EXTRA_RESULT_SIZE = "EXTRA_RESULT_SIZE"
        private const val EXTRA_RESULT_DURATION = "EXTRA_RESULT_DURATION"
        private const val EXTRA_RESULT_MIME_TYPE = "EXTRA_RESULT_MIME_TYPE"
        private const val EXTRA_RESULT_EDITS = "EXTRA_RESULT_EDITS"

        fun newIntent(
                context: Context,
                source: Uri,
                displayName: String?,
                edits: VideoEditorEdits?,
                targetSize: Pair<Int, Int>? = null,
                animatedFormat: AnimatedImageFormat? = null,
        ): Intent {
            return Intent(context, VideoEditorActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_URI, source.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_EDITS, edits)
                putExtra(EXTRA_ANIMATED_FORMAT, animatedFormat?.name)
                targetSize?.let {
                    putExtra(EXTRA_TARGET_WIDTH, it.first)
                    putExtra(EXTRA_TARGET_HEIGHT, it.second)
                }
            }
        }

        fun getOutput(intent: Intent): Output? {
            val uri = intent.getStringExtra(EXTRA_RESULT_URI)?.toUri() ?: return null
            return Output(
                    uri = uri,
                    width = intent.getIntExtra(EXTRA_RESULT_WIDTH, 0),
                    height = intent.getIntExtra(EXTRA_RESULT_HEIGHT, 0),
                    size = intent.getLongExtra(EXTRA_RESULT_SIZE, 0),
                    durationMs = intent.getLongExtra(EXTRA_RESULT_DURATION, 0),
                    mimeType = intent.getStringExtra(EXTRA_RESULT_MIME_TYPE).orEmpty(),
                    edits = intent.getParcelableExtraCompat(EXTRA_RESULT_EDITS) ?: VideoEditorEdits()
            )
        }
    }
}
