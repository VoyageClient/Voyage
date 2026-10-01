/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.databinding.DialogImagePackProgressBinding
import im.vector.lib.strings.CommonStrings

/** Non-dismissable progress for pack imports/exports: a count line over a bar, with Cancel. */
class ImagePackProgressDialog(context: Context, @StringRes title: Int, onCancel: () -> Unit) {

    private val views = DialogImagePackProgressBinding.inflate(LayoutInflater.from(context))
    private val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(views.root)
            .setNegativeButton(CommonStrings.action_cancel) { _, _ -> onCancel() }
            .setCancelable(false)
            .show()

    init {
        views.imagePackProgressText.text = "…"
    }

    /** [total] 0 = not known yet (indeterminate). */
    fun update(message: CharSequence, done: Int, total: Int) {
        views.imagePackProgressText.text = message
        val bar = views.imagePackProgressBar
        if (total <= 0) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.max = total
            bar.progress = done.coerceIn(0, total)
        }
    }

    fun dismiss() {
        runCatching { dialog.dismiss() }
    }
}
