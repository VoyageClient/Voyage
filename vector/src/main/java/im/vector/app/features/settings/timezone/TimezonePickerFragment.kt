/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.timezone

import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentTimezonePickerBinding
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class TimezonePickerFragment :
        VectorBaseFragment<FragmentTimezonePickerBinding>(),
        TimezonePickerController.Listener {

    @Inject lateinit var controller: TimezonePickerController

    private val viewModel: TimezonePickerViewModel by fragmentViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentTimezonePickerBinding {
        return FragmentTimezonePickerBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.timezoneRecyclerView.configureWith(controller)
        controller.listener = this

        views.timezoneSearch.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable) {
                viewModel.handle(TimezonePickerAction.UpdateFilter(s.toString()))
            }
        })

        viewModel.observeViewEvents {
            when (it) {
                TimezonePickerViewEvents.Close -> parentFragmentManager.popBackStack()
            }
        }
    }

    override fun onDestroyView() {
        views.timezoneRecyclerView.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) { state ->
        controller.setData(state)
    }

    override fun onClearClicked() {
        viewModel.handle(TimezonePickerAction.ClearTimezone)
    }

    override fun onTimezoneClicked(id: String) {
        viewModel.handle(TimezonePickerAction.SelectTimezone(id))
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(CommonStrings.settings_timezone)
    }
}
