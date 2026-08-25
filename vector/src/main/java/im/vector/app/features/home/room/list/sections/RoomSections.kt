/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.sections

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Content
import java.util.UUID

/**
 * Element Web's custom room-list sections. Section membership is an ordinary room tag
 * ("element.io.section.<uuid>"); the section definitions (name, order) live in Element Web's
 * "im.vector.web.settings" account data, which we read as-is for cross-client interop.
 */
object RoomSections {

    const val SECTION_TAG_PREFIX = "element.io.section."
    const val WEB_SETTINGS_EVENT_TYPE = "im.vector.web.settings"

    private const val KEY_SECTION_DATA = "RoomList.CustomSectionData"
    private const val KEY_SECTION_ORDER = "RoomList.OrderedCustomSections"
    private const val KEY_SHOW_SECTIONS = "RoomList.showSections"

    /** Sentinel in the stored order marking where the catch-all list sits among custom sections. */
    const val CHATS_TAG = "chats"

    /** Web's MetaSpace.Home key, the spaceId it records for sections created outside a space. */
    const val HOME_SPACE_ID = "home-space"

    data class CustomRoomSection(
            val tag: String,
            val name: String,
            val spaceId: String?,
    )

    data class Config(
            val showSections: Boolean,
            /** Custom sections ordered above the catch-all section. */
            val aboveChats: List<CustomRoomSection>,
            /** Custom sections ordered below the catch-all section. */
            val belowChats: List<CustomRoomSection>,
    ) {
        val all: List<CustomRoomSection> get() = aboveChats + belowChats
        val allTags: List<String> get() = all.map { it.tag }

        companion object {
            val EMPTY = Config(showSections = true, aboveChats = emptyList(), belowChats = emptyList())
        }
    }

    fun parse(content: Content?): Config {
        val showSections = content?.get(KEY_SHOW_SECTIONS) as? Boolean ?: true
        val byTag = LinkedHashMap<String, CustomRoomSection>()
        (content?.get(KEY_SECTION_DATA) as? Map<*, *>)?.forEach { (key, value) ->
            val map = value as? Map<*, *> ?: return@forEach
            val tag = map["tag"] as? String ?: return@forEach
            val name = map["name"] as? String ?: return@forEach
            if (key != tag || !tag.startsWith(SECTION_TAG_PREFIX)) return@forEach
            byTag[tag] = CustomRoomSection(tag, name, map["spaceId"] as? String)
        }
        val storedOrder = (content?.get(KEY_SECTION_ORDER) as? List<*>).orEmpty().filterIsInstance<String>()
        val ordered = storedOrder.filter { it == CHATS_TAG || it in byTag }.distinct().toMutableList()
        if (CHATS_TAG !in ordered) ordered += CHATS_TAG
        // Sections absent from the stored order slot in above the catch-all, where web creates them.
        ordered.addAll(ordered.indexOf(CHATS_TAG), byTag.keys.filter { it !in ordered })
        val chatsIndex = ordered.indexOf(CHATS_TAG)
        return Config(
                showSections = showSections,
                aboveChats = ordered.take(chatsIndex).mapNotNull { byTag[it] },
                belowChats = ordered.drop(chatsIndex + 1).mapNotNull { byTag[it] },
        )
    }

    fun get(session: Session): Config =
            parse(session.accountDataService().getUserAccountDataEvent(WEB_SETTINGS_EVENT_TYPE)?.content)

    fun flow(session: Session): Flow<Config> =
            session.accountDataService().getUserAccountDataEventsFlow(setOf(WEB_SETTINGS_EVENT_TYPE))
                    .map { events -> parse(events.firstOrNull()?.content) }
                    .onStart { emit(get(session)) }
                    .distinctUntilChanged()

    fun isSectionTag(tagName: String): Boolean = tagName.startsWith(SECTION_TAG_PREFIX)

    /** Creates a section named [name], ordered just above the catch-all like web, and returns its tag. */
    suspend fun createSection(session: Session, name: String, spaceId: String?): String {
        val tag = SECTION_TAG_PREFIX + UUID.randomUUID().toString()
        updateSettings(session) { settings ->
            val data = (settings[KEY_SECTION_DATA] as? Map<*, *>).orEmpty().toMutableMap()
            data[tag] = mapOf("tag" to tag, "name" to name, "spaceId" to (spaceId ?: HOME_SPACE_ID))
            settings[KEY_SECTION_DATA] = data

            val order = (settings[KEY_SECTION_ORDER] as? List<*>).orEmpty().filterIsInstance<String>().toMutableList()
            if (CHATS_TAG !in order) order += CHATS_TAG
            order.add(order.indexOf(CHATS_TAG), tag)
            settings[KEY_SECTION_ORDER] = order
        }
        return tag
    }

