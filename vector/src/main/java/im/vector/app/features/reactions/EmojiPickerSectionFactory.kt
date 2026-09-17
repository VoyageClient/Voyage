/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.imagepack.ImagePackSource
import im.vector.app.features.imagepack.ImagePackUsageFilter
import im.vector.app.features.reactions.data.EmojiCatalogCache
import im.vector.app.features.reactions.data.EmojiCatalogCategory
import im.vector.app.features.reactions.data.EmojiDataSource
import im.vector.app.features.reactions.data.RecentEmojiDataSource
import im.vector.app.features.reactions.data.RecentEmote
import im.vector.app.features.reactions.data.RecentEmoteDataSource
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import javax.inject.Inject

/**
 * Builds the unified picker sections (Frequently used + custom-emote packs + unicode emoji categories) and
 * records recents. Shared by the reaction picker and the inline emoji keyboard.
 */
class EmojiPickerSectionFactory @Inject constructor(
        private val emojiDataSource: EmojiDataSource,
        private val imagePackProvider: ImagePackProvider,
        private val activeSessionHolder: ActiveSessionHolder,
        private val recentEmojiDataSource: RecentEmojiDataSource,
        private val recentEmoteDataSource: RecentEmoteDataSource,
        private val catalogCache: EmojiCatalogCache,
        private val stringProvider: StringProvider,
) {

    /** Resolves a reaction/emote `mxc` key to its shortcode, for recents bookkeeping. */
    private val mxcToShortcode = HashMap<String, String>()

    /**
     * Readies what a picker draws — the emoji categories and the room's packs — before one is opened.
     * Cheap once the catalog is on disk; safe to call repeatedly.
     */
    suspend fun warm(roomId: String?) = withContext(Dispatchers.Default) {
        emojiSections()
        imagePackProvider.warmImagePacks(roomId)
    }

    /**
     * The unicode categories: from this process, else from the stored catalog, else built from the
     * bundled resource — which is the only path that has to parse it, and which stores the result.
     */
    private suspend fun emojiSections(): List<EmojiPickerSection> {
        emojiSectionsCache?.let { return it }
        // Both pickers can ask at once (a prewarm and an open); one builds, the other waits for it.
        return sectionsLock.withLock { emojiSectionsCache ?: loadEmojiSections() }
    }

    private suspend fun loadEmojiSections(): List<EmojiPickerSection> {
        catalogCache.read()?.let { stored ->
            val sections = stored.map { category ->
                EmojiPickerSection(
                        name = category.name,
                        tabGlyph = category.tabGlyph,
                        tabImageUrl = null,
                        items = category.glyphs.map { EmojiPickerItem.Unicode(it) },
                )
            }
            emojiSectionsCache = sections
            return sections
        }
        val rawData = emojiDataSource.rawData.await()
        val categories = rawData.categories.mapNotNull { category ->
            val glyphs = category.emojis.mapNotNull { key -> rawData.emojis[key]?.emoji }
            if (glyphs.isEmpty()) null else EmojiCatalogCategory(category.name, glyphs.first(), glyphs)
        }
        val sections = categories.map { category ->
            EmojiPickerSection(
                    name = category.name,
                    tabGlyph = category.tabGlyph,
                    tabImageUrl = null,
                    items = category.glyphs.map { EmojiPickerItem.Unicode(it) },
            )
        }
        if (sections.isNotEmpty()) {
            emojiSectionsCache = sections
            catalogCache.write(categories)
        }
        return sections
    }

    suspend fun build(roomId: String?): List<EmojiPickerSection> {
        // Have the searchable data on its way while the grid draws from the catalog.
        emojiDataSource.prime()
        // Outside the Default block: the stored catalog means a picker never waits on the resource.
        val emojiSections = emojiSections()
        return withContext(Dispatchers.Default) { buildBlocking(roomId, emojiSections) }
    }

    private fun buildBlocking(roomId: String?, emojiSections: List<EmojiPickerSection>): List<EmojiPickerSection> {
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()

        val validEmoteMxcs = HashSet<String>()
        // The cached packs when the room has them, so a picker is not held up by the aggregation; it
        // refreshes them itself for the next open.
        val resolved = imagePackProvider.cachedImagePacks(roomId).ifEmpty { imagePackProvider.warmImagePacks(roomId) }
                .ifEmpty { imagePackProvider.refreshImagePacks(roomId) }
        val enabledPacks = imagePackProvider.sortForDisplay(ImagePackUsageFilter.emoticonPacks(imagePackProvider.enabledPacksOf(resolved)))
        val emoteSections = enabledPacks.mapNotNull { pack ->
            val emotes = pack.images
            if (emotes.isEmpty()) return@mapNotNull null
            emotes.forEach { mxcToShortcode[it.mxcUrl] = it.shortcode; validEmoteMxcs.add(it.mxcUrl) }
            val tabMxc = pack.avatarUrl ?: emotes.first().mxcUrl
            EmojiPickerSection(
                    name = pack.displayName?.takeIf { it.isNotBlank() }
                            ?: if (pack.source == ImagePackSource.ACCOUNT) stringProvider.getString(CommonStrings.image_pack_personal_pack) else "",
                    tabGlyph = null,
                    tabImageUrl = contentUrlResolver?.fullSize(tabMxc),
                    items = emotes.map { it.toItem(contentUrlResolver) },
            )
        }

        // Keep the recents' stored shortcodes current now that we know each enabled emote's disambiguated form.
        recentEmoteDataSource.migrateShortcodes(mxcToShortcode.toMap())
        // Drop recents whose emote was deleted from the packs (so they don't send an empty :: shortcode).
        recentEmoteDataSource.pruneToValidMxcs(validEmoteMxcs)

        val frequent = buildFrequentlyUsed(contentUrlResolver, validEmoteMxcs)
        return listOfNotNull(frequent) + emoteSections + emojiSections
    }

    private fun buildFrequentlyUsed(contentUrlResolver: ContentUrlResolver?, validEmoteMxcs: Set<String>): EmojiPickerSection? {
        // Legacy recent_emoji entries can hold mxc emote keys — render those as emote images, and drop any
        // whose emote is no longer in a pack.
        val emojiRecents = recentEmojiDataSource.getRecentEmojisSnapshot()
                .filter { (value, _) -> !value.isMxcUrl() || value in validEmoteMxcs }
                .map { (value, count) ->
                    val item: EmojiPickerItem = if (value.isMxcUrl()) {
                        EmojiPickerItem.Emote(
                                key = value,
                                shortcode = mxcToShortcode[value].orEmpty(),
                                resolvedUrl = contentUrlResolver?.fullSize(value),
                                contentDescription = mxcToShortcode[value].orEmpty(),
                        )
                    } else {
                        EmojiPickerItem.Unicode(value)
                    }
                    item to count
                }
        val emoteRecents = recentEmoteDataSource.getRecentEmotesSnapshot()
                // Only emotes still in a pack — a deleted one renders blank / sends an empty :: shortcode.
                .filter { (emote, _) -> emote.mxcUrl in validEmoteMxcs }
                .map { (emote, count) ->
                    // Re-resolve to the emote's CURRENT (possibly disambiguated) shortcode by its stable mxc.
                    val shortcode = mxcToShortcode[emote.mxcUrl] ?: emote.shortcode
                    EmojiPickerItem.Emote(
                            key = emote.mxcUrl,
                            shortcode = shortcode,
                            resolvedUrl = contentUrlResolver?.fullSize(emote.mxcUrl),
                            contentDescription = shortcode,
                    ) as EmojiPickerItem to count
                }
        val items = (emojiRecents + emoteRecents)
                .sortedByDescending { it.second }
                .distinctBy { (it.first as? EmojiPickerItem.Emote)?.key ?: (it.first as EmojiPickerItem.Unicode).glyph }
                .take(FREQUENT_LIMIT)
                .map { it.first }
        if (items.isEmpty()) return null
        return EmojiPickerSection(
                name = stringProvider.getString(CommonStrings.sticker_picker_frequently_used),
                tabGlyph = null,
                tabImageUrl = null,
                tabIconRes = R.drawable.ic_clock,
                items = items,
        )
    }

    /**
     * Narrows built sections to what matches [query], keeping the category grouping and dropping the
     * categories left with nothing. Unicode emojis match on name/keyword, emotes on their shortcode.
     */
    suspend fun filterSections(sections: List<EmojiPickerSection>, query: String): List<EmojiPickerSection> = withContext(Dispatchers.Default) {
        filterSectionsBlocking(sections, query)
    }

    private suspend fun filterSectionsBlocking(sections: List<EmojiPickerSection>, query: String): List<EmojiPickerSection> {
        if (query.isBlank()) return sections
        val matchingGlyphs = emojiDataSource.filterWith(query).mapTo(HashSet()) { it.emoji }
        return sections.mapNotNull { section ->
            val items = section.items.filter { item ->
                when (item) {
                    is EmojiPickerItem.Unicode -> item.glyph in matchingGlyphs
                    is EmojiPickerItem.Emote -> item.shortcode.contains(query, ignoreCase = true) ||
                            item.contentDescription.contains(query, ignoreCase = true)
                }
            }
            if (items.isEmpty()) null else section.copy(items = items)
        }
    }

    /** Records a tap on a reaction/emote key (mxc) or unicode glyph into the relevant recents store. */
    fun recordUse(reaction: String) {
        if (reaction.isMxcUrl()) {
            val shortcode = mxcToShortcode[reaction] ?: return
            recentEmoteDataSource.recordEmoteUse(RecentEmote(reaction, shortcode))
        } else {
            recentEmojiDataSource.recordEmojiUse(listOf(reaction))
        }
    }

    private fun im.vector.app.features.imagepack.ResolvedImage.toItem(contentUrlResolver: ContentUrlResolver?) =
            EmojiPickerItem.Emote(
                    key = mxcUrl,
                    shortcode = shortcode,
                    resolvedUrl = contentUrlResolver?.fullSize(mxcUrl),
                    contentDescription = body ?: shortcode,
            )

    // Full (original) file so animated custom emotes actually animate; the grid downsamples it to cell size.
    private fun ContentUrlResolver.fullSize(mxc: String) = resolveFullSize(mxc)

    companion object {
        private const val FREQUENT_LIMIT = 48

        /**
         * The unicode categories, built once per process rather than per picker: they come from an app
         * resource and never change, while every open used to rebuild all ~1800 of their items. Held
         * here rather than on the instance because each screen injects a factory of its own — and
         * because, unlike the rest of a factory's state, this carries nothing account-specific.
         */
        @Volatile
        private var emojiSectionsCache: List<EmojiPickerSection>? = null

        private val sectionsLock = Mutex()
    }
}
