/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.content

import android.net.Uri

/** [ContentAttachmentData.queryUri] parsed as an android [Uri]. */
val ContentAttachmentData.queryUriAndroid: Uri
    get() = Uri.parse(queryUri)
