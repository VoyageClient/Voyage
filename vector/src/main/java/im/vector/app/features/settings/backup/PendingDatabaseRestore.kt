/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Swaps a staged database backup into place. It runs from Application.attachBaseContext, before Hilt, the SDK,
 * WorkManager or any SharedPreferences have opened the files it replaces.
 *
 * The swap is resumable: the `.ready` marker becomes `.moving` once the old data is gone, and each staged file
 * leaves the staging folder as it is moved, so a process killed halfway finishes the job on the next start.
 */
object PendingDatabaseRestore {

    const val STAGING_DIR = "database_restore"
    const val DEFAULT_PREFERENCES_PLACEHOLDER = "shared_prefs/default_preferences.xml"
    private const val READY = ".ready"
    private const val MOVING = ".moving"
    private const val TAG = "PendingDatabaseRestore"

    /** Shared preference files that belong to libraries' device state rather than the app's data. */
    val DEVICE_PREFERENCE_FILES = setOf(
            "WebViewChromiumPrefs.xml",
            "AwOriginVisitLoggerPrefs.xml",
            "MapboxSharedPreferences.xml",
    )

    private val SESSION_DIR = Regex("[0-9a-f]{32}")

    /** Top-level entries of filesDir that a backup carries, or that only make sense next to the data it replaces. */
    private val REPLACED_FILES = setOf(
            "preserved_media", "account_info", "bulk_queue", "datastore", "custom_emoji.ttf", "profile_colors.z",
            "send_cache", "media", "ShortcutInfoCompatSaver_share_targets",
    )

    fun stagingDir(dataDir: File) = File(dataDir, STAGING_DIR)

    /** Marks a fully verified staging folder for the next start. */
    fun markReady(dataDir: File) {
        File(stagingDir(dataDir), READY).writeText("")
    }

    @SuppressLint("LogNotTimber")
    fun applyIfPending(context: Context) {
        val dataDir = File(context.applicationInfo.dataDir)
        try {
            if (apply(dataDir, context.packageName)) dropKeystoreBoundSecrets(context)
        } catch (e: Exception) {
            // Timber isn't planted this early.
            Log.e(TAG, "Database restore failed", e)
        }
    }

    /** @return true if a restore was applied. */
    fun apply(dataDir: File, packageName: String): Boolean {
        val staging = stagingDir(dataDir)
        val ready = File(staging, READY)
        val moving = File(staging, MOVING)
        if (!ready.exists() && !moving.exists()) {
            // An import the process died in the middle of: nothing was replaced yet.
            if (staging.exists()) staging.deleteRecursively()
            return false
        }
        if (ready.exists()) {
            deleteReplacedData(dataDir)
            if (!ready.renameTo(moving)) moving.writeText("")
        }
        moveStaged(staging, dataDir, packageName)
        staging.deleteRecursively()
        return true
    }

    private fun deleteReplacedData(dataDir: File) {
        File(dataDir, "databases").listFiles().orEmpty()
                .filter { it.name.startsWith("matrix-sdk-auth.db") || it.name.startsWith("matrix-sdk-global.db") || it.name.startsWith("androidx.work.workdb") }
                .forEach { it.deleteRecursively() }
        // WorkManager's queue still names the replaced sessions.
        File(dataDir, "no_backup").listFiles().orEmpty().filter { it.name.startsWith("androidx.work.workdb") }.forEach { it.delete() }
        File(dataDir, "files").listFiles().orEmpty()
                .filter { it.name in REPLACED_FILES || SESSION_DIR.matches(it.name) || it.name.startsWith("image_pack_cache_") }
                .forEach { it.deleteRecursively() }
        File(dataDir, "shared_prefs").listFiles().orEmpty()
                .filter { it.name !in DEVICE_PREFERENCE_FILES }
                .forEach { it.delete() }
        File(dataDir, "cache").listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    private fun moveStaged(staging: File, dataDir: File, packageName: String) {
        staging.walkBottomUp().filter { it.isFile && it.parentFile != staging }.forEach { source ->
            val relative = source.relativeTo(staging).invariantSeparatorsPath
            val target = if (relative == DEFAULT_PREFERENCES_PLACEHOLDER) {
                File(dataDir, "shared_prefs/${packageName}_preferences.xml")
            } else {
                File(dataDir, relative)
            }
            target.parentFile?.mkdirs()
            if (target.exists()) target.deleteRecursively()
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }
    }

    // The PIN is encrypted with an Android Keystore key, which a backup can't carry to another install.
    private fun dropKeystoreBoundSecrets(context: Context) {
        context.getSharedPreferences("im.vector.riot", Context.MODE_PRIVATE).edit()
                .remove("ENCODED_PIN_CODE_KEY")
                .remove("REMAINING_PIN_CODE_ATTEMPTS_KEY")
                .remove("REMAINING_BIOMETRICS_ATTEMPTS_KEY")
                .commit()
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE).edit()
                .remove("SETTINGS_SECURITY_USE_PIN_CODE_FLAG")
                .remove("SETTINGS_SECURITY_USE_BIOMETRICS_FLAG")
                .commit()
    }
}
