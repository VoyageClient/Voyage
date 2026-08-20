/*
 * Copyright (c) 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.crypto.crosssigning

import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.internal.worker.SessionWorkerParams

@JsonClass(generateAdapter = true)
internal data class UpdateTrustWorkerParams(
        override val sessionId: String,
        override val lastFailureMessage: String? = null,
        // Kept for compatibility, but not used anymore (can be used for pending Worker)
        val updatedUserIds: List<String>? = null,
        // Passing a long list of userId can break the Work Manager due to data size limitation.
        // so now we use a temporary file to store the data
        val filename: String? = null
) : SessionWorkerParams