    suspend fun renameSection(session: Session, tag: String, newName: String) {
        updateSettings(session) { settings ->
            val data = (settings[KEY_SECTION_DATA] as? Map<*, *>).orEmpty().toMutableMap()
            val section = (data[tag] as? Map<*, *>) ?: return@updateSettings
            data[tag] = section.toMutableMap().apply { put("name", newName) }
            settings[KEY_SECTION_DATA] = data
        }
    }

    /**
     * Moves [tag] one slot up or down among the reorderable sections (customs + the catch-all
     * sentinel), so a section can also be moved above/below the catch-all like on web.
     */
    suspend fun moveSection(session: Session, tag: String, up: Boolean) {
        updateSettings(session) { settings ->
            val order = normalizedReorderableOrder(settings)
            val index = order.indexOf(tag)
            val newIndex = if (up) index - 1 else index + 1
            if (index == -1 || newIndex < 0 || newIndex >= order.size) return@updateSettings
            order[index] = order[newIndex].also { order[newIndex] = order[index] }
            settings[KEY_SECTION_ORDER] = order
        }
    }

    /** Replaces the reorderable order (customs + the catch-all sentinel) with [order], from drag-reorder. */
    suspend fun setSectionOrder(session: Session, order: List<String>) {
        updateSettings(session) { settings ->
            val data = (settings[KEY_SECTION_DATA] as? Map<*, *>).orEmpty()
            val sanitized = order.filter { it == CHATS_TAG || (isSectionTag(it) && data.containsKey(it)) }.distinct().toMutableList()
            if (CHATS_TAG !in sanitized) sanitized += CHATS_TAG
            // Sections the visual order didn't cover (e.g. created concurrently) keep web's default slot.
            sanitized.addAll(sanitized.indexOf(CHATS_TAG), data.keys.filterIsInstance<String>().filter { isSectionTag(it) && it !in sanitized })
            settings[KEY_SECTION_ORDER] = sanitized
        }
    }

    private fun normalizedReorderableOrder(settings: Map<String, Any>): MutableList<String> {
        val data = (settings[KEY_SECTION_DATA] as? Map<*, *>).orEmpty()
        val stored = (settings[KEY_SECTION_ORDER] as? List<*>).orEmpty().filterIsInstance<String>()
        val order = stored.filter { it == CHATS_TAG || (isSectionTag(it) && data.containsKey(it)) }.distinct().toMutableList()
        if (CHATS_TAG !in order) order += CHATS_TAG
        order.addAll(order.indexOf(CHATS_TAG), data.keys.filterIsInstance<String>().filter { isSectionTag(it) && it !in order })
        return order
    }

    /** Removes the definition only; orphaned room tags are left behind, matching web. */
    suspend fun deleteSection(session: Session, tag: String) {
        updateSettings(session) { settings ->
            val order = (settings[KEY_SECTION_ORDER] as? List<*>).orEmpty().filterIsInstance<String>()
            settings[KEY_SECTION_ORDER] = order.filter { it != tag }
            val data = (settings[KEY_SECTION_DATA] as? Map<*, *>).orEmpty().toMutableMap()
            data.remove(tag)
            settings[KEY_SECTION_DATA] = data
        }
    }

    // The settings event is shared with Element Web, so writes must merge into the latest content
    // rather than replace it.
    private suspend fun updateSettings(session: Session, mutate: (MutableMap<String, Any>) -> Unit) {
        val current = session.accountDataService().getUserAccountDataEvent(WEB_SETTINGS_EVENT_TYPE)
                ?.content.orEmpty().toMutableMap()
        mutate(current)
        @Suppress("UNCHECKED_CAST")
        val sanitized = coerceWholeDoublesToLongs(current) as Content
        session.accountDataService().updateUserAccountData(WEB_SETTINGS_EVENT_TYPE, sanitized)
    }

    // Moshi's Any adapter parses every JSON number as Double, so re-serializing untouched settings
    // would emit e.g. 80.0 — Synapse strictly rejects that (M_BAD_JSON "Bad JSON value: float").
    private fun coerceWholeDoublesToLongs(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
                value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            value.toLong()
        } else value
        is Map<*, *> -> value.mapValues { coerceWholeDoublesToLongs(it.value) }
        is List<*> -> value.map { coerceWholeDoublesToLongs(it) }
        else -> value
    }
}
