/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.audio

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.LayerDrawable
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.extensions.setExpandedContentView
import im.vector.app.core.extensions.thumbCompat
import im.vector.app.databinding.BottomSheetAudioPlayerBinding
import im.vector.app.features.attachments.editor.video.PlaybackSpeed
import im.vector.app.features.attachments.editor.video.PlaybackSpeedDialog
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

internal fun AudioPlaybackController.State.errorMessage(context: Context): String =
        if (track.content.voiceMessageIndicator != null) context.getString(CommonStrings.error_voice_message_unable_to_play)
        else context.getString(CommonStrings.error_audio_message_unable_to_play, title)

internal fun AudioPlaybackController.State.credits(context: Context): String =
        if (error) errorMessage(context) else AudioDetails.credits(title, artist, album).orEmpty().neutralizeDirectionOverrides()

object AudioPlaybackBannerBinder {
    fun bind(banner: AudioPlaybackBannerView, fragment: Fragment, controller: AudioPlaybackController) {
        banner.controller = controller
        banner.onControls = { showControls(fragment, controller) }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { banner.render(it) }
            }
        }
    }

    private fun showControls(fragment: Fragment, controller: AudioPlaybackController) {
        val context = fragment.requireContext()
        val views = BottomSheetAudioPlayerBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        var displayedOrientation = Configuration.ORIENTATION_UNDEFINED
        fun playlistRows(trackCount: Int) = trackCount.coerceAtMost(if (displayedOrientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 4)
        fun updateOrientation() {
            val window = dialog.window?.decorView
            val landscape = if (window != null && window.width > 0 && window.height > 0) {
                window.width > window.height
            } else {
                context.applicationContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            val orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            if (displayedOrientation == orientation) return
            displayedOrientation = orientation
            val dividerSize = (context.resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)
            views.root.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            views.root.gravity = if (landscape) Gravity.CENTER_VERTICAL else Gravity.TOP
            views.controlsPane.layoutParams = (views.controlsPane.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                weight = if (landscape) 1f else 0f
            }
            views.separator.layoutParams = views.separator.layoutParams.apply {
                width = if (landscape) dividerSize else ViewGroup.LayoutParams.MATCH_PARENT
                height = if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else dividerSize
            }
            views.playlist.layoutParams = (views.playlist.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                weight = if (landscape) 1f else 0f
                height = (playlistRows(controller.state.value?.playlist?.size ?: 0) * 64 * context.resources.displayMetrics.density).toInt()
            }
        }
        updateOrientation()
        views.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOrientation() }
        dialog.window?.decorView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOrientation() }
        dialog.setExpandedContentView(views.root)
        dialog.behavior.skipCollapsed = true
        val controlsColor = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
        views.seek.thumbCompat?.let { thumb ->
            views.seek.thumb = DrawableCompat.wrap(thumb.mutate()).also { DrawableCompat.setTint(it, controlsColor) }
        }
        (views.seek.progressDrawable.mutate() as? LayerDrawable)?.let { progress ->
            val filter = PorterDuffColorFilter(controlsColor, PorterDuff.Mode.SRC_IN)
            progress.findDrawableByLayerId(android.R.id.background)?.colorFilter = filter
            progress.findDrawableByLayerId(android.R.id.secondaryProgress)?.colorFilter = filter
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val lifecycle = fragment.viewLifecycleOwner.lifecycle
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        lifecycle.addObserver(lifecycleObserver)
        dialog.setOnDismissListener {
            lifecycle.removeObserver(lifecycleObserver)
            scope.cancel()
        }
        var seeking = false
        var artwork: Bitmap? = null
        var hadTrack = false
        var displayedPlaying: Boolean? = null
        var displayedLoopMode: AudioPlaybackController.LoopMode? = null
        views.playPause.setOnClickListener { controller.toggle() }
        views.previous.setOnClickListener { controller.skip(false) }
        views.next.setOnClickListener { controller.skip(true) }
        views.loop.setOnClickListener { controller.cycleLoopMode() }
        views.rewind.setOnClickListener { controller.state.value?.let { controller.seek(it.position - 5000) } }
        views.fastForward.setOnClickListener { controller.state.value?.let { controller.seek(it.position + 5000) } }
        val adapter = AudioPlaybackPlaylistAdapter(controller, context) { dialog.dismiss() }
        val playlistLayout = LinearLayoutManager(context)
        views.playlist.layoutManager = playlistLayout
        views.playlist.adapter = adapter
        ViewCompat.setNestedScrollingEnabled(views.playlist, false)
        views.playlist.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> recyclerView.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recyclerView.parent.requestDisallowInterceptTouchEvent(false)
                }
                return false
            }
        })
        var displayedTracks = emptyList<AudioPlaybackController.Track>()
        var positionedCurrentTrack = false
        fun loadAtEdge() {
            val state = controller.state.value ?: return
            if (!state.canLoadMore || state.loadingTracks) return
            if (playlistLayout.findFirstVisibleItemPosition() <= 1 ||
                    playlistLayout.findLastVisibleItemPosition() >= adapter.itemCount - 2) controller.loadMoreTracks()
        }
        views.playlist.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) loadAtEdge()
            }
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) loadAtEdge()
            }
        })
        views.parameters.setOnClickListener { showParameters(context, controller) }
        views.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seeking = false
                controller.seek(seekBar.progress)
            }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) views.time.text = context.getString(CommonStrings.video_position_of_duration,
                        DateUtils.formatElapsedTime(progress / 1000L), DateUtils.formatElapsedTime(seekBar.max / 1000L))
            }
        })
        dialog.show()
        scope.launch {
            controller.state.collect { state ->
                if (state == null) {
                    if (hadTrack) dialog.dismiss()
                    return@collect
                }
                hadTrack = true
                views.title.text = state.title.neutralizeDirectionOverrides()
                views.credits.text = state.credits(context)
                views.credits.isVisible = views.credits.text.isNotBlank()
                views.art.isVisible = state.art != null
                if (artwork !== state.art) {
                    artwork = state.art
                    if (state.art == null) {
                        views.art.setImageDrawable(null)
                    } else {
                        views.art.setImageDrawable(AudioDetails.roundedSquareArt(context, state.art))
                    }
                }
                if (!seeking) {
                    views.seek.max = state.duration.coerceAtLeast(1)
                    views.seek.progress = state.position
                    views.time.text = context.getString(CommonStrings.video_position_of_duration,
                            DateUtils.formatElapsedTime(state.position / 1000L), DateUtils.formatElapsedTime(state.duration / 1000L))
                }
                views.seek.isEnabled = !state.loading && !state.error && state.duration > 0
                views.playPause.isEnabled = !state.loading
                if (displayedPlaying != state.playing) {
                    displayedPlaying = state.playing
                    views.playPause.setImageDrawable(AppCompatResources.getDrawable(context,
                            if (state.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow))
                    views.playPause.contentDescription = context.getString(
                            if (state.playing) CommonStrings.action_pause else CommonStrings.action_play)
                }
                views.previous.isEnabled = state.hasPrevious && !state.loading
                views.next.isEnabled = state.hasNext && !state.loading
                views.parameters.isEnabled = !state.loading
                if (displayedLoopMode != state.loopMode) {
                    displayedLoopMode = state.loopMode
                    views.loop.isSelected = state.loopMode != AudioPlaybackController.LoopMode.OFF
                    views.loop.setImageDrawable(AppCompatResources.getDrawable(context,
                            if (state.loopMode == AudioPlaybackController.LoopMode.ONE) R.drawable.ic_audio_repeat_one else R.drawable.ic_audio_repeat))
                    views.loop.contentDescription = context.getString(when (state.loopMode) {
                        AudioPlaybackController.LoopMode.OFF -> CommonStrings.audio_player_loop_off
                        AudioPlaybackController.LoopMode.ALL -> CommonStrings.audio_player_loop_all
                        AudioPlaybackController.LoopMode.ONE -> CommonStrings.audio_player_loop_one
                    })
                    ImageViewCompat.setImageTintList(views.loop, ColorStateList.valueOf(
                            ThemeUtils.getColor(context, if (state.loopMode != AudioPlaybackController.LoopMode.OFF) com.google.android.material.R.attr.colorPrimary
                            else im.vector.lib.ui.styles.R.attr.vctr_content_primary)))
                }
                views.parameters.text = context.getString(CommonStrings.video_editor_speed_value, String.format(Locale.US, "%.2f", state.speed))
                views.rewind.isEnabled = !state.loading && !state.error && state.duration > 0
                views.fastForward.isEnabled = views.rewind.isEnabled
                val firstVisible = playlistLayout.findFirstVisibleItemPosition()
                val anchorId = displayedTracks.getOrNull(firstVisible)?.id
                val anchorOffset = playlistLayout.findViewByPosition(firstVisible)?.top ?: 0
                adapter.render(state)
                if (displayedTracks != state.playlist) {
                    displayedTracks = state.playlist
                    val anchorIndex = displayedTracks.indexOfFirst { it.id == anchorId }
                    if (anchorIndex >= 0) playlistLayout.scrollToPositionWithOffset(anchorIndex, anchorOffset)
                }
                if (!positionedCurrentTrack) {
                    val currentIndex = state.playlist.indexOfFirst(controller::isCurrentTrack)
                    if (currentIndex >= 0) {
                        val offset = ((playlistRows(state.playlist.size) - 1) * 32 * context.resources.displayMetrics.density).toInt()
                        playlistLayout.scrollToPositionWithOffset(currentIndex, offset)
                        positionedCurrentTrack = true
                    }
                }
                if (state.playlist.size < 4 && state.canLoadMore && !state.loadingTracks) controller.loadMoreTracks()
                val playlistHeight = (playlistRows(state.playlist.size) * 64 * context.resources.displayMetrics.density).toInt()
                if (views.playlist.layoutParams.height != playlistHeight) {
                    views.playlist.layoutParams = views.playlist.layoutParams.apply { height = playlistHeight }
                }
            }
        }
    }

    private fun showParameters(context: Context, controller: AudioPlaybackController) {
        val state = controller.state.value ?: return
        if (!state.adjustable) {
            MaterialAlertDialogBuilder(context).setTitle(CommonStrings.audio_player_parameters)
                    .setMessage(CommonStrings.audio_player_parameters_legacy).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        PlaybackSpeedDialog(context, PlaybackSpeed(state.speed, state.changePitch), allowPitchChoice = true,
                onChanged = controller::setPlaybackSpeed, asPopup = true).show()
    }
}
