/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.tools

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.text.toSpannable
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyViewHolder
import im.vector.app.EmojiSpanify
import im.vector.app.core.linkify.VectorLinkify
import im.vector.app.core.ui.PerformanceMode
import im.vector.app.core.utils.EvenBetterLinkMovementMethod
import im.vector.app.core.utils.isValidUrl
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.item.ItemWithEvents
import im.vector.app.features.html.AttachmentPillSpan
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.html.HtmlCodeSpan
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.SpoilerSpan
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.permalinks.MatrixLinkify
import org.matrix.android.sdk.api.session.permalinks.MatrixPermalinkSpan

// Set once at app start so the shared message-text helpers can apply emoji without injecting
// EmojiSpanify at every (often non-DI epoxy / custom-view) call site. EmojiSpanify is a stateless singleton.
@Volatile
var messageEmojiSpanify: EmojiSpanify? = null

// Set once at app start so the shared topic helper reaches the (injectable) renderer from the many
// non-DI epoxy/custom-view sites that display a room topic.
@Volatile
var messageTopicRenderer: RoomTopicRenderer? = null

/**
 * Render a room topic for display, preferring its [formattedTopic] HTML body and falling back to
 * markdown rendering of the plain text, then pills + matrix ids/aliases made clickable (routed to
 * [callback]). Falls back to plain linkify when the renderer isn't wired yet.
 */
fun CharSequence.formatTopic(roomId: String?, formattedTopic: String? = null, callback: TimelineEventController.UrlClickCallback? = null): CharSequence {
    return messageTopicRenderer?.render(this, formattedTopic, roomId, callback)
            ?: linkify(callback).prepareForDisplay()
}

/**
 * Render an MSC4440 biography, which carries the same optional HTML body as a topic. No room context,
 * so permalink pills resolve globally rather than against a room's members. A bio without an HTML body
 * renders verbatim rather than as markdown, the way a plain message does — markdown would fold the
 * blank lines its author typed into a single paragraph break.
 */
fun CharSequence.formatProfileBio(formattedBio: String? = null, callback: TimelineEventController.UrlClickCallback? = null): CharSequence {
    return if (formattedBio.isNullOrEmpty()) {
        linkify(callback).prepareForDisplay()
    } else {
        formatTopic(roomId = null, formattedTopic = formattedBio, callback = callback)
    }
}

/** Prepare user-provided text for display: neutralize Unicode direction-override characters (shown as an
 *  unsupported-glyph box, see [neutralizeDirectionOverrides]) and apply the app's emoji rendering
 *  (Twemoji sprites / emoji2 / system font). */
fun CharSequence.prepareForDisplay(): CharSequence {
    val neutralized = neutralizeDirectionOverrides()
    return messageEmojiSpanify?.spanify(neutralized) ?: neutralized
}

/** Add a watcher so emoji typed/pasted into this input field render like elsewhere (Twemoji / emoji2); the
 *  spans sit over the original codepoints so the entered/saved text is unchanged. Call once per view. */
fun android.widget.EditText.setupLiveEmojiInput() {
    addTextChangedListener(object : im.vector.app.core.platform.SimpleTextWatcher() {
        override fun afterTextChanged(s: android.text.Editable) {
            messageEmojiSpanify?.applyLive(s)
        }
    })
}

/** Same, for a [SearchView]'s inner query EditText (filter/search boxes). */
fun androidx.appcompat.widget.SearchView.setupLiveEmojiInput() {
    (findViewById(androidx.appcompat.R.id.search_src_text) as? android.widget.EditText)?.setupLiveEmojiInput()
}

/** Same, for an [EditTextPreference]'s dialog EditText (rendered each time the dialog opens), plus rendering
 *  the current value the framework sets before this callback runs. */
fun androidx.preference.EditTextPreference.setupLiveEmojiInput() {
    setOnBindEditTextListener { editText ->
        editText.setupLiveEmojiInput()
        messageEmojiSpanify?.applyLive(editText.text)
    }
}

/**
 * Build an inline attachment pill (rounded background + icon + label) for a file / voice / audio,
 * matching the reply header's pill, for the long-press sheet and reply composer where only a TextView
 * is available.
 */
