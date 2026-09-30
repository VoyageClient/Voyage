/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.locale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.TextUtilsCompat
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.epoxy.ListDividerDecoration
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.layoutDirectionCompat
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentLocalePickerBinding
import im.vector.app.features.configuration.VectorConfiguration
import im.vector.app.features.settings.VectorSettingsActivity
import im.vector.lib.strings.CommonStrings
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class LocalePickerFragment :
        VectorBaseFragment<FragmentLocalePickerBinding>(),
        LocalePickerController.Listener,
        OnBackPressed {

    @Inject lateinit var controller: LocalePickerController
    @Inject lateinit var vectorConfiguration: VectorConfiguration

    private val viewModel: LocalePickerViewModel by fragmentViewModel()

    // Set once the locale was switched in place: the screens below were built with the old one.
    private var localeSwitched = false

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLocalePickerBinding {
        return FragmentLocalePickerBinding.inflate(inflater, container, false)
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        if (!localeSwitched) return false
        val activity = activity ?: return false
        if (activity is VectorSettingsActivity) {
            activity.popAndRebuildBackStack()
        } else {
            (activity as? VectorBaseActivity<*>)?.acknowledgeConfigurationChange()
            activity.recreate()
        }
        return true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.localeRecyclerView.configureWith(controller)
        views.localeRecyclerView.addItemDecoration(ListDividerDecoration(requireContext()))
        controller.listener = this

        viewModel.observeViewEvents {
            when (it) {
                LocalePickerViewEvents.LocaleApplied -> applyLocaleInPlace()
            }
        }
    }

    private fun applyLocaleInPlace() {
        val activity = activity as? AppCompatActivity ?: return
        vectorConfiguration.applyToResources(activity.resources)
        // Explicit rather than LAYOUT_DIRECTION_LOCALE: re-setting an unchanged value doesn't re-resolve, so
        // switching between two locales would stay stuck on the first direction.
        activity.window.decorView.layoutDirectionCompat = TextUtilsCompat.getLayoutDirectionFromLocale(Locale.getDefault())
        (activity as? VectorBaseActivity<*>)?.acknowledgeConfigurationChange()
        activity.supportActionBar?.setTitle(CommonStrings.settings_select_language)
        localeSwitched = true
    }

    override fun onDestroyView() {
        views.localeRecyclerView.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) { state ->
        controller.setData(state)
    }

    override fun onLocaleClicked(locale: Locale) {
        viewModel.handle(LocalePickerAction.SelectLocale(locale))
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(CommonStrings.settings_select_language)
    }
}
