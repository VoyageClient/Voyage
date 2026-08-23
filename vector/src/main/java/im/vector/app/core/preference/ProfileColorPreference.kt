/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import im.vector.app.core.ui.colorpicker.ColorSwatches
import im.vector.app.core.ui.colorpicker.ProfileColorPickerDialogFragment

/** A settings row previewing a name color as a round swatch, with the hex as summary. */
class ProfileColorPreference : Preference {

    private var swatchView: ImageView? = null
    private var color: Int? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    init {
        widgetLayoutResource = R.layout.vector_settings_color_swatch
        isIconSpaceReserved = true
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        swatchView = holder.itemView.findViewById(R.id.settings_color_swatch)
        refreshSwatch()
    }

    fun setColor(@ColorInt color: Int, hex: String, light: Boolean, isDefault: Boolean) {
        this.color = color
        summary = ProfileColorPickerDialogFragment.describe(context, hex, light, isDefault)
        refreshSwatch()
    }

    private fun refreshSwatch() {
        val color = color ?: return
        swatchView?.setImageDrawable(ColorSwatches.round(color))
    }
}
