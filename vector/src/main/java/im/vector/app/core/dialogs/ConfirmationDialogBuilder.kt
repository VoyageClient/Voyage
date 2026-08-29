/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.dialogs

import android.app.Activity
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.databinding.DialogConfirmationWithReasonBinding
import im.vector.lib.strings.CommonStrings

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
        val layout = activity.layoutInflater.inflate(R.layout.dialog_confirmation_with_reason, null)
        val views = DialogConfirmationWithReasonBinding.bind(layout)
        views.dialogConfirmationText.setText(confirmationRes)

        views.dialogReasonTextInputLayout.isVisible = askForReason
        views.dialogRedactEventsCheck.isVisible = offerRedactEvents

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
                    confirmation(reason, offerRedactEvents && views.dialogRedactEventsCheck.isChecked)
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }
}
