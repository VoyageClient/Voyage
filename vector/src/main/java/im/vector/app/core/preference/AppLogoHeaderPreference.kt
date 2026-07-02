/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import im.vector.app.features.settings.AppLogo
import im.vector.app.features.themes.ThemeUtils

/**
 * Non-interactive header showing the current app-logo glyph tinted with the accent colour, with the
 * app name below it drawn in the bundled serif font.
 */
class AppLogoHeaderPreference @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.vector_preference_app_logo_header
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val itemContext = holder.itemView.context

        (holder.findViewById(R.id.appLogoHeaderImage) as? ImageView)?.apply {
            setImageResource(AppLogo.current(itemContext).logoRes)
            setColorFilter(ThemeUtils.getColor(itemContext, com.google.android.material.R.attr.colorAccent), PorterDuff.Mode.SRC_IN)
        }

        (holder.findViewById(R.id.appLogoHeaderText) as? TextView)?.let { text ->
            runCatching { Typeface.createFromAsset(itemContext.assets, "fonts/anthropic_serif.otf") }
                    .getOrNull()
                    ?.let { text.typeface = it }
        }
    }
}
