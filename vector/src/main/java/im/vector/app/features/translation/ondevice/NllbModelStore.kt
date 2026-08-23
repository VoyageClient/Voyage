/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation.ondevice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.vpn.VpnGateInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage + manual download of the on-device translation models (NLLB-200 600M int8 in ONNX form,
 * the RTranslator conversion, plus the SentencePiece vocabulary and fastText's language-id model).
 * Nothing is bundled: the user triggers the ~1 GB download from Translation settings.
 */
@Singleton
class NllbModelStore @Inject constructor(
        @ApplicationContext private val context: Context,
        vpnGateInterceptor: VpnGateInterceptor,
) {
    data class ModelFile(val name: String, val url: String, val bytes: Long)

    sealed class State {
        object NotDownloaded : State()
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    val files = listOf(
            ModelFile("sentencepiece_bpe.model", "https://raw.githubusercontent.com/niedev/RTranslator/v2.00/app/src/main/assets/sentencepiece_bpe.model", 4_852_054),
            ModelFile("lid.176.ftz", "https://dl.fbaipublicfiles.com/fasttext/supervised-models/lid.176.ftz", 938_013),
            ModelFile("NLLB_cache_initializer.onnx", "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_cache_initializer.onnx", 25_368_443),
            ModelFile("NLLB_decoder.onnx", "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_decoder.onnx", 179_109_694),
            ModelFile("NLLB_embed_and_lm_head.onnx", "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_embed_and_lm_head.onnx", 524_712_277),
            ModelFile("NLLB_encoder.onnx", "https://github.com/niedev/RTranslator/releases/download/2.0.0/NLLB_encoder.onnx", 266_487_014),
    )

    val totalBytes: Long = files.sumOf { it.bytes }

    private val dir = File(context.filesDir, "nllb")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
            .addInterceptor(vpnGateInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    private val _state = MutableStateFlow<State>(if (isReady()) State.Ready else State.NotDownloaded)
    val state: StateFlow<State> = _state

    fun fileFor(name: String): File = File(dir, name)

    fun isReady(): Boolean = files.all { fileFor(it.name).length() == it.bytes }

    fun downloadedBytes(): Long = files.sumOf { minOf(fileFor(it.name).length(), it.bytes) }

    fun startDownload() {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(downloadedBytes(), totalBytes)
        scope.launch {
            try {
                dir.mkdirs()
                for (file in files) {
                    downloadFile(file)
                }
                _state.value = if (isReady()) State.Ready else State.Failed("Downloaded files failed validation")
            } catch (e: Exception) {
                Timber.w(e, "NLLB model download failed")
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun delete() {
        if (_state.value is State.Downloading) return
        dir.deleteRecursively()
        _state.value = State.NotDownloaded
    }

    // Resumes with a Range request so an interrupted 500MB file doesn't restart from zero.
    private fun downloadFile(model: ModelFile) {
        val target = fileFor(model.name)
        if (target.length() == model.bytes) return
        val part = File(dir, model.name + ".part")
        val existing = part.length()
        val request = Request.Builder()
                .url(model.url)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
        client.newCall(request).execute().use { response ->
            val resumed = response.code() == 206
            if (!response.isSuccessful) throw IOException("HTTP ${response.code()} for ${model.name}")
            if (!resumed && existing > 0) part.delete()
            val body = response.body() ?: throw IOException("Empty body for ${model.name}")
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var sinceEmit = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sinceEmit += read
                        if (sinceEmit >= 1_000_000) {
                            sinceEmit = 0
                            _state.value = State.Downloading(downloadedBytes() + part.length(), totalBytes)
                        }
                    }
                }
            }
        }
        if (part.length() != model.bytes) throw IOException("${model.name}: got ${part.length()} of ${model.bytes} bytes")
        if (!part.renameTo(target)) throw IOException("Could not move ${model.name} into place")
        _state.value = State.Downloading(downloadedBytes(), totalBytes)
    }
}
