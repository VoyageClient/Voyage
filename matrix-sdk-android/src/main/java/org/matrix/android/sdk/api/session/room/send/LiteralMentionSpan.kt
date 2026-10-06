/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.send

/**
 * Marks outgoing text whose mentions are meant literally (an escaped mention, a /plain message): an `@room`
 * under it never sets `m.mentions.room`, and the message always carries an `m.mentions` block so the server
 * doesn't match the text against its legacy mention rules either.
 */
class LiteralMentionSpan
