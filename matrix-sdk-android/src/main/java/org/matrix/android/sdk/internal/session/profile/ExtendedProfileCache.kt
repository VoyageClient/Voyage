/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.profile.ProfileKeys
import org.matrix.android.sdk.api.session.profile.Pronoun
import org.matrix.android.sdk.api.session.profile.UserBio
import org.matrix.android.sdk.api.session.profile.UserStatus
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.task.TaskExecutor
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

// Stable key preferred over unstable.
internal fun JsonDict.profileBannerUrl(): String? {
    return (this[ProfileKeys.BANNER_URL] as? String)
            ?: (this[ProfileKeys.BANNER_URL_UNSTABLE] as? String)
}

internal fun JsonDict.profileTimezone(): String? {
    return (this[ProfileKeys.TIMEZONE] as? String)
            ?: (this[ProfileKeys.TIMEZONE_UNSTABLE] as? String)
}

internal fun JsonDict.profilePronouns(): List<Pronoun>? {
    val raw = (this[ProfileKeys.PRONOUNS] as? List<*>)
            ?: (this[ProfileKeys.PRONOUNS_UNSTABLE] as? List<*>)
            ?: return null
    return raw.mapNotNull { entry ->
        val map = entry as? Map<*, *> ?: return@mapNotNull null
        val summary = map["summary"] as? String ?: return@mapNotNull null
        Pronoun(
                summary = summary,
                language = map["language"] as? String,
                subject = map["subject"] as? String,
                objectForm = map["object"] as? String,
                possessiveDeterminer = map["possessive_determiner"] as? String,
                possessivePronoun = map["possessive_pronoun"] as? String,
                reflexive = map["reflexive"] as? String,
                grammaticalGender = (map["grammatical_gender"] as? String)?.lowercase(),
        )
    }
}

internal fun JsonDict.profileStatus(): UserStatus? {
    val fromObject = (this[ProfileKeys.STATUS] as? Map<*, *>)
            ?: (this[ProfileKeys.STATUS_UNSTABLE] as? Map<*, *>)
    if (fromObject != null) {
        val text = fromObject["text"] as? String
        val emoji = fromObject["emoji"] as? String
        return UserStatus(text = text.orEmpty(), emoji = emoji.orEmpty()).takeIf { !it.isEmpty() }
    }
    val commet = this[ProfileKeys.STATUS_COMMET] as? String ?: return null
    return UserStatus(text = commet).takeIf { !it.isEmpty() }
}

