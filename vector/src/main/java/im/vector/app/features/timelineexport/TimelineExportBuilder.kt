/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.ui.colorpicker.ColorPalette
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.home.room.detail.timeline.helper.TimelineDisplayableEvents
import im.vector.app.features.home.room.detail.timeline.helper.isDebugOnlyType
import im.vector.app.features.home.room.detail.timeline.selection.TimelineSelection
import im.vector.app.features.location.INITIAL_MAP_ZOOM_IN_TIMELINE
import im.vector.app.features.location.toLocationData
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.media.shouldHideMediaPreview
import im.vector.app.features.media.withAvatarHiddenIn
import im.vector.app.features.redaction.preservation.PreservedAttachmentResolver
import im.vector.app.features.redaction.preservation.RedactedContentRepository
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.room.VectorRoomDisplayNameFallbackProvider
import im.vector.app.features.settings.FontScalePreferences
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.admin.ServerAdminStatusDataSource
import im.vector.app.features.themes.BubbleThemeUtils
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.profile.IdentityAliases
import org.matrix.android.sdk.api.session.profile.IdentityPresentation
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.message.ForwardedInfo
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconLocationDataContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageLocationContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.galleryCaption
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.getForwardedInfo
import org.matrix.android.sdk.api.session.room.model.message.getThumbnailUrl
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastEditNewContent
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.MatrixJsonParser
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class TimelineExportBuilder @Inject constructor(
        @ApplicationContext private val context: Context,
        private val preferences: VectorPreferences,
        private val noticeFormatter: NoticeEventFormatter,
        private val restorer: RedactedContentRestorer,
        private val revealManager: MediaContentRevealManager,
        private val adminStatus: ServerAdminStatusDataSource,
        private val preservedAttachments: PreservedAttachmentResolver,
        private val fontScalePreferences: FontScalePreferences,
        private val emojiAssets: ExportEmojiAssets,
        private val rendering: TimelineExportRenderComponent.Factory,
        private val nameColors: MatrixItemColorProvider,
        private val roomNames: VectorRoomDisplayNameFallbackProvider,
) {
    private fun memberBuiltName(names: List<String>, isDirect: Boolean): String = when (names.size) {
        0 -> roomNames.getNameForEmptyRoom(isDirect, emptyList())
        1 -> roomNames.getNameFor1member(names[0])
        2 -> roomNames.getNameFor2members(names[0], names[1])
        3 -> roomNames.getNameFor3members(names[0], names[1], names[2])
        4 -> roomNames.getNameFor4members(names[0], names[1], names[2], names[3])
        else -> roomNames.getNameFor4membersAndMore(names[0], names[1], names[2], names.size - 3)
    }

    // Exports show people as they publish themselves, so a name this account shows is usable only when it's not
    // one of its private overrides or live aliases.
    private fun showsRealProfile(userId: String, roomId: String?): Boolean =
            ProfileOverrides.overrides[userId].isNullOrEmpty() && !IdentityPresentation.isAnonymous(userId, roomId)

    // What the original sender publishes, never the nickname this account gave them.
    private fun realForwardedSenderName(session: Session, userId: String, vararg roomIds: String?): String? =
            roomIds.filterNotNull().firstNotNullOfOrNull { roomId -> session.getRoom(roomId)?.membershipService()?.getRoomMember(userId)?.originalDisplayName }

    // The source room's own name: its summary's display name may be this account's personal name for it.
    private fun realForwardedRoomName(session: Session, origin: ForwardedInfo?): String? {
        val summary = origin?.roomId?.let { session.roomService().getRoomSummary(it) }
        return summary?.name?.takeIf { it.isNotBlank() } ?: origin?.roomName?.takeIf { it.isNotBlank() } ?: summary?.canonicalAlias
    }

    fun defaults(session: Session, roomId: String): TimelineExportOptions {
        val themeUtils = ThemeUtils
        val bubbles = BubbleThemeUtils(context)
        val theme = themeUtils.getApplicationTheme(context).takeUnless { it == "system" } ?: if (themeUtils.isLightTheme(context)) "light" else "dark"
        fun css(attribute: Int) = TimelineExportHtml.cssColor(themeUtils.getColor(context, attribute))
        val accent = themeUtils.getColor(context, com.google.android.material.R.attr.colorAccent)
        val room = session.getRoom(roomId)
        return TimelineExportOptions(
                theme = theme,
                layoutWidth = context.resources.configuration.screenWidthDp.coerceIn(240, 1440),
                phoneWidth = context.resources.configuration.screenWidthDp.coerceIn(240, 600),
                accent = TimelineExportHtml.cssColor(accent),
                background = css(android.R.attr.colorBackground),
                foreground = css(im.vector.lib.ui.styles.R.attr.vctr_content_primary),
                incoming = css(im.vector.lib.ui.styles.R.attr.sc_message_bg_incoming),
                outgoing = css(im.vector.lib.ui.styles.R.attr.sc_message_bg_outgoing),
                accentName = ThemeUtils.getApplicationThemeAccent(context),
                fontScale = fontScalePreferences.getResolvedFontScaleValue().scale,
                avatarShape = preferences.avatarShape().name,
                showProfileColors = preferences.showOthersProfileColors(),
                codePalette = preferences.codeBlockPalette().name,
                bubbles = bubbles.getBubbleStyle(),
                bubbleCorners = bubbles.getBubbleRoundnessSetting(),
                bubbleTail = bubbles.isTailEnabled(),
                bubbleTint = bubbles.isOutgoingTintEnabled(),
                timestamps = true,
                twelveHourTime = !DateFormat.is24HourFormat(context),
                receipts = preferences.showReadReceipts(),
                visibility = VectorPreferences.TimelineVisibilityRule.values().associate { rule ->
                    rule.name to (rule != VectorPreferences.TimelineVisibilityRule.HIDDEN_EVENTS &&
                            rule != VectorPreferences.TimelineVisibilityRule.HIDDEN_THREAD_EVENTS && preferences.timelineVisibility(rule, roomId))
                },
                identity = IdentityPresentation.config(session.myUserId).let { config -> config.copy(rule = config.ruleFor(roomId), rooms = emptyMap()) },
                roomHeader = room != null,
        )
    }

    suspend fun build(session: Session, draft: TimelineExportDraft, directory: File,
                      onStage: (ExportPreparationStage) -> Unit = {}, progress: (Int, Int) -> Unit): TimelineExportSnapshot =
            withContext(Dispatchers.IO) {
                val startedAt = SystemClock.elapsedRealtime()
                Timber.d("ExportTrace prepare start selected=%d", draft.eventIds.size)
                val room = session.getRoom(draft.roomId) ?: error(context.getString(CommonStrings.unknown_error))
                val options = draft.options
                val renderingScope = rendering.create()
                val informationFactory = renderingScope.information()
                val issues = mutableListOf<String>()
                val assets = LinkedHashMap<String, ExportAsset>()
                val assetUrls = HashMap<String, String>()
                val failedAssets = HashSet<String>()
                val diskAssets = File(directory, "assets").listFiles().orEmpty().associateBy { it.nameWithoutExtension }.toMutableMap()
                val peopleCache = HashMap<List<Any?>, ExportPerson>()
                TimelineHtmlFonts.write(context, directory).forEach { assets[it.path] = it }
                val privacy = ExportPrivacy(options, draft.owner, draft.roomId, assetUrls, context.getString(CommonStrings.export_room),
                        room.roomSummary()?.let { it.aliases.toSet() + listOfNotNull(it.canonicalAlias) }.orEmpty())
                onStage(ExportPreparationStage.LOADING_EVENTS)
                progress(0, draft.eventIds.size)
                val source = ArrayList<TimelineEvent>(draft.eventIds.size)
                var loaded = 0
                for (ids in draft.eventIds.chunked(64)) {
                    coroutineContext.ensureActive()
                    val batch = room.timelineService().getTimelineEventsFlow(ids).first().associateBy { it.eventId }
                    ids.forEach { id ->
                        val event = batch[id] ?: room.timelineService().getTimelineEvent(id)
                        if (event == null) issues.add(context.getString(CommonStrings.export_missing_event, privacy.eventId(id)))
                        else source.add(event)
                    }
                    loaded += ids.size
                    progress(loaded, draft.eventIds.size)
                }
                Timber.d("ExportTrace events loaded count=%d elapsedMs=%d", source.size, SystemClock.elapsedRealtime() - startedAt)
                val sourceById = source.associateBy { it.eventId }
                val included = TimelineSelection.inTimelineOrder(source).filter { include(it, options, draft.threadId != null) }
                val includedIds = included.map { it.eventId }.toSet()
                val events = ArrayList<ExportEvent>()
                val mentionedPeople = linkedMapOf<String, ExportPerson>()

                suspend fun asset(url: String?, mime: String?, decrypt: ElementToDecrypt? = null, fallback: File? = null): String? {
                    if (url.isNullOrBlank()) return null
                    assetUrls[url]?.let { return it }
                    if (url in failedAssets) return null
                    if (!url.startsWith("mxc://")) return null
                    return try {
                        val prefix = IdentityAliases.digest(url).take(32)
                        val cached = diskAssets[prefix]?.takeIf { it.isFile && it.length() > 0 }
                        val file = cached ?: fallback?.takeIf { it.isFile } ?: session.fileService().downloadFile("asset", mime, url, decrypt)
                        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                                ?: file.extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
                        val path = "assets/" + IdentityAliases.digest(url).take(32) + "." + extension
                        val target = File(directory, path)
                        target.parentFile?.mkdirs()
                        if (file.absolutePath != target.absolutePath) file.copyTo(target, overwrite = true)
                        val animated = AnimatedImageFormat.detect(target) != null
                        assets[path] = ExportAsset(path, mime ?: "application/octet-stream", animated)
                        assetUrls[url] = path
                        diskAssets[prefix] = target
                        path
                    } catch (failure: CancellationException) { throw failure } catch (failure: Exception) {
                        failedAssets.add(url)
                        Timber.w(failure, "ExportTrace asset unavailable mime=%s attempted=%d", mime, assets.size + failedAssets.size)
                        issues.add(context.getString(CommonStrings.export_missing_asset, privacy.eventId(url)))
                        null
                    }
                }

                fun visibleAvatar(item: MatrixItem): String? = when (options.avatars) {
                    "SHOW" -> item.avatarUrl
                    "HIDE" -> null
                    else -> item.withAvatarHiddenIn(draft.roomId, session, preferences).avatarUrl
                }

                suspend fun person(id: String, name: String?, avatar: String?): ExportPerson {
                    val cacheKey = listOf(id, name, avatar)
                    peopleCache[cacheKey]?.let { return it }
                    val anonymous = options.identity.includes(id, draft.roomId)
                    // The member event is what they publish; the name and avatar handed in are only a fallback.
                    val published = room.stateService().getStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(id))
                            ?.getClearContent().toModel<RoomMemberContent>()
                    val fallbackIsReal = showsRealProfile(id, draft.roomId)
                    val resolvedName = if (anonymous) privacy.alias(id) else published?.displayName ?: name?.takeIf { fallbackIsReal } ?: id
                    val realAvatar = published?.avatarUrl ?: avatar?.takeIf { fallbackIsReal }
                    val item = MatrixItem.UserItem(id, resolvedName,
                            colorPreference = room.membershipService().getRoomMember(id)?.colorPreference)
                    val colors = buildMap<String, Int> {
                        for (light in listOf(true, false)) {
                            val axis = if (light) ":light" else ":dark"
                            put("CURRENT" + axis, nameColors.exportNameColor(item, light, anonymous, "CURRENT", options.showProfileColors))
                            for (palette in ColorPalette.values()) {
                                put(palette.name + axis, nameColors.exportNameColor(item, light, anonymous, palette.name, options.showProfileColors))
                            }
                        }
                    }
                    return ExportPerson(privacy.userId(id), resolvedName, colors[options.peoplePalette + if (ExportTheme.light(options.theme)) ":light" else ":dark"] ?: colors.getValue("CURRENT" + if (ExportTheme.light(options.theme)) ":light" else ":dark"),
                            if (anonymous) null else asset(visibleAvatar(item.copy(avatarUrl = realAvatar)), "image/png"),
                            sharedAvatar = anonymous && options.identity.sharedAvatar, paletteColors = colors).also { peopleCache[cacheKey] = it }
                }

                suspend fun html(content: Map<String, Any?>): String {
                    val raw = content["formatted_body"] as? String ?: return ""
                    val doc = Jsoup.parseBodyFragment(raw)
                    doc.select("mx-reply").remove()
                    val exportedLinks = Jsoup.parseBodyFragment(privacy.html(doc.body().html())).select("a[href]").map { it.attr("href") }.toSet()
                    for (anchor in doc.select("a[href]")) {
                        if (!PermalinkParser.isSupported(context.resources.getStringArray(im.vector.app.config.R.array.permalink_supported_hosts), anchor.attr("href"))) continue
                        when (val link = PermalinkParser.parse(anchor.attr("href"))) {
                            is PermalinkData.UserLink -> {
                                val member = room.membershipService().getRoomMember(link.userId)
                                val target = person(link.userId, member?.displayName ?: anchor.text(), member?.avatarUrl)
                                mentionedPeople[target.id] = target
                            }
                            is PermalinkData.RoomLink -> if (link.eventId == null) {
                                if (anchor.attr("href") !in exportedLinks) continue
                                val target = session.roomService().getRoomSummary(link.roomIdOrAlias)
                                val name = target?.displayName ?: anchor.text()
                                if (options.hideRoomIdentity && (target?.roomId == draft.roomId || link.roomIdOrAlias == draft.roomId)) continue
                                val item = if (link.isRoomAlias) MatrixItem.RoomAliasItem(link.roomIdOrAlias, name, target?.avatarUrl)
                                        else MatrixItem.RoomItem(link.roomIdOrAlias, name, target?.avatarUrl)
                                val colors = buildMap<String, Int> {
                                    for (light in listOf(true, false)) for (palette in ColorPalette.values()) {
                                        put(palette.name + if (light) ":light" else ":dark", ContextCompat.getColor(context, palette.colorFor(item.id, light)))
                                    }
                                }
                                val color = nameColors.defaultColor(item, ExportTheme.light(options.theme))
                                mentionedPeople[item.id] = ExportPerson(item.id, name, color,
                                        asset(visibleAvatar(item), "image/png"), paletteColors = colors)
                            }
                            else -> Unit
                        }
                    }
                    for (image in doc.select("img")) {
                        val path = asset(image.attr("src"), "image/png")
                        if (path == null) image.replaceWith(TextNode(image.attr("alt"))) else image.attr("src", path)
                    }
                    return privacy.html(doc.body().html())
                }

                onStage(ExportPreparationStage.PROCESSING_EVENTS)
                for ((index, original) in included.withIndex()) {
                    coroutineContext.ensureActive()
                    progress(index, included.size)
                    var event = original
                    val wantsReveal = when (options.revealRedacted) {
                        "ALL" -> true
                        "SELF" -> event.root.senderId == draft.owner
                        "OTHERS" -> event.root.senderId != draft.owner
                        "NONE" -> false
                        else -> restorer.isShowingRestoredContent(event)
                    }
                    if (original.root.isRedacted() && wantsReveal && !original.root.isStateEvent()) {
                        val preserved = session.redactedContentService().getPreservedContent(event.eventId)
                        val root = if (preserved != null) original.root.copyAll(
                                type = preserved.clearType ?: original.root.type, content = preserved.content,
                                unsignedData = null, mxDecryptionResult = null,
                        ) else {
                            val supported = session.homeServerCapabilitiesService().getHomeServerCapabilities().canViewUnredactedContent
                            val privileged = room.stateService().getRoomPowerLevels().isUserAbleToRedact(draft.owner) || adminStatus.cachedStatus().mayBeAdmin
                            if (supported && privileged) try {
                                session.eventService().getUnredactedEvent(draft.roomId, event.eventId)
                            } catch (failure: CancellationException) { throw failure } catch (failure: Exception) { null } else null
                        }
                        if (root != null && root.getClearContent() != null) {
                            val relations = session.redactedContentService().getPreservedRelationsOf(draft.roomId, original.eventId)
                                    .filterNot { room.timelineService().getTimelineEvent(it.eventId)?.root?.isRedacted() == true }
                            event = restorer.restorePreservedEvent(original,
                                    RedactedContentRepository.PreservedBody(root.getClearContent()!!, root.getClearType(), relations))
                        } else issues.add(context.getString(CommonStrings.export_redacted_unavailable, privacy.eventId(event.eventId)))
                    }
                    val unrevealed = event.root.isRedacted()
                    val content = if (unrevealed) emptyMap() else event.getLastEditNewContent() ?: event.root.getClearContent().orEmpty()
                    val sender = event.root.senderId ?: event.senderInfo.userId
                    val information = informationFactory.create(TimelineItemFactoryParams(
                            event, lastEdit = event.annotations?.editSummary?.latestEdit, preserveSenderInfo = true, isRevealedRedaction = original.root.isRedacted() && !unrevealed,
                            partialState = TimelineEventController.PartialState(roomSummary = room.roomSummary())))
                    val author = person(sender, information.memberName?.toString() ?: event.senderInfo.displayName,
                            information.avatarUrl ?: event.senderInfo.avatarUrl)
                    val relation = (content["m.relates_to"] as? Map<*, *>)
                    val replyId = (relation?.get("m.in_reply_to") as? Map<*, *>)?.get("event_id") as? String
                    val missingReply = replyId != null && replyId !in includedIds
                    val type = event.root.getClearType()
                    val parsedMessage = content.toModel<MessageContent>()
                    val message = parsedMessage as? MessageWithAttachmentContent
                    val attachment = if (options.omitAttachments || unrevealed) null else {
                        val url = message?.getFileUrl() ?: content["url"] as? String
                        val mime = message?.mimeType ?: (content["info"] as? Map<*, *>)?.get("mimetype") as? String
                        asset(url, mime, message?.encryptedFileInfo?.toElementToDecrypt(), preservedAttachments.fileFor(draft.roomId, original.eventId))
                    }
                    val gallery = (parsedMessage as? MessageGalleryContent)?.galleryItems().orEmpty()
                    if (!options.omitAttachments && !unrevealed) {
                        (listOfNotNull(message) + gallery).forEach { item ->
                            val video = item as? MessageVideoContent
                            if (video != null) asset(video.videoInfo?.getThumbnailUrl(), video.videoInfo?.thumbnailInfo?.mimeType,
                                    video.videoInfo?.thumbnailFile?.toElementToDecrypt())
                        }
                    }
                    val extraAttachments = gallery.map { item ->
                        ExportAttachment(if (options.omitAttachments || unrevealed) null else asset(item.getFileUrl(), item.mimeType, item.encryptedFileInfo?.toElementToDecrypt()),
                                item.mimeType, item.getFileName(), false)
                    }
                    val notice = type != EventType.MESSAGE && type != EventType.STICKER && type !in EventType.POLL_START.values
                    val hideMediaReactions = when (options.media) {
                        "SHOW" -> false
                        "HIDE" -> true
                        else -> information.hideMediaReactions
                    }
                    var noticeSpans = emptyList<ExportNoticeSpan>()
                    val body = when {
                        unrevealed -> context.getString(CommonStrings.export_redacted_message)
                        notice -> {
                            val userNames = if (type == EventType.STATE_ROOM_POWER_LEVELS) {
                                val ids = (content["users"] as? Map<*, *>)?.keys.orEmpty() +
                                        (event.root.resolvedPrevContent()?.get("users") as? Map<*, *>)?.keys.orEmpty()
                                ids.filterIsInstance<String>().distinct().associate { id ->
                                    privacy.userId(id) to person(id, room.membershipService().getRoomMember(id)?.displayName, null).name
                                }
                            } else emptyMap()
                            val safeRoot = event.root.copyAll(type = type, mxDecryptionResult = null, senderId = privacy.userId(sender),
                                    stateKey = event.root.stateKey?.let(privacy::userId), content = privacy.eventContent(event.root, content).filterValues { it != null }.mapValues { it.value!! },
                                    prevContent = privacy.eventContent(event.root, event.root.resolvedPrevContent().orEmpty()).filterValues { it != null }.mapValues { it.value!! })
                                    .also { it.decryptedPrevContent = it.prevContent }
                            val formatted = noticeFormatter.format(safeRoot, author.name, room.roomSummary()?.isDirect == true, userNames) ?: type
                            val exported = exportNoticeText(formatted, context.getString(CommonStrings.a11y_image)) { url ->
                                if (hideMediaReactions) null else asset(url, "image/png")
                            }
                            noticeSpans = exported.second
                            exported.first
                        }
                        parsedMessage is MessageGalleryContent -> if (parsedMessage.galleryCaption() != null) privacy.body(content) else ""
                        message != null -> if (message.getCaption(isReply = replyId != null) != null) privacy.body(content) else ""
                        else -> privacy.body(content)
                    }
                    val history = mutableListOf<ExportRevision>()
                    var incomplete = false
                    if (options.editHistory && !unrevealed && (original.annotations?.editSummary != null || original.root.isRedacted())) {
                        try {
                            val fetched = room.relationService().fetchEditHistory(original.eventId).toMutableList()
                            val preserved = session.redactedContentService().getPreservedRelationsOf(draft.roomId, original.eventId)
                            if (wantsReveal) preserved.filter { (it.content["m.relates_to"] as? Map<*, *>)?.get("rel_type") == RelationType.REPLACE }
                                    .filterNot { room.timelineService().getTimelineEvent(it.eventId)?.root?.isRedacted() == true }.forEach { entry ->
                                if (fetched.none { it.eventId == entry.eventId }) {
                                    fetched.add(Event(eventId = entry.eventId, roomId = draft.roomId, senderId = entry.senderId,
                                            type = entry.clearType, originServerTs = entry.originServerTs, content = entry.content))
                                }
                            }
                            for (revision in fetched.sortedBy { it.originServerTs ?: 0 }) {
                                if (revision.isRedacted()) continue
                                if (revision.isEncrypted() && revision.mxDecryptionResult == null) {
                                    try {
                                        val result = session.cryptoService().decryptEvent(revision, "export-" + draft.id)
                                        revision.mxDecryptionResult = OlmDecryptionResult(
                                                payload = result.clearEvent, senderKey = result.senderCurve25519Key,
                                                keysClaimed = result.claimedEd25519Key?.let { mapOf("ed25519" to it) },
                                                forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain,
                                                verificationState = result.messageVerificationState)
                                    } catch (failure: CancellationException) { throw failure } catch (failure: Exception) { incomplete = true; continue }
                                }
                                val clear = revision.getClearContent()
                                if (clear == null) { incomplete = true; continue }
                                @Suppress("UNCHECKED_CAST")
                                val editedRaw = clear["m.new_content"] as? Map<String, Any?> ?: clear
                                val edited = if (replyId != null) editedRaw + ("body" to ExportPrivacy.withoutReplyFallback(editedRaw["body"] as? String ?: "")) else editedRaw
                                val formatted = html(edited)
                                history.add(ExportRevision(revision.originServerTs ?: 0,
                                        privacy.body(edited), formatted,
                                        privacy.event(revision, edited, includedIds)))
                            }
                        } catch (failure: CancellationException) { throw failure } catch (failure: Exception) { incomplete = true }
                        if (incomplete) issues.add(context.getString(CommonStrings.export_missing_history, privacy.eventId(event.eventId)))
                    }
                    val reactions = if (!options.reactionSummary) emptyList() else event.annotations?.reactionsSummary.orEmpty().map { reaction ->
                        val people = reaction.sourceEvents.mapNotNull { (sourceById[it] ?: room.timelineService().getTimelineEvent(it))?.senderInfo }
                                .distinctBy { it.userId }.map { person(it.userId, it.displayName, it.avatarUrl) }
                        val imageUrls = Regex("mxc://[^\\s/<>\"'`]+/[A-Za-z0-9_-]+").findAll(reaction.key).map { it.value }.toList()
                        val images = if (hideMediaReactions) emptyMap() else imageUrls.mapNotNull { url -> asset(url, "image/png")?.let { url to it } }.toMap()
                        ExportReaction(reaction.key, reaction.count, people, images, reaction.addedByMe)
                    }
                    val receipts = if (!options.receipts || draft.threadId != null &&
                            !session.homeServerCapabilitiesService().getHomeServerCapabilities().canUseThreadReadReceiptsAndNotifications) emptyList() else {
                        original.readReceipts.filter { it.threadId == draft.threadId || draft.threadId == null && it.threadId == "main" }
                                .map { receipt -> ExportReceipt(person(receipt.roomMember.userId, receipt.roomMember.displayName,
                                        receipt.roomMember.avatarUrl), receipt.originServerTs) }
                    }
                    val info = content["info"] as? Map<*, *>
                    val mediaHidden = when (options.media) {
                        "SHOW" -> false
                        "HIDE" -> true
                        else -> shouldHideMediaPreview(original, session, preferences, revealManager)
                    }
                    val audio = parsedMessage as? MessageAudioContent
                    val audioDetails = if (audio != null && attachment != null && !mediaHidden) {
                        AudioDetails.load(context, Uri.fromFile(File(directory, attachment)), forTimeline = true)
                    } else null
                    val audioBackdrop = audioDetails?.backdrop?.let { image ->
                        val path = "assets/audio-backdrop-${privacy.eventId(event.eventId)}.png"
                        File(directory, path).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        assets[path] = ExportAsset(path, "image/png")
                        path
                    }
                    val forwardOrigin = content.getForwardedInfo()
                    val liveLocation = event.annotations?.liveLocationShareAggregatedSummary
                    val locationData = when (parsedMessage) {
                        is MessageLocationContent -> parsedMessage.toLocationData()
                        is MessageBeaconInfoContent -> liveLocation?.lastLocationDataContent?.getBestLocationInfo()?.geoUri?.toLocationData()
                        else -> null
                    }
                    val mapImage = if (Build.VERSION.SDK_INT >= 21 && locationData != null && !mediaHidden && !options.omitAttachments && !unrevealed) {
                        try {
                            val sizes = renderingScope.mediaSizes()
                            val size = sizes.getLocationMapSize()
                            val url = renderingScope.maps().buildStaticMapUrl(locationData, INITIAL_MAP_ZOOM_IN_TIMELINE, size, size, sizes.getLocationPinTipOffset())
                            val requests = GlideApp.with(context)
                            val download = requests.downloadOnly().load(url).submit(size, size)
                            try {
                                val file = download.get(30, TimeUnit.SECONDS)
                                val path = "assets/map-${privacy.eventId(event.eventId)}.png"
                                file.copyTo(File(directory, path).also { it.parentFile?.mkdirs() }, overwrite = true)
                                assets[path] = ExportAsset(path, "image/png")
                                path
                            } finally { requests.clear(download) }
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            issues.add(context.getString(CommonStrings.export_missing_asset, privacy.eventId(event.eventId)))
                            null
                        }
                    } else null
                    val formatted = if (unrevealed || notice) "" else html(content)
                    events.add(ExportEvent(
                            id = privacy.eventId(event.eventId), type = type, timestamp = event.root.originServerTs ?: 0L,
                            person = author, body = body, html = formatted,
                            raw = privacy.event(event.root, if (unrevealed) emptyMap() else content, includedIds),
                            notice = notice, noticeSpans = noticeSpans, sentByMe = sender == draft.owner, group = mergeGroup(event),
                            extraAttachments = extraAttachments.map { it.copy(hidden = mediaHidden) },
                            replyTo = replyId?.takeIf { it in includedIds }?.let(privacy::eventId), replyMissing = missingReply,
                            attachment = attachment, attachmentMime = message?.mimeType ?: info?.get("mimetype") as? String ?: when (content["msgtype"]) {
                                "m.image" -> "image/*"
                                "m.video" -> "video/*"
                                "m.audio" -> "audio/*"
                                else -> if (type == EventType.STICKER) "image/*" else null
                            },
                            attachmentName = message?.getFileName(), mediaHidden = mediaHidden,
                            audioTitle = audio?.audioInfo?.metadata?.title ?: audioDetails?.title,
                            audioArtist = audio?.audioInfo?.metadata?.artist ?: audioDetails?.artist,
                            audioAlbum = audio?.audioInfo?.metadata?.album ?: audioDetails?.album, audioBackdrop = audioBackdrop,
                            reactions = reactions, hideMediaReactions = hideMediaReactions, receipts = receipts, history = history,
                            threadCount = if (draft.threadId == null && options.threadSummary) original.root.threadDetails?.numberOfThreads ?: 0 else 0,
                            linkPreviews = if (preferences.showUrlPreviews() && !unrevealed) session.mediaService().extractBundledUrlPreviews(event).orEmpty().mapNotNull { preview ->
                                preview.previewUrlData?.let { data -> ExportLinkPreview(data.url, data.title.orEmpty(), data.description.orEmpty(),
                                        if (mediaHidden) null else asset(data.encryptedImage?.url ?: data.mxcUrl, data.imageMimeType, data.encryptedImage?.toElementToDecrypt()), data.imageWidth, data.imageHeight) }
                            } else emptyList(),
                            poll = poll(event, options.visible("POLL_RESULTS", preferences.revealUndisclosedPollResults())), historyIncomplete = incomplete, edited = information.hasBeenEdited,
                            e2eDecoration = information.e2eDecoration.name, isPgp = information.isPgp, isRevealedRedaction = information.isRevealedRedaction,
                            mapImage = mapImage,
                            mapIsSelf = (parsedMessage as? MessageLocationContent)?.isSelfLocation() == true || liveLocation != null,
                            liveLocationActive = liveLocation?.isActive,
                            liveLocationEnd = liveLocation?.endOfLiveTimestampMillis,
                            liveLocationContent = liveLocation?.lastLocationDataContent?.let { last ->
                                @Suppress("UNCHECKED_CAST")
                                val raw = MatrixJsonParser.getMoshi()
                                        .adapter(MessageBeaconLocationDataContent::class.java).toJsonValue(last) as Map<String, Any?>
                                privacy.content(raw)
                            },
                            forwardedSenderId = information.forwardedInfo?.senderId?.let(privacy::userId),
                            forwardedSenderName = information.forwardedInfo?.let { forwarded ->
                                val id = forwarded.senderId
                                when {
                                    id == null -> forwarded.senderDisplayName
                                    options.identity.includes(id, draft.roomId) -> privacy.alias(id)
                                    else -> realForwardedSenderName(session, id, forwardOrigin?.roomId, draft.roomId) ?: forwarded.senderDisplayName
                                            ?.takeIf { showsRealProfile(id, forwardOrigin?.roomId) && showsRealProfile(id, draft.roomId) } ?: id
                                }
                            },
                            forwardedRoom = information.forwardedInfo?.roomName?.let {
                                if (options.hideRoomIdentity) context.getString(CommonStrings.export_room) else realForwardedRoomName(session, forwardOrigin)
                            },
                            forwardedDate = information.forwardedInfo?.formattedDate,
                            pollClosed = event.annotations?.pollResponseSummary?.closedTime != null,
                            pollVotes = event.annotations?.pollResponseSummary?.aggregatedContent?.votesSummary.orEmpty().mapValues { it.value.total },
                            pollMyVote = event.annotations?.pollResponseSummary?.aggregatedContent?.myVote,
                            pollResultsVisible = options.visible("POLL_RESULTS", preferences.revealUndisclosedPollResults()) ||
                                    (parsedMessage as? MessagePollContent)?.getBestPollCreationInfo()?.isUndisclosed() != true || event.annotations?.pollResponseSummary?.closedTime != null,
                            pollTotalVotes = event.annotations?.pollResponseSummary?.aggregatedContent?.totalVotes ?: 0,
                    ))
                }
                val summary = room.roomSummary()
                val topicPlain = if (options.hideRoomIdentity) "" else summary?.topic.orEmpty()
                val topicFormatted = if (options.hideRoomIdentity) null else html(mapOf("formatted_body" to summary?.topicFormatted)).takeIf { it.isNotBlank() }
                // Named from room state, as members see it: the summary's name and avatar carry this account's personal
                // room name and avatar, and member-built names its nicknames for people.
                val dmPerson = summary?.directUserId?.takeIf { summary.isDirect }?.let { person(it, null, null) }
                val roomName = when {
                    options.hideRoomIdentity -> context.getString(CommonStrings.export_room)
                    !summary?.name.isNullOrBlank() -> summary!!.name
                    dmPerson != null -> dmPerson.name
                    !summary?.canonicalAlias.isNullOrBlank() -> summary!!.canonicalAlias!!
                    else -> memberBuiltName(summary?.otherMemberIds.orEmpty().map { person(it, null, null).name }, summary?.isDirect == true)
                }
                val stateAvatar = room.stateService().getStateEvent(EventType.STATE_ROOM_AVATAR, QueryStringValue.IsEmpty)
                        ?.getClearContent()?.get("url") as? String
                val roomColors = buildMap<String, Int> {
                    val item = MatrixItem.RoomItem(draft.roomId, roomName)
                    for (light in listOf(true, false)) {
                        val axis = if (light) ":light" else ":dark"
                        put("CURRENT" + axis, nameColors.defaultColor(item, light))
                        ColorPalette.values().filter { it != ColorPalette.NONE }.forEach { palette ->
                            put(palette.name + axis, ContextCompat.getColor(context, palette.colorFor(draft.roomId, light)))
                        }
                    }
                }
                val roomPerson = ExportPerson(EXPORT_ROOM_ID, roomName,
                        roomColors[options.roomPalette + if (ExportTheme.light(options.theme)) ":light" else ":dark"] ?: roomColors.getValue("CURRENT" + if (ExportTheme.light(options.theme)) ":light" else ":dark"),
                        when {
                            options.hideRoomIdentity -> null
                            stateAvatar.isNullOrBlank() && dmPerson != null -> dmPerson.avatar
                            else -> asset(visibleAvatar(MatrixItem.RoomItem(draft.roomId, roomName, stateAvatar)), "image/png")
                        },
                        paletteColors = roomColors)
                val creatorId = room.stateService().getStateEvent(EventType.STATE_ROOM_CREATE, QueryStringValue.Equals(""))?.senderId
                val creatorName = creatorId?.let { person(it, room.membershipService().getRoomMember(it)?.displayName, null).name }.orEmpty()
                val exporterName = person(draft.owner, room.membershipService().getRoomMember(draft.owner)?.displayName, null).name
                onStage(ExportPreparationStage.COLLECTING_EMOJI)
                val emojiImages = emojiAssets.collect(events.flatMap { event ->
                    listOf(event.body, Jsoup.parseBodyFragment(event.html).text(), event.person.name, event.poll.orEmpty()) +
                            event.history.flatMap { listOf(it.body, Jsoup.parseBodyFragment(it.html).text()) } + event.reactions.map { it.key } + event.receipts.map { it.person.name }
                } + listOf(roomName, creatorName, exporterName, topicPlain, Jsoup.parseBodyFragment(topicFormatted.orEmpty()).text()), directory, assets)
                progress(included.size, included.size)
                Timber.d("ExportTrace prepare complete events=%d assets=%d mentions=%d issues=%d elapsedMs=%d", events.size, assets.size, mentionedPeople.size, issues.size, SystemClock.elapsedRealtime() - startedAt)
                val noticePeople = events.flatMap { event -> event.noticeSpans.mapNotNull { it.person } + listOfNotNull(event.forwardedSenderId) }.toSet()
                val presentedPeople = (mentionedPeople.values + peopleCache.values.filter { it.id in noticePeople }).distinctBy { it.id }
                TimelineExportSnapshot(draft.owner, draft.id, roomName,
                        topicPlain,
                        creatorName, exporterName,
                        System.currentTimeMillis(), draft.eventIds.size, events, assets.values.toList(), options, roomPerson = roomPerson, isDirect = summary?.isDirect == true, isThread = draft.threadId != null, issues = issues.distinct(),
                        redactionRecoveryAvailable = session.homeServerCapabilitiesService().getHomeServerCapabilities().canViewUnredactedContent &&
                                (room.stateService().getRoomPowerLevels().isUserAbleToRedact(draft.owner) || adminStatus.cachedStatus().mayBeAdmin) ||
                                source.filter { it.root.isRedacted() }.any { session.redactedContentService().getPreservedContent(it.eventId) != null },
                        draftRevision = draft.updatedAt, emojiImages = emojiImages, topicFormatted = topicFormatted, mentionedPeople = presentedPeople)
            }

    private fun include(event: TimelineEvent, options: TimelineExportOptions, isThread: Boolean): Boolean {
        if (!isThread && (event.root.getRelationContent()?.type == RelationType.THREAD || event.root.threadDetails?.isThread == true) &&
                !options.visible("HIDDEN_THREAD_EVENTS", false)) return false
        if (event.root.isRedacted() && !options.visible("REDACTED")) return false
        return when (event.root.getClearType()) {
            EventType.STATE_ROOM_MEMBER -> options.visible(if (event.root.getClearContent()?.get("membership") == event.root.resolvedPrevContent()?.get("membership")) "PROFILE_CHANGES" else "MEMBERSHIP")
            EventType.STATE_ROOM_THIRD_PARTY_INVITE -> options.visible("MEMBERSHIP")
            EventType.STATE_ROOM_SERVER_ACL -> options.visible("ACL", false)
            EventType.REACTION -> options.visible("REACTIONS", false)
            in TimelineDisplayableEvents.ROOM_SETTINGS_TYPES -> options.visible("ROOM_SETTINGS")
            else -> if (event.root.isDebugOnlyType() || event.root.getRelationContent()?.type == RelationType.REPLACE ||
                    event.root.getClearType() !in TimelineDisplayableEvents.DISPLAYABLE_TYPES) options.visible("HIDDEN_EVENTS", false) else true
        }
    }

    private fun mergeGroup(event: TimelineEvent): String? = when {
        event.root.isRedacted() -> "redacted"
        event.root.getClearType() in setOf(EventType.STATE_ROOM_MEMBER, EventType.STATE_ROOM_SERVER_ACL) -> event.root.getClearType()
        event.root.isDebugOnlyType() -> "hidden"
        else -> null
    }

    private fun poll(event: TimelineEvent, revealResults: Boolean): String? {
        if (event.root.getClearType() !in EventType.POLL_START.values) return null
        val content = (event.getLastEditNewContent() ?: event.root.getClearContent()).toModel<MessagePollContent>()?.getBestPollCreationInfo() ?: return null
        val summary = event.annotations?.pollResponseSummary
        val showResults = revealResults || !content.isUndisclosed() || summary?.closedTime != null
        return buildString {
            append(content.question?.getBestQuestion().orEmpty())
            content.answers.orEmpty().forEach { answer ->
                append("\n").append(answer.getBestAnswer())
                if (showResults) append(" · ").append(summary?.aggregatedContent?.votesSummary?.get(answer.getBestId())?.total ?: 0)
            }
        }
    }
}

