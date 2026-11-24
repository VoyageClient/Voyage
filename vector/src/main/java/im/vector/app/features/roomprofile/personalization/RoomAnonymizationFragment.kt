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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.airbnb.mvrx.args
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.preference.VectorPreferenceCategoryWithAction
import im.vector.app.databinding.FragmentRoomPersonalizationBinding
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.app.features.timelineexport.AnonymizationPeoplePickerActivity
import im.vector.app.features.timelineexport.AnonymizationPreferenceBinder
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.profile.IdentityPresentation

/** A room's anonymization, opened from its personalization screen as the global one is from Security & Privacy. */
@AndroidEntryPoint
class RoomAnonymizationFragment : VectorBaseFragment<FragmentRoomPersonalizationBinding>() {

    private val roomProfileArgs: RoomProfileArgs by args()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) = FragmentRoomPersonalizationBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar(views.roomPersonalizationToolbar)
                .setTitle(CommonStrings.export_anonymization)
                .allowBack()
        if (childFragmentManager.findFragmentById(views.roomPersonalizationContent.id) == null) {
            childFragmentManager.beginTransaction()
                    .replace(views.roomPersonalizationContent.id, RoomAnonymizationSettingsFragment.newInstance(roomProfileArgs.roomId))
                    .commit()
        }
    }
}

@AndroidEntryPoint
class RoomAnonymizationSettingsFragment : VectorSettingsBaseFragment() {

    override var titleRes = CommonStrings.export_anonymization
    override val preferenceXmlRes = R.xml.vector_settings_room_anonymization

    private val roomId: String by lazy { requireArguments().getString(ARG_ROOM_ID)!! }

    private val peoplePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayExtra(AnonymizationPeoplePickerActivity.EXTRA_USER_IDS)?.let { binder.onPeoplePicked(it.toList()) }
    }

    private val binder: AnonymizationPreferenceBinder by lazy {
        AnonymizationPreferenceBinder(this, session, launchPicker = { title, excluded, roomId ->
            peoplePicker.launch(AnonymizationPeoplePickerActivity.intent(requireContext(), title, excluded, roomId))
        }, onChanged = ::refreshResetState)
    }

    private val category get() = findPreference<VectorPreferenceCategoryWithAction>(AnonymizationPreferenceBinder.CATEGORY_KEY)

    override fun bindPref() {
        val category = category ?: return
        binder.bind(category, roomId)
        // The room's other override categories reset the same way.
        category.actionClickListener = {
            AlertDialog.Builder(requireContext())
                    .setTitle(CommonStrings.action_reset)
                    .setMessage(CommonStrings.room_personalization_anonymization_reset_confirmation)
                    .setPositiveButton(CommonStrings.action_reset) { _, _ ->
                        val config = IdentityPresentation.config(session.myUserId)
                        IdentityPresentation.update(session.myUserId, config.copy(rooms = config.rooms - roomId))
                        binder.bind(category, roomId)
                        refreshResetState()
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
        }
        refreshResetState()
    }

    private fun refreshResetState() {
        category?.isActionEnabled = roomId in IdentityPresentation.config(session.myUserId).rooms
    }

    companion object {
        private const val ARG_ROOM_ID = "ARG_ROOM_ID"

        fun newInstance(roomId: String) = RoomAnonymizationSettingsFragment().apply {
            arguments = Bundle().apply { putString(ARG_ROOM_ID, roomId) }
        }
    }
}
