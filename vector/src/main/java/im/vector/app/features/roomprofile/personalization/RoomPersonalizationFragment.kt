/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.personalization

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.airbnb.mvrx.args
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentRoomPersonalizationBinding
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.lib.strings.CommonStrings

@AndroidEntryPoint
class RoomPersonalizationFragment :
        VectorBaseFragment<FragmentRoomPersonalizationBinding>() {

    private val roomProfileArgs: RoomProfileArgs by args()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomPersonalizationBinding {
        return FragmentRoomPersonalizationBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar(views.roomPersonalizationToolbar)
                .setTitle(CommonStrings.room_profile_section_more_personalization)
                .allowBack()

        if (childFragmentManager.findFragmentById(views.roomPersonalizationContent.id) == null) {
            childFragmentManager.beginTransaction()
                    .replace(
                            views.roomPersonalizationContent.id,
                            RoomPersonalizationSettingsFragment.newInstance(roomProfileArgs.roomId)
                    )
                    .commit()
        }
    }
}
