/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.dialogs

import android.app.Activity
import android.content.DialogInterface
import android.text.Editable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.databinding.DialogExportE2eKeysBinding
import im.vector.lib.strings.CommonStrings

class ExportKeysDialog {

    fun show(activity: Activity, exportKeyDialogListener: ExportKeyDialogListener) {
        val dialogLayout = activity.layoutInflater.inflate(R.layout.dialog_export_e2e_keys, null)
        val views = DialogExportE2eKeysBinding.bind(dialogLayout)
        // The passphrase is optional in this fork: an empty passphrase exports without encryption.
        views.exportDialogText.setText(CommonStrings.encryption_export_notice_optional)
        val exportDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(CommonStrings.encryption_export_room_keys)
                .setView(dialogLayout)
                .setPositiveButton(CommonStrings.encryption_export_export) { _, _ ->
                    exportKeyDialogListener.onPassphrase(views.exportDialogEt.text.toString())
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
        val exportButton = exportDialog.getButton(DialogInterface.BUTTON_POSITIVE)

        val textWatcher = object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable) {
                if (views.exportDialogEt.text.toString() == views.exportDialogEtConfirm.text.toString()) {
                    exportButton.isEnabled = true
                    views.exportDialogTilConfirm.error = null
                } else {
                    exportButton.isEnabled = false
                    views.exportDialogTilConfirm.error = activity.getString(CommonStrings.passphrase_passphrase_does_not_match)
                }
            }
        }

        views.exportDialogEt.addTextChangedListener(textWatcher)
        views.exportDialogEtConfirm.addTextChangedListener(textWatcher)
    }

    interface ExportKeyDialogListener {
        fun onPassphrase(passphrase: String)
    }
}
