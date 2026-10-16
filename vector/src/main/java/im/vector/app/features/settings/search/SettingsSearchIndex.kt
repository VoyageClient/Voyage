/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.search

import android.content.Context
import android.content.res.TypedArray
import android.os.Build
import android.util.Xml
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.VectorFeatures
import im.vector.app.features.home.NightlyProxy
import im.vector.app.features.settings.VectorPreferences
import org.xmlpull.v1.XmlPullParser
import javax.inject.Inject
import androidx.preference.R as PreferenceR

/**
 * Builds the searchable rows of every registered settings screen straight from their XML, following
 * `app:fragment` links from the root so each row knows the screens leading to it.
 */
class SettingsSearchIndex @Inject constructor(
        private val vectorPreferences: VectorPreferences,
        private val vectorFeatures: VectorFeatures,
        private val nightlyProxy: NightlyProxy,
        private val activeSessionHolder: ActiveSessionHolder,
) {

    fun build(context: Context): List<SettingsSearchEntry> {
        val entries = mutableListOf<SettingsSearchEntry>()
        walk(
                context = context,
                xmlRes = SettingsScreenRegistry.xmlFor(SettingsScreenRegistry.ROOT) ?: return entries,
                screenPath = emptyList(),
                breadcrumb = emptyList(),
                hiddenKeys = runtimeHiddenKeys(),
                visited = mutableSetOf(SettingsScreenRegistry.ROOT),
                out = entries,
        )
        return entries
    }

    // Rows their fragment hides from local state or cached server capabilities. Rows hidden from other
    // state stay listed; opening one reports that it isn't available.
    private fun runtimeHiddenKeys(): Set<String> = buildSet {
        activeSessionHolder.getSafeActiveSession()?.homeServerCapabilitiesService()?.getHomeServerCapabilities()?.let { capabilities ->
            if (!capabilities.canChangePassword) add(VectorPreferences.SETTINGS_CHANGE_PASSWORD_PREFERENCE_KEY)
            if (!capabilities.canChange3pid) add(VectorPreferences.SETTINGS_EMAILS_AND_PHONE_NUMBERS_PREFERENCE_KEY)
            if (capabilities.externalAccountManagementUrl == null) add(VectorPreferences.SETTINGS_EXTERNAL_ACCOUNT_MANAGEMENT_KEY)
            if (capabilities.delegatedOidcAuthEnabled) add("SETTINGS_DEACTIVATE_ACCOUNT_CATEGORY_KEY")
        }
        if (vectorPreferences.isNewAppLayoutEnabled()) add(VectorPreferences.SETTINGS_PREF_SPACE_CATEGORY)
        if (vectorFeatures.isNewAppLayoutFeatureEnabled()) {
            add(VectorPreferences.SETTINGS_LABS_UNREAD_NOTIFICATIONS_AS_TAB)
        } else {
            add(VectorPreferences.SETTINGS_LABS_NEW_APP_LAYOUT_KEY)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) add(VectorPreferences.SETTINGS_SECURITY_INCOGNITO_KEYBOARD_PREFERENCE_KEY)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) add("SETTINGS_TRANSLATION_LOCAL_CATEGORY")
        if (!nightlyProxy.isNightlyBuild()) add("SETTINGS_NIGHTLY_BUILD_PREFERENCE_KEY")
    }

    private fun walk(
            context: Context,
            xmlRes: Int,
            screenPath: List<String>,
            breadcrumb: List<String>,
            hiddenKeys: Set<String>,
            visited: MutableSet<String>,
            out: MutableList<SettingsSearchEntry>,
    ) {
        val rows = readRows(context, xmlRes, hiddenKeys)
        for (row in rows) {
            val crumbs = breadcrumb + row.categories
            val linked = row.fragment ?: row.key?.let { SettingsScreenRegistry.codeLinks[it] }
            out += SettingsSearchEntry(
                    title = row.title,
                    summary = row.summary,
                    breadcrumb = crumbs,
                    screenPath = if (linked != null) screenPath + linked else screenPath,
                    highlight = if (linked != null) null else SettingsSearchEntry.Highlight(row.key, row.title),
            )
            if (linked != null && visited.add(linked)) {
                val linkedXml = SettingsScreenRegistry.xmlFor(linked) ?: continue
                walk(context, linkedXml, screenPath + linked, breadcrumb + row.title, hiddenKeys, visited, out)
            }
        }
    }

    private class Row(val title: String, val summary: String?, val key: String?, val fragment: String?, val categories: List<String>)

    private fun readRows(context: Context, xmlRes: Int, hiddenKeys: Set<String>): List<Row> {
        val rows = mutableListOf<Row>()
        // Category titles of the open groups, with the depth each was opened at.
        val groups = ArrayDeque<Pair<Int, String?>>()
        var skipDepth = -1
        val parser = context.resources.getXml(xmlRes)
        try {
            val attrs = Xml.asAttributeSet(parser)
            var event = parser.next()
            while (event != XmlPullParser.END_DOCUMENT) {
                val depth = parser.depth
                if (event == XmlPullParser.START_TAG && skipDepth == -1) {
                    val a = context.obtainStyledAttributes(attrs, PreferenceR.styleable.Preference)
                    try {
                        val key = a.string(PreferenceR.styleable.Preference_android_key, PreferenceR.styleable.Preference_key)
                        val visible = a.getBoolean(PreferenceR.styleable.Preference_isPreferenceVisible, true)
                        val title = a.string(PreferenceR.styleable.Preference_android_title, PreferenceR.styleable.Preference_title)
                        when {
                            !visible || key in hiddenKeys -> skipDepth = depth
                            depth == 1 -> Unit
                            parser.name.endsWith("PreferenceCategory") || parser.name.endsWith("PreferenceCategoryWithAction") ->
                                groups.addLast(depth to title)
                            !title.isNullOrBlank() -> rows += Row(
                                    title = title,
                                    // A summary with placeholders is a template the fragment fills in at runtime.
                                    summary = a.string(PreferenceR.styleable.Preference_android_summary, PreferenceR.styleable.Preference_summary)
                                            ?.takeUnless { FORMAT_PLACEHOLDER.containsMatchIn(it) },
                                    key = key,
                                    fragment = a.string(PreferenceR.styleable.Preference_android_fragment, PreferenceR.styleable.Preference_fragment),
                                    categories = groups.mapNotNull { it.second?.takeIf(String::isNotBlank) },
                            )
                        }
                    } finally {
                        a.recycle()
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if (skipDepth == depth) {
                        skipDepth = -1
                    } else if (skipDepth == -1 && groups.lastOrNull()?.first == depth) {
                        groups.removeLast()
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return rows
    }

    private fun TypedArray.string(index: Int, fallbackIndex: Int): String? =
            (getText(index) ?: getText(fallbackIndex))?.toString()

    private companion object {
        val FORMAT_PLACEHOLDER = Regex("%(\\d+\\$)?[a-zA-Z]")
    }
}
