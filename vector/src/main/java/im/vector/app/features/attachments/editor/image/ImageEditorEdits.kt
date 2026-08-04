/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.image

import android.graphics.RectF
import im.vector.app.features.attachments.editor.AttachmentEdits
import kotlinx.parcelize.Parcelize

/**
 * The user's edits, kept normalised (0..1) against the displayed image so they can be re-applied
 * to the untouched original every time. Re-opening the editor replays these rather than editing
 * the previously exported file, which would compound cropping and JPEG loss.
 */
@Parcelize
data class ImageEditorEdits(
        val userRotation: Int = 0,
        val crop: RectF = RectF(0f, 0f, 1f, 1f),
        val censors: List<RectF> = emptyList()
) : AttachmentEdits {

    override val hasChanges: Boolean
        get() = userRotation != 0 || censors.isNotEmpty() ||
                crop.left > 0.001f || crop.top > 0.001f || crop.right < 0.999f || crop.bottom < 0.999f
}
