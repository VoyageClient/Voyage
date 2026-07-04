/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.personalization

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import com.airbnb.mvrx.args
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.databinding.FragmentRoomPersonalizationBinding
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

@AndroidEntryPoint
class RoomPersonalizationFragment :
        VectorBaseFragment<FragmentRoomPersonalizationBinding>(),
        VectorMenuProvider {

    private val roomProfileArgs: RoomProfileArgs by args()

    private val settingsFragment
        get() = childFragmentManager.findFragmentById(views.roomPersonalizationContent.id) as? RoomPersonalizationSettingsFragment

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

    override fun getMenuRes() = R.menu.menu_room_personalization

    override fun handlePrepareMenu(menu: Menu) {
        menu.findItem(R.id.roomPersonalizationReset)?.apply {
            isEnabled = settingsFragment?.isPersonalized() ?: false
            val tint = ThemeUtils.getColor(
                    requireContext(),
                    if (isEnabled) im.vector.lib.ui.styles.R.attr.vctr_content_secondary else im.vector.lib.ui.styles.R.attr.vctr_content_quaternary
            )
            icon?.mutate()?.let { DrawableCompat.setTint(it, tint) }
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.roomPersonalizationReset -> {
                confirmReset()
                true
            }
            else -> false
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.action_reset)
                .setMessage(CommonStrings.room_personalization_reset_confirmation)
                .setPositiveButton(CommonStrings.action_reset) { _, _ -> settingsFragment?.resetToAccountProfile() }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }
}
