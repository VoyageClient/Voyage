/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session

import org.matrix.android.sdk.api.auth.data.SessionParams

// Platform seam for building a session graph, since the Dagger component doing it is per-platform.
internal interface SessionComponentFactory {

    fun create(sessionParams: SessionParams): SessionComponent
}
