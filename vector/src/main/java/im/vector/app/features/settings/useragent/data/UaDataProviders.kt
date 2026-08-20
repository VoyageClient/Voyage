/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent.data

import java.util.Locale

/** A source of selectable values for a fetched field. [fetch] returns options newest-first. */
interface UaDataProvider {
    val id: String

    /** The single URL this provider fetches, so the repository can drop a stale validator for it. */
    val primaryUrl: String?

    suspend fun fetch(http: UaHttp): List<UaOption>
}

/**
 * StatCounter GlobalStats CSV source. The header is the category list; the most recent complete row
 * is the current worldwide share. [map] turns a (category, share) pair into an option, or null to drop
 * it. Options that collapse to the same value (e.g. Chrome minor versions → one major) are merged with
 * their shares summed, then sorted newest-first.
 */
class StatCounterProvider(
        override val id: String,
        private val url: String,
        private val map: (category: String, share: Double) -> UaOption?,
) : UaDataProvider {

    override val primaryUrl: String get() = url

    override suspend fun fetch(http: UaHttp): List<UaOption> {
        // Only the latest month is used, but StatCounter takes ~25 s to generate the full multi-year CSV.
        // Narrowing to the last few months (fromMonthYear/toMonthYear) cuts that to ~1 s.
        val lines = http.get(url + recentMonthsRange()).lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val header = parseCsvLine(lines.first())
        // StatCounter CSV rows are NOT in chronological order, so pick the newest month by date, not the last row.
        val row = lines.drop(1)
                .map { it.split(',') }
                .filter { it.size == header.size && DATE.matches(it.first()) }
                .maxByOrNull { it.first() } ?: return emptyList()

        val raw = (1 until header.size).mapNotNull { i ->
            val share = row.getOrNull(i)?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@mapNotNull null
            map(header[i], share)
        }
        return raw.groupBy { it.value }
                .map { (value, group) ->
                    val share = group.mapNotNull { it.share }.takeIf { it.isNotEmpty() }?.sum()
                    val name = group.first().label
                    UaOption(value, if (share != null) "$name (${formatShare(share)})" else name, share)
                }
                .sortedWith(VersionSort.descending)
    }

    companion object {
        private val DATE = Regex("\\d{4}-\\d{2}")

        /** `&fromMonthYear=YYYY-MM&toMonthYear=YYYY-MM` for the last 4 months, so a partial current month still leaves data. */
        private fun recentMonthsRange(): String {
            val cal = java.util.Calendar.getInstance()
            val to = yearMonth(cal)
            cal.add(java.util.Calendar.MONTH, -3)
            val from = yearMonth(cal)
            return "&fromMonthYear=$from&toMonthYear=$to"
        }

        private fun yearMonth(cal: java.util.Calendar): String =
                String.format(Locale.US, "%04d-%02d", cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
    }
}

/** GitHub releases/tags source. Pulls a single string field per entry and keeps the API's newest-first order. */
class GithubVersionProvider(
        override val id: String,
        private val url: String,
        private val jsonField: String,
        private val extract: (String) -> String?,
) : UaDataProvider {

    override val primaryUrl: String get() = url

    override suspend fun fetch(http: UaHttp): List<UaOption> {
        val json = http.get(url)
        return Regex("\"$jsonField\"\\s*:\\s*\"([^\"]+)\"").findAll(json)
                .mapNotNull { extract(it.groupValues[1]) }
                .distinct()
                .map { UaOption(value = it, label = it, share = null) }
                .toList()
    }
}

/**
 * Pulls version keys from a JSON object, e.g. Mozilla's firefox release-history map
 * ({"154.0":"2025-…", …}). Modern Firefox hides its version from usage stats, so recency is the only
 * signal; options come back newest-major-first with no share.
 */
class JsonKeyVersionProvider(
        override val id: String,
        private val url: String,
        private val toOption: (major: String) -> UaOption,
) : UaDataProvider {
    private val key = Regex("\"(\\d+)\\.\\d+(?:\\.\\d+)?\"\\s*:")

    override val primaryUrl: String get() = url

    override suspend fun fetch(http: UaHttp): List<UaOption> =
            key.findAll(http.get(url))
                    .map { it.groupValues[1] }
                    .distinct()
                    .sortedByDescending { it.toIntOrNull() ?: 0 }
                    .take(30)
                    .map { toOption(it) }
                    .toList()
}

/**
 * Dart has no clean stable-version list source (its GitHub tags are analyzer/dev builds), so anchor on
 * the live stable version and enumerate recent minor versions. The UA only carries major.minor anyway.
 */
class DartVersionProvider(
        override val id: String,
        private val url: String,
        private val count: Int = 20,
) : UaDataProvider {
    private val version = Regex("\"version\"\\s*:\\s*\"(\\d+)\\.(\\d+)")

    override val primaryUrl: String get() = url

    override suspend fun fetch(http: UaHttp): List<UaOption> {
        val m = version.find(http.get(url)) ?: return emptyList()
        var major = m.groupValues[1].toInt()
        var minor = m.groupValues[2].toInt()
        val out = ArrayList<UaOption>(count)
        repeat(count) {
            val v = "$major.$minor"
            out.add(UaOption(v, "Dart $v", null))
            // Dart 3.x started at 3.0; 2.x ran up to 2.19.
            if (minor == 0) { major--; minor = if (major == 2) 19 else 9 } else minor--
        }
        return out
    }
}

/** Picks the default value for a field: highest usage share, or newest when there's no share data. */
fun mostPopularValue(options: List<UaOption>): String? =
        if (options.any { it.share != null }) options.maxByOrNull { it.share ?: Double.NEGATIVE_INFINITY }?.value
        else options.firstOrNull()?.value

// -- Shared parsing helpers ---------------------------------------------------------------------

internal fun parseCsvLine(line: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var inQuotes = false
    for (c in line) {
        when {
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
            else -> sb.append(c)
        }
    }
    out.add(sb.toString())
    return out
}

internal fun formatShare(share: Double): String = String.format(Locale.US, "%.1f%%", share)

/** Sorts options newest-first by comparing their values' integer groups. */
internal object VersionSort {
    private val NUM = Regex("\\d+")

    fun parts(s: String): List<Int> = NUM.findAll(s).map { it.value.toIntOrNull() ?: 0 }.toList()

    val descending: Comparator<UaOption> = Comparator { a, b ->
        val x = parts(b.value)
        val y = parts(a.value)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return@Comparator c
        }
        0
    }
}

