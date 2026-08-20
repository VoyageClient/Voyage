/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent.data

import com.squareup.moshi.JsonClass
import java.util.Locale

/**
 * Device models from Google's public supported-devices list (UTF-16 CSV, columns
 * Retail Branding, Marketing Name, Device, Model). The UA device field is `<manufacturer> <model>`,
 * so options are "<branding> <model>". Filtered to mainstream OEMs and capped to keep the cache sane.
 */
class DeviceModelProvider(
        override val id: String,
        private val url: String,
        private val perBrandCap: Int,
) : UaDataProvider {

    override val primaryUrl: String get() = url

    // The UA uses `<manufacturer> <Build.MODEL>` (value); the label shows the marketing name for search.
    override suspend fun fetch(http: UaHttp): List<UaOption> {
        val text = String(http.getBytes(url), Charsets.UTF_16)
        val byBrand = HashMap<Int, LinkedHashMap<String, String>>() // rank -> (model -> marketing name)
        text.lineSequence().drop(1).forEach { line ->
            // Cheap prefilter on the leading branding field (no commas) before the full CSV parse.
            val brand = line.substringBefore(',').trim('"').trim().lowercase(Locale.US)
            val rank = BRAND_ORDER.indexOf(brand)
            if (rank < 0) return@forEach
            val cells = parseCsvLine(line)
            if (cells.size < 4) return@forEach
            val model = cells[3].trim()
            if (model.isEmpty()) return@forEach
            byBrand.getOrPut(rank) { LinkedHashMap() }.getOrPut(model) { cells[1].trim() }
        }
        return byBrand.entries.sortedBy { it.key }.flatMap { (rank, models) ->
            val display = BRAND_DISPLAY[BRAND_ORDER[rank]] ?: BRAND_ORDER[rank]
            models.entries.sortedBy { it.key }.take(perBrandCap).map { (model, marketing) ->
                UaOption("$display $model", if (marketing.isNotEmpty()) "$marketing ($model)" else model, null)
            }
        }
    }

    companion object {
        // Roughly descending Android OEM global share; index used as the sort key.
        private val BRAND_ORDER = listOf(
                "samsung", "xiaomi", "google", "oppo", "vivo", "realme", "oneplus",
                "motorola", "huawei", "honor", "nothing", "sony", "asus", "nokia", "lenovo",
        )

        // Canonical display casing (Retail Branding casing varies, e.g. "Realme"/"realme").
        private val BRAND_DISPLAY = mapOf(
                "samsung" to "Samsung", "xiaomi" to "Xiaomi", "google" to "Google", "oppo" to "OPPO",
                "vivo" to "vivo", "realme" to "realme", "oneplus" to "OnePlus", "motorola" to "Motorola",
                "huawei" to "Huawei", "honor" to "HONOR", "nothing" to "Nothing", "sony" to "Sony",
                "asus" to "ASUS", "nokia" to "Nokia", "lenovo" to "Lenovo",
        )
    }
}

/** One row of the AOSP build-numbers table: a build id, its Android major, and the devices it shipped to. */
@JsonClass(generateAdapter = true)
data class BuildIdRow(
        val buildId: String,
        val androidMajor: Int,
        val devices: List<String>,
)

/**
 * Parses the AOSP build-numbers HTML table into rows. Columns are
 * `Build ID | Tag | Android version | Supported devices`; only the Build ID and Android major are
 * mandatory (the newest rows have an empty device column, meaning "applies broadly").
 */
fun parseBuildIdTable(html: String): List<BuildIdRow> {
    val rows = ArrayList<BuildIdRow>()
    var i = html.indexOf("<tr")
    while (i >= 0) {
        val end = html.indexOf("</tr>", i)
        if (end < 0) break
        val cells = extractCells(html, i, end)
        i = html.indexOf("<tr", end)
        if (cells.size < 3) continue
        val buildId = cells[0]
        if (!BUILD_ID.matches(buildId)) continue
        val major = ANDROID_MAJOR.find(cells[2])?.groupValues?.get(1)?.toIntOrNull() ?: continue
        val devices = cells.getOrElse(3) { "" }.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        rows.add(BuildIdRow(buildId, major, devices))
    }
    return rows
}

/** Pulls the text of each <td>/<th> cell in html[from,to) with plain indexOf scanning (no regex backtracking). */
private fun extractCells(html: String, from: Int, to: Int): List<String> {
    val cells = ArrayList<String>(4)
    var i = from
    while (i < to) {
        val td = html.indexOf("<td", i)
        val th = html.indexOf("<th", i)
        val open = listOf(td, th).filter { it in 0 until to }.minOrNull() ?: break
        val gt = html.indexOf('>', open)
        if (gt < 0 || gt >= to) break
        val closeTd = html.indexOf("</td>", gt)
        val closeTh = html.indexOf("</th>", gt)
        val close = listOf(closeTd, closeTh).filter { it in 0..to }.minOrNull() ?: break
        cells.add(stripHtml(html, gt + 1, close))
        i = close + 5
    }
    return cells
}

/** Build-id candidates for a device + Android major, newest-first (table order). */
fun buildIdOptions(rows: List<BuildIdRow>, device: String, androidMajor: Int): List<UaOption> {
    val dev = device.trim()

    // Word-boundary match, not substring: "Google Pixel 9" must not match a row listing "Pixel".
    fun matches(d: String) = dev.equals(d, ignoreCase = true) || dev.endsWith(" $d", ignoreCase = true)
    return rows.asSequence()
            .filter { it.androidMajor == androidMajor }
            .filter { it.devices.isEmpty() || it.devices.any(::matches) }
            .map { UaOption(it.buildId, it.buildId, null) }
            .distinctBy { it.value }
            .toList()
}

private val BUILD_ID = Regex("^[A-Z][A-Z0-9]{3}\\.\\d{6}\\.\\d{3}(?:\\.[A-Z0-9]+)?$")
private val ANDROID_MAJOR = Regex("Android\\s*(\\d+)")

/** Single-pass tag strip of html[from,to): drops everything between '<' and '>', unescapes &amp;. */
private fun stripHtml(html: String, from: Int, to: Int): String {
    val sb = StringBuilder(to - from)
    var inTag = false
    for (j in from until to) {
        when (val c = html[j]) {
            '<' -> inTag = true
            '>' -> inTag = false
            else -> if (!inTag) sb.append(c)
        }
    }
    return sb.toString().replace("&amp;", "&").trim()
}
