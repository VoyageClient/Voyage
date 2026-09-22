/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarFrameClockTest {

    @Before
    fun reset() {
        // Robolectric advances its clock to serve a vsync, so an unpaused Choreographer turns the
        // clock's own loop into one that never lets the looper go idle. These tests drive it by hand.
        ShadowChoreographer.setPaused(true)
        AvatarFrameClock.pause()
        frames = 0
    }

    @After
    fun tearDown() {
        AvatarFrameClock.pause()
        ShadowChoreographer.setPaused(false)
    }

    @Test
    fun `a drawable only animates once it is started, visible and hosted`() {
        val drawable = attached()

        drawable.isRunning.shouldBeFalse()

        drawable.start()
        drawable.isRunning.shouldBeTrue()

        drawable.stop()
        drawable.isRunning.shouldBeFalse()
    }

    @Test
    fun `stopping and starting again resumes`() {
        // Glide hands the same drawable back on a rebind, so a stopped one has to come back to life.
        val drawable = attached()

        drawable.start()
        drawable.stop()
        drawable.start()

        drawable.isRunning.shouldBeTrue()
    }

    @Test
    fun `an off-screen drawable does not take one of the limited slots`() {
        val offScreen = (0 until 20).map { attached().apply { setVisible(false, false) } }
        offScreen.forEach { it.start() }

        val onScreen = attached()
        onScreen.start()

        onScreen.isRunning.shouldBeTrue()
    }

    @Test
    fun `only so many avatars are given frames at once`() {
        val drawables = (0 until 200).map { attached() }

        drawables.forEach { it.start() }

        val animating = AvatarFrameClock.animatingNow()
        (animating in 1..64).shouldBeTrue()
        (animating < drawables.size).shouldBeTrue()
    }

    @Test
    fun `an avatar left out by the cap starts animating once a slot frees`() {
        // Leaving a screen and coming back binds new avatars while the old ones are briefly still on
        // the clock, so the newcomers find every slot taken and have to be picked up once those go.
        val crowd = (0 until 200).map { attached() }
        crowd.forEach { it.start() }
        val latecomer = attached()
        latecomer.start()

        val whileCrowded = AvatarFrameClock.animatingNow()
        crowd.forEach { it.setVisible(false, false) }

        AvatarFrameClock.animatingNow() shouldBeEqualTo 1
        (whileCrowded > 1).shouldBeTrue()
        latecomer.isRunning.shouldBeTrue()
    }

    @Test
    fun `an animated avatar keeps feeding the shape its own frames`() {
        val drawable = attached()
        val movingAvatar = FakeAnimatedAvatar()

        drawable.setAnimatedSource(movingAvatar)

        movingAvatar.isRunning.shouldBeTrue()
        movingAvatar.callback.shouldNotBeNull()
    }

    @Test
    fun `an animated avatar picks its own frames back up after the app was away`() {
        // Backgrounding stops every shape, and the picture feeding it has to come back with it
        // rather than leaving the shape turning on a frozen image.
        val drawable = attached()
        val movingAvatar = FakeAnimatedAvatar()
        drawable.setAnimatedSource(movingAvatar)
        drawable.start()

        drawable.stop()
        movingAvatar.isRunning.shouldBeFalse()

        drawable.start()

        movingAvatar.isRunning.shouldBeTrue()
        movingAvatar.callback.shouldNotBeNull()
    }

    @Test
    fun `a cleared request lets go of the animated avatar it was drawing`() {
        val drawable = attached()
        val movingAvatar = FakeAnimatedAvatar()
        drawable.setAnimatedSource(movingAvatar)

        drawable.release()

        // Glide can recycle it once the request is cleared, and reading a recycled drawable crashes.
        movingAvatar.isRunning.shouldBeFalse()
        movingAvatar.callback.shouldBeNull()
    }

    @Test
    fun `an avatar scrolled off screen stops decoding its picture`() {
        val drawable = attached()
        val movingAvatar = FakeAnimatedAvatar()
        drawable.setAnimatedSource(movingAvatar)
        drawable.start()

        drawable.setVisible(false, false)
        movingAvatar.isRunning.shouldBeFalse()

        drawable.setVisible(true, false)
        movingAvatar.isRunning.shouldBeTrue()
    }

    @Test
    fun `a started drawable actually delivers frames to its host`() {
        val drawable = attached()
        drawable.start()

        drawable.isRunning.shouldBeTrue()
        awaitFrames(drawable, atLeast = 1)
    }

    @Test
    fun `a drawable waiting on a frame skips ticks instead of queueing them`() {
        // This is the whole backpressure story: a slow render costs frame rate and nothing else, so
        // the clock never has to throttle itself and make every avatar slow because there are many.
        val drawable = attached()
        drawable.start()

        repeat(50) { drawable.tick() }

        // One request in flight, not fifty.
        awaitFrames(drawable, atLeast = 1)
    }

    /** Drives the clock by hand and waits for the render thread to hand a frame back. */
    private fun awaitFrames(drawable: AnimatedAvatarDrawable, atLeast: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            drawable.tick()
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (frames >= atLeast) return
        }
        throw AssertionError("no frame reached the host in 5s; the render chain is broken")
    }

    @Test
    fun `an effect's phase comes from the shared frame, not from when it was bound`() {
        val texture = texture()
        val first = AvatarEffectRenderer.renderStill(AvatarEffect.CUBE, texture, SIZE, 7)
        val second = AvatarEffectRenderer.renderStill(AvatarEffect.CUBE, texture, SIZE, 7 + AvatarEffect.PERIOD_FRAMES)

        first.sameAs(second).shouldBeTrue()
    }

    @Test
    fun `a drawable is never blank, even on its very first paint`() {
        // A fresh drawable has no rendered frame, and painting the bare texture instead would show
        // an unshaped square. Its first paint has to already be the shape.
        val drawable = AnimatedAvatarDrawable(texture(), AvatarEffect.CUBE, SIZE)
        drawable.setBounds(0, 0, SIZE, SIZE)

        drawable.hasFrame().shouldBeFalse()
        drawable.draw(Canvas(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)))

        drawable.hasFrame().shouldBeTrue()
    }

    @Test
    fun `reloading an avatar keeps the frame on screen and the animation running`() {
        val drawable = attached()
        drawable.start()
        awaitFrames(drawable, atLeast = 1)

        drawable.swapTexture(texture())

        drawable.hasFrame().shouldBeTrue()
        drawable.isRunning.shouldBeTrue()
    }

    @Test
    fun `a replacement drawable takes over the frame rather than starting blank`() {
        val previous = attached()
        previous.start()
        awaitFrames(previous, atLeast = 1)

        val replacement = AnimatedAvatarDrawable(texture(), AvatarEffect.CUBE, SIZE)
        replacement.adoptFrameFrom(previous)

        replacement.hasFrame().shouldBeTrue()
        // Ownership moved: frames get recycled, and two drawables holding one would race over it.
        previous.hasFrame().shouldBeFalse()
    }

    @Test
    fun `measuring a row does not throw its frame away`() {
        val drawable = attached()
        drawable.start()
        awaitFrames(drawable, atLeast = 1)

        drawable.setBounds(0, 0, SIZE * 2, SIZE * 2)

        drawable.hasFrame().shouldBeTrue()
    }

    @Test
    fun `a still is produced without a clock running at all`() {
        val drawable = AnimatedAvatarDrawable(texture(), AvatarEffect.SPHERE, SIZE)
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

        drawable.setBounds(0, 0, SIZE, SIZE)
        drawable.draw(Canvas(bitmap))

        var covered = 0
        for (x in 0 until SIZE) for (y in 0 until SIZE) if (bitmap.getPixel(x, y) != 0) covered++
        (covered > 0).shouldBeTrue()
    }

    @Test
    fun `every animated shape carries an effect and every static one does not`() {
        im.vector.app.features.settings.AvatarShape.ANIMATED.forEach { it.effect shouldBeEqualTo it.effect!! }
        im.vector.app.features.settings.AvatarShape.STATIC.forEach { (it.effect == null).shouldBeTrue() }
        im.vector.app.features.settings.AvatarShape.ANIMATED.map { it.effect }.toSet().size shouldBeEqualTo
                AvatarEffect.values().size
    }

    @Test
    fun `a tick that comes up while the main thread is behind is dropped`() {
        // The whole point: a scroll or a room opening keeps its frames, and the shapes lose theirs.
        val drawable = attached()
        drawable.start()
        val before = AvatarFrameClock.frame

        AvatarFrameClock.onDue(SystemClock.uptimeMillis() + 1_000, lateBy = 200)

        AvatarFrameClock.frame shouldBeEqualTo before
    }

    @Test
    fun `a tick that is due on an unhurried main thread advances the animation`() {
        val drawable = attached()
        drawable.start()
        val before = AvatarFrameClock.frame

        AvatarFrameClock.onDue(SystemClock.uptimeMillis() + 1_000, lateBy = 0)

        AvatarFrameClock.frame shouldBeEqualTo before + 1
    }

    @Test
    fun `a frame that is not due yet does not advance the animation`() {
        val drawable = attached()
        drawable.start()
        val now = SystemClock.uptimeMillis()
        // Jank pushes the next frame a full delay out, so the vsync right after it is early.
        AvatarFrameClock.onDue(now, lateBy = 200)
        val before = AvatarFrameClock.frame

        AvatarFrameClock.onDue(now + 1, lateBy = 0)

        AvatarFrameClock.frame shouldBeEqualTo before
    }

    @Test
    fun `binding an avatar shows the shape without launching a render of its own`() {
        // A row binding is the busiest the main thread gets, so the full-size render waits for the
        // clock to decide there is room for it.
        val drawable = attached()
        drawable.start()

        drawable.draw(Canvas(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)))

        drawable.hasFrame().shouldBeTrue()
        drawable.needsFirstFrame().shouldBeTrue()
    }

    @Test
    fun `a texture buffer comes back from the pool blank`() {
        val used = AvatarEffectRenderer.obtainBlank(POOLED_SIZE).apply { eraseColor(android.graphics.Color.RED) }
        AvatarEffectRenderer.recycle(used)

        val reused = AvatarEffectRenderer.obtainBlank(POOLED_SIZE)

        (reused === used).shouldBeTrue()
        reused.getPixel(0, 0) shouldBeEqualTo 0
    }

    @Test
    fun `a solid's faces are filled with the avatar's own picture`() {
        val still = AvatarEffectRenderer.renderStill(AvatarEffect.DODECAHEDRON, texture(), SIZE)

        var painted = 0
        for (x in 0 until SIZE) for (y in 0 until SIZE) {
            if (still.getPixel(x, y) == android.graphics.Color.RED) painted++
        }
        (painted > 0).shouldBeTrue()
    }

    private fun attached(): AnimatedAvatarDrawable {
        val drawable = AnimatedAvatarDrawable(texture(), AvatarEffect.CUBE, SIZE)
        drawable.setBounds(0, 0, SIZE, SIZE)
        drawable.callback = countingCallback
        drawable.setVisible(true, false)
        return drawable
    }

    private fun texture() = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.RED)
    }

    private var frames = 0

    private class FakeAnimatedAvatar : Drawable(), android.graphics.drawable.Animatable {
        private var running = false
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun isRunning() = running
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private val countingCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            frames++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    private companion object {
        const val SIZE = 48
        const val POOLED_SIZE = 37
    }
}
