/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.handler

import org.matrix.android.sdk.api.session.sync.model.UserProfileSyncUpdate
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.profile.ExtendedProfileCache
import javax.inject.Inject

/**
 * Applies the MSC4429 profile field updates the server pushes down sync, so extended profile fields
 * stay fresh without a per-user fetch. Sliding sync's MSC4262 extension is folded into this same
 * shape by SlidingSyncTranslator.
 */
@SessionScope
internal class ProfileSyncHandler @Inject constructor(
        private val extendedProfileCache: ExtendedProfileCache,
) {

    /** A null `profile_updates` means we no longer share a room, so stop tracking the user. */
    fun handle(users: Map<String, UserProfileSyncUpdate>?) {
        users?.forEach { (userId, update) ->
            val updates = update.profileUpdates
            if (updates == null) {
                extendedProfileCache.forget(userId)
            } else {
                extendedProfileCache.applyProfileUpdates(userId, updates)
            }
        }
    }
}
