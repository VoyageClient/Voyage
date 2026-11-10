/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Looper
import android.widget.ImageView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.request.target.DrawableImageViewTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BannerRendererTest {
    private val placeholder = ColorDrawable(Color.GRAY)
    private val image = ColorDrawable(Color.WHITE)
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    // Attached, since View.postDelayed only runs once the view is in a window.
    private val view = ImageView(activity).apply {
        setImageDrawable(placeholder)
        activity.setContentView(this)
    }
    private val target = DrawableImageViewTarget(view)

    @Test
    fun `a downloaded banner crossfades and then settles to the bare image`() {
        val transition = BannerRenderer.SettlingCrossFadeFactory.build(DataSource.REMOTE, true)
        assertTrue(transition.transition(image, target))
        assertTrue(view.drawable is TransitionDrawable)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(220))
        assertSame(image, view.drawable)
    }

    @Test
    fun `a newer image set during the fade is not replaced`() {
        val transition = BannerRenderer.SettlingCrossFadeFactory.build(DataSource.REMOTE, true)
        transition.transition(image, target)
        val newer = ColorDrawable(Color.BLACK)
        view.setImageDrawable(newer)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(220))
        assertSame(newer, view.drawable)
    }

    @Test
    fun `a memory cache hit appears without a fade`() {
        val transition = BannerRenderer.SettlingCrossFadeFactory.build(DataSource.MEMORY_CACHE, true)
        assertFalse(transition.transition(image, target))
        assertSame(placeholder, view.drawable)
    }
}
