/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.redaction.PreservationOrigin
import org.matrix.android.sdk.api.session.redaction.PreservedEventContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import timber.log.Timber
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/** A failed reveal, carrying the room so a background failure isn't attributed to whatever is open. */
data class RevealFailureEvent(val roomId: String, val eventId: String, val reason: RevealFailure)

/** Why a reveal couldn't produce content, so the UI can say something specific. */
enum class RevealFailure {
    /** The server's retention window has elapsed; the content is gone for good. */
    CONTENT_DELETED,

    /** The server never held the original. */
    CONTENT_NOT_RECEIVED,

    /** Not a moderator here, and not a server admin. */
    FORBIDDEN,

    /** The homeserver doesn't implement MSC2815. */
    UNSUPPORTED,

    NETWORK;

    /** Asking again can only give the same answer, so it isn't worth retrying. */
    val isPermanent get() = this != NETWORK
}

/**
 * Reads preserved content, fetching it from the server on demand when it isn't already stored.
 *
 * Fetches for different events run concurrently — MSC2815 has no batch endpoint, so revealing a
 * screenful of redactions is N requests either way; serialising them would just make it slower.
 * Concurrent callers for the *same* event share one request.
 */
@Singleton
class RedactedContentRepository @Inject constructor(
        // Lazy: ActiveSessionHolder builds ConfigureAndStartSessionUseCase, which injects this.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        // Lazy: the preserver reaches back here through the settings graph.
        private val mediaPreserver: Lazy<PreservedMediaPreserver>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Content already resolved this session, so rebinding a revealed item doesn't hit the disk.
    // Bounded: in a reveal-everything room this fills passively just by scrolling, and each entry
    // holds a whole message body. Cleared outright on sign-out and cache-clear.
    private val memoryCache = Collections.synchronizedMap(
            object : LinkedHashMap<String, PreservedBody>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreservedBody>?) = size > MEMORY_CACHE_MAX
            }
    )
    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())

    // Events we already failed to resolve. Without this, restoreEvent re-requests on every rebind,
    // so scrolling a room of unrecoverable redactions is an unbounded request loop.
    private val failed = Collections.synchronizedSet(mutableSetOf<String>())

    // Events redacted while their media upload was still pending: the captured copy points at a
    // content URI the server will never serve, so it is dropped and must not be re-captured when the
    // sync echo (or an MSC2815 fetch) hands the same dead content back.
    private val suppressed = Collections.synchronizedSet(mutableSetOf<String>())

    // Transient failures, with the time of the last attempt, so they can be retried after a delay.
    private val transientFailures = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val fetchMutex = Mutex()

    private val _contentResolved = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Emits an event id once its preserved content becomes available. */
    val contentResolved: SharedFlow<String> = _contentResolved.asSharedFlow()

    private val _failures = MutableSharedFlow<RevealFailureEvent>(extraBufferCapacity = 64)
    val failures: SharedFlow<RevealFailureEvent> = _failures.asSharedFlow()

    /** The restored content plus the type it had before redaction. */
    data class PreservedBody(val content: Content, val clearType: String?)

    /** Non-suspending read for bind-time use; null means "not resolved yet". */
    fun cachedContent(eventId: String): PreservedBody? = memoryCache[eventId]

    /**
     * Whether a copy exists at all, memory or disk.
     *
     * [cachedContent] only ever sees what a reveal has already resolved, so it cannot answer "could
     * this be revealed?". The reveal is what would populate it.
     */
    suspend fun hasPreservedContent(eventId: String): Boolean {
        if (memoryCache.containsKey(eventId)) return true
        val session = activeSessionHolder.get().getSafeActiveSession() ?: return false
        return session.redactedContentService().getPreservedContent(eventId) != null
    }

    /**
     * Ensure the content for [eventId] is available, fetching it if needed. Safe to call on every
     * bind: it returns immediately once resolved and never launches a duplicate request.
     */
    fun requestContent(roomId: String, eventId: String) {
        if (eventId in suppressed) return
        if (memoryCache.containsKey(eventId)) return
        if (!isRetryable(eventId)) return
        if (!inFlight.add(eventId)) return
        scope.launch {
            try {
                resolve(roomId, eventId)
            } finally {
                inFlight.remove(eventId)
            }
        }
    }

    private suspend fun resolve(roomId: String, eventId: String) {
        val session = activeSessionHolder.get().getSafeActiveSession() ?: return
        val service = session.redactedContentService()

        service.getPreservedContent(eventId)?.let { stored ->
            memoryCache[eventId] = PreservedBody(stored.content, stored.clearType)
            _contentResolved.tryEmit(eventId)
            return
        }

        if (!session.homeServerCapabilitiesService().getHomeServerCapabilities().canViewUnredactedContent) {
            fail(roomId, eventId, RevealFailure.UNSUPPORTED)
            return
        }

        val event = try {
            session.eventService().getUnredactedEvent(roomId, eventId)
        } catch (failure: Throwable) {
            Timber.d(failure, "Could not fetch unredacted content for $eventId")
            fail(roomId, eventId, failure.toRevealFailure())
            return
        }

        val content = event.getClearContent()
        if (content == null) {
            fail(roomId, eventId, RevealFailure.CONTENT_NOT_RECEIVED)
            return
        }
        memoryCache[eventId] = PreservedBody(content, event.getClearType())
        fetchMutex.withLock {
            service.preserve(
                    PreservedEventContent(
                            eventId = eventId,
                            roomId = roomId,
                            content = content,
                            clearType = event.getClearType(),
                            senderId = event.senderId,
                            originServerTs = event.originServerTs,
                            origin = PreservationOrigin.FETCHED,
                            preservedAt = System.currentTimeMillis(),
                    )
            )
        }
        // MSC2815 hands back the content, not the media. Synapse doesn't purge attachments with the
        // redaction, so the mxc url usually still resolves right now — grab a copy before whatever
        // retention job eventually does remove it.
        content.toModel<MessageContent>()?.let { mediaPreserver.get().preserveAsync(roomId, eventId, it) }
        _contentResolved.tryEmit(eventId)
    }

    /**
     * A definitive answer is remembered forever; a transient one only briefly, so that an eager
     * capture that failed while offline is retried when the message is next viewed instead of being
     * written off for the life of the process.
     */
    private fun fail(roomId: String, eventId: String, reason: RevealFailure) {
        if (reason.isPermanent) failed.add(eventId) else transientFailures[eventId] = System.currentTimeMillis()
        _failures.tryEmit(RevealFailureEvent(roomId, eventId, reason))
    }

    private fun isRetryable(eventId: String): Boolean {
        if (eventId in failed) return false
        val lastAttempt = transientFailures[eventId] ?: return true
        return System.currentTimeMillis() - lastAttempt >= TRANSIENT_RETRY_DELAY_MS
    }

    /** Forgets cached content and failures, e.g. on sign-out or clear-cache. */
    fun clearCaches() {
        memoryCache.clear()
        failed.clear()
        transientFailures.clear()
    }

    fun isSuppressed(eventId: String): Boolean = eventId in suppressed

    /** Extend suppression to another id of the same event (the server id of a suppressed local echo). */
    fun suppressAlso(eventId: String) {
        suppressed.add(eventId)
    }

    /**
     * The user redacted [eventId] while its media was still uploading: the upload is cancelled, so
     * the preserved copy describes media that will never exist. Drop it everywhere and refuse to
     * capture it again (the sync echo carrying the same content may arrive after this call).
     */
    fun discardNeverUploaded(roomId: String, eventId: String) {
        suppressed.add(eventId)
        memoryCache.remove(eventId)
        val session = activeSessionHolder.get().getSafeActiveSession() ?: return
        scope.launch {
            session.redactedContentService().discard(eventId)
            mediaPreserver.get().discard(roomId, eventId)
        }
    }

    private fun Throwable.toRevealFailure(): RevealFailure {
        val error = (this as? Failure.ServerError)?.error ?: return RevealFailure.NETWORK
        return when (error.code) {
            MatrixError.M_UNREDACTED_CONTENT_DELETED -> RevealFailure.CONTENT_DELETED
            MatrixError.M_UNREDACTED_CONTENT_NOT_RECEIVED -> RevealFailure.CONTENT_NOT_RECEIVED
            MatrixError.M_FORBIDDEN -> RevealFailure.FORBIDDEN
            MatrixError.M_UNRECOGNIZED -> RevealFailure.UNSUPPORTED
            else -> RevealFailure.NETWORK
        }
    }

    companion object {
        // Hiding defers deletion, so toggling reveal off and straight back on costs nothing.
        private const val MEMORY_CACHE_MAX = 200
        private const val TRANSIENT_RETRY_DELAY_MS = 60L * 1000
    }
}
