/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.graphics.drawable.Animatable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.features.settings.VectorPreferences

/**
 * Keeps a grid of (possibly animated) images smooth to scroll: while the list is dragging/flinging it
 * stops the currently-visible animated drawables, resuming them once it settles. This lets animated
 * emotes/stickers play when idle without the per-frame redraw cost during a scroll. (Glide loading is
 * intentionally NOT paused here — pausing its request manager can get stuck and hang all later loads.)
 *
 * Only active in performance mode — capable devices keep animations running through the scroll.
 * Raw pref read (the key is seeded at startup) since callers include plain custom views.
 */
fun RecyclerView.pauseImageAnimationsWhileScrolling() {
    val performanceMode = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(VectorPreferences.SETTINGS_PERFORMANCE_MODE_KEY, false)
    if (!performanceMode) return
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            val idle = newState == RecyclerView.SCROLL_STATE_IDLE
            for (i in 0 until recyclerView.childCount) {
                toggleAnimatables(recyclerView.getChildAt(i), idle)
            }
        }
    })
}

private fun toggleAnimatables(view: View, play: Boolean) {
    when (view) {
        is ImageView -> (view.drawable as? Animatable)?.let { if (play) it.start() else it.stop() }
        is ViewGroup -> for (i in 0 until view.childCount) toggleAnimatables(view.getChildAt(i), play)
    }
}
