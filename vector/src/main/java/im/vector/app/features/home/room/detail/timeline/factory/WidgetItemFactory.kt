/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.core.epoxy.VectorEpoxyModel
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.widgets.model.WidgetContent
import javax.inject.Inject

class WidgetItemFactory @Inject constructor(
        private val noticeItemFactory: NoticeItemFactory,
) {

    fun create(params: TimelineItemFactoryParams): VectorEpoxyModel<*>? {
        val event = params.event
        event.root.content.toModel<WidgetContent>() ?: return null
        // There is lot of other widget types we could improve here
        return noticeItemFactory.create(params)
    }
}
