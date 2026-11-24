/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.displayname

import im.vector.app.features.home.room.detail.timeline.render.TimelineRenderingScope
import org.matrix.android.sdk.api.session.profile.IdentityPresentation
import org.matrix.android.sdk.api.util.MatrixItem

/** The id to show for this item: an anonymized user's stand-in, anything else's own. */
fun MatrixItem.shownId(): String =
        if (this is MatrixItem.UserItem && TimelineRenderingScope.current() == null) IdentityPresentation.displayedId(id, presentationRoomId) else id

fun MatrixItem.getBestName(): String {
    if (this is MatrixItem.UserItem && TimelineRenderingScope.current() == null) IdentityPresentation.name(id, presentationRoomId)?.let { return it }
    // Note: this code is copied from [DisplayNameResolver] in the SDK
    return if (this is MatrixItem.RoomAliasItem && displayName.isNullOrBlank()) {
        // Best name is the id, and we keep the displayName of the room for the case we need the first letter
        id
    } else {
        displayName
                ?.takeIf { it.isNotBlank() }
                ?: VectorMatrixItemDisplayNameFallbackProvider.getDefaultName(this)
    }
}