fun attachmentPreviewText(context: Context, @DrawableRes iconRes: Int, label: CharSequence): CharSequence {
    return SpannableStringBuilder(" ").apply {
        setSpan(AttachmentPillSpan(context, iconRes, label), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/**
 * Prefix an m.emote body with "* Sender ", the way the timeline renders it: the body reads as an
 * action attributed to its sender, so a preview without the prefix is left dangling.
 */
fun CharSequence.asEmoteBody(senderName: CharSequence): CharSequence {
    return SpannableStringBuilder("* ")
            .append(messageEmojiSpanify?.spanify(senderName) ?: senderName)
            .append(" ")
            .append(this)
}

fun CharSequence.findPillsAndProcess(scope: CoroutineScope, processBlock: (PillImageSpan) -> Unit) {
    scope.launch(Dispatchers.Main) {
        withContext(Dispatchers.IO) {
            toSpannable().let { spannable ->
                spannable.getSpans(0, spannable.length, PillImageSpan::class.java)
            }
        }.forEach { processBlock(it) }
    }
}

// BlurMaskFilter (used by hidden spoilers) is ignored on a hardware layer, so switch the view to a
// software layer whenever it still contains a blurred spoiler, and back once everything is revealed.
// In performance mode there is no blur (spoilers render as a flat block, see SpoilerSpan), so keep the
// cheaper hardware layer.
fun TextView.applySpoilerRenderLayer() {
    if (PerformanceMode.enabled) return
    val spanned = text as? Spanned
    val blurred = spanned?.getSpans(0, spanned.length, SpoilerSpan::class.java)?.any { it.blurFraction > 0f } ?: false
    val desired = if (blurred) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE
    if (layerType != desired) setLayerType(desired, null)
}

fun CharSequence.linkify(callback: TimelineEventController.UrlClickCallback?): CharSequence {
    // SpannableStringBuilder is used to avoid Epoxy throwing ImmutableModelException
    val spannable = SpannableStringBuilder(this)
    MatrixLinkify.addLinks(spannable, object : MatrixPermalinkSpan.Callback {
        override fun onUrlClicked(url: String) {
            callback?.onUrlClicked(url, this.toString())
        }
    })
    VectorLinkify.addLinks(spannable, true)
    spannable.removeLinksOverEmotes()
    spannable.removeLinksOverCode()
    return spannable
}

// Code is verbatim: a URL / matrix permalink inside inline code or a code block must not be linkified
// (removing the clickable span drops both the tap handling and the link colour).
private fun SpannableStringBuilder.removeLinksOverCode() {
    val codeSpans = getSpans(0, length, HtmlCodeSpan::class.java)
    if (codeSpans.isEmpty()) return
    getSpans(0, length, ClickableSpan::class.java).forEach { link ->
        val ls = getSpanStart(link)
        val le = getSpanEnd(link)
        if (codeSpans.any { ls < getSpanEnd(it) && getSpanStart(it) < le }) {
            removeSpan(link)
        }
    }
}

// Linkify can lay a clickable span over an emote's (invisible) alt text; drop those so tapping the emote is inert.
private fun SpannableStringBuilder.removeLinksOverEmotes() {
    val emotes = getSpans(0, length, EmoteImageSpan::class.java)
    if (emotes.isEmpty()) return
    getSpans(0, length, ClickableSpan::class.java).forEach { link ->
        val ls = getSpanStart(link)
        val le = getSpanEnd(link)
        if (emotes.any { ls < getSpanEnd(it) && getSpanStart(it) < le }) {
            removeSpan(link)
        }
    }
}

/**
 * The UrlClickCallback chain doesn't carry which event's text was clicked, but same-room permalink
 * navigation wants the source message (to offer a jump back to it, like the reply-header jump does).
 * Recorded here at click time — the last point where the clicked view is still available — and
 * consumed by the navigation interceptor.
 */
object LinkClickSourceHolder {
    @Volatile private var eventId: String? = null
    fun record(id: String?) {
        eventId = id
    }
    fun consume(): String? = eventId.also { eventId = null }
}

private fun View.findContainingTimelineEventId(): String? {
    var v: View = this
    while (true) {
        val parent = v.parent as? View ?: return null
        if (parent is RecyclerView) {
            val holder = parent.getChildViewHolder(v) as? EpoxyViewHolder ?: return null
            return (holder.model as? ItemWithEvents)?.getEventIds()?.firstOrNull()
        }
        v = parent
    }
}

// Better link movement methods fixes the issue when
// long pressing to open the context menu on a TextView also triggers an autoLink click.
fun createLinkMovementMethod(urlClickCallback: TimelineEventController.UrlClickCallback?): EvenBetterLinkMovementMethod {
    return EvenBetterLinkMovementMethod(object : EvenBetterLinkMovementMethod.OnLinkClickListener {
        override fun onLinkClicked(textView: TextView, span: ClickableSpan, url: String, actualText: String): Boolean {
            // Record before the callback runs: this covers both the handled path and the fallback
            // where the span's own onClick (e.g. MatrixPermalinkSpan) delivers the url without the view.
            LinkClickSourceHolder.record(textView.findContainingTimelineEventId())
            // Always return false if the url is not valid, so the EvenBetterLinkMovementMethod can fallback to default click listener.
            return url.isValidUrl() && urlClickCallback?.onUrlClicked(url, actualText) == true
        }
    })
            .apply {
                // We need also to fix the case when long click on link will trigger long click on cell
                setOnLinkLongClickListener { tv, url ->
                    // Long clicks are handled by parent, return true to block android to do something with url
                    // Always return false if the url is not valid, so the EvenBetterLinkMovementMethod can fallback to default click listener.
                    if (url.isValidUrl() && urlClickCallback?.onUrlLongClicked(url) == true) {
                        tv.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0))
                        true
                    } else {
                        false
                    }
                }
            }
}
