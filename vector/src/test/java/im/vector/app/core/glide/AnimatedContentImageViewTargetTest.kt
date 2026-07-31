/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.widget.ImageView
import im.vector.app.test.fakes.FakeAnimatedDrawable
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnimatedContentImageViewTargetTest {

    private val context = RuntimeEnvironment.getApplication()

    private val imageView = ImageView(context)

    private fun target(animate: Boolean) = AnimatedContentImageViewTarget(imageView, animate)

    @Test
    fun `animated content plays when animation is wanted`() {
        val drawable = FakeAnimatedDrawable()

        target(animate = true).onResourceReady(drawable, null)

        drawable.isRunning shouldBeEqualTo true
    }

    @Test
    fun `an animated file served as a still is held on its first frame, and still shown`() {
        val drawable = FakeAnimatedDrawable()

        target(animate = false).onResourceReady(drawable, null)

        drawable.isRunning shouldBeEqualTo false
        imageView.drawable shouldBeEqualTo drawable
    }

    @Test
    fun `coming back to the foreground does not start frozen content`() {
        val drawable = FakeAnimatedDrawable()
        val target = target(animate = false)
        target.onResourceReady(drawable, null)

        target.onStart()

        drawable.isRunning shouldBeEqualTo false
    }

    @Test
    fun `coming back to the foreground resumes playing content`() {
        val drawable = FakeAnimatedDrawable()
        val target = target(animate = true)
        target.onResourceReady(drawable, null)
        drawable.stop()

        target.onStart()

        drawable.isRunning shouldBeEqualTo true
    }
}
