/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.view

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import im.vector.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReplyContentLayoutTest {
    private val context: Context = Robolectric.buildActivity(Activity::class.java).setup().get()

    // Wraps to its natural width like a TextView, unlike a bare View which fills any AT_MOST spec.
    private class NaturalWidthView(context: Context, private val natural: Int) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(resolveSize(natural, widthMeasureSpec), 10)
        }
    }

    private val layout = ReplyContentLayout(context)

    private fun child(natural: Int, width: Int, id: Int = View.NO_ID): View {
        return NaturalWidthView(context, natural).also {
            it.id = id
            layout.addView(it, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun header(natural: Int) = child(natural, ViewGroup.LayoutParams.WRAP_CONTENT, R.id.inReplyToContainer)
    private fun content(natural: Int) = child(natural, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun measureAtMost(size: Int) {
        layout.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    }

    @Test
    fun `content wider than the reply header widens the column`() {
        val header = header(100)
        val content = content(300)
        measureAtMost(1000)
        assertEquals(300, layout.measuredWidth)
        assertEquals(300, content.measuredWidth)
        assertEquals(100, header.measuredWidth)
    }

    @Test
    fun `content narrower than the reply header fills the header width`() {
        header(300)
        val content = content(100)
        measureAtMost(1000)
        assertEquals(300, layout.measuredWidth)
        assertEquals(300, content.measuredWidth)
    }

    @Test
    fun `content is still capped at the available width`() {
        header(100)
        val content = content(2000)
        measureAtMost(1000)
        assertEquals(1000, layout.measuredWidth)
        assertEquals(1000, content.measuredWidth)
    }

    @Test
    fun `another wrap_content child keeps setting the width`() {
        header(100)
        child(150, ViewGroup.LayoutParams.WRAP_CONTENT)
        val content = content(300)
        measureAtMost(1000)
        assertEquals(150, layout.measuredWidth)
        assertEquals(150, content.measuredWidth)
    }

    @Test
    fun `a hidden reply header leaves the stock measure alone`() {
        header(100).visibility = View.GONE
        val content = content(300)
        measureAtMost(1000)
        assertEquals(300, layout.measuredWidth)
        assertEquals(300, content.measuredWidth)
    }

    @Test
    fun `an exact width is used as is`() {
        header(100)
        val content = content(300)
        layout.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(500, layout.measuredWidth)
        assertEquals(500, content.measuredWidth)
    }
}
