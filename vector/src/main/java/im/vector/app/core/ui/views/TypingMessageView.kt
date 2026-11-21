/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.databinding.TypingMessageLayoutBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.home.room.detail.timeline.tools.SenderNameSpan
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.home.room.typing.TypingHelper
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class TypingMessageView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val SLIDE_MS = 200L
    }

    val views: TypingMessageLayoutBinding

    @Inject
    lateinit var typingHelper: TypingHelper

    @Inject
    lateinit var matrixItemColorProvider: MatrixItemColorProvider

    /** Receives how many pixels of the indicator currently show above its resting bottom edge. */
    var onShownHeightChanged: ((Int) -> Unit)? = null

    var shownUsers: List<SenderInfo> = emptyList()
        private set
    private var shownFraction = 0f
    private var slide: ValueAnimator? = null

    private var renderedNames: List<CharSequence> = emptyList()
    private var fittedWidth = -1

    init {
        inflate(context, R.layout.typing_message_layout, this)
        views = TypingMessageLayoutBinding.bind(this)
    }

    fun render(typingUsers: List<SenderInfo>, avatarRenderer: AvatarRenderer, roomId: String?) {
        renderedNames = typingUsers.map { styledName(it) }
        fittedWidth = -1
        views.typingUserText.text = typingHelper.getTypingLine(renderedNames)
        views.typingUserAvatars.render(typingUsers, avatarRenderer, roomId)
    }

    fun refreshNameColors() {
        views.typingUserText.invalidate()
    }

    private fun styledName(user: SenderInfo): CharSequence {
        return SpannableStringBuilder(user.disambiguatedDisplayName.prepareForDisplay()).apply {
            setSpan(SenderNameSpan(user.toMatrixItem(), matrixItemColorProvider), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val available = measuredWidth - paddingLeft - paddingRight -
                views.typingUserAvatars.horizontalSpace() - views.typingUserText.horizontalMargins()
        if (available == fittedWidth) return
        fittedWidth = available
        if (fitTypingLine(available)) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // Shortens the names rather than the line, so "is typing…" stays visible. Returns whether the text changed.
    private fun fitTypingLine(available: Int): Boolean {
        val paint = views.typingUserText.paint
        val full = typingHelper.getTypingLine(renderedNames)
        if (available <= 0 || renderedNames.isEmpty() || Layout.getDesiredWidth(full, paint) <= available) {
            return setTypingLine(full)
        }
        val frame = Layout.getDesiredWidth(typingHelper.getTypingLine(renderedNames.map { "" }), paint)
        val widths = renderedNames.map { Layout.getDesiredWidth(it, paint) }
        val shares = fitNameWidths(widths, available - frame)
        val fitted = renderedNames.mapIndexed { i, name ->
            if (widths[i] <= shares[i]) name else TextUtils.ellipsize(name, paint, shares[i], TextUtils.TruncateAt.END)
        }
        return setTypingLine(typingHelper.getTypingLine(fitted))
    }

    private fun setTypingLine(text: CharSequence): Boolean {
        if (TextUtils.equals(views.typingUserText.text, text)) return false
        views.typingUserText.text = text
        return true
    }

    private fun View.horizontalSpace(): Int = if (isGone) 0 else measuredWidth + horizontalMargins()

    private fun View.horizontalMargins(): Int {
        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return 0
        return MarginLayoutParamsCompat.getMarginStart(params) + MarginLayoutParamsCompat.getMarginEnd(params)
    }

    // Slides up from and back down past its bottom edge, so the parent has to clip it there.
    fun showTypingUsers(typingUsers: List<SenderInfo>, avatarRenderer: AvatarRenderer, roomId: String?) {
        if (typingUsers == shownUsers) return
        shownUsers = typingUsers
        if (typingUsers.isNotEmpty()) render(typingUsers, avatarRenderer, roomId)
        slideTo(if (typingUsers.isEmpty()) 0f else 1f)
    }

    private fun slideTo(target: Float) {
        slide?.cancel()
        if (shownFraction == target) return
        isVisible = true
        slide = ValueAnimator.ofFloat(shownFraction, target).apply {
            duration = (SLIDE_MS * abs(target - shownFraction)).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyShownFraction(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (shownFraction == 0f) isInvisible = true
                }
            })
            start()
        }
    }

    private fun applyShownFraction(fraction: Float) {
        shownFraction = fraction
        translationY = height * (1 - fraction)
        onShownHeightChanged?.invoke((height * fraction).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyShownFraction(shownFraction)
    }

    override fun onDetachedFromWindow() {
        slide?.end()
        super.onDetachedFromWindow()
    }
}

/** Water-fills [budget] across names: those narrower than an even share keep their width, the rest split what's left. */
internal fun fitNameWidths(widths: List<Float>, budget: Float): List<Float> {
    val shares = FloatArray(widths.size)
    var remaining = budget.coerceAtLeast(0f)
    val open = widths.indices.sortedBy { widths[it] }.toMutableList()
    while (open.isNotEmpty()) {
        val share = remaining / open.size
        val narrowest = open.first()
        if (widths[narrowest] > share) {
            open.forEach { shares[it] = share }
            break
        }
        shares[narrowest] = widths[narrowest]
        remaining -= widths[narrowest]
        open.removeAt(0)
    }
    return shares.toList()
}