class ExportPrivacy(
        private val options: TimelineExportOptions,
        private val self: String,
        private val room: String,
        private val assets: Map<String, String> = emptyMap(),
        private val roomLabel: String = "",
        private val roomAliases: Set<String> = emptySet(),
) {
    private val aliasOwners = HashMap<String, MutableList<String>>()

    /**
     * The name an anonymized [id] is shown under in this export. People sharing a generated name are numbered in
     * the order they first appear, as the app numbers them within a room, and the stand-in id follows the name.
     */
    fun alias(id: String): String = synchronized(aliasOwners) {
        val name = options.identity.name(id, self)
        if (options.identity.naming != "WORDS" || id == self) return name
        val owners = aliasOwners.getOrPut(name) { mutableListOf() }
        val index = owners.indexOf(id).takeIf { it >= 0 } ?: owners.size.also { owners.add(id) }
        if (index == 0) name else "$name ${index + 1}"
    }

    fun userId(id: String): String = if (!options.identity.includes(id, room)) id
    else IdentityAliases.id(id, alias(id).takeIf { options.identity.naming == "WORDS" || id == self })
    fun eventId(id: String): String = "e" + IdentityAliases.digest(id).take(32)

    fun content(value: Map<String, Any?>, subject: String? = null, omitMediaUrls: Boolean = false): Map<String, Any?> {
        val anonymous = subject != null && options.identity.includes(subject, room)
        return value.mapNotNull { (key, item) ->
            val cleaned: Any? = when {
                omitMediaUrls && key in attachmentFields -> null
                anonymous && key == "displayname" -> alias(subject!!)
                anonymous && key in setOf("avatar_url", "avatar_file", "file") -> null
                (key == "file" || key.endsWith("_file") || key == "matrix:image:encrypted") && item is Map<*, *> -> null
                key == "body" && item is String -> body(value)
                key == "formatted_body" && item is String -> html(item)
                key == "key" && item is String -> item
                item is String && options.hideRoomIdentity && (item == room || item in roomAliases) -> EXPORT_ROOM_ID
                item is String && item.startsWith("mxc://") -> assets[item]
                item is String && item.startsWith("@") && item.contains(':') -> userId(item)
                item is String && key in setOf("event_id", "redacts") -> matrixEventId(item)
                item is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    content(item as Map<String, Any?>, subject, omitMediaUrls)
                }
                item is List<*> -> item.map { entry ->
                    if (entry is String && entry.startsWith("@")) userId(entry)
                    else if (entry is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        content(entry as Map<String, Any?>, subject, omitMediaUrls)
                    } else entry
                }
                else -> item
            }
            if (cleaned == null) null else userId(key) to cleaned
        }.toMap().toMutableMap().apply {
            if (omitMediaUrls) return@apply
            val encrypted = value["file"] as? Map<*, *>
            val path = (encrypted?.get("url") as? String)?.let { assets[it] }
            if (path != null) put("url", path)
            val thumbnail = value["thumbnail_file"] as? Map<*, *>
            (thumbnail?.get("url") as? String)?.let { assets[it] }?.let { put("thumbnail_url", it) }
        }
    }

    fun body(value: Map<String, Any?>): String {
        val relation = value["m.relates_to"] as? Map<*, *>
        val raw = value["body"] as? String ?: ""
        var text = if (relation?.containsKey("m.in_reply_to") == true) withoutReplyFallback(raw) else raw
        val mentions = (value["m.mentions"] as? Map<*, *>)?.get("user_ids") as? List<*>
        mentions.orEmpty().filterIsInstance<String>().forEach { id ->
            if (options.identity.includes(id, room)) text = text.replace(id, alias(id))
        }
        // The body spells a mention as its link text (usually a display name); only the formatted body says whose it is.
        (value["formatted_body"] as? String)?.let { Jsoup.parseBodyFragment(it).select("a[href]") }.orEmpty().forEach { anchor ->
            val label = anchor.text().trim()
            when (val link = PermalinkParser.parse(anchor.attr("href"))) {
                is PermalinkData.UserLink -> if (options.identity.includes(link.userId, room)) {
                    text = text.replaceWord(label, alias(link.userId)).replace(link.userId, alias(link.userId))
                }
                is PermalinkData.RoomLink -> if (options.hideRoomIdentity && (link.roomIdOrAlias == room || link.roomIdOrAlias in roomAliases)) {
                    text = text.replaceWord(label, roomLabel).replace(link.roomIdOrAlias, roomLabel)
                }
                else -> Unit
            }
        }
        return text
    }

    // Whole words only, so a one-letter name doesn't rewrite every word containing that letter.
    private fun String.replaceWord(word: String, replacement: String): String =
            if (word.isEmpty()) this else replace(Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])"), Regex.escapeReplacement(replacement))

    private fun matrixEventId(id: String): String = if (options.hideRoomIdentity || options.identity.ruleFor(room).enabled) {
        "$" + IdentityAliases.digest(id).take(43)
    } else {
        id
    }

    fun eventContent(event: Event, clear: Map<String, Any?>): Map<String, Any?> {
        val type = event.getClearType()
        val omitMediaUrls = options.omitAttachments && (type == EventType.MESSAGE || type == EventType.STICKER)
        val content = content(clear, event.stateKey, omitMediaUrls)
        if (!options.hideRoomIdentity) return content
        return when (type) {
            EventType.STATE_ROOM_NAME -> content + ("name" to roomLabel)
            EventType.STATE_ROOM_TOPIC -> content - setOf("topic", "formatted_topic")
            EventType.STATE_ROOM_AVATAR -> content - setOf("url", "file")
            EventType.STATE_ROOM_CANONICAL_ALIAS, EventType.STATE_ROOM_ALIASES -> content - setOf("alias", "aliases", "alt_aliases")
            else -> content
        }
    }

    fun event(event: Event, clear: Map<String, Any?>, included: Set<String>): Map<String, Any?> {
        val cleaned = eventContent(event, clear).toMutableMap()
        val relation = (clear["m.relates_to"] as? Map<*, *>)
        val target = (relation?.get("m.in_reply_to") as? Map<*, *>)?.get("event_id") as? String
        if (target != null && target !in included) {
            val replacement = (cleaned["m.relates_to"] as? Map<*, *>)?.filterKeys { it != "m.in_reply_to" }.orEmpty()
            cleaned["m.relates_to"] = replacement
        }
        return mapOf("event_id" to matrixEventId(event.eventId.orEmpty()),
                "room_id" to if (options.hideRoomIdentity) EXPORT_ROOM_ID else room,
                "type" to event.getClearType(), "origin_server_ts" to event.originServerTs,
                "sender" to event.senderId?.let(::userId), "state_key" to event.stateKey?.let(::userId),
                "content" to cleaned,
                "unsigned" to if (event.isRedacted()) mapOf("redacted_because" to mapOf("type" to EventType.REDACTION)) else null).filterValues { it != null }
    }

    fun html(source: String): String {
        val doc = Jsoup.parseBodyFragment(source)
        doc.select("mx-reply").remove()
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            val id = when (val link = PermalinkParser.parse(href)) {
                is PermalinkData.UserLink -> link.userId
                is PermalinkData.RoomLink -> link.roomIdOrAlias
                else -> ""
            }
            if (options.hideRoomIdentity && (id == room || id in roomAliases)) {
                anchor.text(roomLabel)
                anchor.attr("href", "#room")
                anchor.removeAttr("title")
            } else if (id.startsWith("@") && options.identity.includes(id, room)) {
                anchor.text(alias(id))
                anchor.attr("href", "https://matrix.to/#/" + userId(id))
                anchor.removeAttr("title")
            }
        }
        doc.select("img[src]").forEach { image ->
            val src = image.attr("src")
            val path = assets[src] ?: src.takeIf { it.matches(Regex("assets/[A-Za-z0-9._-]+")) }
            if (path == null) image.replaceWith(TextNode(image.attr("alt"))) else image.attr("src", path)
        }
        return Jsoup.clean(doc.body().html(), "", safelist(), Document.OutputSettings().prettyPrint(false))
    }

    companion object {
        private val attachmentFields = setOf("url", "thumbnail_url", "file", "thumbnail_file")

        // Everything the timeline renders from Matrix HTML (colors, code languages, block maths) must survive.
        fun safelist(): Safelist = Safelist.relaxed().addProtocols("a", "href", "matrix")
                .addTags("span", "font", "del", "s", "hr", "summary", "details")
                .addAttributes("span", "data-mx-spoiler", "data-mx-maths", "data-mx-color", "data-mx-bg-color")
                .addAttributes("font", "color", "data-mx-color", "data-mx-bg-color")
                .addAttributes("div", "data-mx-maths")
                .addAttributes("code", "class")
                .addAttributes("img", "width", "height", "data-mx-emoticon")
                .removeProtocols("img", "src", "http", "https")

        fun withoutReplyFallback(body: String): String {
            if (!body.startsWith("> <") && !body.startsWith("> * <")) return body
            val end = body.indexOf("\n\n")
            return if (end >= 0) body.substring(end + 2) else body
        }
    }
}
