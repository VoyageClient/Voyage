/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R

@EpoxyModelClass
abstract class RedactedMessageItem : AbsMessageItem<RedactedMessageItem.Holder>() {

    // Who deleted it and why, matching the long-press menu and the reply preview.
    @EpoxyAttribute
    var redactedText: CharSequence = ""

    override fun getViewStubId() = STUB_ID

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.redactedTextView.text = redactedText
    }

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val redactedTextView by bind<TextView>(R.id.messageContentRedactedText)
    }

    companion object {
        private val STUB_ID = R.id.messageContentRedactedStub
    }
}
