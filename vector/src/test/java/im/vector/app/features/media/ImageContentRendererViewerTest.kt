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
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.ByteArrayLoader
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.request.ResourceCallback
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.glide.ImageContentRendererDataLoader
import im.vector.app.core.ui.model.Size
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageContentRendererViewerTest {

    private val imageView = ImageView(RuntimeEnvironment.getApplication())
    private val activeSessionHolder = mockk<ActiveSessionHolder>(relaxed = true)
    private val renderer = ImageContentRenderer(
            context = RuntimeEnvironment.getApplication(),
            localFilesHelper = mockk(relaxed = true),
            activeSessionHolder = activeSessionHolder,
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
    fun `compression changing the timeline playback mode keeps the upload frame`() {
        assertUploadReplacesPreview(grid = false, video = false, previousMode = ImageContentRenderer.Mode.ANIMATED_THUMBNAIL)
    }

    @Test
    fun `compression changing a gallery original into a thumbnail keeps the upload frame`() {
        assertUploadReplacesPreview(grid = true, video = false, previousMode = ImageContentRenderer.Mode.STICKER)
    }

    @Test
    fun `scrolling back to an encrypted timeline upload reuses its decoded image after sync`() {
        assertRebindUsesUploadCache(grid = false, encrypted = true)
    }

    @Test
    fun `scrolling back to an encrypted gallery upload reuses its decoded image after sync`() {
        assertRebindUsesUploadCache(grid = true, encrypted = true)
    }

    @Test
    fun `scrolling back to an unencrypted timeline upload keeps its cached source model`() {
        assertRebindUsesUploadCache(grid = false, encrypted = false)
    }

    @Test
    fun `scrolling back to an unencrypted gallery upload keeps its cached source model`() {
        assertRebindUsesUploadCache(grid = true, encrypted = false)
    }

    @Test
    fun `resuming an upload thumbnail does not draw a recycled bitmap from its previous request`() {
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val previous = TransitionDrawable(arrayOf(ColorDrawable(Color.BLACK), BitmapDrawable(imageView.resources, source)))
        val local = uploadData().copy(url = "file:///cache/original.jpg", allowNonMxcUrls = true)
        imageView.setImageDrawable(previous)
        imageView.setTag(R.id.image_renderer_last_render, ImageContentRenderer.LastRender(local.stableId, local,
                ImageContentRenderer.Mode.THUMBNAIL, completed = true))

        renderer.render(uploadData(), imageView, 128)
        assertEquals(Color.RED, shownPixel())
        Glide.with(imageView).pauseRequests()
        source.recycle()
        Glide.get(imageView.context).clearMemory()
        Glide.with(imageView).resumeRequests()
        imageView.layout(0, 0, 128, 128)
        imageView.draw(Canvas(Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)))

        assertNotSame(previous, imageView.drawable)
        assertEquals(Color.RED, shownPixel())
    }

    @Test
    fun `a second upload handoff keeps the frame while the first remote load is pending`() {
        val local = uploadData().copy(url = "file:///cache/original.jpg", allowNonMxcUrls = true)
        imageView.setImageDrawable(ColorDrawable(Color.RED))
        imageView.setTag(R.id.image_renderer_last_render, ImageContentRenderer.LastRender(local.stableId, local,
                ImageContentRenderer.Mode.THUMBNAIL, completed = true))
        renderer.render(uploadData().copy(allowNonMxcUrls = true), imageView, 128)
        val frame = imageView.drawable

        renderer.render(uploadData().copy(eventId = "remote-echo"), imageView, 128)

        assertSame(frame, imageView.drawable)
        assertEquals(Color.RED, shownPixel())
    }

    @Test
    fun `an upload frame is not shown for a different message`() {
        val local = uploadData().copy(url = "file:///cache/original.jpg", allowNonMxcUrls = true)
        imageView.setImageDrawable(ColorDrawable(Color.RED))
        imageView.setTag(R.id.image_renderer_last_render, ImageContentRenderer.LastRender(local.stableId, local,
                ImageContentRenderer.Mode.THUMBNAIL, completed = true))

        renderer.render(uploadData().copy(stableId = "another-message"), imageView, 128)

        val current = imageView.getTag(R.id.image_renderer_last_render) as ImageContentRenderer.LastRender
        assertNull(current.uploadFrame)
        assertTrue(imageView.drawable is MediaPlaceholderDrawable)
    }

    @Test
    fun `an explicit retry replaces the retained upload frame with loading feedback`() {
        val local = uploadData().copy(url = "file:///cache/original.jpg", allowNonMxcUrls = true)
        imageView.setImageDrawable(ColorDrawable(Color.RED))
        imageView.setTag(R.id.image_renderer_last_render, ImageContentRenderer.LastRender(local.stableId, local,
                ImageContentRenderer.Mode.THUMBNAIL, completed = true))
        renderer.render(uploadData(), imageView, 128)

        renderer.render(uploadData(), imageView, 128, fromRetryTap = true)

        assertTrue(imageView.drawable is MediaPlaceholderDrawable)
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

    private fun assertUploadReplacesPreview(
            grid: Boolean,
            video: Boolean,
            previousMode: ImageContentRenderer.Mode = ImageContentRenderer.Mode.THUMBNAIL,
    ) {
        val mode = ImageContentRenderer.Mode.THUMBNAIL
        val local = ImageContentRenderer.Data(
                eventId = "local-echo",
                stableId = "upload",
                filename = if (video) "clip.mp4" else "photo.jpg",
                mimeType = if (video) "video/mp4" else "image/png",
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
        imageView.setTag(R.id.image_renderer_last_render, ImageContentRenderer.LastRender(local.stableId, local, previousMode, completed = true))

        if (grid) {
            renderer.render(uploaded, imageView, 128)
        } else {
            renderer.render(uploaded, mode, imageView, cornerTransformation = null)
        }

        val current = imageView.getTag(R.id.image_renderer_last_render) as ImageContentRenderer.LastRender
        assertEquals(uploaded, current.data)
        assertFalse(current.completed)
        assertNotSame(preview, imageView.drawable)
        assertEquals(Color.RED, shownPixel())
    }

    private fun shownPixel(): Int {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val drawable = imageView.drawable
        val bounds = drawable.copyBounds()
        return try {
            drawable.setBounds(0, 0, 128, 128)
            drawable.draw(Canvas(bitmap))
            bitmap.getPixel(64, 64)
        } finally {
            drawable.bounds = bounds
            bitmap.recycle()
        }
    }

    private fun assertRebindUsesUploadCache(grid: Boolean, encrypted: Boolean) {
        val loads = registerSyntheticSource()
        val local = uploadData().copy(
                preservedFile = null, maxWidth = 128, maxHeight = 128, allowNonMxcUrls = true,
                elementToDecrypt = if (encrypted) ElementToDecrypt("synthetic-iv", "synthetic-key", "synthetic-hash") else null,
        )
        every {
            activeSessionHolder.getActiveSession().fileService().getLocalFileFor(any(), any(), any(), any())
        } returns File("cached-upload.jpg")
        val request = renderer.createGlideRequest(local, ImageContentRenderer.Mode.THUMBNAIL, GlideApp.with(imageView), Size(128, 128))
                .let { if (grid) it.optionalCenterCrop() else it.dontAnimate().signature(ObjectKey("still-frame")) }
        val preload = request.submit(128, 128)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val decoded = executor.submit<Drawable> { preload.get(10, TimeUnit.SECONDS) }.get(15, TimeUnit.SECONDS)
            val synced = local.copy(eventId = "remote-echo", allowNonMxcUrls = false, senderName = "Alice", timestampMs = 42L)
            imageView.layoutParams = ViewGroup.LayoutParams(128, 128)
            imageView.layout(0, 0, 128, 128)
            fun bind(data: ImageContentRenderer.Data) {
                if (grid) renderer.render(data, imageView, 128)
                else renderer.render(data, ImageContentRenderer.Mode.THUMBNAIL, imageView, cornerTransformation = null)
            }

            bind(synced)
            assertSame((decoded as BitmapDrawable).bitmap, (imageView.drawable as BitmapDrawable).bitmap)

            renderer.clear(imageView)
            bind(synced)

            val current = imageView.getTag(R.id.image_renderer_last_render) as ImageContentRenderer.LastRender
            assertTrue(current.completed)
            assertSame(decoded.bitmap, (imageView.drawable as BitmapDrawable).bitmap)
            assertEquals(1, loads.get())

            renderer.clear(imageView)
            bind(synced.copy(url = "mxc://example.org/another-image"))
            assertFalse((imageView.getTag(R.id.image_renderer_last_render) as ImageContentRenderer.LastRender).completed)
        } finally {
            GlideApp.with(imageView).clear(preload)
            executor.shutdownNow()
        }
    }

    private fun registerSyntheticSource(): AtomicInteger {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val loads = AtomicInteger()
        // Keep Glide's decoder and memory cache real while supplying bytes without a session.
        Glide.get(imageView.context).registry.replace(
                ImageContentRendererDataLoader.Model::class.java, ByteBuffer::class.java,
                object : ModelLoaderFactory<ImageContentRendererDataLoader.Model, ByteBuffer> {
                    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<ImageContentRendererDataLoader.Model, ByteBuffer> {
                        val delegate = ByteArrayLoader.ByteBufferFactory().build(multiFactory)
                        return object : ModelLoader<ImageContentRendererDataLoader.Model, ByteBuffer> {
                            override fun handles(model: ImageContentRendererDataLoader.Model) = true

                            override fun buildLoadData(
                                    model: ImageContentRendererDataLoader.Model, width: Int, height: Int, options: Options,
                            ): ModelLoader.LoadData<ByteBuffer>? {
                                loads.incrementAndGet()
                                return delegate.buildLoadData(bytes, width, height, options)
                            }
                        }
                    }

                    override fun teardown() = Unit
                },
        )
        return loads
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
