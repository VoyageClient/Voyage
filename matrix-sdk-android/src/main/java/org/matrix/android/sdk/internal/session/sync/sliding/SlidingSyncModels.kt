/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.sync.model.DeviceListResponse
import org.matrix.android.sdk.api.session.sync.model.DeviceOneTimeKeysCountSyncResponse
import org.matrix.android.sdk.api.session.sync.model.RoomSyncUnreadNotifications

/**
 * Wire models shared by MSC4186 (simplified sliding sync) and MSC4525 (paginated sync). MSC4525 is
 * defined as MSC4186 minus lists/ranges/subscriptions plus paging, so the room results and the
 * extensions are literally the same shapes; only a handful of top-level fields differ.
 *
 * Moshi omits null fields, so one request class can serve both endpoints without either server
 * seeing keys meant for the other.
 */
@JsonClass(generateAdapter = true)
internal data class SlidingSyncRequest(
        // MSC4186
        @Json(name = "lists") val lists: Map<String, SlidingSyncListRequest>? = null,
        // MSC4525: required_state is stated once and applies to every room in the response.
        @Json(name = "required_state") val requiredState: List<List<String>>? = null,
        @Json(name = "page_size") val pageSize: Int? = null,
        @Json(name = "limit") val limit: Int? = null,
        @Json(name = "history") val history: Int? = null,
        // Both
        @Json(name = "extensions") val extensions: SlidingSyncExtensionsRequest? = null,
        @Json(name = "set_presence") val setPresence: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class SlidingSyncListRequest(
        @Json(name = "ranges") val ranges: List<List<Int>>,
        @Json(name = "required_state") val requiredState: List<List<String>>,
        @Json(name = "timeline_limit") val timelineLimit: Int,
        @Json(name = "filters") val filters: SlidingSyncFilters? = null,
)

@JsonClass(generateAdapter = true)
internal data class SlidingSyncFilters(
        @Json(name = "is_dm") val isDm: Boolean? = null,
        @Json(name = "is_invite") val isInvite: Boolean? = null,
        @Json(name = "tags") val tags: List<String>? = null,
)

@JsonClass(generateAdapter = true)
internal data class SlidingSyncExtensionsRequest(
        @Json(name = "to_device") val toDevice: ToDeviceExtensionRequest? = null,
        @Json(name = "e2ee") val e2ee: EnabledExtensionRequest? = null,
        @Json(name = "account_data") val accountData: EnabledExtensionRequest? = null,
        @Json(name = "receipts") val receipts: EnabledExtensionRequest? = null,
        @Json(name = "typing") val typing: EnabledExtensionRequest? = null,
        // MSC4262 has no stable extension name yet, so only the unstable one is ever requested.
        @Json(name = "org.matrix.msc4262.profiles") val unstableProfiles: ProfilesExtensionRequest? = null,
)

@JsonClass(generateAdapter = true)
internal data class ProfilesExtensionRequest(
        @Json(name = "enabled") val enabled: Boolean = true,
        @Json(name = "fields") val fields: List<String>? = null,
)

@JsonClass(generateAdapter = true)
internal data class EnabledExtensionRequest(
        @Json(name = "enabled") val enabled: Boolean = true,
)

@JsonClass(generateAdapter = true)
internal data class ToDeviceExtensionRequest(
        @Json(name = "enabled") val enabled: Boolean = true,
        @Json(name = "limit") val limit: Int = 100,
        @Json(name = "since") val since: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class SlidingSyncResponse(
        @Json(name = "pos") val pos: String,
        @Json(name = "lists") val lists: Map<String, SlidingSyncListResult>? = null,
        @Json(name = "rooms") val rooms: Map<String, SlidingSyncRoom>? = null,
        @Json(name = "extensions") val extensions: SlidingSyncExtensionsResponse? = null,
        /** MSC4525: rooms with updates that did not fit into `page_size`. Absent means none. */
        @Json(name = "pending") val pending: Int? = null,
        @Json(name = "total_rooms") val totalRooms: Int? = null,
)

@JsonClass(generateAdapter = true)
internal data class SlidingSyncListResult(
        @Json(name = "count") val count: Int = 0,
)

@JsonClass(generateAdapter = true)
internal data class SlidingSyncRoom(
        @Json(name = "name") val name: String? = null,
        @Json(name = "avatar") val avatar: String? = null,
        @Json(name = "heroes") val heroes: List<SlidingSyncHero>? = null,
        @Json(name = "is_dm") val isDm: Boolean? = null,
        @Json(name = "initial") val initial: Boolean = false,
        @Json(name = "required_state") val requiredState: List<Event>? = null,
        @Json(name = "timeline") val timeline: List<Event>? = null,
        @Json(name = "prev_batch") val prevBatch: String? = null,
        @Json(name = "limited") val limited: Boolean = false,
        @Json(name = "joined_count") val joinedCount: Int? = null,
        @Json(name = "invited_count") val invitedCount: Int? = null,
        // Synapse flattens the counts; ruma also accepts them nested the way sync v2 sends them.
        @Json(name = "notification_count") val notificationCount: Int? = null,
        @Json(name = "highlight_count") val highlightCount: Int? = null,
        @Json(name = "unread_notifications") val unreadNotifications: RoomSyncUnreadNotifications? = null,
        @Json(name = "membership") val membership: String? = null,
        // The MSC calls this stripped_state; Synapse and matrix-js-sdk speak invite_state.
        @Json(name = "invite_state") val inviteState: List<Event>? = null,
        @Json(name = "stripped_state") val strippedState: List<Event>? = null,
) {
    val strippedStateEvents: List<Event>? get() = inviteState ?: strippedState

    /** Null when the server said nothing about the counts, which means unchanged rather than zero. */
    val unreadCounts: RoomSyncUnreadNotifications?
        get() = when {
            notificationCount != null || highlightCount != null ->
                RoomSyncUnreadNotifications(notificationCount = notificationCount, highlightCount = highlightCount)
            else -> unreadNotifications
        }
}

@JsonClass(generateAdapter = true)
internal data class SlidingSyncHero(
        @Json(name = "user_id") val userId: String,
        @Json(name = "displayname") val displayName: String? = null,
        @Json(name = "avatar_url") val avatarUrl: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class SlidingSyncExtensionsResponse(
        @Json(name = "to_device") val toDevice: ToDeviceExtensionResponse? = null,
        @Json(name = "e2ee") val e2ee: E2eeExtensionResponse? = null,
        @Json(name = "account_data") val accountData: AccountDataExtensionResponse? = null,
        @Json(name = "receipts") val receipts: RoomEduExtensionResponse? = null,
        @Json(name = "typing") val typing: RoomEduExtensionResponse? = null,
        @Json(name = "profiles") val profiles: ProfilesExtensionResponse? = null,
        @Json(name = "org.matrix.msc4262.profiles") val unstableProfiles: ProfilesExtensionResponse? = null,
) {
    val profileUpdates: Map<String, SlidingSyncProfileUpdate?>? get() = (profiles ?: unstableProfiles)?.users
}

@JsonClass(generateAdapter = true)
internal data class ProfilesExtensionResponse(
        /** A null entry means the user left every shared room, so we can stop tracking them. */
        @Json(name = "users") val users: Map<String, SlidingSyncProfileUpdate?>? = null,
)

@JsonClass(generateAdapter = true)
internal data class SlidingSyncProfileUpdate(
        @Json(name = "updated") val updated: Map<String, Any?>? = null,
        @Json(name = "removed") val removed: List<String>? = null,
)

@JsonClass(generateAdapter = true)
internal data class ToDeviceExtensionResponse(
        @Json(name = "events") val events: List<Event>? = null,
        @Json(name = "next_batch") val nextBatch: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class E2eeExtensionResponse(
        @Json(name = "device_lists") val deviceLists: DeviceListResponse? = null,
        @Json(name = "device_one_time_keys_count") val deviceOneTimeKeysCount: DeviceOneTimeKeysCountSyncResponse? = null,
        @Json(name = "device_unused_fallback_key_types") val deviceUnusedFallbackKeyTypes: List<String>? = null,
        @Json(name = "org.matrix.msc2732.device_unused_fallback_key_types") val devDeviceUnusedFallbackKeyTypes: List<String>? = null,
)

@JsonClass(generateAdapter = true)
internal data class AccountDataExtensionResponse(
        @Json(name = "global") val global: List<UserAccountDataEvent>? = null,
        @Json(name = "rooms") val rooms: Map<String, List<Event>>? = null,
)

/** Receipts and typing both answer with exactly one aggregate EDU per room. */
@JsonClass(generateAdapter = true)
internal data class RoomEduExtensionResponse(
        @Json(name = "rooms") val rooms: Map<String, Event>? = null,
)
