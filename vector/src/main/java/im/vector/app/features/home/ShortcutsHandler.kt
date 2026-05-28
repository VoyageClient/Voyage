/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.getSystemService
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

    // Value will be set correctly if necessary
    private var hasPinCode = AtomicBoolean(true)

    // Signature of the last set of shortcuts that was pushed to the system. The room summaries
    // flow emits very frequently (every read receipt, new message, etc.) but the visible
    // shortcuts only change when the SET of top-N rooms changes (or one of their avatars /
    // names changes). Re-pushing identical shortcuts on every emission causes Android to
    // write a fresh bitmap file under /data/system_ce/0/shortcut_service/bitmaps/<pkg>/
    // each time, eventually consuming gigabytes of device storage.
    //
    // We compare as a Set, so when room *order* changes within the top-N (e.g. an incoming
    // message bumps an already-pinned room up) we skip the re-push entirely. Pushing 5
    // shortcuts goes through Glide bitmap rasterization per shortcut, which can take hundreds
    // of ms and occasionally seconds (cold avatar cache). Avoiding it when nothing visible
    // changed is a big win.
    private var lastShortcutsSignature: Set<String>? = null

    fun observeRoomsAndBuildShortcuts(coroutineScope: CoroutineScope): Job {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            // No op
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
                    // Remove dead shortcuts (i.e. deleted rooms)
                    removeDeadShortcuts(rooms.map { it.roomId })

                    // Create shortcuts
                    createShortcuts(rooms)
                }
                .flowOn(appDispatchers.computation)
                .launchIn(coroutineScope)
    }

    @SuppressLint("RestrictedApi")
    fun updateShortcutsWithPreviousIntent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        // Check if it's been already done
        if (sharedPreferences.getBoolean(SHARED_PREF_KEY, false)) return
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.intent.component?.className == RoomDetailActivity::class.qualifiedName }
                .mapNotNull {
                    it.intent.getStringExtra("EXTRA_ROOM_ID")?.let { roomId ->
                        ShortcutInfoCompat.Builder(context, it.toShortcutInfo())
                                .setIntent(MainActivity.shortcutIntent(context, roomId))
                                .build()
                    }
                }
                .takeIf { it.isNotEmpty() }
                ?.also { Timber.d("Update ${it.size} shortcut(s)") }
                ?.let { tryOrNull("Error") { ShortcutManagerCompat.updateShortcuts(context, it) } }
                ?.also { Timber.d("Update shortcuts with success: $it") }
        sharedPreferences.edit { putBoolean(SHARED_PREF_KEY, true) }
    }

    private fun removeDeadShortcuts(roomIds: List<String>) {
        val deadShortcutIds = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
                .map { it.id }
                .filter { !roomIds.contains(it) }

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

    private fun createShortcuts(rooms: List<RoomSummary>) = im.vector.app.core.utils.PerfTrace.time("shortcuts.create") {
        // No shortcut in this case (privacy, or user opted out).
        if (hasPinCode.get() || !vectorPreferences.appShortcutsEnabled()) {
            if (lastShortcutsSignature != emptySet<String>()) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                lastShortcutsSignature = emptySet()
            }
            return
        }

        val visibleRooms = rooms.take(maxShortcutCountPerActivity)
        // Set-based, order-insensitive: only re-push when the top-N membership / avatars /
        // names change. Reorders within the top-N (the common case as messages arrive) are
        // ignored — the launcher's long-press menu doesn't visibly rely on stable ordering
        // here and the cost of pushing is dominated by bitmap rasterization.
        val signature = visibleRooms.mapTo(HashSet(visibleRooms.size)) { room ->
            "${room.roomId}|${room.avatarUrl}|${room.displayName}"
        }
        if (signature == lastShortcutsSignature) {
            return
        }
        lastShortcutsSignature = signature

        ShortcutManagerCompat.removeAllDynamicShortcuts(context)

        val shortcuts = visibleRooms.mapIndexed { index, room ->
            shortcutCreator.create(room, index)
        }

        shortcuts.forEach { shortcut ->
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    /**
     * Drop all current dynamic shortcuts immediately. Used when the user disables the
     * "Enable app shortcuts" preference so they vanish without waiting for the next room
     * summaries emission. Pinned shortcuts are left alone.
     */
    fun removeAllDynamicShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        lastShortcutsSignature = emptySet()
    }

    fun clearShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            // No op
            return
        }

        // according to Android documentation
        // removeLongLivedShortcuts for API 29 and lower should behave like removeDynamicShortcuts(Context, List)
        // getDynamicShortcuts: returns all dynamic shortcuts from the app.
        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
        ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcuts)
        lastShortcutsSignature = null

        // We can only disabled pinned shortcuts with the API, but at least it will prevent the crash
        if (isRequestPinShortcutSupported) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                context.getSystemService<ShortcutManager>()
                        ?.pinnedShortcuts
                        ?.takeIf { it.isNotEmpty() }
                        ?.map { pinnedShortcut -> pinnedShortcut.id }
                        ?.let { shortcutIdsToDisable ->
                            ShortcutManagerCompat.disableShortcuts(
                                    context,
                                    shortcutIdsToDisable,
                                    stringProvider.getString(CommonStrings.shortcut_disabled_reason_sign_out)
                            )
                        }
            }
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
