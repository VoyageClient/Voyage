/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import java.io.File

// Platform seam for stripping image metadata before upload; the android impl needs androidx
// ExifInterface + Commons Imaging.
internal interface ImageExifTagRemover {

    /**
     * @return the scrubbed file (or the original when there is nothing to strip / scrubbing failed),
     * or `null` if the format can't be stripped in place and should be re-encoded instead.
     */
    suspend fun stripImageMetadata(imageFile: File): File?
}
