/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.net.Uri
import androidx.lifecycle.asFlow
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.imagepack.ImagePackSource
import im.vector.app.features.imagepack.ResolvedImagePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.coroutineContext
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackContent
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackRoomsContent
import org.matrix.android.sdk.api.session.room.model.imagepack.effectiveImages
import org.matrix.android.sdk.api.session.room.state.StateService
import org.matrix.android.sdk.api.util.JsonDict
import javax.inject.Inject

/**
 * Reads and writes MSC2545 image packs for the authoring UI. Reading tolerates legacy ids; writing always
 * targets the stable identifiers (the personal pack keeps `im.ponies.user_emotes`, which has no stable id).
 */
class ImagePackRepository @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val imagePackProvider: ImagePackProvider,
        private val diskCache: ImagePackDiskCache,
) {

    private val roomPackTypes = setOf(EventType.STATE_ROOM_IMAGE_PACK, EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE)

    // Keys the editor re-writes itself (so they're not carried over from the existing event): the current
    // `images` map, the `pack` object, and the legacy `emoticons` / `short` maps we migrate away from.
    private val droppedOnSaveKeys = setOf("images", "pack", "emoticons", "short")

    /**
     * Live list data for the authoring screen, recomputed off the main thread whenever the underlying
     * state changes (room packs for a room, or account data — personal pack + global toggles — for settings).
     * Doing the (potentially all-rooms) scan on [Dispatchers.IO] avoids the ANR seen on open/toggle.
     */
    fun listDataLive(roomId: String?): Flow<ImagePackListController.Data> {
        val session = activeSessionHolder.getSafeActiveSession() ?: return flowOf(settingsData(emptyList()))
        return if (roomId != null) {
            val trigger = session.roomService().getRoom(roomId)?.stateService()
                    ?.getStateEventsLive(roomPackTypes, QueryStringValue.IsNotNull)?.asFlow()
                    ?: flowOf(null)
            trigger.map { withContext(Dispatchers.IO) { roomData(roomId) } }
        } else {
            // Settings: show the personal pack + last scan instantly, then reconcile with a fresh scan.
            val trigger = session.accountDataService().getUserAccountDataEventsFlow(
                    setOf(
                            UserAccountDataTypes.TYPE_USER_EMOTES,
                            UserAccountDataTypes.TYPE_IMAGE_PACK_ROOMS,
                            UserAccountDataTypes.TYPE_IMAGE_PACK_ROOMS_UNSTABLE,
                    )
            )
            val userId = session.myUserId
            flow {
                // 1. Instant — computed on IO (it does Realm + file reads): the warm in-memory scan, else
                //    the on-disk cache, else at least the personal pack so the screen never blocks on open.
                emit(withContext(Dispatchers.IO) {
                    imagePackProvider.cachedAllRoomsPacks()?.let { settingsData(it) }
                            ?: diskCache.load(userId)?.let { settingsDataFromCache(it) }
                            ?: settingsData(emptyList())
                })
                // 2. Fresh all-rooms scan (off-main); reconcile the list, refresh counts, persist to disk.
                emit(withContext(Dispatchers.IO) {
                    val fresh = imagePackProvider.getAllRoomsPacks()
                    diskCache.save(userId, fresh.toCacheEntries())
                    settingsData(fresh)
                })
                // 3. Follow account-data changes (toggles) by only re-mapping the cached packs — a toggle
                //    changes enabled state, not which packs exist, so no all-rooms rescan is needed.
                emitAll(trigger.drop(1).map { withContext(Dispatchers.IO) { settingsData(imagePackProvider.cachedAllRoomsPacks().orEmpty()) } })
            }
        }
    }

    private fun List<ResolvedImagePack>.toCacheEntries(): List<ImagePackDiskCache.Entry> = mapNotNull { pack ->
        val roomId = pack.roomId ?: return@mapNotNull null
        val stateKey = pack.stateKey ?: return@mapNotNull null
        ImagePackDiskCache.Entry(roomId, stateKey, pack.displayName, pack.avatarUrl, pack.images.firstOrNull()?.mxcUrl, pack.images.size)
    }

    private fun settingsDataFromCache(entries: List<ImagePackDiskCache.Entry>): ImagePackListController.Data {
        // Fresh scans are saved room-grouped/sorted, but a cache written by an older version may carry
        // insertion order — re-sort so even the instant first paint is deterministic.
        val roomPacks = entries
                .map { entry -> roomManagedPack(entry.roomId, entry.stateKey, entry.displayName, entry.avatarUrl, entry.firstImageUrl, entry.imageCount) }
                .sortedWith(compareBy(
                        { it.roomDisplayName.orEmpty().lowercase() },
                        { it.roomId.orEmpty() },
                        { it.displayName.orEmpty().lowercase() },
                        { it.stateKey.orEmpty() },
                ))
        return ImagePackListController.Data(
                packs = listOf(accountManagedPack()) + roomPacks,
                canCreateRoomPack = false,
                hasAccountPack = true,
                inRoom = false,
        )
    }

    private fun roomData(roomId: String): ImagePackListController.Data = ImagePackListController.Data(
            packs = getRoomOwnPacks(roomId),
            canCreateRoomPack = canEditRoomPacks(roomId),
            hasAccountPack = true,
            inRoom = true,
    )

    private fun roomManagedPack(roomId: String, stateKey: String, displayName: String?, avatarUrl: String?, firstImageUrl: String?, imageCount: Int): ManagedPack {
        val session = activeSessionHolder.getSafeActiveSession()
        return ManagedPack(
                kind = ManagedPackKind.GLOBAL,
                displayName = displayName,
                avatarUrl = avatarUrl,
                firstImageUrl = firstImageUrl,
                imageCount = imageCount,
                roomId = roomId,
                stateKey = stateKey,
                canEdit = canEditRoomPacks(roomId),
                canToggleGlobal = true,
                isGloballyEnabled = isPackEnabledGlobally(roomId, stateKey),
                roomDisplayName = session?.roomService()?.getRoomSummary(roomId)?.displayName,
        )
    }

    private fun settingsData(roomPacks: List<ResolvedImagePack>): ImagePackListController.Data {
        val packs = mutableListOf(accountManagedPack())
        roomPacks.forEach { pack ->
            val roomId = pack.roomId ?: return@forEach
            val stateKey = pack.stateKey ?: return@forEach
            packs += roomManagedPack(roomId, stateKey, pack.displayName, pack.avatarUrl, pack.images.firstOrNull()?.mxcUrl, pack.images.size)
        }
        return ImagePackListController.Data(packs = packs, canCreateRoomPack = false, hasAccountPack = true, inRoom = false)
    }

    // The personal pack is always shown in settings (clicking it opens the editor, creating it if empty).
    private fun accountManagedPack(): ManagedPack {
        val content = getAccountPack()
        return ManagedPack(
                kind = ManagedPackKind.ACCOUNT,
                displayName = null,
                avatarUrl = content?.pack?.avatarUrl,
                firstImageUrl = content?.effectiveImages()?.values?.firstOrNull()?.url,
                imageCount = content?.effectiveImages()?.size ?: 0,
                roomId = null,
                stateKey = null,
                canEdit = true,
                canToggleGlobal = false,
                isGloballyEnabled = false,
        )
    }

    /** Just the packs defined in [roomId] itself — no personal/space/global, no toggles (per-room screen). */
    fun getRoomOwnPacks(roomId: String): List<ManagedPack> {
        val canEdit = canEditRoomPacks(roomId)
        return imagePackProvider.getRoomOwnPacks(roomId).map { pack ->
            ManagedPack(
                    kind = ManagedPackKind.THIS_ROOM,
                    displayName = pack.displayName,
                    avatarUrl = pack.avatarUrl,
                    firstImageUrl = pack.images.firstOrNull()?.mxcUrl,
                    imageCount = pack.images.size,
                    roomId = pack.roomId,
                    stateKey = pack.stateKey,
                    canEdit = canEdit,
                    canToggleGlobal = false,
                    isGloballyEnabled = false,
            )
        }
    }

    fun hasAccountPack(): Boolean = getAccountPack()?.effectiveImages()?.isNotEmpty() == true

    // ----- Personal account pack -----

    fun getAccountPack(): ImagePackContent? {
        val session = activeSessionHolder.getSafeActiveSession() ?: return null
        return session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_USER_EMOTES)
                ?.content.toModel()
    }

    suspend fun saveAccountPack(content: ImagePackContent, includeUsage: Boolean = false) {
        val session = activeSessionHolder.getActiveSession()
        val existing = session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_USER_EMOTES)?.content
        session.accountDataService().updateUserAccountData(UserAccountDataTypes.TYPE_USER_EMOTES, mergePackContent(existing, content, includeUsage))
    }

    suspend fun deleteAccountPack() {
        val session = activeSessionHolder.getActiveSession()
        session.accountDataService().updateUserAccountData(UserAccountDataTypes.TYPE_USER_EMOTES, ImagePackContent().toContent())
    }

    // ----- Room pack (stable m.room.image_pack state event) -----

    fun getRoomPack(roomId: String, stateKey: String): ImagePackContent? {
        val session = activeSessionHolder.getSafeActiveSession() ?: return null
        val room = session.roomService().getRoom(roomId) ?: return null
        return room.stateService().canonicalPackEvent(stateKey)?.content.toModel()
    }

    private fun Event.packHasImages(): Boolean = content.toModel<ImagePackContent>()?.effectiveImages().isNullOrEmpty().not()

    // Has any content at all — only a fully-cleared `{}` (a deleted pack) is empty.
    private fun Event.packHasContent(): Boolean = content?.isNotEmpty() == true

    // The pack a (roomId, stateKey) actually lives in. Prefer the legacy im.ponies.room_emotes event
    // whenever it still carries the pack, so an existing legacy pack keeps its format (and an empty stable
    // event never shadows it); the stable m.room.image_pack id is only used when legacy can't be. Read AND
    // write go through this so editing a legacy/empty-key pack stays in place.
    private fun StateService.canonicalPackEvent(stateKey: String): Event? {
        val stable = getStateEvent(EventType.STATE_ROOM_IMAGE_PACK, QueryStringValue.Equals(stateKey))
        val legacy = getStateEvent(EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE, QueryStringValue.Equals(stateKey))
        return when {
            legacy?.packHasImages() == true -> legacy
            stable?.packHasImages() == true -> stable
            legacy?.packHasContent() == true -> legacy
            stable?.packHasContent() == true -> stable
            // Both missing or deleted (`{}`): a brand-new pack — create it with the stable id.
            else -> stable ?: legacy
        }
    }

    // Per-image usage is a legacy im.ponies extension (the current MSC2545 schema carries usage on the pack
    // only), so the editor offers per-image toggles solely for packs living in an unstable-id event.
    fun isRoomPackLegacy(roomId: String, stateKey: String): Boolean {
        val session = activeSessionHolder.getSafeActiveSession() ?: return false
        val room = session.roomService().getRoom(roomId) ?: return false
        return room.stateService().canonicalPackEvent(stateKey)?.type == EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE
    }

    fun canEditRoomPacks(roomId: String): Boolean {
        val session = activeSessionHolder.getSafeActiveSession() ?: return false
        val room = session.roomService().getRoom(roomId) ?: return false
        return room.stateService().getRoomPowerLevels()
                .isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_IMAGE_PACK)
    }

    suspend fun saveRoomPack(roomId: String, stateKey: String, content: ImagePackContent, includeUsage: Boolean = false) {
        val session = activeSessionHolder.getActiveSession()
        val room = session.roomService().getRoom(roomId) ?: return
        // Edit the pack in whichever event it already lives in (preserving its id AND its state key, which
        // for older packs is the empty string), so a legacy pack stays legacy instead of spawning a second
        // (stable) copy. A brand-new pack is written with the stable id.
        val canonical = room.stateService().canonicalPackEvent(stateKey)
        val type = canonical?.type ?: EventType.STATE_ROOM_IMAGE_PACK
        room.stateService().sendStateEvent(type, stateKey, mergePackContent(canonical?.content, content, includeUsage))
        // A pack you create is enabled (usable in pickers) right away; packs from other rooms you're in
        // still have to be turned on from the settings list.
        if (canonical == null) setPackEnabledGlobally(roomId, stateKey, true)
    }

    suspend fun clearRoomPack(roomId: String, stateKey: String) {
        val session = activeSessionHolder.getActiveSession()
        val room = session.roomService().getRoom(roomId) ?: return
        val type = room.stateService().canonicalPackEvent(stateKey)?.type ?: EventType.STATE_ROOM_IMAGE_PACK
        room.stateService().sendStateEvent(type, stateKey, ImagePackContent().toContent())
    }

    // Merge the editor's content over the pack's existing raw event so we never silently drop fields we
    // don't model: the editor fully owns `images` and the pack's display_name / avatar_url, but every other
    // key (pack `usage`, `attribution`, and any unknown top-level or pack field) is passed through untouched.
    // [includeUsage] additionally lets the new content own the pack `usage` (the zip import sets it; the
    // editor never does, so its saves keep passing existing usage through).
    private fun mergePackContent(existing: JsonDict?, content: ImagePackContent, includeUsage: Boolean = false): JsonDict {
        val newMap = content.toContent()
        val result = LinkedHashMap<String, Any>()
        // Drop legacy image maps too: we re-write the pack under the current `images` key, so leaving the old
        // `emoticons` / `short` keys behind would duplicate (or shadow) it for other clients.
        existing?.forEach { (key, value) -> if (key !in droppedOnSaveKeys) result[key] = value }
        newMap["images"]?.let { result["images"] = it }

        val pack = LinkedHashMap<String, Any>()
        (existing?.get("pack") as? Map<*, *>)?.forEach { (key, value) -> if (key is String && value != null) pack[key] = value }
        val newPack = newMap["pack"] as? Map<*, *>
        pack.setOrRemove("display_name", newPack?.get("display_name"))
        pack.setOrRemove("avatar_url", newPack?.get("avatar_url"))
        if (includeUsage) pack.setOrRemove("usage", newPack?.get("usage"))
        if (pack.isNotEmpty()) result["pack"] = pack
        return result
    }

    private fun MutableMap<String, Any>.setOrRemove(key: String, value: Any?) {
        if (value != null) this[key] = value else remove(key)
    }

    // ----- Global enablement (m.image_pack.rooms) -----

    fun getImagePackRooms(): ImagePackRoomsContent? {
        val session = activeSessionHolder.getSafeActiveSession() ?: return null
        return (session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_IMAGE_PACK_ROOMS)
                ?: session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_IMAGE_PACK_ROOMS_UNSTABLE))
                ?.content.toModel()
    }

    fun isPackEnabledGlobally(roomId: String, stateKey: String): Boolean {
        return getImagePackRooms()?.rooms?.get(roomId)?.containsKey(stateKey) == true
    }

    suspend fun setPackEnabledGlobally(roomId: String, stateKey: String, enabled: Boolean) {
        val session = activeSessionHolder.getActiveSession()
        val current = getImagePackRooms()?.rooms?.mapValues { it.value.toMutableMap() }?.toMutableMap() ?: mutableMapOf()
        val roomEntry = current.getOrPut(roomId) { mutableMapOf() }
        if (enabled) {
            // Preserve any existing opaque object (reserved for future use).
            if (!roomEntry.containsKey(stateKey)) roomEntry[stateKey] = emptyMap()
        } else {
            roomEntry.remove(stateKey)
            if (roomEntry.isEmpty()) current.remove(roomId)
        }
        session.accountDataService().updateUserAccountData(
                UserAccountDataTypes.TYPE_IMAGE_PACK_ROOMS,
                ImagePackRoomsContent(rooms = current).toContent()
        )
    }

    // ----- Media upload -----

    suspend fun uploadImage(uri: Uri, fileName: String?, mimeType: String?): String {
        return activeSessionHolder.getActiveSession().fileService().uploadFile(uri.toString(), fileName, mimeType)
    }

    // Foreground uploads have no retry (unlike the media-send worker), and a flaky TLS connection can stall
    // until the 60s read timeout. Use a shorter per-attempt timeout and retry on a fresh connection.
    suspend fun uploadImageWithRetry(uri: Uri, fileName: String?, mimeType: String?): String {
        var lastError: Throwable? = null
        repeat(UPLOAD_MAX_ATTEMPTS) {
            coroutineContext.ensureActive()
            try {
                return withTimeout(UPLOAD_ATTEMPT_TIMEOUT_MS) { uploadImage(uri, fileName, mimeType) }
            } catch (timeout: TimeoutCancellationException) {
                lastError = timeout
            } catch (io: IOException) {
                coroutineContext.ensureActive()
                lastError = io
            }
        }
        // Not a CancellationException, so the caller surfaces it as a failure rather than silent cancellation.
        throw (lastError as? IOException) ?: IOException("Upload failed", lastError)
    }

    suspend fun compressImage(uri: Uri, mimeType: String?, maxDimension: Int): Pair<Uri, String?> {
        val result = activeSessionHolder.getActiveSession().fileService().compressImageForUpload(uri.toString(), mimeType, maxDimension)
        return result.uri to result.mimeType
    }

    companion object {
        private const val UPLOAD_MAX_ATTEMPTS = 3
        private const val UPLOAD_ATTEMPT_TIMEOUT_MS = 30_000L
    }
}
