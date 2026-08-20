/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.util

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.internal.di.MoshiProvider

/**
 * Try to extract and serialize a MatrixError, or default to localizedMessage.
 */
internal fun Throwable.toMatrixErrorStr(): String {
    return (this as? Failure.ServerError)
            ?.let { tryOrNull { serializeServerError(it) } }
            ?: localizedMessage
            ?: "error"
}

/** Serializes the MatrixError with the HTTP status folded in, so readers can tell a server rejection apart. */
private fun serializeServerError(failure: Failure.ServerError): String? {
    val moshi = MoshiProvider.providesMoshi()
    @Suppress("UNCHECKED_CAST")
    val fields = (moshi.adapter(MatrixError::class.java).toJsonValue(failure.error) as? Map<String, Any?>)
            ?.toMutableMap()
            ?: return null
    fields[MatrixError.HTTP_CODE_JSON_KEY] = failure.httpCode
    return moshi.adapter(Map::class.java).toJson(fields)
}
