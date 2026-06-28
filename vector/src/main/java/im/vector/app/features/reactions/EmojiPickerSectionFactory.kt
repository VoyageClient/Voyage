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
import im.vector.app.features.reactions.data.EmojiDataSource
import im.vector.app.features.reactions.data.RecentEmote
import im.vector.app.features.reactions.data.RecentEmojiDataSource
import im.vector.app.features.reactions.data.RecentEmoteDataSource
import im.vector.lib.strings.CommonStrings
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
        private val stringProvider: StringProvider,
) {

    /** Resolves a reaction/emote `mxc` key to its shortcode, for recents bookkeeping. */
    private val mxcToShortcode = HashMap<String, String>()

    suspend fun build(roomId: String?): List<EmojiPickerSection> {
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
        val rawData = emojiDataSource.rawData.await()

        val validEmoteMxcs = HashSet<String>()
        val emoteSections = ImagePackUsageFilter.emoticonPacks(imagePackProvider.getEnabledImagePacks(roomId)).mapNotNull { pack ->
            val emotes = pack.images
            if (emotes.isEmpty()) return@mapNotNull null
            emotes.forEach { mxcToShortcode[it.mxcUrl] = it.shortcode; validEmoteMxcs.add(it.mxcUrl) }
            val tabMxc = pack.avatarUrl ?: emotes.first().mxcUrl
            EmojiPickerSection(
                    name = pack.displayName?.takeIf { it.isNotBlank() }
                            ?: if (pack.source == ImagePackSource.ACCOUNT) stringProvider.getString(CommonStrings.image_pack_personal_pack) else "",
                    tabGlyph = null,
                    tabImageUrl = contentUrlResolver?.thumb(tabMxc),
                    items = emotes.map { it.toItem(contentUrlResolver) },
            )
        }

        val emojiSections = rawData.categories.map { category ->
            EmojiPickerSection(
                    name = category.name,
                    tabGlyph = category.emojis.firstOrNull()?.let { rawData.emojis[it]?.emoji },
                    tabImageUrl = null,
                    items = category.emojis.mapNotNull { key -> rawData.emojis[key]?.emoji?.let { EmojiPickerItem.Unicode(it) } },
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

    /** Flat search results (custom emotes by shortcode first, then unicode emojis by name/keyword). */
    suspend fun search(roomId: String?, query: String): List<EmojiPickerItem> {
        if (query.isBlank()) return emptyList()
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
        val emotes = imagePackProvider.getEmoticons(roomId)
                .filter { it.shortcode.contains(query, ignoreCase = true) }
                .onEach { mxcToShortcode[it.mxcUrl] = it.shortcode }
                .map { it.toItem(contentUrlResolver) }
        val emojis = emojiDataSource.filterWith(query).map { EmojiPickerItem.Unicode(it.emoji) }
        return emotes + emojis
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

    private fun ContentUrlResolver.thumb(mxc: String) = resolveThumbnail(mxc, 96, 96, ContentUrlResolver.ThumbnailMethod.SCALE)

    // Full (original) file so animated custom emotes actually animate; the grid downsamples it to cell size.
    private fun ContentUrlResolver.fullSize(mxc: String) = resolveFullSize(mxc)

    companion object {
        private const val FREQUENT_LIMIT = 24
    }
}
