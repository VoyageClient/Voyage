/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.api.session.accountdata

object UserAccountDataTypes {
    const val TYPE_IGNORED_USER_LIST = "m.ignored_user_list"
    const val TYPE_DIRECT_MESSAGES = "m.direct"
    const val TYPE_BREADCRUMBS = "im.vector.setting.breadcrumbs"
    const val TYPE_PREVIEW_URLS = "org.matrix.preview_urls"
    const val TYPE_WIDGETS = "m.widgets"
    const val TYPE_PUSH_RULES = "m.push_rules"
    const val TYPE_INTEGRATION_PROVISIONING = "im.vector.setting.integration_provisioning"
    const val TYPE_ALLOWED_WIDGETS = "im.vector.setting.allowed_widgets"
    const val TYPE_IDENTITY_SERVER = "m.identity_server"
    const val TYPE_ACCEPTED_TERMS = "m.accepted_terms"
    const val TYPE_OVERRIDE_COLORS = "im.vector.setting.override_colors"
    const val TYPE_LOCAL_NOTIFICATION_SETTINGS = "org.matrix.msc3890.local_notification_settings."

    // MSC4441 profile annotations ("profile notes"), optionally MSC4483-encrypted
    const val TYPE_PROFILE_ANNOTATIONS = "m.profile_annotations"
    const val TYPE_PROFILE_ANNOTATIONS_UNSTABLE = "dev.zirco.msc4441.profile_annotations"
    val TYPES_PROFILE_ANNOTATIONS = listOf(TYPE_PROFILE_ANNOTATIONS, TYPE_PROFILE_ANNOTATIONS_UNSTABLE)

    // Frequently-used unicode emojis. Element Web migrated to the stable type but still writes both.
    const val TYPE_RECENT_EMOJI = "m.recent_emoji"
    const val TYPE_RECENT_EMOJI_UNSTABLE = "io.element.recent_emoji"

    // MSC4287 key backup preference. The unstable form Element shipped stores the reversed sense
    // ({"disabled": true}) rather than {"enabled": true}, so the two are not interchangeable.
    const val TYPE_KEY_BACKUP = "m.key_backup"
    const val TYPE_KEY_BACKUP_UNSTABLE = "m.org.matrix.custom.backup_disabled"

    // MSC4380 invite blocking.
    const val TYPE_INVITE_PERMISSION_CONFIG = "m.invite_permission_config"

    // MSC4278 media preview controls. Element Web still writes only the unstable type.
    const val TYPE_MEDIA_PREVIEW_CONFIG = "m.media_preview_config"
    const val TYPE_MEDIA_PREVIEW_CONFIG_UNSTABLE = "io.element.msc4278.media_preview_config"

    // MSC2545 image packs. Personal pack has no stable id in the redrafted spec; keep using the unstable one.
    const val TYPE_USER_EMOTES = "im.ponies.user_emotes"
    const val TYPE_IMAGE_PACK_ROOMS = "m.image_pack.rooms"
    const val TYPE_IMAGE_PACK_ROOMS_UNSTABLE = "im.ponies.emote_rooms"

    // Frequently-used stickers, mirroring the recent-emoji shape. No stable id exists for these.
    const val TYPE_RECENT_STICKERS = "io.element.recent_stickers"

    // Frequently-used custom emoticons (separate from the unicode recent emojis).
    const val TYPE_RECENT_EMOTICONS = "io.element.recent_emoticons"

    // The message long-press quick-reaction row, as an ordered list of emojis / emote mxc urls.
    const val TYPE_QUICK_REACTIONS = "im.voyage.setting.quick_reactions"

    // Per-room mention counts (roomId -> userId -> count), used to rank the @-autocomplete list.
    const val TYPE_MENTION_FREQUENCY = "im.voyage.setting.mention_frequency"

    /** Cached result of the Synapse admin-API probe, so every device doesn't have to re-probe. */
    const val TYPE_SERVER_ADMIN = "im.voyage.setting.server_admin"

    /** MSC4529 per-user profile field overrides (userId -> field -> value), see [org.matrix.android.sdk.api.session.profile.ProfileOverrides]. */
    const val TYPE_PROFILE_OVERRIDES = "m.profile_overrides"
    const val TYPE_PROFILE_OVERRIDES_UNSTABLE = "org.matrix.msc4529.profile_overrides"
}
