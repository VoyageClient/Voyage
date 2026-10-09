/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.ContextThemeWrapper
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import dagger.Lazy
import im.vector.app.R
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.views.ForwardFrame
import im.vector.app.core.ui.views.ForwardFrameLayout
import im.vector.app.features.home.room.detail.timeline.tools.SenderNameSpan
import im.vector.app.features.home.room.detail.timeline.tools.asEmoteBody
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.ForwardBarSpan
import im.vector.app.features.share.forwardedSenderDisplayName
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.model.message.getForwardedInfo
import org.matrix.android.sdk.api.session.room.model.message.isForward
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject

/**
 * How a forward (MSC4553) reads wherever it is shown: who it is from, where and when it was first sent,
 * and who a forwarded emote is by. The timeline frames it with views; [framed] draws the same frame
 * inside a single text view for the previews that only have one.
 */
class ForwardedMessagePresenter @Inject constructor(
        private val context: Context,
        // Lazy: the session holder's own graph reaches back to the formatters that use this.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        private val stringProvider: StringProvider,
        private val matrixItemColorProvider: MatrixItemColorProvider,
        private val dateFormatter: VectorDateFormatter,
) {

    /** The original sender, or only a [name] when the forward doesn't say who that was. */
    class Origin(val name: String, val user: MatrixItem.UserItem?)

    class Description(
            val origin: Origin,
            /** Name of the room the original was sent in, when we know it. */
            val roomName: String?,
            val formattedDate: String?,
    )

    /** Null when [event] isn't a forward. */
    fun describe(event: TimelineEvent): Description? {
        val content = event.root.getClearContent()
        if (!content.isForward()) return null
        val info = content.getForwardedInfo()
        val session = activeSessionHolder.get().getSafeActiveSession()
        val sender = info?.sender
        val origin = if (sender == null) {
            Origin(stringProvider.getString(CommonStrings.forwarded_emote_generic_sender), null)
        } else {
            val name = session?.forwardedSenderDisplayName(sender, info.roomId, event.roomId)
            Origin(name ?: sender, MatrixItem.UserItem(sender, name))
        }
        return Description(
                origin = origin,
                roomName = info?.roomId?.let { session?.getRoomSummary(it)?.displayName },
                formattedDate = info?.originServerTs?.let { dateFormatter.format(it, DateFormatKind.DEFAULT_DATE_AND_TIME) },
        )
    }

    fun origin(event: TimelineEvent): Origin? = describe(event)?.origin

    /** [body] inside the frame the timeline draws around a forward, or [body] alone for anything else. */
    fun framed(event: TimelineEvent, body: CharSequence): CharSequence {
        val description = describe(event) ?: return body
        val text = header(description).append("\n").append(body).append("\n").append(footer(description))
        text.setSpan(barSpan(), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return text
    }

    /**
     * The frame for a preview that draws its media as views above its text: the header and bar go around
     * the media ([ForwardFrameLayout]), and the text carries the rest of the frame ([framedTail]).
     */
    fun previewFrame(event: TimelineEvent): ForwardFrame? {
        val description = describe(event) ?: return null
        val res = context.resources
        return ForwardFrame(
                header = header(description).toEpoxyCharSequence(),
                barWidth = res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.forwarded_bar_width),
                gap = barGap(),
                color = barColor(),
        )
    }

    /** The text under a [previewFrame]: [body] (empty for uncaptioned media) then the footer, on the same bar. */
    fun framedTail(event: TimelineEvent, body: CharSequence): CharSequence {
        val description = describe(event) ?: return body
        val text = SpannableStringBuilder()
        if (body.isNotEmpty()) text.append(body).append("\n")
        text.append(footer(description))
        text.setSpan(barSpan(), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return text
    }

    private fun themed() = ContextThemeWrapper(context, ThemeUtils.getApplicationThemeRes(context))

    private fun secondaryColor() = ThemeUtils.getColor(themed(), im.vector.lib.ui.styles.R.attr.vctr_content_secondary)

    private fun captionSize() = context.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.text_size_caption)

    private fun header(description: Description): SpannableStringBuilder {
        val themed = themed()
        val secondary = secondaryColor()
        val captionSize = captionSize()
        val text = SpannableStringBuilder()
        val user = description.origin.user
        val header = if (user != null) {
            stringProvider.getString(CommonStrings.forwarded_message_from_user, description.origin.name)
        } else {
            stringProvider.getString(CommonStrings.forwarded_message)
        }.prepareForDisplay()
        AppCompatResources.getDrawable(themed, R.drawable.ic_forward)?.mutate()?.let { drawable ->
            val icon = DrawableCompat.wrap(drawable)
            DrawableCompat.setTint(icon, secondary)
            val size = (captionSize * FORWARDED_ICON_SCALE).toInt()
            icon.setBounds(0, 0, size, size)
            text.append(" ")
            text.setSpan(ImageSpan(icon, ImageSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(" ")
        }
        val headerStart = text.length
        text.append(header)
        text.setSpan(ForegroundColorSpan(secondary), headerStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), headerStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (user != null) {
            val nameStart = header.indexOf(description.origin.name).takeIf { it >= 0 }?.plus(headerStart)
            nameStart?.let { text.setSpan(SenderNameSpan(user, matrixItemColorProvider), it, it + description.origin.name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        text.setSpan(AbsoluteSizeSpan(captionSize), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun footer(description: Description): CharSequence {
        val text = SpannableStringBuilder(
                listOfNotNull(description.roomName ?: stringProvider.getString(CommonStrings.forwarded_unknown_room), description.formattedDate)
                        .joinToString(stringProvider.getString(CommonStrings.forwarded_footer_separator))
        )
        text.setSpan(ForegroundColorSpan(secondaryColor()), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(AbsoluteSizeSpan(captionSize()), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun barGap(): Int {
        val res = context.resources
        return res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.forwarded_bubble_text_inset) -
                res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.forwarded_bar_width)
    }

    private fun barColor() = ThemeUtils.getColor(themed(), im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)

    private fun barSpan() = ForwardBarSpan(
            barWidth = context.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.forwarded_bar_width),
            gap = barGap(),
            color = barColor(),
    )

    /** [body] as an emote, by the original sender when [event] is a forward and otherwise by [ownName]. */
    fun emoteBody(event: TimelineEvent, body: CharSequence, ownName: CharSequence, ownItem: MatrixItem): CharSequence {
        val origin = origin(event) ?: return body.asEmoteBody(ownName, SenderNameSpan(ownItem, matrixItemColorProvider))
        return body.asEmoteBody(origin.name, origin.user?.let { SenderNameSpan(it, matrixItemColorProvider) }, boldSender = true)
    }

    private companion object {
        // Same proportion as the timeline header's icon.
        const val FORWARDED_ICON_SCALE = 1.15f
    }
}
