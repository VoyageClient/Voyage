/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import org.matrix.android.sdk.api.session.room.model.message.ImageInfo

/** A pack image being edited. Mutable so inline edits (shortcode, usage) don't require a list rebind. */
class EditableImage(
        var shortcode: String,
        // Null until a local image has been uploaded on Apply.
        var mxcUrl: String?,
        var body: String?,
        var info: ImageInfo?,
        var emoticon: Boolean,
        var sticker: Boolean,
        val local: DraftImage? = null,
        // Not in the pack as applied: shown highlighted until the next Apply.
        var added: Boolean = false,
        var pendingRemoval: Boolean = false,
)
