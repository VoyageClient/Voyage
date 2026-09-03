/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.devtools

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.core.epoxy.noResultItem
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericItem
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import me.gujun.android.span.span
import org.json.JSONObject
import org.matrix.android.sdk.api.session.events.model.Event
import javax.inject.Inject

class RoomStateListController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider
) : TypedEpoxyController<RoomDevToolViewState>() {

    var interactionListener: DevToolsInteractionListener? = null

    override fun buildModels(data: RoomDevToolViewState?) {
        val host = this
        val query = DevToolsSearchQuery.parse(data?.searchQuery.orEmpty())
        when (data?.displayMode) {
            RoomDevToolViewState.Mode.StateEventList -> {
                if (query.isEmpty) {
                    val stateEventsGroups = data.stateEvents.invoke().orEmpty().groupBy { it.getClearType() }

                    if (stateEventsGroups.isEmpty()) {
                        noResultItem {
                            id("no state events")
                            text(host.stringProvider.getString(CommonStrings.no_result_placeholder))
                        }
                    } else {
                        stateEventsGroups.forEach { entry ->
                            genericItem {
                                id(entry.key)
                                title(entry.key.neutralizeDirectionOverrides().toEpoxyCharSequence())
                                description(
                                        host.stringProvider.getQuantityString(CommonPlurals.entries, entry.value.size, entry.value.size)
                                                .toEpoxyCharSequence()
                                )
                                itemClickAction {
                                    host.interactionListener?.processAction(RoomDevToolAction.ShowStateEventType(entry.key))
                                }
                            }
                        }
                    }
                } else {
                    // Searching spans every type at once, so results are flat events rather than type groups.
                    buildStateEvents(data.stateEvents.invoke().orEmpty().filter { query.matches(it.type, it.stateKey, it.content) }, fromSearch = true)
                }
            }
            RoomDevToolViewState.Mode.StateEventListByType -> {
                buildStateEvents(
                        data.stateEvents.invoke().orEmpty()
                                .filter { it.type == data.currentStateType }
                                .filter { query.matches(it.type, it.stateKey, it.content) },
                        fromSearch = false
                )
            }
            RoomDevToolViewState.Mode.AccountDataList -> {
                val accountDataEvents = data.roomAccountData.invoke().orEmpty()
                        .filter { query.matches(it.type, null, it.content) }
                if (accountDataEvents.isEmpty()) {
                    noResultItem {
                        id("no account data")
                        text(host.stringProvider.getString(CommonStrings.no_result_placeholder))
                    }
                } else {
                    accountDataEvents.forEach { event ->
                        val contentJson = JSONObject(event.content).toString().let {
                            if (it.length > 140) {
                                it.take(140) + Typography.ellipsis
                            } else {
                                it
                            }
                        }
                        genericItem {
                            id(event.type)
                            title(event.type.neutralizeDirectionOverrides().toEpoxyCharSequence())
                            description(contentJson.neutralizeDirectionOverrides().toEpoxyCharSequence())
                            itemClickAction {
                                host.interactionListener?.processAction(RoomDevToolAction.ShowAccountDataEvent(event))
                            }
                        }
                    }
                }
            }
            else -> {
                // nop
            }
        }
    }

    private fun buildStateEvents(stateEvents: List<Event>, fromSearch: Boolean) {
        val host = this
        if (stateEvents.isEmpty()) {
            noResultItem {
                id("no state events")
                text(host.stringProvider.getString(CommonStrings.no_result_placeholder))
            }
            return
        }
        stateEvents.forEach { stateEvent ->
            val contentJson = JSONObject(stateEvent.content.orEmpty()).toString().let {
                if (it.length > 140) {
                    it.take(140) + Typography.ellipsis
                } else {
                    it.take(140)
                }
            }
            genericItem {
                id(stateEvent.eventId ?: "${stateEvent.type}-${stateEvent.stateKey}")
                title(span {
                    +"Type: "
                    span {
                        textColor = host.colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                        text = "\"${stateEvent.type?.neutralizeDirectionOverrides()}\""
                        textStyle = "normal"
                    }
                    +"\nState Key: "
                    span {
                        textColor = host.colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                        text = stateEvent.stateKey.let { "\"${it?.neutralizeDirectionOverrides()}\"" }
                        textStyle = "normal"
                    }
                }.toEpoxyCharSequence())
                description(contentJson.neutralizeDirectionOverrides().toEpoxyCharSequence())
                itemClickAction {
                    host.interactionListener?.processAction(RoomDevToolAction.ShowStateEvent(stateEvent, fromSearch))
                }
            }
        }
    }
}
