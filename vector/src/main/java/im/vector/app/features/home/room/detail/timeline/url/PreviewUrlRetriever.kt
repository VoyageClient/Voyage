/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.url

import im.vector.app.core.resources.BuildMeta
import im.vector.app.features.home.room.detail.timeline.helper.timelineStableId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.cache.CacheStrategy
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLatestEventId

class PreviewUrlRetriever(
        session: Session,
        private val coroutineScope: CoroutineScope,
        private val buildMeta: BuildMeta,
) {
    private val mediaService = session.mediaService()

    private data class EventIdPreviewUrlUiState(
            // Id of the latest event in the case of an edited event, or the eventId for an event which has not been edited
            val latestEventId: String,
            val previewUrlUiState: PreviewUrlUiState
    )

    // Keyed by the event's timelineStableId, so state survives the local-echo → remote-id swap.
    private val data = mutableMapOf<String, EventIdPreviewUrlUiState>()
    private val listeners = mutableMapOf<String, MutableSet<PreviewUrlRetrieverListener>>()

    // In memory list
    private val blockedUrl = mutableSetOf<String>()

    /**
     * @param allowServerFetch false to never hand a URL over to the homeserver, which is what the user asks
     * for by keeping URL previews off in encrypted rooms. Previews bundled in the event itself (MSC4095)
     * are displayed either way: they leak nothing.
     */
    fun getPreviewUrl(event: TimelineEvent, allowServerFetch: Boolean) {
        val eventId = event.timelineStableId()
        val latestEventId = event.getLatestEventId()

        synchronized(data) {
            val current = data[eventId]
            if (current?.latestEventId != latestEventId) {
                // The event is not known or it has been edited
                // Keep only the first URL for the moment
                val bundled = mediaService.extractBundledUrlPreviews(event)
                        ?.filter { canShowUrlPreview(it.matchedUrl) && it.matchedUrl !in blockedUrl && it.previewUrlData?.url !in blockedUrl }
                val bundledData = bundled?.firstNotNullOfOrNull { it.previewUrlData }
                val url = if (bundled == null) {
                    mediaService.extractUrls(event)
                            .firstOrNull { canShowUrlPreview(it) }
                            ?.takeIf { it !in blockedUrl }
                } else {
                    // The sender listed the URLs to preview: anything else stays unpreviewed.
                    bundled.firstOrNull()?.matchedUrl
                }
                if (bundledData != null) {
                    updateState(eventId, latestEventId, PreviewUrlUiState.Data(eventId, bundledData.url, bundledData))
                    null
                } else if (url == null || !allowServerFetch) {
                    updateState(eventId, latestEventId, PreviewUrlUiState.NoUrl)
                    null
                } else if (event.root.sendState.isSending()) {
                    // Our own message, still on its way: it carries its own preview (MSC4095), which lands
                    // with the remote echo in a moment. Asking the homeserver now would both waste the
                    // request and hand it the link the user may have chosen to keep from it.
                    null
                } else if (url != (current?.previewUrlUiState as? PreviewUrlUiState.Data)?.url) {
                    // There is a not known URL, or the Event has been edited and the URL has changed
                    updateState(eventId, latestEventId, PreviewUrlUiState.Loading)
                    url
                } else {
                    // Already handled
                    null
                }
            } else {
                // Already handled
                null
            }
        }?.let { urlToRetrieve ->
            coroutineScope.launch {
                runCatching {
                    mediaService.getPreviewUrl(
                            url = urlToRetrieve,
                            timestamp = null,
                            cacheStrategy = if (buildMeta.isDebug) CacheStrategy.NoCache else CacheStrategy.TtlCache(CACHE_VALIDITY, false)
                    )
                }.fold(
                        {
                            synchronized(data) {
                                // Blocked after the request has been sent?
                                if (urlToRetrieve in blockedUrl) {
                                    updateStateIfCurrent(eventId, latestEventId, PreviewUrlUiState.NoUrl)
                                } else {
                                    updateStateIfCurrent(eventId, latestEventId, PreviewUrlUiState.Data(eventId, urlToRetrieve, it))
                                }
                            }
                        },
                        {
                            synchronized(data) {
                                updateStateIfCurrent(eventId, latestEventId, PreviewUrlUiState.Error(it))
                            }
                        }
                )
            }
        }
    }

    private fun canShowUrlPreview(url: String): Boolean {
        return blockedDomains.all { !url.startsWith(it) }
    }

    fun doNotShowPreviewUrlFor(eventId: String, url: String) {
        blockedUrl.add(url)

        // Notify the listener
        synchronized(data) {
            data[eventId]
                    ?.takeIf { it.previewUrlUiState is PreviewUrlUiState.Data && it.previewUrlUiState.url == url }
                    ?.let {
                        updateState(eventId, it.latestEventId, PreviewUrlUiState.NoUrl)
                    }
        }
    }

    /**
     * A request answers for the event as it was when the request went out. By the time it lands the event
     * may have moved on — most often our own message, whose local echo asked the homeserver and whose
     * remote echo then arrived carrying its own bundled preview (MSC4095). The late answer, success or
     * failure, must not overwrite that.
     */
    private fun updateStateIfCurrent(eventId: String, latestEventId: String, state: PreviewUrlUiState) {
        if (data[eventId]?.latestEventId != latestEventId) return
        updateState(eventId, latestEventId, state)
    }

    private fun updateState(eventId: String, latestEventId: String, state: PreviewUrlUiState) {
        data[eventId] = EventIdPreviewUrlUiState(latestEventId, state)
        // Notify the listener
        coroutineScope.launch(Dispatchers.Main) {
            listeners[eventId].orEmpty().forEach {
                it.onStateUpdated(state)
            }
        }
    }

    // Called by the Epoxy item during binding
    fun addListener(key: String, listener: PreviewUrlRetrieverListener) {
        listeners.getOrPut(key) { mutableSetOf() }.add(listener)

        // Give the current state if any
        synchronized(data) {
            listener.onStateUpdated(data[key]?.previewUrlUiState ?: PreviewUrlUiState.Unknown)
        }
    }

    // Called by the Epoxy item during unbinding
    fun removeListener(key: String, listener: PreviewUrlRetrieverListener) {
        listeners[key]?.remove(listener)
    }

    interface PreviewUrlRetrieverListener {
        fun onStateUpdated(state: PreviewUrlUiState)
    }

    companion object {
        // One week in millis
        private const val CACHE_VALIDITY = 604_800_000L // 7 * 24 * 3_600 * 1_000

        private val blockedDomains = listOf(
                "https://matrix.to",
                "https://app.element.io",
                "https://staging.element.io",
                "https://develop.element.io"
        )
    }
}
