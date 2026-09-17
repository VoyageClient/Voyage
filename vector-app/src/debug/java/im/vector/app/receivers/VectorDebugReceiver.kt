/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import im.vector.app.core.debug.DebugReceiver
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultPreferences
import im.vector.app.core.extensions.startSyncing
import im.vector.app.core.utils.lsFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receiver to handle some command from ADB
 */
@Singleton
class VectorDebugReceiver @Inject constructor(
        @DefaultPreferences
        private val sharedPreferences: SharedPreferences,
        private val activeSessionHolder: ActiveSessionHolder,
) : BroadcastReceiver(), DebugReceiver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val paginating = AtomicBoolean(false)
    private val watchingSync = AtomicBoolean(false)
    private val registered = AtomicBoolean(false)

    override fun register(context: Context) {
        MainThreadWatchdog.start()
        // Registered once for the process (see VectorApplication) so the adb actions work with nothing on
        // screen; every activity's onResume asks again, and a second registration would run each action twice.
        if (!registered.compareAndSet(false, true)) return
        // Exported, or adb-shell broadcasts never arrive on Android 14+ (debug builds only).
        ContextCompat.registerReceiver(
                context,
                this,
                getIntentFilter(context),
                ContextCompat.RECEIVER_EXPORTED,
        )
    }

    // Nothing: the registration lives for the process, so the adb actions keep working while the app is
    // in the background, which is the point of them.
    override fun unregister(context: Context) = Unit

    override fun onReceive(context: Context, intent: Intent) {
        Timber.v("Received debug action: ${intent.action}")

        intent.action?.let {
            when {
                it.endsWith(DEBUG_ACTION_DUMP_FILESYSTEM) -> lsFiles(context)
                it.endsWith(DEBUG_ACTION_DUMP_PREFERENCES) -> dumpPreferences()
                it.endsWith(DEBUG_ACTION_FETCH_PUSH_RULES) -> fetchPushRules()
                it.endsWith(DEBUG_ACTION_ALTER_SCALAR_TOKEN) -> alterScalarToken()
                it.endsWith(DEBUG_ACTION_PAGINATE_ROOM) -> paginateRoom(intent)
                it.endsWith(DEBUG_ACTION_DUMP_CHUNKS) -> dumpChunks(intent)
                it.endsWith(DEBUG_ACTION_CLEAR_CACHE) -> clearCache(context)
                it.endsWith(DEBUG_ACTION_SYNC_WATCH) -> watchSync(intent)
                it.endsWith(DEBUG_ACTION_PERF) -> togglePerf(intent)
                it.endsWith(DEBUG_ACTION_BG_SYNC) -> toggleBackgroundSync(intent)
                it.endsWith(DEBUG_ACTION_TIMELINE_CHECK) -> checkTimeline(intent)
                it.endsWith(DEBUG_ACTION_SEARCH_ROOM) -> searchRoom(intent)
                it.endsWith(DEBUG_ACTION_SEND_TEXT) -> sendText(intent)
                it.endsWith(DEBUG_ACTION_FREEZE_MAIN) -> freezeMain(intent)
            }
        }
    }

    /**
     * Clears the cache the way Settings > General does, so a cold reload can be reproduced without
     * touching the screen.
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_CLEAR_CACHE
     */
    private fun clearCache(context: Context) {
        // Modern Android restricts activity launches from background receivers; clear and resync the session directly.
        scope.launch {
            val session = activeSessionHolder.getSafeActiveSession()
                    ?: return@launch Timber.w("SYNCDBG clear cache: no active session")
            Timber.i("SYNCDBG clear cache: wiping session store")
            val started = System.currentTimeMillis()
            session.clearCache()
            session.startSyncing(context.applicationContext)
            // Clearing restarts the sync service, which comes back foreground-only; re-assert the debug
            // flag or a measurement started from adb stalls the moment the app is not on screen.
            if (org.matrix.android.sdk.api.debug.SyncDebugFlags.keepSyncingInBackground) {
                session.syncService().startSync(true)
            }
            Timber.i("SYNCDBG clear cache: done in ${System.currentTimeMillis() - started}ms, resync started")
            // Restart to replace paging sources still bound to the previous database.
            Timber.w("SYNCDBG clear cache: room list will stay empty until the app is restarted")
        }
    }

    /**
     * Reports how the room list fills after a cold start: how many rooms are known, once a second, with
     * the rate they are arriving at, so "still missing entries" is a number rather than an impression.
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_SYNC_WATCH [--ei seconds 180]
     */
    private fun watchSync(intent: Intent) {
        val seconds = intent.getIntExtra("seconds", 180)
        if (!watchingSync.compareAndSet(false, true)) {
            Timber.w("SYNCDBG watch already running")
            return
        }
        scope.launch {
            try {
                val session = activeSessionHolder.getSafeActiveSession()
                        ?: return@launch Timber.w("SYNCDBG watch: no active session")
                val started = System.currentTimeMillis()
                var previous = -1
                var lastChangeAt = started
                repeat(seconds) {
                    val summaries = session.roomService().getRoomSummaries(roomSummaryQueryParams { })
                    val rooms = summaries.size
                    val dms = summaries.count { it.isDirect }
                    val dmsWithAvatar = summaries.count { it.isDirect && !it.avatarUrl.isNullOrEmpty() }
                    val elapsed = (System.currentTimeMillis() - started) / 1000
                    if (rooms != previous) {
                        val rate = if (elapsed > 0) rooms.toFloat() / elapsed else 0f
                        Timber.i(
                                "SYNCDBG watch t=${elapsed}s rooms=$rooms (+${rooms - previous.coerceAtLeast(0)}) " +
                                        "dms=$dms withAvatar=$dmsWithAvatar rate=%.1f/s".format(rate)
                        )
                        previous = rooms
                        lastChangeAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastChangeAt > STALL_REPORT_MS) {
                        Timber.w("SYNCDBG watch t=${elapsed}s rooms=$rooms STALLED for ${(System.currentTimeMillis() - lastChangeAt) / 1000}s")
                        lastChangeAt = System.currentTimeMillis()
                    }
                    delay(1000)
                }
                Timber.i("SYNCDBG watch done after ${seconds}s: rooms=$previous")
            } finally {
                watchingSync.set(false)
            }
        }
    }

    /**
     * Keeps syncing while the app is backgrounded, so a cold load can be timed without holding the app on
     * screen. Debug builds only, and off again on the next process start.
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_BG_SYNC --ez on true
     */
    private fun toggleBackgroundSync(intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        org.matrix.android.sdk.api.debug.SyncDebugFlags.keepSyncingInBackground = on
        if (on) activeSessionHolder.getSafeActiveSession()?.syncService()?.startSync(true)
        Timber.i("SYNCDBG background sync ${if (on) "on" else "off"}")
    }

    /**
     * Perf measurement. `aggregate` sums every measurement instead of logging the ones over `threshold`,
     * which is the only way to see a cost spread over thousands of cheap calls; `reset` clears the sums and
     * `dump` prints them slowest-first.
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_PERF --ez on true [--ei threshold 5] [--ez aggregate true] [--ez reset true] [--ez dump true]
     */
    private fun togglePerf(intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        org.matrix.android.sdk.api.util.MatrixPerf.isEnabled = on
        // The app side of the same switch: timeline scroll cost is mostly model build and bind.
        im.vector.app.core.utils.PerfTrace.isEnabled = on
        org.matrix.android.sdk.api.util.MatrixPerf.logThresholdMs = intent.getIntExtra("threshold", 5).toLong()
        val aggregate = intent.getBooleanExtra("aggregate", false)
        org.matrix.android.sdk.api.util.MatrixPerf.isAggregating = aggregate
        if (intent.getBooleanExtra("reset", false)) org.matrix.android.sdk.api.util.MatrixPerf.resetTotals()
        if (intent.getBooleanExtra("dump", false)) org.matrix.android.sdk.api.util.MatrixPerf.dumpTotals()
        Timber.i("SYNCDBG perf logging ${if (on) "on" else "off"} aggregate=$aggregate")
    }

    /**
     * Judges the room's stored timeline against every invariant, PASS/FAIL per rule, so a room can be
     * checked without a person scrolling it.
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_TIMELINE_CHECK --es room_id '!roomId'
     */
    private fun checkTimeline(intent: Intent) = withRoom(intent, "check") { roomId, room ->
        Timber.i("TLDBG check $roomId ${room.timelineService().debugCheckTimeline()}")
    }

    private fun withRoom(intent: Intent, what: String, block: suspend (String, org.matrix.android.sdk.api.session.room.Room) -> Unit) {
        val roomId = intent.getStringExtra("room_id") ?: return Unit.also { Timber.w("TLDBG $what: missing room_id extra") }
        scope.launch {
            val room = activeSessionHolder.getSafeActiveSession()?.getRoom(roomId)
                    ?: return@launch Timber.w("TLDBG $what: no room $roomId")
            block(roomId, room)
        }
    }

    /**
     * Prints stored range boundaries and pagination tokens under PAGDBG:
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_DUMP_CHUNKS --es room_id '!roomId'
     */
    private fun dumpChunks(intent: Intent) {
        val roomId = intent.getStringExtra("room_id") ?: return Unit.also { Timber.w("PAGDBG chunks: missing room_id extra") }
        scope.launch {
            val room = activeSessionHolder.getSafeActiveSession()?.getRoom(roomId)
                    ?: return@launch Timber.w("PAGDBG chunks: no room $roomId")
            Timber.i("PAGDBG chunks $roomId\n${room.timelineService().debugDumpChunks()}")
        }
    }

    /**
     * Headless backward-pagination driver, so history fetching can be exercised from adb without
     * scrolling a timeline on screen:
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_PAGINATE_ROOM --es room_id '!roomId' [--ei pages 10] [--ei limit 50]
     * Progress and results go to logcat under the PAGDBG tag.
     */
    private fun paginateRoom(intent: Intent) {
        val roomId = intent.getStringExtra("room_id") ?: return Unit.also { Timber.w("PAGDBG headless: missing room_id extra") }
        val pages = intent.getIntExtra("pages", 10)
        val limit = intent.getIntExtra("limit", 50)
        // Optional: seed the timeline at an event (skips re-walking everything above it) and/or
        // keep going until history reaches a target origin_server_ts (pages then acts as a cap).
        val fromEvent = intent.getStringExtra("from_event")
        val untilTs = intent.getLongExtra("until_ts", 0L)
        // Scrolling down out of a jumped-to event is a forward walk, and it crosses chunk links the
        // backward walk never touches — so it needs driving in its own right.
        val direction = if (intent.getStringExtra("direction") == "forward") Timeline.Direction.FORWARDS else Timeline.Direction.BACKWARDS
        // Healing a gap runs in the timeline's own scope, so disposing right after the last page cancels
        // it: hold the timeline open and report again, to see what the heal actually changed.
        val settleSeconds = intent.getIntExtra("settle", 0)
        if (!paginating.compareAndSet(false, true)) {
            Timber.w("PAGDBG headless: a paginate run is already in progress")
            return
        }
        scope.launch {
            try {
                val session = activeSessionHolder.getSafeActiveSession()
                        ?: return@launch Unit.also { Timber.w("PAGDBG headless: no active session") }
                val room = session.getRoom(roomId)
                        ?: return@launch Unit.also { Timber.w("PAGDBG headless: unknown room $roomId") }
                val timeline = room.timelineService().createTimeline(fromEvent, TimelineSettings(initialSize = limit, buildReadReceipts = false))
                timeline.start()
                // At the live edge the snapshot window is capped, hiding backward growth.
                timeline.setViewAtLiveEdge(false)
                try {
                    // start() seeds the timeline asynchronously; wait for the first snapshot.
                    var waitedMs = 0
                    while (timeline.getSnapshot().isEmpty() && waitedMs < 15_000) {
                        delay(250)
                        waitedMs += 250
                    }
                    if (timeline.getSnapshot().isEmpty()) {
                        Timber.w("PAGDBG headless: timeline never seeded for $roomId")
                        return@launch
                    }
                    for (page in 1..pages) {
                        if (!timeline.hasMoreToLoad(direction)) {
                            Timber.i("PAGDBG headless: reached the $direction end after ${page - 1} pages")
                            break
                        }
                        // A load already in flight (the timeline's own initial one) makes awaitPaginate a
                        // no-op, so without waiting the run finishes and disposing cancels that fetch.
                        var waited = 0
                        while (timeline.getPaginationState(direction).loading && waited < PAGE_WAIT_MAX_MS) {
                            delay(PAGE_WAIT_STEP_MS)
                            waited += PAGE_WAIT_STEP_MS.toInt()
                        }
                        val snapshot = timeline.awaitPaginate(direction, limit)
                        // The snapshot is newest-first, so the walk's own frontier is at the far end
                        // going back and at the head going forward.
                        val frontier = if (direction == Timeline.Direction.BACKWARDS) snapshot.lastOrNull() else snapshot.firstOrNull()
                        // The timeline's own view of the walk, so a page that fetched nothing can be told
                        // apart from a page that had nothing left to fetch.
                        val state = timeline.getPaginationState(direction)
                        Timber.i(
                                "PAGDBG headless page $page/$pages $direction: ${snapshot.size} loaded, " +
                                        "frontier=${frontier?.eventId} ts=${frontier?.root?.originServerTs} " +
                                        "hasMore=${state.hasMoreToLoad} paginating=${state.loading}"
                        )
                        val frontierTs = frontier?.root?.originServerTs
                        if (untilTs > 0 && frontierTs != null &&
                                (direction == Timeline.Direction.BACKWARDS && frontierTs <= untilTs ||
                                        direction == Timeline.Direction.FORWARDS && frontierTs >= untilTs)) {
                            Timber.i("PAGDBG headless: reached until_ts ($frontierTs vs $untilTs) after $page pages")
                            break
                        }
                    }
                    reportTimelineOrder(roomId, timeline.getSnapshot())
                    if (settleSeconds > 0) {
                        Timber.i("PAGDBG headless: settling for ${settleSeconds}s")
                        delay(settleSeconds * 1000L)
                        timeline.awaitPaginate(direction, limit)
                        reportTimelineOrder(roomId, timeline.getSnapshot())
                    }
                    Timber.i("PAGDBG headless: done for $roomId")
                } finally {
                    timeline.dispose()
                }
            } catch (failure: Throwable) {
                Timber.e(failure, "PAGDBG headless: paginate run failed for $roomId")
            } finally {
                paginating.set(false)
            }
        }
    }

    /** Check rendered timestamp order and report large gaps for debug pagination commands. */
    private fun reportTimelineOrder(roomId: String, snapshot: List<org.matrix.android.sdk.api.session.room.timeline.TimelineEvent>) {
        val stamped = snapshot.mapNotNull { event -> event.root.originServerTs?.let { event.eventId to it } }
        if (stamped.isEmpty()) return Unit.also { Timber.w("PAGDBG order: nothing timestamped in $roomId") }
        var inversions = 0
        var gaps = 0
        for (i in 1 until stamped.size) {
            val (newerId, newerTs) = stamped[i - 1]
            val (olderId, olderTs) = stamped[i]
            // Stream order and timestamp order legitimately disagree by seconds, and the order repairer
            // deliberately leaves anything under its own threshold alone: only count what it would move.
            if (olderTs - newerTs > INVERSION_TOLERANCE_MS) {
                inversions++
                Timber.w("PAGDBG order: INVERSION at $i: $olderId (${date(olderTs)}) renders above $newerId (${date(newerTs)})")
            } else if (newerTs - olderTs > SUSPICIOUS_GAP_MS) {
                gaps++
                Timber.w("PAGDBG order: GAP at $i: ${(newerTs - olderTs) / DAY_MS}d between $olderId (${date(olderTs)}) and $newerId (${date(newerTs)})")
            }
        }
        Timber.i(
                "PAGDBG order: ${stamped.size} events ${date(stamped.last().second)} .. ${date(stamped.first().second)}, " +
                        "$inversions inversion(s), $gaps gap(s) — ${if (inversions == 0 && gaps == 0) "CONTINUOUS" else "BROKEN"}"
        )
    }

    private fun date(ts: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ts))

    /**
     * Queues a message and reports whether an open timeline shows it — the local echo has to appear the
     * moment it is queued, not once the room is reopened.
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_SEND_TEXT --es room_id '!roomId' [--es body text]
     */
    private fun sendText(intent: Intent) = withRoom(intent, "send") { roomId, room ->
        val body = intent.getStringExtra("body") ?: "echo probe ${SystemClock.uptimeMillis()}"
        val timeline = room.timelineService().createTimeline(null, TimelineSettings(initialSize = 30))
        timeline.start()
        try {
            delay(2_000)
            val before = timeline.getSnapshot().size
            room.sendService().sendTextMessage(body)
            // Poll rather than sleep once: the echo is supposed to arrive within a frame or two, and how
            // long it actually took is the interesting part.
            var waited = 0L
            var found = false
            while (waited < ECHO_WAIT_MAX_MS) {
                found = timeline.getSnapshot().any { it.root.getClearContent()?.get("body") == body }
                if (found) break
                delay(ECHO_POLL_MS)
                waited += ECHO_POLL_MS
            }
            val snapshot = timeline.getSnapshot()
            Timber.i(
                    "ECHODBG $roomId: queued \"$body\" — echo ${if (found) "shown after ${waited}ms" else "NOT SHOWN after ${ECHO_WAIT_MAX_MS}ms"}, " +
                            "snapshot $before -> ${snapshot.size}, sending=${snapshot.count { it.root.sendState.isSending() }}, " +
                            "newest=${snapshot.firstOrNull()?.root?.getClearContent()?.get("body")}"
            )
        } finally {
            timeline.dispose()
        }
    }

    /**
     * Search a room the way the search screen does, then optionally open a timeline at the top hit and
     * report what it renders — the search-and-jump flow, without a device in anyone's hands:
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_SEARCH_ROOM --es room_id '!roomId' --es term 'hello'
     *   [--ei limit 20] [--ez jump true] [--ei pages 6]
     */
    private fun searchRoom(intent: Intent) {
        val roomId = intent.getStringExtra("room_id") ?: return Unit.also { Timber.w("PAGDBG search: missing room_id extra") }
        val term = intent.getStringExtra("term") ?: return Unit.also { Timber.w("PAGDBG search: missing term extra") }
        val limit = intent.getIntExtra("limit", 20)
        val jump = intent.getBooleanExtra("jump", false)
        val pages = intent.getIntExtra("pages", 6)
        // Oldest-first hits are the interesting ones for history bugs: a recent hit is already cached, so
        // jumping to it never exercises the /context island path.
        val oldest = intent.getBooleanExtra("oldest", false)
        scope.launch {
            try {
                val session = activeSessionHolder.getSafeActiveSession()
                        ?: return@launch Unit.also { Timber.w("PAGDBG search: no active session") }
                val result = session.searchService().search(
                        searchTerm = term,
                        roomId = roomId,
                        nextBatch = null,
                        orderByRecent = !oldest,
                        beforeLimit = 0,
                        afterLimit = 0,
                        includeProfile = false,
                        limit = limit,
                )
                val hits = result.results.orEmpty().mapNotNull { it.event.eventId?.let { id -> id to it.event.originServerTs } }
                Timber.i("PAGDBG search: '$term' in $roomId returned ${hits.size} hit(s)")
                hits.forEachIndexed { i, (eventId, ts) ->
                    Timber.i("PAGDBG search hit $i: $eventId ${ts?.let { date(it) }}")
                }
                val target = hits.firstOrNull()?.first ?: return@launch
                if (!jump) return@launch
                Timber.i("PAGDBG search: jumping to $target")
                paginateRoom(
                        Intent().putExtra("room_id", roomId).putExtra("from_event", target).putExtra("pages", pages)
                                .putExtra("direction", intent.getStringExtra("direction"))
                )
            } catch (failure: Throwable) {
                Timber.e(failure, "PAGDBG search: run failed for $roomId")
            }
        }
    }

    /**
     * What the server actually holds, as opposed to what a sync told us:
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_FETCH_PUSH_RULES
     */
    private fun fetchPushRules() {
        scope.launch {
            val session = activeSessionHolder.getSafeActiveSession()
                    ?: return@launch Unit.also { Timber.w("NOTIFDBG fetch: no active session") }
            fun stored() = session.pushRuleService().getPushRules().getAllRules()
                    .find { it.ruleId == RuleIds.RULE_ID_IS_USER_MENTION }
            Timber.i("NOTIFDBG fetch: before, is_user_mention enabled=${stored()?.enabled}")
            session.pushRuleService().fetchPushRules()
            delay(3_000)
            Timber.i("NOTIFDBG fetch: after GET /pushrules, is_user_mention enabled=${stored()?.enabled}")
        }
    }

    /**
     * Synthetic main-thread block, to exercise ANR behaviour:
     * adb shell am broadcast -a <pkg>.DEBUG_ACTION_FREEZE_MAIN [--ei ms 20000] [--ei repeat 10] [--ei gap 400]
     * Logs under the ANRDBG tag.
     */
    private fun freezeMain(intent: Intent) {
        val ms = intent.getIntExtra("ms", 20_000).toLong()
        val repeat = intent.getIntExtra("repeat", 1)
        val gap = intent.getIntExtra("gap", 0).toLong()
        // Posted rather than slept inline, so this is an app freeze and not a broadcast-dispatch ANR.
        val handler = Handler(Looper.getMainLooper())

        // Each block is posted only once the previous finished, so `gap` is a real window in which
        // the looper drains input and the window goes responsive again.
        fun block(i: Int) {
            handler.postDelayed({
                Timber.i("ANRDBG freeze ${i + 1}/$repeat: blocking main thread for ${ms}ms")
                val start = SystemClock.uptimeMillis()
                Thread.sleep(ms)
                Timber.i("ANRDBG freeze ${i + 1}/$repeat: released after ${SystemClock.uptimeMillis() - start}ms")
                if (i + 1 < repeat) block(i + 1)
            }, if (i == 0) 500 else gap)
        }
        block(0)
    }

    private fun dumpPreferences() {
        logPrefs("DefaultSharedPreferences", sharedPreferences)
    }

    private fun logPrefs(name: String, sharedPreferences: SharedPreferences?) {
        Timber.v("SharedPreferences $name:")

        sharedPreferences?.let { prefs ->
            prefs.all.keys.forEach { key ->
                Timber.v("$key : ${prefs.all[key]}")
            }
        }
    }

    private fun alterScalarToken() {
        sharedPreferences.edit {
            // putString("SCALAR_TOKEN_PREFERENCE_KEY" + Matrix.getInstance(context).defaultSession.myUserId, "bad_token")
        }
    }

    companion object {
        private const val DEBUG_ACTION_DUMP_FILESYSTEM = ".DEBUG_ACTION_DUMP_FILESYSTEM"
        private const val DEBUG_ACTION_DUMP_PREFERENCES = ".DEBUG_ACTION_DUMP_PREFERENCES"
        private const val DEBUG_ACTION_ALTER_SCALAR_TOKEN = ".DEBUG_ACTION_ALTER_SCALAR_TOKEN"
        private const val DEBUG_ACTION_PAGINATE_ROOM = ".DEBUG_ACTION_PAGINATE_ROOM"
        private const val DEBUG_ACTION_DUMP_CHUNKS = ".DEBUG_ACTION_DUMP_CHUNKS"
        private const val DEBUG_ACTION_CLEAR_CACHE = ".DEBUG_ACTION_CLEAR_CACHE"
        private const val DEBUG_ACTION_SYNC_WATCH = ".DEBUG_ACTION_SYNC_WATCH"
        private const val DEBUG_ACTION_PERF = ".DEBUG_ACTION_PERF"
        private const val DEBUG_ACTION_BG_SYNC = ".DEBUG_ACTION_BG_SYNC"
        private const val STALL_REPORT_MS = 10_000L
        private const val PAGE_WAIT_STEP_MS = 500L
        private const val PAGE_WAIT_MAX_MS = 30_000
        private const val DEBUG_ACTION_TIMELINE_CHECK = ".DEBUG_ACTION_TIMELINE_CHECK"
        private const val DEBUG_ACTION_SEARCH_ROOM = ".DEBUG_ACTION_SEARCH_ROOM"
        private const val DEBUG_ACTION_SEND_TEXT = ".DEBUG_ACTION_SEND_TEXT"
        private const val ECHO_WAIT_MAX_MS = 8_000L
        private const val ECHO_POLL_MS = 250L
        private const val DEBUG_ACTION_FETCH_PUSH_RULES = ".DEBUG_ACTION_FETCH_PUSH_RULES"
        private const val DAY_MS = 24 * 3600 * 1000L
        private const val INVERSION_TOLERANCE_MS = 15 * 60 * 1000L

        // A day or two without traffic is an ordinary quiet spell even in a busy room; flagging those
        // called every healthy walk BROKEN. Only a step no real lull explains is worth reporting.
        private const val SUSPICIOUS_GAP_MS = 7 * DAY_MS
        private const val DEBUG_ACTION_FREEZE_MAIN = ".DEBUG_ACTION_FREEZE_MAIN"

        fun getIntentFilter(context: Context) = IntentFilter().apply {
            addAction(context.packageName + DEBUG_ACTION_DUMP_CHUNKS)
            addAction(context.packageName + DEBUG_ACTION_CLEAR_CACHE)
            addAction(context.packageName + DEBUG_ACTION_SYNC_WATCH)
            addAction(context.packageName + DEBUG_ACTION_PERF)
            addAction(context.packageName + DEBUG_ACTION_BG_SYNC)
            addAction(context.packageName + DEBUG_ACTION_TIMELINE_CHECK)
            addAction(context.packageName + DEBUG_ACTION_DUMP_FILESYSTEM)
            addAction(context.packageName + DEBUG_ACTION_DUMP_PREFERENCES)
            addAction(context.packageName + DEBUG_ACTION_ALTER_SCALAR_TOKEN)
            addAction(context.packageName + DEBUG_ACTION_PAGINATE_ROOM)
            addAction(context.packageName + DEBUG_ACTION_SEARCH_ROOM)
            addAction(context.packageName + DEBUG_ACTION_SEND_TEXT)
            addAction(context.packageName + DEBUG_ACTION_FETCH_PUSH_RULES)
            addAction(context.packageName + DEBUG_ACTION_FREEZE_MAIN)
        }
    }
}
