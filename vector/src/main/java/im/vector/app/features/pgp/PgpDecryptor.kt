/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.pgp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide helper that turns a PGP message body into its decrypted form, used by every preview
 * surface (room list, replies, action sheet, …). Decryption goes through [PgpServiceManager]'s
 * shared cache, so a given block is only decrypted once. Synchronous callers get a cache hit or
 * null (and an async decryption is kicked off, with [updates] firing when it lands so the surface
 * can refresh); suspend callers can await the result.
 *
 * Gated by [PgpKeyStore.isEnabled] — when PGP is off, everything returns null and the raw armored
 * body is shown.
 */
@Singleton
class PgpDecryptor @Inject constructor(
        private val pgpServiceManager: PgpServiceManager,
        private val pgpKeyStore: PgpKeyStore,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val updates: SharedFlow<Unit> = _updates

    val isEnabled: Boolean get() = pgpKeyStore.isEnabled

    /** Plaintext for the armored block, decrypting (and caching) if needed; null on failure. */
    suspend fun decryptArmored(armored: String): String? {
        pgpServiceManager.peekDecrypted(armored)?.let { return it }
        return when (val result = pgpServiceManager.decrypt(armored)) {
            is PgpResult.Success -> result.data.also { _updates.tryEmit(Unit) }
            else -> null
        }
    }

    /**
     * Suspend variant: returns [body] with its armored block replaced by the decrypted plaintext,
     * or null when there's nothing to do (not a PGP body, PGP disabled, or decryption failed).
     */
    suspend fun decryptedBody(body: String?): String? {
        if (!pgpKeyStore.isEnabled) return null
        // Strip any reply fallback first so the quoted original (its own armored block) isn't left
        // in place beside the decrypted message in previews / the edit composer.
        val cleaned = body?.let { PgpUtils.stripReplyFallback(it) } ?: return null
        val armored = PgpUtils.extractArmoredBlock(cleaned) ?: return null
        val plain = decryptArmored(armored) ?: return null
        // trimEnd: the armored block in the body ends with a newline (after -----END…-----), which
        // would otherwise reconstruct as a trailing blank line under the message / reply preview.
        return cleaned.replace(armored, plain).trimEnd()
    }

    /**
     * Synchronous variant for render paths that can't suspend. Returns the decrypted body if it's
     * already cached; otherwise kicks off async decryption (emitting [updates] on completion) and
     * returns null. Null also when not a PGP body or PGP is disabled.
     */
    fun peekDecryptedBody(body: String?): String? {
        if (!pgpKeyStore.isEnabled) return null
        val cleaned = body?.let { PgpUtils.stripReplyFallback(it) } ?: return null
        val armored = PgpUtils.extractArmoredBlock(cleaned) ?: return null
        pgpServiceManager.peekDecrypted(armored)?.let { return cleaned.replace(armored, it).trimEnd() }
        if (inFlight.add(armored)) {
            scope.launch {
                try {
                    if (pgpServiceManager.decrypt(armored) is PgpResult.Success) {
                        _updates.tryEmit(Unit)
                    }
                } finally {
                    inFlight.remove(armored)
                }
            }
        }
        return null
    }

    fun isPgpBody(body: String?): Boolean = pgpKeyStore.isEnabled && PgpUtils.bodyContainsPgp(body)
}
