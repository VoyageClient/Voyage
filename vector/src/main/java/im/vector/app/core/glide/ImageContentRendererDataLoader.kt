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
import com.bumptech.glide.util.ByteBufferUtil
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.core.files.LocalFilesHelper
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.session.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

// Delivers ByteBuffer, not InputStream: Glide caps InputStream rewinds at 5MB, and its orientation
// probe consumes the whole source — any image over 5MB would fail every decode path with
// InvalidMarkException. A ByteBuffer rewinds by re-wrapping, with no size limit.
class ImageContentRendererDataLoaderFactory(private val context: Context) : ModelLoaderFactory<ImageContentRenderer.Data, ByteBuffer> {

    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<ImageContentRenderer.Data, ByteBuffer> {
        return ImageContentRendererDataLoader(context)
    }

    override fun teardown() {
        // Is there something to do here?
    }
}

class ImageContentRendererDataLoader(private val context: Context) :
        ModelLoader<ImageContentRenderer.Data, ByteBuffer> {
    override fun handles(model: ImageContentRenderer.Data): Boolean {
        // Always handle
        return true
    }

    override fun buildLoadData(model: ImageContentRenderer.Data, width: Int, height: Int, options: Options): ModelLoader.LoadData<ByteBuffer>? {
        return ModelLoader.LoadData(ObjectKey(model), ImageContentRendererDataFetcher(context, model, width, height))
    }
}

class ImageContentRendererDataFetcher(
        context: Context,
        private val data: ImageContentRenderer.Data,
        private val width: Int,
        private val height: Int
) :
        DataFetcher<ByteBuffer> {

    private val localFilesHelper = LocalFilesHelper(context)
    private val activeSessionHolder = context.singletonEntryPoint().activeSessionHolder()

    override fun getDataClass(): Class<ByteBuffer> {
        return ByteBuffer::class.java
    }

    private val delivered = AtomicBoolean(false)

    override fun cleanup() {
        cancel()
    }

    override fun getDataSource(): DataSource {
        // ?
        return DataSource.REMOTE
    }

    override fun cancel() {
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in ByteBuffer>) {
        val isLocal = localFilesHelper.isLocalFile(data.url)
        Timber.i("MEDIADBG fetcher start url=${data.url} local=$isLocal event=${data.eventId} thread=${Thread.currentThread().name}")
        if (isLocal) {
            val buffer = try {
                localFilesHelper.openInputStream(data.url)?.use { ByteBuffer.wrap(it.readBytes()) }
            } catch (throwable: Throwable) {
                Timber.w(throwable, "MEDIADBG fetcher local open failed url=${data.url}")
                null
            }
            if (buffer == null) {
                // Glide waits forever on a fetcher that answers with neither callback.
                Timber.w("MEDIADBG fetcher local stream unavailable url=${data.url}")
                callback.onLoadFailed(IOException("Cannot open local file ${data.url}"))
            } else {
                callback.onDataReady(buffer)
                Timber.i("MEDIADBG fetcher local done url=${data.url}")
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
            // Mapped, not read: decodes touch only what they sample.
            val buffered = result.mapCatching { ByteBufferUtil.fromFile(it) }
            withContext(Dispatchers.Main) {
                if (delivered.getAndSet(true)) return@withContext
                buffered.fold(
                        { callback.onDataReady(it) },
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
