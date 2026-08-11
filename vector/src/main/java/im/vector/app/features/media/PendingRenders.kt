/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import im.vector.app.R
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * Instrumentation for media that stays on its blurhash: records every Glide request the timeline
 * issues and reports the ones that never come back, with the app-wide backlog at that moment.
 */
object PendingRenders {

    class Token(val id: Long, val describe: String) {
        val startedAt = SystemClock.uptimeMillis()

        @Volatile
        var done = false
    }

    private val nextId = AtomicLong()
    private val handler = Handler(Looper.getMainLooper())
    private val pending = LinkedHashMap<Long, Token>()
    private var lastDumpAt = 0L

    /** Ties the token to the view, so a recycle or a rebind retires it instead of reporting it stuck. */
    fun startOn(imageView: ImageView, data: ImageContentRenderer.Data, mode: ImageContentRenderer.Mode): Token {
        cancelOn(imageView)
        val token = Token(nextId.incrementAndGet(), "event=${data.eventId} mode=$mode url=${data.url}")
        synchronized(pending) { pending[token.id] = token }
        Timber.i("MEDIADBG render start #${token.id} ${token.describe}")
        handler.postDelayed({ report(token) }, STUCK_AFTER_MS)
        imageView.setTag(R.id.image_renderer_pending_render, token)
        return token
    }

    fun cancelOn(imageView: ImageView) {
        (imageView.getTag(R.id.image_renderer_pending_render) as? Token)
                ?.takeIf { !it.done }
                ?.let { finish(it, "abandoned") }
        imageView.setTag(R.id.image_renderer_pending_render, null)
    }

    fun finish(token: Token, outcome: String) {
        token.done = true
        synchronized(pending) { pending.remove(token.id) }
        Timber.i("MEDIADBG render end #${token.id} ${SystemClock.uptimeMillis() - token.startedAt}ms $outcome — ${token.describe}")
    }

    private fun report(token: Token) {
        if (token.done) return
        val outstanding = synchronized(pending) { pending.values.toList() }
        Timber.w(
                "MEDIADBG render STUCK #${token.id} for ${SystemClock.uptimeMillis() - token.startedAt}ms ${token.describe}" +
                        " — ${outstanding.size} outstanding: " +
                        outstanding.joinToString { "#${it.id}(${SystemClock.uptimeMillis() - it.startedAt}ms)" }
        )
        dumpGlideThreads()
    }

    /**
     * Glide runs every disk-cache-backed request on one small pool; if it blocks, nothing that
     * consults the cache ever completes. Print what its threads are doing, so a stuck load names
     * its blocker instead of needing a thread dump caught live.
     */
    private fun dumpGlideThreads() {
        val now = SystemClock.uptimeMillis()
        if (now - lastDumpAt < DUMP_INTERVAL_MS) return
        lastDumpAt = now
        Thread.getAllStackTraces()
                .filterKeys { it.name.contains("glide", ignoreCase = true) }
                .forEach { (thread, frames) ->
                    Timber.w(
                            "MEDIADBG thread ${thread.name} ${thread.state}\n" +
                                    frames.take(STACK_FRAMES).joinToString("\n") { "    at $it" }
                    )
                }
    }

    private const val STUCK_AFTER_MS = 15_000L
    private const val DUMP_INTERVAL_MS = 60_000L
    private const val STACK_FRAMES = 16
}
