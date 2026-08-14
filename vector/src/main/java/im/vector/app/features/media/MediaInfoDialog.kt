/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import im.vector.app.databinding.BottomSheetMediaInfoBinding
import im.vector.app.databinding.ItemMediaInfoFieldBinding

class MediaInfoDialog(
        private val context: Context,
        private val onDismiss: () -> Unit,
) {

    private val views = BottomSheetMediaInfoBinding.inflate(LayoutInflater.from(context))
    private val dialog = BottomSheetDialog(context)
    private val fields = linkedMapOf<String, String>()

    val isShowing get() = dialog.isShowing

    /** Closed with the viewer rather than by the user, so nothing is handed back to it. */
    fun dismiss() {
        dialog.setOnDismissListener(null)
        dialog.dismiss()
    }

    fun show(initialFields: Map<String, String>) {
        fields.putAll(initialFields)
        render()
        dialog.setContentView(views.root)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    /** The file's own values replace what the event declared, keeping the order already on screen. */
    fun update(probedFields: Map<String, String>) {
        fields.putAll(probedFields)
        render()
    }

    private fun render() {
        views.mediaInfoFields.removeAllViews()
        fields.forEach { (label, value) ->
            val row = ItemMediaInfoFieldBinding.inflate(LayoutInflater.from(context), views.mediaInfoFields, false)
            row.mediaInfoFieldLabel.text = label
            row.mediaInfoFieldValue.text = value
            views.mediaInfoFields.addView(row.root)
        }
    }
}
