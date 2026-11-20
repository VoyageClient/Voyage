/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import com.airbnb.epoxy.EpoxyTouchHelper
import com.airbnb.mvrx.args
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.epoxy.ListDividerDecoration
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.ui.colorpicker.ProfileColorPickerDialogFragment
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentGradientEditBinding
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class GradientEditFragment :
        VectorBaseFragment<FragmentGradientEditBinding>(),
        GradientEditController.Listener,
        VectorMenuProvider,
        OnBackPressed {

    @Inject lateinit var controller: GradientEditController
    @Inject lateinit var vectorPreferences: VectorPreferences

    private val pageArgs: GradientEditArgs by args()
    private val editViewModel: GradientEditViewModel by viewModels()
    private val colors get() = editViewModel.colors

    // Back from the eyedropper, the picker reopens on the sampled color (or as it was, if cancelled).
    private val eyedropperLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sampled = result.data?.getIntArrayExtra(GradientImageSamplerActivity.EXTRA_COLORS)?.firstOrNull()
                ?.takeIf { result.resultCode == Activity.RESULT_OK }
        reopenPicker(sampled)
    }

    private val eyedropperImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri == null) {
            reopenPicker(sampled = null)
        } else {
            eyedropperLauncher.launch(GradientImageSamplerActivity.newIntent(requireContext(), maxPoints = 1, imageUri = uri))
        }
    }

    private val fromImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sampled = result.data?.getIntArrayExtra(GradientImageSamplerActivity.EXTRA_COLORS)
        if (result.resultCode != Activity.RESULT_OK || sampled == null) return@registerForActivityResult
        sampled.take(CustomGradient.MAX_COLORS - colors.size).forEach { colors += editViewModel.newColor(it) }
        if (view != null) refresh()
    }

    private val fromImageImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK } ?: return@registerForActivityResult
        fromImageLauncher.launch(GradientImageSamplerActivity.newIntent(requireContext(), roomForColors(), imageUri = uri))
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentGradientEditBinding.inflate(inflater, container, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.setFragmentResultListener(COLOR_REQUEST_KEY, this) { _, result ->
            if (result.getBoolean(ProfileColorPickerDialogFragment.RESULT_EYEDROPPER)) {
                openEyedropper()
                return@setFragmentResultListener
            }
            val hex = result.getString(ProfileColorPickerDialogFragment.RESULT_HEX) ?: return@setFragmentResultListener
            onColorPicked(hex.toColorInt() and 0xFFFFFF)
        }
    }

    // The sampler only opens once an image has been picked.
    private fun openEyedropper() {
        if (!launchImagePicker(eyedropperImageLauncher)) reopenPicker(sampled = null)
    }

    private fun openFromImage() {
        launchImagePicker(fromImageImageLauncher)
    }

    private fun launchImagePicker(launcher: ActivityResultLauncher<Intent>): Boolean {
        return try {
            launcher.launch(GradientImageSamplerActivity.imagePickerIntent())
            true
        } catch (_: ActivityNotFoundException) {
            requireActivity().toast(CommonStrings.error_no_external_application_found)
            false
        }
    }

    private fun roomForColors() = CustomGradient.MAX_COLORS - colors.size

    private fun reopenPicker(sampled: Int?) {
        val id = editViewModel.pickingId ?: return
        openPicker(id, sampled ?: colors.firstOrNull { it.id == id }?.color)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!editViewModel.loaded) {
            vectorPreferences.getCustomGradients().firstOrNull { it.label == pageArgs.label }?.let { gradient ->
                editViewModel.label = gradient.label
                gradient.colors.forEach { colors += editViewModel.newColor(it) }
            }
            editViewModel.initial = editViewModel.current()
            editViewModel.loaded = true
        }

        controller.listener = this
        views.gradientColorsRecycler.configureWith(controller)
        views.gradientColorsRecycler.addItemDecoration(ListDividerDecoration(requireContext(), followItemTranslation = true))
        enableDragReorder()

        views.gradientLabelInput.filters = arrayOf(CustomGradient.LABEL_FILTER, InputFilter.LengthFilter(CustomGradient.MAX_LABEL_LENGTH))
        // Animation off for the prefill, or the hint starts in-field drawn over the restored label.
        views.gradientLabelTil.isHintAnimationEnabled = false
        views.gradientLabelInput.setText(editViewModel.label)
        views.gradientLabelTil.isHintAnimationEnabled = true
        views.gradientLabelInput.doAfterTextChanged {
            editViewModel.label = it?.toString().orEmpty()
            updateLabelError()
            requireActivity().invalidateOptionsMenu()
        }
        updateLabelError()
        refresh()
    }

    override fun onDestroyView() {
        views.gradientColorsRecycler.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    private fun refresh() {
        controller.setData(colors.toList())
        updatePreview()
        requireActivity().invalidateOptionsMenu()
    }

    private fun updatePreview() {
        views.gradientPreviewBar.isVisible = colors.isNotEmpty()
        views.gradientPreviewBar.backgroundCompat = if (colors.isNotEmpty()) {
            CustomGradient.drawable(colors.map { it.color }, GradientDrawable.RECTANGLE).apply {
                cornerRadius = 8 * resources.displayMetrics.density
            }
        } else {
            null
        }
    }

    private fun problem() = CustomGradient.validate(
            editViewModel.label,
            colors.map { it.color },
            vectorPreferences.getCustomGradients(),
            pageArgs.label,
    )

    private fun updateLabelError() {
        views.gradientLabelTil.error = if (problem() == CustomGradient.Problem.DUPLICATE_LABEL) {
            getString(CommonStrings.gradient_label_duplicate)
        } else {
            null
        }
    }

    private fun isDirty() = editViewModel.current() != editViewModel.initial

    private fun canApply() = isDirty() && problem() == null

    private fun enableDragReorder() {
        EpoxyTouchHelper.initDragging(controller)
                .withRecyclerView(views.gradientColorsRecycler)
                .forVerticalList()
                .withTarget(GradientColorItem_::class.java)
                .andCallbacks(object : EpoxyTouchHelper.DragCallbacks<GradientColorItem_>() {
                    override fun onDragStarted(model: GradientColorItem_?, itemView: View?, adapterPosition: Int) {
                        itemView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        itemView?.let { ViewCompat.setElevation(it, 6f) }
                    }

                    override fun clearView(model: GradientColorItem_?, itemView: View?) {
                        itemView?.let { ViewCompat.setElevation(it, 0f) }
                    }

                    override fun onModelMoved(fromPosition: Int, toPosition: Int, modelBeingMoved: GradientColorItem_?, itemView: View?) {
                        colors.clear()
                        colors.addAll(controller.currentOrderedColors())
                        updatePreview()
                    }

                    override fun onDragReleased(model: GradientColorItem_?, itemView: View?) {
                        refresh()
                    }
                })
    }

    override fun onColorClicked(color: EditableColor) {
        openPicker(color.id, color.color)
    }

    override fun onAddColor() {
        openPicker(GradientEditViewModel.PICKING_NEW, initial = null)
    }

    override fun onDeleteColor(color: EditableColor) {
        colors.removeAll { it.id == color.id }
        refresh()
    }

    /** [initial] is the color the picker opens on; a new color opens on nothing, seeded from the last one. */
    private fun openPicker(id: Long, initial: Int?) {
        if (childFragmentManager.findFragmentByTag(COLOR_DIALOG_TAG) != null) return
        editViewModel.pickingId = id
        val title = if (id == GradientEditViewModel.PICKING_NEW) {
            getString(CommonStrings.gradient_add_color)
        } else {
            getString(CommonStrings.gradient_color, colors.indexOfFirst { it.id == id } + 1)
        }
        val initialHex = initial?.let { MatrixItemColorProvider.toHex(it) }
        val seedHex = initialHex ?: colors.lastOrNull()?.color?.let { MatrixItemColorProvider.toHex(it) }
        ProfileColorPickerDialogFragment.newInstance(
                requestKey = COLOR_REQUEST_KEY,
                title = title,
                initialHex = initialHex,
                defaultHex = seedHex ?: DEFAULT_HEX,
                customSeed = seedHex,
                showEyedropper = true,
        ).show(childFragmentManager, COLOR_DIALOG_TAG)
    }

    private fun onColorPicked(color: Int) {
        when (val id = editViewModel.pickingId) {
            null -> return
            GradientEditViewModel.PICKING_NEW -> if (colors.size < CustomGradient.MAX_COLORS) colors += editViewModel.newColor(color)
            else -> {
                val index = colors.indexOfFirst { it.id == id }
                if (index >= 0) colors[index] = colors[index].copy(color = color)
            }
        }
        editViewModel.pickingId = null
        if (view != null) refresh()
    }

    override fun getMenuRes() = R.menu.menu_gradient_edit

    override fun handlePrepareMenu(menu: Menu) {
        // Match the toolbar back arrow's grey; the Apply checkmark goes a dimmer grey when disabled.
        val enabledTint = ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        val disabledTint = ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
        menu.findItem(R.id.gradientMenuFromImage)?.apply {
            val hasRoom = roomForColors() > 0
            isEnabled = hasRoom
            icon?.mutate()?.let { DrawableCompat.setTint(it, if (hasRoom) enabledTint else disabledTint) }
        }
        menu.findItem(R.id.gradientMenuDelete)?.apply {
            isVisible = pageArgs.label != null
            icon?.mutate()?.let { DrawableCompat.setTint(it, enabledTint) }
        }
        menu.findItem(R.id.gradientMenuApply)?.apply {
            val applicable = canApply()
            isEnabled = applicable
            icon?.mutate()?.let { DrawableCompat.setTint(it, if (applicable) enabledTint else disabledTint) }
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.gradientMenuFromImage -> {
                openFromImage()
                true
            }
            R.id.gradientMenuApply -> {
                save()
                true
            }
            R.id.gradientMenuDelete -> {
                confirmDelete()
                true
            }
            else -> false
        }
    }

    private fun save() {
        val existing = vectorPreferences.getCustomGradients()
        val gradient = editViewModel.current()
        if (CustomGradient.validate(gradient.label, gradient.colors, existing, pageArgs.label) != null) {
            updateLabelError()
            return
        }
        val updated = if (existing.any { it.label == pageArgs.label }) {
            existing.map { if (it.label == pageArgs.label) gradient else it }
        } else {
            existing + gradient
        }
        vectorPreferences.setCustomGradients(updated)
        activity?.finish()
    }

    private fun confirmDelete() {
        val label = pageArgs.label ?: return
        MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(CommonStrings.gradient_delete_confirmation, label))
                .setPositiveButton(CommonStrings.action_delete) { _, _ ->
                    vectorPreferences.setCustomGradients(vectorPreferences.getCustomGradients().filterNot { it.label == label })
                    activity?.finish()
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        if (!isDirty()) return false
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.image_pack_unsaved_title)
                .setMessage(CommonStrings.gradient_unsaved_message)
                .apply {
                    if (canApply()) setPositiveButton(CommonStrings.image_pack_apply) { _, _ -> save() }
                }
                .setNegativeButton(CommonStrings.image_pack_unsaved_discard) { _, _ -> activity?.finish() }
                .setNeutralButton(CommonStrings.action_cancel, null)
                .show()
        return true
    }

    companion object {
        private const val COLOR_REQUEST_KEY = "GradientEditFragment.color"
        private const val COLOR_DIALOG_TAG = "GradientColorPicker"
        private const val DEFAULT_HEX = "#000000"
    }
}
