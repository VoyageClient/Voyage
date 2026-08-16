/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.flattenAsScrim
import im.vector.app.core.extensions.resourcesFor
import im.vector.app.core.glide.MediaCache
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityAudioEditorBinding
import im.vector.app.features.attachments.editor.restoreOriginalResult
import im.vector.app.features.attachments.editor.video.PlaybackSpeed
import im.vector.app.features.attachments.editor.video.PlaybackSpeedDialog
import im.vector.app.features.attachments.editor.video.PlaybackVolume
import im.vector.app.features.attachments.editor.video.PlaybackVolumeDialog
import im.vector.app.features.attachments.editor.video.VideoTimelineStripView
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.app.features.attachments.preview.WaveformCache
import im.vector.app.features.attachments.preview.WaveformScrubView
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.audio.LoudnessBoost
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.mediatranscode.AudioEditExporter
import im.vector.lib.mediatranscode.AudioEditSpec
import im.vector.lib.mediatranscode.AudioWaveform
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
import java.util.UUID

/**
 * Trim, speed, volume and reverse for an audio attachment, modelled on the video editor. Writing
 * the result needs MediaMuxer, so the screen is only reachable from API 18 — see `isAudioEditable`.
 */
@AndroidEntryPoint
class AudioEditorActivity : VectorBaseActivity<ActivityAudioEditorBinding>() {

    private lateinit var sourceUri: Uri
    private var displayName: String? = null
    private var initialEdits: AudioEditorEdits? = null

    private var player: MediaPlayer? = null
    private var boost: LoudnessBoost? = null
    private var durationUs = 0L
    private var startUs = 0L
    private var endUs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var exportJob: Job? = null
    private var fineMode = false
    private var scrubbing = false
    private var resumeAfterScrub = false
    private var lastSeekAt = 0L
    private var volume = PlaybackVolume()
    private var playbackSpeed = PlaybackSpeed()
    private var reversed = false
    private var warnedAboutSpeedPreview = false
    private var speedAwaitingPlayback = false
    private var exporting = false

    /** Whether this orientation has room for the inline cover art; see [applyOrientationSizing]. */
    private var showInlineArt = true

    override val drawUnderSystemBars = true

    override fun getOtherThemes() = ActivityOtherThemes.AttachmentsPreview

    override fun getBinding() = ActivityAudioEditorBinding.inflate(layoutInflater)

    override val rootView: View
        get() = views.coordinatorLayout

    override fun initUiAndData() {
        makeSystemBarsTransparent()
        sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.toUri() ?: run { finish(); return }
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        initialEdits = intent.getParcelableExtraCompat(EXTRA_EDITS)

        applyInsets()
        applyOrientationSizing()
        setupToolbar(views.audioEditorToolbar).allowBack()
        views.audioEditorName.text = displayName

        val accent = ThemeUtils.getColorFromContextTheme(this, com.google.android.material.R.attr.colorAccent)
        val (fill, onFill) = ThemeUtils.accentFillOnDarkSurface(this)
        views.audioEditorSaveButton.backgroundTintList = ColorStateList.valueOf(fill)
        ImageViewCompat.setImageTintList(views.audioEditorSaveButton, ColorStateList.valueOf(onFill))
        tintProgressBar(accent)
        views.audioEditorSaveButton.setOnClickListener { save() }
        views.audioEditorExportCancel.setOnClickListener { exportJob?.cancel() }
        // Nothing to press and no band to hunt for: whatever the toolbar and the timeline do not
        // take is handed to the waveform, so a tap or a swipe anywhere plays, pauses or scrubs.
        views.coordinatorLayout.setOnTouchListener { _, event ->
            val copy = MotionEvent.obtain(event)
            copy.offsetLocation(-views.audioEditorWaveform.left.toFloat(), -views.audioEditorWaveform.top.toFloat())
            val handled = views.audioEditorWaveform.onTouchEvent(copy)
            copy.recycle()
            handled
        }
        views.audioEditorWaveform.onTap = { togglePlayback() }
        views.audioEditorWaveform.onSeek = { positionMs, phase ->
            val us = positionMs * 1000L
            when (phase) {
                // Parked for the gesture, and seeked once it is over: seeking at every touch move
                // plays a burst of sound per seek and stalls the drag doing it.
                WaveformScrubView.SeekPhase.MOVING -> {
                    beginScrubbing()
                    views.audioEditorTimeline.playheadUs = us
                }
                WaveformScrubView.SeekPhase.SETTLED -> endScrubbing(us)
                // A throw stopped by a touch: land there, but let the tap that follows decide
                // whether anything plays.
                WaveformScrubView.SeekPhase.INTERRUPTED -> endScrubbing(us, resume = false)
            }
        }

        views.audioEditorTimeline.listener = VideoTimelineStripView.Listener { start, end, dragging ->
            val edited = when {
                start != startUs -> start
                end != endUs -> end
                else -> null
            }
            startUs = start
            endUs = end
            applyTrimToWaveform()
            updateDurationLabel()
            if (dragging) {
                beginScrubbing()
                edited?.let { seekThrottled(it) }
            } else {
                endScrubbing(edited, resume = false)
            }
        }
        views.audioEditorTimeline.onFineModeChanged = { fine, positionUs ->
            fineMode = fine
            if (fine) {
                beginScrubbing()
                seekTo(positionUs)
            } else {
                endScrubbing(positionUs, resume = false)
            }
            updateDurationLabel()
        }
        views.audioEditorTimeline.onScrub = { us, dragging ->
            if (dragging) {
                beginScrubbing()
                seekThrottled(us)
            } else {
                endScrubbing(us)
            }
        }
        // A waveform reads in tenths of a second, so per-frame mode steps by 10ms here.
        views.audioEditorTimeline.frameRate = FINE_STEPS_PER_SECOND
        loadSource()
    }

