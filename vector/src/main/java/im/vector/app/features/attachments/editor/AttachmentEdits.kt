/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.core.net.toUri

/** Edits recorded against an original attachment so re-opening an editor replays them. */
interface AttachmentEdits : Parcelable {
    val hasChanges: Boolean
}

private const val EXTRA_RESTORE_ORIGINAL = "EXTRA_RESTORE_ORIGINAL"
private const val EXTRA_RESTORE_ORIGINAL_URI = "EXTRA_RESTORE_ORIGINAL_URI"

/**
 * An editor result carrying no file: everything was undone, so the attachment goes back to the
 * original it was edited from. Saving nothing is not the same as cancelling, which leaves the
 * previous export in place.
 */
fun restoreOriginalResult(source: Uri? = null): Intent = Intent()
        .putExtra(EXTRA_RESTORE_ORIGINAL, true)
        .putExtra(EXTRA_RESTORE_ORIGINAL_URI, source?.toString())

fun Intent.isRestoreOriginal() = getBooleanExtra(EXTRA_RESTORE_ORIGINAL, false)

/** The source the editor was opened on, for callers that hold no original of their own. */
fun Intent.restoreOriginalUri(): Uri? = getStringExtra(EXTRA_RESTORE_ORIGINAL_URI)?.toUri()
