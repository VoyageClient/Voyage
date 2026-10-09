/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.action

import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.platform.EmptyAction
import im.vector.app.core.platform.EmptyViewEvents
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.core.utils.PerfTrace
import im.vector.app.features.home.room.detail.composer.spliceMentionIds
import im.vector.app.features.home.room.detail.timeline.helper.renderPerMessageProfile
import im.vector.app.features.home.room.detail.timeline.helper.withoutPerMessageProfileFallback
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContentBuilder
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.imagepack.ImagePackUsageFilter
import im.vector.app.features.media.FailedMediaTracker
import im.vector.app.features.pgp.PgpKeyStore
import im.vector.app.features.pgp.PgpResult
import im.vector.app.features.pgp.PgpServiceManager
import im.vector.app.features.pgp.PgpUtils
import im.vector.app.features.reactions.data.QuickReactionsDataSource
import im.vector.app.features.redaction.preservation.RedactedContentRepository
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.redaction.preservation.RedactedContentRevealManager
import im.vector.app.features.redaction.preservation.RedactionPreservationSettings
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.admin.ServerAdminStatusDataSource
import im.vector.app.features.share.isDirectRoom
import im.vector.app.features.translation.MessageTranslationStore
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupState
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContentForType
import org.matrix.android.sdk.api.session.events.model.getRootThreadEventId
import org.matrix.android.sdk.api.session.events.model.isAttachmentMessage
import org.matrix.android.sdk.api.session.events.model.isGalleryMessage
import org.matrix.android.sdk.api.session.events.model.isTextMessage
import org.matrix.android.sdk.api.session.events.model.isThread
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomPinnedEventsContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.forwardOrigin
import org.matrix.android.sdk.api.session.room.model.message.galleryCaption
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.isForward
import org.matrix.android.sdk.api.session.room.model.message.messageForwardPayload
import org.matrix.android.sdk.api.session.room.model.message.toAttachmentContentDict
import org.matrix.android.sdk.api.session.room.model.message.toForwardPayload
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastEditNewContent
import org.matrix.android.sdk.api.session.room.timeline.hasBeenEdited
import org.matrix.android.sdk.api.session.room.timeline.isPoll
import org.matrix.android.sdk.api.session.room.timeline.isRootThread
import org.matrix.android.sdk.api.session.room.timeline.isSticker
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap
import timber.log.Timber

/**
 * Information related to an event and used to display preview in contextual bottom sheet.
 */
