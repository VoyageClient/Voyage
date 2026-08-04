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
     * All mirrors to try, the one that last answered first, and the ones known to be down last.
     */
    fun candidates(): List<String> {
        val all = candidates
        val ordered = listOf(active) + all.filter { it != active }
        val now = System.nanoTime()
        return ordered.sortedBy { if ((downUntil[it] ?: 0L) > now) 1 else 0 }
    }

    fun onReached(base: String) {
        active = base
        downUntil.remove(base)
    }

    fun markDown(base: String) {
        downUntil[base] = System.nanoTime() + DOWN_TTL_NANOS
    }

    fun update(urls: List<String>) {
        val newCandidates = normalize(urls)
        candidates = newCandidates
        downUntil.clear()
        if (active !in newCandidates) {
            active = newCandidates.first()
        }
    }

    private fun normalize(urls: List<String>) = urls.map { it.ensureTrailingSlash() }.distinct()

    companion object {
        private val DOWN_TTL_NANOS = TimeUnit.SECONDS.toNanos(30)
    }
}
