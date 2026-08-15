/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.style.mediaCornerTransformation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds an attachment of our own out of the timeline for the few frames its thumbnail takes to
 * decode, so a send does not put an empty row on screen and fill it in afterwards.
 *
 * A send is never blocked on this: the row appears once the decode settles either way, and at the
 * latest after [DECODE_TIMEOUT_MS], so media that cannot be decoded at all still shows up (with
 * whatever the renderer makes of it) rather than vanishing.
 */
@Singleton
class SendingMediaGate @Inject constructor(
        @ApplicationContext private val context: Context,
        private val imageContentRenderer: ImageContentRenderer,
) {

    /** Set by the timeline while it is attached, so a settled decode can put the row back. */
    var onRequestBuild: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    // Reached from the timeline's background build thread as well as from the decode's own callback
    // on the main one.
    private val lock = Any()
    private val settled = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > MAX_REMEMBERED
    }
    private val inFlight = mutableSetOf<String>()

    /** False only while the first decode of a still-sending attachment is outstanding. */
    fun canShow(data: ImageContentRenderer.Data, mode: ImageContentRenderer.Mode, messageLayout: TimelineMessageLayout): Boolean {
        val key = data.eventId
        val alreadyAsked = synchronized(lock) {
            if (settled.containsKey(key)) return true
            !inFlight.add(key)
        }
        if (!alreadyAsked) {
            val cornerTransformation = messageLayout.mediaCornerTransformation(context)
            // Glide only starts requests on the main thread, and models are built off it.
            handler.post { imageContentRenderer.preloadLocalEcho(data, mode, cornerTransformation) { settle(key) } }
            handler.postDelayed({ settle(key) }, DECODE_TIMEOUT_MS)
        }
        return false
    }

    /**
     * Whether this event is one being held back, so what stands in for it is a blank row rather than
     * the "no factory handled it" debug item.
     */
    fun isHolding(eventId: String?): Boolean {
        eventId ?: return false
        return synchronized(lock) { inFlight.contains(eventId) }
    }

    /** Account switch: a decode still in flight would otherwise hold its row blank forever. */
    fun clearAll() {
        handler.removeCallbacksAndMessages(null)
        synchronized(lock) {
            settled.clear()
            inFlight.clear()
        }
        onRequestBuild = null
    }

    private fun settle(key: String) {
        synchronized(lock) {
            if (settled.put(key, true) != null) return
            inFlight.remove(key)
        }
        onRequestBuild?.invoke()
    }

    companion object {
        private const val DECODE_TIMEOUT_MS = 1_500L
        private const val MAX_REMEMBERED = 128
    }
}
