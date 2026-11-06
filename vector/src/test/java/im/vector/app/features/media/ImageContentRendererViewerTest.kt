/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.app.Application
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.ResourceCallback
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImageContentRendererViewerTest {

    private val imageView = ImageView(RuntimeEnvironment.getApplication())
    private val renderer = ImageContentRenderer(
            context = RuntimeEnvironment.getApplication(),
            localFilesHelper = mockk(),
            activeSessionHolder = mockk(),
            dimensionConverter = mockk(),
            vectorPreferences = mockk(),
            failedMediaTracker = mockk(),
    )

    @After
    fun tearDown() {
        Glide.with(imageView).clear(imageView)
        Glide.tearDown()
    }

    @Test
    fun `replacing a viewer page delivers an in-flight load result to its current target`() {
        val data = ImageContentRenderer.Data(
                eventId = "local-echo",
                filename = "image.jpg",
                mimeType = "image/jpeg",
                url = "mxc://example.org/image",
                elementToDecrypt = null,
                height = null,
                maxHeight = -1,
                width = null,
                maxWidth = -1,
                // Waiting for this unlaid-out view's size keeps the load pending without doing IO.
                preservedFile = File("image.jpg"),
        )
        val provisionalTarget = RecordingTarget(imageView)
        renderer.render(data, imageView, provisionalTarget)
        assertTrue(provisionalTarget.request!!.isRunning)

        val roomTarget = RecordingTarget(imageView)
        renderer.render(data.copy(eventId = "remote-echo"), imageView, roomTarget)

        (roomTarget.request as ResourceCallback).onLoadFailed(GlideException("Load failed"))

        assertTrue(roomTarget.failed)
        assertFalse(provisionalTarget.failed)
    }

    private class RecordingTarget(view: ImageView) : CustomViewTarget<ImageView, Drawable>(view) {
        var failed = false

        override fun onLoadFailed(errorDrawable: Drawable?) {
            failed = true
        }

        override fun onResourceCleared(placeholder: Drawable?) = Unit

        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) = Unit
    }
}
