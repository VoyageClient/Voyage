/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.action

import android.os.Build
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Success
import im.vector.app.EmojiCompatFontProvider
import im.vector.app.R
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.epoxy.bottomSheetDividerItem
import im.vector.app.core.epoxy.bottomsheet.BottomSheetQuickReactionsItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetActionItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetMessagePreviewItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetQuickReactionsItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetSendStateItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.format.EventDetailsFormatter
import im.vector.app.features.home.room.detail.timeline.helper.LocationPinProvider
import im.vector.app.features.home.room.detail.timeline.image.buildImageContentRendererData
import im.vector.app.features.home.room.detail.timeline.item.E2EDecoration
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.SpanUtils
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.location.INITIAL_MAP_ZOOM_IN_TIMELINE
import im.vector.app.features.location.UrlMapProvider
import im.vector.app.features.location.toLocationData
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.media.shouldHideMediaPreview
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.events.model.isLocationMessage
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessageLocationContent
import org.matrix.android.sdk.api.session.room.send.SendState
import javax.inject.Inject

/**
 * Epoxy controller for message action list.
 */
class MessageActionsEpoxyController @Inject constructor(
        private val stringProvider: StringProvider,
        private val avatarRenderer: AvatarRenderer,
        private val fontProvider: EmojiCompatFontProvider,
        private val imageContentRenderer: ImageContentRenderer,
        private val dimensionConverter: DimensionConverter,
        private val errorFormatter: ErrorFormatter,
        private val spanUtils: SpanUtils,
        private val eventDetailsFormatter: EventDetailsFormatter,
        private val vectorPreferences: VectorPreferences,
        private val dateFormatter: VectorDateFormatter,
        private val urlMapProvider: UrlMapProvider,
        private val locationPinProvider: LocationPinProvider,
        private val activeSessionHolder: ActiveSessionHolder,
        private val mediaContentRevealManager: MediaContentRevealManager,
        private val richMessageBodyRenderer: RichMessageBodyRenderer,
        private val pillsPostProcessorFactory: PillsPostProcessor.Factory,
        private val htmlCompressor: VectorHtmlCompressor,
) : TypedEpoxyController<MessageActionState>() {

    // The compressed HTML for a non-redacted text message whose body contains a table, so the
    // long-press preview can render real tables instead of the flattened plaintext a TextView shows.
    private fun computeTableHtml(timelineEvent: org.matrix.android.sdk.api.session.room.timeline.TimelineEvent?): String? {
        timelineEvent ?: return null
        if (timelineEvent.root.isRedacted()) return null
        val content = timelineEvent.getVectorLastMessageContent()
        // m.text, m.notice and m.emote all carry a formatted_body that may hold a table.
        if (content !is MessageContentWithFormattedBody || content.format != MessageFormat.FORMAT_MATRIX_HTML) return null
        val html = content.formattedBody?.takeIf { it.isNotBlank() } ?: return null
        return htmlCompressor.compress(html).takeIf { it.contains("<table", ignoreCase = true) }
    }

    var listener: MessageActionsEpoxyControllerListener? = null

    override fun buildModels(state: MessageActionState) {
        val host = this
        // Message preview
        val date = state.timelineEvent()?.root?.originServerTs
        val formattedDate = dateFormatter.format(date, DateFormatKind.MESSAGE_DETAIL)
        val body = state.messageBody.linkify(host.listener)
        val bindingOptions = spanUtils.getBindingOptions(body)
        val locationUiData = buildLocationUiData(state)

        bottomSheetMessagePreviewItem {
            id("preview")
            avatarRenderer(host.avatarRenderer)
            matrixItem(state.informationData.matrixItem)
            movementMethod(createLinkMovementMethod(host.listener))
            imageContentRenderer(host.imageContentRenderer)
            data(state.timelineEvent()?.buildImageContentRendererData(host.dimensionConverter.dpToPx(66)))
            hideMedia(
                    host.activeSessionHolder.getSafeActiveSession()?.let { session ->
                        state.timelineEvent()?.let { shouldHideMediaPreview(it, session, host.vectorPreferences, host.mediaContentRevealManager) }
                    }.orFalse()
            )
            hideMediaSolidColor(host.vectorPreferences.useSolidColorForHiddenMedia())
            userClicked { host.listener?.didSelectMenuAction(EventSharedAction.OpenUserProfile(state.informationData.senderId)) }
            bindingOptions(bindingOptions)
            body(body.toEpoxyCharSequence())
            bodyDetails(host.eventDetailsFormatter.format(state.timelineEvent()?.root)?.toEpoxyCharSequence())
            time(formattedDate)
            locationUiData(locationUiData)
            tableHtml(host.computeTableHtml(state.timelineEvent()))
            richBodyRenderer(host.richMessageBodyRenderer)
            htmlPostProcessors(arrayOf(host.pillsPostProcessorFactory.create(state.roomId)))
        }

        // Send state
        val sendState = state.sendState()
        if (sendState?.hasFailed().orFalse()) {
            // Get more details about the error
            val root = state.timelineEvent()?.root
            val errorMessage = root?.sendStateError()
                    ?.let { errorFormatter.toHumanReadable(Failure.ServerError(it, 0)) }
                    ?: root?.sendStateDetails
                    ?: stringProvider.getString(CommonStrings.unable_to_send_message)
            bottomSheetSendStateItem {
                id("send_state")
                showProgress(false)
                text(errorMessage)
                drawableStart(R.drawable.ic_warning_badge)
            }
        } else if (sendState?.isSending().orFalse()) {
            bottomSheetSendStateItem {
                id("send_state")
                showProgress(true)
                text(host.stringProvider.getString(CommonStrings.event_status_sending_message))
            }
        } else if (sendState == SendState.SENT) {
            bottomSheetSendStateItem {
                id("send_state")
                showProgress(false)
                drawableStart(R.drawable.ic_message_sent)
                text(host.stringProvider.getString(CommonStrings.event_status_sent_message))
            }
        }

        when (state.informationData.e2eDecoration) {
            E2EDecoration.WARN_IN_CLEAR -> {
                bottomSheetSendStateItem {
                    id("e2e_clear")
                    showProgress(false)
                    text(host.stringProvider.getString(CommonStrings.unencrypted))
                    drawableStart(R.drawable.ic_shield_warning_small)
                }
            }
            E2EDecoration.WARN_SENT_BY_UNVERIFIED,
            E2EDecoration.WARN_SENT_BY_UNKNOWN -> {
                bottomSheetSendStateItem {
                    id("e2e_unverified")
                    showProgress(false)
                    text(host.stringProvider.getString(CommonStrings.encrypted_unverified))
                    drawableStart(R.drawable.ic_shield_warning_small)
                }
            }
            E2EDecoration.WARN_UNSAFE_KEY -> {
                bottomSheetSendStateItem {
                    id("e2e_unsafe")
                    showProgress(false)
                    text(host.stringProvider.getString(CommonStrings.key_authenticity_not_guaranteed))
                    drawableStart(R.drawable.ic_shield_gray)
                }
            }
            E2EDecoration.WARN_SENT_BY_DELETED_SESSION -> {
                bottomSheetSendStateItem {
                    id("e2e_deleted")
                    showProgress(false)
                    text(host.stringProvider.getString(CommonStrings.encrypted_by_deleted))
                    drawableStart(R.drawable.ic_shield_warning_small)
                }
            }
            E2EDecoration.NONE -> {
            }
        }

        // Quick reactions
        if (state.canReact() && state.quickStates is Success) {
            // Separator
            bottomSheetDividerItem {
                id("reaction_separator")
            }

            bottomSheetQuickReactionsItem {
                id("quick_reaction")
                fontProvider(host.fontProvider)
                compact(host.vectorPreferences.compactQuickReactions())
                texts(state.quickStates()?.map { it.reaction }.orEmpty())
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
            }
        }

        // Action
        state.actions.forEachIndexed { index, action ->
            if (action is EventSharedAction.Separator) {
                bottomSheetDividerItem {
                    id("separator_$index")
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

    private fun buildLocationUiData(state: MessageActionState): LocationUiData? {
        // No map renderer on KitKat (maplibre needs API 21); the preview falls back to the notice
        // body ("… sent a location."), matching how the timeline renders location there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        if (state.timelineEvent()?.root?.isLocationMessage() != true) return null

        val locationContent = state.timelineEvent()?.root?.getClearContent().toModel<MessageLocationContent>(catchError = true)
                ?: return null
        val locationUrl = locationContent.toLocationData()
                ?.let { urlMapProvider.buildStaticMapUrl(it, INITIAL_MAP_ZOOM_IN_TIMELINE, 1200, 800) }
                ?: return null
        val locationOwnerId = if (locationContent.isSelfLocation()) state.informationData.senderId else null

        return LocationUiData(
                locationUrl = locationUrl,
                locationOwnerId = locationOwnerId,
                locationPinProvider = locationPinProvider,
        )
    }

    private fun EventSharedAction.shouldShowBetaLabel(): Boolean =
            this is EventSharedAction.ReplyInThread && !vectorPreferences.areThreadMessagesEnabled()

    interface MessageActionsEpoxyControllerListener : TimelineEventController.UrlClickCallback {
        fun didSelectMenuAction(eventAction: EventSharedAction)
    }
}
