/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityVideoEditorBinding
import im.vector.app.features.attachments.editor.restoreOriginalResult
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

    private var player: MediaPlayer? = null
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
    private var resumeAfterScrub = false
    private var lastSeekAt = 0L
    private var pendingEditedUs: Long? = null
    private var audioBurstActive = false
    private var pendingAudioUs: Long? = null
    private var volume = PlaybackVolume()
    private var reversed = false
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
        views.videoEditorCropOverlay.onTap = { togglePlayback() }

        views.videoEditorTimeline.listener = VideoTimelineStripView.Listener { start, end, dragging ->
            // Null means neither edge moved, and a mere grab must not disturb playback.
            val edited = when {
                start != startUs -> start
                end != endUs -> end
                else -> null
            }
            startUs = start
            endUs = end
            applyTrimToAnimatedPlayer()
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
        views.videoEditorTimeline.onFineModeChanged = { fine, positionUs ->
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
            R.id.videoEditorSpeedAction -> {
                showSpeedDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (!playbackSpeed.isDefault && !warnedAboutSpeedPreview) {
                warnedAboutSpeedPreview = true
                Toast.makeText(this, getString(CommonStrings.video_editor_speed_preview_unsupported), Toast.LENGTH_LONG).show()
            }
            return
        }
        // Setting the parameters on a paused player starts it, and the sound it gets out before it
        // can be paused again is a click — one per step of the speed dialog, which is a crackle.
        // The speed only means anything while playing anyway, so it waits for that.
        if (!player.isPlaying) {
            speedAwaitingPlayback = true
            return
        }
        pushPlaybackSpeed(player)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun pushPlaybackSpeed(player: MediaPlayer) {
        speedAwaitingPlayback = false
        runCatching {
            player.playbackParams = player.playbackParams
                    .setSpeed(playbackSpeed.speed)
                    // Tape behaviour is pitch riding along with the speed; the alternative holds it.
                    .setPitch(if (playbackSpeed.changePitch) playbackSpeed.speed else 1f)
        }.onFailure { Timber.w(it, "VideoEditor: cannot preview speed ${playbackSpeed.speed}") }
    }

    private fun resetEdits() {
        if (durationUs <= 0) return
        startUs = 0
        endUs = durationUs
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
            updateDurationLabel()
            addAnimatedThumbnails(source)
            animatedPlayer = AnimatedFramePlayer(source, views.videoEditorTextureView, handler) { positionUs ->
                views.videoEditorTimeline.playheadUs = positionUs
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
                    resumePositionUs = it.currentPosition * 1000L
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
        player = MediaPlayer().apply {
            setSurface(this@VideoEditorActivity.surface)
            setOnPreparedListener {
                applyVolume()
                applyPlaybackSpeed()
                seekTo(resumePositionUs.takeIf { position -> position > startUs } ?: startUs)
                // A seek before playback starts does not reliably render a frame, so an idle
                // editor would show nothing at all: play either way, and stop on the first frame
                // when playback was not running when we left — or when preparation outlived the
                // activity being on screen, which must not start sound in the background.
                pauseOnFirstFrame = activityPaused || (resumePositionUs > 0 && !resumePlaying)
                startPlayback()
            }
            // Reaching the end of the file bypasses the ticker's own check, and nothing else
            // would notice playback had stopped.
            setOnCompletionListener { stopAtEnd() }
            setOnErrorListener { _, what, extra ->
                Timber.w("VideoEditor: player error $what/$extra")
                Toast.makeText(
                        this@VideoEditorActivity, getString(CommonStrings.video_editor_load_failed), Toast.LENGTH_SHORT
                ).show()
                true
            }
            runCatching {
                setDataSource(this@VideoEditorActivity, sourceUri)
                prepareAsync()
            }.onFailure { Timber.w(it, "VideoEditor: cannot open $sourceUri") }
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
        runCatching { player?.setVolume(scalar, scalar) }
        runCatching { audioPlayer?.setVolume(scalar, scalar) }
        applyBoost()
    }

    /** Anything above 100% is out of MediaPlayer's reach, and only KitKat has the effect for it. */
    private fun applyBoost() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        playerBoost = tuneBoost(playerBoost, player)
        audioBoost = tuneBoost(audioBoost, audioPlayer)
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun tuneBoost(existing: LoudnessBoost?, target: MediaPlayer?): LoudnessBoost? {
        val boost = existing ?: target?.let { LoudnessBoost.attachTo(it.audioSessionId) } ?: return null
        boost.setGain(volume.effectiveGain)
        return boost
    }

    private fun togglePlayback() {
        if (isPlaying()) pausePlayback() else startPlayback()
    }

    private fun isPlaying() = animatedPlayer?.isPlaying ?: (player?.isPlaying == true)

    private fun startPlayback() {
        animatedPlayer?.let {
            it.start()
            return
        }
        val player = player ?: return
        if (endUs > 0 && player.currentPosition * 1000L >= endUs) seekTo(startUs)
        player.start()
        if (speedAwaitingPlayback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pushPlaybackSpeed(player)
        handler.post(playbackTicker)
    }

    private fun pausePlayback() {
        animatedPlayer?.let {
            it.pause()
            return
        }
        handler.removeCallbacks(playbackTicker)
        player?.takeIf { it.isPlaying }?.pause()
    }

    /** Playback runs the trimmed window and stops at its end, where the export would. */
    private val playbackTicker = object : Runnable {
        override fun run() {
            val player = player ?: return
            val positionUs = player.currentPosition * 1000L
            views.videoEditorTimeline.playheadUs = positionUs
            // endUs is zero until the metadata probe lands, and stopping against it then would
            // park playback at the start every tick.
            if (endUs > 0 && positionUs >= endUs) {
                stopAtEnd()
                return
            }
            handler.postDelayed(this, PLAYHEAD_INTERVAL_MS)
        }
    }

    /** The cut is where this clip ends; playing again starts it over from the other cut. */
    private fun stopAtEnd() {
        pausePlayback()
        views.videoEditorTimeline.playheadUs = endUs
    }

    /**
     * Playback fights a drag: the ticker keeps writing the (lagging) player position back into the
     * playhead. Pause for the duration of the gesture and resume afterwards if we were playing.
     */
    private fun beginScrubbing() {
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

    /** Seeks are expensive enough that one per touch-move event stutters. */
    private fun seekThrottled(us: Long) {
        views.videoEditorTimeline.playheadUs = us
        blipAudio(us)
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekAt < SEEK_THROTTLE_MS) return
        lastSeekAt = now
        seekTo(us)
    }

    private fun seekTo(us: Long) {
        views.videoEditorTimeline.playheadUs = us
        animatedPlayer?.let {
            it.seekTo(us)
            return
        }
        val player = player ?: return
        runCatching {
            // Plain seekTo() lands on the previous sync frame, which on a sparsely keyframed video
            // can be seconds earlier — that reads as the playhead jumping backwards.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(us / 1000, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo((us / 1000).toInt())
            }
        }
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
    }

    override fun onDestroy() {
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
        playerBoost?.release()
        audioBoost?.release()
        playerBoost = null
        audioBoost = null
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
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
        private const val PLAYHEAD_INTERVAL_MS = 60L
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
