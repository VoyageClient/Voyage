/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.sections

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.EditText
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.lib.strings.CommonStrings

object RoomSectionDialogs {

    fun showNameDialog(context: Context, @StringRes titleRes: Int, initialName: String?, onDone: (String) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_base_edit_text, null)
        val editText = view.findViewById<EditText>(R.id.editText)
        editText.setHint(CommonStrings.room_section_name_hint)
        initialName?.let {
            editText.setText(it)
            editText.setSelection(it.length)
        }
        val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setView(view)
                .setPositiveButton(CommonStrings.ok) { _, _ ->
                    val name = editText.text.toString().trim()
                    if (name.isNotEmpty() && name != initialName) onDone(name)
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .create()
        editText.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    fun showDeleteDialog(context: Context, isEmpty: Boolean, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(context)
                .setTitle(CommonStrings.room_section_delete_prompt)
                .apply { if (!isEmpty) setMessage(CommonStrings.room_section_delete_warning) }
                .setPositiveButton(CommonStrings.action_remove) { _, _ -> onConfirm() }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }
}
