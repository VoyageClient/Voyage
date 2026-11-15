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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker.State
import org.matrix.android.sdk.internal.session.SessionScope
import timber.log.Timber
import javax.inject.Inject

@SessionScope
internal class DefaultContentUploadStateTracker @Inject constructor(
        coroutineDispatchers: MatrixCoroutineDispatchers,
) : ContentUploadStateTracker {

    private class TransferProgress {
        var current = 0L
        var total = -1L
        var lastReported = 0L
        var isRetrying = false
        var isAwaitingResponse = false

        fun update(reported: Long, length: Long) {
            val next = if (length > 0) reported.coerceIn(0L, length) else reported.coerceAtLeast(0L)
            if (next < lastReported) isRetrying = true
            if (next >= current) isRetrying = false
            // Retransmitted bytes must catch up before the visible position advances again.
            current = maxOf(current, next)
            total = length
            lastReported = next
            isAwaitingResponse = false
        }
    }

    private class GalleryProgress(val sizes: List<Long>) {
        val items = mutableMapOf<Int, TransferProgress>()
        val settled = mutableSetOf<Int>()
    }

    private val lock = Any()
    private val mainScope = CoroutineScope(SupervisorJob() + coroutineDispatchers.main)
    private val states = mutableMapOf<String, State>()
    private val uploads = mutableMapOf<String, TransferProgress>()
    private val thumbnails = mutableMapOf<String, TransferProgress>()
    private val galleries = mutableMapOf<String, GalleryProgress>()
    private val listeners = mutableMapOf<String, MutableSet<ContentUploadStateTracker.UpdateListener>>()
    private val pendingUpdates = mutableMapOf<String, Any>()

    override fun track(key: String, updateListener: ContentUploadStateTracker.UpdateListener) {
        synchronized(lock) {
            listeners.getOrPut(key) { linkedSetOf() }.add(updateListener)
            DebugLog.i { "UPLOADDBG transfer=${key.hashCode().toString(16)} track listeners=${listeners[key]?.size}" }
            notifyListeners(key)
        }
    }

    override fun untrack(key: String, updateListener: ContentUploadStateTracker.UpdateListener) {
        synchronized(lock) {
            listeners[key]?.let {
                it.remove(updateListener)
                if (it.isEmpty()) listeners.remove(key)
            }
            DebugLog.i { "UPLOADDBG transfer=${key.hashCode().toString(16)} untrack listeners=${listeners[key]?.size ?: 0}" }
        }
    }

    override fun clear() {
        synchronized(lock) {
            DebugLog.i { "UPLOADDBG clear states=${states.size} listeners=${listeners.size}" }
            listeners.clear()
            states.clear()
            uploads.clear()
            thumbnails.clear()
            galleries.clear()
            pendingUpdates.clear()
        }
    }

    internal fun reset(key: String) {
        synchronized(lock) {
            uploads.remove(key)
            thumbnails.remove(key)
            galleries.remove(key)
            states.remove(key)
            DebugLog.i { "UPLOADDBG transfer=${key.hashCode().toString(16)} reset" }
            updateState(key, State.Idle)
        }
    }

    internal fun setGalleryItemSettled(key: String, itemIndex: Int, sizes: List<Long>) {
        synchronized(lock) {
            if (isTerminal(key) || itemIndex !in sizes.indices) return
            val gallery = galleries.getOrPut(key) { GalleryProgress(sizes.toList()) }
            gallery.settled.add(itemIndex)
            DebugLog.i { "UPLOADDBG transfer=${key.hashCode().toString(16)} item=$itemIndex settled=${gallery.settled.size}/${sizes.size}" }
            if (gallery.settled.size == sizes.size) {
                updateState(key, State.Success)
            } else {
                publishGallery(key, gallery, itemIndex)
            }
        }
    }

    internal fun setFailure(key: String, throwable: Throwable) = updateState(key, State.Failure(throwable))

    internal fun setSuccess(key: String) = updateState(key, State.Success)

    internal fun setPreparingThumbnail(key: String) = updateState(key, State.PreparingThumbnail)

    internal fun setEncryptingThumbnail(key: String) = updateState(key, State.EncryptingThumbnail)

    internal fun setProgressThumbnail(key: String, current: Long, total: Long) {
        changeUpload(key, thumbnail = true) { it.update(current, total) }
    }

    internal fun setEncrypting(key: String, current: Long, total: Long) = updateState(key, State.Encrypting(current, total))

    internal fun setCompressingImage(key: String) = updateState(key, State.CompressingImage)

    internal fun setCompressingVideo(key: String, percent: Float) = updateState(key, State.CompressingVideo(percent))

    internal fun setProcessingVideo(key: String, percent: Float) = updateState(key, State.ProcessingVideo(percent))

    internal fun setProcessingAudio(key: String) = updateState(key, State.ProcessingAudio)

    internal fun setGalleryProgress(key: String, itemIndex: Int, sizes: List<Long>, itemCurrent: Long, itemTotal: Long) {
        changeUpload(key, itemIndex = itemIndex, sizes = sizes) {
            // Preparation and thumbnail phases do not send any of the gallery item's main bytes.
            if (itemTotal > 0) it.update(itemCurrent, itemTotal)
        }
    }

    internal fun setProgress(key: String, current: Long, total: Long) {
        changeUpload(key) { it.update(current, total) }
    }

    internal fun setUploadStarted(key: String, isRetry: Boolean, thumbnail: Boolean = false, itemIndex: Int? = null, sizes: List<Long>? = null) {
        changeUpload(key, thumbnail, itemIndex, sizes) {
            it.isRetrying = isRetry || it.current > 0
            it.isAwaitingResponse = false
            it.lastReported = 0
        }
    }

    internal fun setUploadRetrying(key: String, thumbnail: Boolean = false, itemIndex: Int? = null, sizes: List<Long>? = null) {
        changeUpload(key, thumbnail, itemIndex, sizes) {
            it.isRetrying = true
            it.isAwaitingResponse = false
        }
    }

    internal fun setUploadAwaitingResponse(key: String, thumbnail: Boolean = false, itemIndex: Int? = null, sizes: List<Long>? = null) {
        changeUpload(key, thumbnail, itemIndex, sizes) {
            it.isRetrying = false
            it.isAwaitingResponse = true
        }
    }

    private fun changeUpload(
            key: String,
            thumbnail: Boolean = false,
            itemIndex: Int? = null,
            sizes: List<Long>? = null,
            change: (TransferProgress) -> Unit,
    ) {
        synchronized(lock) {
            if (isTerminal(key)) return
            if (itemIndex != null && sizes != null && itemIndex in sizes.indices) {
                val gallery = galleries.getOrPut(key) { GalleryProgress(sizes.toList()) }
                if (itemIndex in gallery.settled) return
                change(gallery.items.getOrPut(itemIndex) { TransferProgress() })
                publishGallery(key, gallery, itemIndex)
            } else {
                val progress = (if (thumbnail) thumbnails else uploads).getOrPut(key) { TransferProgress() }
                change(progress)
                updateState(key, if (thumbnail) {
                    State.UploadingThumbnail(progress.current, progress.total, progress.isRetrying, progress.isAwaitingResponse)
                } else {
                    State.Uploading(progress.current, progress.total, progress.isRetrying, progress.isAwaitingResponse)
                })
            }
        }
    }

    private fun publishGallery(key: String, gallery: GalleryProgress, itemIndex: Int) {
        val sizes = gallery.sizes.mapIndexed { index, declared ->
            if (declared > 0) declared else gallery.items[index]?.total?.coerceAtLeast(0L) ?: 0L
        }
        val hasUnknownSize = sizes.indices.any { sizes[it] == 0L && it !in gallery.settled }
        val overallTotal = if (hasUnknownSize) 0L else sizes.sum()
        val overallCurrent = sizes.indices.sumOf { index ->
            val progress = gallery.items[index]
            when {
                index in gallery.settled -> sizes[index]
                progress == null || progress.total <= 0 -> 0L
                // Multiplying two byte counts as Long can overflow for large attachments.
                else -> (sizes[index].toDouble() * progress.current / progress.total).toLong().coerceIn(0L, sizes[index])
            }
        }
        val item = gallery.items[itemIndex]
        val unfinished = sizes.indices.filterNot { it in gallery.settled }
        updateState(key, State.UploadingGalleryItem(
                itemIndex = itemIndex,
                itemCount = sizes.size,
                current = item?.current ?: 0L,
                total = item?.total ?: sizes[itemIndex],
                overallCurrent = overallCurrent,
                overallTotal = overallTotal,
                completedCount = gallery.settled.size,
                isRetrying = unfinished.any { gallery.items[it]?.isRetrying == true },
                isAwaitingResponse = unfinished.isNotEmpty() && unfinished.all { gallery.items[it]?.isAwaitingResponse == true },
        ))
    }

    private fun isTerminal(key: String) = states[key] is State.Success || states[key] is State.Failure

    private fun updateState(key: String, state: State) {
        synchronized(lock) {
            if (isTerminal(key)) return
            val previous = states.put(key, state)
            if (previous == state) return
            if (state is State.Success || state is State.Failure) {
                uploads.remove(key)
                thumbnails.remove(key)
                galleries.remove(key)
            }
            if (DebugLog.enabled && diagnosticPhase(previous) != diagnosticPhase(state)) {
                DebugLog.i { "UPLOADDBG transfer=${key.hashCode().toString(16)} state=${diagnosticPhase(state)}" }
            }
            notifyListeners(key)
        }
    }

    private fun diagnosticPhase(state: State?): String = when (state) {
        is State.Uploading -> "upload bucket=${bucket(state.current, state.total)} retry=${state.isRetrying} awaiting=${state.isAwaitingResponse}"
        is State.UploadingThumbnail -> "thumbnail bucket=${bucket(state.current, state.total)} retry=${state.isRetrying} awaiting=${state.isAwaitingResponse}"
        is State.UploadingGalleryItem -> "gallery bucket=${bucket(state.overallCurrent, state.overallTotal)} settled=${state.completedCount}/${state.itemCount} retry=${state.isRetrying} awaiting=${state.isAwaitingResponse}"
        is State.Failure -> "failure type=${state.throwable.javaClass.simpleName}"
        else -> state?.javaClass?.simpleName ?: "none"
    }

    private fun bucket(current: Long, total: Long) = if (total > 0) (current.toDouble() * 10 / total).toInt() else -1

    private fun notifyListeners(key: String) {
        if (pendingUpdates.containsKey(key)) return
        val token = Any()
        pendingUpdates[key] = token
        mainScope.launch {
            synchronized(lock) {
                if (pendingUpdates[key] !== token) return@launch
                pendingUpdates.remove(key)
                listeners[key]?.toList()?.forEach { listener ->
                    if (listeners[key]?.contains(listener) == true) {
                        try {
                            listener.onUpdate(states[key] ?: State.Idle)
                        } catch (e: Exception) {
                            Timber.e(e, "## ContentUploadStateTracker.onUpdate() failed")
                        }
                    }
                }
            }
        }
    }
}
