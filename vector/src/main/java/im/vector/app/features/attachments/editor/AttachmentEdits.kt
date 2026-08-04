/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.os.Parcelable

/** Edits recorded against an original attachment so re-opening an editor replays them. */
interface AttachmentEdits : Parcelable {
    val hasChanges: Boolean
}
