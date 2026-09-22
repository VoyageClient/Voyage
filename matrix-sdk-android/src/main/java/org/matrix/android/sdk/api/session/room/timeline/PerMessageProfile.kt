/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.timeline

import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel

data class PerMessageProfile(
        val id: String,
        val displayName: String?,
        val avatarUrl: String?,
        val avatarDecryption: ElementToDecrypt?,
        val clearsAvatar: Boolean,
        val hasFallback: Boolean,
)

private const val NEW_CONTENT = "m.new_content"
private const val STABLE_PER_MESSAGE_PROFILE = "m.per_message_profile"
private const val UNSTABLE_PER_MESSAGE_PROFILE = "com.beeper.per_message_profile"
private const val MAX_PROFILE_TEXT_BYTES = 255

fun TimelineEvent.getPerMessageProfile(): PerMessageProfile? {
    if (root.getClearType() !in setOf(EventType.MESSAGE, EventType.STICKER)) return null
    if (root.isRedacted()) return null
    // An edit may restate the profile in m.new_content, only at its top level, or not at all — the
    // last leaving the original's profile standing. Read the edit's raw content: getLastEditNewContent()
    // round-trips replies through a typed model, which drops the unknown profile key.
    val latestEdit = annotations?.editSummary?.latestEdit?.getClearContent()
    val editedProfile = latestEdit?.let {
        (it[NEW_CONTENT] as? Map<*, *>)?.perMessageProfile() ?: it.perMessageProfile()
    }
    return editedProfile ?: root.getClearContent()?.perMessageProfile()
}

private fun Map<*, *>.perMessageProfile(): PerMessageProfile? {
    val raw = (this[STABLE_PER_MESSAGE_PROFILE] ?: this[UNSTABLE_PER_MESSAGE_PROFILE]) as? Map<*, *> ?: return null
    val id = raw["id"] as? String ?: return null
    if (!id.isValidProfileText(allowEmpty = true)) return null
    val displayName = (raw["displayname"] as? String)?.takeIf { it.isValidProfileText() && it.isNotEmpty() }
    val rawAvatarUrl = raw["avatar_url"] as? String
    val encryptedAvatarContent = (raw["avatar_file"] as? Map<*, *>)
            ?.mapNotNull { (key, value) -> (key as? String)?.let { name -> value?.let { name to it } } }
            ?.toMap()
    val encryptedAvatar = encryptedAvatarContent?.toModel<EncryptedFileInfo>()
    val encryptedAvatarUrl = encryptedAvatar?.url?.takeIf { it.isMxcUrl() }
    val avatarUrl = encryptedAvatarUrl ?: rawAvatarUrl?.takeIf { it.isEmpty() || it.isMxcUrl() }
    return PerMessageProfile(
            id = id,
            displayName = displayName,
            avatarUrl = avatarUrl,
            avatarDecryption = encryptedAvatar?.takeIf { encryptedAvatarUrl != null }?.toElementToDecrypt(),
            clearsAvatar = rawAvatarUrl == "" && encryptedAvatarUrl == null,
            hasFallback = raw["has_fallback"] == true,
    )
}

private fun String.isValidProfileText(allowEmpty: Boolean = false): Boolean {
    if ((!allowEmpty && isEmpty()) || toByteArray(Charsets.UTF_8).size > MAX_PROFILE_TEXT_BYTES) return false
    var index = 0
    while (index < length) {
        val character = this[index]
        if (character == '\u0000') return false
        if (character.isHighSurrogate()) {
            if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
            index += 2
        } else {
            if (character.isLowSurrogate()) return false
            index++
        }
    }
    return true
}
