/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import androidx.lifecycle.asFlow
import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackContent
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackRoomsContent
import org.matrix.android.sdk.api.session.room.model.imagepack.effectiveImages
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.matrix.android.sdk.api.session.room.model.imagepack.resolveUsages
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates MSC2545 image packs from every source (personal account pack, globally-enabled room packs,
 * the current room's packs and its canonical-space hierarchy) and exposes them for the emote autocomplete,
 * the sticker picker and reactions.
 *
 * Reading tolerates both the stable (`m.room.image_pack` / `m.image_pack.rooms`) and the legacy
 * (`im.ponies.*`) identifiers, plus legacy per-image `usage`.
 */
@Singleton
class ImagePackProvider @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
) {

    private val roomPackTypes = setOf(EventType.STATE_ROOM_IMAGE_PACK, EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE)

    // A room can carry the same pack under both the stable (m.room.image_pack) and legacy
    // (im.ponies.room_emotes) state event with the same state key. Keep one per state key, preferring the
    // legacy event whenever it carries the pack — so an empty stable event never shadows a populated legacy
    // one — and the stable id only otherwise. Matches ImagePackRepository's read/write selection.
    private fun List<Event>.uniqueRoomPackEvents(): List<Event> =
            groupBy { it.stateKey }
                    .values
                    .map { group ->
                        val legacy = group.firstOrNull { it.type == EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE }
                        val stable = group.firstOrNull { it.type == EventType.STATE_ROOM_IMAGE_PACK }
                        when {
                            legacy?.hasPackImages() == true -> legacy
                            stable?.hasPackImages() == true -> stable
                            legacy?.hasPackContent() == true -> legacy
                            stable?.hasPackContent() == true -> stable
                            else -> stable ?: legacy ?: group.first()
                        }
                    }

    private fun Event.hasPackImages(): Boolean = content.toModel<ImagePackContent>()?.effectiveImages().isNullOrEmpty().not()

    // MSC2545 defines no pack order and the state store's row order is arbitrary (insertion order), so sort
    // alphabetically for a deterministic display everywhere packs are listed.
    private fun List<ResolvedImagePack>.sortedAlphabetically(): List<ResolvedImagePack> =
            sortedWith(compareBy({ it.displayName.orEmpty().lowercase() }, { it.roomId.orEmpty() }, { it.stateKey.orEmpty() }))

    // A pack that still exists: only a fully-cleared `{}` event (an explicitly deleted pack) is empty. A pack
    // with 0 images but a `pack` object (e.g. just created) still counts, so it stays listed for editing.
    private fun Event.hasPackContent(): Boolean = content?.isNotEmpty() == true

    fun getImagePacks(roomId: String?): List<ResolvedImagePack> {
        val session = activeSessionHolder.getSafeActiveSession() ?: return emptyList()
        val packs = mutableListOf<ResolvedImagePack>()

        val emoteRooms = session.readImagePackRooms()
        fun isEnabled(packRoomId: String, stateKey: String?) =
                emoteRooms?.rooms?.get(packRoomId)?.containsKey(stateKey) == true

        // 1. Personal account pack (always enabled).
        session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_USER_EMOTES)
                ?.content.toModel<ImagePackContent>()
                ?.toResolved(ImagePackSource.ACCOUNT, roomId = null, stateKey = null, fallbackName = null, fallbackAvatar = null, enabled = true, legacyPack = true)
                ?.let { packs += it }

        // 2. Globally-enabled room packs (m.image_pack.rooms, fall back to im.ponies.emote_rooms).
        //    Skip the current room here; its packs are added in step 3 (avoids listing them twice).
        val globalPacks = mutableListOf<ResolvedImagePack>()
        emoteRooms?.rooms?.forEach { (refRoomId, stateKeys) ->
            if (refRoomId == roomId) return@forEach
            stateKeys.keys.forEach { stateKey ->
                session.readRoomPackEvent(refRoomId, stateKey)
                        ?.toResolvedPack(ImagePackSource.GLOBAL_ROOM, refRoomId, enabled = true)
                        ?.let { globalPacks += it }
            }
        }
        packs += globalPacks.sortedAlphabetically()

        // 3. Current room packs (enabled only when referenced in m.image_pack.rooms — authoring lists the rest).
        val room = roomId?.let { session.roomService().getRoom(it) }
        if (room != null) {
            packs += room.stateService().getStateEvents(roomPackTypes, QueryStringValue.IsNotNull).uniqueRoomPackEvents()
                    .mapNotNull { event -> event.toResolvedPack(ImagePackSource.CURRENT_ROOM, room.roomId, isEnabled(room.roomId, event.stateKey)) }
                    .sortedAlphabetically()

            // 4. Canonical-space hierarchy packs (flattenParentIds is already cycle/depth bounded by the SDK).
            room.roomSummary()?.flattenParentIds.orEmpty().take(MAX_SPACES).forEach { spaceId ->
                packs += session.roomService().getRoom(spaceId)
                        ?.stateService()
                        ?.getStateEvents(roomPackTypes, QueryStringValue.IsNotNull)
                        ?.uniqueRoomPackEvents()
                        ?.mapNotNull { event -> event.toResolvedPack(ImagePackSource.SPACE, spaceId, isEnabled(spaceId, event.stateKey)) }
                        ?.sortedAlphabetically()
                        .orEmpty()
            }
        }

        // A pack can be reached from more than one source (e.g. a parent space that is also globally
        // enabled); keep the highest-priority occurrence so it isn't listed twice.
        return packs.distinctBy { it.roomId to it.stateKey }
    }

    /** Only the packs active in pickers (personal pack + packs enabled via m.image_pack.rooms), with
     * duplicate emoticon shortcodes disambiguated (`name`, `name@2`, `name@3`…) in priority order. */
    fun getEnabledImagePacks(roomId: String?): List<ResolvedImagePack> = disambiguate(getImagePacks(roomId).filter { it.enabled })

    /** Display order for pack lists: personal pack first, then room packs grouped by room (rooms
     * alphabetical), packs alphabetical within their room. Display-only — aggregation priority
     * (which drives dedup and shortcode disambiguation) is unaffected. */
    fun sortForDisplay(packs: List<ResolvedImagePack>): List<ResolvedImagePack> {
        val session = activeSessionHolder.getSafeActiveSession()
        val roomNames = HashMap<String, String>()
        fun roomName(roomId: String?): String {
            if (roomId == null) return ""
            return roomNames.getOrPut(roomId) { session?.roomService()?.getRoomSummary(roomId)?.displayName.orEmpty() }
        }
        return packs.sortedWith(compareBy(
                { it.source != ImagePackSource.ACCOUNT },
                { roomName(it.roomId).lowercase() },
                { it.roomId.orEmpty() },
                { it.displayName.orEmpty().lowercase() },
                { it.stateKey.orEmpty() },
        ))
    }

    /** Only the packs defined in [roomId] itself (for the per-room authoring screen). */
    fun getRoomOwnPacks(roomId: String): List<ResolvedImagePack> {
        val session = activeSessionHolder.getSafeActiveSession() ?: return emptyList()
        val room = session.roomService().getRoom(roomId) ?: return emptyList()
        val emoteRooms = session.readImagePackRooms()
        return room.stateService().getStateEvents(roomPackTypes, QueryStringValue.IsNotNull).uniqueRoomPackEvents()
                .filter { it.hasPackContent() }
                .mapNotNull { event ->
                    event.toResolvedPack(ImagePackSource.CURRENT_ROOM, roomId, emoteRooms?.rooms?.get(roomId)?.containsKey(event.stateKey) == true, allowEmpty = true)
                }
                .sortedAlphabetically()
    }

    @Volatile private var allRoomsPacksCache: List<ResolvedImagePack>? = null

    /** Last scan result, for showing the settings list instantly while a fresh scan runs in the background. */
    fun cachedAllRoomsPacks(): List<ResolvedImagePack>? = allRoomsPacksCache

    /** Every pack in every joined room (for the global settings screen), each with its enabled state. */
    fun getAllRoomsPacks(): List<ResolvedImagePack> {
        val session = activeSessionHolder.getSafeActiveSession() ?: return emptyList()
        val emoteRooms = session.readImagePackRooms()
        // Grouped by room (rooms alphabetical), packs alphabetical within their room.
        return session.roomService().getRoomSummaries(roomSummaryQueryParams { memberships = listOf(Membership.JOIN) })
                .sortedWith(compareBy({ it.displayName.lowercase() }, { it.roomId }))
                .flatMap { summary ->
                    val room = session.roomService().getRoom(summary.roomId) ?: return@flatMap emptyList()
                    room.stateService().getStateEvents(roomPackTypes, QueryStringValue.IsNotNull).uniqueRoomPackEvents()
                            .filter { it.hasPackContent() }
                            .mapNotNull { event ->
                                event.toResolvedPack(ImagePackSource.GLOBAL_ROOM, summary.roomId, emoteRooms?.rooms?.get(summary.roomId)?.containsKey(event.stateKey) == true, allowEmpty = true)
                            }
                            .sortedAlphabetically()
                }
                .also { allRoomsPacksCache = it }
    }

    // A shortcode defined in only one (enabled) pack stays plain `:name:`. A shortcode defined in several packs
    // is disambiguated in ALL of them so the bare `:name:` is never an ambiguous suggestion:
    //  - the account pack uses `name@personal` (it's where the emote lives, not a pack name; can never collide),
    //  - a room pack uses `name/<pack-name-slug>`, falling back to `name/<pack-name-slug>@<room-id-slug>` when
    //    two packs share a name (rooms differ), and a `-N` tiebreaker only if even that collides.
    // `/` and `@` are stripped from real shortcodes on read so the suffix stays unambiguous; on send the
    // resolved emote's shortcode is used as-is (plain when unique, suffixed only when needed).
    private fun disambiguate(packs: List<ResolvedImagePack>): List<ResolvedImagePack> {
        val occurrences = HashMap<String, Int>()
        packs.forEach { pack ->
            pack.images.forEach { image ->
                if (ImagePackUsage.EMOTICON in image.usages) occurrences[image.shortcode] = (occurrences[image.shortcode] ?: 0) + 1
            }
        }
        // How many room packs share each (shortcode, pack-name-slug): when more than one, qualify by room id so
        // the chosen form doesn't depend on iteration order (the enabled set is global / room-independent).
        val slugUsage = HashMap<String, HashMap<String, Int>>()
        packs.forEach { pack ->
            if (pack.source == ImagePackSource.ACCOUNT) return@forEach
            pack.images.forEach { image ->
                if (ImagePackUsage.EMOTICON in image.usages && (occurrences[image.shortcode] ?: 0) > 1) {
                    val perSlug = slugUsage.getOrPut(image.shortcode) { HashMap() }
                    val slug = packSlug(pack)
                    perSlug[slug] = (perSlug[slug] ?: 0) + 1
                }
            }
        }
        val used = HashSet<String>()
        return packs.map { pack ->
            pack.copy(images = pack.images.map imageMap@{ image ->
                if (ImagePackUsage.EMOTICON !in image.usages) return@imageMap image
                if ((occurrences[image.shortcode] ?: 0) <= 1) return@imageMap image
                image.copy(shortcode = disambiguatedShortcode(image.shortcode, pack, slugUsage, used))
            })
        }
    }

    private fun packSlug(pack: ResolvedImagePack): String =
            pack.displayName?.let { slugify(it) }?.takeIf { it.isNotBlank() } ?: "pack"

    private fun disambiguatedShortcode(
            base: String,
            pack: ResolvedImagePack,
            slugUsage: Map<String, Map<String, Int>>,
            used: HashSet<String>,
    ): String {
        val candidate = if (pack.source == ImagePackSource.ACCOUNT) {
            "$base@personal"
        } else {
            val slug = packSlug(pack)
            val form = "$base/$slug"
            // Only qualify by room id when the pack-name slug alone is ambiguous (shared by several packs).
            if ((slugUsage[base]?.get(slug) ?: 0) > 1) {
                pack.roomId?.let { slugify(it) }?.takeIf { it.isNotBlank() }?.let { "$form@$it" } ?: form
            } else {
                form
            }
        }
        if (used.add(candidate)) return candidate
        var n = 2
        var unique = "$candidate-$n"
        while (!used.add(unique)) {
            n++
            unique = "$candidate-$n"
        }
        return unique
    }

    // Slug for disambiguation: lowercase ASCII [a-z0-9_], everything else collapsed to '-'. Also used to make a
    // room id safe to embed (room ids can contain ':' on v11 rooms, which would break `:shortcode:` parsing).
    private fun slugify(name: String): String = buildString {
        for (c in name.lowercase()) {
            append(if (c in 'a'..'z' || c in '0'..'9' || c == '_') c else '-')
        }
    }.trim('-')

    /** Emoticon-usable images across enabled packs, deduplicated by shortcode keeping the highest-priority pack. */
    fun getEmoticons(roomId: String?): List<ResolvedImage> = emoticonsOf(getEnabledImagePacks(roomId))

    /** Sticker-usable images across enabled packs. */
    fun getStickers(roomId: String?): List<ResolvedImage> = collectImages(getEnabledImagePacks(roomId), ImagePackUsage.STICKER)

    fun emoticonsOf(packs: List<ResolvedImagePack>): List<ResolvedImage> = collectImages(packs, ImagePackUsage.EMOTICON)

    private val emoticonCache = ConcurrentHashMap<String, List<ResolvedImage>>()

    /** Last computed emoticons for [roomId], so the autocomplete popup can prime instantly on room open. */
    fun cachedEmoticons(roomId: String?): List<ResolvedImage> = emoticonCache[roomId.orEmpty()].orEmpty()

    /** Emoticon images for the current room, re-emitted whenever any pack source changes. */
    fun getEmoticonsLive(roomId: String?): Flow<List<ResolvedImage>> = getImagePacksLive(roomId)
            .map { packs -> emoticonsOf(disambiguate(packs.filter { it.enabled })) }
            // The aggregation walks account data + room state + the space hierarchy; keep it off the main thread.
            // Safe off-main: each SDK read opens its own Realm.
            .flowOn(Dispatchers.Default)
            .onEach { emoticonCache[roomId.orEmpty()] = it }

    private fun collectImages(packs: List<ResolvedImagePack>, usage: String): List<ResolvedImage> {
        val seen = HashSet<String>()
        return packs
                .flatMap { it.images }
                .filter { usage in it.usages }
                .filter { seen.add(it.shortcode) }
    }

    fun getImagePacksLive(roomId: String?): Flow<List<ResolvedImagePack>> {
        val session = activeSessionHolder.getSafeActiveSession() ?: return flowOf(emptyList())
        val accountDataFlow = session.accountDataService()
                .getLiveUserAccountDataEvents(
                        setOf(
                                UserAccountDataTypes.TYPE_USER_EMOTES,
                                UserAccountDataTypes.TYPE_IMAGE_PACK_ROOMS,
                                UserAccountDataTypes.TYPE_IMAGE_PACK_ROOMS_UNSTABLE,
                        )
                )
                .asFlow()
        val roomStateFlow = roomId?.let { session.roomService().getRoom(it) }
                ?.stateService()
                ?.getStateEventsLive(roomPackTypes, QueryStringValue.IsNotNull)
                ?.asFlow()
                ?: flowOf(emptyList())
        return combine(accountDataFlow, roomStateFlow) { _, _ -> getImagePacks(roomId) }
    }

    private fun Session.readImagePackRooms(): ImagePackRoomsContent? {
        return (accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_IMAGE_PACK_ROOMS)
                ?: accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_IMAGE_PACK_ROOMS_UNSTABLE))
                ?.content.toModel()
    }

    private fun Session.readRoomPackEvent(roomId: String, stateKey: String): Event? {
        val room = roomService().getRoom(roomId) ?: return null
        // Same legacy-first selection as everywhere else, so a globally-enabled legacy pack isn't shadowed
        // by an empty stable event.
        val events = listOfNotNull(
                room.stateService().getStateEvent(EventType.STATE_ROOM_IMAGE_PACK, QueryStringValue.Equals(stateKey)),
                room.stateService().getStateEvent(EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE, QueryStringValue.Equals(stateKey)),
        )
        return events.uniqueRoomPackEvents().firstOrNull()
    }

    private fun Event.toResolvedPack(source: ImagePackSource, roomId: String, enabled: Boolean, allowEmpty: Boolean = false): ResolvedImagePack? {
        // MSC2545: an unnamed in-room pack defaults to the room's name. Deliberate deviation for the avatar:
        // no room-avatar fallback — surfaces show the pack's first image instead.
        val roomName = activeSessionHolder.getSafeActiveSession()?.roomService()?.getRoomSummary(roomId)?.displayName
        return content.toModel<ImagePackContent>()
                ?.toResolved(
                        source, roomId, stateKey,
                        fallbackName = roomName?.takeIf { it.isNotBlank() },
                        fallbackAvatar = null,
                        enabled = enabled,
                        allowEmpty = allowEmpty,
                        legacyPack = type == EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE,
                )
    }

    private fun ImagePackContent.toResolved(
            source: ImagePackSource,
            roomId: String?,
            stateKey: String?,
            fallbackName: String?,
            fallbackAvatar: String?,
            enabled: Boolean,
            // The authoring list keeps 0-image packs (so they stay editable); pickers drop them.
            allowEmpty: Boolean = false,
            // Legacy im.ponies packs may narrow usage per image when the pack itself doesn't restrict it.
            legacyPack: Boolean = false,
    ): ResolvedImagePack? {
        val images = effectiveImages().orEmpty()
        if (images.isEmpty() && !allowEmpty) return null
        val packName = pack?.displayName ?: fallbackName
        val resolved = images.mapNotNull { (rawShortcode, image) ->
            image.url.takeIf { it.startsWith("mxc://") } ?: return@mapNotNull null
            // Spec grammar excludes '/' and '@'; strip them so our disambiguation suffixes stay unambiguous.
            val shortcode = rawShortcode.replace("/", "").replace("@", "")
            if (shortcode.isBlank()) return@mapNotNull null
            ResolvedImage(
                    shortcode = shortcode,
                    mxcUrl = image.url,
                    body = image.body,
                    info = image.info,
                    usages = image.resolveUsages(pack, allowPerImage = legacyPack),
                    packDisplayName = packName,
                    personal = source == ImagePackSource.ACCOUNT,
            )
        }
        if (resolved.isEmpty() && !allowEmpty) return null
        return ResolvedImagePack(
                source = source,
                roomId = roomId,
                stateKey = stateKey,
                displayName = packName,
                avatarUrl = pack?.avatarUrl ?: fallbackAvatar,
                images = resolved,
                enabled = enabled,
        )
    }

    companion object {
        private const val MAX_SPACES = 16
    }
}
