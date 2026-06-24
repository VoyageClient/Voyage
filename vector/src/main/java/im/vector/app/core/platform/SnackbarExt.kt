/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.platform

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar

private const val MIN_SNACKBAR_DURATION = 2000
private const val MAX_SNACKBAR_DURATION = 8000
private const val DURATION_PER_LETTER = 50

fun View.showOptimizedSnackbar(message: String, anchorView: View? = null) {
    val snackbar = Snackbar.make(this, message, getDuration(message))
    if (anchorView != null) {
        // Anchoring pins the Snackbar just above the given view (e.g. the composer), which already
        // sits above the keyboard / nav bar — and it tracks that view as the keyboard opens/closes.
        snackbar.anchorView = anchorView
    } else {
        // The hosting activity applies window insets as padding to its root and CONSUMES them, so the
        // Snackbar — which attaches to the (un-inset) content view — can't offset itself above the
        // navigation bar / keyboard and ends up drawn off the bottom edge. Re-apply the bottom inset
        // (system bars + IME) as the Snackbar's bottom margin ourselves.
        val bottomInset = ViewCompat.getRootWindowInsets(this)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                ?.bottom
                ?: 0
        if (bottomInset > 0) {
            (snackbar.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin += bottomInset
                snackbar.view.layoutParams = lp
            }
        }
    }
    snackbar.show()
}

private fun getDuration(message: String): Int {
    return (message.length * DURATION_PER_LETTER).coerceIn(MIN_SNACKBAR_DURATION, MAX_SNACKBAR_DURATION)
}
