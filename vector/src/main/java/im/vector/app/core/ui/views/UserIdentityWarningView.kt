/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.text.HtmlCompat
import im.vector.app.R
import im.vector.app.databinding.ViewUserIdentityWarningBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.UserIdentityChangePrompt
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.util.MatrixItem

class UserIdentityWarningView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Callback {
        fun onUserIdentityWarningDismissed(userId: String)
    }

    private val views: ViewUserIdentityWarningBinding
    var callback: Callback? = null
    private var boundUserId: String? = null

    init {
        orientation = VERTICAL
        inflate(context, R.layout.view_user_identity_warning, this)
        views = ViewUserIdentityWarningBinding.bind(this)
        views.userIdentityWarningText.movementMethod = LinkMovementMethod.getInstance()
        views.userIdentityWarningButton.setOnClickListener {
            boundUserId?.let { callback?.onUserIdentityWarningDismissed(it) }
        }
    }

    fun render(prompt: UserIdentityChangePrompt, avatarRenderer: AvatarRenderer) {
        boundUserId = prompt.userId
        val matrixItem = MatrixItem.UserItem(prompt.userId, prompt.displayName, prompt.avatarUrl)
        avatarRenderer.render(matrixItem, views.userIdentityWarningAvatar)

        val displayName = prompt.displayName
        val html = if (displayName.isNullOrEmpty() || displayName == prompt.userId) {
            resources.getString(CommonStrings.identity_reset_banner_no_displayname, prompt.userId)
        } else {
            resources.getString(CommonStrings.identity_reset_banner, displayName, prompt.userId)
        }
        views.userIdentityWarningText.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        val textColorAttr = if (prompt.critical) {
            com.google.android.material.R.attr.colorError
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_content_secondary
        }
        views.userIdentityWarningText.setTextColor(ThemeUtils.getColor(context, textColorAttr))

        views.userIdentityWarningButton.setText(
                if (prompt.critical) CommonStrings.identity_reset_withdraw_verification else CommonStrings.action_dismiss
        )
    }
}
