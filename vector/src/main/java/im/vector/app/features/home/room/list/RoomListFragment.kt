/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyController
import com.airbnb.epoxy.OnModelBuildFinishedListener
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.epoxy.LayoutManagerStateRestorer
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.core.utils.PerfTrace
import im.vector.app.databinding.FragmentRoomListBinding
import im.vector.app.features.home.RoomListDisplayMode
import im.vector.app.features.home.room.filtered.FilteredRoomFooterItem
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsBottomSheet
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedAction
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedActionViewModel
import im.vector.app.features.home.room.list.actions.RoomTagBottomSheet
import im.vector.app.features.home.room.list.widget.NotifsFabMenuView
import im.vector.app.features.matrixto.OriginOfMatrixTo
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.room.LeaveRoomPrompt
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.extensions.orTrue
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import javax.inject.Inject

@Parcelize
data class RoomListParams(
        val displayMode: RoomListDisplayMode
) : Parcelable

@AndroidEntryPoint
class RoomListFragment :
        VectorBaseFragment<FragmentRoomListBinding>(),
        RoomListListener,
        OnBackPressed,
        FilteredRoomFooterItem.Listener,
        NotifsFabMenuView.Listener {

    @Inject lateinit var pagedControllerFactory: RoomSummaryPagedControllerFactory
    @Inject lateinit var pgpDecryptor: im.vector.app.features.pgp.PgpDecryptor
    @Inject lateinit var pgpKeyStore: im.vector.app.features.pgp.PgpKeyStore
    @Inject lateinit var notificationDrawerManager: NotificationDrawerManager
    @Inject lateinit var footerController: RoomListFooterController
    @Inject lateinit var userPreferencesProvider: UserPreferencesProvider

    private var modelBuildListener: OnModelBuildFinishedListener? = null
    private lateinit var sharedActionViewModel: RoomListQuickActionsSharedActionViewModel
    private val roomListParams: RoomListParams by args()
    private val roomListViewModel: RoomListViewModel by fragmentViewModel()
    private lateinit var stateRestorer: LayoutManagerStateRestorer

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomListBinding {
        return FragmentRoomListBinding.inflate(inflater, container, false)
    }

    data class SectionKey(
            val name: String,
            val isExpanded: Boolean,
            val notifyOfLocalEcho: Boolean
    )

    data class SectionAdapterInfo(
            var section: SectionKey,
            val sectionHeaderAdapter: SectionHeaderAdapter,
            val contentEpoxyController: EpoxyController
    )

    private val adapterInfosList = mutableListOf<SectionAdapterInfo>()
    private var concatAdapter: ConcatAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val perfMarker = PerfTrace.mark("roomlist.onViewCreated")
        try {
            onViewCreatedBody(view, savedInstanceState)
        } finally {
            perfMarker.end()
        }
    }

    private fun onViewCreatedBody(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.stateView.contentView = views.roomListView
        views.stateView.state = StateView.State.Loading
        setupCreateRoomButton()
        PerfTrace.time("roomlist.setupRecyclerView") { setupRecyclerView() }
        sharedActionViewModel = activityViewModelProvider.get(RoomListQuickActionsSharedActionViewModel::class.java)
        roomListViewModel.observeViewEvents {
            when (it) {
                is RoomListViewEvents.Loading -> showLoading(it.message)
                is RoomListViewEvents.Failure -> showFailure(it.throwable)
                is RoomListViewEvents.SelectRoom -> handleSelectRoom(it, it.isInviteAlreadyAccepted)
                is RoomListViewEvents.Done -> Unit
                is RoomListViewEvents.NavigateToMxToBottomSheet -> handleShowMxToLink(it.link)
            }
        }

        views.createChatFabMenu.listener = this

        sharedActionViewModel
                .stream()
                .onEach { handleQuickActions(it) }
                .launchIn(viewLifecycleOwner.lifecycleScope)

        // Re-render last-message previews once a PGP body finishes decrypting. Forced: the room
        // summaries are unchanged, so a plain requestModelBuild() would diff to a no-op and the
        // preview would stay as the raw armored ciphertext.
        pgpDecryptor.updates
                .sample(300)
                .onEach { adapterInfosList.forEach { info -> (info.contentEpoxyController as? RoomSummaryPagedController)?.requestForcedRebuild() } }
                .launchIn(viewLifecycleOwner.lifecycleScope)

        // Flip the room-list PGP lock the moment the global/per-room toggle changes.
        pgpKeyStore.changes
                .onEach { adapterInfosList.forEach { info -> (info.contentEpoxyController as? RoomSummaryPagedController)?.requestForcedRebuild() } }
                .launchIn(viewLifecycleOwner.lifecycleScope)

        roomListViewModel.onEach(RoomListViewState::roomMembershipChanges) { ms ->
            // it's for invites local echo
            adapterInfosList.filter { it.section.notifyOfLocalEcho }
                    .onEach {
                        (it.contentEpoxyController as? RoomSummaryPagedController)?.roomChangeMembershipStates = ms
                    }
        }
    }

    override fun onStart() {
        super.onStart()

        // Local rooms should not exist anymore when the room list is shown
        roomListViewModel.handle(RoomListAction.DeleteAllLocalRoom)
    }

    private fun refreshCollapseStates() {
        // A still-loading section may yet become visible, so count it as present when deciding
        // collapsability. Otherwise every section reads as hidden during load, is deemed
        // non-collapsable, and is force-expanded — flashing the saved-collapsed sections open
        // until their data streams in.
        val sectionsCount = adapterInfosList.count {
            !it.sectionHeaderAdapter.roomsSectionData.isHidden || it.sectionHeaderAdapter.roomsSectionData.isLoading
        }
        roomListViewModel.sections.forEachIndexed { index, roomsSection ->
            val actualBlock = adapterInfosList[index]
            val isRoomSectionCollapsable = sectionsCount > 1
            // The saved/live state is the source of truth. A lone (non-collapsable) section is always shown
            // expanded for usability, but we never write that back so the user's saved choice survives.
            val savedExpanded = roomsSection.isExpanded.value.orTrue()
            val effectiveExpanded = savedExpanded || !isRoomSectionCollapsable
            actualBlock.contentEpoxyController.setCollapsed(!effectiveExpanded)
            actualBlock.section = actualBlock.section.copy(isExpanded = effectiveExpanded)
            actualBlock.sectionHeaderAdapter.updateSection {
                it.copy(
                        isExpanded = effectiveExpanded,
                        isCollapsable = isRoomSectionCollapsable
                )
            }
        }
    }

    override fun showFailure(throwable: Throwable) {
        showErrorInSnackbar(throwable)
    }

    private fun handleShowMxToLink(link: String) {
        navigator.openMatrixToBottomSheet(requireActivity(), link, OriginOfMatrixTo.ROOM_LIST)
    }

    override fun onDestroyView() {
        adapterInfosList.onEach { it.contentEpoxyController.removeModelBuildListener(modelBuildListener) }
        adapterInfosList.clear()
        modelBuildListener = null
        views.roomListView.cleanup()
        footerController.listener = null
        // TODO Cleanup listener on the ConcatAdapter's adapters?
        stateRestorer.clear()
        views.createChatFabMenu.listener = null
        concatAdapter = null
        super.onDestroyView()
    }

    private fun handleSelectRoom(event: RoomListViewEvents.SelectRoom, isInviteAlreadyAccepted: Boolean) {
        navigator.openRoom(
                context = requireActivity(),
                roomId = event.roomSummary.roomId,
                isInviteAlreadyAccepted = isInviteAlreadyAccepted,
        )
    }

    private fun setupCreateRoomButton() {
        when (roomListParams.displayMode) {
            RoomListDisplayMode.NOTIFICATIONS,
            RoomListDisplayMode.ALL -> views.createChatFabMenu.isVisible = true
            RoomListDisplayMode.PEOPLE -> views.createChatRoomButton.isVisible = true
            RoomListDisplayMode.ROOMS -> views.createGroupRoomButton.isVisible = true
            RoomListDisplayMode.FILTERED -> Unit // No button in this mode
        }

        views.createChatRoomButton.debouncedClicks {
            fabCreateDirectChat()
        }
        views.createGroupRoomButton.debouncedClicks {
            fabOpenRoomDirectory()
        }

        // Hide FAB when list is scrolling
        views.roomListView.addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        views.createChatFabMenu.removeCallbacks(showFabRunnable)

                        when (newState) {
                            RecyclerView.SCROLL_STATE_IDLE -> {
                                views.createChatFabMenu.postDelayed(showFabRunnable, 250)
                            }
                            RecyclerView.SCROLL_STATE_DRAGGING,
                            RecyclerView.SCROLL_STATE_SETTLING -> {
                                when (roomListParams.displayMode) {
                                    RoomListDisplayMode.NOTIFICATIONS,
                                    RoomListDisplayMode.ALL -> views.createChatFabMenu.hide()
                                    RoomListDisplayMode.PEOPLE -> views.createChatRoomButton.hide()
                                    RoomListDisplayMode.ROOMS -> views.createGroupRoomButton.hide()
                                    RoomListDisplayMode.FILTERED -> Unit
                                }
                            }
                        }
                    }
                })
    }

    fun filterRoomsWith(filter: String) {
        // Scroll the list to top
        views.roomListView.scrollToPosition(0)

        roomListViewModel.handle(RoomListAction.FilterWith(filter))
    }

    // FilteredRoomFooterItem.Listener
    override fun createRoom(initialName: String) {
        navigator.openCreateRoom(requireActivity(), initialName)
    }

    override fun createDirectChat() {
        navigator.openCreateDirectRoom(requireActivity())
    }

    override fun openRoomDirectory(initialFilter: String) {
        navigator.openRoomDirectory(requireActivity(), initialFilter)
    }

    // NotifsFabMenuView.Listener
    override fun fabCreateDirectChat() {
        navigator.openCreateDirectRoom(requireActivity())
    }

    override fun fabOpenRoomDirectory() {
        navigator.openRoomDirectory(requireActivity(), "")
    }

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(requireContext())
        stateRestorer = LayoutManagerStateRestorer(layoutManager).register()
        views.roomListView.layoutManager = layoutManager
        views.roomListView.itemAnimator = RoomListAnimator()
        layoutManager.recycleChildrenOnDetach = true

        modelBuildListener = OnModelBuildFinishedListener { it.dispatchTo(stateRestorer) }

        val concatAdapter = ConcatAdapter()

        roomListViewModel.sections.forEachIndexed { index, section ->
            val sectionAdapter = SectionHeaderAdapter(SectionHeaderAdapter.RoomsSectionData(section.sectionName)) {
                if (adapterInfosList[index].sectionHeaderAdapter.roomsSectionData.isCollapsable) {
                    roomListViewModel.handle(RoomListAction.ToggleSection(section))
                }
            }
            val contentAdapter =
                    when {
                        section.livePages != null -> {
                            pagedControllerFactory.createRoomSummaryPagedController(roomListParams.displayMode)
                                    .also { controller ->
                                        section.livePages.observe(viewLifecycleOwner) { pl ->
                                            controller.submitList(pl)
                                            sectionAdapter.updateSection {
                                                it.copy(
                                                        isHidden = pl.isEmpty(),
                                                        isLoading = false
                                                )
                                            }
                                            refreshCollapseStates()
                                            checkEmptyState()
                                        }
                                        observeItemCount(section, sectionAdapter)
                                        section.notificationCount.observe(viewLifecycleOwner) { counts ->
                                            sectionAdapter.updateSection {
                                                it.copy(
                                                        notificationCount = counts.totalCount,
                                                        isHighlighted = counts.isHighlight,
                                                )
                                            }
                                        }
                                        section.isExpanded.observe(viewLifecycleOwner) {
                                            refreshCollapseStates()
                                        }
                                        controller.listener = this
                                    }
                        }
                        section.liveSuggested != null -> {
                            pagedControllerFactory.createSuggestedRoomListController()
                                    .also { controller ->
                                        section.liveSuggested.observe(viewLifecycleOwner) { info ->
                                            controller.setData(info)
                                            sectionAdapter.updateSection {
                                                it.copy(
                                                        isHidden = info.rooms.isEmpty(),
                                                        isLoading = false
                                                )
                                            }
                                            refreshCollapseStates()
                                            checkEmptyState()
                                        }
                                        observeItemCount(section, sectionAdapter)
                                        section.isExpanded.observe(viewLifecycleOwner) {
                                            refreshCollapseStates()
                                        }
                                        controller.listener = this
                                    }
                        }
                        else -> {
                            pagedControllerFactory.createRoomSummaryListController(roomListParams.displayMode)
                                    .also { controller ->
                                        section.liveList?.observe(viewLifecycleOwner) { list ->
                                            controller.setData(list)
                                            sectionAdapter.updateSection {
                                                it.copy(
                                                        isHidden = list.isEmpty(),
                                                        isLoading = false,
                                                )
                                            }
                                            refreshCollapseStates()
                                            checkEmptyState()
                                        }
                                        observeItemCount(section, sectionAdapter)
                                        section.notificationCount.observe(viewLifecycleOwner) { counts ->
                                            sectionAdapter.updateSection {
                                                it.copy(
                                                        notificationCount = counts.totalCount,
                                                        isHighlighted = counts.isHighlight
                                                )
                                            }
                                        }
                                        section.isExpanded.observe(viewLifecycleOwner) {
                                            refreshCollapseStates()
                                        }
                                        controller.listener = this
                                    }
                        }
                    }
            adapterInfosList.add(
                    SectionAdapterInfo(
                            SectionKey(
                                    // Start as expanded to match the freshly-created content controller; refreshCollapseStates()
                                    // then diffs against the (possibly persisted-collapsed) section state and collapses if needed.
                                    name = section.sectionName,
                                    isExpanded = true,
                                    notifyOfLocalEcho = section.notifyOfLocalEcho
                            ),
                            sectionAdapter,
                            contentAdapter
                    )
            )
            concatAdapter.addAdapter(sectionAdapter)
            concatAdapter.addAdapter(contentAdapter.adapter)
        }

        // Add the footer controller
        footerController.listener = this
        concatAdapter.addAdapter(footerController.adapter)

        this.concatAdapter = concatAdapter
        views.roomListView.adapter = concatAdapter
    }

    private val showFabRunnable = Runnable {
        if (isAdded) {
            when (roomListParams.displayMode) {
                RoomListDisplayMode.NOTIFICATIONS,
                RoomListDisplayMode.ALL -> views.createChatFabMenu.show()
                RoomListDisplayMode.PEOPLE -> views.createChatRoomButton.show()
                RoomListDisplayMode.ROOMS -> views.createGroupRoomButton.show()
                RoomListDisplayMode.FILTERED -> Unit
            }
        }
    }

    private fun observeItemCount(section: RoomsSection, sectionAdapter: SectionHeaderAdapter) {
        lifecycleScope.launch {
            section.itemCount
                    .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                    .filter { it > 0 }
                    .collect { count ->
                        sectionAdapter.updateSection {
                            it.copy(itemCount = count)
                        }
                    }
        }
    }

    private suspend fun handleQuickActions(quickAction: RoomListQuickActionsSharedAction) {
        // Hidden tab fragments keep their observers live (see HomeDetailFragment.updateSelectedFragment),
        // so each previously visited tab would repeat the action.
        if (isHidden) return
        when (quickAction) {
            is RoomListQuickActionsSharedAction.NotificationsAllNoisy -> {
                roomListViewModel.handle(RoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.ALL_MESSAGES_NOISY))
            }
            is RoomListQuickActionsSharedAction.NotificationsAll -> {
                roomListViewModel.handle(RoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.ALL_MESSAGES))
            }
            is RoomListQuickActionsSharedAction.NotificationsMentionsOnly -> {
                roomListViewModel.handle(RoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.MENTIONS_ONLY))
            }
            is RoomListQuickActionsSharedAction.NotificationsMute -> {
                roomListViewModel.handle(RoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.MUTE))
            }
            is RoomListQuickActionsSharedAction.Settings -> {
                navigator.openRoomProfile(requireActivity(), quickAction.roomId)
            }
            is RoomListQuickActionsSharedAction.Favorite -> {
                roomListViewModel.handle(RoomListAction.ToggleTag(quickAction.roomId, RoomTag.ROOM_TAG_FAVOURITE))
            }
            is RoomListQuickActionsSharedAction.LowPriority -> {
                roomListViewModel.handle(RoomListAction.ToggleTag(quickAction.roomId, RoomTag.ROOM_TAG_LOW_PRIORITY))
            }
            is RoomListQuickActionsSharedAction.Tag -> {
                RoomTagBottomSheet.newInstance(quickAction.roomId)
                        .show(childFragmentManager, "ROOM_TAG")
            }
            is RoomListQuickActionsSharedAction.MarkUnread -> {
                roomListViewModel.handle(RoomListAction.SetMarkedUnread(quickAction.roomId, true))
            }
            is RoomListQuickActionsSharedAction.MarkRead -> {
                roomListViewModel.handle(RoomListAction.MarkRoomAsRead(quickAction.roomId))
            }
            is RoomListQuickActionsSharedAction.Leave -> {
                promptLeaveRoom(quickAction.roomId)
            }
            is RoomListQuickActionsSharedAction.Forget -> {
                promptForgetRoom(quickAction.roomId)
            }
        }
    }

    private fun promptForgetRoom(roomId: String) {
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.room_list_quick_actions_forget)
                .setMessage(CommonStrings.room_forget_prompt_msg)
                .setPositiveButton(CommonStrings.room_list_quick_actions_forget) { _, _ ->
                    roomListViewModel.handle(RoomListAction.ForgetRoom(roomId))
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private suspend fun promptLeaveRoom(roomId: String) {
        val warning = roomListViewModel.getLeaveRoomWarning(roomId)
        LeaveRoomPrompt.show(requireContext(), warning) {
            roomListViewModel.handle(RoomListAction.LeaveRoom(roomId))
        }
    }

    override fun invalidate() = withState(roomListViewModel) { state ->
        footerController.setData(state)
        checkEmptyState()
    }

    private fun checkEmptyState() {
        val isInitialSyncInProgress = withState(roomListViewModel) { it.isInitialSyncInProgress }
        val shouldShowEmpty = adapterInfosList.all { it.sectionHeaderAdapter.roomsSectionData.isHidden } &&
                !adapterInfosList.any { it.sectionHeaderAdapter.roomsSectionData.isLoading } &&
                !isInitialSyncInProgress
        if (shouldShowEmpty) {
            val emptyState = when (roomListParams.displayMode) {
                RoomListDisplayMode.NOTIFICATIONS -> {
                    StateView.State.Empty(
                            title = getString(CommonStrings.room_list_catchup_empty_title),
                            image = ContextCompat.getDrawable(requireContext(), R.drawable.ic_noun_party_popper),
                            message = getString(CommonStrings.room_list_catchup_empty_body)
                    )
                }
                RoomListDisplayMode.PEOPLE,
                RoomListDisplayMode.ROOMS,
                RoomListDisplayMode.ALL ->
                    StateView.State.Empty()
                RoomListDisplayMode.FILTERED ->
                    // Always display the content in this mode, because if the footer
                    StateView.State.Content
            }
            views.stateView.state = emptyState
        } else {
            // is there something to show already?
            if (adapterInfosList.any { !it.sectionHeaderAdapter.roomsSectionData.isHidden }) {
                views.stateView.state = StateView.State.Content
            } else {
                views.stateView.state = StateView.State.Loading
            }
        }
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        if (views.createChatFabMenu.onBackPressed()) {
            return true
        }
        return false
    }

    // RoomSummaryController.Callback **************************************************************

    override fun onRoomClicked(room: RoomSummary) {
        roomListViewModel.handle(RoomListAction.SelectRoom(room))
    }

    override fun onRoomLongClicked(room: RoomSummary): Boolean {
        userPreferencesProvider.neverShowLongClickOnRoomHelpAgain()
        // Watched rooms have no local Room object, which the quick-actions sheet requires;
        // the only action anyway is to stop watching.
        if (room.membership == Membership.NONE && room.isWatched) {
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(CommonStrings.room_list_quick_actions_stop_watching)
                    .setMessage(room.displayName)
                    .setPositiveButton(CommonStrings.room_list_quick_actions_stop_watching) { _, _ ->
                        roomListViewModel.handle(RoomListAction.StopWatchingRoom(room.roomId))
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
            return true
        }
        withState(roomListViewModel) {
            // refresh footer
            footerController.setData(it)
        }
        RoomListQuickActionsBottomSheet
                .newInstance(room.roomId)
                .show(childFragmentManager, "ROOM_LIST_QUICK_ACTIONS")
        return true
    }

    override fun onAcceptRoomInvitation(room: RoomSummary) {
        notificationDrawerManager.updateEvents { it.clearMemberShipNotificationForRoom(room.roomId) }
        roomListViewModel.handle(RoomListAction.AcceptInvitation(room))
    }

    override fun onJoinSuggestedRoom(room: SpaceChildInfo) {
        roomListViewModel.handle(RoomListAction.JoinSuggestedRoom(room.childRoomId, room.viaServers))
    }

    override fun onSuggestedRoomClicked(room: SpaceChildInfo) {
        roomListViewModel.handle(RoomListAction.ShowRoomDetails(room.childRoomId, room.viaServers))
    }

    override fun onRejectRoomInvitation(room: RoomSummary) {
        notificationDrawerManager.updateEvents { it.clearMemberShipNotificationForRoom(room.roomId) }
        roomListViewModel.handle(RoomListAction.RejectInvitation(room))
    }
}
