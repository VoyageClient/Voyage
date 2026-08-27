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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import im.vector.app.core.debug.DebugReceiver
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultPreferences
import im.vector.app.core.utils.lsFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Receiver to handle some command from ADB
 */
class VectorDebugReceiver @Inject constructor(
        @DefaultPreferences
        private val sharedPreferences: SharedPreferences,
        private val activeSessionHolder: ActiveSessionHolder,
) : BroadcastReceiver(), DebugReceiver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val paginating = AtomicBoolean(false)

    override fun register(context: Context) {
        // Exported, or adb-shell broadcasts never arrive on Android 14+ (debug builds only).
        ContextCompat.registerReceiver(
                context,
                this,
                getIntentFilter(context),
                ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun unregister(context: Context) {
        context.unregisterReceiver(this)
    }

    override fun onReceive(context: Context, intent: Intent) {
        Timber.v("Received debug action: ${intent.action}")

        intent.action?.let {
            when {
                it.endsWith(DEBUG_ACTION_DUMP_FILESYSTEM) -> lsFiles(context)
                it.endsWith(DEBUG_ACTION_DUMP_PREFERENCES) -> dumpPreferences()
                it.endsWith(DEBUG_ACTION_ALTER_SCALAR_TOKEN) -> alterScalarToken()
                it.endsWith(DEBUG_ACTION_PAGINATE_ROOM) -> paginateRoom(intent)
            }
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
                        if (!timeline.hasMoreToLoad(Timeline.Direction.BACKWARDS)) {
                            Timber.i("PAGDBG headless: reached room start after ${page - 1} pages")
                            break
                        }
                        val snapshot = timeline.awaitPaginate(Timeline.Direction.BACKWARDS, limit)
                        val oldest = snapshot.lastOrNull()
                        Timber.i(
                                "PAGDBG headless page $page/$pages: ${snapshot.size} loaded, " +
                                        "oldest=${oldest?.eventId} ts=${oldest?.root?.originServerTs}"
                        )
                        val oldestTs = oldest?.root?.originServerTs
                        if (untilTs > 0 && oldestTs != null && oldestTs <= untilTs) {
                            Timber.i("PAGDBG headless: reached until_ts ($oldestTs <= $untilTs) after $page pages")
                            break
                        }
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

        fun getIntentFilter(context: Context) = IntentFilter().apply {
            addAction(context.packageName + DEBUG_ACTION_DUMP_FILESYSTEM)
            addAction(context.packageName + DEBUG_ACTION_DUMP_PREFERENCES)
            addAction(context.packageName + DEBUG_ACTION_ALTER_SCALAR_TOKEN)
            addAction(context.packageName + DEBUG_ACTION_PAGINATE_ROOM)
        }
    }
}
