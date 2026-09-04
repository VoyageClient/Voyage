/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.Color
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import im.vector.app.features.settings.AvatarShape
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShapeMaskTransformationTest {

    private val pool: BitmapPool = LruBitmapPool(4 * 1024 * 1024)

    @Test
    fun `masking cuts the corners away and keeps the centre`() {
        for (shape in AvatarShape.STATIC) {
            if (shape == AvatarShape.SQUARE) continue
            val result = transform(shape)

            withClue(shape) { result.getPixel(SIZE / 2, SIZE / 2) shouldBeEqualTo Color.RED }
            withClue(shape) { (Color.alpha(result.getPixel(0, 0)) == 0).shouldBeTrue() }
        }
    }

    @Test
    fun `each shape has its own cache key`() {
        val keys = AvatarShape.STATIC.map { key(it) }

        keys.distinct().size shouldBeEqualTo keys.size
        ShapeMaskTransformation(AvatarShape.HEXAGON) shouldBeEqualTo ShapeMaskTransformation(AvatarShape.HEXAGON)
        ShapeMaskTransformation(AvatarShape.HEXAGON) shouldNotBeEqualTo ShapeMaskTransformation(AvatarShape.OCTAGON)
    }

    @Test
    fun `a masked bitmap keeps an alpha channel`() {
        transform(AvatarShape.PENTAGON).hasAlpha().shouldBeTrue()
        // The source had none, so the transform must be what adds it.
        source().hasAlpha().shouldBeFalse()
    }

    private fun transform(shape: AvatarShape): Bitmap {
        val resource = ShapeMaskTransformation(shape).transform(
                org.robolectric.RuntimeEnvironment.getApplication(),
                com.bumptech.glide.load.resource.bitmap.BitmapResource.obtain(source(), pool)!!,
                SIZE,
                SIZE,
        )
        return resource.get()
    }

    private fun source() = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.RED)
        setHasAlpha(false)
    }

    private fun key(shape: AvatarShape): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ShapeMaskTransformation(shape).updateDiskCacheKey(digest)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun <T> withClue(clue: Any, block: () -> T): T = try {
        block()
    } catch (error: AssertionError) {
        throw AssertionError("$clue: ${error.message}", error)
    }

    private companion object {
        const val SIZE = 64
    }
}
