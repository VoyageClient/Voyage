/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.ui.views.MassRedactionBannerView
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Wires a [MassRedactionBannerView] to the app-wide [MassRedactionManager] so the job's progress shows on
 * any screen that embeds the banner (room list, any room timeline) — the job isn't tied to one room.
 */
object MassRedactionBannerBinder {

    fun bind(banner: MassRedactionBannerView, fragment: Fragment, manager: MassRedactionManager) {
        banner.callback = object : MassRedactionBannerView.Callback {
            override fun onMassRedactionPauseToggled() {
                manager.togglePause()
            }

            override fun onMassRedactionCancelled() {
                MaterialAlertDialogBuilder(fragment.requireActivity())
                        .setTitle(CommonStrings.mass_redaction_cancel_confirmation_title)
                        .setMessage(CommonStrings.mass_redaction_cancel_confirmation_message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> manager.cancel() }
                        .setNegativeButton(CommonStrings.action_cancel, null)
                        .show()
            }
        }
        val lifecycleOwner = fragment.viewLifecycleOwner
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.stream().collect { banner.render(it) }
            }
        }
    }
}
