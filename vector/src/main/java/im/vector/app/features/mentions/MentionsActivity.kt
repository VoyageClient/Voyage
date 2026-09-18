/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.mentions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivitySimpleBinding

@AndroidEntryPoint
class MentionsActivity : VectorBaseActivity<ActivitySimpleBinding>() {

    override fun getBinding() = ActivitySimpleBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.coordinatorLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFirstCreation()) {
            addFragment(views.simpleFragmentContainer, MentionsFragment::class.java)
        }
    }

    companion object {
        fun newIntent(context: Context) = Intent(context, MentionsActivity::class.java)
    }
}
