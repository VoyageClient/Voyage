/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.pgp

import android.app.PendingIntent
import im.vector.app.core.di.ActiveSessionHolder
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Shared "encrypt this text for everyone in the room who has a PGP key" logic, used by both the
 * composer (text / replies) and media captions. Recipients are resolved one address at a time so
 * members without a key are silently skipped (no OpenKeychain picker), and the resolved key ids are
 * cached per room. The room stays unencrypted at the Matrix level.
 */
@Singleton
class PgpRoomEncryptor @Inject constructor(
        private val pgpServiceManager: PgpServiceManager,
        private val pgpKeyStore: PgpKeyStore,
        // Provider to avoid a Dagger cycle via ActiveSessionHolder (see PgpKeyStore).
        private val activeSessionHolder: Provider<ActiveSessionHolder>,
) {
    sealed interface Outcome {
        // armoredFormatted is the separately-encrypted formatted_body (null when there's none) —
        // each field carries its own armored block, never a formatted version of the other.
        data class Encrypted(val armoredBody: String, val armoredFormatted: String?) : Outcome
        object NotConfigured : Outcome
        object NoRecipients : Outcome
        data class NeedsInteraction(val pendingIntent: PendingIntent) : Outcome
        data class Error(val message: String) : Outcome
    }

    /** True when the room has PGP-send mode on and isn't a Matrix-encrypted room. */
    fun isRoomPgpActive(room: Room): Boolean =
            pgpKeyStore.isEnabled && pgpKeyStore.isRoomPgpEnabled(room.roomId) && !room.roomCryptoService().isEncrypted()

    /** Resolves the room's other recipients (for an upfront "can I enable PGP here?" check). */
    suspend fun resolveRoomRecipients(room: Room): LongArray? = resolveOtherRecipients(room)

    suspend fun encryptForRoom(room: Room, text: CharSequence, formattedText: String? = null): Outcome {
        if (room.roomCryptoService().isEncrypted()) return Outcome.NotConfigured
        if (!pgpKeyStore.isEnabled || !pgpKeyStore.hasMyKey() || !pgpServiceManager.isOpenKeychainInstalled()) {
            return Outcome.NotConfigured
        }
        val others = resolveOtherRecipients(room) ?: return Outcome.NoRecipients
        val allKeyIds = (others.toList() + pgpKeyStore.myKeyId).distinct().toLongArray()
        return when (val result = pgpServiceManager.encrypt(text.toString(), emptyList(), allKeyIds, pgpKeyStore.myKeyId)) {
            is PgpResult.Success -> {
                // Encrypt the original formatted_body separately so formatted_body holds its own
                // armored block (not a formatted/escaped copy of the body's block).
                val armoredFormatted = formattedText
                        ?.takeIf { it.isNotEmpty() && it != text.toString() }
                        ?.let { (pgpServiceManager.encrypt(it, emptyList(), allKeyIds, pgpKeyStore.myKeyId) as? PgpResult.Success)?.data }
                Outcome.Encrypted(result.data, armoredFormatted)
            }
            is PgpResult.NeedsInteraction -> Outcome.NeedsInteraction(result.pendingIntent)
            is PgpResult.Error -> {
                // A cached key id may be stale (e.g. key removed) — drop the cache so the next send re-resolves.
                pgpKeyStore.clearRoomRecipientKeyIds(room.roomId)
                Outcome.Error(result.message)
            }
        }
    }

    private suspend fun resolveOtherRecipients(room: Room): LongArray? {
        val cached = pgpKeyStore.getRoomRecipientKeyIds(room.roomId)
        if (cached.isNotEmpty()) return cached
        val resolved = pgpServiceManager.resolveRecipientKeyIds(recipientAddresses(room))
        val others = resolved.filter { it != pgpKeyStore.myKeyId }.distinct().toLongArray()
        if (others.isEmpty()) return null
        pgpKeyStore.setRoomRecipientKeyIds(room.roomId, others)
        return others
    }

    // A pinned override address, else the auto-derived "user@server", for every other joined member.
    private fun recipientAddresses(room: Room): List<String> {
        val myUserId = activeSessionHolder.get().getSafeActiveSession()?.myUserId
        val overrides = pgpKeyStore.getOverrides()
        val members = room.membershipService()
                .getRoomMembers(roomMemberQueryParams { memberships = listOf(Membership.JOIN) })
        val addresses = members.asSequence()
                .filter { it.userId != myUserId }
                .mapNotNull { overrides[it.userId] ?: PgpUtils.matrixIdToEmail(it.userId) }
                .distinct()
                .toList()
        return addresses
    }
}