/** Category → option mappers for the individual StatCounter/GitHub sources. */
internal object UaMappers {

    fun android(category: String, share: Double): UaOption? {
        val m = Regex("^(\\d+)(?:\\.(\\d+))?").find(category) ?: return null
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toIntOrNull() ?: 0
        // RELEASE is bare-major from Android 9 onward, major.minor before it (8.1, 7.0, …).
        val release = if (major >= 9 && minor == 0) "$major" else "$major.$minor"
        return UaOption(release, "Android $release", share)
    }

    fun chrome(category: String, share: Double): UaOption? {
        val ver = category.removePrefix("Chrome ").takeIf { it != category } ?: return null
        val major = Regex("^(\\d+)").find(ver)?.groupValues?.get(1) ?: return null
        // Modern Chrome freezes the minor/build/patch to 0.
        return UaOption("$major.0.0.0", "Chrome $major", share)
    }

    fun ios(category: String, share: Double): UaOption? {
        val ver = category.removePrefix("iOS ").takeIf { it != category } ?: return null
        if (ver.firstOrNull()?.isDigit() != true) return null
        return UaOption(ver, "iOS $ver", share)
    }

    private val MAC_NAMES = linkedMapOf(
            "sequoia" to "15_6", "sonoma" to "14_6", "ventura" to "13_6", "monterey" to "12_7",
            "big sur" to "11_7", "catalina" to "10_15_7", "mojave" to "10_14_6", "high sierra" to "10_13_6",
            "sierra" to "10_12_6", "el capitan" to "10_11_6", "yosemite" to "10_10_5", "mavericks" to "10_9_5",
    )

    fun macos(category: String, share: Double): UaOption? {
        val lower = category.lowercase(Locale.US)
        val named = MAC_NAMES.entries.firstOrNull { lower.contains(it.key) }
        val token = named?.value
                ?: Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(category)
                        ?.groupValues?.drop(1)?.filter { it.isNotEmpty() }?.joinToString("_")
                ?: return null
        return UaOption(token, category, share)
    }
}
