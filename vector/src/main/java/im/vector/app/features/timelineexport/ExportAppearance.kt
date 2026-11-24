/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

fun TimelineExportOptions.withAppearance(other: TimelineExportOptions): TimelineExportOptions = copy(
        theme = other.theme, accent = other.accent, accentName = other.accentName,
        background = other.background, foreground = other.foreground, incoming = other.incoming, outgoing = other.outgoing,
        peoplePalette = other.peoplePalette, roomPalette = other.roomPalette,
        showProfileColors = other.showProfileColors, codePalette = other.codePalette,
        peopleAvatarStyle = other.peopleAvatarStyle, roomAvatarStyle = other.roomAvatarStyle, avatarShape = other.avatarShape,
        bubbles = other.bubbles, bubbleCorners = other.bubbleCorners, bubbleTail = other.bubbleTail, bubbleTint = other.bubbleTint,
)
