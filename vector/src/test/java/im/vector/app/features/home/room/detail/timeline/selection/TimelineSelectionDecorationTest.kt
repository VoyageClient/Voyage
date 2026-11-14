/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimelineSelectionDecorationTest {
    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }
    private val recycler = CountingRecycler(context)
    private val decoration = TimelineSelectionDecoration(context, recycler, TimelineSelection(), { null }, { true }, { null })

    @Test
    fun `gutter animations relayout once per mode change`() {
        recycler.decorationInvalidations = 0
        decoration.animateGutter(true)
        val entering = Rect()
        decoration.getItemOffsets(entering, View(context), recycler, RecyclerView.State())
        (entering.left > 0) shouldBeEqualTo true
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        recycler.decorationInvalidations shouldBeEqualTo 1

        decoration.animateGutter(false)
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        val exiting = Rect()
        decoration.getItemOffsets(exiting, View(context), recycler, RecyclerView.State())
        exiting.left shouldBeEqualTo 0
        recycler.decorationInvalidations shouldBeEqualTo 2
        decoration.dispose()
    }

    private class CountingRecycler(context: Context) : RecyclerView(context) {
        var decorationInvalidations = 0
        override fun invalidateItemDecorations() {
            decorationInvalidations++
            super.invalidateItemDecorations()
        }
    }
}
