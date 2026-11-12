/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemBarScrimTest {

    private val content = Rect(0, 100, 1000, 1900)
    private val out = Rect()

    @Test
    fun `an overlay over the whole content dims every bar`() {
        SystemBarScrim.region(Rect(content), content, 1000, 2000, out) shouldBeEqualTo true
        out shouldBeEqualTo Rect(0, 0, 1000, 2000)
    }

    @Test
    fun `an overlay below the toolbar dims only the navigation bar`() {
        SystemBarScrim.region(Rect(0, 300, 1000, 1900), content, 1000, 2000, out) shouldBeEqualTo true
        out shouldBeEqualTo Rect(0, 300, 1000, 2000)
    }

    @Test
    fun `an overlay reaching no edge dims nothing`() {
        SystemBarScrim.region(Rect(100, 300, 900, 1500), content, 1000, 2000, out) shouldBeEqualTo false
    }

    @Test
    fun `an overlay outside the content dims nothing`() {
        SystemBarScrim.region(Rect(0, 1950, 1000, 2000), content, 1000, 2000, out) shouldBeEqualTo false
    }

    @Test
    fun `the scrim paints the bars and leaves the content alone`() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        SystemBarScrimDrawable().apply {
            region.set(0, 0, 100, 200)
            content.set(10, 20, 90, 180)
            color = Color.RED
        }.draw(Canvas(bitmap))

        bitmap.getPixel(50, 10) shouldBeEqualTo Color.RED
        bitmap.getPixel(50, 190) shouldBeEqualTo Color.RED
        bitmap.getPixel(5, 100) shouldBeEqualTo Color.RED
        bitmap.getPixel(95, 100) shouldBeEqualTo Color.RED
        bitmap.getPixel(50, 100) shouldBeEqualTo Color.TRANSPARENT
    }

    @Test
    fun `overlays are found by color, hidden ones included, never inside lists or as the root`() {
        val context = RuntimeEnvironment.getApplication()
        val hidden = View(context).apply { setBackgroundColor(WAITING); visibility = View.GONE }
        val listItem = View(context).apply { setBackgroundColor(WAITING) }
        val root = FrameLayout(context).apply {
            setBackgroundColor(WAITING)
            addView(View(context).apply { setBackgroundColor(Color.WHITE) })
            addView(FrameLayout(context).apply { addView(hidden) })
            addView(RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                addView(listItem)
            })
        }

        SystemBarScrim.findOverlays(root, WAITING) shouldBeEqualTo listOf(hidden)
    }

    private companion object {
        const val WAITING = 0x55555555
    }
}
