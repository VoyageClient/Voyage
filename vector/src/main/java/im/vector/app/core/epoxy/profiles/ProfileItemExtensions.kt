/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.epoxy.profiles

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.airbnb.epoxy.EpoxyController
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.util.MatrixItem

/** A blank [title] leaves only a gap, for a trailing group such as the destructive actions. */
fun EpoxyController.buildProfileSection(title: String, id: String = title) {
    profileSectionItem {
        id("section_$id")
        title(title)
    }
}

fun EpoxyController.buildProfileAction(
        id: String,
        title: String,
        subtitle: String? = null,
        editable: Boolean = true,
        @DrawableRes icon: Int = 0,
        tintIcon: Boolean = true,
        @DrawableRes editableRes: Int? = null,
        destructive: Boolean = false,
        action: ClickListener? = null,
        @DrawableRes accessory: Int = 0,
        accessoryMatrixItem: MatrixItem? = null,
        profileColorGeneration: Long = 0,
        @ColorInt accessoryColor: Int? = null,
        notificationBadge: Boolean = false,
        avatarRenderer: AvatarRenderer? = null
) {
    profileActionItem {
        iconRes(icon)
        tintIcon(tintIcon)
        id("action_$id")
        subtitle(subtitle)
        editable(editable)
        editableRes?.let { editableRes(editableRes) }
        destructive(destructive)
        notificationBadgeVisible(notificationBadge)
        title(title)
        accessoryRes(accessory)
        accessoryMatrixItem(accessoryMatrixItem)
        profileColorGeneration(profileColorGeneration)
        accessoryColor(accessoryColor)
        avatarRenderer(avatarRenderer)
        listener(action)
    }
}
