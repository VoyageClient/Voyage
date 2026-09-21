/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.permalink

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import im.vector.app.core.extensions.singletonEntryPoint
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import timber.log.Timber

/** A `matrix:` URI is opaque, so [java.net.URL] rejects it: the link machinery has to spot it by scheme. */
fun String.isMatrixUri() = startsWith(PermalinkService.MATRIX_URI_SCHEME_PREFIX, ignoreCase = true)

/**
 * Open [url] in the app if it is a matrix permalink, whatever scheme it is written in. Returns false
 * when it isn't one, or when there is no session or activity to open it with, so the caller can fall
 * back to its own handling.
 */
fun openPermalinkInApp(context: Context, url: String): Boolean {
    val activity = context.findFragmentActivity() ?: return false
    val entryPoint = activity.singletonEntryPoint()
    val session = entryPoint.activeSessionHolder().getSafeActiveSession() ?: return false
    val supportedHosts = context.resources.getStringArray(im.vector.app.config.R.array.permalink_supported_hosts)
    if (!session.permalinkService().isPermalinkSupported(supportedHosts, url)) return false
    activity.lifecycleScope.launch {
        if (!entryPoint.permalinkHandler().launch(activity, url)) {
            Timber.w("Permalink not handled in app: $url")
        }
    }
    return true
}

private fun Context.findFragmentActivity(): FragmentActivity? =
        generateSequence(this) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<FragmentActivity>()
                .firstOrNull()
