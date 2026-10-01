/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.telegram

import im.vector.app.core.resources.StringProvider
import im.vector.app.core.vpn.VpnGateInterceptor
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class TelegramStickerFormat { STATIC, ANIMATED, VIDEO }

data class TelegramSticker(
        val fileId: String,
        val fileUniqueId: String,
        val emoji: String?,
        val format: TelegramStickerFormat,
)

data class TelegramStickerSet(
        val name: String,
        val title: String,
        val isCustomEmoji: Boolean,
        val stickers: List<TelegramSticker>,
        val thumbnailFileId: String?,
)

class TelegramApiException(message: String) : IOException(message)

/**
 * Minimal Telegram Bot API client for reading sticker sets. Uses its own client: requests must never
 * carry the Matrix session's interceptors (access token) to a third party.
 */
@Singleton
class TelegramBotApi @Inject constructor(
        private val stringProvider: StringProvider,
        vpnGateInterceptor: VpnGateInterceptor,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(vpnGateInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

    suspend fun checkToken(token: String) {
        call(token, "getMe")
    }

    suspend fun getStickerSet(token: String, name: String): TelegramStickerSet {
        val result = try {
            call(token, "getStickerSet", "name" to name) as JSONObject
        } catch (failure: TelegramApiException) {
            if (failure.message?.contains("STICKERSET_INVALID") == true) {
                throw TelegramApiException(stringProvider.getString(CommonStrings.image_pack_telegram_set_not_found, name))
            }
            throw failure
        }
        val array = result.optJSONArray("stickers")
        val stickers = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array?.optJSONObject(index) ?: return@mapNotNull null
            TelegramSticker(
                    fileId = item.optString("file_id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                    fileUniqueId = item.optString("file_unique_id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                    emoji = item.optString("emoji").takeIf { it.isNotEmpty() },
                    format = when {
                        item.optBoolean("is_video") -> TelegramStickerFormat.VIDEO
                        item.optBoolean("is_animated") -> TelegramStickerFormat.ANIMATED
                        else -> TelegramStickerFormat.STATIC
                    },
            )
        }
        return TelegramStickerSet(
                name = result.optString("name").ifEmpty { name },
                title = result.optString("title").ifEmpty { name },
                isCustomEmoji = result.optString("sticker_type") == "custom_emoji",
                stickers = stickers,
                thumbnailFileId = (result.optJSONObject("thumbnail") ?: result.optJSONObject("thumb"))?.optString("file_id")?.takeIf { it.isNotEmpty() },
        )
    }

    /** Downloads the file behind [fileId] into [dir]; the extension comes from Telegram's file path. */
    suspend fun download(token: String, fileId: String, dir: File, baseName: String): File {
        val filePath = (call(token, "getFile", "file_id" to fileId) as JSONObject).optString("file_path")
        if (filePath.isEmpty()) throw TelegramApiException(stringProvider.getString(CommonStrings.image_pack_telegram_error, "file_path"))
        val extension = filePath.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 5 } ?: "bin"
        val url = HttpUrl.Builder()
                .scheme("https")
                .host(HOST)
                .addPathSegment("file")
                .addPathSegment("bot$token")
                .addPathSegments(filePath)
                .build()
        val target = File(dir, "$baseName.$extension")
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TelegramApiException(stringProvider.getString(CommonStrings.image_pack_telegram_error, "HTTP ${response.code()}"))
                }
                val body = response.body() ?: throw TelegramApiException(stringProvider.getString(CommonStrings.image_pack_telegram_error, "empty body"))
                target.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            }
        }
        return target
    }

    private suspend fun call(token: String, method: String, vararg params: Pair<String, String>): Any = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
                .scheme("https")
                .host(HOST)
                .addPathSegment("bot$token")
                .addPathSegment(method)
                .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
                .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val json = response.body()?.string()?.let { runCatching { JSONObject(it) }.getOrNull() }
            // A malformed token gets 404 rather than 401: the bot path itself doesn't exist.
            if (response.code() == 401 || response.code() == 404) {
                throw TelegramApiException(stringProvider.getString(CommonStrings.image_pack_telegram_token_invalid))
            }
            if (json == null || !json.optBoolean("ok")) {
                val description = json?.optString("description")?.takeIf { it.isNotEmpty() } ?: "HTTP ${response.code()}"
                throw TelegramApiException(stringProvider.getString(CommonStrings.image_pack_telegram_error, description))
            }
            json.opt("result") ?: JSONObject()
        }
    }

    private companion object {
        const val HOST = "api.telegram.org"
    }
}
