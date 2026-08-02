/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.contentscanner

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.util.Optional

fun ContentScannerService.getLiveStatusForFile(
        mxcUrl: String,
        fetchIfNeeded: Boolean = true,
        fileInfo: ElementToDecrypt? = null
): LiveData<Optional<ScanStatusInfo>> = getStatusFlowForFile(mxcUrl, fetchIfNeeded, fileInfo).asLiveData()
