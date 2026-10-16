/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.delayedevents

import org.matrix.android.sdk.api.session.events.model.Content

/**
 * MSC4140 delayed events. Only usable when
 * [org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities.canUseDelayedEvents] is true.
 */
interface DelayedEventService {

    /**
     * Have the homeserver send an event after [delayMs]. A non-null [stateKey] makes it a state event.
     * @return the delay id, used to cancel or send it early
     */
    suspend fun scheduleEvent(roomId: String, eventType: String, content: Content, delayMs: Long, stateKey: String? = null): String

    /**
     * Schedule a text message built exactly as an immediate send would build it (markdown, pills, mentions).
     * Content is sent as is, so this is only for unencrypted rooms.
     */
    suspend fun scheduleTextMessage(roomId: String, text: CharSequence, formattedText: String?, autoMarkdown: Boolean, delayMs: Long): String

    /** Every event the current user has pending, across all rooms. */
    suspend fun getDelayedEvents(): List<DelayedEvent>

    suspend fun cancel(delayId: String)

    suspend fun sendNow(delayId: String)

    /**
     * After [delayMs], put [userId]'s power level back to [level] (null meaning `users_default`).
     * [currentContent] is the room's power levels as they now stand, typically what was just sent.
     * Pending restores in the room are re-based so that they all compose regardless of firing order.
     */
    suspend fun schedulePowerLevelRestore(roomId: String, currentContent: Content, userId: String, level: Int?, delayMs: Long): String

    /** Stop restoring [userId], leaving the other pending restores in [roomId] intact. */
    suspend fun cancelPowerLevelRestore(roomId: String, currentContent: Content, userId: String)

    /**
     * Pending power-level restores in [roomId] relative to its [currentContent] power levels, soonest first,
     * read out of [delayedEvents] as returned by [getDelayedEvents].
     */
    fun getPowerLevelRestores(roomId: String, currentContent: Content, delayedEvents: List<DelayedEvent>): List<PowerLevelRestore>

    /**
     * Unban [userId] from [roomId] after [delayMs], then invite them back when [reinvite]. Any membership
     * change we later make for them in that room through the SDK drops both, since the homeserver only
     * does so for changes made by someone else.
     */
    suspend fun scheduleUnban(roomId: String, userId: String, delayMs: Long, reinvite: Boolean = false)

    /** The unban of [userId] pending in [roomId], read out of [delayedEvents]. */
    fun getPendingUnban(roomId: String, userId: String, delayedEvents: List<DelayedEvent>): DelayedEvent?
}
