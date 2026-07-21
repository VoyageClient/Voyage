/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.databinding.ViewMassRedactionBannerBinding
import im.vector.app.features.redaction.MassRedactionState
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings

class MassRedactionBannerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Callback {
        fun onMassRedactionPauseToggled()
        fun onMassRedactionCancelled()
    }

    private val views: ViewMassRedactionBannerBinding
    var callback: Callback? = null

    init {
        orientation = VERTICAL
        inflate(context, R.layout.view_mass_redaction_banner, this)
        views = ViewMassRedactionBannerBinding.bind(this)
        views.massRedactionPauseButton.setOnClickListener { callback?.onMassRedactionPauseToggled() }
        views.massRedactionCancelButton.setOnClickListener { callback?.onMassRedactionCancelled() }
    }

    fun render(state: MassRedactionState?) {
        if (state == null) {
            isVisible = false
            return
        }
        isVisible = true
        views.massRedactionLabel.text = resources.getString(CommonStrings.mass_redaction_redacting_from, state.targetDisplayName.neutralizeDirectionOverrides())
        views.massRedactionCount.text = "${state.completed}/${state.total}"
        views.massRedactionProgress.isIndeterminate = state.total == 0 && !state.paused
        if (state.total > 0) {
            views.massRedactionProgress.max = state.total
            views.massRedactionProgress.progress = state.completed
        }
        views.massRedactionPauseButton.setImageResource(
                if (state.paused) R.drawable.ic_play_arrow else R.drawable.ic_pause
        )
        views.massRedactionPauseButton.contentDescription = resources.getString(
                if (state.paused) CommonStrings.mass_redaction_resume else CommonStrings.mass_redaction_pause
        )
    }

    fun setTopDividerVisible(visible: Boolean) {
        views.massRedactionTopDivider.isVisible = visible
    }
}
