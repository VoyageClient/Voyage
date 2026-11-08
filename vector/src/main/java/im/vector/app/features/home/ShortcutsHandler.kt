/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultPreferences
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.MainActivity
import im.vector.app.features.home.room.detail.RoomDetailActivity
import im.vector.app.features.pin.PinCodeStore
import im.vector.app.features.pin.PinCodeStoreListener
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.flow.flow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

class ShortcutsHandler @Inject constructor(
        private val context: Context,
        private val stringProvider: StringProvider,
        private val appDispatchers: CoroutineDispatchers,
        private val shortcutCreator: ShortcutCreator,
        private val activeSessionHolder: ActiveSessionHolder,
        private val pinCodeStore: PinCodeStore,
        private val vectorPreferences: VectorPreferences,
        @DefaultPreferences
        private val sharedPreferences: SharedPreferences,
) : PinCodeStoreListener {

    private val isRequestPinShortcutSupported = ShortcutManagerCompat.isRequestPinShortcutSupported(context)
    private val maxShortcutCountPerActivity = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)

    private val hasPinCode = AtomicBoolean(true)

    // Republishing unchanged shortcuts duplicates avatar files in Android's shortcut store.
    // Ignore room reordering within the same set of visible shortcuts.
    private var lastShortcutsSignature: Set<String>? = null

    fun observeRoomsAndBuildShortcuts(coroutineScope: CoroutineScope): Job {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return Job()
        }
        coroutineScope.launch {
            hasPinCode.set(pinCodeStore.hasEncodedPin())
        }
        val session = activeSessionHolder.getSafeActiveSession() ?: return Job()
        return session.flow().liveRoomSummaries(
                roomSummaryQueryParams {
                    memberships = listOf(Membership.JOIN)
                },
                sortOrder = RoomSortOrder.PRIORITY_AND_ACTIVITY
        )
                .onStart { pinCodeStore.addListener(this@ShortcutsHandler) }
                .onCompletion { pinCodeStore.removeListener(this@ShortcutsHandler) }
                .onEach { rooms ->
                    val roomIds = rooms.mapTo(HashSet()) { it.roomId }
                    restorePinnedShortcuts(session.sessionId, roomIds)
                    removeDeadShortcuts(session.sessionId, roomIds)
                    createShortcuts(session.sessionId, rooms)
                }
                .flowOn(appDispatchers.computation)
                .launchIn(coroutineScope)
    }

    fun updateShortcutsWithPreviousIntent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        // Check if it's been already done
        if (sharedPreferences.getBoolean(SHARED_PREF_KEY, false)) return
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.intent.component?.className == RoomDetailActivity::class.qualifiedName }
                .mapNotNull {
                    it.intent.getStringExtra("EXTRA_ROOM_ID")?.let { roomId ->
                        shortcutWithRoomIntent(it, roomId)
                    }
                }
                .takeIf { it.isNotEmpty() }
                ?.also { Timber.d("Update ${it.size} shortcut(s)") }
                ?.let { tryOrNull("Error") { ShortcutManagerCompat.updateShortcuts(context, it) } }
                ?.also { Timber.d("Update shortcuts with success: $it") }
        sharedPreferences.edit { putBoolean(SHARED_PREF_KEY, true) }
    }

    private fun restorePinnedShortcuts(sessionId: String, roomIds: Set<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter {
                    val owner = it.intent.getStringExtra(MainActivity.EXTRA_SHORTCUT_SESSION_ID)
                    (owner == null || owner == sessionId) && it.intent.getStringExtra("EXTRA_ROOM_ID") in roomIds
                }
        val disabled = pinned.filter { !it.isEnabled }
        if (disabled.isNotEmpty()) ShortcutManagerCompat.enableShortcuts(context, disabled)

        // Older shortcuts have no account identity. Bind them when their room is found locally.
        val migrated = pinned.filter { it.intent.getStringExtra(MainActivity.EXTRA_SHORTCUT_SESSION_ID) == null }
                .map {
                    shortcutWithRoomIntent(it, it.intent.getStringExtra("EXTRA_ROOM_ID")!!, sessionId)
                }
        if (migrated.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, migrated)
    }

    private fun shortcutWithRoomIntent(shortcut: ShortcutInfoCompat, roomId: String, sessionId: String? = null): ShortcutInfoCompat {
        // Updating without an icon preserves the launcher's stored bitmap.
        return ShortcutInfoCompat.Builder(context, shortcut.id)
                .setShortLabel(shortcut.shortLabel)
                .setRank(shortcut.rank)
                .setLongLived(true)
                .setIntent(MainActivity.shortcutIntent(context, roomId, sessionId))
                .build()
    }

    private fun removeDeadShortcuts(sessionId: String, roomIds: Set<String>) {
        val deadShortcutIds = ShortcutManagerCompat.getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED
        )
                .filter {
                    it.isEnabled && it.intent.getStringExtra(MainActivity.EXTRA_SHORTCUT_SESSION_ID) == sessionId &&
                            it.intent.getStringExtra("EXTRA_ROOM_ID") !in roomIds
                }
                .map { it.id }

        if (deadShortcutIds.isNotEmpty()) {
            Timber.d("Removing shortcut(s) $deadShortcutIds")
            ShortcutManagerCompat.removeLongLivedShortcuts(context, deadShortcutIds)
            if (isRequestPinShortcutSupported) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    ShortcutManagerCompat.disableShortcuts(
                            context,
                            deadShortcutIds,
                            stringProvider.getString(CommonStrings.shortcut_disabled_reason_room_left)
                    )
                }
            }
        }
    }

    private fun createShortcuts(sessionId: String, rooms: List<RoomSummary>) = im.vector.app.core.utils.PerfTrace.time("shortcuts.create") {
        if (hasPinCode.get() || !vectorPreferences.appShortcutsEnabled()) {
            if (lastShortcutsSignature != emptySet<String>()) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                lastShortcutsSignature = emptySet()
            }
            return
        }

        val visibleRooms = rooms.take(maxShortcutCountPerActivity)
        val signature = visibleRooms.mapTo(HashSet(visibleRooms.size)) { room ->
            "$sessionId|${room.roomId}|${room.avatarUrl}|${room.displayName}"
        }
        if (signature == lastShortcutsSignature) {
            return
        }
        lastShortcutsSignature = signature

        ShortcutManagerCompat.removeAllDynamicShortcuts(context)

        val shortcuts = visibleRooms.mapIndexed { index, room ->
            shortcutCreator.create(room, sessionId, index)
        }

        shortcuts.forEach { shortcut ->
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    fun removeAllDynamicShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        lastShortcutsSignature = emptySet()
    }

    fun resetForAccountSwitch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
        ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcuts)
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        lastShortcutsSignature = null
    }

    fun clearShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }

        val sessionId = activeSessionHolder.getSafeActiveSession()?.sessionId
        resetForAccountSwitch()

        if (!isRequestPinShortcutSupported) return
        val shortcutIds = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter {
                    val owner = it.intent.getStringExtra(MainActivity.EXTRA_SHORTCUT_SESSION_ID)
                    owner == null || owner == sessionId
                }
                .map { it.id }
        if (shortcutIds.isNotEmpty()) {
            ShortcutManagerCompat.disableShortcuts(
                    context,
                    shortcutIds,
                    stringProvider.getString(CommonStrings.shortcut_disabled_reason_sign_out)
            )
        }
    }

    override fun onPinSetUpChange(isConfigured: Boolean) {
        hasPinCode.set(isConfigured)
        if (isConfigured) {
            // Remove shortcuts immediately
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            lastShortcutsSignature = emptySet()
        } else {
            // Force a refresh on the next room summaries emission.
            lastShortcutsSignature = null
        }
        // Else shortcut will be created next time any room summary is updated, or
        // next time the app is started which is acceptable
    }

    companion object {
        const val SHARED_PREF_KEY = "ROOM_DETAIL_ACTIVITY_SHORTCUT_UPDATED"
    }
}
