/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.widget.TextView
import androidx.annotation.StringRes
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.lib.strings.CommonStrings

@EpoxyModelClass
abstract class RedactedMessageItem : AbsMessageItem<RedactedMessageItem.Holder>() {

    // Which "… redacted" label to show; defaults to the generic message one, overridden e.g. for reactions.
    @EpoxyAttribute
    @StringRes
    var redactedTextRes: Int = CommonStrings.event_redacted

    override fun getViewStubId() = STUB_ID

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.redactedTextView.setText(redactedTextRes)
    }

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val redactedTextView by bind<TextView>(R.id.messageContentRedactedText)
    }

    companion object {
        private val STUB_ID = R.id.messageContentRedactedStub
    }
}
