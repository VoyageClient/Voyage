/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Provider ids, shared by the client→field mapping and the repository registry. */
object UaProviderIds {
    const val ANDROID_VERSION = "android_version"
    const val CHROME_VERSION = "chrome_version"
    const val FIREFOX_VERSION = "firefox_version"
    const val MACOS_VERSION = "macos_version"
    const val CURL_VERSION = "curl_version"
    const val EXA_APP_VERSION = "exa_app_version"
    const val SCN_APP_VERSION = "scn_app_version"
    const val LEGACY_APP_VERSION = "legacy_app_version"
    const val DEVICE_MODEL = "device_model"
    const val IOS_VERSION = "ios_version"
    const val IOS_DEVICE = "ios_device"
    const val MTXCLIENT_VERSION = "mtxclient_version"
    const val GOMUKS_VERSION = "gomuks_version"
    const val MAUTRIX_VERSION = "mautrix_version"
    const val GO_VERSION = "go_version"
    const val DART_VERSION = "dart_version"
    const val DESKTOP_APP_VERSION = "desktop_app_version"
    const val EXA_IOS_APP_VERSION = "exa_ios_app_version"
    const val IOS_CLASSIC_APP_VERSION = "ios_classic_app_version"
}

/**
 * Fetches and caches the selectable option lists for spoofing fields. Each source is fetched once and
 * stored as a JSON file under the app cache dir; the populate path reuses that file forever, and only
 * the Update button ([refresh]) re-fetches. Storing under cacheDir means the general "Clear cache"
 * wipes it too. Build ids keep the full AOSP table cached once and filter it per device + version.
 */
