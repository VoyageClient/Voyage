/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.ui.views.MassRedactionBannerView
import im.vector.app.features.bulk.BulkActionQueue
import im.vector.app.features.bulk.BulkJobKind
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch

/**
 * Wires a [MassRedactionBannerView] to the app-wide [BulkActionQueue] so the running job's progress shows on
 * any screen that embeds the banner (room list, any room timeline) — jobs aren't tied to one room.
 */
object MassRedactionBannerBinder {

    fun bind(banner: MassRedactionBannerView, fragment: Fragment, queue: BulkActionQueue) {
        banner.callback = object : MassRedactionBannerView.Callback {
            override fun onMassRedactionPauseToggled() {
                queue.togglePause()
            }

            override fun onMassRedactionCancelled() {
                confirmCancel(fragment.requireActivity(), queue.activeKind() ?: return) { queue.cancel() }
            }
        }
        val lifecycleOwner = fragment.viewLifecycleOwner
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                queue.progress.collect { banner.render(it) }
            }
        }
    }

    fun confirmCancel(activity: Activity, kind: BulkJobKind, onConfirmed: () -> Unit) {
        val (title, message) = when (kind) {
            BulkJobKind.MASS_REDACTION -> CommonStrings.mass_redaction_cancel_confirmation_title to CommonStrings.mass_redaction_cancel_confirmation_message
            BulkJobKind.FORWARD -> CommonStrings.bulk_forward_cancel_confirmation_title to CommonStrings.bulk_forward_cancel_confirmation_message
            BulkJobKind.REDACT -> CommonStrings.bulk_redact_cancel_confirmation_title to CommonStrings.bulk_redact_cancel_confirmation_message
        }
        MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> onConfirmed() }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }
}
