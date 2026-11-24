/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.graphics.Bitmap
import android.text.Spanned
import im.vector.app.features.emoji.TwemojiProvider
import im.vector.app.features.emoji.TwemojiSpan
import org.matrix.android.sdk.api.session.profile.IdentityAliases
import java.io.File
import javax.inject.Inject

class ExportEmojiAssets @Inject constructor(private val provider: TwemojiProvider) {
    fun collect(texts: List<String>, directory: File, assets: MutableMap<String, ExportAsset>): Map<String, String> {
        val images = linkedMapOf<String, String>()
        for (text in texts) {
            val spanned = provider.spanify(text) as? Spanned ?: continue
            spanned.getSpans(0, spanned.length, TwemojiSpan::class.java).forEach { span ->
                val emoji = text.substring(spanned.getSpanStart(span), spanned.getSpanEnd(span))
                if (emoji !in images) provider.bitmapForEmoji(emoji)?.let { bitmap ->
                    val path = "assets/emoji-" + IdentityAliases.digest(emoji).take(32) + ".png"
                    val target = File(directory, path).also { it.parentFile?.mkdirs() }
                    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    assets[path] = ExportAsset(path, "image/png")
                    images[emoji] = path
                }
            }
        }
        return images
    }
}
