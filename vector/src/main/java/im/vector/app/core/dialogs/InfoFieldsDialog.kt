/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.dialogs

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.extensions.setExpandedContentView
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.databinding.DialogInfoFieldsBinding
import im.vector.app.databinding.ItemInfoFieldBinding
import im.vector.lib.strings.CommonStrings

/** Label/value rows, as a bottom sheet or a centered popup; long-pressing a row offers to copy its value. */
class InfoFieldsDialog(
        private val context: Context,
        @StringRes private val title: Int,
        private val asPopup: Boolean = false,
        private val onDismiss: () -> Unit = {},
) {

    private val views = DialogInfoFieldsBinding.inflate(LayoutInflater.from(context))
    private var dialog: Dialog? = null
    private val fields = linkedMapOf<String, String>()
    private val contentBottomPadding = views.root.paddingBottom

    val isShowing get() = dialog?.isShowing == true

    /** Closed by its owner rather than by the user, so nothing is handed back. */
    fun dismiss() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
    }

    fun show(initialFields: Map<String, String>) {
        fields.putAll(initialFields)
        render()
        present()
    }

    /** Only the title and a spinner, until the fields arrive through [replaceAll]. */
    fun showLoading() {
        setLoading(true)
        present()
    }

    private fun setLoading(loading: Boolean) {
        views.infoFieldsLoading.isVisible = loading
        views.infoFields.isVisible = !loading
        // Bottom padding is for the rows; with only the spinner it would push it off-center.
        views.root.updatePadding(bottom = if (loading) 0 else contentBottomPadding)
    }

    private fun present() {
        val shown = if (asPopup) {
            // The popup draws its own title, and its content already sits below it.
            views.infoFieldsTitle.isVisible = false
            views.root.updatePadding(top = 0)
            val scroll = NestedScrollView(context).apply {
                addView(views.root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setView(scroll)
                    .setPositiveButton(CommonStrings.action_close, null)
                    .create()
        } else {
            views.infoFieldsTitle.setText(title)
            BottomSheetDialog(context).apply { setExpandedContentView(views.root) }
        }
        shown.setOnDismissListener { onDismiss() }
        dialog = shown
        shown.show()
    }

    /** New values replace existing ones in place, keeping the order already on screen. */
    fun update(newFields: Map<String, String>) {
        fields.putAll(newFields)
        render()
    }

    fun replaceAll(newFields: Map<String, String>) {
        fields.clear()
        setLoading(false)
        update(newFields)
    }

    private fun render() {
        views.infoFields.removeAllViews()
        fields.forEach { (label, value) ->
            val row = ItemInfoFieldBinding.inflate(LayoutInflater.from(context), views.infoFields, false)
            row.infoFieldLabel.text = label
            row.infoFieldValue.text = value
            row.root.setOnCreateContextMenuListener { menu, _, _ ->
                menu.add(CommonStrings.action_copy).setOnMenuItemClickListener {
                    copyToClipboard(context, value)
                    true
                }
            }
            views.infoFields.addView(row.root)
        }
    }
}
