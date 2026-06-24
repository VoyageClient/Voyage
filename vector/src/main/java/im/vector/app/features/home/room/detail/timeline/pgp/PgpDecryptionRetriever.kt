/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.pgp

import android.app.PendingIntent
import im.vector.app.features.pgp.PgpKeyStore
import im.vector.app.features.pgp.PgpResult
import im.vector.app.features.pgp.PgpServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Decrypts PGP message bodies out-of-band (via OpenKeychain) and caches the result per eventId,
 * mirroring [im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever]. The
 * timeline reads the cached state synchronously while building items; when a decryption finishes
 * it emits the eventId on [decrypted] so the fragment can invalidate just that item and rebuild
 * (no per-view listener, so no recycle flash).
 *
 * Lives on the ViewModel scope so it survives rotation.
 */
class PgpDecryptionRetriever(
        private val coroutineScope: CoroutineScope,
        private val pgpServiceManager: PgpServiceManager,
        private val pgpKeyStore: PgpKeyStore,
) {
    sealed interface State {
        object Pending : State
        data class Decrypted(val text: String) : State
        object NeedsInteraction : State
        data class Failed(val message: String) : State
    }

    private val cache = ConcurrentHashMap<String, State>()

    private val _decrypted = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val decrypted: SharedFlow<String> = _decrypted

    private val _interaction = MutableSharedFlow<PendingIntent>(extraBufferCapacity = 4)
    val interaction: SharedFlow<PendingIntent> = _interaction

    /**
     * Returns the current decryption state for a PGP block, kicking off decryption on first sight.
     * [cacheKey] lets one event hold several blocks (body vs formatted_body) without colliding;
     * invalidation still targets [eventId]. Returns null when PGP is disabled (raw armored shown).
     */
    fun getOrRequest(eventId: String, armored: String, cacheKey: String = eventId): State? {
        if (!pgpKeyStore.isEnabled) return null
        cache[cacheKey]?.let { return it }
        if (cache.putIfAbsent(cacheKey, State.Pending) == null) {
            launchDecrypt(eventId, cacheKey, armored)
        }
        return cache[cacheKey]
    }

    /** Forces a (re-)decryption regardless of the auto-decrypt setting, e.g. for "View decrypted source". */
    fun forceDecrypt(eventId: String, armored: String) {
        cache[eventId] = State.Pending
        launchDecrypt(eventId, eventId, armored)
    }

    /** Drops cached failures/interaction-pending entries so they're retried (after an OpenKeychain prompt). */
    fun clearUnresolved() {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value !is State.Decrypted) iterator.remove()
        }
    }

    private fun launchDecrypt(eventId: String, cacheKey: String, armored: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val state = when (val result = pgpServiceManager.decrypt(armored)) {
                is PgpResult.Success -> State.Decrypted(result.data)
                is PgpResult.NeedsInteraction -> {
                    _interaction.tryEmit(result.pendingIntent)
                    State.NeedsInteraction
                }
                is PgpResult.Error -> State.Failed(result.message)
            }
            cache[cacheKey] = state
            _decrypted.tryEmit(eventId)
        }
    }
}
