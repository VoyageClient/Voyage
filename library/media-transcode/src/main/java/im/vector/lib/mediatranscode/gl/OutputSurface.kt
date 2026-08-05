/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.gl

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The decoder's render target: a [SurfaceTexture] fed into [CropTextureRenderer]. Must be built on
 * the thread holding the EGL context, which then also owns [awaitNewImage] and [drawImage].
 *
 * The frame-available callback binds to the Looper of whichever thread creates the SurfaceTexture
 * (the `(listener, handler)` overload is API 21+), so a dedicated one is spun up for it — the codec
 * loop cannot both block waiting and dispatch the callback.
 */
@RequiresApi(17)
internal class OutputSurface(textureCoords: FloatArray, outputWidth: Int, outputHeight: Int) {

    private val renderer = CropTextureRenderer(textureCoords, outputWidth, outputHeight)
    private val callbackThread = HandlerThread("VideoEditFrameCallback").apply { start() }
    private val transformMatrix = FloatArray(MATRIX_SIZE)
    private val frameLock = Object()

    private var frameAvailable = false
    private var surfaceTexture: SurfaceTexture? = null

    val surface: Surface

    init {
        renderer.setup()
        val ready = CountDownLatch(1)
        Handler(callbackThread.looper).post {
            surfaceTexture = SurfaceTexture(renderer.textureId).apply {
                setOnFrameAvailableListener {
                    synchronized(frameLock) {
                        frameAvailable = true
                        frameLock.notifyAll()
                    }
                }
            }
            ready.countDown()
        }
        ready.await()
        surface = Surface(surfaceTexture)
    }

    /**
     * The texture holds a single frame, so every rendered decoder buffer must be consumed before
     * the next is released or it is silently dropped. Bounded so a wedged decoder reads as a stall
     * rather than hanging the export for good.
     */
    fun awaitNewImage(): Boolean {
        synchronized(frameLock) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FRAME_TIMEOUT_MS)
            while (!frameAvailable) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) return false
                frameLock.wait(remaining)
            }
            frameAvailable = false
        }
        return true
    }

    fun drawImage() {
        val texture = surfaceTexture ?: return
        texture.updateTexImage()
        // Composed with, never replaced by, the crop: it carries the decoder's own alignment crop
        // (a 1080-high frame coded as 1088) and the vertical flip.
        texture.getTransformMatrix(transformMatrix)
        renderer.drawFrame(transformMatrix)
    }

    fun release() {
        renderer.release()
        surface.release()
        surfaceTexture?.release()
        surfaceTexture = null
        callbackThread.quit()
    }

    companion object {
        private const val MATRIX_SIZE = 16
        private const val FRAME_TIMEOUT_MS = 2_500L
    }
}
