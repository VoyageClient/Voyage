/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.send

/**
 * Tag interface for spans representing an MSC2545 custom emoticon. When detected in a message to send,
 * these are serialized to an `<img data-mx-emoticon>` element in the formatted body.
 */
interface MatrixEmoteSpan {
    /** The emote shortcode, e.g. "cat_wave" (without surrounding colons). */
    val shortcode: String

    /** The `mxc://` URI of the emote image. */
    val mxcUrl: String

    /** Accessible description of the emote; falls back to the shortcode when null. */
    val body: String?
}
