/*
 * Copyright 2024 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.android.sdk.internal.session.sync.handler

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.getPresenceContent
import org.matrix.android.sdk.api.session.sync.model.PresenceSyncResponse
import org.matrix.android.sdk.internal.database.model.presence.UserPresenceEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import javax.inject.Inject

/**
 * SQLDelight write-path counterpart of [PresenceSyncHandler]. Runs inside the session DB transaction
 * (so no explicit Realm). Upserts user_presence and links the FK on the affected room/member summaries
 * (the actual presence is then resolved by FK at read time).
 */
internal class SqlPresenceSyncHandler @Inject constructor() {

    fun handle(stores: SessionStores, presenceSyncResponse: PresenceSyncResponse?) {
        val ignoredUserIds = stores.user.getIgnoredUserIds().toSet()
        presenceSyncResponse?.events
                ?.filter { event -> event.type == EventType.PRESENCE }
                ?.forEach { event ->
                    val content = event.getPresenceContent() ?: return@forEach
                    val userId = event.senderId ?: return@forEach
                    if (userId in ignoredUserIds) return@forEach
                    val entity = UserPresenceEntity(
                            userId = userId,
                            lastActiveAgo = content.lastActiveAgo,
                            statusMessage = content.statusMessage,
                            isCurrentlyActive = content.isCurrentlyActive,
                            avatarUrl = content.avatarUrl,
                            displayName = content.displayName,
                    ).also { it.presence = content.presence }
                    stores.user.upsertPresence(entity)
                    stores.roomSummary.linkDirectUserPresence(userId)
                    stores.roomMember.linkUserPresence(userId)
                }
    }
}
