/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils

/** A section header with an action button on its trailing edge, e.g. a reset for that section. */
class VectorPreferenceCategoryWithAction : PreferenceCategory {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    var actionClickListener: (() -> Unit)? = null

    /** Greys the action out; the section header itself stays visible. */
    var isActionEnabled: Boolean = true
        set(value) {
            field = value
            notifyChanged()
        }

    var isActionVisible: Boolean = true
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        layoutResource = R.layout.vector_preference_category_with_action
        // Matches VectorPreferenceCategory, so both kinds of header align on the same left edge.
        isIconSpaceReserved = true
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.title) as? TextView)?.apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
        }
        (holder.findViewById(R.id.preferenceCategoryAction) as? ImageView)?.apply {
            isVisible = isActionVisible
            isEnabled = isActionEnabled
            alpha = if (isActionEnabled) 1f else 0.4f
            setOnClickListener(
                    if (isActionEnabled) View.OnClickListener { actionClickListener?.invoke() } else null
            )
        }
    }
}
