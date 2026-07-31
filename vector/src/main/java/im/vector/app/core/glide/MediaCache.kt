/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import androidx.annotation.MainThread
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.cache.DiskCache
import im.vector.app.core.utils.getSizeOfFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import java.io.File
import javax.inject.Inject

/** Glide's thumbnail cache, both variants of each thumbnail, plus the SDK's full-size file cache. */
class MediaCache @Inject constructor(
        private val context: Context,
) {

    suspend fun size(session: Session): Long = withContext(Dispatchers.IO) {
        getSizeOfFiles(File(context.cacheDir, DiskCache.Factory.DEFAULT_DISK_CACHE_DIR)) + session.fileService().getCacheSize()
    }

    @MainThread
    suspend fun clear(session: Session) {
        clearThumbnails()
        session.fileService().clearCache()
    }

    @MainThread
    suspend fun clearThumbnails() {
        // Glide requires its memory cache to be cleared from the main thread, and its disk cache off it.
        Glide.get(context).clearMemory()
        withContext(Dispatchers.IO) {
            Glide.get(context).clearDiskCache()
        }
    }
}
