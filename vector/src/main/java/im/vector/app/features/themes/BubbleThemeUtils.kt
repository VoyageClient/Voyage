/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.themes

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Parcelable
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.preference.PreferenceManager
import im.vector.app.R
import im.vector.app.features.home.room.detail.timeline.item.AnonymousReadReceipt
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import timber.log.Timber
import javax.inject.Inject

/**
 * SchildiChat message-bubble styling (tail, roundness). Default style is "none" so nothing changes unless
 * the user opts in.
 */
class BubbleThemeUtils @Inject constructor(private val context: Context) {

    companion object {
        const val BUBBLE_STYLE_KEY = "BUBBLE_STYLE_KEY"
        const val BUBBLE_ROUNDNESS_KEY = "SETTINGS_SC_BUBBLE_ROUNDED_CORNERS"
        const val BUBBLE_TAIL_KEY = "SETTINGS_SC_BUBBLE_TAIL"
        const val BUBBLE_TINT_KEY = "SETTINGS_SC_BUBBLE_TINT"

        const val BUBBLE_ROUNDNESS_DEFAULT = "default"
        const val BUBBLE_ROUNDNESS_R1 = "r1"
        const val BUBBLE_ROUNDNESS_R2 = "r2"

        const val BUBBLE_STYLE_NONE = "none"
        const val BUBBLE_STYLE_ELEMENT = "element"
        const val BUBBLE_STYLE_START = "start"
        const val BUBBLE_STYLE_BOTH = "both"

        const val BUBBLE_TIME_TOP = "top"
        const val BUBBLE_TIME_BOTTOM = "bottom"

        fun getVisibleAnonymousReadReceipts(readReceipt: AnonymousReadReceipt?, sentByMe: Boolean): AnonymousReadReceipt {
            readReceipt ?: return AnonymousReadReceipt.NONE
            return if (sentByMe && readReceipt == AnonymousReadReceipt.PROCESSING) {
                readReceipt
            } else {
                AnonymousReadReceipt.NONE
            }
        }

        fun anonymousReadReceiptForEvent(event: TimelineEvent): AnonymousReadReceipt {
            return if (event.root.sendState == SendState.SYNCED || event.root.sendState == SendState.SENT) {
                AnonymousReadReceipt.NONE
            } else {
                AnonymousReadReceipt.PROCESSING
            }
        }
    }

    fun getBubbleStyle(): String {
        val bubbleStyle = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(BUBBLE_STYLE_KEY, BUBBLE_STYLE_NONE) ?: BUBBLE_STYLE_NONE
        if (bubbleStyle !in listOf(BUBBLE_STYLE_NONE, BUBBLE_STYLE_ELEMENT, BUBBLE_STYLE_START, BUBBLE_STYLE_BOTH)) {
            Timber.e("Ignoring invalid bubble style setting: $bubbleStyle")
            return BUBBLE_STYLE_NONE
        }
        return bubbleStyle
    }

    fun isBubbleEnabled(): Boolean = getBubbleStyle() != BUBBLE_STYLE_NONE

    // SchildiChat-rendered bubbles (dual or single sided). "element" uses Element's own bubble renderer.
    fun isScBubble(): Boolean = getBubbleStyle() in listOf(BUBBLE_STYLE_START, BUBBLE_STYLE_BOTH)

    // Element does not tint outgoing bubbles, SchildiChat keeps them neutral too. This opt-in (default on)
    // tints outgoing bubbles with the current accent colour.
    fun isOutgoingTintEnabled(): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(BUBBLE_TINT_KEY, true)

    private fun getBubbleRoundnessSetting(): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(BUBBLE_ROUNDNESS_KEY, BUBBLE_ROUNDNESS_DEFAULT) ?: BUBBLE_ROUNDNESS_DEFAULT
    }

    fun getBubbleAppearance(): ScBubbleAppearance {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val baseAppearance = when (getBubbleRoundnessSetting()) {
            BUBBLE_ROUNDNESS_R1 -> r1ScBubbleAppearance
            BUBBLE_ROUNDNESS_R2 -> r2ScBubbleAppearance
            else -> defaultScBubbleAppearance
        }
        return if (prefs.getBoolean(BUBBLE_TAIL_KEY, true)) {
            baseAppearance
        } else {
            baseAppearance.copy(
                    textBubbleOutgoing = baseAppearance.textBubbleOutgoingNoTail,
                    textBubbleIncoming = baseAppearance.textBubbleIncomingNoTail
            )
        }
    }
}

fun guessTextWidth(view: TextView): Float {
    return guessTextWidth(view, view.text)
}

fun guessTextWidth(view: TextView, text: CharSequence): Float {
    return guessTextWidth(view.textSize, text, view.typeface) + view.paddingLeft + view.paddingRight
}

fun guessTextWidth(textSize: Float, text: CharSequence, typeface: Typeface? = null): Float {
    val paint = Paint()
    paint.textSize = textSize
    typeface?.let { paint.typeface = it }
    return paint.measureText(text, 0, text.length)
}

@Parcelize
data class ScBubbleAppearance(
        @DimenRes val roundness: Int,
        @DrawableRes val textBubbleOutgoing: Int,
        @DrawableRes val textBubbleIncoming: Int,
        @DrawableRes val textBubbleOutgoingNoTail: Int,
        @DrawableRes val textBubbleIncomingNoTail: Int,
        @DrawableRes val timestampOverlay: Int,
        @DrawableRes val imageBorderOutgoing: Int,
        @DrawableRes val imageBorderIncoming: Int,
) : Parcelable {
    fun getBubbleRadiusPx(context: Context): Int = context.resources.getDimensionPixelSize(roundness)
    fun getBubbleRadiusDp(context: Context): Float =
            context.resources.getDimension(roundness) / context.resources.displayMetrics.density
}

val defaultScBubbleAppearance = ScBubbleAppearance(
        im.vector.lib.ui.styles.R.dimen.sc_bubble_radius,
        R.drawable.msg_bubble_text_outgoing,
        R.drawable.msg_bubble_text_incoming,
        R.drawable.msg_bubble_text_outgoing_notail,
        R.drawable.msg_bubble_text_incoming_notail,
        R.drawable.timestamp_overlay,
        R.drawable.background_image_border_outgoing,
        R.drawable.background_image_border_incoming,
)

val r1ScBubbleAppearance = ScBubbleAppearance(
        im.vector.lib.ui.styles.R.dimen.sc_bubble_r1_radius,
        R.drawable.msg_bubble_r1_text_outgoing,
        R.drawable.msg_bubble_r1_text_incoming,
        R.drawable.msg_bubble_r1_text_outgoing_notail,
        R.drawable.msg_bubble_r1_text_incoming_notail,
        R.drawable.timestamp_overlay_r1,
        R.drawable.background_image_border_outgoing_r1,
        R.drawable.background_image_border_incoming_r1,
)

val r2ScBubbleAppearance = ScBubbleAppearance(
        im.vector.lib.ui.styles.R.dimen.sc_bubble_r2_radius,
        R.drawable.msg_bubble_r2_text_outgoing,
        R.drawable.msg_bubble_r2_text_incoming,
        R.drawable.msg_bubble_r2_text_outgoing_notail,
        R.drawable.msg_bubble_r2_text_incoming_notail,
        R.drawable.timestamp_overlay_r2,
        R.drawable.background_image_border_outgoing_r2,
        R.drawable.background_image_border_incoming_r2,
)
