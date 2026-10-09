/*
 * Copyright (c) 2020 New Vector Ltd
 * Copyright (c) 2022 SpiritCroc
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

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.core.extensions.setSenderNameEmphasis
import im.vector.app.databinding.ViewInReplyToBinding
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewView
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.translation.MessageTranslationStore
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import timber.log.Timber

private const val REPLY_PLAYER_SCALE = 0.85f
private const val REPLY_MEDIA_MARGIN_TOP_DP = 6

/**
 * A View to render a replied-to event.
 */
class InReplyToView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), View.OnClickListener {

    private lateinit var views: ViewInReplyToBinding

    var delegate: TimelineEventController.InReplyToClickCallback? = null
    var sourceEventId: String? = null

    // Echo-swap-stable identity of the host message, for stale-update guards; sourceEventId is the
    // real event id for navigation, which local echoes change on sync.
    var sourceStableId: String? = null

    // Clicking the reply while its host message is still sending only has a local-echo id to jump
    // back to, which fails the later jump-to-bottom. Stay inert until the message is sent.
    var sourceIsSent: Boolean = true

    /** Whether the quoted event is showing content recovered from a redaction, so the item can band the row. */
    var quotesRevealedRedaction: Boolean = false
        private set

    /** Invoked when [quotesRevealedRedaction] flips, so the host item can repaint its row-level band. */
    var onBandStateChanged: (() -> Unit)? = null

    init {
        setupView()
    }

    private var state: PreviewReplyUiState = PreviewReplyUiState.NoReply
    private var senderBarColor: Int? = null
    private var senderBarRenderedColor: Int? = null
    private var senderNameColor: Int? = null
    private var senderBarColorAnimator: ValueAnimator? = null

    // What the current render substituted for the quoted body: PGP plaintext / a translation (null = none).
    private var renderedPlainOverride: String? = null
    private var renderedTranslation: MessageTranslationStore.Translation? = null

    fun render(
            newState: PreviewReplyUiState,
            retriever: ReplyPreviewRetriever,
            roomInformationData: MessageInformationData,
            itemLongClickListener: OnLongClickListener?,
            force: Boolean = false
    ) {
        // Substituted before the equality check, not inside renderReplyTo: revealing a redaction leaves
        // the timeline event untouched, so comparing the raw state would early-return and keep the
        // placeholder up.
        val effectiveState = (newState as? PreviewReplyUiState.InReplyTo)
                ?.let { raw -> retriever.redactedContentRestorer.restoreEvent(raw.event)?.let { raw.copy(event = it) } }
                ?: newState
        val revealed = effectiveState !== newState

        // The translation/PGP plaintext substituted for the quoted body lives outside the state, so
        // an identical state can arrive after it changed — compare it too, or the old text stays up.
        val translation = (effectiveState as? PreviewReplyUiState.InReplyTo)?.let { retriever.messageTranslationStore.get(it.event) }
        val plainOverride = (effectiveState as? PreviewReplyUiState.InReplyTo)?.let { s -> pgpPlainOverride(s.event, retriever, translation) }

        if (effectiveState == state && revealed == quotesRevealedRedaction && plainOverride == renderedPlainOverride &&
                translation == renderedTranslation && !force) {
            // The sender's color can change under an otherwise identical state (override, palette).
            (effectiveState as? PreviewReplyUiState.InReplyTo)?.let {
                applySenderColor(retriever.getMemberNameColor(it.event), retriever.isMemberNameColored())
            }
            return
        }

        val previousReply = state as? PreviewReplyUiState.InReplyTo
        val nextReply = effectiveState as? PreviewReplyUiState.InReplyTo
        if (!force && previousReply != null && nextReply != null &&
                previousReply.repliedToEventId == nextReply.repliedToEventId && previousReply.event == nextReply.event &&
                revealed == quotesRevealedRedaction && plainOverride == renderedPlainOverride && translation == renderedTranslation) {
            state = effectiveState
            if (previousReply.senderName != nextReply.senderName) {
                views.replyMemberNameView.text = nextReply.senderName.prepareForDisplay()
            }
            applySenderColor(retriever.getMemberNameColor(nextReply.event), retriever.isMemberNameColored())
            return
        }

        val keepSenderColor = effectiveState is PreviewReplyUiState.InReplyTo && effectiveState.repliedToEventId == state.repliedToEventId
        state = effectiveState
        renderedPlainOverride = plainOverride
        renderedTranslation = translation

        // Only a *revealed* redaction is banded: the band marks content that a redaction took and we are
        // reading anyway, so the plain "Message removed" placeholder carries no mark. Painted by the item
        // on a row-level view rather than here — this view sits inside the content column, so its own
        // background could never reach the avatar gutter or the timestamp column.
        if (quotesRevealedRedaction != revealed) {
            quotesRevealedRedaction = revealed
            onBandStateChanged?.invoke()
        }

        when (effectiveState) {
            PreviewReplyUiState.NoReply -> renderHidden()
            is PreviewReplyUiState.ReplyLoading -> renderLoading()
            is PreviewReplyUiState.Error -> renderError(effectiveState)
            is PreviewReplyUiState.InReplyTo -> renderReplyTo(effectiveState, retriever, roomInformationData, itemLongClickListener, keepSenderColor)
        }

        setOnLongClickListener(itemLongClickListener)
        // Somehow this one needs it additionally?
        views.replyPreview.textView.setOnLongClickListener(itemLongClickListener)
    }

