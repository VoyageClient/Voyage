/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import org.matrix.android.sdk.api.session.events.model.EventType

/**
 * The state a sliding-sync connection is opened with, and a fingerprint of it.
 *
 * A connection only ever delivers the state it was opened with, so changing this list means the existing
 * connection has to be thrown away — [VERSION] is what detects that across app versions.
 */
internal object SlidingSyncRequiredState {

    private const val WILDCARD = "*"
    private const val STATE_KEY_ME = "\$ME"
    private const val STATE_KEY_LAZY = "\$LAZY"

    // Defined in the app rather than the SDK, but the room list reads them for every room, so they have to
    // arrive with the sync rather than waiting for a room to be opened.
    private const val FUNCTIONAL_MEMBERS = "io.element.functional_members"
    private const val VOICE_BROADCAST_INFO = "io.element.voicebroadcast.info"

    /**
     * Everything except the two things that arrive in bulk: whole member lists and policy-rule lists.
     * Sync v2 ran with lazy_load_members and never fetched whole member lists either — /members does that
     * per room, when a room is opened — and asking for them here costs an enormous first sync for state
     * that gets fetched on demand anyway.
     *
     * This has to be spelled out type by type: `["*", ""]` would say the same thing far more safely, but
     * Synapse answers a wildcard type paired with a specific state key with a 500. So any state type added
     * to the app in future must be added here too, or it will silently never arrive.
     */
    val EVENTS: List<List<String>> = listOf(
            listOf(EventType.STATE_ROOM_CREATE, ""),
            listOf(EventType.STATE_ROOM_NAME, ""),
            listOf(EventType.STATE_ROOM_TOPIC, ""),
            listOf(EventType.STATE_ROOM_AVATAR, ""),
            listOf(EventType.STATE_ROOM_CANONICAL_ALIAS, ""),
            listOf(EventType.STATE_ROOM_ALIASES, ""),
            listOf(EventType.STATE_ROOM_JOIN_RULES, ""),
            listOf(EventType.STATE_ROOM_HISTORY_VISIBILITY, ""),
            listOf(EventType.STATE_ROOM_GUEST_ACCESS, ""),
            listOf(EventType.STATE_ROOM_POWER_LEVELS, ""),
            listOf(EventType.STATE_ROOM_ENCRYPTION, ""),
            listOf(EventType.STATE_ROOM_TOMBSTONE, ""),
            listOf(EventType.STATE_ROOM_PINNED_EVENT, ""),
            listOf(EventType.STATE_ROOM_SERVER_ACL, ""),
            listOf(EventType.STATE_ROOM_RELATED_GROUPS, ""),
            listOf(EventType.STATE_ROOM_BANNER.stable, ""),
            listOf(EventType.STATE_ROOM_BANNER.unstable, ""),
            listOf(EventType.STATE_SPACE_CHILD, WILDCARD),
            listOf(EventType.STATE_SPACE_PARENT, WILDCARD),
            listOf(EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE, WILDCARD),
            listOf(EventType.STATE_ROOM_IMAGE_PACK, WILDCARD),
            listOf(EventType.STATE_ROOM_WIDGET_LEGACY, WILDCARD),
            listOf(EventType.STATE_ROOM_WIDGET, WILDCARD),
            listOf(EventType.STATE_ROOM_THIRD_PARTY_INVITE, WILDCARD),
            listOf(EventType.STATE_ROOM_BEACON_INFO.stable, WILDCARD),
            listOf(EventType.STATE_ROOM_BEACON_INFO.unstable, WILDCARD),
            listOf(EventType.BOT_OPTIONS, WILDCARD),
            listOf(FUNCTIONAL_MEMBERS, ""),
            listOf(VOICE_BROADCAST_INFO, WILDCARD),
            listOf(EventType.STATE_ROOM_MEMBER, STATE_KEY_ME),
            listOf(EventType.STATE_ROOM_MEMBER, STATE_KEY_LAZY),
    )

    val VERSION: String = EVENTS.toString().hashCode().toString()
}
