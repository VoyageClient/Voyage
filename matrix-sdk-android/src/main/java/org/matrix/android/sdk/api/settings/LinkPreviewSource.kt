/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.settings

/**
 * Who generates link previews in a room: the previews bundled into the messages we send (MSC4095), and
 * the previews of received links that carry none. Only [SERVER] previews received links, since fetching
 * them on the device would let whoever posts one learn our IP address.
 */
enum class LinkPreviewSource(val value: String) {
    NONE("none"),
    DEVICE("device"),
    SERVER("server");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value }
    }
}
