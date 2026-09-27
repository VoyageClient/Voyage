/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.request.target.CustomTarget
import im.vector.app.features.settings.AvatarShape
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetainedAvatarPreviewTest {
    private val view = ImageView(RuntimeEnvironment.getApplication())
    private val preview = RetainedAvatarPreview(view)

    private fun load(key: String, fallback: Boolean = false): CustomTarget<Drawable> {
        lateinit var target: CustomTarget<Drawable>
        preview.load(key, ClippedDrawableImageViewTarget(view, AvatarShape.SQUARE), fallback) { target = it }
        return target
    }

    @Test
    fun `reverting a selection keeps the selected image until the original is ready`() {
        val selected = ColorDrawable(0xff123456.toInt())
        load("selected").onResourceReady(selected, null)
        val originalRequest = load("original")
        originalRequest.onLoadStarted(ColorDrawable())
        assertSame(selected, view.drawable)
        val original = ColorDrawable(0xff654321.toInt())
        originalRequest.onResourceReady(original, null)
        assertSame(original, view.drawable)
        preview.clear()
        assertNull(view.drawable)
    }

    @Test
    fun `failed replacement keeps a valid preview but deleting shows the default avatar`() {
        val selected = ColorDrawable(0xff123456.toInt())
        load("selected").onResourceReady(selected, null)
        load("unavailable").onLoadFailed(ColorDrawable())
        assertSame(selected, view.drawable)
        val placeholder = ColorDrawable(0xff654321.toInt())
        load("deleted", fallback = true).onLoadFailed(placeholder)
        assertSame(placeholder, view.drawable)
        preview.clear()
    }
}
