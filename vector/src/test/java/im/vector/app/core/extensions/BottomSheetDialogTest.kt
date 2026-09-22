/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.view.View
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import im.vector.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BottomSheetDialogTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    @Test
    fun `a view content opens expanded, never collapsing to the peek height`() {
        val content = View(context)
        val dialog = BottomSheetDialog(context).apply { setExpandedContentView(content) }

        assertEquals(BottomSheetBehavior.STATE_EXPANDED, dialog.behavior.state)
        assertTrue(dialog.behavior.skipCollapsed)
    }

    @Test
    fun `a view content is wrapped so it scrolls when it outgrows the window`() {
        val content = View(context)
        BottomSheetDialog(context).apply { setExpandedContentView(content) }

        assertTrue(content.parent is NestedScrollView)
    }

    @Test
    fun `a layout content is inflated into the scrolling wrapper`() {
        val dialog = BottomSheetDialog(context).apply {
            setExpandedContentView(R.layout.bottom_sheet_video_speed)
        }

        val inflated = dialog.findViewById<View>(R.id.speedSeekBar)
        assertNotNull(inflated)
        assertTrue(generateSequence(inflated?.parent) { it.parent }.any { it is NestedScrollView })
        assertEquals(BottomSheetBehavior.STATE_EXPANDED, dialog.behavior.state)
    }
}
