/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.matrix.android.sdk.api.debug.DebugLog

object TimelineScrollDebug {

    inline fun log(message: () -> String) {
        DebugLog.i { "SCROLLDBG ${message()}" }
    }

    /** Pixels the newest row's bottom sits below the list's bottom edge; null when it isn't laid out. */
    fun hang(rv: RecyclerView, lm: LinearLayoutManager): Int? {
        val newest = lm.findViewByPosition(0) ?: return null
        return lm.getDecoratedBottom(newest) - (rv.height - rv.paddingBottom)
    }

    fun state(rv: RecyclerView?, lm: LinearLayoutManager?): String {
        if (rv == null || lm == null) return "rv=null"
        val newest = lm.findViewByPosition(0)
        val loc = IntArray(2).also { rv.getLocationInWindow(it) }
        return "atLiveEdge=${TimelineLiveEdge.isAtLiveEdge(rv, lm)} hang=${hang(rv, lm)} canScrollNewer=${rv.canScrollVertically(1)} " +
                "inWindow=${loc[1]}..${loc[1] + rv.height} clip=${ViewCompat.getClipBounds(rv)} " +
                "firstVis=${lm.findFirstVisibleItemPosition()} firstFull=${lm.findFirstCompletelyVisibleItemPosition()} " +
                "items=${lm.itemCount} children=${lm.childCount} " +
                "pos0=${newest?.let { "${lm.getDecoratedTop(it)}..${lm.getDecoratedBottom(it)}" }} " +
                "h=${rv.height} padT=${rv.paddingTop} padB=${rv.paddingBottom} " +
                "scrollState=${rv.scrollState} layoutRequested=${rv.isLayoutRequested} computing=${rv.isComputingLayout}"
    }

    fun caller(depth: Int = 6): String = Throwable().stackTrace
            .drop(2)
            .take(depth)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
}
