/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.picker

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.mvrx.args
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.glide.GridImagePreloader
import im.vector.app.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.app.databinding.BottomSheetStickerPickerBinding
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.imagepack.ImagePackUsageFilter
import im.vector.app.features.imagepack.ResolvedImage
import im.vector.app.features.imagepack.ResolvedImagePack
import im.vector.app.features.reactions.EmojiPickerSection
import im.vector.app.features.reactions.pauseImageAnimationsWhileScrolling
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

@Parcelize
data class StickerPickerArgs(
        val roomId: String,
) : Parcelable

@AndroidEntryPoint
class StickerPickerBottomSheet :
        VectorBaseBottomSheetDialogFragment<BottomSheetStickerPickerBinding>(),
        StickerPickerController.Listener {

    @Inject lateinit var controller: StickerPickerController
    @Inject lateinit var imagePackProvider: ImagePackProvider
    @Inject lateinit var recentStickerDataSource: RecentStickerDataSource
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder

    private val pickerArgs: StickerPickerArgs by args()

    override val showExpanded = true

    private var frequent: List<ResolvedImage> = emptyList()
    private var packs: List<ResolvedImagePack> = emptyList()

    // Adapter position of each section header, in order — used to scroll on tab tap and to pick the
    // current tab while scrolling.
    private val sectionHeaderPositions = mutableListOf<Int>()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): BottomSheetStickerPickerBinding {
        return BottomSheetStickerPickerBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Full-screen, non-draggable panel dismissed via the back arrow (like the web sticker UI), rather than
        // a swipe-to-dismiss sheet.
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            isDraggable = false
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        (view.parent as? View)?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        views.stickerPickerToolbar.apply {
            title = getString(CommonStrings.sticker_picker_title)
            navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(), R.drawable.ic_back_24dp)
            setNavigationOnClickListener { dismiss() }
        }
        controller.spanCount = SPAN_COUNT
        val layoutManager = GridLayoutManager(requireContext(), SPAN_COUNT).apply {
            spanSizeLookup = controller.spanSizeLookup
        }
        views.stickerPickerRecyclerView.layoutManager = layoutManager
        views.stickerPickerRecyclerView.adapter = controller.adapter
        views.stickerPickerRecyclerView.pauseImageAnimationsWhileScrolling()
        // As in the emote grid: nothing animates in or out, the bounds never depend on the contents, and
        // a screenful of already-bound cells beats rebinding them when the grid is scrolled back over.
        views.stickerPickerRecyclerView.itemAnimator = null
        views.stickerPickerRecyclerView.setHasFixedSize(true)
        views.stickerPickerRecyclerView.setItemViewCacheSize(STICKER_VIEW_CACHE_SIZE)
        views.stickerPickerRecyclerView.recycledViewPool.setMaxRecycledViews(R.layout.item_sticker, STICKER_POOL_SIZE)
        controller.listener = this

        setupSearch()
        // Whatever the room already resolved, drawn in this same frame.
        show(fromPacks(imagePackProvider.cachedImagePacks(pickerArgs.roomId), prune = false), layoutManager)
        // Aggregating the packs walks account data, the room's state and its parent spaces' state, and
        // pruning the recents writes account data — none of it belongs on the thread drawing the sheet.
        viewLifecycleOwner.lifecycleScope.launch {
            // Cold caches fall back to the stored copy, then to the aggregation itself.
            val loaded = withContext(Dispatchers.Default) {
                if (packs.isEmpty()) fromPacks(imagePackProvider.warmImagePacks(pickerArgs.roomId), prune = false) else null
            }
            loaded?.takeIf { it.packs.isNotEmpty() }?.let { show(it, layoutManager) }
            val refreshed = withContext(Dispatchers.Default) { fromPacks(imagePackProvider.refreshImagePacks(pickerArgs.roomId), prune = true) }
            if (refreshed.packs != packs || refreshed.frequent != frequent) show(refreshed, layoutManager)
        }
    }

    private fun show(loaded: LoadedStickers, layoutManager: GridLayoutManager) {
        if (loaded.packs.isEmpty() && loaded.frequent.isEmpty() && packs.isNotEmpty()) return
        packs = loaded.packs
        frequent = loaded.frequent
        controller.setData(StickerPickerController.Data(frequentlyUsed = frequent, packs = packs))
        setupTabs(layoutManager)
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
        val stickers = frequent + packs.flatMap { it.images }
        val mxcByResolvedUrl = stickers.mapNotNull { sticker ->
            contentUrlResolver?.resolveFullSize(sticker.mxcUrl)?.let { it to sticker.mxcUrl }
        }.toMap()
        GridImagePreloader.warm(
                key = "stickers",
                context = requireContext(),
                urls = mxcByResolvedUrl.keys.toList(),
                size = StickerItem.CELL_PX,
                // Still frames: what a cell needs to draw something the instant it binds. The animation
                // itself is decoded by the cell, from the file this also puts in the disk cache.
                animated = false,
                keepFrameFor = { resolvedUrl -> mxcByResolvedUrl[resolvedUrl] },
        )
    }

    private class LoadedStickers(val packs: List<ResolvedImagePack>, val frequent: List<ResolvedImage>)

    /**
     * @param prune whether to drop recents whose sticker is gone from the packs. Only for a freshly
     * resolved set: doing it from a cached one could delete recents whose pack simply hasn't loaded.
     */
    private fun fromPacks(resolved: List<ResolvedImagePack>, prune: Boolean): LoadedStickers {
        val packs = imagePackProvider.sortForDisplay(ImagePackUsageFilter.stickerPacks(imagePackProvider.enabledPacksOf(resolved)))
                .filter { it.images.isNotEmpty() }
        val packImagesByMxc = packs.flatMap { it.images }.associateBy { it.mxcUrl }
        // Drop deleted stickers from both the displayed list and the remote recent_stickers account data.
        if (prune) recentStickerDataSource.pruneToValidMxcs(packImagesByMxc.keys)
        // Re-resolve against the packs: the account data doesn't round-trip shortcode/info.
        val frequent = recentStickerDataSource.getRecentStickersSnapshot()
                .mapNotNull { packImagesByMxc[it.mxcUrl] }
        return LoadedStickers(packs, frequent)
    }

    @Suppress("DEPRECATION")
    private fun setupSearch() {
        // Shrink the sheet for the keyboard rather than letting it cover the grid.
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        views.stickerPickerTabRow.onQueryChanged = { query ->
            controller.setData(StickerPickerController.Data(frequentlyUsed = frequent, packs = packs, searchQuery = query))
            views.stickerPickerRecyclerView.scrollToPosition(0)
        }
        views.stickerPickerTabRow.onSearchModeChanged = { searching ->
            if (!searching) views.stickerPickerTabRow.tabs.setSelectedTab(0)
        }
    }

    private fun setupTabs(layoutManager: GridLayoutManager) {
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
        val sections = mutableListOf<EmojiPickerSection>()
        sectionHeaderPositions.clear()
        var position = 0
        if (frequent.isNotEmpty()) {
            sections += EmojiPickerSection(name = "", tabGlyph = null, tabImageUrl = null, tabIconRes = R.drawable.ic_clock, items = emptyList())
            sectionHeaderPositions += position
            position += 1 + frequent.size
        }
        packs.forEach { pack ->
            val tabMxc = pack.avatarUrl ?: pack.images.first().mxcUrl
            sections += EmojiPickerSection(
                    name = controller.packTitle(pack),
                    tabGlyph = null,
                    tabImageUrl = contentUrlResolver?.resolveFullSize(tabMxc),
                    items = emptyList(),
            )
            sectionHeaderPositions += position
            position += 1 + pack.images.size
        }

        // Resolve the accent from the host activity (the bottom-sheet dialog theme would give green).
        views.stickerPickerTabRow.tabs.setIndicatorColor(
                im.vector.app.features.themes.ThemeUtils.getColor(requireActivity(), com.google.android.material.R.attr.colorAccent)
        )
        views.stickerPickerTabRow.tabs.setTabs(sections)
        views.stickerPickerTabRow.tabs.setSelectedTab(0)
        views.stickerPickerTabRow.tabs.onTabClicked = { index ->
            sectionHeaderPositions.getOrNull(index)?.let { layoutManager.scrollToPositionWithOffset(it, 0) }
            views.stickerPickerTabRow.tabs.setSelectedTab(index)
        }
        views.stickerPickerRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var userScrolling = false
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> userScrolling = true
                    RecyclerView.SCROLL_STATE_IDLE -> userScrolling = false
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!userScrolling || views.stickerPickerTabRow.isSearching) return
                val first = layoutManager.findFirstVisibleItemPosition()
                val section = sectionHeaderPositions.indexOfLast { it <= first }.coerceAtLeast(0)
                views.stickerPickerTabRow.tabs.setSelectedTab(section)
            }
        })
    }

    override fun onDestroyView() {
        GridImagePreloader.cancel("stickers")
        views.stickerPickerRecyclerView.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun onStickerClicked(image: ResolvedImage) {
        recentStickerDataSource.recordStickerUse(image)
        setFragmentResult(
                RESULT_KEY,
                bundleOf(
                        BUNDLE_URL to image.mxcUrl,
                        BUNDLE_BODY to (image.body ?: image.shortcode),
                        BUNDLE_MIME to image.info?.mimeType,
                        BUNDLE_WIDTH to (image.info?.width ?: 0),
                        BUNDLE_HEIGHT to (image.info?.height ?: 0),
                        BUNDLE_SIZE to (image.info?.size ?: 0L),
                )
        )
        dismiss()
    }

    companion object {
        private const val SPAN_COUNT = 4
        private const val STICKER_VIEW_CACHE_SIZE = 60
        private const val STICKER_POOL_SIZE = 120
        const val RESULT_KEY = "StickerPickerBottomSheet_result"
        const val BUNDLE_URL = "url"
        const val BUNDLE_BODY = "body"
        const val BUNDLE_MIME = "mime"
        const val BUNDLE_WIDTH = "width"
        const val BUNDLE_HEIGHT = "height"
        const val BUNDLE_SIZE = "size"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, roomId: String) {
            StickerPickerBottomSheet().apply {
                setArguments(StickerPickerArgs(roomId))
            }.show(fragmentManager, "StickerPickerBottomSheet")
        }
    }
}
