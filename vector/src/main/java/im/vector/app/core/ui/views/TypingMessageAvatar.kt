/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.isNotEmpty
import im.vector.app.core.extensions.marginStartCompat
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.util.toMatrixItem

class TypingMessageAvatar @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        const val OVERLAP_FACT0R = -3 // =~ 30% to left
    }

    private val typingAvatarSize by lazy(LazyThreadSafetyMode.NONE) {
        context.resources.getDimension(im.vector.lib.ui.styles.R.dimen.typing_avatar_size).toInt()
    }

    fun render(typingUsers: List<SenderInfo>, avatarRenderer: AvatarRenderer, roomId: String?) {
        removeAllViews()
        for (user in typingUsers.take(avatarStackFaces(typingUsers.size))) {
            val avatar = ImageView(context)
            addStacked(avatar)
            avatarRenderer.render(user.toMatrixItem(), avatar, roomId = roomId)
        }
        val overflow = avatarStackOverflow(typingUsers.size)
        if (overflow > 0) {
            val badge = AppCompatTextView(context)
            badge.setupAvatarCountBadge(typingAvatarSize)
            badge.renderAvatarCount(overflow, avatarRenderer)
            addStacked(badge)
        }
    }

    private fun addStacked(view: View) {
        view.id = ViewCompat.generateViewId()
        val layoutParams = MarginLayoutParams(typingAvatarSize, typingAvatarSize)
        if (isNotEmpty()) layoutParams.marginStartCompat = typingAvatarSize / OVERLAP_FACT0R
        view.layoutParams = layoutParams
        addView(view)
    }
}
