/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search.index

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.model.MXEventDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.EventMapper
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.EventInsertLiveProcessor
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.redaction.PreservedContent
import org.matrix.android.sdk.internal.session.room.redaction.RedactedContentStore
import org.matrix.android.sdk.internal.session.search.extractMentionedUserIds
import org.matrix.android.sdk.internal.session.search.searchMsgTypes
import org.matrix.android.sdk.internal.util.BackgroundDetectionObserver
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Maintains the local message search index (the equivalent of Element Desktop's seshat event
 * index) over encrypted rooms — and unencrypted ones too unless [setUnencryptedRoomsEnabled]
 * turns those over to the server. Three feeds:
 * - live: decryptors report each successful decryption ([onEventsDecrypted]), and clear events
 *   are picked up at insert time (the [EventInsertLiveProcessor] side);
 * - sweep: an incremental scan of the session `event` table picks up rows that predate the index
 *   or arrived while it was disabled;
 * - crawler: room history is fetched via /messages checkpoint by checkpoint, decrypted and
 *   indexed, mirroring element-web's EventIndex crawler.
 */
@SessionScope
internal class EventIndexer @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dbDispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val indexStore: EventIndexStore,
        private val redactedContentStore: RedactedContentStore,
        private val roomAPI: RoomAPI,
        private val cryptoService: dagger.Lazy<CryptoService>,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val backgroundDetectionObserver: BackgroundDetectionObserver,
) : SessionLifecycleObserver, EventInsertLiveProcessor, DecryptedEventIndexer {

    private val enabled = AtomicBoolean(false)
    private val includeUnencrypted = AtomicBoolean(true)
    private val crawlMutex = Mutex()
    private var sessionStarted = false
    private var scope: CoroutineScope? = null
    private var crawlJob: Job? = null
    private val isForeground = MutableStateFlow(false)

    private val backgroundListener = object : BackgroundDetectionObserver.Listener {
        override fun onMoveToForeground() {
            isForeground.value = true
        }

        override fun onMoveToBackground() {
            isForeground.value = false
        }
    }

    fun isEnabled() = enabled.get()

    fun setEnabled(value: Boolean) {
        if (enabled.getAndSet(value) == value) return
        if (sessionStarted) {
            if (value) start() else stop()
        }
    }

    /** Whether unencrypted rooms are indexed too (searched locally) or left to the server. */
    fun includesUnencryptedRooms() = includeUnencrypted.get()

    fun setUnencryptedRoomsEnabled(value: Boolean) {
        if (includeUnencrypted.getAndSet(value) == value) return
        // Restart so bootstrap picks up (or stops crawling) the unencrypted rooms.
        if (sessionStarted && enabled.get()) {
            stop()
            start()
        }
    }

    override fun onSessionStarted(session: Session) {
        sessionStarted = true
        if (enabled.get()) start()
    }

    override fun onSessionStopped(session: Session) {
        sessionStarted = false
        stop()
    }

    override fun onClearCache(session: Session) {
        // The event table is dropped and its row ids restart, so the sweep watermark is stale.
        runBlocking { indexStore.setSweepWatermark(0L) }
    }

    private fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + coroutineDispatchers.io)
        scope = newScope
        backgroundDetectionObserver.register(backgroundListener)
        isForeground.value = !backgroundDetectionObserver.isInBackground
        crawlJob = newScope.launch {
            try {
                rebuildIfFormatChanged()
                indexStore.deleteLocalEchoes()
                sweepEventTable()
                crawl()
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Throwable) {
                Timber.e(failure, "EventIndexer failed")
            }
        }
    }

    // What a row records grew over time (gallery item types, polls): a row written by an older
    // version simply can't answer the newer has: filters, so start the index over when it changes.
    private suspend fun rebuildIfFormatChanged() {
        if (indexStore.getFormatVersion() == INDEX_FORMAT_VERSION) return
        Timber.i("EventIndexer: index format changed, rebuilding")
        indexStore.clear()
        indexStore.setFormatVersion(INDEX_FORMAT_VERSION)
    }

    private fun stop() {
        backgroundDetectionObserver.unregister(backgroundListener)
        crawlJob = null
        scope?.cancel()
        scope = null
    }

    /**
     * Called by the decryptors right after they persist successful decryption results.
     */
    override fun onEventsDecrypted(events: List<Pair<Event, MXEventDecryptionResult>>) {
        val currentScope = scope ?: return
        if (!enabled.get()) return
        val indexables = events.mapNotNull { (event, result) ->
            val clearType = result.clearEvent["type"] as? String ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val clearContent = result.clearEvent["content"] as? Content
            toIndexable(event, clearType, clearContent)
        }
        if (indexables.isEmpty()) return
        currentScope.launch {
            indexStore.addEvents(indexables)
        }
    }

    /**
     * Called when a redaction is applied. The content normally leaves the index with the event, but
     * a preserved copy is kept searchable — flagged as redacted, so a hit renders like the timeline
     * does: a deleted placeholder until the user reveals it.
     */
    fun onEventRedacted(redactedEventId: String) {
        scope?.launch {
            val preserved = redactedContentStore.get(redactedEventId)
            if (preserved == null) {
                indexStore.deleteEvent(redactedEventId)
            } else {
                val json = indexStore.eventJson(redactedEventId)
                if (json == null) {
                    indexPreservedContent(preserved, knownRedacted = true)
                    return@launch
                }
                val event = tryOrNull { eventAdapter.fromJson(json) } ?: return@launch
                indexStore.updateEventJson(redactedEventId, eventAdapter.toJson(event.markRedacted()))
            }
        }
    }

    /**
     * Puts a preserved copy of an already redacted event back into the index — fetched after the fact
     * (MSC2815), or captured before a redaction that raced the capture write and dropped the row.
     * Idempotent, and a no-op while the event is still live: the ordinary feeds own those.
     */
    suspend fun indexPreservedContent(preserved: PreservedContent, knownRedacted: Boolean = false) {
        if (!enabled.get()) return
        // Cheap index read first: an event still indexed under its own row needs nothing from here, so
        // only a missing row is worth the session-DB lookup that follows. The redaction path skips even
        // that, since it runs before the prune writes the row it would read.
        if (indexStore.eventJson(preserved.eventId) != null) return
        if (!knownRedacted && !isRedactedLocally(preserved.roomId, preserved.eventId)) return
        val event = Event(
                type = preserved.clearType?.takeIf { it.isNotEmpty() } ?: EventType.MESSAGE,
                eventId = preserved.eventId,
                content = ContentMapper.map(preserved.content),
                originServerTs = preserved.originServerTs,
                senderId = preserved.sender,
                roomId = preserved.roomId,
        )
        val indexable = toIndexable(event) ?: return
        indexStore.putEvent(indexable.copy(eventJson = eventAdapter.toJson(event.markRedacted())))
    }

    private suspend fun isRedactedLocally(roomId: String, eventId: String): Boolean =
            database.awaitDbTransaction(dbDispatcher) {
                stores.timelineEvent.getByRoomAndEventId(roomId, eventId)?.root
                        ?.let { EventMapper.map(it).isRedacted() } == true
            }

    /**
     * Drops the index rows of preserved events whose copy has just been deleted. Without this the
     * pre-redaction text stays searchable — and on disk — after the user clears preserved content.
     */
    suspend fun dropIndexedRedactions(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        eventIds.forEach { eventId ->
            val json = indexStore.eventJson(eventId) ?: return@forEach
            val event = tryOrNull { eventAdapter.fromJson(json) } ?: return@forEach
            // Only the rows kept *because* content was preserved; an unredacted event's row is its own.
            if (event.isRedacted()) indexStore.deleteEvent(eventId)
        }
    }

    // The redaction event's own id isn't reachable from every caller, and nothing downstream reads
    // it: isRedacted() only tests the field for null.
    private fun Event.markRedacted() = copy(
            unsignedData = (unsignedData ?: UnsignedData(null, null)).copy(redactedBy = eventId)
    )

    // EventInsertLiveProcessor: live-indexes events that arrive already clear (unencrypted rooms,
    // plus plaintext events in encrypted ones). Encrypted events insert as m.room.encrypted and
    // are picked up by the decryptor hooks instead.
    override fun shouldProcess(eventId: String, eventType: String, insertType: EventInsertType): Boolean {
        return enabled.get() && eventType in INDEXABLE_TYPES
    }

    override fun process(stores: SessionStores, event: Event) {
        val currentScope = scope ?: return
        val roomId = event.roomId ?: return
        if (!includeUnencrypted.get() && !stores.roomSummary.isEncrypted(roomId)) return
        // This runs inside the sync insert transaction; do the (de)serialization work elsewhere.
        currentScope.launch {
            val indexable = toIndexable(event) ?: return@launch
            indexStore.addEvents(listOf(indexable))
        }
    }

    /**
     * Build the index entry for an event whose clear type/content are known.
     * Returns null for events we don't index (matches element-web's isValidEvent()).
     */
    private fun toIndexable(event: Event, clearType: String, clearContent: Content?): IndexableEvent? {
        val eventId = event.eventId
        val roomId = event.roomId
        if (eventId.isNullOrEmpty() || roomId.isNullOrEmpty()) return null
        // Local echoes carry a fake event id; the remote echo is indexed instead (the sweep would
        // otherwise index sent messages twice — once per id).
        if (LocalEcho.isLocalEchoId(eventId)) return null
        if (event.isRedacted()) return null
        val msgtypes = searchMsgTypes(clearType, clearContent)
        val text = when {
            clearType == EventType.MESSAGE -> {
                if (msgtypes.isEmpty() || msgtypes.any { it.startsWith("m.key.verification") }) return null
                clearContent?.get("body") as? String
            }
            clearType == EventType.STICKER -> clearContent?.get("body") as? String
            clearType in EventType.POLL_START.values -> pollText(clearContent)
            clearType == EventType.STATE_ROOM_NAME -> clearContent?.get("name") as? String
            clearType == EventType.STATE_ROOM_TOPIC -> clearContent?.get("topic") as? String
            else -> return null
        }
        // Media events stay findable through has: even without a text body (usually the filename).
        if (text.isNullOrBlank() && msgtypes.none { it in MEDIA_MSGTYPES }) return null
        val mentions = if (clearType == EventType.MESSAGE) extractMentionedUserIds(clearContent) else emptyList()
        val clearEvent = Event(
                type = clearType,
                eventId = eventId,
                content = clearContent,
                originServerTs = event.originServerTs,
                senderId = event.senderId,
                stateKey = event.stateKey,
                roomId = roomId,
                unsignedData = event.unsignedData,
        )
        return IndexableEvent(
                eventId = eventId,
                roomId = roomId,
                sender = event.senderId,
                originServerTs = event.originServerTs ?: 0L,
                // Rich-reply fallback is stripped from display, so it must not be searchable text.
                contentText = ContentUtils.extractUsefulTextFromReply(text.orEmpty()).lowercase(),
                eventJson = eventAdapter.toJson(clearEvent),
                msgtype = msgtypes.joinToString(" ").takeIf { it.isNotEmpty() },
                mentions = mentions.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.lowercase() },
        )
    }

    // A poll has no body: its question and answers are what the user would search for.
    private fun pollText(clearContent: Content?): String? {
        val poll = clearContent.toModel<MessagePollContent>()?.getBestPollCreationInfo() ?: return null
        val answers = poll.answers.orEmpty().mapNotNull { it.getBestAnswer() }
        return (listOfNotNull(poll.question?.getBestQuestion()) + answers).joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun toIndexable(event: Event): IndexableEvent? =
            toIndexable(event, event.getClearType(), event.getClearContent())

    /** The rooms this index covers: all joined rooms, or only the encrypted ones. */
    private suspend fun includedRoomIds(): HashSet<String> = database.awaitDbTransaction(dbDispatcher) {
        if (includeUnencrypted.get()) {
            stores.roomSummary.getRoomIdsByMembership(Membership.JOIN)
        } else {
            stores.roomSummary.getEncryptedRoomIds(Membership.JOIN)
        }
    }.toHashSet()

    /**
     * Incrementally index rows of the session `event` table that appeared since the last sweep.
     * Only rows already carrying clear content are picked up; rows decrypted later flow through
     * [onEventsDecrypted] instead.
     */
    private suspend fun sweepEventTable() {
        val includedRoomIds = includedRoomIds()
        var watermark = indexStore.getSweepWatermark()
        while (true) {
            val batch = database.awaitDbTransaction(dbDispatcher) {
                stores.event.getForIndexAfterId(watermark, SWEEP_BATCH_SIZE)
            }
            if (batch.isEmpty()) break
            val indexables = batch.mapNotNull { (_, event) ->
                if (event.roomId !in includedRoomIds) return@mapNotNull null
                toIndexable(event)
            }
            if (indexables.isNotEmpty()) {
                indexStore.addEvents(indexables)
            }
            watermark = batch.last().first
            indexStore.setSweepWatermark(watermark)
        }
    }

    /**
     * The crawler: consume the persisted checkpoints round-robin, fetching/decrypting/indexing
     * room history. Runs until no checkpoints remain; only fetches while the app is foregrounded.
     * The checkpoint store is the single source of truth, shared (under [crawlMutex]) with the
     * search-driven [backfillRoom].
     */
    private suspend fun crawl() {
        addBootstrapCheckpoints()
        var rotation = 0
        var consecutiveFailures = 0
        while (true) {
            isForeground.first { it }
            delay(CRAWL_DELAY_MS * (consecutiveFailures + 1))
            val outcome = crawlMutex.withLock {
                val checkpoints = indexStore.loadCheckpoints()
                if (checkpoints.isEmpty()) return@withLock null
                crawlCheckpoint(checkpoints[rotation++ % checkpoints.size])
            } ?: break
            consecutiveFailures = if (outcome == CrawlOutcome.RETRY) {
                (consecutiveFailures + 1).coerceAtMost(MAX_FAILURE_BACKOFF_STEPS)
            } else {
                0
            }
        }
        Timber.i("EventIndexer: crawl complete")
    }

    /**
     * Seed checkpoints: full history crawl for encrypted rooms never indexed, plus a shallow
     * backward crawl for rooms whose live timeline token moved since the last crawl (a sync gap) —
     * the dedup stop condition ends the latter as soon as known events are reached.
     */
    private suspend fun addBootstrapCheckpoints() {
        for (roomId in includedRoomIds()) {
            bootstrapRoomCheckpoints(roomId)
        }
    }

    /**
     * @return the checkpoints newly persisted for [roomId], if it needed (re-)crawling.
     *
     * A room needs a FULL backward crawl until one has completed (reached the start of its
     * history): merely having indexed events proves nothing, since the sweep seeds the index with
     * whatever was cached locally and the dedup stop condition would end a plain crawl right
     * there. Once fully crawled, only shallow gap-heal crawls are needed when the live token moves.
     */
    private suspend fun bootstrapRoomCheckpoints(roomId: String): List<IndexCheckpoint> {
        val liveToken: String? = database.awaitDbTransaction(dbDispatcher) {
            stores.chunk.lastForward(roomId)?.prev_token
        }
        if (liveToken == null) return emptyList()
        val existingBackwards = indexStore.loadCheckpoints().filter { it.roomId == roomId && it.backwards }
        val fullyCrawled = indexStore.isRoomFullyCrawled(roomId)
        // Already covered by a pending crawl of the right depth.
        if (if (fullyCrawled) existingBackwards.isNotEmpty() else existingBackwards.any { it.fullCrawl }) {
            return emptyList()
        }
        val checkpoints = when {
            !fullyCrawled -> listOfNotNull(
                    IndexCheckpoint(roomId, liveToken, backwards = true, fullCrawl = true),
                    IndexCheckpoint(roomId, liveToken, backwards = false, fullCrawl = false)
                            .takeIf { !indexStore.isRoomIndexed(roomId) },
            )
            indexStore.getCrawledToken(roomId) != liveToken -> listOf(
                    IndexCheckpoint(roomId, liveToken, backwards = true, fullCrawl = false),
            )
            else -> return emptyList()
        }
        indexStore.setCrawledToken(roomId, liveToken)
        checkpoints.forEach { indexStore.addCheckpoint(it) }
        return checkpoints
    }

    /** Whether older history of [roomId] can still be fetched and indexed. */
    suspend fun roomHasMoreHistory(roomId: String): Boolean {
        if (!enabled.get()) return false
        if (indexStore.loadCheckpoints().any { it.roomId == roomId && it.backwards }) return true
        if (indexStore.isRoomFullyCrawled(roomId)) return false
        return database.awaitDbTransaction<String?>(dbDispatcher) {
            stores.chunk.lastForward(roomId)?.prev_token
        } != null
    }

    /**
     * Search-driven backfill: synchronously crawl up to [maxBatches] batches of [roomId]'s older
     * history so a search can look beyond what background crawling has indexed so far.
     *
     * @return true while the room still has uncrawled history.
     */
    suspend fun backfillRoom(roomId: String, maxBatches: Int): Boolean {
        if (!enabled.get()) return false
        repeat(maxBatches) {
            val stepped = crawlMutex.withLock {
                if (indexStore.isRoomFullyCrawled(roomId)) return false
                val backwards = indexStore.loadCheckpoints().filter { it.roomId == roomId && it.backwards }
                val checkpoint = backwards.firstOrNull { it.fullCrawl }
                        ?: backwards.firstOrNull()
                        ?: bootstrapRoomCheckpoints(roomId).firstOrNull { it.backwards }
                        ?: return false
                crawlCheckpoint(checkpoint) != CrawlOutcome.RETRY
            }
            if (!stepped) return roomHasMoreHistory(roomId)
        }
        return roomHasMoreHistory(roomId)
    }

    private enum class CrawlOutcome { CONTINUE, DONE, RETRY }

    private suspend fun crawlCheckpoint(checkpoint: IndexCheckpoint): CrawlOutcome {
        val response = try {
            executeRequest(globalErrorReceiver) {
                roomAPI.getRoomMessagesFrom(
                        roomId = checkpoint.roomId,
                        from = checkpoint.token,
                        dir = if (checkpoint.backwards) "b" else "f",
                        limit = EVENTS_PER_CRAWL,
                        filter = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Throwable) {
            return if (failure is Failure.ServerError && failure.httpCode == 403) {
                // No permission to read that history; drop the checkpoint.
                indexStore.removeCheckpoint(checkpoint)
                CrawlOutcome.DONE
            } else {
                Timber.w(failure, "EventIndexer: error crawling ${checkpoint.roomId}")
                CrawlOutcome.RETRY
            }
        }

        val events = response.chunk.orEmpty()
        if (events.isEmpty()) {
            // Reached the start/end of the room history.
            indexStore.removeCheckpoint(checkpoint)
            if (checkpoint.backwards) indexStore.markRoomFullyCrawled(checkpoint.roomId)
            return CrawlOutcome.DONE
        }

        val indexables = ArrayList<IndexableEvent>(events.size)
        for (event in events) {
            if (event.getClearType() == EventType.ENCRYPTED) {
                val result = try {
                    cryptoService.get().decryptEvent(event, "")
                } catch (failure: Throwable) {
                    // Missing keys: skip, like element-web's crawler.
                    continue
                }
                val clearType = result.clearEvent["type"] as? String ?: continue
                @Suppress("UNCHECKED_CAST")
                val clearContent = result.clearEvent["content"] as? Content
                toIndexable(event, clearType, clearContent)?.let { indexables.add(it) }
            } else {
                toIndexable(event)?.let { indexables.add(it) }
            }
        }
        val added = if (indexables.isEmpty()) 0 else indexStore.addEvents(indexables)

        indexStore.removeCheckpoint(checkpoint)
        val newToken = response.end ?: return CrawlOutcome.DONE
        // If every decryptable event of the batch was already indexed we caught up with a previous
        // crawl — stop unless this is a full-history crawl.
        if (!checkpoint.fullCrawl && indexables.isNotEmpty() && added == 0) {
            return CrawlOutcome.DONE
        }
        indexStore.addCheckpoint(checkpoint.copy(token = newToken))
        return CrawlOutcome.CONTINUE
    }

    companion object {
        private val INDEXABLE_TYPES = setOf(
                EventType.MESSAGE,
                EventType.STICKER,
                EventType.STATE_ROOM_NAME,
                EventType.STATE_ROOM_TOPIC,
        )

        private val MEDIA_MSGTYPES = setOf(
                MessageType.MSGTYPE_IMAGE,
                MessageType.MSGTYPE_VIDEO,
                MessageType.MSGTYPE_AUDIO,
                MessageType.MSGTYPE_FILE,
                EventType.STICKER,
        )

        // Bump whenever toIndexable stores something new that a filter relies on.
        private const val INDEX_FORMAT_VERSION = 2

        private const val EVENTS_PER_CRAWL = 100
        private const val CRAWL_DELAY_MS = 3000L
        private const val MAX_FAILURE_BACKOFF_STEPS = 20
        private const val SWEEP_BATCH_SIZE = 500

        private val eventAdapter = MoshiProvider.providesMoshi().adapter(Event::class.java)
    }
}
