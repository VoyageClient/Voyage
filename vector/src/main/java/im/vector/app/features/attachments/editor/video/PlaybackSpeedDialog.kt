/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import im.vector.app.databinding.BottomSheetVideoSpeedBinding
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.math.QuadraticSlider
import im.vector.lib.strings.CommonStrings
import java.util.Locale

/** Modelled on NewPipe's playback parameter dialog; changes publish live, not on dismiss. */
class PlaybackSpeedDialog(
        private val context: Context,
        private val initial: PlaybackSpeed,
        private val allowPitchChoice: Boolean,
        private val onChanged: (PlaybackSpeed) -> Unit,
) {

    private val views = BottomSheetVideoSpeedBinding.inflate(LayoutInflater.from(context))
    private val slider = QuadraticSlider(
            minimum = PlaybackSpeed.MINIMUM,
            maximum = PlaybackSpeed.MAXIMUM,
            centre = PlaybackSpeed.NORMAL,
            maximumProgress = SLIDER_RANGE,
    )
    private val stepButtons = mutableListOf<TextView>()
    private val verticalPadding = (STEP_PADDING_DP * context.resources.displayMetrics.density).toInt()

    private var current = initial
    private var stepPercentage = DEFAULT_STEP_PERCENTAGE

    fun show() {
        views.speedSeekBar.max = SLIDER_RANGE
        views.speedMinimum.text = format(PlaybackSpeed.MINIMUM)
        views.speedMaximum.text = format(PlaybackSpeed.MAXIMUM)
        views.speedChangePitch.visibility = if (allowPitchChoice) View.VISIBLE else View.GONE
        addStepButtons()

        views.speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) update(current.copy(speed = slider.valueOf(progress)), moveSlider = false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        views.speedStepDown.setOnClickListener { step(-stepPercentage) }
        views.speedStepUp.setOnClickListener { step(stepPercentage) }
        views.speedChangePitch.setOnCheckedChangeListener { _, checked ->
            update(current.copy(changePitch = checked))
        }
        views.speedReset.setOnClickListener { update(PlaybackSpeed()) }

        val dialog = BottomSheetDialog(context).apply { setContentView(views.root) }
        views.speedDone.setOnClickListener { dialog.dismiss() }
        // Changes apply as they are made, so leaving has to be able to put them back.
        views.speedCancel.setOnClickListener {
            update(initial)
            dialog.dismiss()
        }
        render()
        dialog.show()
    }

    private fun addStepButtons() {
        val accent = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorAccent)
        val normal = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        PlaybackSpeed.STEP_PERCENTAGES.forEach { percentage ->
            val button = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                setPadding(0, verticalPadding, 0, verticalPadding)
                text = context.getString(CommonStrings.video_editor_speed_step_value, percentage)
                // AppCompat's, not the framework's: the borderless variant is API 21 in android:.
                setBackgroundResource(
                        ThemeUtils.getAttribute(context, androidx.appcompat.R.attr.selectableItemBackgroundBorderless)?.resourceId ?: 0
                )
                setOnClickListener {
                    stepPercentage = percentage
                    renderStepButtons(accent, normal)
                }
            }
            stepButtons.add(button)
            views.speedStepSelector.addView(button)
        }
        renderStepButtons(accent, normal)
    }

    private fun renderStepButtons(accent: Int, normal: Int) {
        stepButtons.forEachIndexed { index, button ->
            val selected = PlaybackSpeed.STEP_PERCENTAGES[index] == stepPercentage
            button.setTextColor(if (selected) accent else normal)
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        views.speedStepDown.text = context.getString(CommonStrings.video_editor_speed_step_down, stepPercentage)
        views.speedStepUp.text = context.getString(CommonStrings.video_editor_speed_step_up, stepPercentage)
    }

    /** Steps by a percentage of normal speed, so the buttons move by the same amount everywhere. */
    private fun step(percentage: Int) {
        val stepped = current.speed + percentage / 100f
        update(current.copy(speed = stepped.coerceIn(PlaybackSpeed.MINIMUM, PlaybackSpeed.MAXIMUM)))
    }

    private fun update(next: PlaybackSpeed, moveSlider: Boolean = true) {
        current = next
        render(moveSlider)
        onChanged(next)
    }

    private fun render(moveSlider: Boolean = true) {
        views.speedValue.text = format(current.speed)
        // Writing the progress back while the finger is on the bar fights the drag.
        if (moveSlider) views.speedSeekBar.progress = slider.progressOf(current.speed)
        if (views.speedChangePitch.isChecked != current.changePitch) {
            views.speedChangePitch.isChecked = current.changePitch
        }
    }

    private fun format(speed: Float) =
            context.getString(CommonStrings.video_editor_speed_value, String.format(Locale.US, "%.2f", speed))

    companion object {
        /** Wide enough that a step of the bar is finer than the two decimal places on show. */
        private const val SLIDER_RANGE = 10_000

        /** NewPipe's own default. */
        private const val DEFAULT_STEP_PERCENTAGE = 25
        private const val STEP_PADDING_DP = 12
    }
}
