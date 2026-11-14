/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import im.vector.lib.ui.styles.R as StylesR

class SwipeActionPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {
    var action = MessageSwipeAction.NONE
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    init {
        widgetLayoutResource = R.layout.preference_swipe_action_value
        isPersistent = false
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val disabledColor = ThemeUtils.getColor(context, StylesR.attr.vctr_content_quaternary)
        val iconColor = if (isEnabled) action.color(context, muted = true) else disabledColor
        val iconSize = (20 * context.resources.displayMetrics.density).toInt()
        val icon = action.drawable(context, iconColor).apply { setBounds(0, 0, iconSize, iconSize) }
        (holder.findViewById(R.id.swipe_action_value) as TextView).apply {
            TextViewCompat.setCompoundDrawablesRelative(this, icon, null, null, null)
            setText(action.titleRes)
            setTextColor(
                    when {
                        !this@SwipeActionPreference.isEnabled -> disabledColor
                        action == MessageSwipeAction.REDACT -> iconColor
                        else -> ThemeUtils.getColor(context, StylesR.attr.vctr_content_secondary)
                    }
            )
        }
        val slot = MessageSwipeSlot.values().first { it.preferenceKey == key }
        holder.itemView.contentDescription = context.getString(
                CommonStrings.settings_swipe_binding_description,
                context.getString(slot.titleRes),
                context.getString(action.titleRes),
        )
    }
}
