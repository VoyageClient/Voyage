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
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.features.reactions.EmojiPickerSection
import im.vector.app.features.reactions.pauseImageAnimationsWhileScrolling
import com.airbnb.mvrx.args
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.app.databinding.BottomSheetStickerPickerBinding
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.imagepack.ImagePackUsageFilter
import im.vector.app.features.imagepack.ResolvedImage
import im.vector.app.features.imagepack.ResolvedImagePack
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
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
        controller.listener = this

        val frequent = recentStickerDataSource.getRecentStickersSnapshot()
        val packs = ImagePackUsageFilter.stickerPacks(imagePackProvider.getEnabledImagePacks(pickerArgs.roomId))
                .filter { it.images.isNotEmpty() }

        controller.setData(StickerPickerController.Data(frequentlyUsed = frequent, packs = packs))
        setupTabs(frequent, packs, layoutManager)
    }

    private fun setupTabs(frequent: List<ResolvedImage>, packs: List<ResolvedImagePack>, layoutManager: GridLayoutManager) {
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
                    tabImageUrl = contentUrlResolver?.resolveThumbnail(tabMxc, 96, 96, ContentUrlResolver.ThumbnailMethod.SCALE),
                    items = emptyList(),
            )
            sectionHeaderPositions += position
            position += 1 + pack.images.size
        }

        // Resolve the accent from the host activity (the bottom-sheet dialog theme would give green).
        views.stickerPickerTabs.setIndicatorColor(
                im.vector.app.features.themes.ThemeUtils.getColor(requireActivity(), com.google.android.material.R.attr.colorAccent)
        )
        views.stickerPickerTabs.setTabs(sections)
        views.stickerPickerTabs.setSelectedTab(0)
        views.stickerPickerTabs.onTabClicked = { index ->
            sectionHeaderPositions.getOrNull(index)?.let { layoutManager.scrollToPositionWithOffset(it, 0) }
            views.stickerPickerTabs.setSelectedTab(index)
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
                if (!userScrolling) return
                val first = layoutManager.findFirstVisibleItemPosition()
                val section = sectionHeaderPositions.indexOfLast { it <= first }.coerceAtLeast(0)
                views.stickerPickerTabs.setSelectedTab(section)
            }
        })
    }

    override fun onDestroyView() {
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
