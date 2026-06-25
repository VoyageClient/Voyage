/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

enum class ManagedPackKind {
    /** The user's personal pack (account data), usable everywhere. */
    ACCOUNT,

    /** A pack defined in the current room. */
    THIS_ROOM,

    /** A pack from the room's parent space. */
    SPACE,

    /** A pack from another room the user enabled globally. */
    GLOBAL,
}

data class ManagedPack(
        val kind: ManagedPackKind,
        val displayName: String?,
        val avatarUrl: String?,
        // First image in the pack; serves as the avatar when none is explicitly set (per MSC2545 pickers).
        val firstImageUrl: String?,
        val imageCount: Int,
        val roomId: String?,
        val stateKey: String?,
        val canEdit: Boolean,
        val canToggleGlobal: Boolean,
        val isGloballyEnabled: Boolean,
        // For the settings list, the name of the room a pack lives in (so the user knows its origin).
        val roomDisplayName: String? = null,
)
