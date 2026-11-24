/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import com.squareup.moshi.JsonClass
import im.vector.app.features.settings.AvatarShape
import org.jsoup.Jsoup
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.profile.AnonymizationConfig
import org.matrix.android.sdk.api.session.profile.IdentityAliases
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.livelocation.LiveLocationShareAggregatedSummary
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import java.util.TimeZone

/** The room id an export shows when it hides which room it came from. */
const val EXPORT_ROOM_ID = "!export:" + IdentityAliases.DOMAIN

enum class ExportPreparationStage { LOADING_EVENTS, PROCESSING_EVENTS, COLLECTING_EMOJI }

@JsonClass(generateAdapter = true)
data class TimelineExportOptions(
        val format: String = "PNG",
        val singleHtml: Boolean = false,
        val theme: String = "dark",
        val accent: String = "#66c7bf",
        val accentName: String = "default",
        val peoplePalette: String = "CURRENT",
        val roomPalette: String = "CURRENT",
        val peopleAvatarStyle: String = "CURRENT",
        val roomAvatarStyle: String = "CURRENT",
        val showProfileColors: Boolean = true,
        val codePalette: String = "ELEMENT",
        val fontScale: Float = 1f,
        val avatarShape: String = "CIRCLE",
        val background: String = "#17191c",
        val foreground: String = "#eeeeee",
        val incoming: String = "#30343b",
        val outgoing: String = "#224e4b",
        val bubbles: String = "none",
        val bubbleCorners: String = "default",
        val bubbleTail: Boolean = true,
        val bubbleTint: Boolean = true,
        val width: Int = 1080,
        val layoutWidth: Int = 360,
        val phoneWidth: Int = 360,
        val maxHeight: Int = 0,
        val timestamps: Boolean = true,
        val twelveHourTime: Boolean = false,
        val timeZoneId: String = TimeZone.getDefault().id,
        val dates: Boolean = false,
        val receipts: Boolean = true,
        val reactionSummary: Boolean = true,
        val threadSummary: Boolean = true,
        val roomHeader: Boolean = true,
        val hideRoomIdentity: Boolean = false,
        val visibility: Map<String, Boolean> = emptyMap(),
        val revealRedacted: String = "CURRENT",
        val media: String = "CURRENT",
        val avatars: String = "CURRENT",
        val omitAttachments: Boolean = false,
        val editHistory: Boolean = true,
        val expandedGroups: Set<String> = emptySet(),
        val expandAll: Boolean = false,
        val durationSeconds: Int = 10,
        val fps: Int = 15,
        val quality: Int = 80,
        val identity: AnonymizationConfig = AnonymizationConfig(),
) {
    fun visible(key: String, default: Boolean = true): Boolean = visibility[key] ?: default

    fun bubbleStyle(): String = when (bubbles) {
        "both", "start" -> bubbles
        else -> "none"
    }
}

