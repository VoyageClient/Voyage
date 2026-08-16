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

package org.matrix.android.sdk.internal.session.room.send

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.isAttachmentMessage
import org.matrix.android.sdk.api.session.events.model.isTextMessage
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.PollType
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.session.room.model.relation.ReplyToContent
import org.matrix.android.sdk.api.session.room.send.SendService
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.api.util.CancelableBag
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.NoOpCancellable
import org.matrix.android.sdk.api.util.TextContent
import org.matrix.android.sdk.internal.crypto.store.IMXCommonCryptoStore
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.platform.BackgroundQueuePolicy
import org.matrix.android.sdk.internal.platform.BackgroundTaskRequest
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.platform.backgroundTask
import org.matrix.android.sdk.internal.session.content.PendingMediaUploadRegistry
import org.matrix.android.sdk.internal.session.content.UploadContentWorkerParams
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.matrix.android.sdk.internal.task.TaskExecutor

private const val UPLOAD_WORK = "UPLOAD_WORK"

/**
 * Tag shared by every background task belonging to one attachment send, so cancelling the send stops
 * both the upload chain and any deferred byte upload it queued.
 */
internal fun uploadWorkTag(eventId: String): String = "upload_${eventId}"

internal class DefaultSendService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val backgroundTaskScheduler: BackgroundTaskScheduler,
        @SessionId private val sessionId: String,
        @UserId private val userId: String,
        private val localEchoEventFactory: LocalEchoEventFactory,
        private val cryptoStore: IMXCommonCryptoStore,
        private val taskExecutor: TaskExecutor,
        private val localEchoRepository: LocalEchoRepository,
        private val eventSenderProcessor: EventSenderProcessor,
        private val cancelSendTracker: CancelSendTracker,
        private val pendingMediaUploadRegistry: PendingMediaUploadRegistry,
) : SendService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultSendService
    }

    override fun sendEvent(eventType: String, content: JsonDict?): Cancelable {
        return localEchoEventFactory.createEvent(roomId, eventType, content)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun sendTextMessage(text: CharSequence, msgType: String, autoMarkdown: Boolean, additionalContent: Content?): Cancelable {
        return localEchoEventFactory.createTextEvent(roomId, msgType, text, autoMarkdown, additionalContent)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun computeFormattedHtml(text: CharSequence, autoMarkdown: Boolean): String? {
        return localEchoEventFactory.computeFormattedHtml(text, autoMarkdown)
    }

    override fun sendFormattedTextMessage(text: String, formattedText: String, msgType: String, additionalContent: Content?): Cancelable {
        return localEchoEventFactory.createFormattedTextEvent(roomId, TextContent(text, formattedText), msgType, additionalContent)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun sendQuotedTextMessage(
            quotedEvent: TimelineEvent,
            text: String,
            formattedText: String?,
            autoMarkdown: Boolean,
            rootThreadEventId: String?,
            additionalContent: Content?,
    ): Cancelable {
        return localEchoEventFactory.createQuotedTextEvent(
                roomId = roomId,
                quotedEvent = quotedEvent,
                text = text,
                formattedText = formattedText,
                autoMarkdown = autoMarkdown,
                rootThreadEventId = rootThreadEventId,
                additionalContent = additionalContent,
        )
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun sendPoll(pollType: PollType, question: String, options: List<String>, additionalContent: Content?): Cancelable {
        return localEchoEventFactory.createPollEvent(roomId, pollType, question, options, additionalContent)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun voteToPoll(pollEventId: String, answerId: String, additionalContent: Content?): Cancelable {
        return localEchoEventFactory.createPollReplyEvent(roomId, pollEventId, answerId, additionalContent)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun endPoll(pollEventId: String, additionalContent: Content?): Cancelable {
        return localEchoEventFactory.createEndPollEvent(roomId, pollEventId, additionalContent)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun redactEvent(event: Event, reason: String?, withRelTypes: List<String>?, additionalContent: Content?): Cancelable {
        val targetId = event.eventId!!
        if (LocalEcho.isLocalEchoId(targetId) && localEchoRepository.getRemoteEchoId(targetId) == null) {
            // Never dispatched: redacting it means cancelling it — kill the send/upload and drop the
            // echo now instead of leaving the redaction queued behind a possibly minutes-long upload.
            cancelSend(targetId)
            return NoOpCancellable
        }
        val localEchoId = targetId.takeIf { LocalEcho.isLocalEchoId(it) }
                ?: event.unsignedData?.transactionId?.takeIf { LocalEcho.isLocalEchoId(it) }
        if (localEchoId != null) {
            // The event itself is out, but its media may still be uploading (MSC2246) — the redaction
            // makes those bytes pointless, so tear the upload down rather than let it finish. The
            // tracker mark is what makes the workers treat the stop as a user cancel, not a system
            // stop to retry.
            cancelSendTracker.markLocalEchoForCancel(localEchoId, roomId)
            backgroundTaskScheduler.cancelAllByTag(uploadWorkTag(localEchoId))
            pendingMediaUploadRegistry.discardForEvent(localEchoId)
        }
        val redactionEcho = localEchoEventFactory.createRedactEvent(roomId, targetId, reason, withRelTypes, additionalContent)
                .also { createLocalEcho(it) }
        return eventSenderProcessor.postRedaction(redactionEcho, reason, withRelTypes)
    }

    override fun resendTextMessage(localEcho: TimelineEvent): Cancelable {
        if (localEcho.root.isTextMessage() && localEcho.root.sendState.hasFailed()) {
            localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
            return sendEvent(localEcho.root)
        }
        return NoOpCancellable
    }

    override fun resendMediaMessage(localEcho: TimelineEvent): Cancelable {
        if (localEcho.root.sendState.hasFailed()) {
            val clearContent = localEcho.root.getClearContent()
            (clearContent?.toModel<MessageContent>() as? MessageGalleryContent)?.let { gallery ->
                // Re-running the upload chain against an existing gallery echo is not supported; only
                // a gallery whose uploads all landed (the event send itself failed) can be resent.
                return if (gallery.galleryItems().let { items -> items.isNotEmpty() && items.all { it.getFileUrl()?.isMxcUrl() == true } }) {
                    localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                    sendEvent(localEcho.root)
                } else {
                    NoOpCancellable
                }
            }
            val messageContent = clearContent?.toModel<MessageContent>() as? MessageWithAttachmentContent ?: return NoOpCancellable

            val url = messageContent.getFileUrl() ?: return NoOpCancellable
            if (url.isMxcUrl()) {
                // We need to resend only the message as the attachment is ok
                localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                return sendEvent(localEcho.root)
            }

            // we need to resend the media
            return when (messageContent) {
                is MessageImageContent -> {
                    // The image has not yet been sent
                    val info = messageContent.info!!
                    val attachmentData = ContentAttachmentData(
                            size = info.size,
                            mimeType = messageContent.mimeType,
                            width = info.width.toLong(),
                            height = info.height.toLong(),
                            name = messageContent.body,
                            queryUri = messageContent.url.orEmpty(),
                            type = ContentAttachmentData.Type.IMAGE
                    )
                    localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                    internalSendMedia(listOf(localEcho.root), attachmentData, true)
                }
                is MessageVideoContent -> {
                    val attachmentData = ContentAttachmentData(
                            size = messageContent.videoInfo?.size ?: 0L,
                            mimeType = messageContent.mimeType,
                            width = messageContent.videoInfo?.width?.toLong(),
                            height = messageContent.videoInfo?.height?.toLong(),
                            duration = messageContent.videoInfo?.duration?.toLong(),
                            name = messageContent.body,
                            queryUri = messageContent.url.orEmpty(),
                            type = ContentAttachmentData.Type.VIDEO
                    )
                    localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                    internalSendMedia(listOf(localEcho.root), attachmentData, true)
                }
                is MessageFileContent -> {
                    val attachmentData = ContentAttachmentData(
                            size = messageContent.info!!.size,
                            mimeType = messageContent.mimeType,
                            name = messageContent.getFileName(),
                            queryUri = messageContent.url.orEmpty(),
                            type = ContentAttachmentData.Type.FILE
                    )
                    localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                    internalSendMedia(listOf(localEcho.root), attachmentData, true)
                }
                is MessageAudioContent -> {
                    val attachmentData = ContentAttachmentData(
                            size = messageContent.audioInfo?.size ?: 0,
                            duration = messageContent.audioInfo?.duration?.toLong() ?: 0L,
                            mimeType = messageContent.mimeType,
                            name = messageContent.body,
                            queryUri = messageContent.url.orEmpty(),
                            type = ContentAttachmentData.Type.AUDIO,
                            waveform = messageContent.audioWaveformInfo?.waveform?.filterNotNull()
                    )
                    localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                    internalSendMedia(listOf(localEcho.root), attachmentData, true)
                }
                else -> NoOpCancellable
            }
        }
        return NoOpCancellable
    }

    override fun deleteFailedEcho(localEcho: TimelineEvent) {
        taskExecutor.executorScope.launch {
            localEchoRepository.deleteFailedEcho(roomId, localEcho)
        }
    }

    override fun cancelSend(eventId: String) {
        cancelSendTracker.markLocalEchoForCancel(eventId, roomId)
        // This is maybe the current task, so cancel it too
        eventSenderProcessor.cancel(eventId, roomId)
        // CancelSendTracker is in-memory only; the background upload chain is persistent, so without
        // this cancel a stuck upload would survive restarts and block every subsequent send.
        backgroundTaskScheduler.cancelAllByTag(uploadWorkTag(eventId))
        // That tag also covers any deferred byte upload, which is torn down before it can
        // release the bytes it was holding.
        pendingMediaUploadRegistry.discardForEvent(eventId)
        taskExecutor.executorScope.launch {
            localEchoRepository.deleteFailedEcho(roomId, eventId)
        }
    }

    override fun resendAllFailedMessages() {
        taskExecutor.executorScope.launch {
            val eventsToResend = localEchoRepository.getAllFailedEventsToResend(roomId)
            eventsToResend.forEach {
                if (it.root.isTextMessage()) {
                    resendTextMessage(it)
                } else if (it.root.isAttachmentMessage()) {
                    resendMediaMessage(it)
                }
            }
            localEchoRepository.updateSendState(roomId, eventsToResend.map { it.eventId }, SendState.UNSENT)
        }
    }

    override fun cancelAllFailedMessages() {
        taskExecutor.executorScope.launch {
            // Not getAllFailedEventsToResend: that filters to resendable message echoes, which drops
            // redaction echoes and leaves them stuck UNDELIVERED (red room warning, only cleared by cache wipe).
            localEchoRepository.getAllEventsWithStates(roomId, SendState.HAS_FAILED_STATES).forEach { event ->
                cancelSend(event.eventId)
            }
        }
    }

    override fun sendMedias(
            attachments: List<ContentAttachmentData>,
            compressBeforeSending: Boolean,
            roomIds: Set<String>,
            rootThreadEventId: String?,
            additionalContent: Content?,
            replyToEvent: TimelineEvent?,
            captionText: CharSequence?,
            captionFormattedText: String?,
            autoMarkdown: Boolean,
    ): Cancelable {
        return attachments.mapIndexedTo(CancelableBag()) { index, attachment ->
            sendMedia(
                    attachment = attachment,
                    compressBeforeSending = compressBeforeSending,
                    roomIds = roomIds,
                    rootThreadEventId = rootThreadEventId,
                    // Reply target + caption attach only to the first event; subsequent ones are standalone.
                    replyToEvent = if (index == 0) replyToEvent else null,
                    captionText = if (index == 0) captionText else null,
                    captionFormattedText = if (index == 0) captionFormattedText else null,
                    autoMarkdown = autoMarkdown,
            )
        }
    }

    override fun sendGallery(
            attachments: List<ContentAttachmentData>,
            compressBeforeSending: Boolean,
            roomIds: Set<String>,
            rootThreadEventId: String?,
            additionalContent: Content?,
            replyToEvent: TimelineEvent?,
            captionText: CharSequence?,
            captionFormattedText: String?,
            autoMarkdown: Boolean,
    ): Cancelable {
        if (attachments.isEmpty()) return NoOpCancellable
        val rootThreadId = if (roomIds.isNotEmpty()) null else rootThreadEventId
        val allRoomIds = (roomIds + roomId).toList()
        val replyToEventId = replyToEvent?.root?.eventId
        val effectiveRelatesTo = when {
            replyToEventId != null -> if (rootThreadId != null) {
                RelationDefaultContent(
                        type = RelationType.THREAD,
                        eventId = rootThreadId,
                        isFallingBack = false,
                        inReplyTo = ReplyToContent(eventId = replyToEventId),
                )
            } else {
                RelationDefaultContent(null, null, ReplyToContent(eventId = replyToEventId))
            }
            else -> null
        }
        val rootThreadForFactory = if (effectiveRelatesTo != null) null else rootThreadId
        val mentions = IntentionalMentions.build(
                body = captionText?.toString(),
                formattedBody = captionFormattedText,
                extraUserIds = listOfNotNull(replyToEvent?.root?.senderId),
                selfUserId = userId,
        )
        val allLocalEchoes = allRoomIds.map {
            localEchoEventFactory.createGalleryEvent(
                    roomId = it,
                    attachments = attachments,
                    rootThreadEventId = rootThreadForFactory,
                    relatesTo = effectiveRelatesTo,
                    additionalContent = additionalContent,
                    captionText = captionText,
                    captionFormattedText = captionFormattedText,
                    autoMarkdown = autoMarkdown,
                    mentions = mentions,
            ).also { event ->
                createLocalEcho(event)
            }
        }
        val cancelableBag = CancelableBag()
        allLocalEchoes.groupBy { cryptoStore.roomWasOnceEncrypted(it.roomId!!) }.forEach { (isRoomEncrypted, localEchoes) ->
            val localEchoIds = localEchoes.map { LocalEchoIdentifiers(it.roomId!!, it.eventId!!) }
            val itemSizes = attachments.map { it.size }
            // One upload work per item, all on the same FIFO queue, each patching its slot of the
            // shared echo; only the last one is chained to the dispatcher that sends the event.
            attachments.forEachIndexed { index, attachment ->
                val params = UploadContentWorkerParams(
                        sessionId = sessionId,
                        localEchoIds = localEchoIds,
                        attachment = attachment,
                        isEncrypted = isRoomEncrypted,
                        compressBeforeSending = compressBeforeSending,
                        galleryItemIndex = index,
                        galleryItemSizes = itemSizes,
                )
                val work = backgroundTask(
                        type = BackgroundTaskType.UPLOAD_CONTENT,
                        params = params,
                        matrixConstraints = true,
                        isolateInput = true,
                        extraTags = localEchoIds.map { uploadWorkTag(it.eventId) },
                )
                val handle = if (index == attachments.lastIndex) {
                    backgroundTaskScheduler.enqueueUniqueChain(
                            buildWorkName(UPLOAD_WORK),
                            BackgroundQueuePolicy.APPEND_OR_REPLACE,
                            work,
                            createMultipleEventDispatcherWork(isRoomEncrypted),
                    )
                } else {
                    backgroundTaskScheduler.enqueueUnique(buildWorkName(UPLOAD_WORK), BackgroundQueuePolicy.APPEND_OR_REPLACE, work)
                }
                cancelableBag.add(handle)
            }
        }
        return cancelableBag
    }

    override fun sendMedia(
            attachment: ContentAttachmentData,
            compressBeforeSending: Boolean,
            roomIds: Set<String>,
            rootThreadEventId: String?,
            relatesTo: RelationDefaultContent?,
            additionalContent: Content?,
            replyToEvent: TimelineEvent?,
            captionText: CharSequence?,
            captionFormattedText: String?,
            autoMarkdown: Boolean,
    ): Cancelable {
        // Ensure that the event will not be send in a thread if we are a different flow.
        // Like sending files to multiple rooms
        val rootThreadId = if (roomIds.isNotEmpty()) null else rootThreadEventId

        // Create an event with the media file path
        // Ensure current roomId is included in the set
        val allRoomIds = (roomIds + roomId).toList()

        val replyToEventId = replyToEvent?.root?.eventId
        val effectiveRelatesTo = when {
            relatesTo != null -> relatesTo
            replyToEventId != null -> if (rootThreadId != null) {
                RelationDefaultContent(
                        type = RelationType.THREAD,
                        eventId = rootThreadId,
                        isFallingBack = false,
                        inReplyTo = ReplyToContent(eventId = replyToEventId),
                )
            } else {
                RelationDefaultContent(null, null, ReplyToContent(eventId = replyToEventId))
            }
            else -> null
        }
        val rootThreadForFactory = if (effectiveRelatesTo != null) null else rootThreadId
        val mentions = IntentionalMentions.build(
                body = captionText?.toString(),
                formattedBody = captionFormattedText,
                extraUserIds = listOfNotNull(replyToEvent?.root?.senderId),
                selfUserId = userId,
        )

        // Create local echo for each room
        val allLocalEchoes = allRoomIds.map {
            localEchoEventFactory.createMediaEvent(
                    roomId = it,
                    attachment = attachment,
                    rootThreadEventId = rootThreadForFactory,
                    relatesTo = effectiveRelatesTo,
                    additionalContent = additionalContent,
                    captionText = captionText,
                    captionFormattedText = captionFormattedText,
                    autoMarkdown = autoMarkdown,
                    mentions = mentions,
            ).also { event ->
                createLocalEcho(event)
            }
        }
        return internalSendMedia(allLocalEchoes, attachment, compressBeforeSending)
    }

    /**
     * We use the roomId of the local echo event.
     */
    private fun internalSendMedia(allLocalEchoes: List<Event>, attachment: ContentAttachmentData, compressBeforeSending: Boolean): Cancelable {
        val cancelableBag = CancelableBag()

        allLocalEchoes.groupBy { cryptoStore.roomWasOnceEncrypted(it.roomId!!) }
                .apply {
                    keys.forEach { isRoomEncrypted ->
                        // Should never be empty
                        val localEchoes = get(isRoomEncrypted).orEmpty()
                        val uploadWork = createUploadMediaWork(localEchoes, attachment, isRoomEncrypted, compressBeforeSending)

                        val dispatcherWork = createMultipleEventDispatcherWork(isRoomEncrypted)

                        val handle = backgroundTaskScheduler.enqueueUniqueChain(
                                buildWorkName(UPLOAD_WORK),
                                BackgroundQueuePolicy.APPEND_OR_REPLACE,
                                uploadWork,
                                dispatcherWork,
                        )
                        cancelableBag.add(handle)
                    }
                }

        return cancelableBag
    }

    private fun sendEvent(event: Event): Cancelable {
        return eventSenderProcessor.postEvent(event)
    }

    private fun createLocalEcho(event: Event) {
        localEchoEventFactory.createLocalEcho(event)
    }

    private fun buildWorkName(identifier: String): String {
        return "${roomId}_$identifier"
    }

    private fun createUploadMediaWork(
            allLocalEchos: List<Event>,
            attachment: ContentAttachmentData,
            isRoomEncrypted: Boolean,
            compressBeforeSending: Boolean
    ): BackgroundTaskRequest<UploadContentWorkerParams> {
        val localEchoIds = allLocalEchos.map {
            LocalEchoIdentifiers(it.roomId!!, it.eventId!!)
        }
        val uploadMediaWorkerParams = UploadContentWorkerParams(sessionId, localEchoIds, attachment, isRoomEncrypted, compressBeforeSending)
        return backgroundTask(
                type = BackgroundTaskType.UPLOAD_CONTENT,
                params = uploadMediaWorkerParams,
                matrixConstraints = true,
                isolateInput = true,
                extraTags = localEchoIds.map { uploadWorkTag(it.eventId) },
        )
    }

    private fun createMultipleEventDispatcherWork(isRoomEncrypted: Boolean): BackgroundTaskRequest<MultipleEventSendingDispatcherWorkerParams> {
        // the list of events will be replaced by the result of the media upload work
        val params = MultipleEventSendingDispatcherWorkerParams(sessionId, emptyList(), isRoomEncrypted)
        return backgroundTask(
                type = BackgroundTaskType.MULTIPLE_EVENT_DISPATCHER,
                params = params,
        )
    }
}