    override fun onClick(v: View?) {
        if (!sourceIsSent) return
        state.repliedToEventId?.let { delegate?.onRepliedToEventClicked(sourceEventId, it) }
    }

    // PRIVATE METHODS ****************************************************************************************************************************************

    private fun setupView() {
        inflate(context, R.layout.view_in_reply_to, this)
        views = ViewInReplyToBinding.bind(this)

        setOnClickListener(this)
        // Somehow this one needs it additionally?
        views.replyPreview.textView.setOnClickListener(this)
        views.replyPreview.onRichClick = { onClick(it) }
    }

    private fun hideViews(keepSenderColor: Boolean = false) {
        if (!keepSenderColor) {
            senderBarColorAnimator?.cancel()
            senderBarColorAnimator = null
            senderBarColor = null
            senderBarRenderedColor = null
            senderNameColor = null
        }
        views.replyMemberNameView.isVisible = false
    }

    private fun renderHidden() {
        isVisible = false
    }

    private fun renderLoading() = renderStatus(context.getString(CommonStrings.in_reply_to_loading))

    private fun renderError(state: PreviewReplyUiState.Error) {
        Timber.w(state.throwable, "Error rendering reply")
        renderStatus(context.getString(CommonStrings.in_reply_to_error))
    }

    private fun renderStatus(text: String) {
        hideViews()
        isVisible = true
        views.replyPreview.showText(
                SpannableString(text).apply { setSpan(StyleSpan(Typeface.ITALIC), 0, length, 0) },
                MessagePreviewContent.TextStyle.NOTICE,
        )
        views.inReplyToBar.setBackgroundColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
    }

    // Uncolored names give the bar no sender color to echo, so it falls back to a neutral rule.
    private fun applySenderColor(color: Int, colored: Boolean) {
        views.replyMemberNameView.setSenderNameEmphasis(colored)
        val nextBarColor = if (colored) color else ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        if (senderNameColor == color && senderBarColor == nextBarColor && senderBarColorAnimator?.isRunning == true) return
        val previousNameColor = views.replyMemberNameView.currentTextColor
        val previousBarColor = senderBarRenderedColor
        senderBarColorAnimator?.cancel()
        if (previousBarColor != null && (previousBarColor != nextBarColor || previousNameColor != color)) {
            val evaluator = ArgbEvaluator()
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220L
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    val bar = evaluator.evaluate(fraction, previousBarColor, nextBarColor) as Int
                    senderBarRenderedColor = bar
                    views.inReplyToBar.setBackgroundColor(bar)
                    views.replyMemberNameView.setTextColor(evaluator.evaluate(fraction, previousNameColor, color) as Int)
                }
                senderBarColorAnimator = this
                start()
            }
        } else {
            views.replyMemberNameView.setTextColor(color)
            views.inReplyToBar.setBackgroundColor(nextBarColor)
            senderBarRenderedColor = nextBarColor
        }
        senderBarColor = nextBarColor
        senderNameColor = color
    }

    // No movement method anywhere in the preview: links/pills/spoilers stay inert so a tap anywhere on the
    // reply header jumps to the replied-to message instead.
    private fun renderReplyTo(
            state: PreviewReplyUiState.InReplyTo,
            retriever: ReplyPreviewRetriever,
            roomInformationData: MessageInformationData,
            itemLongClickListener: OnLongClickListener?,
            keepSenderColor: Boolean,
    ) {
        hideViews(keepSenderColor)
        isVisible = true
        views.replyMemberNameView.isVisible = true
        views.replyMemberNameView.text = state.senderName.prepareForDisplay()
        applySenderColor(retriever.getMemberNameColor(state.event), retriever.isMemberNameColored())
        views.replyPreview.style = MessagePreviewView.Style(
                fillWidth = false,
                mediaMarginTop = (REPLY_MEDIA_MARGIN_TOP_DP * resources.displayMetrics.density).toInt(),
                // A quote is smaller than the message it quotes, as its thumbnails are.
                playerScale = REPLY_PLAYER_SCALE,
                // Outside bubbles, stretch block code to the full timeline width like the timeline does;
                // inside a bubble it should hug its content instead.
                fullWidthBlockCode = roomInformationData.messageLayout is TimelineMessageLayout.Default,
                hideCaptionWithHiddenMedia = true,
                showAttachmentInfo = false,
                compactPolls = true,
        )
        views.replyPreview.onRichLongClick = { itemLongClickListener?.onLongClick(it) ?: false }
        views.replyPreview.bind(
                retriever.preview(state.event),
                hidden = retriever.isMediaHidden(state.event),
                hiddenAsSolidColor = retriever.useSolidColorForHiddenMedia,
                renderers = MessagePreviewView.Renderers(
                        retriever.imageContentRenderer,
                        retriever.richMessageBodyRenderer,
                        arrayOf(retriever.pillsPostProcessor),
                        retriever.locationPinProvider,
                ),
        )
    }

    // A translation of a PGP message is of its plaintext, and so replaces it.
    private fun pgpPlainOverride(event: TimelineEvent, retriever: ReplyPreviewRetriever, translation: MessageTranslationStore.Translation?): String? {
        if (translation != null) return null
        return (event.getLastMessageContent() as? MessageContentWithFormattedBody)?.let { retriever.pgpDecryptor.peekDecryptedBody(it.body) }
    }
}
