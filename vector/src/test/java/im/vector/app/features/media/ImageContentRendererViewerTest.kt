/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.view.ViewGroup
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.ResourceCallback
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import im.vector.app.R
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImageContentRendererViewerTest {

    private val imageView = ImageView(RuntimeEnvironment.getApplication())
    private val renderer = ImageContentRenderer(
            context = RuntimeEnvironment.getApplication(),
            localFilesHelper = mockk(relaxed = true),
            activeSessionHolder = mockk(relaxed = true),
            dimensionConverter = mockk(relaxed = true),
            vectorPreferences = mockk(relaxed = true),
            failedMediaTracker = mockk(relaxed = true),
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

    @Test
    fun `a timeline photo reloads the uploaded image`() {
        assertUploadReplacesPreview(grid = false, video = false)
    }

    @Test
    fun `a timeline video reloads the uploaded thumbnail`() {
        assertUploadReplacesPreview(grid = false, video = true)
    }

    @Test
    fun `a gallery photo reloads the uploaded image`() {
        assertUploadReplacesPreview(grid = true, video = false)
    }

    @Test
    fun `a gallery video reloads the uploaded thumbnail`() {
        assertUploadReplacesPreview(grid = true, video = true)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `resuming an upload thumbnail does not draw a recycled bitmap from its previous request`() {
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val previous = TransitionDrawable(arrayOf(ColorDrawable(Color.BLACK), BitmapDrawable(imageView.resources, source)))
        val local = uploadData().copy(url = "file:///cache/original.jpg", allowNonMxcUrls = true)
        imageView.setImageDrawable(previous)
        imageView.setTag(R.id.image_renderer_last_render, ImageContentRenderer.LastRender(local.stableId, local,
                ImageContentRenderer.Mode.THUMBNAIL, completed = true))

        renderer.render(uploadData(), imageView, 128)
        Glide.with(imageView).pauseRequests()
        source.recycle()
        Glide.get(imageView.context).clearMemory()
        Glide.with(imageView).resumeRequests()
        imageView.layout(0, 0, 128, 128)
        imageView.draw(Canvas(Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)))

        assertNotSame(previous, imageView.drawable)
    }

    @Test
    fun `a failed upload thumbnail retries on the next bind instead of remaining in flight`() {
        val data = uploadData().copy(allowNonMxcUrls = true)
        renderer.render(data, imageView, 128)
        val request = imageView.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag) as com.bumptech.glide.request.Request
        (request as ResourceCallback).onLoadFailed(GlideException("Synthetic cache handoff failure"))
        val failed = imageView.getTag(R.id.image_renderer_last_render) as ImageContentRenderer.LastRender
        assertTrue(failed.failed)

        renderer.render(data.copy(eventId = "remote-echo", allowNonMxcUrls = false), imageView, 128)

        val current = imageView.getTag(R.id.image_renderer_last_render) as ImageContentRenderer.LastRender
        assertNotSame(failed, current)
        assertFalse(current.failed)
        assertNotSame(request, imageView.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag))
    }

    private fun uploadData() = ImageContentRenderer.Data(
            eventId = "local-echo", stableId = "upload", filename = "photo.jpg", mimeType = "image/jpeg",
            url = "mxc://example.org/compressed", elementToDecrypt = null, width = 128, height = 128,
            maxWidth = 256, maxHeight = 256, preservedFile = File("synthetic-preview.jpg"),
    )

    private fun assertUploadReplacesPreview(grid: Boolean, video: Boolean) {
        val mode = ImageContentRenderer.Mode.THUMBNAIL
        val local = ImageContentRenderer.Data(
                eventId = "local-echo",
                stableId = "upload",
                filename = if (video) "clip.mp4" else "photo.jpg",
                mimeType = if (video) "video/mp4" else "image/jpeg",
                url = "content://example.org/source",
                elementToDecrypt = null,
                width = 128,
                height = 128,
                maxWidth = 256,
                maxHeight = 256,
                allowNonMxcUrls = true,
        )
        val uploaded = local.copy(
                eventId = "remote-echo",
                url = "mxc://example.org/compressed",
                mimeType = "image/jpeg",
                allowNonMxcUrls = false,
                preservedFile = File("uploaded-preview.jpg"),
        )
        val preview = ColorDrawable(Color.RED)
        imageView.layoutParams = ViewGroup.LayoutParams(128, 128)
        imageView.setImageDrawable(preview)
        imageView.setTag(R.id.image_renderer_last_render, ImageContentRenderer.LastRender(local.stableId, local, mode, completed = true))

        if (grid) {
            renderer.render(uploaded, imageView, 128)
        } else {
            renderer.render(uploaded, mode, imageView, cornerTransformation = null)
        }

        val current = imageView.getTag(R.id.image_renderer_last_render) as ImageContentRenderer.LastRender
        assertEquals(uploaded, current.data)
        assertFalse(current.completed)
        assertNotSame(preview, imageView.drawable)
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