    /**
     * This screen keeps its views across a rotation (configChanges, so an export in flight is not
     * killed), which also means the layout is never re-inflated — so the orientation-dependent
     * resources have to be re-read here by hand.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationSizing(newConfig)
    }

    /**
     * Resolved against the given configuration rather than [getResources]: this activity handles the
     * rotation itself, and its own Resources can still answer for the configuration it is leaving.
     */
    private fun applyOrientationSizing(config: android.content.res.Configuration = resources.configuration) {
        val res = resourcesFor(config)
        // Height is 0dp/match-constraints so the waveform can only take the room that is left;
        // this caps it at the design height for the orientation.
        views.audioEditorWaveform.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
            matchConstraintMaxHeight = res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.audio_preview_waveform_height)
        }
        views.audioEditorTimeline.updateLayoutParams {
            height = res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.audio_editor_timeline_height)
        }
        // Landscape has no room for the inline art beside the waveform; the backdrop still shows it.
        showInlineArt = res.getBoolean(im.vector.lib.ui.styles.R.bool.audio_preview_show_inline_art)
        views.audioEditorArt.isVisible = views.audioEditorArt.drawable != null && showInlineArt
    }

    /** The backdrop runs to the edges of the screen; the controls stay clear of the system bars. */
    private fun applyInsets() {
        views.audioEditorAppBar.flattenAsScrim(TOP_BAR_DIM)
        val saveMargin = views.audioEditorSaveButton.marginBottom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // On the views themselves rather than the root, whose listener belongs to the activity.
            ViewCompat.setOnApplyWindowInsetsListener(views.audioEditorAppBar) { v, insets ->
                val barsAndCutout = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.updatePadding(left = barsAndCutout.left, top = barsAndCutout.top, right = barsAndCutout.right)
                insets
            }
            ViewCompat.setOnApplyWindowInsetsListener(views.audioEditorSaveButton) { v, insets ->
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = saveMargin + insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                }
                insets
            }
        } else {
            // Pre-21 has no window-insets dispatch, and the navigation bar is not overlapped there.
            views.audioEditorAppBar.updatePadding(top = statusBarHeightPx())
        }
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_audio_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.audioEditorVolumeAction)?.setIcon(volumeIcon())
        menu.findItem(R.id.audioEditorReverseAction)?.isChecked = reversed
        // Changing an edit while it is being written would export something nobody asked for.
        for (index in 0 until menu.size()) {
            menu.getItem(index).isEnabled = !exporting
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.audioEditorResetAction -> {
                resetEdits()
                true
            }
            R.id.audioEditorSpeedAction -> {
                showSpeedDialog()
                true
            }
            R.id.audioEditorVolumeAction -> {
                showVolumeDialog()
                true
            }
            R.id.audioEditorReverseAction -> {
                setReversed(!reversed)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadSource() {
        lifecycleScope.launch {
            val player = withContext(Dispatchers.IO) { preparePlayer() }
            if (player == null) {
                Toast.makeText(this@AudioEditorActivity, getString(CommonStrings.audio_editor_load_failed), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            this@AudioEditorActivity.player = player
            durationUs = player.duration.toLong() * 1000
            if (durationUs <= 0) {
                Toast.makeText(this@AudioEditorActivity, getString(CommonStrings.audio_editor_load_failed), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            views.audioEditorTimeline.durationUs = durationUs
            val edits = initialEdits
            startUs = edits?.startUs ?: 0L
            endUs = edits?.endUs?.takeIf { it > 0 } ?: durationUs
            edits?.volume?.let { setVolume(it) }
            edits?.speed?.let { playbackSpeed = it }
            if (edits?.reversed == true) setReversed(true, announce = false)
            views.audioEditorTimeline.setTrim(startUs, endUs)
            applyTrimToWaveform()
            updateDurationLabel()
            applyVolume()
            seekTo(startUs)
            loadWaveform()
            loadDetails()
        }
    }

    /** Prepared on a background thread: a content:// source can take a moment to open. */
    private fun preparePlayer(): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setDataSource(this@AudioEditorActivity, sourceUri)
            // Reaching the end of the file bypasses the ticker's own check, and nothing else
            // would notice playback had stopped.
            setOnCompletionListener { stopAtEnd() }
            setOnErrorListener { _, what, extra ->
                Timber.w("AudioEditor: player error $what/$extra")
                true
            }
            prepare()
        }
    }.onFailure { Timber.w(it, "AudioEditor: cannot open $sourceUri") }.getOrNull()

    /** Reading a file for its peaks is a pass over all of it, so it never holds up the screen. */
    @SuppressLint("NewApi")
    private fun loadWaveform() {
        WaveformCache.get(sourceUri)?.let {
            showWaveform(it)
            return
        }
        lifecycleScope.launch {
            val levels = withContext(Dispatchers.Default) {
                WaveformCache.get(this@AudioEditorActivity, sourceUri)
                        // Partial reads land as they come, so a long file draws from its first seconds.
                        ?: AudioWaveform.extract(this@AudioEditorActivity, sourceUri) { partial ->
                            handler.post { showWaveform(partial) }
                        }.also {
                            if (it.isNotEmpty()) WaveformCache.put(this@AudioEditorActivity, sourceUri, it)
                        }
            }
            if (levels.isEmpty()) return@launch
            showWaveform(levels)
        }
    }

    /** What the file says it is, with a blown-up copy of its art behind the screen as VLC does. */
    private fun loadDetails() {
        AudioDetails.cached(sourceUri)?.let {
            showDetails(it)
            return
        }
        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) { AudioDetails.load(this@AudioEditorActivity, sourceUri) }
            showDetails(details)
        }
    }

    private fun showDetails(details: AudioDetails.Details) {
        views.audioEditorName.text = details.title ?: displayName
        views.audioEditorArtist.text = details.credits
        views.audioEditorArtist.isVisible = details.credits != null
        views.audioEditorArt.setImageDrawable(details.art?.let { AudioDetails.roundedArt(this, it) })
        views.audioEditorArt.isVisible = details.art != null && showInlineArt
        views.audioEditorBackdrop.setImageBitmap(details.backdrop)
        views.audioEditorBackdrop.isVisible = details.art != null
        views.audioEditorScrim.isVisible = details.art != null
    }

    private fun showWaveform(levels: FloatArray) {
        views.audioEditorTimeline.waveform = levels
        views.audioEditorWaveform.levels = levels
        views.audioEditorWaveform.durationMs = (durationUs / 1000).toInt()
    }

    private fun showSpeedDialog() {
        PlaybackSpeedDialog(
                context = ContextThemeWrapper(this, ThemeUtils.getApplicationThemeRes(this)),
                initial = playbackSpeed,
                allowPitchChoice = true,
                onChanged = { speed ->
                    playbackSpeed = speed
                    applyPlaybackSpeed()
                    updateDurationLabel()
                }
        ).show()
    }

    private fun showVolumeDialog() {
        PlaybackVolumeDialog(
                context = ContextThemeWrapper(this, ThemeUtils.getApplicationThemeRes(this)),
                initial = volume,
                canPreviewBoost = { boost != null },
                cappedMessage = CommonStrings.audio_editor_volume_preview_capped,
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

    private fun applyVolume() {
        // MediaPlayer's own scalar tops out at 1; anything above it comes from the boost.
        val scalar = volume.effectiveGain.coerceIn(0f, 1f)
        runCatching { player?.setVolume(scalar, scalar) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        applyBoost()
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun applyBoost() {
        val active = player ?: return
        if (boost == null) boost = LoudnessBoost.attachTo(active.audioSessionId)
        boost?.setGain(volume.effectiveGain)
    }

    /** MediaPlayer only runs forwards; a reversed file is written by the export alone. */
    private fun setReversed(next: Boolean, announce: Boolean = true) {
        reversed = next
        invalidateOptionsMenu()
        if (announce && reversed) {
            Toast.makeText(this, getString(CommonStrings.audio_editor_reverse_preview_unsupported), Toast.LENGTH_LONG).show()
        }
    }

    private fun applyPlaybackSpeed() {
        val player = player ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (!playbackSpeed.isDefault && !warnedAboutSpeedPreview) {
                warnedAboutSpeedPreview = true
                Toast.makeText(this, getString(CommonStrings.audio_editor_speed_preview_unsupported), Toast.LENGTH_LONG).show()
            }
            return
        }
        // Setting the parameters on a paused player starts it, and the sound it gets out before it
        // can be paused again is a click — one per step of the speed dialog, which is a crackle.
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
        }.onFailure { Timber.w(it, "AudioEditor: cannot preview speed ${playbackSpeed.speed}") }
    }

    private fun resetEdits() {
        if (durationUs <= 0) return
        startUs = 0
        endUs = durationUs
        setVolume(PlaybackVolume())
        setReversed(false, announce = false)
        playbackSpeed = PlaybackSpeed()
        applyPlaybackSpeed()
        views.audioEditorTimeline.setTrim(startUs, endUs)
        applyTrimToWaveform()
        updateDurationLabel()
        seekTo(startUs)
    }

    /** progressTintList is API 21+, and this screen runs from 18. */
    @Suppress("DEPRECATION")
    private fun tintProgressBar(accent: Int) {
        views.audioEditorExportProgress.progressDrawable?.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
    }

    private fun togglePlayback() {
        if (player?.isPlaying == true) pausePlayback() else startPlayback()
    }

    private fun startPlayback() {
        val player = player ?: return
        if (endUs > 0 && player.currentPosition * 1000L >= endUs) seekTo(startUs)
        runCatching { player.start() }
        views.audioEditorWaveform.setPlaying(true)
        if (speedAwaitingPlayback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pushPlaybackSpeed(player)
        handler.post(playbackTicker)
    }

    private fun pausePlayback() {
        handler.removeCallbacks(playbackTicker)
        player?.takeIf { it.isPlaying }?.pause()
        // The waveform runs on its own clock between reports, so a stop has to be told to it —
        // but only that it stopped: where it is comes from the seek, not from a player mid-seek.
        views.audioEditorWaveform.setPlaying(false)
    }

    private fun applyTrimToWaveform() {
        views.audioEditorWaveform.rangeStartMs = (startUs / 1000).toInt()
        views.audioEditorWaveform.rangeEndMs = (endUs / 1000).toInt()
    }

    private fun setPlayhead(us: Long, sync: Boolean = false) {
        views.audioEditorTimeline.playheadUs = us
        val positionMs = (us / 1000).toInt()
        val playing = player?.isPlaying == true
        if (sync) {
            views.audioEditorWaveform.syncTo(positionMs, playing)
        } else {
            views.audioEditorWaveform.setPosition(positionMs, playing, playbackSpeed.speed)
        }
    }

    /** Playback runs the trimmed window and stops at its end, where the export would. */
    private val playbackTicker = object : Runnable {
        override fun run() {
            val player = player ?: return
            val positionUs = player.currentPosition * 1000L
            setPlayhead(positionUs)
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
        setPlayhead(endUs, sync = true)
    }

    private fun beginScrubbing() {
        if (scrubbing) return
        scrubbing = true
        resumeAfterScrub = player?.isPlaying == true
        handler.removeCallbacks(playbackTicker)
        pausePlayback()
    }

    /**
     * @param resume whether playback picks up again. A trim edit stays paused where it landed —
     * playing away from it immediately hides the very thing being adjusted.
     */
    private fun endScrubbing(us: Long?, resume: Boolean = true) {
        if (!scrubbing) return
        scrubbing = false
        us?.let { seekTo(it) }
        if (resume && resumeAfterScrub) startPlayback() else pausePlayback()
        resumeAfterScrub = false
    }

    /** Seeks are expensive enough that one per touch-move event stutters. */
    private fun seekThrottled(us: Long) {
        setPlayhead(us)
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekAt < SEEK_THROTTLE_MS) return
        lastSeekAt = now
        seekTo(us)
    }

    private fun seekTo(us: Long) {
        setPlayhead(us, sync = true)
        val player = player ?: return
        runCatching {
            // Plain seekTo() lands on the previous sync frame, which reads as the playhead
            // jumping backwards on a sparsely framed file.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(us / 1000, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo((us / 1000).toInt())
            }
        }
    }

    /** How long the export will be: the trimmed range at the chosen speed. */
    private fun outputDurationUs(): Long = ((endUs - startUs).coerceAtLeast(0) / playbackSpeed.speed).toLong()

    private fun updateDurationLabel() {
        views.audioEditorDurationLabel.text = if (playbackSpeed.isDefault) {
            getString(CommonStrings.video_editor_range, formatTime(startUs), formatTime(endUs))
        } else {
            getString(
                    CommonStrings.video_editor_range_output,
                    formatTime(startUs), formatTime(endUs), formatTime(outputDurationUs())
            )
        }
    }

    /** Per-step trimming reads in hundredths of a second: m:ss:hh. */
    private fun formatTime(us: Long): String {
        val totalSeconds = us / 1_000_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (fineMode) {
            val hundredths = us % 1_000_000 / 10_000
            String.format(Locale.US, "%d:%02d:%02d", minutes, seconds, hundredths)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun save() {
        val edits = AudioEditorEdits(
                startUs = startUs,
                endUs = endUs,
                durationUs = durationUs,
                volume = volume,
                speed = playbackSpeed,
                reversed = reversed
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
                        setResult(RESULT_OK, Intent().apply {
                            putExtra(EXTRA_RESULT_URI, output.uri.toString())
                            putExtra(EXTRA_RESULT_SIZE, output.size)
                            putExtra(EXTRA_RESULT_DURATION, output.durationMs)
                            putExtra(EXTRA_RESULT_MIME_TYPE, AudioEditExporter.OUTPUT_MIME_TYPE)
                            putExtra(EXTRA_RESULT_EDITS, edits)
                        })
                        finish()
                    },
                    onFailure = { error ->
                        Timber.w(error, "AudioEditor: export failed")
                        Toast.makeText(this@AudioEditorActivity, messageFor(error), Toast.LENGTH_LONG).show()
                    }
            )
        }
    }

    /** The exporter needs API 18, and the editor is only reachable from there. */
    @SuppressLint("NewApi")
    private suspend fun runExport(edits: AudioEditorEdits): Output {
        val destination = createOutputFile()
        val progress = VideoEditProgressListener { percent ->
            handler.post { views.audioEditorExportProgress.progress = percent }
        }
        val output = try {
            AudioEditExporter.export(
                    this,
                    AudioEditSpec(
                            sourceUri = sourceUri,
                            startUs = edits.startUs,
                            endUs = edits.endUs,
                            outputFile = destination,
                            speed = edits.speed.speed,
                            changePitch = edits.speed.changePitch,
                            volume = edits.volume.effectiveGain,
                            reversed = edits.reversed
                    ),
                    progress
            )
        } catch (throwable: Throwable) {
            // Cancelling or failing leaves nothing worth keeping, and filesDir is never reclaimed.
            destination.parentFile?.deleteRecursively()
            throw throwable
        }
        return Output(
                // DocumentFile cannot stat a file:// URI, so the result must be published through
                // our FileProvider or it stays invisible until the upload completes.
                uri = FileProvider.getUriForFile(this, packageName + FILE_PROVIDER_SUFFIX, output.file),
                size = output.file.length(),
                durationMs = output.durationMs,
                edits = edits
        )
    }

    /** Always .m4a: the exporter writes AAC in an mp4 whatever went in. */
    private fun createOutputFile(): File {
        val directory = File(MediaCache.editedMediaDirectory(this), UUID.randomUUID().toString()).also { it.mkdirs() }
        val baseName = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "audio"
        return File(directory, "$baseName.m4a")
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is VideoEditException.NotEnoughSpace -> getString(CommonStrings.audio_editor_too_long_to_reverse)
        is VideoEditException.UnsupportedCodec -> getString(CommonStrings.video_editor_unsupported)
        else -> getString(CommonStrings.audio_editor_export_failed)
    }

    private fun showExportOverlay(visible: Boolean) {
        exporting = visible
        views.audioEditorExportOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        // The overlay covers everything, but the save button and the app bar are raised above it on
        // API 21+, where elevation decides what a touch lands on rather than the order in the layout.
        ViewCompat.setElevation(views.audioEditorExportOverlay, if (visible) EXPORT_OVERLAY_ELEVATION else 0f)
        views.audioEditorSaveButton.isEnabled = !visible
        invalidateOptionsMenu()
        views.audioEditorExportProgress.progress = 0
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
        boost?.release()
        boost = null
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        super.onDestroy()
    }

    data class Output(
            val uri: Uri,
            val size: Long,
            val durationMs: Long,
            val edits: AudioEditorEdits,
    )

    companion object {
        private const val TOP_BAR_DIM = 0x40000000
        private const val PLAYHEAD_INTERVAL_MS = 60L
        private const val SEEK_THROTTLE_MS = 40L
        private const val FINE_STEPS_PER_SECOND = 100f
        private const val QUIET_GAIN = 0.5f
        private const val FILE_PROVIDER_SUFFIX = ".multipicker.fileprovider"

        /** Above the save button's 6dp and the app bar's 4dp, so neither takes a touch. */
        private const val EXPORT_OVERLAY_ELEVATION = 16f

        private const val EXTRA_SOURCE_URI = "EXTRA_SOURCE_URI"
        private const val EXTRA_DISPLAY_NAME = "EXTRA_DISPLAY_NAME"
        private const val EXTRA_EDITS = "EXTRA_EDITS"
        private const val EXTRA_RESULT_URI = "EXTRA_RESULT_URI"
        private const val EXTRA_RESULT_SIZE = "EXTRA_RESULT_SIZE"
        private const val EXTRA_RESULT_DURATION = "EXTRA_RESULT_DURATION"
        private const val EXTRA_RESULT_MIME_TYPE = "EXTRA_RESULT_MIME_TYPE"
        private const val EXTRA_RESULT_EDITS = "EXTRA_RESULT_EDITS"

        fun newIntent(context: Context, source: Uri, displayName: String?, edits: AudioEditorEdits?): Intent {
            return Intent(context, AudioEditorActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_URI, source.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_EDITS, edits)
            }
        }

        fun getOutput(intent: Intent): Output? {
            val uri = intent.getStringExtra(EXTRA_RESULT_URI)?.toUri() ?: return null
            return Output(
                    uri = uri,
                    size = intent.getLongExtra(EXTRA_RESULT_SIZE, 0),
                    durationMs = intent.getLongExtra(EXTRA_RESULT_DURATION, 0),
                    edits = intent.getParcelableExtraCompat(EXTRA_RESULT_EDITS) ?: AudioEditorEdits()
            )
        }

        fun mimeTypeOf(intent: Intent): String =
                intent.getStringExtra(EXTRA_RESULT_MIME_TYPE) ?: AudioEditExporter.OUTPUT_MIME_TYPE
    }
}
