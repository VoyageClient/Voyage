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
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

/** A section header with an action button on its trailing edge, e.g. a reset for that section. */
class VectorPreferenceCategoryWithAction : PreferenceCategory {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { readAttrs(context, attrs) }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { readAttrs(context, attrs) }

    var actionClickListener: (() -> Unit)? = null
    var resetClickListener: (() -> Unit)? = null
    var upgradeClickListener: (() -> Unit)? = null

    var isUpgradeVisible: Boolean = false
        set(value) { field = value; notifyChanged() }
    var isUpgradeEnabled: Boolean = true
        set(value) { field = value; notifyChanged() }

    /** Secondary reset action, shown to the left of the main action. Hidden by default. */
    var isResetVisible: Boolean = false
        set(value) { field = value; notifyChanged() }
    var isResetEnabled: Boolean = false
        set(value) { field = value; notifyChanged() }

    @DrawableRes private var actionIconRes: Int = R.drawable.ic_refresh_cw
    private var actionContentDescriptionRes: Int = CommonStrings.action_reset

    private fun readAttrs(context: Context, attrs: AttributeSet) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.VectorPreferenceCategoryWithAction)
        actionIconRes = a.getResourceId(R.styleable.VectorPreferenceCategoryWithAction_actionIcon, actionIconRes)
        actionContentDescriptionRes =
                a.getResourceId(R.styleable.VectorPreferenceCategoryWithAction_actionContentDescription, actionContentDescriptionRes)
        a.recycle()
    }

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
            setImageResource(actionIconRes)
            contentDescription = context.getString(actionContentDescriptionRes)
            // A custom icon (e.g. our download glyph) is drawn dark; tint it with the accent so it
            // reads as tappable. The default reset icon keeps its own light-grey stroke.
            if (actionIconRes != R.drawable.ic_refresh_cw) {
                setColorFilter(ThemeUtils.getColor(context, androidx.appcompat.R.attr.colorAccent))
                background = null
            } else {
                clearColorFilter()
            }
            isVisible = isActionVisible
            isEnabled = isActionEnabled
            alpha = if (isActionEnabled) 1f else 0.4f
            setOnClickListener(
                    if (isActionEnabled) View.OnClickListener { actionClickListener?.invoke() } else null
            )
        }
        (holder.findViewById(R.id.preferenceCategoryReset) as? ImageView)?.apply {
            setColorFilter(ThemeUtils.getColor(context, androidx.appcompat.R.attr.colorAccent))
            background = null
            isVisible = isResetVisible
            isEnabled = isResetEnabled
            alpha = if (isResetEnabled) 1f else 0.4f
            setOnClickListener(
                    if (isResetEnabled) View.OnClickListener { resetClickListener?.invoke() } else null
            )
        }
        (holder.findViewById(R.id.preferenceCategoryUpgrade) as? ImageView)?.apply {
            setColorFilter(ThemeUtils.getColor(context, androidx.appcompat.R.attr.colorAccent))
            isVisible = isUpgradeVisible
            isEnabled = isUpgradeEnabled
            alpha = if (isUpgradeEnabled) 1f else 0.4f
            setOnClickListener(
                    if (isUpgradeEnabled) View.OnClickListener { upgradeClickListener?.invoke() } else null
            )
        }
    }
}
