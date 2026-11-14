/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.bulk

import android.content.Context
import androidx.core.util.AtomicFile
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.popup.DefaultVectorAlert
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.redaction.MassRedactionManager
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.getRetryDelay
import org.matrix.android.sdk.api.failure.isLimitExceededError
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.relation.MassRedactionRange
import org.matrix.android.sdk.api.util.MatrixJsonParser
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The one app-wide queue for long runs of sends and redactions: forwarding or redacting many messages,
 * accepting or rejecting many invites, and [MassRedactionManager]'s jobs. Jobs run one at a time in the order
 * they were asked for, so none competes with another for the server's rate limit, and each keeps running when
 * its room is closed. A forward is sent straight to the server one message at a time, so nothing shows in a
 * room until it has been sent.
 * The queue is saved per account, and comes back paused after the app is killed.
 */
@Singleton
class BulkActionQueue @Inject constructor(
        @ApplicationContext private val context: Context,
        private val activeSessionHolder: ActiveSessionHolder,
        activeSessionDataSource: ActiveSessionDataSource,
        private val massRedactionManager: MassRedactionManager,
        private val popupAlertManager: PopupAlertManager,
        private val stringProvider: StringProvider,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableListOf<BulkJob>()
    private val progressById = HashMap<String, BulkJobProgress>()
    private val head = MutableStateFlow<BulkActionProgress?>(null)
    private val jobCount = MutableStateFlow(0)
    private var runner: Job? = null

    // The account whose queue is loaded; switching accounts parks one queue and loads the other's.
    @Volatile private var owner: String? = null

    private val jobsAdapter: JsonAdapter<List<BulkJob>> =
            MatrixJsonParser.getMoshi().adapter(Types.newParameterizedType(List::class.java, BulkJob::class.java))
    private val progressAdapter: JsonAdapter<Map<String, BulkJobProgress>> =
            MatrixJsonParser.getMoshi().adapter(Types.newParameterizedType(Map::class.java, String::class.java, BulkJobProgress::class.java))

    /** What the banner shows: the running job, mass redaction included, and how many wait behind it. */
    val progress: Flow<BulkActionProgress?> = combine(
            massRedactionManager.stream().onStart { emit(massRedactionManager.currentValue()) },
            head,
            jobCount,
    ) { mass, own, count ->
        when {
            mass != null -> BulkActionProgress(
                    label = stringProvider.getString(CommonStrings.mass_redaction_redacting_from, mass.targetDisplayName.neutralizeDirectionOverrides()),
                    completed = mass.completed,
                    total = mass.total,
                    paused = mass.paused,
                    queued = count,
            )
            own != null -> own.copy(queued = (count - 1).coerceAtLeast(0))
            else -> null
        }
    }.distinctUntilChanged()

    init {
        activeSessionDataSource.stream().onEach { session ->
            onActiveSessionChanged(session.orNull()?.myUserId)
        }.launchIn(scope)
    }

    @Synchronized
    private fun onActiveSessionChanged(current: String?) {
        if (current == owner) return
        runner?.cancel()
        runner = null
        owner = current
        jobs.clear()
        progressById.clear()
        if (current != null) {
            jobs += readJobs(current)
            progressById += readProgress(current)
            // As mass redaction does: a queue that was interrupted waits for the user to resume it.
            jobs.firstOrNull()?.takeUnless { it.paused }?.let { jobs[0] = it.copy(paused = true); saveJobs() }
        }
        publish()
    }

    fun enqueueRedact(roomId: String, eventIds: List<String>, reason: String?) {
        if (eventIds.isEmpty()) return
        enqueue(BulkJob(id = newJobId(), kind = BulkJobKind.REDACT, roomIds = listOf(roomId), eventIds = eventIds, reason = reason))
    }

    /**
     * @param forwards event type, content and the index of an earlier entry it replies to, in timeline
     * order; each is sent to every room of [roomIds]
     */
    fun enqueueForward(roomIds: List<String>, forwards: List<Triple<String, Content, Int?>>) {
        if (roomIds.isEmpty() || forwards.isEmpty()) return
        val entries = forwards.map { (eventType, content, inReplyToIndex) ->
            BulkForward(eventType, content, inReplyToIndex, txnIds = roomIds.map { LocalEcho.createLocalEchoId() })
        }
        enqueue(BulkJob(id = newJobId(), kind = BulkJobKind.FORWARD, roomIds = roomIds, forwards = entries))
    }

    fun enqueueInvites(roomIds: List<String>, accept: Boolean) {
        if (roomIds.isEmpty()) return
        val kind = if (accept) BulkJobKind.ACCEPT_INVITES else BulkJobKind.REJECT_INVITES
        enqueue(BulkJob(id = newJobId(), kind = kind, roomIds = roomIds))
    }

    /** Starts a mass redaction now, or queues it behind the jobs already waiting. */
    @Synchronized
    fun startMassRedaction(
            roomId: String,
            userId: String,
            displayName: String,
            delayMs: Long,
            range: MassRedactionRange = MassRedactionRange.ALL,
    ): MassRedactionManager.StartResult {
        // Mass redaction keeps a single resume record, so only one can be running or waiting at a time.
        if (massRedactionManager.currentValue() != null || jobs.any { it.kind == BulkJobKind.MASS_REDACTION }) {
            return MassRedactionManager.StartResult.AlreadyRunning
        }
        if (jobs.isEmpty()) return massRedactionManager.start(roomId, userId, displayName, delayMs, range)
        val params = BulkMassRedaction(userId, displayName, delayMs, range.fromTs, range.toTs, range.messagesOnly)
        enqueue(BulkJob(id = newJobId(), kind = BulkJobKind.MASS_REDACTION, roomIds = listOf(roomId), massRedaction = params))
        return MassRedactionManager.StartResult.Started
    }

    /** Which kind of job the banner's pause and cancel apply to. */
    fun activeKind(): BulkJobKind? = when {
        massRedactionManager.currentValue() != null -> BulkJobKind.MASS_REDACTION
        else -> synchronized(this) { jobs.firstOrNull()?.kind }
    }

    @Synchronized
    fun togglePause() {
        if (massRedactionManager.currentValue() != null) {
            massRedactionManager.togglePause()
            return
        }
        val job = jobs.firstOrNull() ?: return
        if (job.paused) {
            jobs[0] = job.copy(paused = false)
            saveJobs()
            publish()
            kick()
        } else {
            runner?.cancel()
            runner = null
            jobs[0] = job.copy(paused = true)
            saveJobs()
            publish()
        }
    }

    /** Drops the running job; what it already did stays done, and the next job starts. */
    @Synchronized
    fun cancel() {
        if (massRedactionManager.currentValue() != null) {
            massRedactionManager.cancel()
            return
        }
        val job = jobs.firstOrNull() ?: return
        runner?.cancel()
        runner = null
        removeJob(job.id)
        kick()
    }

    @Synchronized
    private fun enqueue(job: BulkJob) {
        if (owner == null) return
        jobs += job
        saveJobs()
        publish()
        kick()
    }

    @Synchronized
    private fun kick() {
        if (runner?.isActive == true) return
        val runOwner = owner ?: return
        if (jobs.firstOrNull()?.paused != false) return
        runner = scope.launch { runQueue(runOwner) }
    }

    private suspend fun runQueue(runOwner: String) {
        while (true) {
            coroutineContext.ensureActive()
            // Nothing else runs while a mass redaction is running or paused.
            while (massRedactionManager.currentValue() != null) massRedactionManager.stream().first { it == null }
            val job = synchronized(this) { jobs.firstOrNull()?.takeIf { owner == runOwner } } ?: return
            if (job.paused) return
            val session = activeSessionHolder.getSafeActiveSession()?.takeIf { it.myUserId == runOwner } ?: return
            when (job.kind) {
                BulkJobKind.MASS_REDACTION -> {
                    synchronized(this) { removeJob(job.id) }
                    val params = job.massRedaction ?: continue
                    massRedactionManager.start(
                            job.roomIds.first(), params.userId, params.displayName, params.delayMs,
                            MassRedactionRange(params.fromTs, params.toTs, params.messagesOnly),
                    )
                }
                BulkJobKind.FORWARD -> if (!runForward(session, job)) return
                BulkJobKind.REDACT -> if (!runRedact(session, job)) return
                BulkJobKind.ACCEPT_INVITES, BulkJobKind.REJECT_INVITES -> if (!runInvites(session, job)) return
            }
        }
    }

    /** @return false when the job stopped short and paused for the user to retry. */
    private suspend fun runForward(session: Session, job: BulkJob): Boolean {
        val perRoom = job.forwards.size
        val total = job.roomIds.size * perRoom
        var progress = synchronized(this) { progressById[job.id] } ?: BulkJobProgress()
        while (progress.next < total) {
            coroutineContext.ensureActive()
            publishRunning(job, progress, total)
            val roomIndex = progress.next / perRoom
            val index = progress.next % perRoom
            val forward = job.forwards[index]
            val replyTo = forward.inReplyToIndex?.let { progress.sentEventIds[unitKey(roomIndex, it)] }
            @Suppress("UNCHECKED_CAST")
            val base = forward.content.filterValues { it != null } as Content
            val content = replyTo?.let { base + ("m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to it))) } ?: base
            val room = session.getRoom(job.roomIds[roomIndex])
            val sent = room?.let { withRetries { it.sendService().sendEventNow(forward.eventType, content, forward.txnIds[roomIndex]) } }
            // Sending the rest would put them out of order around the gap, so the job waits for a retry.
            if (sent == null) {
                pauseOnFailure(
                        job,
                        stringProvider.getString(CommonStrings.bulk_forward_failed_title),
                        stringProvider.getString(CommonStrings.bulk_forward_failed),
                )
                return false
            }
            progress = progress.copy(next = progress.next + 1, sentEventIds = progress.sentEventIds + (unitKey(roomIndex, index) to sent))
            saveProgress(job.id, progress)
            delay(COOLDOWN_MS)
        }
        synchronized(this) { removeJob(job.id) }
        return true
    }

    private suspend fun runRedact(session: Session, job: BulkJob): Boolean {
        val room = session.getRoom(job.roomIds.first())
        var progress = synchronized(this) { progressById[job.id] } ?: BulkJobProgress()
        while (progress.next < job.eventIds.size) {
            coroutineContext.ensureActive()
            publishRunning(job, progress, job.eventIds.size)
            val eventId = job.eventIds[progress.next]
            val redacted = room != null && redactWithRelations(session, room, eventId, job.reason)
            progress = progress.copy(
                    next = progress.next + 1,
                    failedEventIds = if (redacted) progress.failedEventIds else progress.failedEventIds + eventId,
            )
            saveProgress(job.id, progress)
            delay(COOLDOWN_MS)
        }
        val failed = progress.failedEventIds
        if (failed.isNotEmpty()) {
            // Resuming retries only the ones that failed.
            synchronized(this) {
                jobs.indexOfFirst { it.id == job.id }.takeIf { it >= 0 }?.let { jobs[it] = job.copy(eventIds = failed) }
                progressById[job.id] = BulkJobProgress()
                saveJobs()
                saveProgressFile()
            }
            pauseOnFailure(
                    job,
                    stringProvider.getString(CommonStrings.bulk_redact_failed_title),
                    stringProvider.getQuantityString(CommonPlurals.bulk_redact_failed, failed.size, failed.size),
            )
            return false
        }
        synchronized(this) { removeJob(job.id) }
        return true
    }

    private suspend fun runInvites(session: Session, job: BulkJob): Boolean {
        val accept = job.kind == BulkJobKind.ACCEPT_INVITES
        val rooms = session.roomService()
        var progress = synchronized(this) { progressById[job.id] } ?: BulkJobProgress()
        while (progress.next < job.roomIds.size) {
            coroutineContext.ensureActive()
            publishRunning(job, progress, job.roomIds.size)
            val roomId = job.roomIds[progress.next]
            // Answered meanwhile, here or on another device; a local answer shows in the summary only after the next sync.
            val change = rooms.getChangeMemberships(roomId)
            val pending = session.getRoomSummary(roomId)?.membership == Membership.INVITE && !change.isInProgress() && !change.isSuccessful()
            val done = !pending || withRetries { if (accept) rooms.joinRoom(roomId) else rooms.leaveRoom(roomId) } != null
            progress = progress.copy(
                    next = progress.next + 1,
                    failedRoomIds = if (done) progress.failedRoomIds else progress.failedRoomIds + roomId,
            )
            saveProgress(job.id, progress)
            delay(COOLDOWN_MS)
        }
        val failed = progress.failedRoomIds
        if (failed.isNotEmpty()) {
            synchronized(this) {
                jobs.indexOfFirst { it.id == job.id }.takeIf { it >= 0 }?.let { jobs[it] = job.copy(roomIds = failed) }
                progressById[job.id] = BulkJobProgress()
                saveJobs()
                saveProgressFile()
            }
            pauseOnFailure(
                    job,
                    stringProvider.getString(if (accept) CommonStrings.bulk_accept_invites_failed_title else CommonStrings.bulk_reject_invites_failed_title),
                    stringProvider.getQuantityString(
                            if (accept) CommonPlurals.bulk_accept_invites_failed else CommonPlurals.bulk_reject_invites_failed,
                            failed.size,
                            failed.size,
                    ),
            )
            return false
        }
        synchronized(this) { removeJob(job.id) }
        return true
    }

    /**
     * As a single redaction does: edits and reactions are separate events the server keeps after the message
     * itself is redacted. They are gathered first, since redacting the message drops them from the local copy.
     */
    private suspend fun redactWithRelations(session: Session, room: Room, eventId: String, reason: String?): Boolean {
        val relations = room.relationService()
        val event = room.getTimelineEvent(eventId)
        val related = LinkedHashSet<String>()
        event?.annotations?.editSummary?.sourceEvents?.let(related::addAll)
        tryOrNull { relations.fetchEditHistory(eventId) }?.mapNotNullTo(related) { it.eventId }
        val myUserId = session.myUserId
        val canRedactOthers = tryOrNull { room.stateService().getRoomPowerLevels().isUserAbleToRedact(myUserId) } ?: false
        val reactions = event?.annotations?.reactionsSummary?.flatMap { it.sourceEvents }.orEmpty().mapNotNull { room.getTimelineEvent(it)?.root } +
                tryOrNull { relations.fetchReactions(eventId) }.orEmpty()
        reactions.filter { it.senderId == myUserId || canRedactOthers }.mapNotNullTo(related) { it.eventId }
        related.remove(eventId)
        if (withRetries { relations.redactEventNoEcho(eventId, reason) } == null) return false
        related.forEach { withRetries { relations.redactEventNoEcho(it, reason) } }
        return true
    }

    /** Being told to slow down doesn't use up the attempts; it only waits as long as the server asks. */
    private suspend fun <T> withRetries(block: suspend () -> T): T? {
        var attempts = 0
        var rateLimited = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                when {
                    t.isLimitExceededError() && rateLimited++ < MAX_RATE_LIMITED -> delay(t.getRetryDelay(RETRY_DELAY_MS))
                    ++attempts < ATTEMPTS -> delay(RETRY_DELAY_MS)
                    else -> {
                        Timber.w(t, "bulk: giving up after $attempts attempts")
                        return null
                    }
                }
            }
        }
    }

    @Synchronized
    private fun pauseOnFailure(job: BulkJob, title: String, description: String) {
        val index = jobs.indexOfFirst { it.id == job.id }
        if (index < 0) return
        jobs[index] = jobs[index].copy(paused = true)
        saveJobs()
        publish()
        popupAlertManager.postVectorAlert(DefaultVectorAlert(uid = "bulk_action_failed", title = title, description = description, iconId = null))
    }

    @Synchronized
    private fun removeJob(id: String) {
        jobs.removeAll { it.id == id }
        progressById.remove(id)
        saveJobs()
        saveProgressFile()
        publish()
    }

    @Synchronized
    private fun saveProgress(id: String, progress: BulkJobProgress) {
        if (jobs.none { it.id == id }) return
        progressById[id] = progress
        saveProgressFile()
    }

    @Synchronized
    private fun publishRunning(job: BulkJob, progress: BulkJobProgress, total: Int) {
        if (jobs.firstOrNull()?.id != job.id) return
        head.value = job.toProgress(progress, total)
    }

    @Synchronized
    private fun publish() {
        jobCount.value = jobs.size
        val job = jobs.firstOrNull()?.takeIf { it.kind != BulkJobKind.MASS_REDACTION }
        head.value = job?.let {
            val total = when (it.kind) {
                BulkJobKind.FORWARD -> it.roomIds.size * it.forwards.size
                BulkJobKind.ACCEPT_INVITES, BulkJobKind.REJECT_INVITES -> it.roomIds.size
                else -> it.eventIds.size
            }
            it.toProgress(progressById[it.id] ?: BulkJobProgress(), total)
        }
    }

    private fun BulkJob.toProgress(progress: BulkJobProgress, total: Int): BulkActionProgress {
        val label = when (kind) {
            BulkJobKind.FORWARD -> stringProvider.getQuantityString(CommonPlurals.bulk_forwarding, forwards.size, forwards.size)
            BulkJobKind.ACCEPT_INVITES -> stringProvider.getQuantityString(CommonPlurals.bulk_accepting_invites, roomIds.size, roomIds.size)
            BulkJobKind.REJECT_INVITES -> stringProvider.getQuantityString(CommonPlurals.bulk_rejecting_invites, roomIds.size, roomIds.size)
            else -> stringProvider.getQuantityString(CommonPlurals.bulk_redacting, eventIds.size, eventIds.size)
        }
        return BulkActionProgress(label, completed = progress.next, total = total, paused = paused, queued = 0)
    }

    private fun unitKey(roomIndex: Int, forwardIndex: Int) = "$roomIndex:$forwardIndex"

    private fun newJobId() = UUID.randomUUID().toString()

    // region persistence
    private fun directory() = File(context.filesDir, DIRECTORY)

    private fun fileName(owner: String) = owner.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun jobsFile(owner: String) = AtomicFile(File(directory(), "${fileName(owner)}.json"))

    private fun progressFile(owner: String) = AtomicFile(File(directory(), "${fileName(owner)}.progress.json"))

    private fun readJobs(owner: String): List<BulkJob> =
            read(jobsFile(owner)) { jobsAdapter.fromJson(it) }.orEmpty()

    private fun readProgress(owner: String): Map<String, BulkJobProgress> =
            read(progressFile(owner)) { progressAdapter.fromJson(it) }.orEmpty()

    private fun saveJobs() {
        val current = owner ?: return
        write(jobsFile(current), jobsAdapter.toJson(jobs.toList()))
    }

    private fun saveProgressFile() {
        val current = owner ?: return
        write(progressFile(current), progressAdapter.toJson(progressById.filterKeys { id -> jobs.any { it.id == id } }))
    }

    private fun <T> read(file: AtomicFile, parse: (String) -> T?): T? = try {
        if (file.baseFile.exists()) parse(String(file.readFully(), Charsets.UTF_8)) else null
    } catch (t: Throwable) {
        Timber.w(t, "bulk: unreadable queue file ${file.baseFile.name}")
        null
    }

    private fun write(file: AtomicFile, json: String) {
        directory().mkdirs()
        val stream = try {
            file.startWrite()
        } catch (t: Throwable) {
            Timber.w(t, "bulk: cannot save queue")
            return
        }
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (t: Throwable) {
            file.failWrite(stream)
            Timber.w(t, "bulk: cannot save queue")
        }
    }
    // endregion

    companion object {
        private const val DIRECTORY = "bulk_queue"
        private const val ATTEMPTS = 3
        private const val MAX_RATE_LIMITED = 30
        private const val RETRY_DELAY_MS = 3_000L

        // Floor between sends so a long run can't saturate the session dispatcher and stall sync.
        private const val COOLDOWN_MS = 30L
    }
}
