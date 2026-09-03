/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar

import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.amulyakhare.textdrawable.TextDrawable
import im.vector.app.features.emoji.TwemojiProvider
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the avatar shown when there is nothing to load, in the style the user picked. */
@Singleton
class DefaultAvatarFactory @Inject constructor(
        private val vectorPreferences: VectorPreferences,
        private val twemojiProvider: TwemojiProvider,
) {

    // A DM renders as the other user, so it follows the people style, as its color already does.
    fun styleFor(matrixItem: MatrixItem): DefaultAvatarStyle =
            if (matrixItem is MatrixItem.UserItem) vectorPreferences.peopleAvatarStyle() else vectorPreferences.roomAvatarStyle()

    fun create(matrixItem: MatrixItem, @ColorInt color: Int, shape: AvatarShape): Drawable =
            create(styleFor(matrixItem), matrixItem.firstLetterOfDisplayName(), color, shape)

    fun create(style: DefaultAvatarStyle, letter: String, @ColorInt color: Int, shape: AvatarShape): Drawable {
        return when (style) {
            DefaultAvatarStyle.ELEMENT -> letterDrawable(letter, color, shape)
            DefaultAvatarStyle.GENERIC -> GlyphAvatarDrawable(shape, color, AvatarGlyph.PERSON, TINTED_WHITE)
            DefaultAvatarStyle.TWITTER_EGG -> GlyphAvatarDrawable(shape, color, AvatarGlyph.EGG, EGG_WHITE)
            // Not TextDrawable: it centers on the baseline, which leaves a '#' visibly high.
            DefaultAvatarStyle.HASHTAG -> TextAvatarDrawable(shape, color, "#", TINTED_WHITE)
        }
    }

    private fun letterDrawable(letter: String, @ColorInt color: Int, shape: AvatarShape): Drawable {
        twemojiProvider.takeIf { it.enabled }
                ?.bitmapForEmoji(letter)
                ?.let { return TwemojiLetterDrawable(it, color, shape) }
        // Self-shape the letter avatar (proportional corners) so it matches photo avatars at any size.
        return TextDrawable.builder()
                .beginConfig()
                .bold()
                .endConfig()
                .let {
                    when (shape) {
                        AvatarShape.CIRCLE -> it.buildRound(letter, color)
                        AvatarShape.ROUNDED -> it.buildRoundRectPercent(letter, color, AvatarRenderer.ROUNDED_CORNER_PERCENT)
                        AvatarShape.SQUARE -> it.buildRect(letter, color)
                    }
                }
    }

    companion object {
        private val TINTED_WHITE = ColorUtils.setAlphaComponent(Color.WHITE, 128)

        // Twitter's egg was this off-white, not pure white.
        private const val EGG_WHITE = 0xFFF5F8FA.toInt()
    }
}
