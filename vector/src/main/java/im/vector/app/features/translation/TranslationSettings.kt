/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.core.di.DefaultPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslationSettings @Inject constructor(
        @DefaultPreferences private val prefs: SharedPreferences,
) {
    companion object {
        const val KEY_ENGINE = "SETTINGS_TRANSLATION_ENGINE"
        const val KEY_BACKUP_ENGINE = "SETTINGS_TRANSLATION_BACKUP_ENGINE"
        const val KEY_MICROSOFT_REGION = "SETTINGS_TRANSLATION_MICROSOFT_REGION"
        const val KEY_OAI_ENDPOINT = "SETTINGS_TRANSLATION_OAI_ENDPOINT"
        const val KEY_OAI_MODEL = "SETTINGS_TRANSLATION_OAI_MODEL"
        const val NONE = "none"
        private const val KEY_AUTO_ROOMS = "SETTINGS_TRANSLATION_AUTO_ROOMS"

        fun apiKeyPrefKey(engine: TranslationEngine) = "SETTINGS_TRANSLATION_API_KEY_${engine.id}"
    }

    /** null = translation disabled ("None"), the default below API 24 where Local can't run. */
    val engine: TranslationEngine?
        get() {
            val stored = prefs.getString(KEY_ENGINE, null)
            if (stored == NONE) return null
            val valid = TranslationEngine.fromId(stored)?.takeIf { it != TranslationEngine.LOCAL || localSupported() }
            return valid ?: if (localSupported()) TranslationEngine.LOCAL else null
        }

    private fun localSupported() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N

    val backupEngine: TranslationEngine?
        get() = TranslationEngine.fromId(prefs.getString(KEY_BACKUP_ENGINE, null))?.takeIf { it != engine }

    fun apiKey(engine: TranslationEngine): String = prefs.getString(apiKeyPrefKey(engine), null).orEmpty().trim()

    val microsoftRegion: String get() = prefs.getString(KEY_MICROSOFT_REGION, null)?.trim().orEmpty().ifEmpty { "global" }
    val oaiEndpoint: String get() = prefs.getString(KEY_OAI_ENDPOINT, null)?.trim().orEmpty().ifEmpty { TranslationEngine.OPENAI_ENDPOINT }
    val oaiModel: String get() = prefs.getString(KEY_OAI_MODEL, null)?.trim().orEmpty().ifEmpty { TranslationEngine.OPENAI_MODEL }

    /** Target language for the room's outgoing auto-translation, or null when it's off. */
    fun roomAutoTranslateTarget(roomId: String): String? =
            prefs.getStringSet(KEY_AUTO_ROOMS, emptySet()).orEmpty()
                    .firstOrNull { it == roomId || it.startsWith("$roomId ") }
                    ?.let { entry -> if (entry == roomId) TranslationLanguages.APP else entry.substringAfter(' ') }

    fun isRoomAutoTranslateEnabled(roomId: String): Boolean = roomAutoTranslateTarget(roomId) != null

    /** [target] null turns auto-translation off; [TranslationLanguages.APP] follows the app language. */
    fun setRoomAutoTranslate(roomId: String, target: String?) {
        val set = prefs.getStringSet(KEY_AUTO_ROOMS, emptySet()).orEmpty()
                .filterNot { it == roomId || it.startsWith("$roomId ") }
                .toMutableSet()
        if (target != null) set.add(if (target == TranslationLanguages.APP) roomId else "$roomId $target")
        prefs.edit { putStringSet(KEY_AUTO_ROOMS, set) }
    }
}
