/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreference
import im.vector.app.core.extensions.setBackgroundKeepingPadding

/**
 * Switch preference with title on multiline (only used in XML).
 */
open class VectorSwitchPreference : SwitchPreference {

    // Note: @JvmOverload does not work here...
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context) : super(context)

    init {
        isIconSpaceReserved = false
    }

    // A recycled row may still carry the settings search highlight as its background.
    private fun restoreRippleBackground(itemView: android.view.View) {
        val ta = itemView.context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        itemView.setBackgroundKeepingPadding(ta.getDrawable(0))
        ta.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        // display the title in multi-line to avoid ellipsis.
        (holder.findViewById(android.R.id.title) as? TextView)?.isSingleLine = false

        restoreRippleBackground(holder.itemView)

        super.onBindViewHolder(holder)
    }
}
