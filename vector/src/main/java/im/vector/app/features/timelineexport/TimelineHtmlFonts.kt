/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import timber.log.Timber
import java.io.File

internal object TimelineHtmlFonts {
    fun write(context: Context, directory: File): List<ExportAsset> {
        val files = listOf("Regular" to "regular", "Bold" to "bold", "Italic" to "italic", "BoldItalic" to "bold-italic", "Medium" to "medium", "Light" to "light", "Mono" to "mono").map { (source, destination) ->
            val path = "assets/timeline-$destination.ttf"
            val output = File(directory, path).also { it.parentFile?.mkdirs() }
            if (!output.isFile) {
                val filename = if (source == "Mono") "DroidSansMono.ttf" else "Roboto-$source.ttf"
                val system = File("/system/fonts/$filename")
                if (system.isFile && system.canRead()) system.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
                else context.assets.open("timeline-export/$filename").use { input -> output.outputStream().use { input.copyTo(it) } }
                Timber.d("ExportTrace HTML font variant=%s system=%s bytes=%d", source, system.isFile, output.length())
            }
            ExportAsset(path, "font/ttf")
        }
        val license = "assets/timeline-font-license.txt"
        context.assets.open("timeline-export/LICENSE.txt").use { input -> File(directory, license).outputStream().use { input.copyTo(it) } }
        return files + ExportAsset(license, "text/plain")
    }
}
