/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.debug

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object DiagnosticContext {
    data class Origin internal constructor(val id: Long, val root: Long, val parent: Long?, val source: String)

    private val nextId = AtomicLong()
    private val local = ThreadLocal<Origin?>()

    fun current(): Origin? = if (DebugLog.enabled) local.get() else null

    fun newOrigin(source: String, parent: Origin? = current()): Origin? {
        if (!DebugLog.enabled) return null
        val id = nextId.incrementAndGet()
        return Origin(id, parent?.root ?: id, parent?.id, source)
    }

    fun context(origin: Origin? = current()): CoroutineContext =
            if (DebugLog.enabled && origin != null) local.asContextElement(origin) else EmptyCoroutineContext

    fun context(source: String): CoroutineContext = context(current() ?: newOrigin(source))

    fun describe(origin: Origin? = current()): String =
            "origin=${origin?.id} root=${origin?.root} parent=${origin?.parent} source=${origin?.source ?: "unattributed"}"

    fun <T> run(origin: Origin?, block: () -> T): T {
        if (!DebugLog.enabled || origin == null) return block()
        val previous = local.get()
        local.set(origin)
        try {
            return block()
        } finally {
            local.set(previous)
        }
    }

    suspend fun <T> withOrigin(source: String, block: suspend () -> T): T {
        if (!DebugLog.enabled) return block()
        return withContext(context(newOrigin(source))) { block() }
    }
}
