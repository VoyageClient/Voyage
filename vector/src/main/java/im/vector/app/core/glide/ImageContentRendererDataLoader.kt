/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.core.files.LocalFilesHelper
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.session.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class ImageContentRendererDataLoaderFactory(private val context: Context) : ModelLoaderFactory<ImageContentRenderer.Data, InputStream> {

    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<ImageContentRenderer.Data, InputStream> {
        return ImageContentRendererDataLoader(context)
    }

    override fun teardown() {
        // Is there something to do here?
    }
}

class ImageContentRendererDataLoader(private val context: Context) :
        ModelLoader<ImageContentRenderer.Data, InputStream> {
    override fun handles(model: ImageContentRenderer.Data): Boolean {
        // Always handle
        return true
    }

    override fun buildLoadData(model: ImageContentRenderer.Data, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
        return ModelLoader.LoadData(ObjectKey(model), ImageContentRendererDataFetcher(context, model, width, height))
    }
}

class ImageContentRendererDataFetcher(
        context: Context,
        private val data: ImageContentRenderer.Data,
        private val width: Int,
        private val height: Int
) :
        DataFetcher<InputStream> {

    private val localFilesHelper = LocalFilesHelper(context)
    private val activeSessionHolder = context.singletonEntryPoint().activeSessionHolder()

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    private var stream: InputStream? = null
    private val delivered = AtomicBoolean(false)

    override fun cleanup() {
        cancel()
    }

    override fun getDataSource(): DataSource {
        // ?
        return DataSource.REMOTE
    }

    override fun cancel() {
        if (stream != null) {
            try {
                // This is often called on main thread, and this could be a network Stream..
                // on close will throw android.os.NetworkOnMainThreadException, so we catch throwable
                stream?.close() // interrupts decode if any
                stream = null
            } catch (ignore: Throwable) {
                Timber.e("Failed to close stream ${ignore.localizedMessage}")
            } finally {
                stream = null
            }
        }
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        val isLocal = localFilesHelper.isLocalFile(data.url)
        Timber.i("MEDIADBG fetcher start url=${data.url} local=$isLocal event=${data.eventId} thread=${Thread.currentThread().name}")
        if (isLocal) {
            // Wrap so the stream supports mark/reset — content-URI input streams typically don't,
            // and Glide's animated decoders skip non-markable sources.
            val stream = try {
                localFilesHelper.openInputStream(data.url)?.let(::BufferedInputStream)
            } catch (throwable: Throwable) {
                Timber.w(throwable, "MEDIADBG fetcher local open failed url=${data.url}")
                null
            }
            if (stream == null) {
                // Glide waits forever on a fetcher that answers with neither callback.
                Timber.w("MEDIADBG fetcher local stream unavailable url=${data.url}")
                callback.onLoadFailed(IOException("Cannot open local file ${data.url}"))
            } else {
                stream.use {
                    callback.onDataReady(it)
                    Timber.i("MEDIADBG fetcher local done url=${data.url}")
                }
            }
            return
        }
//        val contentUrlResolver = activeSessionHolder.getActiveSession().contentUrlResolver()

        val session = activeSessionHolder.getSafeActiveSession() ?: return Unit.also {
            Timber.w("MEDIADBG fetcher no session url=${data.url}")
            callback.onLoadFailed(IllegalArgumentException("No session"))
        }
        val fileService = session.fileService()
        // Use the file vector service, will avoid flickering and redownload after upload
        val job = session.coroutineScope.launch {
            val result = runCatching {
                // The service's own watchdog is half an hour, sized for large file downloads, and
                // requests for the same url share one download — so a stalled fetch leaves the
                // thumbnail spinning for that long and every retry attaches to the same wait. Give up
                // on displaying it much sooner; the download itself carries on and still populates the
                // cache, this only stops the view waiting on it.
                withTimeout(RENDER_TIMEOUT_MS) {
                    fileService.downloadFile(
                            fileName = data.filename,
                            mimeType = data.mimeType,
                            url = data.url,
                            elementToDecrypt = data.elementToDecrypt
                    )
                }
            }
            Timber.i("MEDIADBG fetcher download settled url=${data.url} ok=${result.isSuccess}")
            withContext(Dispatchers.Main) {
                if (delivered.getAndSet(true)) return@withContext
                result.fold(
                        { callback.onDataReady(BufferedInputStream(it.inputStream())) },
                        // Failure is a Throwable, not an Exception, so it never survives the cast —
                        // keep it as the cause or the HTTP status is lost before anything can read it.
                        { callback.onLoadFailed(it as? Exception ?: IOException(it.localizedMessage, it)) }
                )
            }
            Timber.i("MEDIADBG fetcher callback delivered url=${data.url}")
        }
        // A cancelled scope silently swallows the launch, leaving Glide on the placeholder forever.
        job.invokeOnCompletion { cause ->
            if (cause != null && !delivered.getAndSet(true)) {
                Timber.w(cause, "MEDIADBG fetcher job ended without delivering url=${data.url}")
                callback.onLoadFailed(cause as? Exception ?: IOException(cause.localizedMessage, cause))
            }
        }
//        val url = contentUrlResolver.resolveFullSize(data.url)
//                ?: return
//
//        val request = Request.Builder()
//                .url(url)
//                .build()
//
//        val response = client.newCall(request).execute()
//        val inputStream = response.body?.byteStream()
//        Timber.v("Response size ${response.body?.contentLength()} - Stream available: ${inputStream?.available()}")
//        if (!response.isSuccessful) {
//            callback.onLoadFailed(IOException("Unexpected code $response"))
//            return
//        }
//        stream = if (data.elementToDecrypt != null && data.elementToDecrypt.k.isNotBlank()) {
//            Matrix.decryptStream(inputStream, data.elementToDecrypt)
//        } else {
//            inputStream
//        }
//        callback.onDataReady(stream)
    }
}

private const val RENDER_TIMEOUT_MS = 25_000L
