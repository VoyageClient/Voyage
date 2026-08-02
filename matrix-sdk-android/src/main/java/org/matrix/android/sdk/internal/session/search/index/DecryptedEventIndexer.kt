/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search.index

import org.matrix.android.sdk.api.session.crypto.model.MXEventDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event

// Narrow seam over EventIndexer so the room-summary decryptor can feed decrypted events to the local
// search index without depending on EventIndexer itself (which observes the Session lifecycle). A
// desktop build without local search can bind a no-op.
internal interface DecryptedEventIndexer {
    fun onEventsDecrypted(events: List<Pair<Event, MXEventDecryptionResult>>)
}
