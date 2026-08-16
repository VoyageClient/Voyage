/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import im.vector.app.R
import im.vector.app.core.utils.setReadOnlySelectable
import im.vector.app.databinding.ViewFailedMessagesWarningBinding
import im.vector.lib.strings.CommonStrings

class FailedMessagesWarningView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    interface Callback {
        fun onDeleteAllClicked()
        fun onRetryClicked()
    }

    var callback: Callback? = null

    private lateinit var views: ViewFailedMessagesWarningBinding

    init {
        setupViews()
    }

    private fun setupViews() {
        inflate(context, R.layout.view_failed_messages_warning, this)
        views = ViewFailedMessagesWarningBinding.bind(this)

        views.failedMessagesDeleteAllButton.setOnClickListener { callback?.onDeleteAllClicked() }
        views.failedMessagesRetryButton.setOnClickListener { callback?.onRetryClicked() }

        views.failedMessagesWarningTextView.setReadOnlySelectable(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            views.failedMessagesWarningTextView.showSoftInputOnFocus = false
        }
    }

    fun render(text: String?) {
        views.failedMessagesWarningTextView.text = text ?: context.getString(CommonStrings.event_status_failed_messages_warning)
    }
}
