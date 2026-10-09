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
import im.vector.app.features.imagepack.EmojiShortNames
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.imagepack.ImagePackSource
import im.vector.app.features.imagepack.ImagePackUsageFilter
import im.vector.app.features.reactions.data.EmojiCatalogCache
import im.vector.app.features.reactions.data.EmojiCatalogCategory
import im.vector.app.features.reactions.data.EmojiDataSource
import im.vector.app.features.reactions.data.FrecencyDataSource
import im.vector.app.features.reactions.data.RecentEmojiDataSource
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
        private val frecencyDataSource: FrecencyDataSource,
        private val catalogCache: EmojiCatalogCache,
        private val stringProvider: StringProvider,
        private val emojiShortNames: EmojiShortNames,
) {

    /** Resolves an emote's `mxc` key to its shortcode, for the frequently used row. */
    private val mxcToShortcode = HashMap<String, String>()

    /**
     * Readies what a picker draws — the emoji categories and the room's packs — before one is opened.
     * Cheap once the catalog is on disk; safe to call repeatedly.
     */
    suspend fun warm(roomId: String?) = withContext(Dispatchers.Default) {
        imagePackProvider.warmImagePacks(roomId)
        emojiSections()
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

        // Forget the frecency of emotes deleted from the packs.
        frecencyDataSource.pruneEmotes(validEmoteMxcs)

        val frequent = buildFrequentlyUsed(contentUrlResolver, validEmoteMxcs)
        return listOfNotNull(frequent) + emoteSections + emojiSections
    }

    private fun buildFrequentlyUsed(contentUrlResolver: ContentUrlResolver?, validEmoteMxcs: Set<String>): EmojiPickerSection? {
        val items = recentEmojiDataSource.frequentlyUsed()
                .mapNotNull { (emojiOrMxc, _) ->
                    if (emojiOrMxc.isMxcUrl()) {
                        // Only emotes still in a pack: a deleted one renders blank and sends an empty `::`.
                        val shortcode = mxcToShortcode[emojiOrMxc]?.takeIf { emojiOrMxc in validEmoteMxcs } ?: return@mapNotNull null
                        EmojiPickerItem.Emote(
                                key = emojiOrMxc,
                                shortcode = shortcode,
                                resolvedUrl = contentUrlResolver?.fullSize(emojiOrMxc),
                                contentDescription = shortcode,
                        )
                    } else {
                        EmojiPickerItem.Unicode(emojiOrMxc)
                    }
                }
                .take(FREQUENT_LIMIT)
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
        val emojiNames = emojiShortNames.searchTerms(query).drop(1)
        return sections.mapNotNull { section ->
            val items = section.items.filter { item ->
                when (item) {
                    is EmojiPickerItem.Unicode -> item.glyph in matchingGlyphs
                    is EmojiPickerItem.Emote -> item.shortcode.contains(query, ignoreCase = true) ||
                            item.contentDescription.contains(query, ignoreCase = true) ||
                            emojiNames.any {
                                EmojiShortNames.nameContains(item.shortcode, it) || EmojiShortNames.nameContains(item.contentDescription, it)
                            } ||
                            (emojiShortNames.namesIn(item.shortcode) + emojiShortNames.namesIn(item.contentDescription))
                                    .any { EmojiShortNames.nameContains(it, query) }
                }
            }
            if (items.isEmpty()) null else section.copy(items = items)
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
