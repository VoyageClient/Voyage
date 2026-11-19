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
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.databinding.TypingMessageLayoutBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.home.room.typing.TypingHelper
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
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
        const val MAX_TYPING_USERS_COUNT = 4
        private const val SLIDE_MS = 200L
    }

    val views: TypingMessageLayoutBinding

    @Inject
    lateinit var typingHelper: TypingHelper

    /** Receives how many pixels of the indicator currently show above its resting bottom edge. */
    var onShownHeightChanged: ((Int) -> Unit)? = null

    private var shownUsers: List<SenderInfo> = emptyList()
    private var shownFraction = 0f
    private var slide: ValueAnimator? = null

    init {
        inflate(context, R.layout.typing_message_layout, this)
        views = TypingMessageLayoutBinding.bind(this)
    }

    fun render(typingUsers: List<SenderInfo>, avatarRenderer: AvatarRenderer, roomId: String?) {
        views.typingUserText.text = typingHelper.getNotificationTypingMessage(typingUsers).prepareForDisplay()
        views.typingUserAvatars.render(typingUsers, avatarRenderer, roomId)
    }

    // Slides up from and back down past its bottom edge, so the parent has to clip it there.
    fun showTypingUsers(users: List<SenderInfo>, avatarRenderer: AvatarRenderer, roomId: String?) {
        val typingUsers = users.take(MAX_TYPING_USERS_COUNT)
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
