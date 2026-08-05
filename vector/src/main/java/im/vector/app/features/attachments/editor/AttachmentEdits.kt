/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.content.Intent
import android.os.Parcelable

/** Edits recorded against an original attachment so re-opening an editor replays them. */
interface AttachmentEdits : Parcelable {
    val hasChanges: Boolean
}

private const val EXTRA_RESTORE_ORIGINAL = "EXTRA_RESTORE_ORIGINAL"

/**
 * An editor result carrying no file: everything was undone, so the attachment goes back to the
 * original it was edited from. Saving nothing is not the same as cancelling, which leaves the
 * previous export in place.
 */
fun restoreOriginalResult(): Intent = Intent().putExtra(EXTRA_RESTORE_ORIGINAL, true)

fun Intent.isRestoreOriginal() = getBooleanExtra(EXTRA_RESTORE_ORIGINAL, false)
