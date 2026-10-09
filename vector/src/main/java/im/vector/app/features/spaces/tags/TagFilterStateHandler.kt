/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.tags

import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.utils.BehaviorDataSource
import im.vector.app.features.ui.UiStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOption
import javax.inject.Inject
import javax.inject.Singleton

/** Sentinel "tag" used by the legacy Overview to filter the unified list down to direct messages. */
const val DM_FILTER_TAG = "de.spiritcroc.dm"

/** Sentinel "tag" selecting the Historical view: rooms the user was kicked or banned from. */
const val HISTORICAL_FILTER_TAG = "im.voyage.historical"

/** Sentinel "tag" selecting the Watching view: previewable rooms followed via /watch. */
const val WATCHING_FILTER_TAG = "im.voyage.watching"

interface TagFilterStateHandler {
    fun getSelectedTag(): String?
    fun setSelectedTag(tag: String?)
    fun getSelectedTagFlow(): Flow<Optional<String>>
}

@Singleton
class TagFilterStateHandlerImpl @Inject constructor(
        sessionDataSource: ActiveSessionDataSource,
        private val activeSessionHolder: ActiveSessionHolder,
        private val uiStateRepository: UiStateRepository,
) : TagFilterStateHandler {

    private val selectedTagDataSource = BehaviorDataSource<Optional<String>>(Optional.empty())

    init {
        sessionDataSource.stream().distinctUntilChanged().onEach { optionalSession ->
            val tag = optionalSession.orNull()?.let { uiStateRepository.getSelectedTag(it.sessionId) }
            selectedTagDataSource.post(tag.toOption())
        }.launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Main))
    }

    override fun getSelectedTag(): String? = selectedTagDataSource.currentValue?.orNull()

    override fun setSelectedTag(tag: String?) {
        activeSessionHolder.getSafeActiveSession()?.let { uiStateRepository.storeSelectedTag(tag, it.sessionId) }
        if (selectedTagDataSource.currentValue?.orNull() == tag) return
        selectedTagDataSource.post(tag.toOption())
    }

    override fun getSelectedTagFlow(): Flow<Optional<String>> = selectedTagDataSource.stream()
}
