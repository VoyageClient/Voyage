/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent.data

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

private val moshi = Moshi.Builder().build()

// -- Electron (paired with the Chromium version it bundles) -------------------------------------

@JsonClass(generateAdapter = true)
data class ElectronRelease(val version: String, val chrome: String)

private val STABLE_SEMVER = Regex("""^\d+\.\d+\.\d+$""")
private fun semverParts(v: String): List<Int> = v.split(".").map { it.toIntOrNull() ?: 0 }

/**
 * Parses electron-to-chromium's full-versions.json, a compact {electronVersion: chromeVersion} map
 * (~50 KB, ~6 KB gzipped — a tenth of electron/releases lite.json). Keeps only stable x.y.z releases,
 * newest-first, capped at [limit].
 */
fun parseElectronReleases(json: String, limit: Int = 60): List<ElectronRelease> {
    val type = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    val map = runCatching { moshi.adapter<Map<String, String>>(type).fromJson(json) }.getOrNull().orEmpty()
    return map.entries.asSequence()
            .filter { STABLE_SEMVER.matches(it.key) && it.value.isNotBlank() }
            .map { ElectronRelease(it.key, it.value) }
            .sortedWith(Comparator { a, b ->
                val x = semverParts(b.version)
                val y = semverParts(a.version)
                for (i in 0 until maxOf(x.size, y.size)) {
                    val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                    if (c != 0) return@Comparator c
                }
                0
            })
            .take(limit)
            .toList()
}

// -- iOS devices (marketing names from ipsw.me) -------------------------------------------------

@JsonClass(generateAdapter = true)
internal data class IpswDevice(val name: String?, val identifier: String?)

/** Parses ipsw.me /v4/devices into iPhone/iPad marketing names, newest hardware first. */
fun parseIosDevices(json: String, limit: Int = 80): List<String> {
    val type = Types.newParameterizedType(List::class.java, IpswDevice::class.java)
    val list = runCatching { moshi.adapter<List<IpswDevice>>(type).fromJson(json) }.getOrNull().orEmpty()
    return list.asSequence()
            .filter { it.name != null && (it.identifier?.startsWith("iPhone") == true || it.identifier?.startsWith("iPad") == true) }
            .sortedWith(
                    compareByDescending<IpswDevice> { it.identifier!!.startsWith("iPhone") }
                            .thenByDescending { generation(it.identifier!!).first }
                            .thenByDescending { generation(it.identifier!!).second }
            )
            .map { it.name!! }
            .distinct()
            .take(limit)
            .toList()
}

/** "iPhone17,2" -> (17, 2); anything unparseable -> (0, 0). */
private fun generation(identifier: String): Pair<Int, Int> {
    val m = Regex("(\\d+),(\\d+)").find(identifier) ?: return 0 to 0
    return (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0)
}

/** Fetches iOS device marketing names from ipsw.me. */
class IosDeviceProvider(
        override val id: String,
        private val url: String,
) : UaDataProvider {
    override val primaryUrl: String get() = url

    override suspend fun fetch(http: UaHttp): List<UaOption> =
            parseIosDevices(http.get(url)).map { UaOption(it, it, null) }
}
