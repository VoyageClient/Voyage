/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.billcarsonfr.jsonviewer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.view.ContextMenu
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.getSystemService
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyHolder
import com.airbnb.epoxy.EpoxyModelClass
import com.airbnb.epoxy.EpoxyModelWithHolder
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence

@EpoxyModelClass
internal abstract class ValueItem : EpoxyModelWithHolder<ValueItem.Holder>() {

    @EpoxyAttribute
    var text: EpoxyCharSequence? = null

    @EpoxyAttribute
    var depth: Int = 0

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var copyValue: (() -> String?)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemClickListener: View.OnClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onDoubleTap: (() -> Unit)? = null

    /** Only fires once the double-tap window has passed, so it never precedes [onDoubleTap]. */
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onSingleTap: (() -> Unit)? = null

    override fun getDefaultLayout() = R.layout.item_jv_base_value

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.textView.text = text?.charSequence
        holder.baseView.setPadding(Utils.dpToPx(16 * depth, holder.baseView.context), 0, 0, 0)
        itemClickListener?.let { holder.baseView.setOnClickListener(it) }
        holder.copyValue = copyValue
        holder.onDoubleTap = onDoubleTap
        holder.onSingleTap = onSingleTap
    }

    override fun unbind(holder: Holder) {
        super.unbind(holder)
        holder.baseView.setOnClickListener(null)
        holder.copyValue = null
        holder.onDoubleTap = null
        holder.onSingleTap = null
    }

    class Holder : EpoxyHolder(), View.OnCreateContextMenuListener {

        lateinit var textView: TextView
        lateinit var baseView: LinearLayout
        var copyValue: (() -> String?)? = null
        var onDoubleTap: (() -> Unit)? = null
        var onSingleTap: (() -> Unit)? = null

        // The touch listener only observes (returns false), so the view still performs its own clicks.
        @SuppressLint("ClickableViewAccessibility")
        override fun bindView(itemView: View) {
            baseView = itemView.findViewById(R.id.jvBaseLayout)
            textView = itemView.findViewById(R.id.jvValueText)
            itemView.setOnCreateContextMenuListener(this)
            val gestureDetector = GestureDetector(itemView.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val action = onDoubleTap ?: return false
                    action()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val action = onSingleTap ?: return false
                    action()
                    return true
                }
            })
            // The long-clickable row keeps consuming the stream, so the context menu still works.
            itemView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        override fun onCreateContextMenu(
                menu: ContextMenu?,
                v: View?,
                menuInfo: ContextMenu.ContextMenuInfo?
        ) {
            val copyValue = copyValue
            if (copyValue != null) {
                val menuItem = menu?.add(R.string.copy_value)
                val clipService = v?.context?.getSystemService<ClipboardManager>()
                menuItem?.setOnMenuItemClickListener {
                    val value = copyValue() ?: return@setOnMenuItemClickListener true
                    clipService?.setPrimaryClip(ClipData.newPlainText("", value))
                    true
                }
            }
        }
    }
}
