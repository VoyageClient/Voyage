/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * A modal sheet opens collapsed at an automatic peek of `height - width * 9 / 16`, which hits its
 * 64dp floor on any window wider than 16:9 — in landscape the sheet is a strip to be dragged up.
 * Open expanded instead, and scroll the content when it outgrows the window.
 */
fun BottomSheetDialog.setExpandedContentView(view: View) {
    expand(scrollContainer().apply { addView(view) })
}

fun BottomSheetDialog.setExpandedContentView(@LayoutRes layoutResId: Int) {
    expand(scrollContainer().apply { LayoutInflater.from(context).inflate(layoutResId, this, true) })
}

private fun BottomSheetDialog.scrollContainer() = NestedScrollView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

private fun BottomSheetDialog.expand(container: NestedScrollView) {
    setContentView(container)
    behavior.skipCollapsed = true
    behavior.state = BottomSheetBehavior.STATE_EXPANDED
}
