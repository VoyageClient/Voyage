/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.content

import android.os.Handler
import android.os.Looper
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker
import org.matrix.android.sdk.internal.session.SessionScope
import timber.log.Timber
import javax.inject.Inject

@SessionScope
internal class DefaultContentUploadStateTracker @Inject constructor() : ContentUploadStateTracker {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = mutableMapOf<String, ContentUploadStateTracker.State>()

    /** Which items of a gallery have finished, so the last one to land ends the whole send. */
    private val settledGalleryItems = mutableMapOf<String, MutableSet<Int>>()
    private val listeners = mutableMapOf<String, MutableList<ContentUploadStateTracker.UpdateListener>>()

    override fun track(key: String, updateListener: ContentUploadStateTracker.UpdateListener) {
        val listeners = listeners.getOrPut(key) { ArrayList() }
        listeners.add(updateListener)
        val currentState = states[key] ?: ContentUploadStateTracker.State.Idle
        mainHandler.post {
            try {
                updateListener.onUpdate(currentState)
            } catch (e: Exception) {
                Timber.e(e, "## ContentUploadStateTracker.onUpdate() failed")
            }
        }
    }

    override fun untrack(key: String, updateListener: ContentUploadStateTracker.UpdateListener) {
        listeners[key]?.apply {
            remove(updateListener)
        }
    }

    override fun clear() {
        listeners.clear()
        states.clear()
        settledGalleryItems.clear()
    }

    /**
     * One item of a gallery is done. Ends the send once every item has landed — a count rather than
     * a position, because the items upload independently and can finish in any order.
     */
    internal fun setGalleryItemSettled(key: String, itemIndex: Int, sizes: List<Long>) {
        val settled = settledGalleryItems.getOrPut(key) { mutableSetOf() }
        settled.add(itemIndex)
        if (settled.size >= sizes.size) {
            setSuccess(key)
        } else {
            val declared = sizes.getOrElse(itemIndex) { 0L }
            setGalleryProgress(key, itemIndex, sizes, declared, declared)
        }
    }

    internal fun setFailure(key: String, throwable: Throwable) {
        settledGalleryItems.remove(key)
        val failure = ContentUploadStateTracker.State.Failure(throwable)
        updateState(key, failure)
    }

    internal fun setSuccess(key: String) {
        settledGalleryItems.remove(key)
        val success = ContentUploadStateTracker.State.Success
        updateState(key, success)
    }

    internal fun setPreparingThumbnail(key: String) {
        updateState(key, ContentUploadStateTracker.State.PreparingThumbnail)
    }

    internal fun setEncryptingThumbnail(key: String) {
        val progressData = ContentUploadStateTracker.State.EncryptingThumbnail
        updateState(key, progressData)
    }

    internal fun setProgressThumbnail(key: String, current: Long, total: Long) {
        val progressData = ContentUploadStateTracker.State.UploadingThumbnail(current, total)
        updateState(key, progressData)
    }

    internal fun setEncrypting(key: String, current: Long, total: Long) {
        val progressData = ContentUploadStateTracker.State.Encrypting(current, total)
        updateState(key, progressData)
    }

    internal fun setCompressingImage(key: String) {
        val progressData = ContentUploadStateTracker.State.CompressingImage
        updateState(key, progressData)
    }

    internal fun setCompressingVideo(key: String, percent: Float) {
        val progressData = ContentUploadStateTracker.State.CompressingVideo(percent)
        updateState(key, progressData)
    }

    internal fun setProcessingVideo(key: String, percent: Float) {
        updateState(key, ContentUploadStateTracker.State.ProcessingVideo(percent))
    }

    internal fun setProcessingAudio(key: String) {
        updateState(key, ContentUploadStateTracker.State.ProcessingAudio)
    }

    /**
     * A gallery's items report from independent workers that interleave, so the aggregate is clamped
     * monotonic here. A non-positive [itemTotal] means the item is not uploading yet, and holds it
     * at the boundary of the items before it.
     */
    internal fun setGalleryProgress(key: String, itemIndex: Int, sizes: List<Long>, itemCurrent: Long, itemTotal: Long) {
        val previous = states[key]
        // A straggler must not paint over the verdict.
        if (previous is ContentUploadStateTracker.State.Success || previous is ContentUploadStateTracker.State.Failure) return
        val declared = sizes.getOrElse(itemIndex) { 0L }.coerceAtLeast(0L)
        val total = if (itemTotal > 0) itemTotal else declared.coerceAtLeast(1L)
        val current = itemCurrent.coerceIn(0L, total)
        val overallTotal = sizes.sumOf { it.coerceAtLeast(0L) }.coerceAtLeast(1L)
        // Double, not Long: the product of two byte counts overflows, and Double is exact well past
        // any size a homeserver will take.
        val done = sizes.take(itemIndex).sumOf { it.coerceAtLeast(0L) } + (declared.toDouble() * current / total).toLong()
        val overallCurrent = maxOf(done, (previous as? ContentUploadStateTracker.State.UploadingGalleryItem)?.overallCurrent ?: 0L)
        updateState(
                key,
                ContentUploadStateTracker.State.UploadingGalleryItem(itemIndex, sizes.size, current, total, overallCurrent, overallTotal)
        )
    }

    internal fun setProgress(key: String, current: Long, total: Long) {
        val progressData = ContentUploadStateTracker.State.Uploading(current, total)
        updateState(key, progressData)
    }

    private fun updateState(key: String, state: ContentUploadStateTracker.State) {
        states[key] = state
        mainHandler.post {
            listeners[key]?.forEach {
                try {
                    it.onUpdate(state)
                } catch (e: Exception) {
                    Timber.e(e, "## ContentUploadStateTracker.onUpdate() failed")
                }
            }
        }
    }
}
