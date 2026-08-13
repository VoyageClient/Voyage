/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.files

/**
 * Whether this url points at bytes on the device — a local echo's own source — rather than at
 * homeserver content. Both schemes occur: what the picker hands us, and the private copy
 * [im.vector.app.features.attachments.SendMediaMaterializer] takes of it.
 */
fun String?.isLocalMediaUri(): Boolean {
    this ?: return false
    return startsWith("content://") || startsWith("file://")
}
