/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments

import im.vector.app.core.utils.PERMISSIONS_EMPTY
import im.vector.app.core.utils.PERMISSIONS_FOR_FOREGROUND_LOCATION_SHARING
import im.vector.app.core.utils.PERMISSIONS_FOR_TAKING_PHOTO

/**
 * The all possible types to pick with their required permissions.
 */
enum class AttachmentType(val permissions: List<String>) {
    CAMERA(PERMISSIONS_FOR_TAKING_PHOTO),
    GALLERY(PERMISSIONS_EMPTY),
    FILE(PERMISSIONS_EMPTY),
    STICKER(PERMISSIONS_EMPTY),
    STICKER_LOCAL(PERMISSIONS_EMPTY),
    VOICE_FILE(PERMISSIONS_EMPTY),
    POLL(PERMISSIONS_EMPTY),
    LOCATION(PERMISSIONS_FOR_FOREGROUND_LOCATION_SHARING),
}
