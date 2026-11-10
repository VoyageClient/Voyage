/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.voice

import org.json.JSONException
import org.json.JSONObject

/**
 * A voice draft is the recording's attachment JSON; the caption rides along under a key of our own, which the
 * attachment's adapter skips when the draft is read back.
 */
object VoiceDraftCaption {
    private const val KEY_TEXT = "im.voyage.caption"

    fun write(draftJson: String, text: String): String {
        if (text.isBlank()) return draftJson
        return try {
            JSONObject(draftJson).put(KEY_TEXT, text).toString()
        } catch (failure: JSONException) {
            draftJson
        }
    }

    fun read(draftJson: String): String? = try {
        JSONObject(draftJson).optString(KEY_TEXT).takeIf { it.isNotBlank() }
    } catch (failure: JSONException) {
        null
    }
}
