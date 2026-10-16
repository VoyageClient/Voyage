/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.delayedevents

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.error.ErrorFormatter
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.delayedevents.DelayedEvent
import org.matrix.android.sdk.api.session.delayedevents.DelayedEventService
import org.matrix.android.sdk.api.session.events.model.EventType

/**
 * Lists the messages the user has scheduled in a room, with send now, edit and delete for each.
 */
class ScheduledMessagesDialog(
        private val fragment: Fragment,
        private val session: Session,
        private val dateFormatter: VectorDateFormatter,
        private val errorFormatter: ErrorFormatter,
        private val roomId: String,
        private val onEdit: (body: String) -> Unit,
) {

    fun show() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val messages = try {
                session.delayedEventService().getDelayedEvents()
                        .filter { it.roomId == roomId && it.type == EventType.MESSAGE && it.stateKey == null }
                        .sortedBy { it.sendTs }
            } catch (failure: Throwable) {
                showError(failure)
                return@launch
            }
            showList(messages)
        }
    }

    private fun showList(messages: List<DelayedEvent>) {
        val context = fragment.context ?: return
        val builder = MaterialAlertDialogBuilder(context)
                .setTitle(CommonStrings.scheduled_messages_title)
                .setPositiveButton(CommonStrings.action_close, null)
        if (messages.isEmpty()) {
            builder.setMessage(CommonStrings.scheduled_messages_empty)
        } else {
            val labels = messages.map { "${dateFormatter.format(ScheduleTimePicker.forDisplay(it.sendTs), DateFormatKind.DEFAULT_DATE_AND_TIME)}\n${it.body().take(PREVIEW_LENGTH)}" }
            builder.setItems(labels.toTypedArray()) { _, which -> showActions(messages[which]) }
        }
        builder.show()
    }

    private fun showActions(message: DelayedEvent) {
        val context = fragment.context ?: return
        val actions = arrayOf(
                context.getString(CommonStrings.scheduled_message_send_now),
                context.getString(CommonStrings.edit),
                context.getString(CommonStrings.action_delete),
        )
        MaterialAlertDialogBuilder(context)
                .setTitle(dateFormatter.format(ScheduleTimePicker.forDisplay(message.sendTs), DateFormatKind.DEFAULT_DATE_AND_TIME))
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> update { sendNow(message.delayId) }
                        1 -> update {
                            cancel(message.delayId)
                            onEdit(message.body())
                        }
                        else -> update { cancel(message.delayId) }
                    }
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun update(block: suspend DelayedEventService.() -> Unit) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                session.delayedEventService().block()
            } catch (failure: Throwable) {
                showError(failure)
            }
        }
    }

    private fun showError(failure: Throwable) {
        val context = fragment.context ?: return
        Toast.makeText(context, errorFormatter.toHumanReadable(failure), Toast.LENGTH_LONG).show()
    }

    private fun DelayedEvent.body(): String = content["body"] as? String ?: ""

    private companion object {
        const val PREVIEW_LENGTH = 120
    }
}
