/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.net.Uri
import android.text.Editable
import android.text.SpannableStringBuilder
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.text.toSpannable
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.setSenderNameEmphasis
import im.vector.app.core.extensions.showKeyboard
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.core.ui.ColorRefreshable
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.databinding.ComposerLayoutBinding
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.emoji.TwemojiProvider
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.helper.LocationPinProvider
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.home.room.detail.timeline.helper.renderPerMessageProfile
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContentBuilder
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewView
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.expandPillSpans
import im.vector.app.features.html.setPillSpan
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.media.shouldHideMediaPreview
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.text.DirectionOverridesTransformation
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.getUserOrDefault
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toEveryoneInRoomMatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

/**
 * Encapsulate the timeline composer UX.
 */
@AndroidEntryPoint
class PlainTextComposerLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), MessageComposerView, ColorRefreshable {

    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var matrixItemColorProvider: MatrixItemColorProvider
    @Inject lateinit var richMessageBodyRenderer: RichMessageBodyRenderer
    @Inject lateinit var dimensionConverter: DimensionConverter
    @Inject lateinit var imageContentRenderer: ImageContentRenderer
    @Inject lateinit var pillsPostProcessorFactory: PillsPostProcessor.Factory
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var redactedContentRestorer: RedactedContentRestorer
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var mediaContentRevealManager: MediaContentRevealManager
    @Inject lateinit var twemojiProvider: TwemojiProvider
    @Inject lateinit var previewBuilder: MessagePreviewContentBuilder
    @Inject lateinit var locationPinProvider: LocationPinProvider

    private val views: ComposerLayoutBinding

    private val classic = vectorPreferences.useClassicComposer()

    // Kept so the media can be re-rendered in place when revealed elsewhere.
    private var relatedMessagePreview: MessagePreviewContent? = null

    // Lets a reconstructed pill resolve its member (and so its room display name) the way the timeline does.
    var roomId: String? = null
    var isThreadTimeline = false

    override var callback: Callback? = null

    override val text: Editable?
        get() = views.composerEditText.text

    override val formattedText: String? = null

    override val editText: EditText
        get() = views.composerEditText

    @Suppress("RedundantNullableReturnType")
    override val emojiButton: ImageButton?
        get() = views.composerEmojiButton

    override val microphoneButton: ImageButton
        get() = views.composerMicButton

    override val sendButton: ImageButton
        get() = views.sendButton

    override val attachmentButton: ImageButton
        get() = views.attachmentButton

    /** Match the timeline and the reply preview above it, which dim while names are uncolored. */
    override fun refreshColors() {
        views.composerEditText.setTextColor(ThemeUtils.getMessageTextColor(context))
        // The preview resolves its colors as it renders, so re-render it rather than repainting pieces.
        lastSpecialMode?.let { renderSpecialMode(it) }
    }

    /**
     * The inflated input row, which carries the composer's own background. The layout around it is
     * match_parent and painted differently, so anything matching the composer's color reads it here.
     */
    val composerSurface: View get() = getChildAt(0) ?: this

