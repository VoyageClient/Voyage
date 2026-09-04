/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting

/**
 * An avatar drawn as an animated shape.
 *
 * Being an [Animatable] is the whole integration: `AnimatedContentImageViewTarget` already starts
 * and stops those with the view, so this plays exactly where an animated WebP avatar would.
 *
 * @param exemptFromCap for the settings previews, which are a modal grid of nothing but shapes: the
 *   cap is there to stop a scrolling list from animating dozens of avatars at once, and applying it
 *   to the picker would leave most of its tiles frozen and looking broken.
 */
class AnimatedAvatarDrawable(
        texture: Bitmap,
        internal val effect: AvatarEffect,
        internal val sizePx: Int,
        internal val exemptFromCap: Boolean = false,
) : Drawable(), Animatable, Drawable.Callback {

    internal var texture: Bitmap = texture
        private set

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * An animated avatar (GIF, animated WebP, APNG) kept running to feed the texture, so the picture
     * on the shape moves as well as the shape itself.
     */
    private var source: Drawable? = null
    private var spareTexture: Bitmap? = null
    private var front: Bitmap? = null
    private var pending = false
    private var running = false

    /** Bumped whenever a frame in flight stops being the frame we want. */
    internal var generation = 0
        private set

    override fun draw(canvas: Canvas) {
        front?.let {
            canvas.drawBitmap(it, null, bounds, paint)
            return
        }
        // Nothing rendered yet. Drawing the texture would show an unshaped square, so render a frame
        // inline at a fraction of the size instead. It is the right shape immediately, costs well
        // under a millisecond, and the full-size frame replaces it a tick later.
        front = AvatarEffectRenderer.renderFirstFrame(effect, texture, FIRST_FRAME_PX, frameNow())
        front?.let { canvas.drawBitmap(it, null, bounds, paint) }
        requestFrame()
    }

    /** Whether this wants frames at all: started, on screen, and hosted by something that draws it. */
    @UiThread
    internal fun wantsFrames() = running && isVisible && callback != null

    @UiThread
    internal fun tick() = requestFrame()

    /** A drawable that is not animating still wants one frame: its shape, held still. */
    private fun requestFrame() {
        // Skip rather than queue behind a frame that has not come back yet.
        if (pending) return
        pending = true
        redrawAnimatedSource()
        AvatarEffectRenderer.request(this, frameNow())
    }

    /**
     * Takes the animated avatar's current frame as the texture for the frame about to be rendered.
     *
     * It goes into the spare buffer and the two are swapped, because the render thread is reading
     * the other one.
     */
    @UiThread
    private fun redrawAnimatedSource() {
        val moving = source ?: return
        val target = spareTexture ?: Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        moving.setBounds(0, 0, sizePx, sizePx)
        moving.draw(canvas)
        spareTexture = texture
        texture = target
    }

    /**
     * Hosts an animated avatar as the source of this shape's texture.
     *
     * Being its [Drawable.Callback] is what keeps it decoding: it schedules its own frames through
     * whoever hosts it, and without a callback it simply stops after the first one.
     */
    @UiThread
    internal fun setAnimatedSource(drawable: Drawable?) {
        if (source === drawable) return
        source?.let {
            (it as? Animatable)?.stop()
            it.callback = null
        }
        source = drawable
        drawable?.let {
            it.callback = this
            it.setBounds(0, 0, sizePx, sizePx)
            (it as? Animatable)?.start()
        }
    }

    // The source's own invalidations are ignored: its frames are picked up on the shape's clock
    // instead, so it cannot drive extra renders of its own.
    override fun invalidateDrawable(who: Drawable) = Unit

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)

    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    private fun frameNow() = if (running) AvatarFrameClock.frame else effect.heroFrame

    /**
     * Points the shape at a new picture without disturbing what is on screen: the frame already
     * drawn stays until the next one is rendered from the new texture.
     */
    @UiThread
    internal fun swapTexture(replacement: Bitmap) {
        if (replacement === texture) return
        texture = replacement
        // A frame already in flight was drawn from the old picture.
        generation++
        pending = false
        // That render is still reading whichever buffer it was handed, so do not offer it back for
        // the next animated frame to be drawn into.
        spareTexture = null
    }

    /**
     * Takes over [previous]'s last frame, so replacing one of these on a view does not flash the
     * unshaped avatar for the frame or two before the first render lands.
     *
     * Ownership moves rather than being shared: the frame is recycled once it is superseded, and two
     * drawables holding the same bitmap would race over that.
     */
    @UiThread
    internal fun adoptFrameFrom(previous: AnimatedAvatarDrawable) {
        val carried = previous.front
        if (previous.effect != effect || carried == null) {
            return
        }
        // Sizes can differ between binds — an unmeasured view guesses — and a scaled frame still
        // beats a blank one for the tick before the real render lands.
        front = if (carried.width == sizePx) carried else Bitmap.createScaledBitmap(carried, sizePx, sizePx, true)
        previous.front = null
    }

    @UiThread
    internal fun onFrameReady(bitmap: Bitmap) {
        pending = false
        val previous = front
        front = bitmap
        AvatarEffectRenderer.recycle(previous)
        invalidateSelf()
    }

    /** Whether this is showing its shape, as opposed to nothing at all. */
    @VisibleForTesting
    internal fun hasFrame() = front != null

    // Glide hands the same drawable back on a rebind, so start() has to work again after stop().
    override fun start() {
        if (running) return
        running = true
        AvatarFrameClock.subscribe(this)
        resumeSource()
    }

    override fun stop() {
        running = false
        pending = false
        generation++
        // Paused, not let go of: this is also what a trip to the background and back looks like, and
        // an animated avatar that was released there would never start decoding again.
        pauseSource()
        AvatarFrameClock.unsubscribe(this)
    }

    /**
     * Lets go of the animated avatar feeding this shape.
     *
     * Glide may recycle it once its request is cleared, and reading a recycled drawable would crash,
     * so whoever owns the request calls this when that happens.
     */
    @UiThread
    internal fun release() {
        setAnimatedSource(null)
    }

    private fun pauseSource() {
        (source as? Animatable)?.stop()
    }

    private fun resumeSource() {
        // Plain start(), not a rewind: the picture would otherwise jump back to its first frame every
        // time the avatar came back on screen.
        (source as? Animatable)?.let { if (!it.isRunning) it.start() }
    }

    override fun isRunning() = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible && running) {
            AvatarFrameClock.subscribe(this)
            resumeSource()
        } else if (!visible) {
            AvatarFrameClock.unsubscribe(this)
            pauseSource()
        }
        return changed
    }

    // Bounds only scale the frame, so a resize leaves the rendered frame valid.
    override fun onBoundsChange(bounds: android.graphics.Rect) = Unit

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth() = sizePx

    override fun getIntrinsicHeight() = sizePx

    private companion object {
        /** Small enough that rendering one on the UI thread is not felt, big enough to read. */
        const val FIRST_FRAME_PX = 48
    }
}
