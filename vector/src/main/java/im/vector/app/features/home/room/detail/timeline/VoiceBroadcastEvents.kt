/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline

// Voice broadcast recording/playback was removed on this fork, but the state events other clients emit
// are still surfaced as plain timeline notices rather than being silently dropped.
const val STATE_ROOM_VOICE_BROADCAST_INFO = "io.element.voicebroadcast.info"
