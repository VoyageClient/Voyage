/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.view.View
import android.view.ViewGroup
import androidx.core.view.isInvisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.airbnb.epoxy.EpoxyModelWithHolder
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.ui.views.TypingMessageView
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.session.room.sender.SenderInfo

// Never shown: reserves the floating typing indicator's height under the newest message, and is the
// bottom row RecyclerView anchors on to keep the live edge pinned.
@EpoxyModelClass
abstract class TypingItem : EpoxyModelWithHolder<TypingItem.TypingHolder>() {

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    lateinit var avatarRenderer: AvatarRenderer

    @EpoxyAttribute
    var users: List<SenderInfo> = emptyList()

    @EpoxyAttribute
    var roomId: String? = null

    override fun getDefaultLayout(): Int = R.layout.item_typing_users

    override fun bind(holder: TypingHolder) {
        super.bind(holder)

        holder.typingView.apply {
            isInvisible = true
            // Rendered with the same users so the row measures exactly as tall as the indicator.
            if (users.isNotEmpty()) render(users, avatarRenderer, roomId)
            setIdle(users.isEmpty())
        }
    }

    // RecyclerView lays out an invisible or gone item at its full measured height, so the idle row
    // has to shrink explicitly to give its space back to the timeline.
    private fun View.setIdle(idle: Boolean) {
        val params = layoutParams ?: return
        val height = if (idle) {
            resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.typing_idle_height)
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (params.height != height) {
            params.height = height
            layoutParams = params
        }
    }

    class TypingHolder : VectorEpoxyHolder() {
        val typingView by bind<TypingMessageView>(R.id.typingMessageView)
    }
}
