/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.reactions

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.vector.app.core.utils.LiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class EmojiChooserViewModel @Inject constructor(
        private val sectionFactory: EmojiPickerSectionFactory,
        private val reactionText: ReactionText,
) : ViewModel() {

    val sections: MutableLiveData<List<EmojiPickerSection>> = MutableLiveData()
    val navigateEvent: MutableLiveData<LiveEvent<String>> = MutableLiveData()
    var selectedReaction: String? = null
    var eventId: String? = null
    private var roomId: String? = null

    fun setRoomId(roomId: String?) {
        this.roomId = roomId
        viewModelScope.launch {
            sections.postValue(sectionFactory.build(roomId))
        }
    }

    suspend fun filterSections(sections: List<EmojiPickerSection>, query: String) = sectionFactory.filterSections(sections, query)

    fun onFreeformReactionSelected(reaction: String) {
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) { reactionText.resolveShortcodes(roomId, reaction) }
            onReactionSelected(resolved)
        }
    }

    fun onReactionSelected(reaction: String) {
        selectedReaction = reaction
        navigateEvent.value = LiveEvent(NAVIGATE_FINISH)
    }

    companion object {
        const val NAVIGATE_FINISH = "NAVIGATE_FINISH"
    }
}
