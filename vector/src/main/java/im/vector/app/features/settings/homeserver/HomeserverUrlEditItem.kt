/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.homeserver

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick

class EditableHomeserverUrl(var value: String)

@EpoxyModelClass
abstract class HomeserverUrlEditItem : VectorEpoxyModel<HomeserverUrlEditItem.Holder>(R.layout.item_homeserver_url_edit) {

    @EpoxyAttribute lateinit var url: EditableHomeserverUrl
    @EpoxyAttribute var deletable: Boolean = true
    @EpoxyAttribute var inUse: Boolean = false
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onDeleteClick: ClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onEdited: (() -> Unit)? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.field.removeTextChangedListener(holder.watcher)
        if (holder.field.text.toString() != url.value) {
            holder.field.setText(url.value)
        }
        val boundUrl = url
        holder.watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                boundUrl.value = s?.toString().orEmpty()
                onEdited?.invoke()
            }
        }
        holder.field.addTextChangedListener(holder.watcher)
        holder.field.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }

        holder.inUse.isVisible = inUse
        holder.delete.isVisible = deletable
        holder.delete.onClick(onDeleteClick)
    }

    override fun unbind(holder: Holder) {
        holder.field.removeTextChangedListener(holder.watcher)
        holder.watcher = null
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val field by bind<EditText>(R.id.homeserverUrlEditField)
        val inUse by bind<TextView>(R.id.homeserverUrlEditInUse)
        val delete by bind<ImageButton>(R.id.homeserverUrlEditDelete)
        var watcher: TextWatcher? = null
    }
}
