/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.delayedevents

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import java.util.Calendar

/**
 * Lets the user pick a moment for an MSC4140 delayed event: a list of presets, then the framework date
 * and time pickers for anything else.
 */
object ScheduleTimePicker {

    private const val MINUTE_MS = 60 * 1000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS

    /** [time] returns the chosen epoch millis, or null for a preset that means "no time limit". */
    class Preset(val label: String, val time: () -> Long?)

    /** How long a mute or ban lasts, starting with no end. */
    fun durationPresets(context: Context): List<Preset> = listOf(
            Preset(context.getString(CommonStrings.duration_permanently)) { null },
            Preset(context.getString(CommonStrings.duration_for_1_hour), inMillis(HOUR_MS)),
            Preset(context.getString(CommonStrings.duration_for_1_day), inMillis(DAY_MS)),
            Preset(context.getString(CommonStrings.duration_for_1_week), inMillis(7 * DAY_MS)),
    )

    fun show(
            context: Context,
            title: String,
            presets: List<Preset>,
            maxDelayMs: Long,
            onPicked: (Long?) -> Unit,
    ) {
        val labels = presets.map { it.label } + context.getString(CommonStrings.schedule_pick_custom_time)
        MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < presets.size) {
                        val time = presets[which].time()
                        if (time == null || validate(context, time, maxDelayMs)) onPicked(time)
                    } else {
                        pickDateTime(context, maxDelayMs, onCancelled = {}, onPicked = onPicked)
                    }
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    fun inMillis(delayMs: Long): () -> Long = { System.currentTimeMillis() + delayMs }

    /**
     * A listed send time, ready for a minutes-only display. It is on the server's clock, so a time picked
     * as 12:21 can read 12:20:58 when our clock runs ahead; the nearest minute absorbs that.
     */
    fun forDisplay(sendTs: Long): Long = (sendTs + MINUTE_MS / 2) / MINUTE_MS * MINUTE_MS

    private fun validate(context: Context, time: Long, maxDelayMs: Long): Boolean {
        val delay = time - System.currentTimeMillis()
        val error = when {
            delay <= 0 -> CommonStrings.schedule_time_in_past
            maxDelayMs != HomeServerCapabilities.MAX_EVENT_DELAY_UNKNOWN && delay > maxDelayMs -> CommonStrings.schedule_time_too_far
            else -> return true
        }
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        return false
    }

    /** The framework date then time pickers, starting at now; [onCancelled] also covers a rejected time. */
    fun pickDateTime(context: Context, maxDelayMs: Long, onCancelled: () -> Unit, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance()
        // Some framework versions call the date listener a second time when the dialog is dismissed.
        var dateHandled = false
        val dateDialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    if (dateHandled) return@DatePickerDialog
                    dateHandled = true
                    calendar.set(year, month, day)
                    var timeHandled = false
                    TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                if (timeHandled) return@TimePickerDialog
                                timeHandled = true
                                calendar.set(Calendar.HOUR_OF_DAY, hour)
                                calendar.set(Calendar.MINUTE, minute)
                                calendar.set(Calendar.SECOND, 0)
                                calendar.set(Calendar.MILLISECOND, 0)
                                if (validate(context, calendar.timeInMillis, maxDelayMs)) onPicked(calendar.timeInMillis) else onCancelled()
                            },
                            calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE),
                            DateFormat.is24HourFormat(context),
                    ).apply {
                        setOnDismissListener { if (!timeHandled) onCancelled() }
                    }.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
        )
        dateDialog.setOnCancelListener { if (!dateHandled) onCancelled() }
        dateDialog.show()
    }
}
