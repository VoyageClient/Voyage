/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.di.DefaultPreferences
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.extensions.safeOpenOutputStream
import im.vector.app.features.emoji.CustomEmojiFontStore
import im.vector.app.features.settings.AppLogoManager
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.timer.Clock
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.data.sessionId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject

class PreferencesBackupManager @Inject constructor(
        @ApplicationContext private val context: Context,
        @DefaultPreferences private val preferences: SharedPreferences,
        private val authenticationService: AuthenticationService,
        private val appLogoManager: AppLogoManager,
        private val customEmojiFontStore: CustomEmojiFontStore,
        private val coroutineDispatchers: CoroutineDispatchers,
        private val clock: Clock,
) {

    /** The document last exported to or imported from, so the next picker can open beside it. */
    var lastLocation: Uri?
        get() = preferences.getString(KEY_LAST_LOCATION, null)?.let { Uri.parse(it) }
        set(value) = preferences.edit { putString(KEY_LAST_LOCATION, value?.toString()) }

    private fun accounts(): List<BackupAccount> = authenticationService.getAllSessionParams().map {
        BackupAccount(userId = it.userId, sessionId = it.credentials.sessionId())
    }

    suspend fun signedInUserIds(): Set<String> = withContext(coroutineDispatchers.io) {
        accounts().map { it.userId }.toSet()
    }

    suspend fun exportTo(uri: Uri) = withContext(coroutineDispatchers.io) {
        val stream = context.safeOpenOutputStream(uri) ?: throw IOException("Cannot open $uri")
        stream.use { it.write(exportJson().toByteArray(Charsets.UTF_8)) }
    }

    suspend fun exportToFile(file: File) = withContext(coroutineDispatchers.io) {
        file.writeText(exportJson(), Charsets.UTF_8)
    }

    private fun exportJson(): String = PreferencesBackup.export(preferences.all, accounts(), clock.epochMillis())

    /** @throws IllegalArgumentException if the file is not a settings export. */
    suspend fun read(uri: Uri): PreferencesBackupFile = withContext(coroutineDispatchers.io) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        val bytes = stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                require(buffer.size() <= MAX_FILE_SIZE) { "Settings export too large" }
            }
            buffer.toByteArray()
        }
        PreferencesBackup.parse(String(bytes, Charsets.UTF_8))
    }

    suspend fun restore(backup: PreferencesBackupFile, restoreGlobal: Boolean, userIds: Set<String>) = withContext(coroutineDispatchers.io) {
        val changes = PreferencesBackup.restore(backup, preferences.all, restoreGlobal, userIds, accounts())
        // The font itself isn't in the export; its name alone would make the app expect a font that isn't there.
        val writes = changes.writes.filterKeys { it != VectorPreferences.SETTINGS_CUSTOM_EMOJI_FONT_NAME_KEY || customEmojiFontStore.fontFile.exists() }
        val editor = preferences.edit()
        changes.removals.forEach { editor.remove(it) }
        writes.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        // commit, not apply: the caller kills the process right after to reload every setting read at startup.
        if (!editor.commit()) throw IOException("Unable to save imported settings")
        appLogoManager.reapplyLauncherAlias()
    }

    companion object {
        const val KEY_LAST_LOCATION = "SETTINGS_BACKUP_LAST_LOCATION"
        private const val MAX_FILE_SIZE = 8 * 1024 * 1024
    }
}
