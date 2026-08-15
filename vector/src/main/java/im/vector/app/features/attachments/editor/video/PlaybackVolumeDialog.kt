/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.content.Context
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import im.vector.app.databinding.BottomSheetVideoVolumeBinding
import im.vector.lib.strings.CommonStrings
import kotlin.math.roundToInt

/** The sibling of [PlaybackSpeedDialog]; changes publish live, not on dismiss. */
class PlaybackVolumeDialog(
        private val context: Context,
        private val initial: PlaybackVolume,
        private val canPreviewBoost: () -> Boolean,
        private val onChanged: (PlaybackVolume) -> Unit,
) {

    private val views = BottomSheetVideoVolumeBinding.inflate(LayoutInflater.from(context))

    private var current = initial

    fun show() {
        views.volumeSeekBar.max = percentOf(PlaybackVolume.MAXIMUM)
        views.volumeMinimum.text = format(PlaybackVolume.MINIMUM)
        views.volumeMaximum.text = format(PlaybackVolume.MAXIMUM)
        views.volumeStepDown.text = context.getString(CommonStrings.video_editor_volume_step_down, STEP_PERCENTAGE)
        views.volumeStepUp.text = context.getString(CommonStrings.video_editor_volume_step_up, STEP_PERCENTAGE)

        views.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) update(current.copy(gain = progress / 100f), moveSlider = false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        views.volumeStepDown.setOnClickListener { step(-STEP_PERCENTAGE) }
        views.volumeStepUp.setOnClickListener { step(STEP_PERCENTAGE) }
        views.volumeMute.setOnCheckedChangeListener { _, checked -> update(current.copy(muted = checked)) }
        views.volumeReset.setOnClickListener { update(PlaybackVolume()) }

        val dialog = BottomSheetDialog(context).apply { setContentView(views.root) }
        views.volumeDone.setOnClickListener {
            if (current.effectiveGain > PlaybackVolume.NORMAL && !canPreviewBoost()) {
                Toast.makeText(context, context.getString(CommonStrings.video_editor_volume_preview_capped), Toast.LENGTH_LONG).show()
            }
            dialog.dismiss()
        }
        // Changes apply as they are made, so leaving has to be able to put them back.
        views.volumeCancel.setOnClickListener {
            update(initial)
            dialog.dismiss()
        }
        render()
        dialog.show()
    }

    private fun step(percentage: Int) {
        val stepped = current.gain + percentage / 100f
        update(current.copy(gain = stepped.coerceIn(PlaybackVolume.MINIMUM, PlaybackVolume.MAXIMUM)))
    }

    private fun update(next: PlaybackVolume, moveSlider: Boolean = true) {
        current = next
        render(moveSlider)
        onChanged(next)
    }

    private fun render(moveSlider: Boolean = true) {
        views.volumeValue.text = format(current.gain)
        // Writing the progress back while the finger is on the bar fights the drag.
        if (moveSlider) views.volumeSeekBar.progress = percentOf(current.gain)
        if (views.volumeMute.isChecked != current.muted) views.volumeMute.isChecked = current.muted
        val enabled = !current.muted
        views.volumeSeekBar.isEnabled = enabled
        views.volumeStepDown.isEnabled = enabled
        views.volumeStepUp.isEnabled = enabled
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        views.volumeSeekBar.alpha = alpha
        views.volumeValue.alpha = alpha
        views.volumeStepDown.alpha = alpha
        views.volumeStepUp.alpha = alpha
        views.volumeMinimum.alpha = alpha
        views.volumeMaximum.alpha = alpha
    }

    private fun percentOf(gain: Float) = (gain * 100).roundToInt()

    private fun format(gain: Float) = context.getString(CommonStrings.video_editor_volume_value, percentOf(gain))

    companion object {
        private const val STEP_PERCENTAGE = 10
        private const val DISABLED_ALPHA = 0.4f
    }
}
