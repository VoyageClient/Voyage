/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo

data class MemberSessions(
        val sessions: List<CryptoDeviceInfo>,
        val ambiguousNames: Set<String>,
        val hasUnverified: Boolean,
        val hasDehydratedDevice: Boolean,
)

/** Sorted by name, unverified first once the user is verified. A lone dehydrated device is hidden; several stay. */
fun orderMemberSessions(devices: List<CryptoDeviceInfo>, isMine: Boolean, isUserVerified: Boolean): MemberSessions {
    val dehydrated = devices.filter { it.isDehydrated }
    val visible = if (dehydrated.size == 1) devices - dehydrated.first() else devices
    val sorted = visible.sortedWith(compareBy({ it.displayName()?.trim().orEmpty().lowercase() }, { it.deviceId }))
    val ordered = if (isUserVerified) sorted.sortedBy { it.isSessionVerified(isMine) } else sorted
    val ambiguousNames = sorted
            .mapNotNull { it.displayName()?.trim()?.takeIf(String::isNotEmpty) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    return MemberSessions(
            ordered,
            ambiguousNames,
            hasUnverified = ordered.any { !it.isSessionVerified(isMine) },
            hasDehydratedDevice = dehydrated.size == 1,
    )
}

// Own sessions need cross-signing; for others a local verification also counts.
fun CryptoDeviceInfo.isSessionVerified(isMine: Boolean): Boolean = if (isMine) isCrossSigningVerified else isVerified

/** The session's name, or null when it has none or it just repeats the device id. */
fun CryptoDeviceInfo.distinctDisplayName(): String? = displayName()?.trim()?.takeIf { it.isNotEmpty() && it != deviceId }

/** "Name (id)", or the id alone. */
fun CryptoDeviceInfo.sessionCopyText(): String = distinctDisplayName()?.let { "$it ($deviceId)" } ?: deviceId

fun CryptoDeviceInfo.sessionDisplayName(ambiguousNames: Set<String>): String {
    val name = distinctDisplayName()
    return when {
        name == null -> deviceId
        name in ambiguousNames -> "$name ($deviceId)"
        else -> name
    }
}
