/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.app.Activity
import android.content.Intent
import java.util.UUID

internal object EditorPreviewHandoff {
    const val EXTRA_TOKEN = "EDITOR_PREVIEW_HANDOFF_TOKEN"
    const val EXTRA_DELIVERED = "EDITOR_PREVIEW_HANDOFF_DELIVERED"

    private val receivers = mutableMapOf<String, (Intent) -> Unit>()

    fun register(applyResult: (Intent) -> Unit): String = UUID.randomUUID().toString().also {
        receivers[it] = applyResult
    }

    fun deliver(token: String?, result: Intent): Boolean {
        val receiver = receivers.remove(token) ?: return false
        receiver(result)
        return true
    }

    fun cancel(token: String?) {
        receivers.remove(token)
    }
}

internal fun Activity.finishWithPreview(result: Intent) {
    if (EditorPreviewHandoff.deliver(intent.getStringExtra(EditorPreviewHandoff.EXTRA_TOKEN), result)) {
        result.putExtra(EditorPreviewHandoff.EXTRA_DELIVERED, true)
    }
    setResult(Activity.RESULT_OK, result)
    finish()
}