@Singleton
class UaDataRepository @Inject constructor(
        context: Context,
) {

    // Versioned dir: bumping it invalidates caches written by an older provider set.
    private val cacheDir = File(context.cacheDir, "useragent-v3").apply { mkdirs() }

    private val moshi = Moshi.Builder().build()
    private val optionsAdapter = moshi.adapter<List<UaOption>>(Types.newParameterizedType(List::class.java, UaOption::class.java))
    private val buildIdRowsAdapter = moshi.adapter<List<BuildIdRow>>(Types.newParameterizedType(List::class.java, BuildIdRow::class.java))
    private val electronAdapter = moshi.adapter<List<ElectronRelease>>(Types.newParameterizedType(List::class.java, ElectronRelease::class.java))
    private val validatorsAdapter = moshi.adapter<Map<String, UaValidators>>(
            Types.newParameterizedType(Map::class.java, String::class.java, UaValidators::class.java))

    // Per-URL ETag/Last-Modified store, so re-fetches ask "only if changed" and 304s skip the download.
    private val validators = HttpValidators()

    // var + internal so tests can substitute a fake HTTP layer.
    internal var http = UaHttp(validators = validators)

    // Parsed caches kept in memory: the JSON files (esp. the ~1MB device list) are far too slow to
    // re-parse on the main thread every time a field resolves. Populated by refresh (off-main) and on
    // first read. Thread-safe: read from the request interceptor and the settings UI.
    private val optionsMem = java.util.concurrent.ConcurrentHashMap<String, List<UaOption>>()
    @Volatile private var buildIdRowsMem: List<BuildIdRow>? = null
    @Volatile private var electronMem: List<ElectronRelease>? = null

    private val providers: Map<String, UaDataProvider> = listOf(
            StatCounterProvider(UaProviderIds.ANDROID_VERSION, URL_ANDROID, UaMappers::android),
            StatCounterProvider(UaProviderIds.CHROME_VERSION, URL_BROWSER, UaMappers::chrome),
            // Modern Firefox hides its version from usage stats, so use Mozilla's release history (recency).
            JsonKeyVersionProvider(UaProviderIds.FIREFOX_VERSION, URL_FIREFOX) { UaOption("$it.0", "Firefox $it", null) },
            StatCounterProvider(UaProviderIds.MACOS_VERSION, URL_MACOS, UaMappers::macos),
            GithubVersionProvider(UaProviderIds.CURL_VERSION, URL_CURL, "tag_name") { tag ->
                CURL_TAG.find(tag)?.let { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}" }
            },
            GithubVersionProvider(UaProviderIds.EXA_APP_VERSION, url("element-hq/element-x-android", "releases"), "tag_name") { stripV(it) },
            GithubVersionProvider(UaProviderIds.LEGACY_APP_VERSION, url("element-hq/element-android", "releases"), "tag_name") { stripV(it) },
            GithubVersionProvider(UaProviderIds.SCN_APP_VERSION, url("SchildiChat/schildichat-android-next", "tags"), "name") { tag ->
                tag.removePrefix("sc_v").takeIf { it != tag }
            },
            DeviceModelProvider(UaProviderIds.DEVICE_MODEL, URL_DEVICES, perBrandCap = 5000),
            StatCounterProvider(UaProviderIds.IOS_VERSION, URL_IOS, UaMappers::ios),
            IosDeviceProvider(UaProviderIds.IOS_DEVICE, URL_IOS_DEVICES),
            GithubVersionProvider(UaProviderIds.MTXCLIENT_VERSION, url("Nheko-Reborn/mtxclient", "tags"), "name") { stripV(it) },
            GithubVersionProvider(UaProviderIds.GOMUKS_VERSION, url("gomuks/gomuks", "releases"), "tag_name") { stripV(it) },
            GithubVersionProvider(UaProviderIds.MAUTRIX_VERSION, url("mautrix/go", "tags"), "name") { stripV(it) },
            GithubVersionProvider(UaProviderIds.GO_VERSION, "https://go.dev/dl/?mode=json", "version") { v ->
                v.removePrefix("go").takeIf { it != v && GO_STABLE.matches(v) }
            },
            DartVersionProvider(UaProviderIds.DART_VERSION, URL_DART),
            GithubVersionProvider(UaProviderIds.DESKTOP_APP_VERSION, url("element-hq/element-desktop", "releases"), "tag_name") { cleanSemver(it) },
            GithubVersionProvider(UaProviderIds.IOS_CLASSIC_APP_VERSION, url("element-hq/element-ios", "releases"), "tag_name") { cleanSemver(it) },
            GithubVersionProvider(UaProviderIds.EXA_IOS_APP_VERSION, url("element-hq/element-x-ios", "releases"), "tag_name") { tag ->
                tag.removePrefix("release/").takeIf { it != tag && it.firstOrNull()?.isDigit() == true }
            },
    ).associateBy { it.id }

    fun hasProvider(providerId: String?): Boolean = providerId != null && providers.containsKey(providerId)

    /** Every provider id, for the "download everything" gate. */
    fun allProviderIds(): Set<String> = providers.keys

    /** True once a source has been fetched and cached, so the populate path can skip the network. */
    fun hasCache(providerId: String): Boolean = fileFor(providerId).let { it.exists() && it.length() > 0 }
    fun hasBuildIdsCache(): Boolean = fileFor(BUILD_ID_CACHE).let { it.exists() && it.length() > 0 }
    fun hasElectronCache(): Boolean = fileFor(ELECTRON_CACHE).let { it.exists() && it.length() > 0 }

    fun cached(providerId: String): List<UaOption> = optionsMem.getOrPut(providerId) {
        readFile(providerId)?.let { runCatching { optionsAdapter.fromJson(it) }.getOrNull() }.orEmpty()
    }

    /**
     * Fetch live and update the cache. Returns the fresh list; on a 304 it returns the existing cache
     * (nothing downloaded), and on failure it returns empty (cache untouched).
     */
    suspend fun refresh(providerId: String): List<UaOption> = withContext(Dispatchers.IO) {
        val provider = providers[providerId] ?: return@withContext emptyList()
        try {
            val options = provider.fetch(http)
            Timber.i("UA refresh %s -> %d options", providerId, options.size)
            if (options.isNotEmpty()) {
                writeCache(providerId, optionsAdapter.toJson(options))
                optionsMem[providerId] = options
            } else {
                // A 200 that parses to nothing: drop the validator so we don't 304 into a stranded-empty
                // source next time (the parse, not the content, failed).
                provider.primaryUrl?.let { validators.invalidate(it) }
            }
            options.ifEmpty { cached(providerId) }
        } catch (notModified: UaNotModified) {
            Timber.i("UA refresh %s -> up to date", providerId)
            // A 304 with no cache to fall back on means the validator outlived its data (partial cache
            // eviction, or a prior failed parse): forget it so the next refresh downloads in full.
            cached(providerId).also { if (it.isEmpty()) provider.primaryUrl?.let(validators::invalidate) }
        } catch (t: Throwable) {
            Timber.w(t, "UA refresh failed: $providerId")
            emptyList()
        }
    }

    private fun cachedBuildIdRows(): List<BuildIdRow> = buildIdRowsMem ?: run {
        val rows = readFile(BUILD_ID_CACHE)?.let { runCatching { buildIdRowsAdapter.fromJson(it) }.getOrNull() }.orEmpty()
        buildIdRowsMem = rows
        rows
    }

    fun cachedBuildIds(device: String, androidMajor: Int): List<UaOption> =
            buildIdOptions(cachedBuildIdRows(), device, androidMajor)

    suspend fun refreshBuildIds(device: String, androidMajor: Int): List<UaOption> = withContext(Dispatchers.IO) {
        // The AOSP table changes ~monthly and source.android.com is slow/flaky with no 304 support, so
        // don't re-fetch it if we grabbed it recently — that avoids hanging the download on that server.
        val cacheFile = fileFor(BUILD_ID_CACHE)
        val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
        if (cacheFile.exists() && cacheFile.length() > 0 && ageMs in 0 until BUILD_ID_TTL_MS) {
            Timber.i("UA build-id: cache is %d h old, skipping fetch", ageMs / 3_600_000)
            return@withContext buildIdOptions(cachedBuildIdRows(), device, androidMajor)
        }
        val rows = try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val html = http.get(URL_BUILD_NUMBERS, impatient = true)
            val t1 = android.os.SystemClock.elapsedRealtime()
            val parsed = parseBuildIdTable(html)
            Timber.i("UA build-id: fetch=%d ms parse=%d ms rows=%d", t1 - t0, android.os.SystemClock.elapsedRealtime() - t1, parsed.size)
            if (parsed.isNotEmpty()) {
                writeCache(BUILD_ID_CACHE, buildIdRowsAdapter.toJson(parsed))
                buildIdRowsMem = parsed
                parsed
            } else {
                cachedBuildIdRows()
            }
        } catch (notModified: UaNotModified) {
            cachedBuildIdRows()
        } catch (t: Throwable) {
            Timber.w(t, "UA build-id refresh failed")
            return@withContext buildIdOptions(cachedBuildIdRows(), device, androidMajor)
        }
        buildIdOptions(rows, device, androidMajor)
    }

    fun cachedElectron(): List<ElectronRelease> = electronMem ?: run {
        val releases = readFile(ELECTRON_CACHE)?.let { runCatching { electronAdapter.fromJson(it) }.getOrNull() }.orEmpty()
        electronMem = releases
        releases
    }

    suspend fun refreshElectron(): List<ElectronRelease> = withContext(Dispatchers.IO) {
        try {
            val releases = parseElectronReleases(http.get(URL_ELECTRON))
            if (releases.isNotEmpty()) {
                writeCache(ELECTRON_CACHE, electronAdapter.toJson(releases))
                electronMem = releases
            }
            releases.ifEmpty { cachedElectron() }
        } catch (notModified: UaNotModified) {
            cachedElectron()
        } catch (t: Throwable) {
            Timber.w(t, "UA electron refresh failed")
            emptyList()
        }
    }

    /**
     * Resolves the exact matrix-rust-sdk short sha an app release shipped with, via
     * app-tag libs.versions.toml → components-kotlin release body. Null if any step fails.
     */
    suspend fun resolveSdkSha(appRepo: String, tag: String, componentsRepo: String, releasePrefix: String): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    // These URLs are immutable (pinned to a tag), so conditional requests give nothing and a
                    // 304 would only strand resolution — fetch unconditionally.
                    val toml = http.get("https://raw.githubusercontent.com/$appRepo/$tag/gradle/libs.versions.toml", conditional = false)
                    val version = SDK_ANDROID_PIN.find(toml)?.groupValues?.get(1) ?: return@runCatching null
                    val body = http.get("https://api.github.com/repos/$componentsRepo/releases/tags/$releasePrefix$version", conditional = false)
                    SHA_LINK.find(body)?.groupValues?.get(1)?.take(9)
                }.getOrNull()
            }

    private fun fileFor(key: String) = File(cacheDir, "$key.json")

    private fun readFile(key: String): String? =
            fileFor(key).takeIf { it.exists() && it.length() > 0 }?.let { runCatching { it.readText() }.getOrNull() }

    private fun writeCache(key: String, json: String) {
        runCatching { fileFor(key).writeText(json) }.onFailure { Timber.w(it, "UA cache write failed: $key") }
    }

    /** URL → validators, backed by a single JSON file in the same cache dir (cleared by "Clear cache"). */
    private inner class HttpValidators : UaValidatorStore {
        private val map = java.util.concurrent.ConcurrentHashMap<String, UaValidators>()
        @Volatile private var loaded = false

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                readFile(VALIDATORS_CACHE)?.let { runCatching { validatorsAdapter.fromJson(it) }.getOrNull() }?.let { map.putAll(it) }
                loaded = true
            }
        }

        override fun load(url: String): UaValidators? {
            ensureLoaded()
            return map[url]
        }

        override fun save(url: String, validators: UaValidators) {
            if (validators.etag == null && validators.lastModified == null) return
            ensureLoaded()
            map[url] = validators
            persist()
        }

        override fun invalidate(url: String) {
            ensureLoaded()
            if (map.remove(url) != null) persist()
        }

        private fun persist() = synchronized(this) { writeCache(VALIDATORS_CACHE, validatorsAdapter.toJson(map)) }
    }

    companion object {
        private const val BUILD_ID_CACHE = "buildid_table"
        private const val ELECTRON_CACHE = "electron"
        private const val VALIDATORS_CACHE = "http_validators"
        private const val BUILD_ID_TTL_MS = 7L * 24 * 3_600_000
        private val CURL_TAG = Regex("^curl-(\\d+)_(\\d+)_(\\d+)$")
        private val SHA_LINK = Regex("matrix-rust-sdk/tree/([0-9a-f]{7,40})")
        private val SDK_ANDROID_PIN = Regex("sdk-android:([^\"]+)\"")
        private val GO_STABLE = Regex("^go\\d+\\.\\d+(?:\\.\\d+)?$")

        private fun url(repo: String, kind: String) = "https://api.github.com/repos/$repo/$kind?per_page=40"
        private fun stripV(tag: String) = tag.removePrefix("v").takeIf { it.firstOrNull()?.isDigit() == true }
        private val CLEAN_SEMVER = Regex("^v?(\\d+\\.\\d+\\.\\d+)$")
        private fun cleanSemver(tag: String) = CLEAN_SEMVER.find(tag)?.groupValues?.get(1)

        private const val URL_ANDROID = "https://gs.statcounter.com/android-version-market-share/mobile-tablet/" +
                "worldwide/chart.php?device=Mobile%20%26%20Tablet&device_hidden=mobile%2Btablet&" +
                "statType_hidden=android_version&region_hidden=ww&granularity=monthly&statType=Android+Version&region=Worldwide&csv=1"
        private const val URL_BROWSER = "https://gs.statcounter.com/browser-version-market-share/desktop/" +
                "worldwide/chart.php?device=Desktop&device_hidden=desktop&statType_hidden=browser_version&" +
                "region_hidden=ww&granularity=monthly&statType=Browser+Version&region=Worldwide&csv=1"
        private const val URL_MACOS = "https://gs.statcounter.com/macos-version-market-share/desktop/" +
                "worldwide/chart.php?device=Desktop&device_hidden=desktop&statType_hidden=macos_version&" +
                "region_hidden=ww&granularity=monthly&statType=macOS+Version&region=Worldwide&csv=1"
        private const val URL_CURL = "https://api.github.com/repos/curl/curl/releases?per_page=40"
        private const val URL_FIREFOX = "https://product-details.mozilla.org/1.0/firefox_history_major_releases.json"
        private const val URL_DART = "https://storage.googleapis.com/dart-archive/channels/stable/release/latest/VERSION"
        private const val URL_IOS = "https://gs.statcounter.com/ios-version-market-share/mobile-tablet/" +
                "worldwide/chart.php?device=Mobile%20%26%20Tablet&device_hidden=mobile%2Btablet&" +
                "statType_hidden=ios_version&region_hidden=ww&granularity=monthly&statType=iOS+Version&region=Worldwide&csv=1"
        private const val URL_IOS_DEVICES = "https://api.ipsw.me/v4/devices"

        // electron-to-chromium's compact {electronVersion: chromeVersion} map: ~50 KB vs lite.json's ~1 MB.
        private const val URL_ELECTRON = "https://raw.githubusercontent.com/Kilian/electron-to-chromium/master/full-versions.json"
        private const val URL_DEVICES = "https://storage.googleapis.com/play_public/supported_devices.csv"
        private const val URL_BUILD_NUMBERS = "https://source.android.com/docs/setup/reference/build-numbers"
    }
}
