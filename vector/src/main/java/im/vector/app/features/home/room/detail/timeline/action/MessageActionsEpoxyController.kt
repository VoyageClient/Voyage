/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.action

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Success
import im.vector.app.EmojiCompatFontProvider
import im.vector.app.R
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.epoxy.bottomSheetDividerItem
import im.vector.app.core.epoxy.bottomsheet.BottomSheetQuickReactionsItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetActionItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetMessagePreviewItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetQuickReactionsItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetSendStateItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.sendFailureReason
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.MessageColorProvider
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.LocationPinProvider
import im.vector.app.features.home.room.detail.timeline.item.E2EDecoration
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.media.shouldHideMediaPreview
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import javax.inject.Inject

/**
 * Epoxy controller for message action list.
 */
class MessageActionsEpoxyController @Inject constructor(
        private val stringProvider: StringProvider,
        private val avatarRenderer: AvatarRenderer,
        private val messageColorProvider: MessageColorProvider,
        private val fontProvider: EmojiCompatFontProvider,
        private val imageContentRenderer: ImageContentRenderer,
        private val errorFormatter: ErrorFormatter,
        val redactedContentRestorer: RedactedContentRestorer,
        private val vectorPreferences: VectorPreferences,
        private val dateFormatter: VectorDateFormatter,
        private val locationPinProvider: LocationPinProvider,
        private val activeSessionHolder: ActiveSessionHolder,
        private val mediaContentRevealManager: MediaContentRevealManager,
        private val richMessageBodyRenderer: RichMessageBodyRenderer,
        private val pillsPostProcessorFactory: PillsPostProcessor.Factory,
) : TypedEpoxyController<MessageActionState>() {

    var listener: MessageActionsEpoxyControllerListener? = null

    override fun buildModels(state: MessageActionState) {
        val host = this
        // Message preview
        val date = state.timelineEvent()?.root?.originServerTs
        val formattedDate = dateFormatter.format(date, DateFormatKind.MESSAGE_DETAIL)
        // Linkified here, not by the builder: only the sheet's links are live, and they need the listener.
        val preview = state.preview?.let { preview ->
            if (preview.linkifiable) preview.copy(text = preview.text?.charSequence?.linkify(host.listener)?.toEpoxyCharSequence()) else preview
        }
        // Everything above the first divider describes the message itself, so it all carries the mark —
        // but only once the redacted content is actually being shown, not over the placeholder.
        val showsRestoredContent = state.timelineEvent()?.let { host.redactedContentRestorer.isShowingRestoredContent(it) } == true

        bottomSheetMessagePreviewItem {
            id("preview")
            avatarRenderer(host.avatarRenderer)
            matrixItem(state.informationData.matrixItem)
            senderColor(host.messageColorProvider.getMemberNameTextColor(state.informationData.matrixItem))
            senderIsColored(host.messageColorProvider.isMemberNameColored())
            preview(preview)
            movementMethod(createLinkMovementMethod(host.listener))
            imageContentRenderer(host.imageContentRenderer)
            hideMedia(
                    host.activeSessionHolder.getSafeActiveSession()?.let { session ->
                        state.previewEvent?.let { shouldHideMediaPreview(it, session, host.vectorPreferences, host.mediaContentRevealManager) }
                    }.orFalse()
            )
            hideMediaSolidColor(host.vectorPreferences.useSolidColorForHiddenMedia())
            userClicked { host.listener?.didSelectMenuAction(EventSharedAction.OpenUserProfile(state.informationData.senderId)) }
            redactedTint(showsRestoredContent)
            time(formattedDate)
            locationPinProvider(host.locationPinProvider)
            richBodyRenderer(host.richMessageBodyRenderer)
            htmlPostProcessors(arrayOf(host.pillsPostProcessorFactory.create(state.roomId)))
        }

        // Send state
        val sendState = state.sendState()
        if (sendState?.hasFailed().orFalse()) {
            // Get more details about the error
            val root = state.timelineEvent()?.root
            val errorMessage = root?.sendFailureReason(errorFormatter)
                    ?: stringProvider.getString(CommonStrings.unable_to_send_message)
            bottomSheetSendStateItem {
                id("send_state")
                redactedTint(showsRestoredContent)
                showProgress(false)
                text(errorMessage)
                drawableStart(R.drawable.ic_warning_badge)
            }
        } else if (sendState?.isSending().orFalse()) {
            bottomSheetSendStateItem {
                id("send_state")
                redactedTint(showsRestoredContent)
                showProgress(true)
                accentTint(true)
                text(host.stringProvider.getString(CommonStrings.event_status_sending_message))
            }
        }

        when (state.informationData.e2eDecoration) {
            E2EDecoration.WARN_IN_CLEAR -> {
                bottomSheetSendStateItem {
                    id("e2e_clear")
                    redactedTint(showsRestoredContent)
                    showProgress(false)
                    text(host.stringProvider.getString(CommonStrings.unencrypted))
                    drawableStart(R.drawable.ic_shield_warning_small)
                }
            }
            E2EDecoration.WARN_SENT_BY_UNVERIFIED,
            E2EDecoration.WARN_SENT_BY_UNKNOWN -> {
                bottomSheetSendStateItem {
                    id("e2e_unverified")
                    redactedTint(showsRestoredContent)
                    showProgress(false)
                    text(host.stringProvider.getString(CommonStrings.encrypted_unverified))
                    drawableStart(R.drawable.ic_shield_warning_small)
                }
            }
            E2EDecoration.WARN_UNSAFE_KEY -> {
                bottomSheetSendStateItem {
                    id("e2e_unsafe")
                    redactedTint(showsRestoredContent)
                    showProgress(false)
                    text(host.stringProvider.getString(CommonStrings.key_authenticity_not_guaranteed))
                    drawableStart(R.drawable.ic_shield_gray)
                }
            }
            E2EDecoration.WARN_SENT_BY_DELETED_SESSION -> {
                bottomSheetSendStateItem {
                    id("e2e_deleted")
                    redactedTint(showsRestoredContent)
                    showProgress(false)
                    text(host.stringProvider.getString(CommonStrings.encrypted_by_deleted))
                    drawableStart(R.drawable.ic_shield_warning_small)
                }
            }
            E2EDecoration.NONE -> {
            }
        }

        // MSC4268 requires us to name whoever shared the key, since only they vouch for who really sent the message.
        state.sharedByDisplayName?.let { sharedBy ->
            bottomSheetSendStateItem {
                id("shared_by")
                redactedTint(showsRestoredContent)
                showProgress(false)
                text(host.stringProvider.getString(CommonStrings.notice_keys_shared_by, sharedBy))
                drawableStart(R.drawable.ic_shield_gray)
            }
        }

        // Quick reactions (hidden entirely — separator included — when the user has removed them all)
        if (state.canReact() && state.quickStates is Success && state.quickStates().orEmpty().isNotEmpty()) {
            // Separator
            bottomSheetDividerItem {
                id("reaction_separator")
                subtle(true)
            }

            bottomSheetQuickReactionsItem {
                id("quick_reaction")
                fontProvider(host.fontProvider)
                compact(host.vectorPreferences.compactQuickReactions())
                texts(state.quickStates()?.map { it.reaction }.orEmpty())
                resolvedUrls(state.quickStates()?.map { toggle ->
                    toggle.reaction.takeIf { it.isMxcUrl() }?.let {
                        host.activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
                                ?.resolveThumbnail(it, 64, 64, ContentUrlResolver.ThumbnailMethod.SCALE)
                    }
                }.orEmpty())
                selecteds(state.quickStates.invoke().map { it.isSelected })
                listener(object : BottomSheetQuickReactionsItem.Listener {
                    override fun didSelect(emoji: String, selected: Boolean) {
                        host.listener?.didSelectMenuAction(EventSharedAction.QuickReact(state.eventId, emoji, selected))
                    }
                })
            }
        }

        if (state.actions.isNotEmpty()) {
            // Separator
            bottomSheetDividerItem {
                id("actions_separator")
                subtle(true)
            }
        }

        // Action
        state.actions.forEachIndexed { index, action ->
            if (action is EventSharedAction.Separator) {
                bottomSheetDividerItem {
                    id("separator_$index")
                    subtle(true)
                }
            } else {
                val showBetaLabel = action.shouldShowBetaLabel()

                bottomSheetActionItem {
                    id("action_$index")
                    iconRes(action.iconResId)
                    textRes(action.titleRes)
                    listener { host.listener?.didSelectMenuAction(action) }
                    destructive(action.destructive)
                    showBetaLabel(showBetaLabel)
                }
            }
        }
    }

    private fun EventSharedAction.shouldShowBetaLabel(): Boolean =
            this is EventSharedAction.ReplyInThread && !vectorPreferences.areThreadMessagesEnabled()

    interface MessageActionsEpoxyControllerListener : TimelineEventController.UrlClickCallback {
        fun didSelectMenuAction(eventAction: EventSharedAction)
    }
}
