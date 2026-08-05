/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.reactions

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.airbnb.epoxy.EpoxyTouchHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.epoxy.ListDividerDecoration
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.databinding.FragmentQuickReactionsSettingsBinding
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.reactions.EmojiReactionPickerActivity
import im.vector.app.features.reactions.data.EmojiDataSource
import im.vector.app.features.reactions.data.QuickReactionsDataSource
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class QuickReactionsSettingsFragment :
        VectorBaseFragment<FragmentQuickReactionsSettingsBinding>(),
        QuickReactionsController.Listener,
        VectorMenuProvider,
        OnBackPressed {

    @Inject lateinit var controller: QuickReactionsController
    @Inject lateinit var quickReactionsDataSource: QuickReactionsDataSource
    @Inject lateinit var emojiDataSource: EmojiDataSource
    @Inject lateinit var imagePackProvider: ImagePackProvider

    private val reactions = mutableListOf<String>()
    private var initial: List<String> = emptyList()
    private var isSaving = false

    private val addReactionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            EmojiReactionPickerActivity.getOutputReaction(result.data)?.let { reaction ->
                if (reaction.isNotBlank() && reaction !in reactions) {
                    reactions.add(reaction)
                    refresh()
                    requireActivity().invalidateOptionsMenu()
                }
            }
        }
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentQuickReactionsSettingsBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.listener = this
        views.quickReactionsRecycler.configureWith(controller, hasFixedSize = true)
        views.quickReactionsRecycler.addItemDecoration(ListDividerDecoration(requireContext()))
        enableDragReorder()

        reactions.addAll(quickReactionsDataSource.getQuickReactions())
        initial = reactions.toList()
        refresh()
        loadLabels()
    }

    // Emoji names + custom-emote shortcodes, so each row can show a description.
    private fun loadLabels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val names = emojiDataSource.rawData.await().emojis.values.associate { it.emoji to it.name }
            val shortcodes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                imagePackProvider.getEmoticons(roomId = null).associate { it.mxcUrl to it.shortcode }
            }
            controller.emojiNames = names
            controller.emoteShortcodes = shortcodes
            refresh()
        }
    }

    override fun onDestroyView() {
        views.quickReactionsRecycler.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun getMenuRes() = R.menu.menu_quick_reactions

    override fun handlePrepareMenu(menu: Menu) {
        val enabledTint = im.vector.app.features.themes.ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        val disabledTint = im.vector.app.features.themes.ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
        // Apply only matters when something changed; reset only when not already at the defaults.
        menu.findItem(R.id.quickReactionsApply)?.apply {
            isEnabled = reactions != initial && !isSaving
            icon?.mutate()?.let { DrawableCompat.setTint(it, if (isEnabled) enabledTint else disabledTint) }
        }
        menu.findItem(R.id.quickReactionsReset)?.apply {
            isEnabled = reactions != EmojiDataSource.quickEmojis
            icon?.mutate()?.let { DrawableCompat.setTint(it, if (isEnabled) enabledTint else disabledTint) }
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.quickReactionsApply -> { apply(); true }
            R.id.quickReactionsReset -> { confirmReset(); true }
            else -> false
        }
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        if (isSaving) return true
        if (reactions == initial) return false
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.dialog_title_warning)
                .setMessage(CommonStrings.quick_reactions_unsaved_message)
                .setPositiveButton(CommonStrings.ok) { _, _ -> apply() }
                .setNegativeButton(CommonStrings.image_pack_unsaved_discard) { _, _ -> activity?.finish() }
                .setNeutralButton(CommonStrings.action_cancel, null)
                .show()
        return true
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.action_reset)
                .setMessage(CommonStrings.quick_reactions_reset_confirmation)
                .setPositiveButton(CommonStrings.action_reset) { _, _ -> resetToDefaults() }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun apply() {
        if (isSaving) return
        isSaving = true
        invalidateOptionsMenu()
        views.quickReactionsSpinnerViews.isVisible = true
        val toSave = reactions.toList()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                quickReactionsDataSource.saveQuickReactions(toSave)
                activity?.finish()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                isSaving = false
                views.quickReactionsSpinnerViews.isVisible = false
                invalidateOptionsMenu()
                displayErrorDialog(failure)
            }
        }
    }

    private fun resetToDefaults() {
        reactions.clear()
        reactions.addAll(EmojiDataSource.quickEmojis)
        refresh()
        requireActivity().invalidateOptionsMenu()
    }

    private fun refresh() {
        controller.setData(reactions)
        views.quickReactionsEmptyPlaceholder.isVisible = reactions.isEmpty()
    }

    override fun onRemoveReaction(reaction: String) {
        reactions.remove(reaction)
        refresh()
        requireActivity().invalidateOptionsMenu()
    }

    override fun onAddReaction() {
        // The picker is built around reacting to an event; we only use its returned reaction, so the event id
        // is irrelevant here. A null room keeps it to globally-available (account/enabled) emotes + emojis.
        addReactionLauncher.launch(EmojiReactionPickerActivity.intent(requireContext(), eventId = "", roomId = null))
    }

    private fun enableDragReorder() {
        EpoxyTouchHelper.initDragging(controller)
                .withRecyclerView(views.quickReactionsRecycler)
                .forVerticalList()
                .withTarget(QuickReactionItem_::class.java)
                .andCallbacks(object : EpoxyTouchHelper.DragCallbacks<QuickReactionItem_>() {
                    override fun onDragStarted(model: QuickReactionItem_?, itemView: View?, adapterPosition: Int) {
                        itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        itemView?.let { ViewCompat.setElevation(it, 6f) }
                    }

                    override fun clearView(model: QuickReactionItem_?, itemView: View?) {
                        itemView?.let { ViewCompat.setElevation(it, 0f) }
                    }

                    override fun onModelMoved(fromPosition: Int, toPosition: Int, modelBeingMoved: QuickReactionItem_?, itemView: View?) {
                        itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        reactions.clear()
                        reactions.addAll(controller.currentOrderedReactions())
                    }

                    override fun onDragReleased(model: QuickReactionItem_?, itemView: View?) {
                        refresh()
                        requireActivity().invalidateOptionsMenu()
                    }
                })
    }
}
