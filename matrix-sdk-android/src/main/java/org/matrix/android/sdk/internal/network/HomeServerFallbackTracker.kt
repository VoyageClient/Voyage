/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.ensureTrailingSlash
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Holds the ordered homeserver mirrors [HomeServerFallbackInterceptor] fails over between, and which one
 * is currently answering.
 */
@SessionScope
internal class HomeServerFallbackTracker @Inject constructor(sessionParams: SessionParams) {

    /**
     * The base every outgoing URL is built from: Retrofit and the content-URL resolver are wired with the
     * config as it was at session creation, so this stays fixed for the lifetime of the session even after
     * the user reorders the list.
     */
    val requestBase: String = sessionParams.homeServerConnectionConfig.homeServerUriBase.ensureTrailingSlash()

    @Volatile private var candidates: List<String> = sessionParams.homeServerConnectionConfig
            .let { normalize(listOf(it.homeServerUriBase) + it.fallbackHomeServerUriBases) }

    @Volatile private var active: String = candidates.first()

    private val downUntil = ConcurrentHashMap<String, Long>()

    private val outages = ConcurrentHashMap<String, Int>()

    /**
     * All mirrors in the order the user configured them.
     */
    fun configured(): List<String> = candidates

    /**
     * Whether there is anything to fail over to. Checked before [candidates] on every request, so the
     * common single-URL case does no work at all.
     */
    fun hasMirrors(): Boolean = candidates.size > 1

    /**
     * The mirror currently being used.
     */
    fun active(): String = active

    /**
     * All mirrors to try, in the order the user configured them, the ones known to be down last. The order
     * is deliberately not anchored on [active]: a request is what discovers that a higher-ranked mirror is
     * back, so pinning it to the one that took over would make a fallback permanent between probes.
     */
    fun candidates(): List<String> {
        val now = System.nanoTime()
        return candidates.sortedBy { if (isDown(it, now)) 1 else 0 }
    }

    fun onReached(base: String) {
        downUntil.remove(base)
        outages.remove(base)
        val all = candidates
        val baseRank = all.indexOf(base)
        if (baseRank < 0) return
        // A response that was in flight on a lower-ranked mirror must not demote a healthy
        // higher-ranked one — the probe's switch back would be undone by every completing long-poll.
        val currentRank = all.indexOf(active)
        if (currentRank in 0 until baseRank && !isDown(active, System.nanoTime())) return
        if (active != base) Timber.i("Homeserver mirror in use is now $base")
        active = base
    }

    /**
     * Each successive outage doubles how long the mirror is skipped, up to four minutes, so one that stays
     * down does not cost a request a connect timeout every half minute. Failures piling up inside a single
     * outage count once.
     */
    @Synchronized
    fun markDown(base: String) {
        val now = System.nanoTime()
        val outage = if (isDown(base, now)) (outages[base] ?: 1) else (outages[base] ?: 0) + 1
        outages[base] = outage
        downUntil[base] = now + (DOWN_TTL_NANOS shl (outage - 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS))
    }

    fun update(urls: List<String>) {
        val newCandidates = normalize(urls)
        candidates = newCandidates
        downUntil.clear()
        outages.clear()
        if (active !in newCandidates) {
            active = newCandidates.first()
        }
    }

    private fun isDown(base: String, now: Long) = (downUntil[base] ?: 0L) > now

    private fun normalize(urls: List<String>) = urls.map { it.ensureTrailingSlash() }.distinct()

    companion object {
        private val DOWN_TTL_NANOS = TimeUnit.SECONDS.toNanos(30)
        private const val MAX_BACKOFF_DOUBLINGS = 3
    }
}
