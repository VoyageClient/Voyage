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
import androidx.annotation.StringRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import im.vector.app.databinding.BottomSheetVideoVolumeBinding
import im.vector.lib.core.utils.math.QuadraticSlider
import im.vector.lib.strings.CommonStrings
import kotlin.math.roundToInt

/** The sibling of [PlaybackSpeedDialog]; changes publish live, not on dismiss. */
class PlaybackVolumeDialog(
        private val context: Context,
        private val initial: PlaybackVolume,
        private val canPreviewBoost: () -> Boolean,
        @StringRes private val cappedMessage: Int,
        private val onChanged: (PlaybackVolume) -> Unit,
        private val onDismiss: (() -> Unit)? = null,
) {

    private val views = BottomSheetVideoVolumeBinding.inflate(LayoutInflater.from(context))
    private val slider = QuadraticSlider(
            minimum = PlaybackVolume.MINIMUM,
            maximum = PlaybackVolume.MAXIMUM,
            centre = PlaybackVolume.NORMAL,
            maximumProgress = SLIDER_RANGE,
    )
    private lateinit var steps: StepSelector

    private var current = initial

    fun show() {
        views.volumeSeekBar.max = SLIDER_RANGE
        views.volumeMinimum.text = format(PlaybackVolume.MINIMUM)
        views.volumeMaximum.text = format(PlaybackVolume.MAXIMUM)
        steps = StepSelector(
                views.volumeStepSelector, PlaybackVolume.STEP_PERCENTAGES, DEFAULT_STEP_PERCENTAGE
        ) { renderStepLabels() }
        renderStepLabels()

        views.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) update(current.copy(gain = slider.valueOf(progress)), moveSlider = false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        views.volumeStepDown.setOnClickListener { step(-steps.percentage) }
        views.volumeStepUp.setOnClickListener { step(steps.percentage) }
        views.volumeMute.setOnCheckedChangeListener { _, checked -> update(current.copy(muted = checked)) }
        views.volumeReset.setOnClickListener { update(PlaybackVolume()) }

        val dialog = BottomSheetDialog(context).apply {
            setContentView(views.root)
            setOnDismissListener { onDismiss?.invoke() }
        }
        views.volumeDone.setOnClickListener {
            if (current.effectiveGain > PlaybackVolume.NORMAL && !canPreviewBoost()) {
                Toast.makeText(context, context.getString(cappedMessage), Toast.LENGTH_LONG).show()
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

    private fun renderStepLabels() {
        views.volumeStepDown.text = context.getString(CommonStrings.video_editor_step_down, steps.percentage)
        views.volumeStepUp.text = context.getString(CommonStrings.video_editor_step_up, steps.percentage)
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
        if (moveSlider) views.volumeSeekBar.progress = slider.progressOf(current.gain)
        if (views.volumeMute.isChecked != current.muted) views.volumeMute.isChecked = current.muted
        val enabled = !current.muted
        views.volumeSeekBar.isEnabled = enabled
        views.volumeStepDown.isEnabled = enabled
        views.volumeStepUp.isEnabled = enabled
        steps.isEnabled = enabled
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        views.volumeSeekBar.alpha = alpha
        views.volumeValue.alpha = alpha
        views.volumeStepDown.alpha = alpha
        views.volumeStepUp.alpha = alpha
        views.volumeMinimum.alpha = alpha
        views.volumeMaximum.alpha = alpha
        views.volumeStepSelector.alpha = alpha
    }

    private fun percentOf(gain: Float) = (gain * 100).roundToInt()

    private fun format(gain: Float) = context.getString(CommonStrings.video_editor_volume_value, percentOf(gain))

    companion object {
        /** Wide enough that a step of the bar is finer than the whole percent on show. */
        private const val SLIDER_RANGE = 10_000

        private const val DEFAULT_STEP_PERCENTAGE = 10
        private const val DISABLED_ALPHA = 0.4f
    }
}