    init {
        inflate(context, if (classic) R.layout.composer_layout_classic else R.layout.composer_layout, this)
        views = ComposerLayoutBinding.bind(this)

        views.composerEditText.maxLines = MessageComposerView.MAX_LINES_WHEN_COLLAPSED
        refreshColors()
        // Draw direction-override chars (e.g. in an edited message) as tofu instead of letting them
        // flip the field; the Editable and the sent text keep the real characters.
        views.composerEditText.transformationMethod = DirectionOverridesTransformation

        views.composerRelatedMessagePreview.style = MessagePreviewView.Style(mediaMarginTop = dimensionConverter.dpToPx(6))

        collapse(animate = false)

        // Render emoji the user types/pastes as Twemoji sprites in the input box (the platform/emoji2
        // can't on ICS or when Twemoji is forced). setSpan doesn't retrigger text watchers, so no loop.
        if (twemojiProvider.enabled) {
            views.composerEditText.addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable) {
                    twemojiProvider.applyTo(s)
                }
            })
        }

        views.composerEditText.callback = object : ComposerEditText.Callback {
            override fun onRichContentSelected(contentUri: Uri): Boolean {
                return callback?.onRichContentSelected(contentUri) ?: false
            }

            override fun onTextChanged(text: CharSequence) {
                callback?.onTextChanged(text)
            }
        }
        views.composerRelatedMessageCloseButton.setOnClickListener {
            collapse()
            callback?.onCloseRelatedMessage()
            views.composerEditText.post {
                if (lastSpecialMode == null) {
                    requestRegularModeKeyboard()
                }
            }
        }

        views.sendButton.setOnClickListener {
            val textMessage = text?.toSpannable()?.expandPillSpans() ?: ""
            callback?.onSendMessage(textMessage)
        }

        views.attachmentButton.setOnClickListener {
            callback?.onAddAttachment()
        }
    }

    private var lastSpecialModeKey: Pair<String, String>? = null
    private var lastSpecialMode: MessageComposerMode.Special? = null
    private var editSelectionToRestore: Int? = null
    private var topDividerSuppressed = false

    private fun collapse(animate: Boolean = true, transitionComplete: (() -> Unit)? = null) {
        lastSpecialModeKey = null
        lastSpecialMode = null
        if (animate) beginClassicTransition()
        views.relatedMessageGroup.isVisible = false
        refreshTopDivider()
        transitionComplete?.invoke()
        callback?.onExpandOrCompactChange()
    }

    /** Hidden while a suggestion list sits on top of the composer, which draws its own edge there. */
    fun suppressTopDivider(suppress: Boolean) {
        topDividerSuppressed = suppress
        refreshTopDivider()
    }

    private fun refreshTopDivider() {
        views.composerTopDivider.visibility = when {
            !classic -> View.GONE
            // The preview brings its own top separator.
            views.relatedMessageGroup.isVisible -> View.GONE
            // Invisible, not gone: dropping its 1dp would resize the composer and shift the timeline.
            topDividerSuppressed -> View.INVISIBLE
            else -> View.VISIBLE
        }
        // Whichever separator is topmost has to go, and the preview's is when it is open.
        views.relatedMessageBackgroundTopSeparator.visibility =
                if (topDividerSuppressed) View.INVISIBLE else View.VISIBLE
    }

    private fun expand(animate: Boolean = true, transitionComplete: (() -> Unit)? = null) {
        if (animate) beginClassicTransition()
        views.relatedMessageGroup.isVisible = true
        refreshTopDivider()
        transitionComplete?.invoke()
        callback?.onExpandOrCompactChange()
    }

    private fun requestRegularModeKeyboard() {
        val editText = views.composerEditText
        callback?.onSoftKeyboardRequested()
        editText.showKeyboard(andRequestFocus = true)
        editText.postDelayed({
            if (lastSpecialMode == null && editText.hasFocus()) {
                editText.showKeyboard()
            }
        }, SPECIAL_KEYBOARD_RETRY_DELAY)
    }

    private fun requestSpecialModeKeyboard() {
        val editText = views.composerEditText
        callback?.onSoftKeyboardRequested()
        editText.post {
            if (lastSpecialMode == null) return@post
            editText.showKeyboard(andRequestFocus = true)
            editText.postDelayed({
                if (lastSpecialMode != null && editText.hasFocus()) {
                    editText.showKeyboard()
                }
            }, SPECIAL_KEYBOARD_RETRY_DELAY)
        }
    }

    private fun beginClassicTransition() {
        if (!classic || !ViewCompat.isAttachedToWindow(this)) return
        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_SEQUENTIAL
            addTransition(ChangeBounds())
            addTransition(Fade(Fade.IN))
            duration = RELATED_MESSAGE_ANIMATION_DURATION
            // Target the preview only; untargeted, this also animates the input row.
            addTarget(views.relatedMessageGroup)
        }
        TransitionManager.beginDelayedTransition(this, transition)
    }

    override fun setTextIfDifferent(text: CharSequence?): Boolean {
        return views.composerEditText.setTextProgrammatically(text)
    }

    private fun isMediaHidden(preview: MessagePreviewContent): Boolean {
        val session = activeSessionHolder.getSafeActiveSession() ?: return false
        return shouldHideMediaPreview(preview.event, session, vectorPreferences, mediaContentRevealManager)
    }

    override fun refreshRelatedMessageMedia() {
        val preview = relatedMessagePreview ?: return
        if (!views.relatedMessageGroup.isVisible) return
        views.composerRelatedMessagePreview.refreshMedia(
                preview,
                hidden = isMediaHidden(preview),
                hiddenAsSolidColor = vectorPreferences.useSolidColorForHiddenMedia(),
                imageContentRenderer = imageContentRenderer,
        )
    }

    override fun getDraftContent(): CharSequence = serializeMentionPills(text ?: "")

    // Serialise mention pills (MatrixItemSpan) to matrix.to markdown links so they survive the draft's
    // String round-trip; non-mention text is left verbatim. Returns the plain text when there are none.
    private fun serializeMentionPills(source: CharSequence): String {
        val spannable = source.toSpannable()
        val spans = spannable.getSpans(0, spannable.length, MatrixItemSpan::class.java)
                .sortedBy { spannable.getSpanStart(it) }
        if (spans.isEmpty()) return guardAuthoredMentionLinks(source)
        return buildString {
            var index = 0
            spans.forEach { span ->
                val start = spannable.getSpanStart(span)
                val end = spannable.getSpanEnd(span)
                if (start < index) return@forEach
                append(guardAuthoredMentionLinks(spannable.subSequence(index, start)))
                // The backing text is a placeholder char, so take the label from the span's body text —
                // never the item's name, which may carry a local display-name override.
                append("[").append((span as? PillImageSpan)?.bodyText ?: span.matrixItem.getBestName()).append("]")
                append("(https://matrix.to/#/").append(span.matrixItem.id).append(")")
                index = end
            }
            append(guardAuthoredMentionLinks(spannable.subSequence(index, spannable.length)))
        }
    }

    private val mentionLinkRegex = Regex("""\[([^]]+)]\((https://matrix\.to/#/[^)]+)\)""")

    // Inverse of serializeMentionPills: turn matrix.to markdown links back into PillImageSpans.
    private fun reconstructMentionPills(source: CharSequence): CharSequence {
        if (!source.contains("https://matrix.to/#/")) return source
        val session = activeSessionHolder.getSafeActiveSession()
        if (session == null || sendsLiteralText(source)) return SpannableStringBuilder(source).unguardAuthoredMentionLinks()
        val out = SpannableStringBuilder()
        var index = 0
        mentionLinkRegex.findAll(source).forEach { match ->
            out.append(source, index, match.range.first)
            val label = match.groupValues[1]
            val matrixItem = when (val data = PermalinkParser.parse(match.groupValues[2])) {
                // Prefer the room member: only it carries the name (and any local override) the pill
                // should draw. The global user is a fallback that often knows nothing but the id.
                is PermalinkData.UserLink ->
                    roomId?.let { session.roomService().getRoomMember(data.userId, it)?.toMatrixItem() }
                            ?: session.getUserOrDefault(data.userId).toMatrixItem()
                is PermalinkData.RoomLink -> if (data.eventId == null) roomMentionItem(session, data, label) else null
                else -> null
            }
            if (matrixItem != null) {
                val start = out.length
                out.append(label)
                val span = PillImageSpan(GlideApp.with(this), avatarRenderer, context, matrixItem, bodyText = label)
                        .also { it.bind(editText) }
                out.setPillSpan(span, start, start + label.length)
            } else {
                out.append(source, match.range.first, match.range.last + 1)
            }
            index = match.range.last + 1
        }
        out.append(source, index, source.length)
        return out.unguardAuthoredMentionLinks()
    }

    // The same items the autocomplete builds, so the restored pill sends, draws and backspaces like the original.
    private fun roomMentionItem(session: Session, link: PermalinkData.RoomLink, label: String): MatrixItem? {
        val summary = session.getRoomSummary(link.roomIdOrAlias)
        if (label == MatrixItem.NOTIFY_EVERYONE && summary != null && summary.roomId == roomId) {
            return summary.toEveryoneInRoomMatrixItem()
        }
        return when {
            summary != null -> MatrixItem.RoomAliasItem(link.roomIdOrAlias, summary.displayName, summary.avatarUrl)
            link.isRoomAlias -> MatrixItem.RoomAliasItem(link.roomIdOrAlias)
            else -> null
        }
    }

    override fun renderComposerMode(mode: MessageComposerMode) {
        val specialMode = mode as? MessageComposerMode.Special
        if (specialMode != null) {
            renderSpecialMode(specialMode)
        } else if (mode is MessageComposerMode.Normal) {
            val selectionToRestore = editSelectionToRestore
            collapse()
            // Reconstruct mention pills from a restored draft's matrix.to markdown links. For live
            // content (already-spanned, no markdown), this is a no-op and the existing pills are kept.
            val content = reconstructMentionPills(mode.content ?: "")
            if (editText.text?.toString() != content.toString()) {
                views.composerEditText.setTextProgrammatically(content)
            }
            selectionToRestore?.let { editText.setSelection(it.coerceIn(0, editText.length())) }
            editSelectionToRestore = null
        }

        views.sendButton.apply {
            if (mode is MessageComposerMode.Edit) {
                contentDescription = resources.getString(CommonStrings.action_save)
                // ic_composer_rich_text_save bakes in its own filled disc, which the classic composer's flat
                // accent tint would flood into a solid accent circle; there the glyph has to stand alone.
                setImageResource(if (classic) R.drawable.ic_check_on else R.drawable.ic_composer_rich_text_save)
            } else {
                contentDescription = resources.getString(CommonStrings.action_send)
                setImageResource(if (classic) R.drawable.ic_send else R.drawable.ic_rich_composer_send)
            }
        }
    }

    private fun renderSpecialMode(specialMode: MessageComposerMode.Special) {
        // Re-rendering the same target (it was edited or redacted under the composer) must not replay the
        // expand animation or pull the keyboard back up.
        val modeKey = specialMode.javaClass.name to specialMode.event.eventId
        val isRefresh = views.relatedMessageGroup.isVisible && lastSpecialModeKey == modeKey
        if (!isRefresh && specialMode is MessageComposerMode.Edit && lastSpecialMode !is MessageComposerMode.Edit) {
            editSelectionToRestore = views.composerEditText.selectionStart
        }
        lastSpecialModeKey = modeKey
        lastSpecialMode = specialMode
        // A revealed redaction previews its restored content here too, matching the timeline.
        val restored = redactedContentRestorer.restoreEvent(specialMode.event)
        val event = restored ?: specialMode.event
        val defaultContent = specialMode.defaultContent

        // The classic composer's bar is the toolbar color, and the preview sits flush on it; colorSurface
        // matches that only in some themes.
        val surfaceColor = if (classic) {
            ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_toolbar_background)
        } else {
            ThemeUtils.getColor(context, com.google.android.material.R.attr.colorSurface)
        }
        views.relatedMessageBackground.setBackgroundColor(
                // Only recovered content is marked; the bare "Message removed" placeholder isn't.
                if (restored != null) {
                    // Composited, not stacked: the strip sits on the opaque composer surface.
                    ColorUtils.compositeColors(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_redacted_background), surfaceColor)
                } else {
                    surfaceColor
                }
        )

        val iconRes: Int = when (specialMode) {
            is MessageComposerMode.Reply -> R.drawable.ic_reply
            is MessageComposerMode.Edit -> R.drawable.ic_edit
            is MessageComposerMode.Quote -> R.drawable.ic_quote
        }

        val renderedProfile = event.renderPerMessageProfile(
                event.senderInfo.disambiguatedDisplayName,
                vectorPreferences.arePerMessageProfilesEnabled()
        )
        val senderItem = MatrixItem.UserItem(
                id = event.senderInfo.userId,
                displayName = renderedProfile.senderName,
                avatarUrl = renderedProfile.avatarUrl,
                avatarDecryption = renderedProfile.avatarDecryption,
                colorPreference = event.senderInfo.colorPreference,
        )

        // switch to expanded bar
        views.composerRelatedMessageTitle.apply {
            text = renderedProfile.senderName.prepareForDisplay()
            setTextColor(matrixItemColorProvider.getNameColor(senderItem))
            setSenderNameEmphasis(matrixItemColorProvider.isNameColored())
        }

        val preview = previewBuilder.build(
                specialMode.event,
                MessagePreviewContentBuilder.Options(
                        thumbnailMaxWidth = dimensionConverter.dpToPx(132),
                        thumbnailMaxHeight = dimensionConverter.dpToPx(66),
                        galleryMaxWidth = dimensionConverter.dpToPx(160),
                        galleryMaxHeight = dimensionConverter.dpToPx(160),
                        isThreadTimeline = isThreadTimeline,
                        senderItem = senderItem,
                )
        )
        relatedMessagePreview = preview
        views.composerRelatedMessagePreview.bind(
                preview,
                hidden = isMediaHidden(preview),
                hiddenAsSolidColor = vectorPreferences.useSolidColorForHiddenMedia(),
                renderers = MessagePreviewView.Renderers(
                        imageContentRenderer,
                        richMessageBodyRenderer,
                        arrayOf(pillsPostProcessorFactory.create(event.roomId)),
                        locationPinProvider,
                ),
        )

        views.composerRelatedMessageActionIcon.setImageDrawable(ContextCompat.getDrawable(context, iconRes))

        avatarRenderer.render(senderItem, views.composerRelatedMessageAvatar)

        val content = if (specialMode is MessageComposerMode.Edit) {
            // Edit against the plain body — the markdown source the user typed. The rendered
            // formatted body can't roundtrip: list/quote markers exist only as spans, so saving
            // would flatten the structure.
            reconstructMentionPills(defaultContent)
        } else {
            defaultContent
        }

        // On a refresh the box is the source of truth: the state's text is a snapshot that can trail the
        // user's typing, and setText() would drop those keystrokes and send the cursor to the start.
        if (!isRefresh && views.composerEditText.text?.toString() != content.toString()) {
            views.composerEditText.setText(content)
        }
        if (!isRefresh && specialMode is MessageComposerMode.Edit) {
            views.composerEditText.setSelection(views.composerEditText.length())
        }

        expand(animate = !isRefresh) {
            if (!isRefresh) {
                views.composerEditText.post(::requestSpecialModeKeyboard)
            }
        }
    }

    override fun renderRoomEncryption(isEncrypted: Boolean, trustLevel: RoomEncryptionTrustLevel?, isPgp: Boolean) {
        if (!classic) return
        if (isEncrypted || isPgp) {
            views.composerShield.renderRoomShield(trustLevel, isPgp)
        } else {
            views.composerShield.isVisible = false
        }
    }

    companion object {
        private const val RELATED_MESSAGE_ANIMATION_DURATION = 100L
        private const val SPECIAL_KEYBOARD_RETRY_DELAY = 150L
    }
}
