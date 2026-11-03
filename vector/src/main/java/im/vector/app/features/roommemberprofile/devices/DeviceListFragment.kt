/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.roommemberprofile.devices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.mvrx.parentFragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.databinding.FragmentDeviceListBinding
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import javax.inject.Inject

@AndroidEntryPoint
class DeviceListFragment :
        VectorBaseFragment<FragmentDeviceListBinding>(),
        DeviceListEpoxyController.InteractionListener {

    @Inject lateinit var dimensionConverter: DimensionConverter
    @Inject lateinit var headerController: DeviceListEpoxyController
    @Inject lateinit var epoxyController: DeviceListEpoxyController

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentDeviceListBinding {
        return FragmentDeviceListBinding.inflate(inflater, container, false)
    }

    private val viewModel: DeviceListBottomSheetViewModel by parentFragmentViewModel(DeviceListBottomSheetViewModel::class)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headerController.part = DeviceListEpoxyController.Part.HEADER
        // Not a nested-scrolling child, so dragging the header moves the sheet.
        views.deviceListHeader.isNestedScrollingEnabled = false
        views.deviceListHeader.configureWith(headerController, hasFixedSize = false)

        // The list never drags the sheet: no nested scrolling, and the sheet may not intercept its gestures.
        views.deviceListRecyclerView.isNestedScrollingEnabled = false
        views.deviceListRecyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) rv.parent?.requestDisallowInterceptTouchEvent(true)
                return false
            }
        })
        views.deviceListRecyclerView.setPadding(0, 0, 0, dimensionConverter.dpToPx(16))
        views.deviceListRecyclerView.configureWith(epoxyController, hasFixedSize = false)
        epoxyController.interactionListener = this
    }

    override fun onDestroyView() {
        views.deviceListHeader.cleanup()
        views.deviceListRecyclerView.cleanup()
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) {
        headerController.setData(it)
        epoxyController.setData(it)
    }

    override fun onDeviceSelected(device: CryptoDeviceInfo) {
        viewModel.handle(DeviceListAction.SelectDevice(device))
    }
}
