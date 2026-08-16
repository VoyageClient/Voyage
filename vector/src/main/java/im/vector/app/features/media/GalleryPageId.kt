/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

/**
 * Identifies one item of an MSC4274 gallery, whose items all share their event's id.
 *
 * Shared because the media viewer routes async loads to pages by this id and drops anything that
 * does not match the bound page: two producers spelling it differently leave pages blank.
 */
fun galleryPageId(base: String, index: Int?): String = if (index == null) base else "$base#$index"
