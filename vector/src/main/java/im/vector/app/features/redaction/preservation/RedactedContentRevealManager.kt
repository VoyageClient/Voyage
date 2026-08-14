/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which redacted events are currently showing their original content.
 *
 * A user's explicit Reveal or Hide is stored absolutely, not as a delta from the room's current
 * setting. That setting is mutable, and storing "differs from the default" meant that turning
 * preservation off in a room silently un-hid exactly the messages the user had chosen to hide.
 *
 * Only the choices persist. The content itself is re-read from [RedactedContentRepository], because
 * redaction destroys the local copy outright.
 */
@Singleton
class RedactedContentRevealManager @Inject constructor(
        @DefaultPreferences private val preferences: SharedPreferences,
        // Lazy: ActiveSessionHolder builds ConfigureAndStartSessionUseCase, which reaches this.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        private val settings: RedactionPreservationSettings,
        private val repository: RedactedContentRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Guards both sets and [loadedForUser] together: the sets are loaded as a pair, and a reader
    // that saw loadedForUser set while they were still being repopulated would report the room
    // default instead of the user's explicit choice.
    private val lock = Any()
    private val explicitlyRevealed = mutableSetOf<String>()
    private val explicitlyHidden = mutableSetOf<String>()
    private var loadedForUser: String? = null

    private val _revealChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Emits an event id whenever its revealed state changes, so bound items can re-render. */
    val revealChanges: SharedFlow<String> = _revealChanges.asSharedFlow()

    private fun userId(): String? = activeSessionHolder.get().getSafeActiveSession()?.myUserId

    private fun ensureLoaded(userId: String) {
        synchronized(lock) {
            if (loadedForUser == userId) return
            explicitlyRevealed.clear()
            explicitlyHidden.clear()
            explicitlyRevealed.addAll(preferences.getStringSet(revealedKey(userId), emptySet()).orEmpty())
            explicitlyHidden.addAll(preferences.getStringSet(hiddenKey(userId), emptySet()).orEmpty())
            loadedForUser = userId
        }
    }

    private fun revealedKey(userId: String) = "SETTINGS_REDACTION_REVEALED_$userId"

    private fun hiddenKey(userId: String) = "SETTINGS_REDACTION_HIDDEN_$userId"

    /**
     * Whether [eventId] in [roomId] should currently show its pre-redaction content.
     *
     * [isOwnMessage] only suppresses the room's automatic default: deleting your own message is a
     * deliberate act, so a room-wide reveal shouldn't quietly undo it everywhere. An explicit
     * per-message Reveal still works on your own messages.
     */
    fun isRevealed(roomId: String, eventId: String, isOwnMessage: Boolean): Boolean {
        val userId = userId() ?: return false
        ensureLoaded(userId)
        synchronized(lock) {
            if (eventId in explicitlyRevealed) return true
            if (eventId in explicitlyHidden) return false
        }
        if (isOwnMessage) return false
        return settings.preserveRedactedFor(roomId)
    }

    fun setRevealed(eventId: String, revealed: Boolean) {
        val userId = userId() ?: return
        ensureLoaded(userId)
        val changed = synchronized(lock) {
            val target = if (revealed) explicitlyRevealed else explicitlyHidden
            val other = if (revealed) explicitlyHidden else explicitlyRevealed
            other.remove(eventId)
            target.add(eventId)
        }
        persist(userId)
        if (changed) _revealChanges.tryEmit(eventId)
    }

    /**
     * Reveal/hide [eventId] together with the rest of its group: a message and its relations (edits,
     * reactions) were redacted as one message, so toggling any of them toggles the target and every
     * preserved relation — whichever end the user long-pressed.
     */
    fun setRevealedWithEdits(roomId: String, eventId: String, revealed: Boolean) {
        setRevealed(eventId, revealed)
        val session = activeSessionHolder.get().getSafeActiveSession() ?: return
        scope.launch {
            val service = session.redactedContentService()
            // If the tapped event is itself a relation, its preserved content points at the group's root.
            val relates = service.getPreservedContent(eventId)?.content?.get("m.relates_to") as? Map<*, *>
            val rootId = (relates?.takeIf { it["rel_type"] != null }?.get("event_id") as? String) ?: eventId
            setRevealed(rootId, revealed)
            service.getPreservedRelationsOf(roomId, rootId).forEach { relation ->
                setRevealed(relation.eventId, revealed)
            }
        }
    }

    /** Drops every explicit choice, e.g. when a room's redaction settings are reset. */
    fun clearExplicitChoices() {
        val userId = userId() ?: return
        ensureLoaded(userId)
        val cleared = synchronized(lock) {
            val all = explicitlyRevealed + explicitlyHidden
            explicitlyRevealed.clear()
            explicitlyHidden.clear()
            all
        }
        if (cleared.isEmpty()) return
        persist(userId)
        cleared.forEach { _revealChanges.tryEmit(it) }
    }

    private fun persist(userId: String) {
        // Snapshot under the lock; apply() rather than commit() so a burst of hides doesn't
        // serialise a full preferences rewrite + fsync per event.
        val revealed: Set<String>
        val hidden: Set<String>
        synchronized(lock) {
            revealed = explicitlyRevealed.toSet()
            hidden = explicitlyHidden.toSet()
        }
        preferences.edit {
            putStringSet(revealedKey(userId), revealed)
            putStringSet(hiddenKey(userId), hidden)
        }
    }
}
