/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.billcarsonfr.jsonviewer

import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyHolder
import com.airbnb.epoxy.EpoxyModelClass
import com.airbnb.epoxy.EpoxyModelWithHolder

@EpoxyModelClass
internal abstract class NoResultItem : EpoxyModelWithHolder<NoResultItem.Holder>() {

    @EpoxyAttribute
    var text: String? = null

    @EpoxyAttribute
    @ColorInt
    var textColor: Int = 0

    override fun getDefaultLayout() = R.layout.item_jv_no_result

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.textView.text = text
        holder.textView.setTextColor(textColor)
    }

    class Holder : EpoxyHolder() {
        lateinit var textView: TextView

        override fun bindView(itemView: View) {
            textView = itemView.findViewById(R.id.jvNoResultText)
        }
    }
}