internal fun JsonDict.profileBio(): UserBio? {
    val extensible = (this[ProfileKeys.BIOGRAPHY] as? Map<*, *>)
            ?: (this[ProfileKeys.BIOGRAPHY_UNSTABLE] as? Map<*, *>)
    if (extensible != null) {
        val representations = (extensible["m.text"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val html = representations.firstOrNull { it["mimetype"] == MimeTypes.Html }?.get("body") as? String
        val plain = representations.firstOrNull { it["mimetype"] == null || it["mimetype"] == MimeTypes.PlainText }
                ?.get("body") as? String
        // A bio carrying only HTML still has to render, so fall back to it as the plain body.
        return UserBio(body = plain ?: html.orEmpty(), formattedBody = html).takeIf { !it.isEmpty() }
    }
    val commet = (this[ProfileKeys.BIOGRAPHY_COMMET] as? Map<*, *>)?.get("body") as? String ?: return null
    return UserBio(body = commet).takeIf { !it.isEmpty() }
}

/** Serialize a status into the per-key JSON shapes each profile field expects. */
internal fun UserStatus.toProfileValues(): Map<String, Any> {
    // MSC4426 requires both fields, so an emoji-less status still carries an empty one.
    val msc = mapOf("text" to text, "emoji" to emoji)
    return mapOf(
            ProfileKeys.STATUS to msc,
            ProfileKeys.STATUS_UNSTABLE to msc,
            ProfileKeys.STATUS_COMMET to display(),
    )
}

internal fun UserBio.toProfileValues(): Map<String, Any> {
    // Richest first, as MSC1767 orders representations.
    val representations = buildList {
        if (!formattedBody.isNullOrBlank()) add(mapOf("body" to formattedBody, "mimetype" to MimeTypes.Html))
        add(mapOf("body" to body))
    }
    val msc = mapOf("m.text" to representations)
    return mapOf(
            ProfileKeys.BIOGRAPHY to msc,
            ProfileKeys.BIOGRAPHY_UNSTABLE to msc,
            ProfileKeys.BIOGRAPHY_COMMET to mapOf("body" to body),
    )
}

/** Serialize a pronoun list into the MSC4247 JSON array shape for a PUT. */
internal fun List<Pronoun>.toProfileValue(): List<Map<String, Any>> {
    return map { pronoun ->
        buildMap {
            put("summary", pronoun.summary)
            put("language", pronoun.language ?: "en")
            pronoun.subject?.let { put("subject", it) }
            pronoun.objectForm?.let { put("object", it) }
            pronoun.possessiveDeterminer?.let { put("possessive_determiner", it) }
            pronoun.possessivePronoun?.let { put("possessive_pronoun", it) }
            pronoun.reflexive?.let { put("reflexive", it) }
            pronoun.grammaticalGender?.let { put("grammatical_gender", it) }
        }
    }
}

/**
 * In-memory, per-session cache of the MSC4133 extended profile fields (time zone, pronouns, banner,
 * status, biography). These fields have no live store, so UIs seed synchronously from here and
 * gendered timeline notices read it best-effort. A cache miss triggers a single background fetch
 * that fills in on the next rebind.
 */
@SessionScope
internal class ExtendedProfileCache @Inject constructor(
        private val taskExecutor: TaskExecutor,
        private val getProfileInfoTask: GetProfileInfoTask,
) {

    // The last full field dict seen per user, so MSC4429/MSC4262 field-level deltas can be merged
    // into it and the parsed values re-derived.
    private val rawProfiles = ConcurrentHashMap<String, Map<String, Any>>()
    private val pronounsCache = ConcurrentHashMap<String, List<Pronoun>>()
    private val timezoneCache = ConcurrentHashMap<String, Optional<String>>()
    private val bannerUrlCache = ConcurrentHashMap<String, Optional<String>>()
    private val statusCache = ConcurrentHashMap<String, Optional<UserStatus>>()
    private val bioCache = ConcurrentHashMap<String, Optional<UserBio>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // Emits a userId once its pronouns become known, so UIs already showing the neutral fallback
    // (e.g. timeline "changed their avatar") can rebuild with the gendered wording.
    private val pronounsUpdates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val pronounsUpdateFlow: SharedFlow<String> = pronounsUpdates.asSharedFlow()

    // Emits a userId once their profile has been fetched, whatever it turned out to hold. A screen which
    // read the cache before the fetch landed — a profile opened for the first time — would otherwise show
    // nothing until it is opened again.
    private val profileUpdates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val profileUpdateFlow: SharedFlow<String> = profileUpdates.asSharedFlow()

    fun getCachedPronouns(userId: String): List<Pronoun>? = pronounsCache[userId]

    fun getCachedTimezone(userId: String): String? = timezoneCache[userId]?.getOrNull()

    fun getCachedBannerUrl(userId: String): String? = bannerUrlCache[userId]?.getOrNull()

    fun cacheBannerUrl(userId: String, bannerUrl: String?) {
        bannerUrlCache[userId] = Optional.from(bannerUrl)
    }

    fun getCachedStatus(userId: String): UserStatus? = statusCache[userId]?.getOrNull()

    fun getCachedBio(userId: String): UserBio? = bioCache[userId]?.getOrNull()

    fun cacheStatus(userId: String, status: UserStatus?) {
        statusCache[userId] = Optional.from(status?.takeIf { !it.isEmpty() })
    }

    fun cacheBio(userId: String, bio: UserBio?) {
        bioCache[userId] = Optional.from(bio?.takeIf { !it.isEmpty() })
    }

    fun cachePronouns(userId: String, pronouns: List<Pronoun>?) {
        val updated = pronouns.orEmpty()
        // Only signal when the value actually changed, so re-opening a profile doesn't rebuild its
        // timeline notices for nothing.
        val changed = pronounsCache.put(userId, updated) != updated
        if (changed && updated.isNotEmpty()) pronounsUpdates.tryEmit(userId)
    }

    fun cacheTimezone(userId: String, timezone: String?) {
        timezoneCache[userId] = Optional.from(timezone)
    }

    fun cacheFromProfile(userId: String, dict: JsonDict) {
        rawProfiles[userId] = dict
        cachePronouns(userId, dict.profilePronouns())
        cacheTimezone(userId, dict.profileTimezone())
        cacheBannerUrl(userId, dict.profileBannerUrl())
        cacheStatus(userId, dict.profileStatus())
        cacheBio(userId, dict.profileBio())
        profileUpdates.tryEmit(userId)
    }

    /**
     * Applies a MSC4429/MSC4262 delta: each entry replaces that field outright, and a null value
     * removes it. Fields not mentioned keep their previous value.
     */
    fun applyProfileUpdates(userId: String, updates: Map<String, Any?>) {
        if (updates.isEmpty()) return
        val merged = rawProfiles[userId].orEmpty().toMutableMap()
        updates.forEach { (field, value) ->
            if (value == null) merged.remove(field) else merged[field] = value
        }
        cacheFromProfile(userId, merged)
    }

    /** The server told us we no longer share a room with this user, so drop what we cached. */
    fun forget(userId: String) {
        rawProfiles.remove(userId)
        pronounsCache.remove(userId)
        timezoneCache.remove(userId)
        bannerUrlCache.remove(userId)
        statusCache.remove(userId)
        bioCache.remove(userId)
    }

    fun prefetch(userId: String) {
        if (pronounsCache.containsKey(userId) && timezoneCache.containsKey(userId)) return
        if (!inFlight.add(userId)) return
        taskExecutor.executorScope.launch {
            try {
                val dict = tryOrNull {
                    getProfileInfoTask.execute(GetProfileInfoTask.Params(userId, storeInDatabase = false))
                }
                if (dict != null) {
                    cacheFromProfile(userId, dict)
                } else {
                    // Cache the negative result so we don't hammer a failing/absent profile.
                    cachePronouns(userId, null)
                    cacheTimezone(userId, null)
                    cacheBannerUrl(userId, null)
                    cacheStatus(userId, null)
                    cacheBio(userId, null)
                }
            } finally {
                inFlight.remove(userId)
            }
        }
    }
}
