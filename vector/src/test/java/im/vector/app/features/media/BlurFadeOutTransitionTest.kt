/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.view.View
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.request.transition.Transition
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BlurFadeOutTransitionTest {

    private class FakeAdapter(private var drawable: Drawable?) : Transition.ViewAdapter {
        override fun getView(): View = error("no view is needed to pick the outgoing layer")
        override fun getCurrentDrawable(): Drawable? = drawable
        override fun setDrawable(drawable: Drawable?) {
            this.drawable = drawable
        }
    }

    private fun transitionTo(current: Drawable, shown: Drawable?, placeholder: Drawable?): Drawable? {
        val adapter = FakeAdapter(shown)
        BlurFadeOutTransitionFactory(200, placeholder)
                .build(DataSource.REMOTE, true)
                .transition(current, adapter)
        return adapter.currentDrawable
    }

    private fun TransitionDrawable.layers() = (0 until numberOfLayers).map { getDrawable(it) }

    @Test
    fun `fades from what the view is showing`() {
        val current = ColorDrawable()
        val shown = ColorDrawable()

        val result = transitionTo(current, shown = shown, placeholder = ColorDrawable())

        result.shouldBeInstanceOf<TransitionDrawable>()
        (result as TransitionDrawable).layers() shouldBeEqualTo listOf(shown, current)
    }

    @Test
    fun `falls back to the placeholder when the view is showing nothing`() {
        val current = ColorDrawable()
        val placeholder = ColorDrawable()

        val result = transitionTo(current, shown = null, placeholder = placeholder)

        result.shouldBeInstanceOf<TransitionDrawable>()
        (result as TransitionDrawable).layers() shouldBeEqualTo listOf(placeholder, current)
    }

    @Test
    fun `falls back to the placeholder when the view is already showing the result`() {
        val current = ColorDrawable()
        val placeholder = ColorDrawable()

        val result = transitionTo(current, shown = current, placeholder = placeholder)

        result.shouldBeInstanceOf<TransitionDrawable>()
        (result as TransitionDrawable).layers() shouldBeEqualTo listOf(placeholder, current)
    }

    @Test
    fun `sets the result outright when there is nothing to fade out of`() {
        val current = ColorDrawable()

        transitionTo(current, shown = null, placeholder = null) shouldBeEqualTo current
        transitionTo(current, shown = current, placeholder = current) shouldBeEqualTo current
    }
}
