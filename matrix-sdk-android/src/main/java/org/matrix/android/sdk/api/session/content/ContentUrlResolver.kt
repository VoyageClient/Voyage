/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.api.session.content

import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt

/**
 * This interface defines methods for accessing content from the current session.
 */
interface ContentUrlResolver {

    enum class ThumbnailMethod(val value: String) {
        CROP("crop"),
        SCALE("scale")
    }

    /**
     * URL to use to upload content.
     */
    val uploadUrl: String

    /**
     * URL to reserve a content URI ahead of uploading its bytes (MSC2246).
     */
    val createUrl: String

    /**
     * URL to upload the bytes of a content URI reserved through [createUrl] (MSC2246).
     */
    fun uploadUrlForReserved(serverName: String, mediaId: String): String

    /**
     * Get the actual URL for accessing the full-size image of a Matrix media content URI.
     *
     * @param contentUrl the Matrix media content URI (in the form of "mxc://...").
     * @return the URL to access the described resource, or null if the url is invalid.
     */
    fun resolveFullSize(contentUrl: String?): String?

    /**
     * Get the ResolvedMethod to download a URL.
     *
     * @param contentUrl the Matrix media content URI (in the form of "mxc://...").
     * @param elementToDecrypt Encryption data may be required if you use a content scanner
     * @return the Method to access resource, or null if invalid
     */
    fun resolveForDownload(contentUrl: String?, elementToDecrypt: ElementToDecrypt? = null): ResolvedMethod?

    /**
     * Get the actual URL for accessing the thumbnail image of a given Matrix media content URI.
     *
     * @param contentUrl the Matrix media content URI (in the form of "mxc://...").
     * @param width the desired width
     * @param height the desired height
     * @param method the desired method (METHOD_CROP or METHOD_SCALE)
     * @param animated if true, request an animated thumbnail (animated=true) when the source is animated.
     * @return the URL to access the described resource, or null if the url is invalid.
     */
    fun resolveThumbnail(contentUrl: String?, width: Int, height: Int, method: ThumbnailMethod, animated: Boolean = false): String?

    fun requiresAuthentication(resolvedUrl: String): Boolean

    sealed class ResolvedMethod {
        data class GET(val url: String) : ResolvedMethod()
        data class POST(val url: String, val jsonBody: String) : ResolvedMethod()
    }
}
