/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.error

import android.content.Context
import im.vector.app.features.webview.VectorWebViewActivity
import org.matrix.android.sdk.api.failure.Failure
import java.net.HttpURLConnection

// 404 is left out: the login flow already reports it as "not a homeserver".
fun Throwable.asUnexpectedServerPage(): Failure.OtherServerError? {
    return (this as? Failure.OtherServerError)
            ?.takeIf { it.errorBody.isNotBlank() && it.httpCode != HttpURLConnection.HTTP_NOT_FOUND }
}

/**
 * @return true if [throwable] was a non-Matrix server page and it was opened in the in-app browser.
 */
fun Context.showUnexpectedServerPage(throwable: Throwable): Boolean {
    val failure = throwable.asUnexpectedServerPage() ?: return false
    val mimeType = if (failure.errorBody.trimStart().startsWith("<")) "text/html" else "text/plain"
    startActivity(VectorWebViewActivity.getIntentForContent(this, failure.errorBody, mimeType, failure.url))
    return true
}
