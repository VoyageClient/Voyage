/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.mentions

import android.widget.ImageView
import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.applySpoilerRenderLayer
import im.vector.app.features.html.bindEmoteImageSpans
import im.vector.app.features.html.bindPillImageSpans
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class MentionItem : VectorEpoxyModel<MentionItem.Holder>(R.layout.item_mention) {

    @EpoxyAttribute
    lateinit var avatarRenderer: AvatarRenderer

    @EpoxyAttribute
    lateinit var senderItem: MatrixItem

    @EpoxyAttribute
    lateinit var roomItem: MatrixItem

    @EpoxyAttribute
    lateinit var senderName: CharSequence

    @EpoxyAttribute
    lateinit var roomName: CharSequence

    // Wrapped: binding the emote/pill spans re-sets them, mutating the body's hash mid-bind.
    @EpoxyAttribute
    lateinit var body: EpoxyCharSequence

    @EpoxyAttribute
    var formattedDate: String? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemClickListener: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.view.onClick(itemClickListener)
        avatarRenderer.render(senderItem, holder.avatar)
        avatarRenderer.render(roomItem, holder.roomAvatar)
        holder.sender.text = senderName
        holder.room.text = roomName
        holder.timestamp.text = formattedDate
        holder.body.text = body.charSequence
        holder.body.bindEmoteImageSpans()
        holder.body.bindPillImageSpans()
        holder.body.applySpoilerRenderLayer()
    }

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.mentionAvatar)
        val roomAvatar by bind<ImageView>(R.id.mentionRoomAvatar)
        val sender by bind<TextView>(R.id.mentionSender)
        val timestamp by bind<TextView>(R.id.mentionTimestamp)
        val body by bind<TextView>(R.id.mentionBody)
        val room by bind<TextView>(R.id.mentionRoomName)
    }
}
