/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.dialogs

import android.app.Activity
import android.text.format.DateUtils
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.annotation.StringRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.databinding.DialogConfirmationWithReasonBinding
import im.vector.app.features.delayedevents.ScheduleTimePicker
import im.vector.lib.strings.CommonStrings

private const val DURATION_REASON_GAP_DP = 12

object ConfirmationDialogBuilder {

    fun show(
            activity: Activity,
            askForReason: Boolean,
            @StringRes titleRes: Int,
            @StringRes confirmationRes: Int,
            @StringRes positiveRes: Int,
            @StringRes reasonHintRes: Int,
            confirmation: (String?) -> Unit
    ) {
        show(activity, askForReason, titleRes, confirmationRes, positiveRes, reasonHintRes, offerRedactEvents = false) { reason, _ ->
            confirmation(reason)
        }
    }

    /**
     * [offerRedactEvents] adds the MSC4293 "redact their messages too" option; the flag it produces is the
     * one passed to kick/ban.
     */
    fun show(
            activity: Activity,
            askForReason: Boolean,
            @StringRes titleRes: Int,
            @StringRes confirmationRes: Int,
            @StringRes positiveRes: Int,
            @StringRes reasonHintRes: Int,
            offerRedactEvents: Boolean,
            confirmation: (String?, Boolean) -> Unit
    ) {
        show(activity, askForReason, titleRes, confirmationRes, positiveRes, reasonHintRes, offerRedactEvents, duration = null) { reason, redactEvents, _, _ ->
            confirmation(reason, redactEvents)
        }
    }

    /**
     * Lets the action be time limited. A non-zero [followUpRes] adds a checkbox, usable only with an end,
     * for something to do when it comes.
     */
    class DurationOption(val maxDelayMs: Long, @StringRes val followUpRes: Int = 0)

    /**
     * With a [duration], [confirmation] also gets the chosen end time, null when there is none, and whether
     * its follow-up was ticked.
     */
    fun show(
            activity: Activity,
            askForReason: Boolean,
            @StringRes titleRes: Int,
            @StringRes confirmationRes: Int,
            @StringRes positiveRes: Int,
            @StringRes reasonHintRes: Int,
            offerRedactEvents: Boolean,
            duration: DurationOption?,
            confirmation: (String?, Boolean, Long?, Boolean) -> Unit
    ) {
        val layout = activity.layoutInflater.inflate(R.layout.dialog_confirmation_with_reason, null)
        val views = DialogConfirmationWithReasonBinding.bind(layout)
        views.dialogConfirmationText.setText(confirmationRes)

        views.dialogReasonTextInputLayout.isVisible = askForReason
        views.dialogRedactEventsCheck.isVisible = offerRedactEvents
        views.dialogDurationSpinner.isVisible = duration != null
        val followUp = views.dialogDurationFollowUpCheck
        val followUpRes = duration?.followUpRes ?: 0
        followUp.isVisible = followUpRes != 0
        if (followUpRes != 0) followUp.setText(followUpRes)
        followUp.isEnabled = false
        val untilTs = duration?.let {
            setUpDuration(activity, views.dialogDurationSpinner, it) { indefinite -> followUp.isEnabled = !indefinite }
        }
        if (duration != null) {
            val gap = (DURATION_REASON_GAP_DP * activity.resources.displayMetrics.density).toInt()
            views.dialogReasonTextInputLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = gap
                goneTopMargin = gap
            }
        }

        if (askForReason && reasonHintRes != 0) {
            views.dialogReasonInput.setHint(reasonHintRes)
        }

        MaterialAlertDialogBuilder(activity)
                .setTitle(titleRes)
                .setView(layout)
                .setPositiveButton(positiveRes) { _, _ ->
                    val reason = views.dialogReasonInput.text.toString()
                            .takeIf { askForReason }
                            ?.takeIf { it.isNotBlank() }
                    val end = untilTs?.invoke()
                    confirmation(reason, offerRedactEvents && views.dialogRedactEventsCheck.isChecked, end, end != null && followUp.isVisible && followUp.isChecked)
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    /** @return the end time of the selected duration, evaluated when called */
    private fun setUpDuration(activity: Activity, spinner: Spinner, option: DurationOption, onIndefiniteChanged: (Boolean) -> Unit): () -> Long? {
        val presets = ScheduleTimePicker.durationPresets(activity)
        // Once a custom time is picked it sits at index presets.size, just above the "pick" entry.
        val labels = (presets.map { it.label } + activity.getString(CommonStrings.schedule_pick_custom_time)).toMutableList()
        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        var customTs: Long? = null
        var selected = 0
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != adapter.count - 1) {
                    selected = position
                    onIndefiniteChanged(position == 0)
                    return
                }
                ScheduleTimePicker.pickDateTime(activity, option.maxDelayMs, onCancelled = { spinner.setSelection(selected) }) { ts ->
                    if (customTs != null) adapter.remove(adapter.getItem(presets.size))
                    adapter.insert(DateUtils.formatDateTime(activity, ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), presets.size)
                    customTs = ts
                    selected = presets.size
                    onIndefiniteChanged(false)
                    spinner.setSelection(selected)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        return { if (selected == presets.size) customTs else presets[selected].time() }
    }
}
