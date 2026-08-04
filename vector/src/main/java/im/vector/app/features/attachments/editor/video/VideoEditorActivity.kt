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
import android.view.WindowManager
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityVideoEditorBinding
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.mediatranscode.MediaSourceInfo
import im.vector.lib.mediatranscode.VideoEditException
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

// Only reachable through ContentAttachmentData.isVideoEditable(), which requires API 18.
@SuppressLint("NewApi")
@AndroidEntryPoint
class VideoEditorActivity : VectorBaseActivity<ActivityVideoEditorBinding>() {

    private lateinit var sourceUri: Uri
    private var displayName: String? = null

    private lateinit var cropController: VideoCropController

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
    private var userRotation = 0
    private var muted = false

    override fun getOtherThemes() = ActivityOtherThemes.AttachmentsPreview

    override fun getBinding() = ActivityVideoEditorBinding.inflate(layoutInflater)

    override val rootView: View
        get() = views.coordinatorLayout

    override fun initUiAndData() {
        sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.toUri() ?: run { finish(); return }
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)

        setupToolbar(views.videoEditorToolbar).allowBack()

        // This activity's theme has no accent variant, so ?colorAccent resolves to the default
        // green; ThemeUtils reads the configured application theme instead.
        val accent = ThemeUtils.getColor(this, com.google.android.material.R.attr.colorAccent)
        views.videoEditorSaveButton.backgroundTintList = ColorStateList.valueOf(accent)
        views.videoEditorPlayBadge.setColorFilter(accent)
        tintProgressBar(accent)
        views.videoEditorSaveButton.setOnClickListener { save() }
        views.videoEditorExportCancel.setOnClickListener { exportJob?.cancel() }
        cropController = VideoCropController(
                container = views.videoEditorSurfaceContainer,
                window = views.videoEditorCropWindow,
                textureView = views.videoEditorTextureView,
                frame = views.videoEditorCropFrame,
                onTap = { togglePlayback() }
        )

