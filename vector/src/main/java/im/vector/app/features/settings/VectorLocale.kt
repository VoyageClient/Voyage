/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.edit
import im.vector.app.core.di.DefaultPreferences
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.utils.safeCapitalize
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Object to manage the Locale choice of the user.
 */
@Singleton
class VectorLocale @Inject constructor(
        private val context: Context,
        private val buildMeta: BuildMeta,
        @DefaultPreferences
        private val preferences: SharedPreferences,
) {
    companion object {
        const val APPLICATION_LOCALE_COUNTRY_KEY = "APPLICATION_LOCALE_COUNTRY_KEY"
        const val APPLICATION_LOCALE_VARIANT_KEY = "APPLICATION_LOCALE_VARIANT_KEY"
        const val APPLICATION_LOCALE_LANGUAGE_KEY = "APPLICATION_LOCALE_LANGUAGE_KEY"
        private const val APPLICATION_LOCALE_SCRIPT_KEY = "APPLICATION_LOCALE_SCRIPT_KEY"
        private const val ISO_15924_LATN = "Latn"
        private const val REGIONAL_INDICATOR_A = 0x1F1E6

        // Locales whose values-* folder has no region.
        private val DEFAULT_LANGUAGE_COUNTRY = mapOf(
                "ar" to "SA",
                "bn" to "BD",
                "ur" to "PK",
        )
    }

    private val defaultLocale = Locale("en", "US")

    /**
     * The cache of supported application languages.
     */
    @Volatile
    private var supportedLocales: List<Locale> = emptyList()

    /**
     * Provides the current application locale.
     */
    var applicationLocale = defaultLocale
        private set

    /**
     * Init this singleton.
     */
    fun init() {
        if (preferences.contains(APPLICATION_LOCALE_LANGUAGE_KEY)) {
            applicationLocale = Locale(
                    preferences.getString(APPLICATION_LOCALE_LANGUAGE_KEY, "")!!,
                    preferences.getString(APPLICATION_LOCALE_COUNTRY_KEY, "")!!,
                    preferences.getString(APPLICATION_LOCALE_VARIANT_KEY, "")!!
            )
        } else {
            applicationLocale = Locale.getDefault()

            // detect if the default language is used
            val defaultStringValue = getString(context, defaultLocale, CommonStrings.resources_country_code)
            if (defaultStringValue == getString(context, applicationLocale, CommonStrings.resources_country_code)) {
                applicationLocale = defaultLocale
            }

            saveApplicationLocale(applicationLocale)
        }
    }

    /**
     * Save the new application locale.
     */
    fun saveApplicationLocale(locale: Locale) {
        applicationLocale = locale

        preferences.edit {
            val language = locale.language
            if (language.isEmpty()) {
                remove(APPLICATION_LOCALE_LANGUAGE_KEY)
            } else {
                putString(APPLICATION_LOCALE_LANGUAGE_KEY, language)
            }

            val country = locale.country
            if (country.isEmpty()) {
                remove(APPLICATION_LOCALE_COUNTRY_KEY)
            } else {
                putString(APPLICATION_LOCALE_COUNTRY_KEY, country)
            }

            val variant = locale.variant
            if (variant.isEmpty()) {
                remove(APPLICATION_LOCALE_VARIANT_KEY)
            } else {
                putString(APPLICATION_LOCALE_VARIANT_KEY, variant)
            }

            val script = scriptOf(locale)
            if (script.isEmpty()) {
                remove(APPLICATION_LOCALE_SCRIPT_KEY)
            } else {
                putString(APPLICATION_LOCALE_SCRIPT_KEY, script)
            }
        }
    }

    /**
     * Get String from a locale.
     *
     * @param context the context
     * @param locale the locale
     * @param resourceId the string resource id
     * @return the localized string
     */
    @Suppress("DEPRECATION")
    private fun getString(context: Context, locale: Locale, resourceId: Int): String {
        val config = Configuration(context.resources.configuration)
        return try {
            // Configuration.setLocale + createConfigurationContext are API 17+; pre-17 mutate the
            // resources' config, read the string, then restore.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                config.setLocale(locale)
                context.createConfigurationContext(config).getText(resourceId).toString()
            } else {
                config.locale = locale
                val res = context.resources
                val metrics = res.displayMetrics
                val original = Configuration(res.configuration)
                res.updateConfiguration(config, metrics)
                val value = res.getText(resourceId).toString()
                res.updateConfiguration(original, metrics)
                value
            }
        } catch (e: Exception) {
            Timber.e(e, "## getString() failed")
            // use the default one
            context.getString(resourceId)
        }
    }

    /**
     * Init the supported application locales list.
     */
    private fun initApplicationLocales() {
        val knownLocalesSet = HashSet<Triple<String, String, String>>()

        try {
            val availableLocales = Locale.getAvailableLocales()

            for (locale in availableLocales) {
                knownLocalesSet.add(
                        Triple(
                                getString(context, locale, CommonStrings.resources_language),
                                getString(context, locale, CommonStrings.resources_country_code),
                                getString(context, locale, CommonStrings.resources_script)
                        )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "## getApplicationLocales() : failed")
            knownLocalesSet.add(
                    Triple(
                            context.getString(CommonStrings.resources_language),
                            context.getString(CommonStrings.resources_country_code),
                            context.getString(CommonStrings.resources_script)
                    )
            )
        }

        val list = knownLocalesSet.mapNotNull { (language, country, script) ->
            // Locale.Builder / scripts are API 21+; on KitKat fall back to a plain language+country.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                LocaleScriptCompat.build(language, country, script, buildMeta.isDebug)
            } else {
                Locale(language, country)
            }
        }
                // sort by human display names
                .sortedBy { localeToLocalisedString(it).lowercase(it) }

        supportedLocales = list
    }

    /**
     * Convert a locale to a string.
     *
     * @param locale the locale to convert
     * @return the string
     */
    fun localeToLocalisedString(locale: Locale): String = describe(locale, locale)

    /**
     * Information about the locale in the current locale.
     *
     * @param locale the locale to get info from
     * @return the string
     */
    fun localeToLocalisedStringInfo(locale: Locale): String = describe(locale, Locale.getDefault())

    private fun describe(locale: Locale, inLocale: Locale): String {
        return buildString {
            append(locale.getDisplayLanguage(inLocale))

            val displayScript = displayScriptOf(locale, inLocale)
            if (needsScript(locale) && displayScript.isNotEmpty()) {
                append(" - ")
                append(displayScript)
            }

            val displayCountry = locale.getDisplayCountry(inLocale)
            if (needsCountry(locale) && displayCountry.isNotEmpty()) {
                append(" (")
                append(displayCountry)
                append(")")
            }
        }.safeCapitalize(inLocale) // Many languages lowercase language names ("español", "anglais").
    }

    // Only name the script when it tells two supported locales of the same language apart (zh Hans/Hant);
    // otherwise it just repeats the language ("العربية - العربية").
    private fun needsScript(locale: Locale): Boolean {
        val script = scriptOf(locale)
        if (script.isEmpty() || script == ISO_15924_LATN) return false
        return supportedLocales.any { it.language == locale.language && scriptOf(it) != script }
    }

    // Likewise the country only matters when we ship several regional variants of the language (en US/GB).
    private fun needsCountry(locale: Locale): Boolean {
        return supportedLocales.any { it.language == locale.language && it.country != locale.country }
    }

    /**
     * Whether [getSupportedLocales] can run without touching shared state: below API 17 the scan swaps the
     * application resources' locale from a background thread, which is only safe while nothing else is drawing.
     */
    val canLoadLocalesInBackground: Boolean
        get() = supportedLocales.isNotEmpty() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1

    /**
     * Flag emoji for the locale's country, or for the language's main country when the locale has none.
     */
    fun localeToFlagEmoji(locale: Locale): String? {
        val country = locale.country.ifEmpty { DEFAULT_LANGUAGE_COUNTRY[locale.language].orEmpty() }
        if (country.length != 2 || !country.all { it in 'A'..'Z' }) return null
        return buildString {
            country.forEach { appendCodePoint(REGIONAL_INDICATOR_A + (it - 'A')) }
        }
    }

    // Locale script APIs are API 21+. Route them through LocaleScriptCompat so this class never
    // references them directly (which would VerifyError on KitKat); that class is only loaded here
    // on API >= 21.
    private fun scriptOf(locale: Locale): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) LocaleScriptCompat.script(locale) else ""

    private fun displayScriptOf(locale: Locale, inLocale: Locale): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) LocaleScriptCompat.displayScript(locale, inLocale) else ""

    suspend fun getSupportedLocales(): List<Locale> {
        if (supportedLocales.isEmpty()) {
            // init the known locales in background
            withContext(Dispatchers.IO) {
                initApplicationLocales()
            }
        }
        return supportedLocales
    }
}
