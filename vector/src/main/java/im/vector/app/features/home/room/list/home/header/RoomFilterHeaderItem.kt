/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.home.header

import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.google.android.material.tabs.TabLayout
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel

@EpoxyModelClass
abstract class RoomFilterHeaderItem : VectorEpoxyModel<RoomFilterHeaderItem.Holder>(R.layout.item_home_filter_tabs) {

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onFilterChangedListener: ((HomeRoomFilterTab) -> Unit)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onSectionLongPressListener: ((HomeRoomFilterTab.Section) -> Unit)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onCreateSectionListener: (() -> Unit)? = null

    @EpoxyAttribute
    var showCreateSection: Boolean = false

    @EpoxyAttribute
    var filtersData: List<HomeRoomFilterTab>? = null

    @EpoxyAttribute
    var selectedFilter: HomeRoomFilterTab? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        with(holder.tabLayout) {
            removeAllTabs()
            clearOnTabSelectedListeners()

            val selected = selectedFilter ?: HomeRoomFilterTab.Standard(HomeRoomFilter.ALL)
            filtersData?.forEach { tab ->
                val title = when (tab) {
                    is HomeRoomFilterTab.Standard -> context.getString(tab.filter.titleRes)
                    is HomeRoomFilterTab.Section -> tab.name
                }
                val newTab = newTab().setText(title).setTag(tab)
                addTab(newTab, tab.isSameTab(selected))
                if (tab is HomeRoomFilterTab.Section) {
                    newTab.view.setOnLongClickListener {
                        val listener = onSectionLongPressListener ?: return@setOnLongClickListener false
                        listener(tab)
                        true
                    }
                }
            }
            if (showCreateSection) {
                addTab(newTab().setText("+").setTag(CREATE_SECTION_MARKER), false)
            }

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    when (val tag = tab?.tag) {
                        CREATE_SECTION_MARKER -> {
                            onCreateSectionListener?.invoke()
                            // The "+" tab is a button, not a filter: give the selection back to the
                            // tab of the still-active filter.
                            for (i in 0 until tabCount) {
                                val other = getTabAt(i) ?: continue
                                val otherTag = other.tag as? HomeRoomFilterTab ?: continue
                                if (otherTag.isSameTab(selected)) {
                                    selectTab(other)
                                    break
                                }
                            }
                        }
                        is HomeRoomFilterTab -> onFilterChangedListener?.invoke(tag)
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
                override fun onTabReselected(tab: TabLayout.Tab?) = Unit
            })
        }
    }

    override fun unbind(holder: Holder) {
        holder.tabLayout.clearOnTabSelectedListeners()
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val tabLayout by bind<TabLayout>(R.id.home_filter_tabs_tabs)
    }

    companion object {
        private const val CREATE_SECTION_MARKER = "create_section"
    }
}
