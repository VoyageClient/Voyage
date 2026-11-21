/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.avatar.AvatarShapeBackgroundDrawable
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import im.vector.lib.ui.styles.R
import kotlin.math.roundToInt

private const val AVATAR_STACK_SLOTS = 3
private const val BADGE_TINT = 0.22f

/** How many leading avatars of a stack of [total] people to show; the rest go in a "+N" badge in the last slot. */
fun avatarStackFaces(total: Int): Int = if (total > AVATAR_STACK_SLOTS) AVATAR_STACK_SLOTS - 1 else total

fun avatarStackOverflow(total: Int): Int = total - avatarStackFaces(total)

// Autosizing needs an AppCompatTextView of fixed size: [sizePx], the avatar size.
fun TextView.setupAvatarCountBadge(sizePx: Int) {
    gravity = Gravity.CENTER
    maxLines = 1
    includeFontPadding = false
    val inset = resources.displayMetrics.density.roundToInt()
    setPadding(inset, inset, inset, inset)
    // TextDrawable's letter-avatar text: faux-bold light at half the avatar size.
    setTextColor(ThemeUtils.getColor(context, R.attr.vctr_content_primary))
    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    paintFlags = paintFlags or Paint.FAKE_BOLD_TEXT_FLAG
    val max = (sizePx / 2).coerceAtLeast(2)
    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, (sizePx / 4).coerceIn(1, max - 1), max, 1, TypedValue.COMPLEX_UNIT_PX)
}

fun TextView.renderAvatarCount(count: Int, avatarRenderer: AvatarRenderer) {
    // Animated shapes have no fixed outline to fill.
    val shape = avatarRenderer.userAvatarShape().takeUnless { it.isAnimated } ?: AvatarShape.CIRCLE
    backgroundCompat = AvatarShapeBackgroundDrawable(shape, avatarCountBackground())
    text = context.getString(CommonStrings.x_plus, count)
}

// Not a theme gray: some are translucent (the overlapped avatar would show through) and none stands out on every theme.
@ColorInt
private fun TextView.avatarCountBackground(): Int {
    val primary = ThemeUtils.getColor(context, R.attr.vctr_content_primary)
    val tint = ColorUtils.setAlphaComponent(primary, (Color.alpha(primary) * BADGE_TINT).roundToInt())
    return ColorUtils.compositeColors(tint, ThemeUtils.getColor(context, android.R.attr.colorBackground))
}
