/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.preference

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.features.home.BannerRenderer

/**
 * Preference row previewing an MSC4427 profile banner (or its per-room override).
 */
class UserBannerPreference : Preference {
    private var mBannerContainer: View? = null
    private var mBannerView: ImageView? = null
    private var mLoadingProgressBar: ProgressBar? = null

    private var bannerRenderer: BannerRenderer = context.singletonEntryPoint().let {
        BannerRenderer(it.activeSessionHolder(), it.vectorPreferences())
    }

    private var mxcUrl: String? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    init {
        widgetLayoutResource = R.layout.vector_settings_wide_banner
        // Set to false to remove the space when there is no icon
        isIconSpaceReserved = true
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        mBannerContainer = holder.itemView.findViewById(R.id.settings_banner_container)
        mBannerView = holder.itemView.findViewById(R.id.settings_banner)
        mLoadingProgressBar = holder.itemView.findViewById(R.id.banner_update_progress_bar)
        refreshUi()
    }

    fun refreshBanner(mxcUrl: String?) {
        this.mxcUrl = mxcUrl
        refreshUi()
    }

    private fun refreshUi() {
        mBannerContainer?.isVisible = mxcUrl != null
        mBannerView?.let { bannerRenderer.render(mxcUrl, it) }
    }
}
