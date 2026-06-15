/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.tags

import im.vector.app.core.utils.BehaviorDataSource
import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOption
import javax.inject.Inject
import javax.inject.Singleton

/** Sentinel "tag" used by the legacy Overview to filter the unified list down to direct messages. */
const val DM_FILTER_TAG = "de.spiritcroc.dm"

interface TagFilterStateHandler {
    fun getSelectedTag(): String?
    fun setSelectedTag(tag: String?)
    fun getSelectedTagFlow(): Flow<Optional<String>>
}

@Singleton
class TagFilterStateHandlerImpl @Inject constructor() : TagFilterStateHandler {

    private val selectedTagDataSource = BehaviorDataSource<Optional<String>>(Optional.empty())

    override fun getSelectedTag(): String? = selectedTagDataSource.currentValue?.orNull()

    override fun setSelectedTag(tag: String?) {
        if (selectedTagDataSource.currentValue?.orNull() == tag) return
        selectedTagDataSource.post(tag.toOption())
    }

    override fun getSelectedTagFlow(): Flow<Optional<String>> = selectedTagDataSource.stream()
}
