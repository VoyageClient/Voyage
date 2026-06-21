/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.resources

import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat

/**
 * Backports API-21 "theme attributes inside drawables" to KitKat.
 *
 * On API < 21 the framework's shape / selector / layer-list inflaters call the instance method
 * [Resources.obtainAttributes] (which has no theme), so a `?attr/...` colour can't be resolved and
 * the drawable fails to inflate. By overriding [obtainAttributes] to obtain the attributes *through
 * the current [theme]*, those references resolve against whatever theme/accent is active — so
 * theming keeps working with no hardcoded colours.
 *
 * Vector drawables still can't be inflated by the framework on pre-21, so [getDrawable] falls back
 * to [VectorDrawableCompat] (which itself resolves theme attributes against the supplied theme).
 *
 * [themeSupplier] is read lazily so a theme/accent change (which recreates the activity) is always
 * reflected.
 */
@Suppress("DEPRECATION")
internal class ThemeAwareResources(
        base: Resources,
        private val themeSupplier: () -> Theme,
) : Resources(base.assets, base.displayMetrics, base.configuration) {

    override fun obtainAttributes(set: AttributeSet, attrs: IntArray): TypedArray {
        return themeSupplier().obtainStyledAttributes(set, attrs, 0, 0)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getDrawable(id: Int): Drawable {
        return loadDrawableCompat(id)
    }

    override fun getDrawable(id: Int, theme: Theme?): Drawable {
        return loadDrawableCompat(id)
    }

    private fun loadDrawableCompat(id: Int): Drawable {
        return try {
            @Suppress("DEPRECATION")
            super.getDrawable(id)
        } catch (notFound: Resources.NotFoundException) {
            // Most likely a <vector>, which the framework can't inflate pre-21.
            VectorDrawableCompat.create(this, id, themeSupplier()) ?: throw notFound
        }
    }
}