@JsonClass(generateAdapter = true)
data class TimelineExportDraft(
        val id: String,
        val owner: String,
        val roomId: String,
        val threadId: String? = null,
        val eventIds: List<String> = emptyList(),
        val options: TimelineExportOptions = TimelineExportOptions(),
        val updatedAt: Long = System.currentTimeMillis(),
        val started: Boolean = false,
        val selectionActive: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class ExportAsset(
        val path: String,
        val mime: String,
        val animated: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class ExportPerson(
        val id: String,
        val name: String,
        val color: Int,
        val avatar: String? = null,
        val sharedAvatar: Boolean = false,
        val paletteColors: Map<String, Int> = emptyMap(),
)

@JsonClass(generateAdapter = true)
data class ExportReceipt(val person: ExportPerson, val timestamp: Long)

@JsonClass(generateAdapter = true)
data class ExportReaction(val key: String, val count: Int, val people: List<ExportPerson> = emptyList(), val images: Map<String, String> = emptyMap(), val addedByMe: Boolean = false)

@JsonClass(generateAdapter = true)
data class ExportLinkPreview(val url: String, val title: String, val description: String, val image: String? = null, val imageWidth: Int? = null, val imageHeight: Int? = null)

@JsonClass(generateAdapter = true)
data class ExportAttachment(val path: String?, val mime: String?, val name: String, val hidden: Boolean)

@JsonClass(generateAdapter = true)
data class ExportRevision(val timestamp: Long, val body: String, val html: String, val raw: Map<String, Any?>)

@JsonClass(generateAdapter = true)
data class ExportNoticeSpan(val start: Int, val end: Int, val image: String? = null, val person: String? = null)

@JsonClass(generateAdapter = true)
data class ExportEvent(
        val id: String,
        val type: String,
        val timestamp: Long,
        val person: ExportPerson,
        val body: String,
        val html: String,
        val raw: Map<String, Any?>,
        val notice: Boolean = false,
        val noticeSpans: List<ExportNoticeSpan> = emptyList(),
        val sentByMe: Boolean = false,
        val extraAttachments: List<ExportAttachment> = emptyList(),
        val linkPreviews: List<ExportLinkPreview> = emptyList(),
        val group: String? = null,
        val replyTo: String? = null,
        val replyMissing: Boolean = false,
        val attachment: String? = null,
        val attachmentMime: String? = null,
        val attachmentName: String? = null,
        val audioTitle: String? = null,
        val audioArtist: String? = null,
        val audioAlbum: String? = null,
        val audioBackdrop: String? = null,
        val mediaHidden: Boolean = false,
        val reactions: List<ExportReaction> = emptyList(),
        val hideMediaReactions: Boolean = false,
        val receipts: List<ExportReceipt> = emptyList(),
        val history: List<ExportRevision> = emptyList(),
        val threadCount: Int = 0,
        val poll: String? = null,
        val pollVotes: Map<String, Int> = emptyMap(),
        val pollClosed: Boolean = false,
        val pollMyVote: String? = null,
        val pollResultsVisible: Boolean = true,
        val pollTotalVotes: Int = 0,
        val historyIncomplete: Boolean = false,
        val edited: Boolean = false,
        val mapImage: String? = null,
        val mapIsSelf: Boolean = false,
        val liveLocationActive: Boolean? = null,
        val liveLocationEnd: Long? = null,
        val liveLocationContent: Map<String, Any?>? = null,
        val e2eDecoration: String = "NONE",
        val isPgp: Boolean = false,
        val isRevealedRedaction: Boolean = false,
        val forwardedSenderId: String? = null,
        val forwardedSenderName: String? = null,
        val forwardedRoom: String? = null,
        val forwardedDate: String? = null,
)

@JsonClass(generateAdapter = true)
data class TimelineExportSnapshot(
        val owner: String,
        val draftId: String,
        val roomName: String,
        val topic: String,
        val creator: String,
        val exportedBy: String,
        val exportedAt: Long,
        val selectedCount: Int,
        val events: List<ExportEvent>,
        val assets: List<ExportAsset>,
        val options: TimelineExportOptions,
        val roomPerson: ExportPerson? = null,
        val isDirect: Boolean = false,
        val isThread: Boolean = false,
        val issues: List<String> = emptyList(),
        val redactionRecoveryAvailable: Boolean = false,
        val draftRevision: Long = 0,
        val emojiImages: Map<String, String> = emptyMap(),
        val topicFormatted: String? = null,
        val mentionedPeople: List<ExportPerson> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ExportJobState(
        val snapshotId: String,
        val name: String,
        val selectedCount: Int = 0,
)

fun ExportPerson.withPalette(options: TimelineExportOptions, room: Boolean = false): ExportPerson {
    val palette = if (room) options.roomPalette else options.peoplePalette
    val key = palette + if (ExportTheme.light(options.theme)) ":light" else ":dark"
    return copy(color = paletteColors[key] ?: color)
}

fun TimelineExportSnapshot.forArchive(): TimelineExportSnapshot = copy(
        owner = "", draftId = "", draftRevision = 0, redactionRecoveryAvailable = false,
        options = options.copy(identity = options.identity.copy(
                rule = options.identity.rule.copy(users = emptySet()), rooms = emptyMap())),
)

fun TimelineExportSnapshot.withAppearance(options: TimelineExportOptions): TimelineExportSnapshot = copy(
        options = options,
        roomPerson = roomPerson?.withPalette(options, room = true),
        mentionedPeople = mentionedPeople.map { it.withPalette(options, room = !it.id.startsWith("@")) },
        events = events.map { event -> event.copy(person = event.person.withPalette(options),
                receipts = event.receipts.map { it.copy(person = it.person.withPalette(options)) },
                reactions = event.reactions.map { it.copy(people = it.people.map { person -> person.withPalette(options) }) }) },
)

fun TimelineExportOptions.withOutput(other: TimelineExportOptions): TimelineExportOptions = copy(
        format = other.format, singleHtml = other.singleHtml, width = other.width, maxHeight = other.maxHeight,
        durationSeconds = other.durationSeconds, fps = other.fps, quality = other.quality,
)

fun exportMentionIds(html: String): Set<String> = Jsoup.parseBodyFragment(html).select("a[href]").mapNotNull { anchor ->
    when (val link = PermalinkParser.parse(anchor.attr("href"))) {
        is PermalinkData.UserLink -> link.userId
        is PermalinkData.RoomLink -> link.roomIdOrAlias.takeIf { link.eventId == null }
        else -> null
    }
}.toSet()

fun TimelineExportSnapshot.hasAnimation(): Boolean {
    val shape = AvatarShape.values().firstOrNull { it.name == options.avatarShape }
    val movingAvatars = shape?.isAnimated == true && options.avatars != "HIDE" && (options.roomHeader || events.isNotEmpty())
    if (movingAvatars) return true
    if (assets.none { it.animated }) return false
    val paths = linkedSetOf<String>()
    val mentions = mentionedPeople.associateBy { it.id }
    events.forEach { event -> exportMentionIds(event.html).mapNotNullTo(paths) { mentions[it]?.avatar } }
    if (options.roomHeader) {
        if (options.avatars != "HIDE") roomPerson?.avatar?.let(paths::add)
        Jsoup.parseBodyFragment(topicFormatted.orEmpty()).select("img[src]").forEach { paths.add(it.attr("src")) }
        exportMentionIds(topicFormatted.orEmpty()).mapNotNullTo(paths) { mentions[it]?.avatar }
    }
    events.forEach { event ->
        if (options.avatars != "HIDE") {
            event.person.avatar?.let(paths::add)
            if (options.receipts) event.receipts.mapNotNullTo(paths) { it.person.avatar }
        }
        if (!event.mediaHidden) event.attachment?.let(paths::add)
        event.extraAttachments.filterNot { it.hidden }.mapNotNullTo(paths) { it.path }
        event.reactions.forEach { paths.addAll(it.images.values) }
        event.noticeSpans.mapNotNullTo(paths) { it.image }
        if (event.html.contains("<img")) Jsoup.parseBodyFragment(event.html).select("img[src]").forEach { paths.add(it.attr("src")) }
    }
    return assets.any { it.animated && it.path in paths }
}

internal fun TimelineExportOptions.forPreview(html: Boolean, animated: Boolean): TimelineExportOptions = copy(
        format = if (html) "HTML" else if (animated) "WEBP" else "PNG",
)

@Suppress("UNCHECKED_CAST")
internal fun ExportEvent.timelineEvent(index: Long = 0): TimelineEvent {
    val event = this
    val content = (event.raw["content"] as? Map<String, Any?>).orEmpty().toMutableMap()
    val relates = (content["m.relates_to"] as? Map<*, *>)?.filterKeys { it != "m.in_reply_to" }
    if (relates != null) content["m.relates_to"] = relates
    val root = Event(eventId = event.id, roomId = EXPORT_ROOM_ID, senderId = event.person.id,
            type = event.type, originServerTs = event.timestamp, stateKey = event.raw["state_key"] as? String,
            content = content.filterValues { it != null }.mapValues { it.value!! },
            unsignedData = (event.raw["unsigned"] as? Map<String, Any>)?.toModel()).also { it.sendState = SendState.SYNCED }
    val timelineEvent = TimelineEvent(root, index, event.id,
            senderInfo = SenderInfo(event.person.id, event.person.name, true, event.person.avatar),
            annotations = event.liveLocationActive?.let { active -> EventAnnotationsSummary(
                liveLocationShareAggregatedSummary = LiveLocationShareAggregatedSummary(
                        EXPORT_ROOM_ID, event.person.id, active, event.liveLocationEnd,
                        event.liveLocationContent?.filterValues { it != null }?.mapValues { it.value!! }?.toModel())) })
    return timelineEvent
}
