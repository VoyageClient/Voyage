/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import im.vector.app.features.emoji.TwemojiProvider
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.settings.VectorPreferences
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.util.MatrixItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DefaultAvatarFactoryTest {

    private val vectorPreferences = mockk<VectorPreferences> {
        every { peopleAvatarStyle() } returns DefaultAvatarStyle.GENERIC
        every { roomAvatarStyle() } returns DefaultAvatarStyle.HASHTAG
    }
    private val twemojiProvider = mockk<TwemojiProvider> {
        every { enabled } returns false
    }
    private val factory = DefaultAvatarFactory(vectorPreferences, twemojiProvider)

    private fun render(style: DefaultAvatarStyle, color: Int = Color.BLUE, shape: AvatarShape = AvatarShape.SQUARE): Bitmap {
        return factory.create(style, "A", color, shape).renderToBitmap()
    }

    private fun Drawable.renderToBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, SIZE, SIZE)
        draw(Canvas(bitmap))
        return bitmap
    }

    @Test
    fun `a user takes the people style and a room the room style`() {
        factory.styleFor(MatrixItem.UserItem("@alice:example.org", "Alice")) shouldBeEqualTo DefaultAvatarStyle.GENERIC
        factory.styleFor(MatrixItem.RoomItem("!room:example.org", "Room")) shouldBeEqualTo DefaultAvatarStyle.HASHTAG
        factory.styleFor(MatrixItem.SpaceItem("!space:example.org", "Space")) shouldBeEqualTo DefaultAvatarStyle.HASHTAG
    }

    @Test
    fun `an unknown or out-of-kind stored style falls back to Element`() {
        DefaultAvatarStyle.of("GENERIC", DefaultAvatarStyle.PEOPLE) shouldBeEqualTo DefaultAvatarStyle.GENERIC
        DefaultAvatarStyle.of("GENERIC", DefaultAvatarStyle.ROOM) shouldBeEqualTo DefaultAvatarStyle.ELEMENT
        DefaultAvatarStyle.of("NOPE", DefaultAvatarStyle.PEOPLE) shouldBeEqualTo DefaultAvatarStyle.ELEMENT
        DefaultAvatarStyle.of(null, DefaultAvatarStyle.PEOPLE) shouldBeEqualTo DefaultAvatarStyle.ELEMENT
    }

    @Test
    fun `the egg is solid off-white on the palette colour`() {
        val bitmap = render(DefaultAvatarStyle.TWITTER_EGG)

        bitmap.getPixel(HALF, HALF) shouldBeEqualTo Color.rgb(0xF5, 0xF8, 0xFA)
        bitmap.getPixel(1, 1) shouldBeEqualTo Color.BLUE
    }

    @Test
    fun `the generic person is half-transparent white and its body does not touch its head`() {
        val bitmap = render(DefaultAvatarStyle.GENERIC)

        // Half-transparent white over blue, at the centre of the head.
        bitmap.getPixel(HALF, (SIZE * 0.40f).toInt()) shouldBeEqualTo Color.rgb(128, 128, 255)
        // The gap between the head and the body.
        bitmap.getPixel(HALF, (SIZE * 0.635f).toInt()) shouldBeEqualTo Color.BLUE
        // Inside the body, which runs off the bottom edge.
        bitmap.getPixel(HALF, (SIZE * 0.85f).toInt()) shouldBeEqualTo Color.rgb(128, 128, 255)
        bitmap.getPixel(HALF, SIZE - 1) shouldBeEqualTo Color.rgb(128, 128, 255)
    }

    @Test
    fun `the matrix logo is pure white on the palette colour`() {
        val bitmap = render(DefaultAvatarStyle.MATRIX)

        val pixels = (0 until SIZE).flatMap { x -> (0 until SIZE).map { y -> bitmap.getPixel(x, y) } }
        pixels.any { it == Color.WHITE } shouldBeEqualTo true
        bitmap.getPixel(1, 1) shouldBeEqualTo Color.BLUE
    }

    @Test
    fun `the hashtag glyph replaces the letter`() {
        render(DefaultAvatarStyle.HASHTAG).sameAs(render(DefaultAvatarStyle.ELEMENT)) shouldBeEqualTo false
    }

    @Test
    fun `every style honours the circle shape`() {
        DefaultAvatarStyle.values().forEach { style ->
            val bitmap = render(style, shape = AvatarShape.CIRCLE)

            withClue(style) { bitmap.getPixel(0, 0) shouldBeEqualTo Color.TRANSPARENT }
            withClue(style) { bitmap.getPixel(HALF, HALF) shouldNotBeEqualTo Color.TRANSPARENT }
        }
    }

    private fun withClue(style: DefaultAvatarStyle, assertion: () -> Unit) {
        try {
            assertion()
        } catch (error: AssertionError) {
            throw AssertionError("$style: ${error.message}", error)
        }
    }

    companion object {
        private const val SIZE = 64
        private const val HALF = SIZE / 2
    }
}
