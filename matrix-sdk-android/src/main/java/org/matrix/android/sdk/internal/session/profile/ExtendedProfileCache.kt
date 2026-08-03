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
import org.matrix.android.sdk.api.session.profile.Pronoun
import org.matrix.android.sdk.api.session.profile.ProfileKeys
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.task.TaskExecutor
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

// Stable key preferred over unstable.
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
 * In-memory, per-session cache of MSC4175/MSC4247 profile fields. These fields have no live store,
 * so UIs seed synchronously from here and gendered timeline notices read it best-effort. A cache
 * miss triggers a single background fetch that fills in on the next rebind.
 */
@SessionScope
internal class ExtendedProfileCache @Inject constructor(
        private val taskExecutor: TaskExecutor,
        private val getProfileInfoTask: GetProfileInfoTask,
) {

    private val pronounsCache = ConcurrentHashMap<String, List<Pronoun>>()
    private val timezoneCache = ConcurrentHashMap<String, Optional<String>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // Emits a userId once its pronouns become known, so UIs already showing the neutral fallback
    // (e.g. timeline "changed their avatar") can rebuild with the gendered wording.
    private val pronounsUpdates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val pronounsUpdateFlow: SharedFlow<String> = pronounsUpdates.asSharedFlow()

    fun getCachedPronouns(userId: String): List<Pronoun>? = pronounsCache[userId]

    fun getCachedTimezone(userId: String): String? = timezoneCache[userId]?.getOrNull()

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
        cachePronouns(userId, dict.profilePronouns())
        cacheTimezone(userId, dict.profileTimezone())
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
                }
            } finally {
                inFlight.remove(userId)
            }
        }
    }
}
