/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.location

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.ui.styles.R as StylesR

/** The location screens' pins and controls in the app's accent; their drawables are plain white shapes. */
object LocationAccent {

    /** The pin dropped on a chosen spot. */
    fun pin(context: Context): Drawable {
        val shape = tinted(context, R.drawable.ic_location_pin, fill(context))
        val glyph = tinted(context, R.drawable.ic_location_pin_glyph, ThemeUtils.getColor(context, StylesR.attr.vctr_content_on_accent))
        return LayerDrawable(arrayOf(shape, glyph))
    }

    /** The marker for the user's own position. */
    fun userLocation(context: Context): Drawable = tinted(context, R.drawable.ic_location_user, fill(context))

    /** The locate button, whose icon sits on a white square. */
    fun locateButton(context: Context): Drawable? {
        val button = AppCompatResources.getDrawable(context, R.drawable.btn_locate)?.mutate() ?: return null
        // A white accent would vanish on the white button.
        val iconColor = if (ThemeUtils.isMonochromeAccent(context)) Color.BLACK else fill(context)
        (button as? LayerDrawable)?.findDrawableByLayerId(R.id.locate_icon)?.let { DrawableCompat.setTint(it, iconColor) }
        return button
    }

    private fun fill(context: Context) = ThemeUtils.getColor(context, StylesR.attr.vctr_accent_fill)

    private fun tinted(context: Context, drawableRes: Int, color: Int): Drawable =
            DrawableCompat.wrap(AppCompatResources.getDrawable(context, drawableRes)!!.mutate()).also { DrawableCompat.setTint(it, color) }
}