class MessageActionsViewModel @AssistedInject constructor(
        @Assisted private val initialState: MessageActionState,
        private val session: Session,
        private val errorFormatter: ErrorFormatter,
        private val stringProvider: StringProvider,
        private val vectorPreferences: VectorPreferences,
        private val checkIfCanReplyEventUseCase: CheckIfCanReplyEventUseCase,
        private val checkIfCanRedactEventUseCase: CheckIfCanRedactEventUseCase,
        private val failedMediaTracker: FailedMediaTracker,
        private val pgpServiceManager: PgpServiceManager,
        private val pgpKeyStore: PgpKeyStore,
        private val imagePackProvider: ImagePackProvider,
        private val quickReactionsDataSource: QuickReactionsDataSource,
        private val redactionSettings: RedactionPreservationSettings,
        private val redactedContentRevealManager: RedactedContentRevealManager,
        private val redactedContentRepository: RedactedContentRepository,
        private val redactedContentRestorer: RedactedContentRestorer,
        private val serverAdminStatusDataSource: ServerAdminStatusDataSource,
        private val messageTranslationStore: MessageTranslationStore,
        private val translationSettings: im.vector.app.features.translation.TranslationSettings,
        private val previewBuilder: MessagePreviewContentBuilder,
        private val dimensionConverter: DimensionConverter,
) : VectorViewModel<MessageActionState, EmptyAction, EmptyViewEvents>(initialState) {

    private val informationData = initialState.informationData
    private val room = session.getRoom(initialState.roomId)

    private val eventIdFlow = MutableStateFlow(initialState.eventId)

    private var actionsJob: Job? = null

    private var computedInput: Pair<TimelineEvent, ActionPermissions>? = null

    // Pruning walks account data and room state — 80ms even on a fast device — so it stays off the
    // sheet's creation path.
    private val quickReactions = viewModelScope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
        pruneDeletedEmotes(quickReactionsDataSource.getQuickReactions())
    }

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<MessageActionsViewModel, MessageActionState> {
        override fun create(initialState: MessageActionState): MessageActionsViewModel
    }

    companion object : MavericksViewModelFactory<MessageActionsViewModel, MessageActionState> by hiltMavericksViewModelFactory()

    init {
        PerfTrace.time("longpress.vm.init") {
            // Seeded synchronously: without it the preview shows "-" until liveTimelineEvent's first
            // emission round-trips through the main thread.
            var seededEvent: TimelineEvent? = null
            if (room != null) {
                seededEvent = room.getTimelineEvent(initialState.eventId)
                seededEvent?.let { event ->
                    setState { copy(timelineEvent = Success(event)) }
                }
            }

            // liveAnnotationSummary's first emission is a few hundred ms out, and the seeded event already
            // carries the same reaction summary, so the quick reactions are on the first frame too.
            val storedQuickReactions = quickReactionsDataSource.getQuickReactions()
            if (storedQuickReactions.isNotEmpty()) {
                val seeded = storedQuickReactions.map { emoji ->
                    ToggleState(emoji, seededEvent?.annotations?.reactionsSummary?.firstOrNull { it.key == emoji }?.addedByMe ?: false)
                }
                setState { copy(quickStates = Success(seeded)) }
            }

            // On the main thread on purpose: these reads take ~15ms here and 70-100ms on a background
            // thread, waiting for the database lock this one already holds.
            val seededPermissions = PerfTrace.time("longpress.init.permissions") { readPermissions() }
            setState { copy(actionPermissions = seededPermissions) }

            // Off the seeds rather than through observeTimelineEventState's first emission, which arrives
            // via a main thread busy inflating the sheet — about 130ms later.
            seededEvent?.let { startActionsComputation(it, seededPermissions) }

            initialState.informationData.sharedByUserId?.let { sharedBy ->
                viewModelScope.launch(Dispatchers.Default) {
                    val displayName = room?.membershipService()?.getRoomMember(sharedBy)?.displayName
                    setState { copy(sharedByDisplayName = displayName?.takeIf { it.isNotBlank() } ?: sharedBy) }
                }
            }

            observeEvent()
            if (seededEvent == null) fetchMissingEvent()
            observeReactions()
            observePowerLevel()
            observeTimelineEventState()
        }
    }

    override fun handle(action: EmptyAction) = Unit

    private fun observePowerLevel() {
        if (room == null) {
            return
        }
        room.flow().liveRoomPowerLevels()
                .onEach { roomPowerLevels ->
                    val permissions = permissionsFrom(roomPowerLevels)
                    setState {
                        copy(actionPermissions = permissions)
                    }
                }.launchIn(viewModelScope)
    }

    private fun readPermissions(): ActionPermissions {
        val powerLevels = room?.stateService()?.getRoomPowerLevels() ?: return ActionPermissions()
        return permissionsFrom(powerLevels)
    }

    private fun permissionsFrom(powerLevels: RoomPowerLevels): ActionPermissions {
        val isJoined = room?.roomSummary()?.membership == Membership.JOIN
        return ActionPermissions(
                canSendMessage = isJoined && powerLevels.isUserAllowedToSend(session.myUserId, false, EventType.MESSAGE),
                canReact = isJoined && powerLevels.isUserAllowedToSend(session.myUserId, false, EventType.REACTION),
                canRedact = isJoined && powerLevels.isUserAbleToRedact(session.myUserId),
                canPinUnpin = isJoined && powerLevels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_PINNED_EVENT),
        )
    }

    private fun observeEvent() {
        if (room == null) return
        room.flow()
                .liveTimelineEvent(initialState.eventId)
                .unwrap()
                .execute {
                    copy(timelineEvent = it)
                }
    }

    // Server-side search results aren't in the timeline DB until something fetches them.
    private fun fetchMissingEvent() {
        val room = room ?: return
        viewModelScope.launch(Dispatchers.IO) {
            tryOrNull { session.eventService().ensureEventCached(room.roomId, initialState.eventId, requireTimelineEvent = true) }
            val event = room.getTimelineEvent(initialState.eventId) ?: return@launch
            if (event.root.getClearType() != EventType.ENCRYPTED) return@launch
            // Decryption only touches the event table, which liveTimelineEvent doesn't watch.
            session.eventService().requestDecryption(event.root)
            val decrypted = session.eventService().decryptionUpdates(room.roomId)
                    .onStart { emit(Unit) }
                    .mapNotNull { room.getTimelineEvent(initialState.eventId)?.takeIf { it.root.getClearType() != EventType.ENCRYPTED } }
                    .first()
            setState { copy(timelineEvent = Success(decrypted)) }
        }
    }

    private fun observeReactions() {
        if (room == null) return
        eventIdFlow
                .flatMapLatest { eventId ->
                    combine(quickReactionKeys(), room.flow().liveAnnotationSummary(eventId)) { keys, annotations ->
                        keys.map { emoji ->
                            ToggleState(emoji, annotations.getOrNull()?.reactionsSummary?.firstOrNull { it.key == emoji }?.addedByMe ?: false)
                        }
                    }
                }
                .execute {
                    copy(quickStates = it)
                }
    }

    // The stored list first and the pruned one only if pruning dropped an emote: resolving the image
    // packs takes long enough that the quick reactions would arrive after the rest of the sheet.
    private fun quickReactionKeys(): Flow<List<String>> = flow {
        val stored = quickReactionsDataSource.getQuickReactions()
        emit(stored)
        val pruned = quickReactions.await()
        if (pruned != stored) emit(pruned)
    }

    // Forget quick-reaction emotes whose image pack no longer has them (they'd render blank / send empty ::).
    private fun pruneDeletedEmotes(quickReactions: List<String>): List<String> {
        if (quickReactions.none { it.isMxcUrl() }) return quickReactions
        // The cached packs: this runs while the long-press sheet is being built, and resolving them
        // walks account data, room state and the parent spaces' state — a second on the main thread.
        val resolved = imagePackProvider.cachedImagePacks(initialState.roomId)
        if (resolved.isEmpty()) return quickReactions
        val validMxcs = ImagePackUsageFilter.emoticonPacks(imagePackProvider.enabledPacksOf(resolved))
                .flatMap { it.images }
                .mapTo(HashSet()) { it.mxcUrl }
        val pruned = quickReactions.filter { !it.isMxcUrl() || it in validMxcs }
        if (pruned.size != quickReactions.size) {
            quickReactionsDataSource.setQuickReactions(pruned)
        }
        return pruned
    }

    private fun observeTimelineEventState() {
        onEach(MessageActionState::timelineEvent, MessageActionState::actionPermissions) { timelineEvent, permissions ->
            val nonNullTimelineEvent = timelineEvent() ?: return@onEach
            startActionsComputation(nonNullTimelineEvent, permissions)
        }
    }

    @Synchronized
    private fun startActionsComputation(nonNullTimelineEvent: TimelineEvent, permissions: ActionPermissions) {
        // The seeds reach observeTimelineEventState as an emission of their own; recomputing the same
        // input would only throw away the pass that is already running (or done) for it.
        if (computedInput == nonNullTimelineEvent to permissions) return
        computedInput = nonNullTimelineEvent to permissions
        eventIdFlow.tryEmit(nonNullTimelineEvent.eventId)
        // A newer event or power level supersedes whatever is in flight, rather than both racing to
        // publish and each triggering its own model build.
        actionsJob?.cancel()
        // computeMessageBody runs the Markwon HTML render (tens to hundreds of ms on a slow device);
        // keep it off the main thread so the sheet opens/animates immediately instead of holding the
        // "-" preview placeholder while the main thread blocks on the render.
        actionsJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val restored = redactedContentRestorer.restoreEvent(nonNullTimelineEvent)
                // Actions read the raw event: which of Reveal/Hide/Edit/Redact apply depends on the
                // message still being redacted. Everything that renders content reads the restored one.
                val events = PerfTrace.timeSuspending("longpress.actions") {
                    actionsForEvent(nonNullTimelineEvent, permissions, restored)
                }
                // Before the body: that render is the expensive half and the actions must not wait on it.
                // Unchanged where the result is the same, because liveTimelineEvent re-emits an event equal
                // in everything the sheet reads but not by `equals`, and a new state means a model rebuild.
                setState {
                    if (eventId == nonNullTimelineEvent.eventId && restoredEvent == restored && actions == events) {
                        return@setState this
                    }
                    copy(
                            eventId = nonNullTimelineEvent.eventId,
                            restoredEvent = restored,
                            actions = events
                    )
                }
                val preview = PerfTrace.timeSuspending("longpress.body") {
                    computePreview(nonNullTimelineEvent, restored)
                }
                setState { if (this.preview?.rendersSameAs(preview) == true) this else copy(preview = preview) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // A throw here would otherwise die silently and leave the sheet empty.
                Timber.e(failure, "Failed to compute message actions for ${nonNullTimelineEvent.eventId}")
            }
        }
    }

    private suspend fun computePreview(timelineEvent: TimelineEvent, restored: TimelineEvent?): MessagePreviewContent {
        val shown = restored ?: timelineEvent
        return try {
            previewBuilder.build(
                    timelineEvent,
                    MessagePreviewContentBuilder.Options(
                            thumbnailMaxWidth = dimensionConverter.dpToPx(132),
                            thumbnailMaxHeight = dimensionConverter.dpToPx(66),
                            galleryMaxWidth = dimensionConverter.dpToPx(220),
                            galleryMaxHeight = dimensionConverter.dpToPx(220),
                            isThreadTimeline = initialState.isFromThreadTimeline,
                            galleryItemIndex = initialState.galleryItemIndex,
                            // Decrypted for real here, where the preview cache may not have it yet.
                            pgpPlaintext = if (messageTranslationStore.get(shown) == null) computePgpDecryptedBody(shown) else null,
                            linkify = false,
                            senderItem = initialState.informationData.matrixItem,
                    )
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            previewBuilder.notice(shown, errorFormatter.toHumanReadable(failure))
        }
    }

    // liveTimelineEvent re-emits an event equal in everything the sheet reads but not by `equals`, and every
    // render is a fresh Spannable, so compare what is drawn.
    private fun MessagePreviewContent.rendersSameAs(other: MessagePreviewContent): Boolean =
            drawnText() == other.drawnText() && withoutText(other.event) == other.withoutText(other.event)

    private fun MessagePreviewContent.drawnText() =
            listOf(text, captionlessText, richFooter, forwardFrame?.header, poll?.question).map { it?.charSequence?.toString() }

    private fun MessagePreviewContent.withoutText(event: TimelineEvent) = copy(
            event = event,
            text = null,
            captionlessText = null,
            richFooter = null,
            forwardFrame = forwardFrame?.copy(header = NO_TEXT),
            poll = poll?.copy(question = NO_TEXT),
    )

    private suspend fun actionsForEvent(
            timelineEvent: TimelineEvent,
            actionPermissions: ActionPermissions,
            restoredEvent: TimelineEvent? = null,
    ): List<EventSharedAction> {
        val messageContent = timelineEvent.getVectorLastMessageContent()
        val msgType = messageContent?.msgType

        return arrayListOf<EventSharedAction>().apply {
            val eventId = timelineEvent.eventId
            // Recovery actions for local echoes that haven't synced (or have failed) yet. No Remove or
            // Cancel: Redact is added below for every event and covers both — TimelineFragment routes it
            // to a local removal for a failed echo, and the SDK cancels a still-unsent one outright.
            if (timelineEvent.root.sendState.hasFailed() && canRetry(timelineEvent, actionPermissions)) {
                add(EventSharedAction.Resend(eventId))
            }
            // Then the full action set, regardless of sync/redaction state.
            addActionsForSyncedState(timelineEvent, actionPermissions, messageContent, msgType, restoredEvent)
        }
    }

    // Decrypted plaintext for a PGP-over-plaintext message, for the long-press preview; null when
    // not a PGP message, PGP disabled, or it can't be decrypted (then the raw body is shown).
    private suspend fun computePgpDecryptedBody(timelineEvent: TimelineEvent): CharSequence? {
        if (!pgpKeyStore.isEnabled || timelineEvent.isEncrypted()) return null
        val body = (timelineEvent.getVectorLastMessageContent() as? MessageTextContent)?.body ?: return null
        if (!PgpUtils.bodyContainsPgp(body)) return null
        val armored = PgpUtils.extractArmoredBlock(body) ?: return null
        return (pgpServiceManager.decrypt(armored) as? PgpResult.Success)?.data
    }

    // Text put on the clipboard by "Copy": the decrypted plaintext for a PGP message, otherwise the
    // real message with any legacy reply fallback stripped — so a (plaintext or encrypted) reply
    // copies what's shown, not the quoted "> <@user> …" original (which for a reply to an encrypted
    // message is a whole armored PGP block).
    private suspend fun pgpCopyBody(timelineEvent: TimelineEvent, messageContent: MessageContent, mentionsAsIds: Boolean = false): String {
        computePgpDecryptedBody(timelineEvent)?.let { return it.toString() }
        val profile = timelineEvent.renderPerMessageProfile(
                timelineEvent.senderInfo.disambiguatedDisplayName,
                vectorPreferences.arePerMessageProfilesEnabled()
        )
        val formatted = (messageContent as? MessageContentWithFormattedBody)
                ?.matrixFormattedBody
                ?.withoutPerMessageProfileFallback(profile.fallbackDisplayName)
        val rawBody = messageContent.body.withoutPerMessageProfileFallback(profile.fallbackDisplayName)
        val body = if (messageContent.relatesTo?.inReplyTo?.eventId != null) {
            ContentUtils.extractUsefulTextFromReply(rawBody, formatted)
        } else {
            rawBody
        }
        // Mentions read as names in the body; the clipboard carries the ids instead, which a composer
        // pills back into real mentions.
        return if (mentionsAsIds) spliceMentionIds(body, formatted?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }) else body
    }

    // The formatted body to feed HTML-aware translation; null for a PGP body (only its decrypted plaintext is translatable).
    private suspend fun translatableFormattedBody(timelineEvent: TimelineEvent, messageContent: MessageContent): String? {
        if (computePgpDecryptedBody(timelineEvent) != null) return null
        val profile = timelineEvent.renderPerMessageProfile(
                timelineEvent.senderInfo.disambiguatedDisplayName,
                vectorPreferences.arePerMessageProfilesEnabled()
        )
        val formatted = (messageContent as? MessageContentWithFormattedBody)
                ?.matrixFormattedBody
                ?.withoutPerMessageProfileFallback(profile.fallbackDisplayName)
                ?: return null
        return if (messageContent.relatesTo?.inReplyTo?.eventId != null) {
            ContentUtils.extractUsefulTextFromHtmlReply(formatted)
        } else {
            formatted
        }
    }

    private suspend fun ArrayList<EventSharedAction>.addViewSourceItems(timelineEvent: TimelineEvent, restoredEvent: TimelineEvent? = null) {
        // A revealed redaction shows the recovered content, not the pruned {}; hidden again, it goes
        // back to the redacted form.
        // The timeline withholds the bulky content of some state events (see BulkyStateEvents), so re-read
        // the stored event for the viewer rather than showing it the null it was handed.
        val sourceEvent = restoredEvent
                ?: timelineEvent.takeUnless { it.root.contentWithheld }
                ?: room?.timelineService()?.getTimelineEvent(timelineEvent.eventId)
                ?: timelineEvent
        add(EventSharedAction.ViewSource(sourceEvent.root.toContentStringWithIndent()))
        if (sourceEvent.isEncrypted() && sourceEvent.root.mxDecryptionResult != null) {
            val decryptedContent = sourceEvent.root.toClearContentStringWithIndent()
                    ?: stringProvider.getString(CommonStrings.encryption_information_decryption_error)
            add(EventSharedAction.ViewDecryptedSource(decryptedContent))
        } else {
            // PGP: "View decrypted source" = the event content with body/formatted_body rewritten
            // to the decrypted plaintext, shown in the same JSON viewer olm events use.
            pgpDecryptedContentJson(sourceEvent)?.let { add(EventSharedAction.ViewDecryptedSource(it)) }
        }
    }

    // Full event JSON (matching "View source") but with content.body/formatted_body rewritten to the
    // decrypted plaintext, mirroring how olm's "View decrypted source" shows the whole clear event.
    @Suppress("UNCHECKED_CAST")
    private suspend fun pgpDecryptedContentJson(timelineEvent: TimelineEvent): String? {
        val plain = computePgpDecryptedBody(timelineEvent)?.toString() ?: return null
        val eventMap = timelineEvent.root.toContent().toMutableMap()
        val contentMap = (eventMap["content"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        contentMap["body"] = plain
        (contentMap["formatted_body"] as? String)?.let { formatted ->
            // formatted_body carries its own armored block (the decrypted HTML), not a copy of body.
            val decryptedFormatted = PgpUtils.extractArmoredBlock(formatted)
                    ?.let { (pgpServiceManager.decrypt(it) as? PgpResult.Success)?.data }
            contentMap["formatted_body"] = decryptedFormatted ?: plain
        }
        eventMap["content"] = contentMap
        return JSONObject(eventMap as Map<*, *>).toString(4)
    }

    private suspend fun ArrayList<EventSharedAction>.addActionsForSyncedState(
            timelineEvent: TimelineEvent,
            actionPermissions: ActionPermissions,
            messageContent: MessageContent?,
            msgType: String?,
            restoredEvent: TimelineEvent? = null,
    ) {
        val eventId = timelineEvent.eventId
        val galleryItemIndex = initialState.galleryItemIndex
        if (messageContent is MessageGalleryContent && galleryItemIndex != null) {
            addActionsForGalleryItem(timelineEvent, messageContent, galleryItemIndex)
            return
        }
        // Reply / react / view-reactions are allowed even on redacted events.
        if (canReply(timelineEvent, messageContent, actionPermissions)) {
            add(EventSharedAction.Reply(eventId))
        }
        if (actionPermissions.canReact && !timelineEvent.root.sendState.hasFailed()) {
            add(EventSharedAction.AddReaction(eventId))
        }
        if (canViewReactions(timelineEvent)) {
            add(EventSharedAction.ViewReactions(informationData))
        }
        if (timelineEvent.root.isRedacted()) {
            // Hide applies to what the timeline is actually rendering; Reveal to what it could render.
            val isShowingContent = redactedContentRestorer.isShowingRestoredContent(timelineEvent)
            when {
                isShowingContent -> {
                    add(EventSharedAction.HideRedacted(eventId))
                    val restoredMessageContent = restoredEvent?.getVectorLastMessageContent()
                    if (restoredEvent != null && canCopy(restoredMessageContent?.msgType, restoredMessageContent)) {
                        add(EventSharedAction.Copy(pgpCopyBody(restoredEvent, restoredMessageContent!!, mentionsAsIds = true)))
                    }
                    if (restoredEvent != null) addTranslateAction(eventId, restoredEvent, restoredMessageContent)
                    if (restoredEvent != null && canForward(restoredEvent, restoredMessageContent?.msgType)) {
                        add(EventSharedAction.Forward(eventId, restoredEvent.root.getClearType(), restoredEvent.forwardPayload()))
                    }
                    if (restoredMessageContent != null && canShare(restoredMessageContent.msgType) && !isFailedMedia(restoredMessageContent)) {
                        add(EventSharedAction.Share(eventId, restoredMessageContent))
                    }
                }
                canRestoreRedactedContent(timelineEvent) -> add(EventSharedAction.RevealRedacted(eventId))
                else -> Unit
            }
        } else {
            if (canReplyInThread(timelineEvent, messageContent, actionPermissions)) {
                add(EventSharedAction.ReplyInThread(eventId, !timelineEvent.isRootThread()))
            }

            if (canViewInRoom(timelineEvent, messageContent, actionPermissions)) {
                add(EventSharedAction.ViewInRoom)
            }

            if (canEndPoll(timelineEvent, actionPermissions)) {
                add(EventSharedAction.EndPoll(timelineEvent.eventId))
            }

            if (canEdit(timelineEvent, session.myUserId, actionPermissions)) {
                add(EventSharedAction.Edit(eventId, timelineEvent.root.getClearType()))
            }

            if (canCopy(msgType, messageContent)) {
                // TODO copy images? html? see ClipBoard
                add(EventSharedAction.Copy(pgpCopyBody(timelineEvent, messageContent!!, mentionsAsIds = true)))
            }

            if (canRedact(timelineEvent, actionPermissions)) {
                val isPoll = timelineEvent.root.getClearType() in EventType.POLL_START.values
                add(
                        EventSharedAction.Redact(
                                eventId,
                                askForReason = informationData.senderId != session.myUserId,
                                dialogTitleRes = if (isPoll) CommonStrings.delete_poll_dialog_title else CommonStrings.redact_event_dialog_title,
                                dialogDescriptionRes = if (isPoll) CommonStrings.delete_poll_dialog_content else CommonStrings.redact_event_dialog_content
                        )
                )
            }

            addTranslateAction(eventId, timelineEvent, messageContent)

            if (timelineEvent.hasBeenEdited()) {
                add(EventSharedAction.ViewEditHistory(informationData))
            }

            if (canSave(msgType) && messageContent is MessageWithAttachmentContent && !isFailedMedia(messageContent)) {
                add(EventSharedAction.Save(timelineEvent.eventId, messageContent))
            }

            if (messageContent is MessageGalleryContent && !timelineEvent.root.sendState.hasFailed()) {
                val items = messageContent.galleryItems()
                if (items.isNotEmpty()) {
                    add(EventSharedAction.SaveAll(timelineEvent.eventId, items))
                }
            }

            if (canForward(timelineEvent, msgType)) {
                add(
                        EventSharedAction.Forward(
                                eventId = timelineEvent.eventId,
                                eventType = timelineEvent.root.getClearType(),
                                content = timelineEvent.forwardPayload()
                        )
                )
            }

            if (canShare(msgType) && !isFailedMedia(messageContent)) {
                add(EventSharedAction.Share(timelineEvent.eventId, messageContent!!))
            }

            if (actionPermissions.canPinUnpin && canPin(timelineEvent)) {
                if (eventId in getPinnedEventIds()) {
                    add(EventSharedAction.Unpin(eventId))
                } else {
                    add(EventSharedAction.Pin(eventId))
                }
            }
        }

        // A pin/unpin notice shows in every timeline, so its jump isn't developer-only.
        pinnedRelatedEventId(timelineEvent)?.let { add(EventSharedAction.JumpToRelation(eventId, it)) }

        if (vectorPreferences.developerMode()) {
            add(EventSharedAction.CopyEventId(eventId))
            relatedEventId(timelineEvent, initialState.isFromThreadTimeline)?.let {
                add(EventSharedAction.JumpToRelation(eventId, it, timelineEvent.root.getRootThreadEventId()))
            }
            if (timelineEvent.isEncrypted() && timelineEvent.root.mCryptoError != null) {
                val keysBackupService = session.cryptoService().keysBackupService()
                if (keysBackupService.getState() == KeysBackupState.NotTrusted ||
                        (keysBackupService.getState() == KeysBackupState.ReadyToBackUp &&
                                keysBackupService.canRestoreKeys())
                ) {
                    add(EventSharedAction.UseKeyBackup)
                }
                if (session.cryptoService().getCryptoDeviceInfoList(session.myUserId).size > 1 ||
                        timelineEvent.senderInfo.userId != session.myUserId) {
                    add(EventSharedAction.ReRequestKey(timelineEvent.eventId))
                }
            }
            addViewSourceItems(timelineEvent, restoredEvent)
        }
        add(EventSharedAction.CopyPermalink(eventId))
        if (session.myUserId != timelineEvent.root.senderId) {
            add(EventSharedAction.Separator)
            add(EventSharedAction.IgnoreUser(timelineEvent.root.senderId))
        }
    }

    private suspend fun ArrayList<EventSharedAction>.addTranslateAction(eventId: String, event: TimelineEvent, messageContent: MessageContent?) {
        if (messageContent == null || !canTranslate(messageContent.msgType, messageContent)) return
        when {
            messageTranslationStore.isTranslated(event) -> add(EventSharedAction.Untranslate(eventId))
            !messageTranslationStore.isTranslating(eventId) -> add(
                    EventSharedAction.Translate(
                            eventId,
                            MessageTranslationStore.sourceOf(event),
                            pgpCopyBody(event, messageContent),
                            translatableFormattedBody(event, messageContent),
                    )
            )
        }
    }

    private suspend fun ArrayList<EventSharedAction>.addActionsForGalleryItem(
            timelineEvent: TimelineEvent,
            messageContent: MessageGalleryContent,
            index: Int,
    ) {
        val item = messageContent.galleryItems().getOrNull(index) ?: return
        if (timelineEvent.root.isRedacted() || isFailedMedia(item)) return
        add(EventSharedAction.Save(timelineEvent.eventId, item))
        add(EventSharedAction.Share(timelineEvent.eventId, item))
        // The caption belongs to the whole gallery, but a tile is where most long presses land.
        addTranslateAction(timelineEvent.eventId, timelineEvent, messageContent)
        val itemContent = item.toAttachmentContentDict() ?: return
        val origin = timelineEvent.root.forwardOrigin(includeSourceLink = !session.isDirectRoom(timelineEvent.roomId))
        add(EventSharedAction.Forward(timelineEvent.eventId, EventType.MESSAGE, messageForwardPayload(itemContent, origin)))
    }

    private fun canReply(event: TimelineEvent, messageContent: MessageContent?, actionPermissions: ActionPermissions): Boolean {
        return checkIfCanReplyEventUseCase.execute(event, messageContent, actionPermissions)
    }

    /**
     * Determine whether or not the Reply In Thread bottom sheet action will be visible
     * to the user.
     */
    private fun canReplyInThread(
            event: TimelineEvent,
            messageContent: MessageContent?,
            actionPermissions: ActionPermissions
    ): Boolean {
        if (initialState.isFromThreadTimeline) return false
        if (event.root.isThread()) return false
        if (event.root.getClearType() != EventType.MESSAGE &&
                !event.isSticker() && !event.isPoll()) return false
        if (!actionPermissions.canSendMessage) return false
        // Last, as the only check here that reads the database. Threads stay offered while disabled (the
        // beta prompt), unless the homeserver cannot do them at all.
        if (!vectorPreferences.areThreadMessagesEnabled() &&
                !session.homeServerCapabilitiesService().getHomeServerCapabilities().canUseThreading) return false
        return when (messageContent?.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_GALLERY,
            MessageType.MSGTYPE_GALLERY_STABLE,
            MessageType.MSGTYPE_POLL_START,
            MessageType.MSGTYPE_STICKER_LOCAL -> true
            else -> false
        }
    }

    /**
     * Determine whether or not the view in room action will be available for the current event.
     */
    private fun canViewInRoom(
            event: TimelineEvent,
            messageContent: MessageContent?,
            actionPermissions: ActionPermissions
    ): Boolean {
        if (!vectorPreferences.areThreadMessagesEnabled()) return false
        if (!initialState.isFromThreadTimeline) return false
        if (event.root.getClearType() != EventType.MESSAGE &&
                !event.isSticker() && !event.isPoll()) return false
        if (!actionPermissions.canSendMessage) return false

        return when (messageContent?.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_GALLERY,
            MessageType.MSGTYPE_GALLERY_STABLE,
            MessageType.MSGTYPE_POLL_START,
            MessageType.MSGTYPE_POLL_END,
            MessageType.MSGTYPE_STICKER_LOCAL -> event.root.threadDetails?.isRootThread ?: false
            else -> false
        }
    }

    private fun canQuote(event: TimelineEvent, messageContent: MessageContent?, actionPermissions: ActionPermissions): Boolean {
        // Only event of type EventType.MESSAGE are supported for the moment
        if (event.root.getClearType() != EventType.MESSAGE) return false
        if (!actionPermissions.canSendMessage) return false
        return when (messageContent?.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION -> {
                true
            }
            else -> false
        }
    }

    private fun canRedact(event: TimelineEvent, actionPermissions: ActionPermissions): Boolean {
        return checkIfCanRedactEventUseCase.execute(event, actionPermissions)
    }

    /**
     * Offered only for redacted messages we could actually show: either a local copy was captured
     * before the redaction, or the server implements MSC2815 and we are privileged enough to ask.
     */
    private suspend fun canRestoreRedactedContent(event: TimelineEvent): Boolean {
        if (event.root.isStateEvent()) return false
        // An already-preserved copy needs no permission and no server support.
        if (redactedContentRepository.hasPreservedContent(event.eventId)) return true
        if (!session.homeServerCapabilitiesService().getHomeServerCapabilities().canViewUnredactedContent) return false
        val powerLevels = room?.stateService()?.getRoomPowerLevels() ?: return false
        // mayBeAdmin, not isAdmin: MSC2815 is not Synapse-only, but the admin probe is, so anywhere it
        // can't answer the capability flag decides and a refusal comes back as the usual snackbar.
        return powerLevels.isUserAbleToRedact(session.myUserId) || serverAdminStatusDataSource.cachedStatus().mayBeAdmin
    }

    private fun canRetry(event: TimelineEvent, actionPermissions: ActionPermissions): Boolean {
        return event.root.sendState.hasFailed() &&
                actionPermissions.canSendMessage &&
                (event.root.isAttachmentMessage() || event.root.isGalleryMessage() || event.root.isTextMessage())
    }

    private fun canViewReactions(event: TimelineEvent): Boolean {
        // Only event of type EventType.MESSAGE, EventType.STICKER, EventType.POLL_START, EventType.POLL_END are supported for the moment
        if (event.root.getClearType() !in listOf(EventType.MESSAGE, EventType.STICKER) + EventType.POLL_START.values + EventType.POLL_END.values) return false
        return event.annotations?.reactionsSummary?.isNotEmpty() ?: false
    }

    private fun canEdit(event: TimelineEvent, myUserId: String, actionPermissions: ActionPermissions): Boolean {
        if (!actionPermissions.canSendMessage) return false
        // TODO if user is admin or moderator
        if (event.root.senderId != myUserId) return false
        // MSC4553: readers ignore edits to a forward.
        if (event.root.getClearContent().isForward()) return false
        // Stickers: edit/add a caption.
        if (event.root.getClearType() == EventType.STICKER) return true
        // Only event of type EventType.MESSAGE and EventType.POLL_START are supported for the moment
        if (event.root.getClearType() !in listOf(EventType.MESSAGE) + EventType.POLL_START.values) return false
        val messageContent = event.root.getClearContent().toModel<MessageContent>()
        return when (messageContent?.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_EMOTE,
            // Media: edit/add a caption.
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_AUDIO -> true
            // A gallery captions itself the same way.
            in setOf(MessageType.MSGTYPE_GALLERY, MessageType.MSGTYPE_GALLERY_STABLE) -> true
            else -> canEditPoll(event)
        }
    }

    private fun canTranslate(msgType: String?, messageContent: MessageContent?): Boolean =
            (translationSettings.engine != null || translationSettings.backupEngine != null) &&
                    msgType != MessageType.MSGTYPE_LOCATION && canCopy(msgType, messageContent) && messageContent?.body?.isNotBlank() == true

    private fun canCopy(msgType: String?, messageContent: MessageContent? = null): Boolean {
        // Text-shaped messages: always copyable (body is the user-typed text).
        when (msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION -> return true
        }
        // Media with an MSC2530 caption: body is the caption — copyable.
        if (messageContent is MessageGalleryContent) return messageContent.galleryCaption() != null
        return (messageContent as? MessageWithAttachmentContent)?.getCaption() != null
    }

    private fun TimelineEvent.forwardPayload(): Map<String, Any?> =
            root.toForwardPayload(getLastEditNewContent(), includeSourceLink = !session.isDirectRoom(roomId))

    private fun canForward(event: TimelineEvent, msgType: String?): Boolean {
        if (event.root.getClearType() == EventType.STICKER) return true
        if (event.root.getClearType() != EventType.MESSAGE) return false
        return when (msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_GALLERY,
            MessageType.MSGTYPE_GALLERY_STABLE,
            MessageType.MSGTYPE_STICKER_LOCAL -> true
            else -> false
        }
    }

    // Nothing to hand to another app, and no bytes to write to storage. A format we downloaded but
    // cannot decode is a different case: it failed to *display*, while the file itself is right
    // there and worth keeping, so the actions stay.
    private fun isFailedMedia(messageContent: MessageContent?): Boolean {
        val content = messageContent as? MessageWithAttachmentContent ?: return false
        if (!failedMediaTracker.isFailed(content.getFileUrl())) return false
        val downloaded = session.fileService().getLocalFileFor(
                mxcUrl = content.getFileUrl(),
                fileName = content.body,
                mimeType = content.mimeType,
                isEncrypted = content.encryptedFileInfo != null
        )
        return downloaded == null
    }

    private fun canShare(msgType: String?): Boolean {
        return when (msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_STICKER_LOCAL -> true
            else -> false
        }
    }

    private fun canSave(msgType: String?): Boolean {
        return when (msgType) {
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_STICKER_LOCAL -> true
            else -> false
        }
    }

    private fun canPin(event: TimelineEvent): Boolean {
        if (event.root.isRedacted()) return false
        return event.root.getClearType() in listOf(EventType.MESSAGE, EventType.STICKER) + EventType.POLL_START.values
    }

    private fun getPinnedEventIds(): List<String> {
        return room?.stateService()
                ?.getStateEvent(EventType.STATE_ROOM_PINNED_EVENT, QueryStringValue.IsEmpty)
                ?.content
                .toModel<RoomPinnedEventsContent>()
                ?.pinned
                .orEmpty()
    }

    private fun canEndPoll(event: TimelineEvent, actionPermissions: ActionPermissions): Boolean {
        return event.root.getClearType() in EventType.POLL_START.values &&
                canRedact(event, actionPermissions) &&
                event.annotations?.pollResponseSummary?.closedTime == null
    }

    private fun canEditPoll(event: TimelineEvent): Boolean {
        return event.root.getClearType() in EventType.POLL_START.values &&
                event.annotations?.pollResponseSummary?.closedTime == null &&
                (event.annotations?.pollResponseSummary?.aggregatedContent?.totalVotes ?: 0) == 0
    }
}

internal fun TimelineEvent.jumpToRelationAction(developerMode: Boolean, isFromThreadTimeline: Boolean): EventSharedAction.JumpToRelation? {
    pinnedRelatedEventId(this)?.let { return EventSharedAction.JumpToRelation(eventId, it) }
    if (!developerMode) return null
    return relatedEventId(this, isFromThreadTimeline)?.let { EventSharedAction.JumpToRelation(eventId, it, root.getRootThreadEventId()) }
}

private fun relatedEventId(timelineEvent: TimelineEvent, isFromThreadTimeline: Boolean): String? {
    timelineEvent.root.getRelationContentForType(RelationType.REPLACE)?.eventId?.let { return it }
    val relatedEventId = when (timelineEvent.root.getClearType()) {
        // Room v11 moved redacts into the content.
        EventType.REDACTION -> timelineEvent.root.redacts ?: timelineEvent.root.content?.get("redacts") as? String
        EventType.REACTION -> timelineEvent.root.getClearContent().toModel<ReactionContent>()?.relatesTo?.eventId
        else -> null
    }
    return relatedEventId ?: timelineEvent.root.getRootThreadEventId()?.takeUnless { isFromThreadTimeline }
}

private fun pinnedRelatedEventId(timelineEvent: TimelineEvent): String? {
    if (timelineEvent.root.getClearType() != EventType.STATE_ROOM_PINNED_EVENT) return null
    val pinned = timelineEvent.root.getClearContent().toModel<RoomPinnedEventsContent>()?.pinned.orEmpty()
    val previous = timelineEvent.root.resolvedPrevContent().toModel<RoomPinnedEventsContent>()?.pinned.orEmpty()
    return (pinned - previous.toSet()).singleOrNull() ?: (previous - pinned.toSet()).singleOrNull()
}

private val NO_TEXT = EpoxyCharSequence("")
