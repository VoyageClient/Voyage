/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import android.net.Uri
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Glide model loader for `Uri -> ByteBuffer`. With this in place, animated content URI loads
 * (e.g. the attachment-preview screen) can go through penfeizhou's `ByteBufferAnimationDecoder`
 * without us having to do a blocking `readBytes()` on the main thread — the DataFetcher's
 * `loadData` runs on Glide's source executor. Still images fall through to the default
 * `Uri -> InputStream -> Bitmap` chain unchanged.
 */
internal class UriByteBufferLoader(private val context: Context) : ModelLoader<Uri, ByteBuffer> {

    override fun handles(model: Uri): Boolean = model.scheme == "content" || model.scheme == "file"

    override fun buildLoadData(model: Uri, width: Int, height: Int, options: Options): ModelLoader.LoadData<ByteBuffer> {
        return ModelLoader.LoadData(ObjectKey(model), Fetcher(context, model))
    }

    private class Fetcher(private val context: Context, private val uri: Uri) : DataFetcher<ByteBuffer> {

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in ByteBuffer>) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    callback.onLoadFailed(IOException("Could not open URI $uri"))
                } else {
                    callback.onDataReady(ByteBuffer.wrap(bytes))
                }
            } catch (t: Throwable) {
                callback.onLoadFailed(t as? Exception ?: IOException(t))
            }
        }

        override fun cleanup() = Unit
        override fun cancel() = Unit
        override fun getDataClass(): Class<ByteBuffer> = ByteBuffer::class.java
        override fun getDataSource(): DataSource = DataSource.LOCAL
    }
}

internal class UriByteBufferLoaderFactory(private val context: Context) : ModelLoaderFactory<Uri, ByteBuffer> {
    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Uri, ByteBuffer> = UriByteBufferLoader(context)
    override fun teardown() = Unit
}
