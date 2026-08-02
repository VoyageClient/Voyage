/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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
package org.matrix.android.sdk.internal.session.room.send.pills

/**
 * Detects special spans (mention pills, custom-emote images) in a CharSequence and turns them into
 * the HTML/markdown sent in a Matrix message. Span detection is a platform concern (android uses
 * Spannable); a desktop impl that carries no spans returns null (no transformation).
 */
internal interface TextPillsUtils {

    /** @return the transformed HTML or null if no transformable span is found. */
    fun processSpecialSpansToHtml(text: CharSequence): String?

    /** @return the transformed markdown or null if no transformable span is found. */
    fun processSpecialSpansToMarkdown(text: CharSequence): String?
}
