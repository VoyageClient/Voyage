/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.os.Build
import androidx.annotation.RequiresApi
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * The java.util.Locale script APIs (getScript / getDisplayScript / Locale.Builder) and
 * IllformedLocaleException only exist on API 21+. Keeping them in their own class means KitKat —
 * which only reaches these behind Build.VERSION guards — never loads or verifies this class, so it
 * can't trigger a VerifyError on the caller.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal object LocaleScriptCompat {

    fun script(locale: Locale): String = locale.script

    fun displayScript(locale: Locale, inLocale: Locale): String = locale.getDisplayScript(inLocale)

    /**
     * @return the built locale, or null when [script]/[country] form an ill-formed locale (the
     * caller rethrows in debug builds via [throwOnError]).
     */
    fun build(language: String, country: String, script: String, throwOnError: Boolean): Locale? {
        return try {
            Locale.Builder()
                    .setLanguage(language)
                    .setRegion(country)
                    .setScript(script)
                    .build()
        } catch (exception: IllformedLocaleException) {
            if (throwOnError) throw exception
            null
        }
    }
}
