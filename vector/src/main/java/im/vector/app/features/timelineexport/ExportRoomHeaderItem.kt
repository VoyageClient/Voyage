/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.applyEmoteLineSpacing
import im.vector.app.features.html.bindEmoteImageSpans
import im.vector.app.features.html.bindPillImageSpans
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass
abstract class ExportRoomHeaderItem : VectorEpoxyModel<ExportRoomHeaderItem.Holder>(R.layout.item_timeline_event_merged_room_creation_stub) {
    @EpoxyAttribute var topic: EpoxyCharSequence? = null
    @EpoxyAttribute lateinit var snapshot: TimelineExportSnapshot
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) lateinit var avatarRenderer: AvatarRenderer

    override fun bind(holder: Holder) {
        super.bind(holder)
        listOf(R.id.creationEncryptionTile, R.id.creationTileSetAvatarButton, R.id.creationTileAddPeopleButton,
                R.id.mergedSumContainer, R.id.itemMergedSeparatorView).forEach { holder.view.findViewById<View>(it).visibility = View.GONE }
        holder.view.setPadding(dp(holder.view, 16), 0, dp(holder.view, 16), dp(holder.view, 20))
        holder.name.text = snapshot.roomName.prepareForDisplay()
        val description = topic?.charSequence ?: snapshot.topic.prepareForDisplay()
        holder.description.applyEmoteLineSpacing(description)
        holder.description.text = description
        holder.description.bindEmoteImageSpans()
        holder.description.bindPillImageSpans()
        holder.description.visibility = if (description.isBlank()) View.GONE else View.VISIBLE
        val person = snapshot.roomPerson
        avatarRenderer.render(MatrixItem.RoomItem(person?.id ?: EXPORT_ROOM_ID, snapshot.roomName, person?.avatar), holder.avatar)
    }

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()

    class Holder : VectorEpoxyHolder() {
        val avatar by bind<ImageView>(R.id.creationTileRoomAvatarImageView)
        val name by bind<TextView>(R.id.roomNameTileText)
        val description by bind<TextView>(R.id.roomNameDescriptionText)
    }
}
