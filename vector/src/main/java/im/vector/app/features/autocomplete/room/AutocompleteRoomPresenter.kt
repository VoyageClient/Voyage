/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.room

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.features.autocomplete.AutocompleteClickListener
import im.vector.app.features.autocomplete.RecyclerViewPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import javax.inject.Inject

class AutocompleteRoomPresenter @Inject constructor(
        context: Context,
        private val controller: AutocompleteRoomController,
        private val session: Session
) : RecyclerViewPresenter<RoomSummary>(context), AutocompleteClickListener<RoomSummary> {

    private val queryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queryJob: Job? = null

    init {
        controller.listener = this
    }

    override fun instantiateAdapter(): RecyclerView.Adapter<*> {
        return controller.adapter
    }

    override fun instantiateRecyclerView(): RecyclerView = dividedRecyclerView(MAX_VISIBLE_ROOMS)

    override fun getPopupDimensions() = fullWidthPopupDimensions()

    override fun onViewShown() = slideContentUpOnShow()

    override fun animateViewOut(onEnd: Runnable) = slideContentDownOnHide(onEnd)

    override fun onItemClick(t: RoomSummary) {
        dispatchClick(t)
    }

    override fun onQuery(query: CharSequence?) {
        // Every keystroke is a room-summary query; without this, holding a key down floods the main thread.
        queryJob?.cancel()
        queryJob = queryScope.launch {
            delay(QUERY_DEBOUNCE_MS)
            val queryParams = roomSummaryQueryParams {
                canonicalAlias = if (query.isNullOrBlank()) {
                    QueryStringValue.IsNotNull
                } else {
                    QueryStringValue.Contains(query.toString(), QueryStringValue.Case.INSENSITIVE)
                }
            }
            val rooms = withContext(Dispatchers.Default) {
                session.roomService().getRoomSummaries(queryParams)
                        .asSequence()
                        .sortedBy { it.displayName }
                        .toList()
            }
            // Keep the current rows on screen so they are what animates away, rather than collapsing first.
            if (rooms.isEmpty()) {
                requestDismiss()
                return@launch
            }
            controller.setData(rooms)
        }
    }

    override fun onViewHidden() {
        super.onViewHidden()
        queryJob?.cancel()
    }

    fun clear() {
        controller.listener = null
        queryJob?.cancel()
        queryScope.coroutineContext.cancelChildren()
    }

    companion object {
        private const val MAX_VISIBLE_ROOMS = 3
        private const val QUERY_DEBOUNCE_MS = 100L
    }
}
