/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.os.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Returns a flow that invokes the given action after the first value of the upstream flow is emitted downstream.
 */
fun <T> Flow<T>.onFirst(action: (T) -> Unit): Flow<T> = flow {
    var emitted = false
    collect { value ->
        emit(value) // always emit value

        if (!emitted) {
            action(value) // execute the action after the first emission
            emitted = true
        }
    }
}

/**
 * Collects into de-duplicated batches, flushing once the stream has been quiet for [quietMs] or once
 * [maxDeferMs] has passed since the batch opened — whichever comes first.
 *
 * The point over a plain `debounce`: debounce is purely trailing and unbounded, so a stream that
 * keeps trickling defers the flush for as long as it lasts. [maxDeferMs] caps that, while [quietMs]
 * still collapses a burst into few flushes rather than one per item.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <T> Flow<T>.collectBatched(
        quietMs: Long,
        maxDeferMs: Long,
        onBatch: suspend (List<T>) -> Unit,
) = coroutineScope {
    val items = Channel<T>(Channel.UNLIMITED)
    launch {
        collect { items.send(it) }
        items.close()
    }
    val batch = LinkedHashSet<T>()
    while (true) {
        val first = items.receiveCatching().getOrNull() ?: break
        batch.add(first)
        val deadline = SystemClock.uptimeMillis() + maxDeferMs
        while (true) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) break
            // select, not withTimeoutOrNull around a receive: that can take an element off the channel and
            // then report a timeout, dropping it. Exactly one clause here wins.
            val next = select<T?> {
                items.onReceiveCatching { it.getOrNull() }
                onTimeout(minOf(quietMs, remaining)) { null }
            } ?: break
            batch.add(next)
        }
        onBatch(batch.toList())
        batch.clear()
    }
}
