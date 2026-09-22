/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getPerMessageProfile

data class PerMessageProfileRendering(
        val senderName: String,
        val fallbackDisplayName: String?,
        val avatarUrl: String?,
        val avatarDecryption: ElementToDecrypt?,
)

fun TimelineEvent.renderPerMessageProfile(
        senderName: String,
        enabled: Boolean,
        senderAvatarUrl: String? = senderInfo.avatarUrl,
        senderAvatarDecryption: ElementToDecrypt? = senderInfo.avatarDecryption,
): PerMessageProfileRendering {
    val profile = getPerMessageProfile()?.takeIf { enabled }
            ?: return PerMessageProfileRendering(senderName, null, senderAvatarUrl, senderAvatarDecryption)
    val displayName = profile.displayName ?: senderName
    return PerMessageProfileRendering(
            senderName = "$displayName (${root.senderId ?: senderInfo.userId})",
            fallbackDisplayName = displayName.takeIf { profile.hasFallback },
            avatarUrl = when {
                profile.clearsAvatar -> null
                else -> profile.avatarUrl ?: senderAvatarUrl
            },
            avatarDecryption = when {
                profile.clearsAvatar -> null
                profile.avatarUrl != null -> profile.avatarDecryption
                else -> senderAvatarDecryption
            },
    )
}

fun String.withoutPerMessageProfileFallback(fallbackDisplayName: String?): String {
    if (fallbackDisplayName == null) return this
    val plaintextFallback = "$fallbackDisplayName: "
    if (startsWith(plaintextFallback)) return removePrefix(plaintextFallback)
    return replace(PER_MESSAGE_PROFILE_HTML_FALLBACK, "")
}

private val PER_MESSAGE_PROFILE_HTML_FALLBACK = Regex("<strong\\s+data-mx-profile-fallback(?:=\"\")?\\s*>([^<]+): </strong\\s*>")