        views.videoEditorTimeline.listener = VideoTimelineStripView.Listener { start, end, dragging ->
            // Null means neither edge moved, and a mere grab must not disturb playback.
            val edited = when {
                start != startUs -> start
                end != endUs -> end
                else -> null
            }
            startUs = start
            endUs = end
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_video_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.videoEditorMuteAction)?.setIcon(
                if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic_on
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.videoEditorResetAction -> {
                resetEdits()
                true
            }
            R.id.videoEditorCropAction -> {
                chooseCropRatio()
                true
            }
            R.id.videoEditorRotateAction -> {
                rotateClockwise()
                true
            }
            R.id.videoEditorMuteAction -> {
                toggleMute()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Pinch and pan crop freely; the ratios only fix the shape of the window they work inside. */
    private fun chooseCropRatio() {
        val labels = CROP_RATIOS.map { getString(it.first) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
                .setTitle(CommonStrings.video_editor_crop)
                .setItems(labels) { _, which ->
                    cropController.aspectRatio = CROP_RATIOS[which].second
                }
                .show()
    }

    private fun resetEdits() {
        if (durationUs <= 0) return
        startUs = 0
        endUs = durationUs
        userRotation = 0
        if (muted) toggleMute()
        cropController.reset()
        views.videoEditorTimeline.setTrim(startUs, endUs)
        updateDurationLabel()
        seekTo(startUs)
    }

    /** progressTintList is API 21+, and this screen runs from 18. */
    @Suppress("DEPRECATION")
    private fun tintProgressBar(accent: Int) {
        views.videoEditorExportProgress.progressDrawable?.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
    }

    private fun loadMetadata() {
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
            val edits = intent.getParcelableExtraCompat<VideoEditorEdits>(EXTRA_EDITS)
            startUs = edits?.startUs ?: 0L
            endUs = edits?.endUs?.takeIf { it > 0 } ?: durationUs
            userRotation = edits?.rotationDegrees ?: 0
            if (edits?.muted == true) toggleMute()
            cropController.rotationDegrees = userRotation
            cropController.applyCropRect(edits?.crop)
            views.videoEditorTimeline.setTrim(startUs, endUs)
            updateDurationLabel()
            extractThumbnails()
        }
    }

    /**
     * Frames are decoded one at a time and scaled to strip height immediately — getFrameAtTime is
     * 100-300ms and a full-size frame each on the oldest devices this fork supports.
     */
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
            surface = Surface(texture)
            preparePlayer()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            releasePlayer()
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
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
        prepareAudioPlayer()
        player = MediaPlayer().apply {
            setSurface(this@VideoEditorActivity.surface)
            setOnPreparedListener {
                cropController.setVideoSize(it.videoWidth, it.videoHeight)
                applyVolume()
                seekTo(startUs)
                // A seek before playback starts does not reliably render a frame, so an idle
                // editor would show nothing at all.
                startPlayback()
            }
            // Reaching the end of the file bypasses the ticker's loop check, and playback stops
            // without anything else noticing.
            setOnCompletionListener {
                seekTo(startUs)
                startPlayback()
            }
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

    private fun rotateClockwise() {
        userRotation = (userRotation + 90) % 360
        cropController.rotationDegrees = userRotation
    }

    /** Mutes the preview as well: the export is what the editor is showing. */
    private fun toggleMute() {
        muted = !muted
        invalidateOptionsMenu()
        applyVolume()
    }

    private fun applyVolume() {
        val volume = if (muted) 0f else 1f
        runCatching { player?.setVolume(volume, volume) }
        runCatching { audioPlayer?.setVolume(volume, volume) }
    }

    private fun togglePlayback() {
        val player = player ?: return
        if (player.isPlaying) pausePlayback() else startPlayback()
    }

    private fun startPlayback() {
        val player = player ?: return
        if (endUs > 0 && player.currentPosition * 1000L >= endUs) seekTo(startUs)
        player.start()
        handler.post(playbackTicker)
        syncPlayBadge()
    }

    private fun pausePlayback() {
        handler.removeCallbacks(playbackTicker)
        player?.takeIf { it.isPlaying }?.pause()
        syncPlayBadge()
    }

    /**
     * Driven from the player rather than set per call site, so no path can leave it out of step.
     * Mid-gesture it is parked between audio blips, which is not a pause worth advertising.
     */
    private fun syncPlayBadge() {
        val paused = !scrubbing && player?.isPlaying != true
        views.videoEditorPlayBadge.visibility = if (paused) View.VISIBLE else View.GONE
    }

    /** Playback loops the trimmed window so what plays is what will be exported. */
    private val playbackTicker = object : Runnable {
        override fun run() {
            val player = player ?: return
            val positionUs = player.currentPosition * 1000L
            views.videoEditorTimeline.playheadUs = positionUs
            // endUs is zero until the metadata probe lands, and looping against it then would drag
            // playback back to the start every tick.
            if (endUs > 0 && positionUs >= endUs) seekTo(startUs)
            syncPlayBadge()
            handler.postDelayed(this, PLAYHEAD_INTERVAL_MS)
        }
    }

    /**
     * Playback fights a drag: the ticker keeps writing the (lagging) player position back into the
     * playhead. Pause for the duration of the gesture and resume afterwards if we were playing.
     */
    private fun beginScrubbing() {
        if (scrubbing) return
        scrubbing = true
        resumeAfterScrub = player?.isPlaying == true
        handler.removeCallbacks(playbackTicker)
        pausePlayback()
    }

    /**
     * A short burst of sound at the new position, on a second surface-less player: audio only comes
     * out of a *running* player, and running the visible one would advance the picture off the frame
     * being set.
     */
    private fun blipAudio(us: Long) {
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
        }
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
        views.videoEditorDurationLabel.text = getString(
                CommonStrings.video_editor_range, formatTime(startUs), formatTime(endUs)
        )
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
                rotationDegrees = userRotation,
                muted = muted,
                crop = cropController.cropRect
        )
        if (!edits.hasChanges) {
            finish()
            return
        }
        pausePlayback()
        showExportOverlay(true)
        exportJob = lifecycleScope.launch {
            val result = try {
                runCatching {
                    VideoEditorExporter.export(this@VideoEditorActivity, sourceUri, displayName, edits) { percent ->
                        handler.post { views.videoEditorExportProgress.progress = percent }
                    }
                }
            } finally {
                showExportOverlay(false)
            }
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

    private fun messageFor(error: Throwable): String = when (error) {
        is VideoEditException.NotEnoughSpace -> getString(CommonStrings.video_editor_no_space)
        is VideoEditException.UnsupportedCodec -> getString(CommonStrings.video_editor_unsupported)
        else -> getString(CommonStrings.video_editor_export_failed)
    }

    private fun showExportOverlay(visible: Boolean) {
        views.videoEditorExportOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        views.videoEditorExportProgress.progress = 0
        if (visible) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        super.onPause()
        pausePlayback()
    }

    override fun onDestroy() {
        handler.removeCallbacks(playbackTicker)
        releasePlayer()
        surface?.release()
        surface = null
        super.onDestroy()
    }

    /** Never releases the surface: it belongs to the SurfaceTexture callbacks, not the player. */
    private fun releasePlayer() {
        handler.removeCallbacks(stopBlipRunnable)
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
        private const val THUMBNAIL_COUNT = 10
        private const val PLAYHEAD_INTERVAL_MS = 60L
        private const val SEEK_THROTTLE_MS = 40L
        private const val AUDIO_BLIP_MS = 120L

        private val CROP_RATIOS = listOf(
                CommonStrings.video_editor_crop_original to null,
                CommonStrings.video_editor_crop_square to 1f,
                CommonStrings.video_editor_crop_portrait to 4f / 5f,
                CommonStrings.video_editor_crop_wide to 16f / 9f,
                CommonStrings.video_editor_crop_tall to 9f / 16f,
        )

        private const val EXTRA_SOURCE_URI = "EXTRA_SOURCE_URI"
        private const val EXTRA_DISPLAY_NAME = "EXTRA_DISPLAY_NAME"
        private const val EXTRA_EDITS = "EXTRA_EDITS"
        private const val EXTRA_RESULT_URI = "EXTRA_RESULT_URI"
        private const val EXTRA_RESULT_WIDTH = "EXTRA_RESULT_WIDTH"
        private const val EXTRA_RESULT_HEIGHT = "EXTRA_RESULT_HEIGHT"
        private const val EXTRA_RESULT_SIZE = "EXTRA_RESULT_SIZE"
        private const val EXTRA_RESULT_DURATION = "EXTRA_RESULT_DURATION"
        private const val EXTRA_RESULT_MIME_TYPE = "EXTRA_RESULT_MIME_TYPE"
        private const val EXTRA_RESULT_EDITS = "EXTRA_RESULT_EDITS"

        fun newIntent(context: Context, source: Uri, displayName: String?, edits: VideoEditorEdits?): Intent {
            return Intent(context, VideoEditorActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_URI, source.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_EDITS, edits)
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
