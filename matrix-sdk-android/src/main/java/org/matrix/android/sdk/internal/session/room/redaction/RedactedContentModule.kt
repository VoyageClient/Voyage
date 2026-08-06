/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.redaction

import dagger.Binds
import dagger.Module
import org.matrix.android.sdk.api.session.redaction.RedactedContentService

@Module
internal abstract class RedactedContentModule {

    @Binds
    abstract fun bindRedactedContentService(service: DefaultRedactedContentService): RedactedContentService
}
