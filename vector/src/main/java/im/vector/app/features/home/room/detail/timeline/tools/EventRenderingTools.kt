/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.tools

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.text.toSpannable
import im.vector.app.core.linkify.VectorLinkify
import im.vector.app.core.utils.EvenBetterLinkMovementMethod
import im.vector.app.core.utils.isValidUrl
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.SpoilerSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.permalinks.MatrixLinkify
import org.matrix.android.sdk.api.session.permalinks.MatrixPermalinkSpan

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
fun TextView.applySpoilerRenderLayer() {
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
    return spannable
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

// Better link movement methods fixes the issue when
// long pressing to open the context menu on a TextView also triggers an autoLink click.
fun createLinkMovementMethod(urlClickCallback: TimelineEventController.UrlClickCallback?): EvenBetterLinkMovementMethod {
    return EvenBetterLinkMovementMethod(object : EvenBetterLinkMovementMethod.OnLinkClickListener {
        override fun onLinkClicked(textView: TextView, span: ClickableSpan, url: String, actualText: String): Boolean {
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
