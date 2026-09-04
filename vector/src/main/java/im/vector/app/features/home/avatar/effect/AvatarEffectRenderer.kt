/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.SparseArray
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread

/**
 * Renders effect frames off the main thread, into each drawable's spare buffer.
 *
 * The main thread never does mesh maths: it asks for a frame, and later swaps two references.
 */
object AvatarEffectRenderer {

    private val main = Handler(Looper.getMainLooper())

    /**
     * A render thread with scratch buffers of its own.
     *
     * There are several because the work is per avatar and serial on each thread: a screen full of
     * shapes, or the settings picker showing every shape at once, would otherwise queue behind one
     * thread and each shape would only get a frame once the whole queue had drained.
     */
    private class Renderer(index: Int) {
        // Not THREAD_PRIORITY_BACKGROUND: below Lollipop that lands the thread in a cgroup capped
        // near a twentieth of a core, which would starve it into dropping every frame.
        private val thread = HandlerThread(
                "avatar-fx-$index",
                Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE,
        ).apply { start() }

        val handler = Handler(thread.looper)
        val painter = AvatarEffectPainter()
    }

    private val renderers by lazy { List(threadCount()) { Renderer(it) } }
    private val stillPainter by lazy { AvatarEffectPainter() }
    private val firstFramePainter by lazy { AvatarEffectPainter() }
    private val pool = SparseArray<ArrayDeque<Bitmap>>()

    // One thread short of the machine, capped: past a handful the frames already keep up, and the
    // rest is only scratch buffers nothing needs.
    private fun threadCount() = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, MAX_RENDER_THREADS)

    /**
     * Always the same thread for a given drawable, so its frames stay in order and its painter's
     * caches, such as a sphere's pixel mapping, stay warm for the effect it is drawing.
     */
    private fun rendererFor(drawable: AnimatedAvatarDrawable) =
            renderers[(System.identityHashCode(drawable) and Int.MAX_VALUE) % renderers.size]

    @UiThread
    fun request(drawable: AnimatedAvatarDrawable, frame: Int) {
        val generation = drawable.generation
        val sizePx = drawable.sizePx
        val effect = drawable.effect
        val texture = drawable.texture
        val renderer = rendererFor(drawable)
        renderer.handler.post {
            val target = obtain(sizePx)
            paint(renderer.painter, target, effect, texture, sizePx, frame)
            main.post {
                if (drawable.generation != generation) {
                    // The avatar changed under this render; the frame on screen stays put.
                    recycle(target)
                } else {
                    drawable.onFrameReady(target)
                }
            }
        }
    }

    /** Renders one frame synchronously, for stills and for the settings previews. */
    @AnyThread
    fun renderStill(effect: AvatarEffect, texture: Bitmap, sizePx: Int, frame: Int = effect.heroFrame): Bitmap {
        val target = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        paint(stillPainter, target, effect, texture, sizePx, frame)
        return target
    }

    /**
     * Renders the frame a drawable shows before its first real one arrives.
     *
     * It has its own painter so that the UI thread never blocks behind a full-size render on the
     * worker.
     */
    @UiThread
    fun renderFirstFrame(effect: AvatarEffect, texture: Bitmap, sizePx: Int, frame: Int): Bitmap {
        val target = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        firstFramePainter.paint(canvas, effect, texture, sizePx, frame)
        return target
    }

    /**
     * Drops what the renderer is holding: spare frame buffers and the shared face geometry.
     *
     * A foreground service keeps this process looking foreground forever, so the system never asks
     * for memory back at the levels that would normally free these; going to the background is the
     * app's own cue, as it is for Glide's bitmap cache.
     */
    @UiThread
    fun releaseMemory() {
        synchronized(pool) { pool.clear() }
        SolidGeometryCache.clear()
    }

    @AnyThread
    fun recycle(bitmap: Bitmap?) {
        bitmap ?: return
        if (bitmap.width != bitmap.height || bitmap.isRecycled) return
        synchronized(pool) {
            val bucket = pool.get(bitmap.width) ?: ArrayDeque<Bitmap>().also { pool.put(bitmap.width, it) }
            if (bucket.size < POOL_PER_SIZE) bucket.addLast(bitmap) else bitmap.recycle()
        }
    }

    // A painter carries per-frame scratch buffers, so each one is used by a single thread at a time.
    @AnyThread
    private fun paint(painter: AvatarEffectPainter, target: Bitmap, effect: AvatarEffect, texture: Bitmap, sizePx: Int, frame: Int) {
        val canvas = Canvas(target)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        synchronized(painter) { painter.paint(canvas, effect, texture, sizePx, frame) }
    }

    @WorkerThread
    private fun obtain(sizePx: Int): Bitmap {
        val reused = synchronized(pool) { pool.get(sizePx)?.removeLastOrNull() }
        return reused?.takeIf { !it.isRecycled } ?: Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    private const val POOL_PER_SIZE = 8
    private const val MAX_RENDER_THREADS = 4
}
