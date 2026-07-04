/*
 * Copyright (c) 2020 New Vector Ltd
 * Copyright (c) 2022 Beeper Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.home.room.detail.timeline.reply

import im.vector.app.features.home.room.detail.timeline.MessageColorProvider
import im.vector.app.features.home.room.detail.timeline.format.DisplayableEventFormatter
import im.vector.app.features.pgp.PgpDecryptor
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.SpanUtils
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.settings.MediaPreviewMode
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLatestEventId
import org.matrix.android.sdk.api.util.toMatrixItem
import timber.log.Timber
import java.util.UUID

/**
 * Resolves and caches the replied-to event for each reply in the timeline, exposing it as a
 * [PreviewReplyUiState] that [InReplyToView] renders. Adapted from SchildiChat; the blocking
 * timeline fetch SC relied on doesn't exist in this SDK, so we use [getTimelineEvent] and fall
 * back to [org.matrix.android.sdk.api.session.events.EventService.ensureEventCached].
 */
class ReplyPreviewRetriever(
        private val vectorPreferences: VectorPreferences,
        private val roomId: String,
        private val session: Session,
        private val coroutineScope: CoroutineScope,
        private val displayableEventFormatter: DisplayableEventFormatter,
        private val pillsPostProcessorFactory: PillsPostProcessor.Factory,
        private val textRendererFactory: EventTextRenderer.Factory,
        private val mediaContentRevealManager: MediaContentRevealManager,
        val messageColorProvider: MessageColorProvider,
        val htmlCompressor: VectorHtmlCompressor,
        val htmlRenderer: EventHtmlRenderer,
        val spanUtils: SpanUtils,
        val imageContentRenderer: ImageContentRenderer,
        val richMessageBodyRenderer: RichMessageBodyRenderer,
        val pgpDecryptor: PgpDecryptor,
) {
    private data class ReplyPreviewUiState(
            val latestRepliedToEventId: String?,
            val previewReplyUiState: PreviewReplyUiState
    )

    companion object {
        // Delay between attempts to fetch the replied-to event from the server, if it failed.
        private const val RETRY_SERVER_LOOKUP_INTERVAL_MS = 1000 * 30
        private val IgnoredAuthorException = Exception("Replied-to author is ignored")
    }

    private fun TimelineEvent.getCacheId(): String {
        return if (root.isRedacted()) {
            "REDACTED"
        } else {
            "L:${getLatestEventId()}"
        }
    }

    // Keys are the main eventId
    private val data = mutableMapOf<String, ReplyPreviewUiState>()
    private val listeners = mutableMapOf<String, MutableSet<PreviewReplyRetrieverListener>>()
    // Cache which replied-to events we already looked up successfully: key is main eventId, value is the getCacheId() value.
    private val lookedUpEvents = mutableMapOf<String, String>()
    // Timestamps of allowed server requests for individual events, to not spam the server.
    private val serverRequests = mutableMapOf<String, Long>()
    // eventToRetrieveId-specific locking
    private val retrieveEventLocks = mutableMapOf<String, Any>()
    // In-flight fetches keyed by parent eventId (value = target being fetched). Guards against launching a
    // second coroutine for a reply still loading: the retrieveEventLock only serializes the fetch, not the
    // state update, so a redundant fetch could land its (stale) result last and clobber a good preview.
    private val inFlightRetrievals = mutableMapOf<String, String>()
    // Refreshed once per snapshot; a reply whose author is ignored is shown as unavailable, not their text.
    @Volatile
    private var ignoredUserIds: Set<String> = emptySet()

    private val threadsEnabled = vectorPreferences.areThreadMessagesEnabled()

    fun invalidateEventsFromSnapshot(snapshot: List<TimelineEvent>) {
        ignoredUserIds = session.userService().getIgnoredUserIds().toSet()
        val snapshotEvents = snapshot.associateBy { it.eventId }
        synchronized(data) {
            for (eventId in lookedUpEvents.keys.toList()) {
                val cacheId = snapshotEvents[eventId]?.getCacheId()
                if (lookedUpEvents[eventId] != cacheId) {
                    lookedUpEvents.remove(eventId)
                }
            }
        }
    }

    val pillsPostProcessor by lazy {
        pillsPostProcessorFactory.create(roomId)
    }

    val textRenderer by lazy {
        textRendererFactory.create(roomId)
    }

    fun getReplyTo(event: TimelineEvent) {
        val eventId = event.root.eventId ?: return
        val now = System.currentTimeMillis()

        synchronized(data) {
            val current = data[eventId]

            val relationContent = event.root.getRelationContent()
            val repliedToEventId = relationContent?.inReplyTo?.eventId?.takeIf { relationContent.isFallingBack != true || !threadsEnabled }
            if (current == null || repliedToEventId != current.latestRepliedToEventId) {
                // We have not rendered this yet, or the replied-to event has updated
                if (repliedToEventId?.isNotEmpty().orFalse()) {
                    updateState(eventId, repliedToEventId, PreviewReplyUiState.ReplyLoading(repliedToEventId))
                    repliedToEventId
                } else {
                    updateState(eventId, repliedToEventId, PreviewReplyUiState.NoReply)
                    null
                }
            } else {
                val currentState = current.previewReplyUiState
                if (currentState is PreviewReplyUiState.InReplyTo && currentState.event.root.senderId in ignoredUserIds) {
                    // The replied-to author has since been ignored: drop the cached preview so it re-renders
                    // as unavailable (and can resolve again if they're un-ignored).
                    repliedToEventId?.let { lookedUpEvents.remove(it) }
                    updateState(eventId, repliedToEventId, PreviewReplyUiState.Error(IgnoredAuthorException, repliedToEventId))
                    null
                } else if (repliedToEventId in lookedUpEvents) {
                    // Nothing changed... but the replied-to event might have been edited or decrypted in the meantime
                    null
                } else {
                    repliedToEventId
                }
            }
        }?.let { eventIdToRetrieve ->
            val shouldLaunch = synchronized(data) {
                if (inFlightRetrievals[eventId] == eventIdToRetrieve) {
                    false
                } else {
                    inFlightRetrievals[eventId] = eventIdToRetrieve
                    true
                }
            }
            if (!shouldLaunch) return
            coroutineScope.launch(Dispatchers.IO) {
                val retrieveEventLock = synchronized(retrieveEventLocks) {
                    retrieveEventLocks.getOrPut(eventIdToRetrieve) { eventIdToRetrieve }
                }
                try {
                runCatching {
                    // Don't spam the server too often if it doesn't know the event
                    val mayAskServerForEvent = synchronized(serverRequests) {
                        val lastAttempt = serverRequests[eventIdToRetrieve]
                        if (lastAttempt == null || lastAttempt < now - RETRY_SERVER_LOOKUP_INTERVAL_MS) {
                            serverRequests[eventIdToRetrieve] = now
                            true
                        } else {
                            false
                        }
                    }
                    synchronized(retrieveEventLock) {
                        val room = session.getRoom(roomId)
                        var timelineEvent = room?.getTimelineEvent(eventIdToRetrieve)
                        if (timelineEvent == null && mayAskServerForEvent) {
                            runBlocking { session.eventService().ensureEventCached(roomId, eventIdToRetrieve) }
                            timelineEvent = room?.getTimelineEvent(eventIdToRetrieve)
                                    ?: session.eventService().getEventFromCache(roomId, eventIdToRetrieve)?.toFallbackTimelineEvent()
                        }
                        timelineEvent
                    }?.apply {
                        // Decrypt synchronously if needed
                        val repliedToEvent = root
                        if (repliedToEvent.isEncrypted() && repliedToEvent.mxDecryptionResult == null) {
                            try {
                                val result = runBlocking {
                                    session.cryptoService().decryptEvent(root, root.roomId + UUID.randomUUID().toString())
                                }
                                repliedToEvent.mxDecryptionResult = OlmDecryptionResult(
                                        payload = result.clearEvent,
                                        senderKey = result.senderCurve25519Key,
                                        keysClaimed = result.claimedEd25519Key?.let { k -> mapOf("ed25519" to k) },
                                        forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain,
                                        verificationState = result.messageVerificationState,
                                )
                            } catch (e: MXCryptoError) {
                                Timber.w("Failed to decrypt event in reply")
                            }
                        }
                    }
                }.fold(
                        {
                            synchronized(data) {
                                updateState(eventId, eventIdToRetrieve,
                                        when {
                                            it == null -> PreviewReplyUiState.Error(Exception("Event not found"), eventIdToRetrieve)
                                            it.root.senderId in ignoredUserIds -> PreviewReplyUiState.Error(IgnoredAuthorException, eventIdToRetrieve)
                                            else -> PreviewReplyUiState.InReplyTo(eventIdToRetrieve, it, it.senderInfo.disambiguatedDisplayName)
                                        }
                                )
                            }
                        },
                        {
                            synchronized(data) {
                                updateState(eventId, eventIdToRetrieve, PreviewReplyUiState.Error(it, eventIdToRetrieve))
                            }
                        }
                )
                } finally {
                    synchronized(data) {
                        if (inFlightRetrievals[eventId] == eventIdToRetrieve) inFlightRetrievals.remove(eventId)
                    }
                }
            }
        }
    }

    private fun Event.toFallbackTimelineEvent(): TimelineEvent? {
        val evId = eventId ?: return null
        val sender = senderId.orEmpty()
        val member = session.roomService().getRoomMember(sender, this@ReplyPreviewRetriever.roomId)
        return TimelineEvent(
                root = this,
                localId = evId.hashCode().toLong(),
                eventId = evId,
                displayIndex = 0,
                senderInfo = SenderInfo(sender, member?.displayName, isUniqueDisplayName = true, member?.avatarUrl)
        )
    }

    private fun updateState(eventId: String, latestRepliedToEventId: String?, state: PreviewReplyUiState) {
        data[eventId] = ReplyPreviewUiState(latestRepliedToEventId, state)
        if (state is PreviewReplyUiState.InReplyTo) {
            if (state.event.isEncrypted() && state.event.root.mxDecryptionResult == null) {
                // Do not cache encrypted events, so we try again on next update
                lookedUpEvents.remove(state.repliedToEventId)
            } else {
                lookedUpEvents[state.repliedToEventId] = state.event.getCacheId()
            }
        }
        // Notify the listener
        coroutineScope.launch(Dispatchers.Main) {
            listeners[eventId].orEmpty().forEach {
                it.onStateUpdated(state)
            }
        }
    }

    // Called by the Epoxy item during binding
    fun addListener(key: String, listener: PreviewReplyRetrieverListener) {
        listeners.getOrPut(key) { mutableSetOf() }.add(listener)
        // Give the current state if any
        synchronized(data) {
            listener.onStateUpdated(data[key]?.previewReplyUiState ?: PreviewReplyUiState.NoReply)
        }
    }

    // Called by the Epoxy item during unbinding
    fun removeListener(key: String, listener: PreviewReplyRetrieverListener) {
        listeners[key]?.remove(listener)
    }

    interface PreviewReplyRetrieverListener {
        fun onStateUpdated(state: PreviewReplyUiState)
    }

    fun getMemberNameColor(event: TimelineEvent): Int {
        return messageColorProvider.getMemberNameTextColor(event.senderInfo.toMatrixItem())
    }

    val useSolidColorForHiddenMedia: Boolean
        get() = vectorPreferences.useSolidColorForHiddenMedia()

    /** Whether a media thumbnail in a reply preview should be hidden (blurhash / solid) per the
     *  room's media-preview setting, mirroring the main timeline. Honours a prior in-timeline reveal. */
    fun shouldHideMediaPreview(event: TimelineEvent): Boolean {
        if (event.senderInfo.userId == session.myUserId) return false
        val hideByMode = when (vectorPreferences.getMediaPreviewMode()) {
            MediaPreviewMode.ALWAYS_SHOW -> false
            MediaPreviewMode.ALWAYS_HIDE -> true
            MediaPreviewMode.PRIVATE -> !isRoomPrivate()
            MediaPreviewMode.DIRECT -> session.roomService().getRoomSummary(roomId)?.isDirect != true
        }
        return hideByMode && !mediaContentRevealManager.isRevealed(event.eventId)
    }

    private fun isRoomPrivate(): Boolean {
        return when (session.roomService().getRoomSummary(roomId)?.joinRules) {
            RoomJoinRules.INVITE,
            RoomJoinRules.KNOCK,
            RoomJoinRules.RESTRICTED,
            RoomJoinRules.PRIVATE -> true
            else -> false
        }
    }

    fun formatFallbackReply(event: TimelineEvent): CharSequence {
        return displayableEventFormatter.format(
                event,
                // Sender information is rendered separately, so omit it from the text.
                isDm = false,
                appendAuthor = false,
                unhandledFallback = true,
        )
    }
}
