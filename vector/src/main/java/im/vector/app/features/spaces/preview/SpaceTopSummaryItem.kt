/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.preview

import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.features.home.room.detail.timeline.tools.RoomTopicRenderer
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.app.features.home.room.detail.timeline.tools.formatTopic

@EpoxyModelClass
abstract class SpaceTopSummaryItem : VectorEpoxyModel<SpaceTopSummaryItem.Holder>(R.layout.item_space_top_summary) {

    @EpoxyAttribute
    var topic: String? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    lateinit var topicRenderer: RoomTopicRenderer

    @EpoxyAttribute
    lateinit var formattedMemberCount: String

    private val topicMovementMethod by lazy { createLinkMovementMethod(null) }

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.spaceTopicText.setTextOrHide(topic?.formatTopic(null))
        holder.spaceTopicText.movementMethod = topicMovementMethod
        val segments = topic?.let { topicRenderer.richSegments(it, null) }
        holder.spaceTopicText.isVisible = segments == null && !topic.isNullOrEmpty()
        holder.richTopic.isVisible = segments != null
        if (segments == null) {
            holder.richTopic.removeAllViews()
        } else {
            topicRenderer.renderRich(holder.richTopic, segments, null, null, topicMovementMethod)
        }
        holder.memberCountText.text = formattedMemberCount
    }

    class Holder : VectorEpoxyHolder() {
        val memberCountText by bind<TextView>(R.id.spaceSummaryMemberCountText)
        val spaceTopicText by bind<TextView>(R.id.spaceSummaryTopic)
        val richTopic by bind<LinearLayout>(R.id.spaceSummaryRichTopic)
    }
}
