/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.threads

import org.matrix.android.sdk.api.session.room.threads.ThreadsService

// Lets the room factory stay shared while each platform decides whether its threads service is paged.
internal interface ThreadsServiceFactory {

    fun create(roomId: String): ThreadsService
}
