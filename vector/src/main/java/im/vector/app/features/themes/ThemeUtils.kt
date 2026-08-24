/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.themes

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.PreferenceManager
import im.vector.lib.ui.styles.R
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * Util class for managing themes.
 *
 * SchildiChat additions: the SC theme variants (sc_*) plus a single accent ([SETTINGS_SC_ACCENT_LIGHT]).
 */
object ThemeUtils {
    // preference keys
    const val APPLICATION_THEME_KEY = "APPLICATION_THEME_KEY"
    const val SETTINGS_SC_ACCENT_LIGHT = "SETTINGS_SC_ACCENT_LIGHT"

    // the theme possible values
    private const val SYSTEM_THEME_VALUE = "system"
    private const val THEME_DARK_VALUE = "dark"
    private const val THEME_LIGHT_VALUE = "light"
    private const val THEME_BLACK_VALUE = "black"
    private const val THEME_SC_LIGHT_VALUE = "sc_light"
    private const val THEME_SC_VALUE = "sc"
    private const val THEME_SC_DARK_VALUE = "sc_dark"
    private const val THEME_SC_COLORED_VALUE = "sc_colored"
    private const val THEME_SC_DARK_COLORED_VALUE = "sc_dark_colored"

    private const val DEFAULT_THEME = THEME_DARK_VALUE
    private const val DEFAULT_ACCENT = "cyan"

    private var currentTheme = AtomicReference<String>(null)
    private var currentThemeAccent = AtomicReference<String>(null)

    private val mColorByAttr = HashMap<Int, Int>()

    /** Bumped on every application-theme change, so caches holding resolved colors can drop stale entries. */
    @Volatile
    var themeGeneration = 0
        private set

    // Colors are resolved against this freshly-built theme rather than a Context's theme. Context.setTheme
    // is cumulative (applyStyle never resets), so a live theme swap leaves stale attrs from the previous
    // theme on the app context; rebuilding from scratch each change keeps resolution accurate without a
    // process restart.
    private var themeReference: Resources.Theme? = null

    // init the theme
    fun init(context: Context) {
        setApplicationTheme(context, getApplicationTheme(context), getApplicationThemeAccent(context))
    }

    fun isSystemTheme(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        return false
    }

    fun isLightTheme(context: Context): Boolean {
        return when (getApplicationTheme(context)) {
            THEME_LIGHT_VALUE,
            THEME_SC_LIGHT_VALUE -> true
            else -> false
        }
    }

    /**
     * @return true if current theme is the pure black theme (darker than dark).
     */
    fun isBlackTheme(context: Context): Boolean {
        return when (getApplicationTheme(context)) {
            THEME_BLACK_VALUE,
            THEME_SC_VALUE,
            THEME_SC_COLORED_VALUE -> true
            else -> false
        }
    }

    fun getApplicationTheme(context: Context): String {
        val current = this.currentTheme.get()
        return if (current == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            var themeFromPref = prefs.getString(APPLICATION_THEME_KEY, DEFAULT_THEME) ?: DEFAULT_THEME
            if (themeFromPref == SYSTEM_THEME_VALUE || themeFromPref == "status") {
                // Legacy "system"/"status" theme no longer exists: fall back to the default theme.
                themeFromPref = DEFAULT_THEME
                prefs.edit { putString(APPLICATION_THEME_KEY, DEFAULT_THEME) }
            }
            this.currentTheme.set(themeFromPref)
            themeFromPref
        } else {
            current
        }
    }

    fun getApplicationThemeAccent(context: Context): String {
        val currentAccent = this.currentThemeAccent.get()
        return if (currentAccent == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val accentFromPref = prefs.getString(SETTINGS_SC_ACCENT_LIGHT, DEFAULT_ACCENT) ?: DEFAULT_ACCENT
            this.currentThemeAccent.set(accentFromPref)
            accentFromPref
        } else {
            currentAccent
        }
    }

    fun setApplicationTheme(context: Context, aTheme: String, aAccent: String) {
        currentTheme.set(aTheme)
        currentThemeAccent.set(aAccent)
        val themeRes = themeToRes(aTheme, aAccent)
        context.setTheme(themeRes)

        themeReference = context.applicationContext.resources.newTheme().apply {
            applyStyle(themeRes, true)
        }

        // Clear the cache
        mColorByAttr.clear()
        themeGeneration++
    }

    /** Convenience for the theme picker. */
    fun setApplicationTheme(context: Context, theme: String) {
        setApplicationTheme(context, theme, getApplicationThemeAccent(context))
    }

    fun setApplicationThemeAccent(context: Context, themeAccent: String) {
        setApplicationTheme(context, getApplicationTheme(context), themeAccent)
    }

    @StyleRes
    fun getApplicationThemeRes(context: Context) =
            themeToRes(getApplicationTheme(context), getApplicationThemeAccent(context))

    /**
     * Set the activity theme according to the selected one.
     */
    fun setActivityTheme(activity: Activity, otherThemes: ActivityOtherThemes) {
        val accent = getApplicationThemeAccent(activity)
        when (getApplicationTheme(activity)) {
            THEME_LIGHT_VALUE -> activity.setTheme(getAccentedThemeRes(otherThemes.light, accent))
            THEME_DARK_VALUE -> activity.setTheme(getAccentedThemeRes(otherThemes.dark, accent))
            THEME_BLACK_VALUE -> activity.setTheme(getAccentedThemeRes(otherThemes.black, accent))
            THEME_SC_LIGHT_VALUE -> activity.setTheme(getAccentedThemeRes(otherThemes.sc_light, accent))
            THEME_SC_VALUE -> activity.setTheme(getAccentedThemeRes(otherThemes.sc, accent))
            THEME_SC_DARK_VALUE -> activity.setTheme(getAccentedThemeRes(otherThemes.sc_dark, accent))
            THEME_SC_COLORED_VALUE -> activity.setTheme(getAccentedThemeRes(otherThemes.sc_colored, accent))
            THEME_SC_DARK_COLORED_VALUE -> activity.setTheme(getAccentedThemeRes(otherThemes.sc_dark_colored, accent))
        }

        mColorByAttr.clear()
    }

    @ColorInt
    fun getColor(c: Context, @AttrRes colorAttribute: Int): Int {
        return mColorByAttr.getOrPut(colorAttribute) {
            try {
                val color = TypedValue()
                (themeReference ?: c.theme).resolveAttribute(colorAttribute, color, true)
                color.data
            } catch (e: Exception) {
                Timber.e(e, "Unable to get color")
                ContextCompat.getColor(c, android.R.color.holo_red_dark)
            }
        }
    }

    /**
     * Resolves against the given context's own theme instead of the application one. Screens that
     * are dark whatever the app theme (the attachment preview, the editors, the viewer) need this:
     * [getColor] answers from the configured application theme, so under a light theme it hands them
     * colours meant for a light background.
     */
    @ColorInt
    fun getColorFromContextTheme(c: Context, @AttrRes colorAttribute: Int): Int {
        val color = TypedValue()
        return if (c.theme.resolveAttribute(colorAttribute, color, true)) color.data else getColor(c, colorAttribute)
    }

    /** The white and black accents, whose fill is a literal colour rather than a theme-relative one. */
    fun isMonochromeAccent(context: Context): Boolean =
            getApplicationThemeAccent(context).let { it == "white" || it == "black" }

    /**
     * Fill and content colours for an accent-filled button drawn on an always-black surface. Both
     * monochrome accents render white-on-black there: a black fill would disappear into the backdrop.
     */
    fun accentFillOnDarkSurface(c: Context): Pair<Int, Int> = if (isMonochromeAccent(c)) {
        Color.WHITE to Color.BLACK
    } else {
        getColorFromContextTheme(c, R.attr.vctr_accent_fill) to getColorFromContextTheme(c, R.attr.vctr_content_on_accent)
    }

    fun getAttribute(c: Context, @AttrRes attribute: Int): TypedValue? {
        try {
            val typedValue = TypedValue()
            c.theme.resolveAttribute(attribute, typedValue, true)
            return typedValue
        } catch (e: Exception) {
            Timber.e(e, "Unable to get color")
        }
        return null
    }

    fun tintDrawable(context: Context, drawable: Drawable, @AttrRes attribute: Int): Drawable {
        return tintDrawableWithColor(drawable, getColor(context, attribute))
    }

    fun tintDrawableWithColor(drawable: Drawable, @ColorInt color: Int): Drawable {
        val tinted = DrawableCompat.wrap(drawable)
        drawable.mutate()
        DrawableCompat.setTint(tinted, color)
        return tinted
    }

    @StyleRes
    private fun themeToRes(theme: String, accent: String): Int =
            when (theme) {
                THEME_LIGHT_VALUE -> getAccentedThemeRes(R.style.Theme_Vector_Light, accent)
                THEME_DARK_VALUE -> getAccentedThemeRes(R.style.Theme_Vector_Dark, accent)
                THEME_BLACK_VALUE -> getAccentedThemeRes(R.style.Theme_Vector_Black, accent)
                THEME_SC_LIGHT_VALUE -> getAccentedThemeRes(R.style.AppTheme_SC_Light, accent)
                THEME_SC_VALUE -> getAccentedThemeRes(R.style.AppTheme_SC, accent)
                THEME_SC_DARK_VALUE -> getAccentedThemeRes(R.style.AppTheme_SC_Dark, accent)
                THEME_SC_COLORED_VALUE -> getAccentedThemeRes(R.style.AppTheme_SC_Colored, accent)
                THEME_SC_DARK_COLORED_VALUE -> getAccentedThemeRes(R.style.AppTheme_SC_Dark_Colored, accent)
                else -> R.style.Theme_Vector_Light
            }

    @StyleRes
    private fun getAccentedThemeRes(@StyleRes resId: Int, themeAccent: String): Int {
        return when (resId) {
            R.style.AppTheme_SC_Light -> when (themeAccent) {
                "bluelight" -> R.style.AppTheme_SC_Light_BlueLight
                "amber" -> R.style.AppTheme_SC_Light_Amber
                "cyan" -> R.style.AppTheme_SC_Light_Cyan
                "gold" -> R.style.AppTheme_SC_Light_Gold
                "lime" -> R.style.AppTheme_SC_Light_Lime
                "orange" -> R.style.AppTheme_SC_Light_Orange
                "pink" -> R.style.AppTheme_SC_Light_Pink
                "purple" -> R.style.AppTheme_SC_Light_Purple
                "red" -> R.style.AppTheme_SC_Light_Red
                "teal" -> R.style.AppTheme_SC_Light_Teal
                "turquoise" -> R.style.AppTheme_SC_Light_Turquoise
                "yellow" -> R.style.AppTheme_SC_Light_Yellow
                "carnation" -> R.style.AppTheme_SC_Light_Carnation
                "denim" -> R.style.AppTheme_SC_Light_Denim
                "indigo" -> R.style.AppTheme_SC_Light_Indigo
                "lava" -> R.style.AppTheme_SC_Light_Lava
                "blue" -> R.style.AppTheme_SC_Light_Blue
                "greendark" -> R.style.AppTheme_SC_Light_GreenDark
                "element" -> R.style.AppTheme_SC_Light_Element
                "vibecoder" -> R.style.AppTheme_SC_Light_Vibecoder
                "peach" -> R.style.AppTheme_SC_Light_Peach
                "slate" -> R.style.AppTheme_SC_Light_Slate
                "rose" -> R.style.AppTheme_SC_Light_Rose
                "magenta" -> R.style.AppTheme_SC_Light_Magenta
                "violet" -> R.style.AppTheme_SC_Light_Violet
                "lavender" -> R.style.AppTheme_SC_Light_Lavender
                "periwinkle" -> R.style.AppTheme_SC_Light_Periwinkle
                "white" -> R.style.AppTheme_SC_Light_White
                "black" -> R.style.AppTheme_SC_Light_Black
                else -> resId
            }
            R.style.AppTheme_SC -> when (themeAccent) {
                "bluelight" -> R.style.AppTheme_SC_BlueLight
                "amber" -> R.style.AppTheme_SC_Amber
                "cyan" -> R.style.AppTheme_SC_Cyan
                "gold" -> R.style.AppTheme_SC_Gold
                "lime" -> R.style.AppTheme_SC_Lime
                "orange" -> R.style.AppTheme_SC_Orange
                "pink" -> R.style.AppTheme_SC_Pink
                "purple" -> R.style.AppTheme_SC_Purple
                "red" -> R.style.AppTheme_SC_Red
                "teal" -> R.style.AppTheme_SC_Teal
                "turquoise" -> R.style.AppTheme_SC_Turquoise
                "yellow" -> R.style.AppTheme_SC_Yellow
                "carnation" -> R.style.AppTheme_SC_Carnation
                "denim" -> R.style.AppTheme_SC_Denim
                "indigo" -> R.style.AppTheme_SC_Indigo
                "lava" -> R.style.AppTheme_SC_Lava
                "blue" -> R.style.AppTheme_SC_Blue
                "greendark" -> R.style.AppTheme_SC_GreenDark
                "element" -> R.style.AppTheme_SC_Element
                "vibecoder" -> R.style.AppTheme_SC_Vibecoder
                "peach" -> R.style.AppTheme_SC_Peach
                "slate" -> R.style.AppTheme_SC_Slate
                "rose" -> R.style.AppTheme_SC_Rose
                "magenta" -> R.style.AppTheme_SC_Magenta
                "violet" -> R.style.AppTheme_SC_Violet
                "lavender" -> R.style.AppTheme_SC_Lavender
                "periwinkle" -> R.style.AppTheme_SC_Periwinkle
                "white" -> R.style.AppTheme_SC_White
                "black" -> R.style.AppTheme_SC_Black
                else -> resId
            }
            R.style.AppTheme_SC_Dark -> when (themeAccent) {
                "bluelight" -> R.style.AppTheme_SC_Dark_BlueLight
                "amber" -> R.style.AppTheme_SC_Dark_Amber
                "cyan" -> R.style.AppTheme_SC_Dark_Cyan
                "gold" -> R.style.AppTheme_SC_Dark_Gold
                "lime" -> R.style.AppTheme_SC_Dark_Lime
                "orange" -> R.style.AppTheme_SC_Dark_Orange
                "pink" -> R.style.AppTheme_SC_Dark_Pink
                "purple" -> R.style.AppTheme_SC_Dark_Purple
                "red" -> R.style.AppTheme_SC_Dark_Red
                "teal" -> R.style.AppTheme_SC_Dark_Teal
                "turquoise" -> R.style.AppTheme_SC_Dark_Turquoise
                "yellow" -> R.style.AppTheme_SC_Dark_Yellow
                "carnation" -> R.style.AppTheme_SC_Dark_Carnation
                "denim" -> R.style.AppTheme_SC_Dark_Denim
                "indigo" -> R.style.AppTheme_SC_Dark_Indigo
                "lava" -> R.style.AppTheme_SC_Dark_Lava
                "blue" -> R.style.AppTheme_SC_Dark_Blue
                "greendark" -> R.style.AppTheme_SC_Dark_GreenDark
                "element" -> R.style.AppTheme_SC_Dark_Element
                "vibecoder" -> R.style.AppTheme_SC_Dark_Vibecoder
                "peach" -> R.style.AppTheme_SC_Dark_Peach
                "slate" -> R.style.AppTheme_SC_Dark_Slate
                "rose" -> R.style.AppTheme_SC_Dark_Rose
                "magenta" -> R.style.AppTheme_SC_Dark_Magenta
                "violet" -> R.style.AppTheme_SC_Dark_Violet
                "lavender" -> R.style.AppTheme_SC_Dark_Lavender
                "periwinkle" -> R.style.AppTheme_SC_Dark_Periwinkle
                "white" -> R.style.AppTheme_SC_Dark_White
                "black" -> R.style.AppTheme_SC_Dark_Black
                else -> resId
            }
            R.style.AppTheme_Transparent_SC -> when (themeAccent) {
                "bluelight" -> R.style.AppTheme_Transparent_SC_BlueLight
                "amber" -> R.style.AppTheme_Transparent_SC_Amber
                "cyan" -> R.style.AppTheme_Transparent_SC_Cyan
                "gold" -> R.style.AppTheme_Transparent_SC_Gold
                "lime" -> R.style.AppTheme_Transparent_SC_Lime
                "orange" -> R.style.AppTheme_Transparent_SC_Orange
                "pink" -> R.style.AppTheme_Transparent_SC_Pink
                "purple" -> R.style.AppTheme_Transparent_SC_Purple
                "red" -> R.style.AppTheme_Transparent_SC_Red
                "teal" -> R.style.AppTheme_Transparent_SC_Teal
                "turquoise" -> R.style.AppTheme_Transparent_SC_Turquoise
                "yellow" -> R.style.AppTheme_Transparent_SC_Yellow
                "carnation" -> R.style.AppTheme_Transparent_SC_Carnation
                "denim" -> R.style.AppTheme_Transparent_SC_Denim
                "indigo" -> R.style.AppTheme_Transparent_SC_Indigo
                "lava" -> R.style.AppTheme_Transparent_SC_Lava
                "blue" -> R.style.AppTheme_Transparent_SC_Blue
                "greendark" -> R.style.AppTheme_Transparent_SC_GreenDark
                "element" -> R.style.AppTheme_Transparent_SC_Element
                "vibecoder" -> R.style.AppTheme_Transparent_SC_Vibecoder
                "peach" -> R.style.AppTheme_Transparent_SC_Peach
                "slate" -> R.style.AppTheme_Transparent_SC_Slate
                "rose" -> R.style.AppTheme_Transparent_SC_Rose
                "magenta" -> R.style.AppTheme_Transparent_SC_Magenta
                "violet" -> R.style.AppTheme_Transparent_SC_Violet
                "lavender" -> R.style.AppTheme_Transparent_SC_Lavender
                "periwinkle" -> R.style.AppTheme_Transparent_SC_Periwinkle
                "white" -> R.style.AppTheme_Transparent_SC_White
                "black" -> R.style.AppTheme_Transparent_SC_Black
                else -> resId
            }
            R.style.AppTheme_SC_Colored -> when (themeAccent) {
                "bluelight" -> R.style.AppTheme_SC_Colored_BlueLight
                "amber" -> R.style.AppTheme_SC_Colored_Amber
                "cyan" -> R.style.AppTheme_SC_Colored_Cyan
                "gold" -> R.style.AppTheme_SC_Colored_Gold
                "lime" -> R.style.AppTheme_SC_Colored_Lime
                "orange" -> R.style.AppTheme_SC_Colored_Orange
                "pink" -> R.style.AppTheme_SC_Colored_Pink
                "purple" -> R.style.AppTheme_SC_Colored_Purple
                "red" -> R.style.AppTheme_SC_Colored_Red
                "teal" -> R.style.AppTheme_SC_Colored_Teal
                "turquoise" -> R.style.AppTheme_SC_Colored_Turquoise
                "yellow" -> R.style.AppTheme_SC_Colored_Yellow
                "carnation" -> R.style.AppTheme_SC_Colored_Carnation
                "denim" -> R.style.AppTheme_SC_Colored_Denim
                "indigo" -> R.style.AppTheme_SC_Colored_Indigo
                "lava" -> R.style.AppTheme_SC_Colored_Lava
                "blue" -> R.style.AppTheme_SC_Colored_Blue
                "greendark" -> R.style.AppTheme_SC_Colored_GreenDark
                "element" -> R.style.AppTheme_SC_Colored_Element
                "vibecoder" -> R.style.AppTheme_SC_Colored_Vibecoder
                "peach" -> R.style.AppTheme_SC_Colored_Peach
                "slate" -> R.style.AppTheme_SC_Colored_Slate
                "rose" -> R.style.AppTheme_SC_Colored_Rose
                "magenta" -> R.style.AppTheme_SC_Colored_Magenta
                "violet" -> R.style.AppTheme_SC_Colored_Violet
                "lavender" -> R.style.AppTheme_SC_Colored_Lavender
                "periwinkle" -> R.style.AppTheme_SC_Colored_Periwinkle
                "white" -> R.style.AppTheme_SC_Colored_White
                "black" -> R.style.AppTheme_SC_Colored_Black
                else -> resId
            }
            R.style.AppTheme_SC_Dark_Colored -> when (themeAccent) {
                "bluelight" -> R.style.AppTheme_SC_Dark_Colored_BlueLight
                "amber" -> R.style.AppTheme_SC_Dark_Colored_Amber
                "cyan" -> R.style.AppTheme_SC_Dark_Colored_Cyan
                "gold" -> R.style.AppTheme_SC_Dark_Colored_Gold
                "lime" -> R.style.AppTheme_SC_Dark_Colored_Lime
                "orange" -> R.style.AppTheme_SC_Dark_Colored_Orange
                "pink" -> R.style.AppTheme_SC_Dark_Colored_Pink
                "purple" -> R.style.AppTheme_SC_Dark_Colored_Purple
                "red" -> R.style.AppTheme_SC_Dark_Colored_Red
                "teal" -> R.style.AppTheme_SC_Dark_Colored_Teal
                "turquoise" -> R.style.AppTheme_SC_Dark_Colored_Turquoise
                "yellow" -> R.style.AppTheme_SC_Dark_Colored_Yellow
                "carnation" -> R.style.AppTheme_SC_Dark_Colored_Carnation
                "denim" -> R.style.AppTheme_SC_Dark_Colored_Denim
                "indigo" -> R.style.AppTheme_SC_Dark_Colored_Indigo
                "lava" -> R.style.AppTheme_SC_Dark_Colored_Lava
                "blue" -> R.style.AppTheme_SC_Dark_Colored_Blue
                "greendark" -> R.style.AppTheme_SC_Dark_Colored_GreenDark
                "element" -> R.style.AppTheme_SC_Dark_Colored_Element
                "vibecoder" -> R.style.AppTheme_SC_Dark_Colored_Vibecoder
                "peach" -> R.style.AppTheme_SC_Dark_Colored_Peach
                "slate" -> R.style.AppTheme_SC_Dark_Colored_Slate
                "rose" -> R.style.AppTheme_SC_Dark_Colored_Rose
                "magenta" -> R.style.AppTheme_SC_Dark_Colored_Magenta
                "violet" -> R.style.AppTheme_SC_Dark_Colored_Violet
                "lavender" -> R.style.AppTheme_SC_Dark_Colored_Lavender
                "periwinkle" -> R.style.AppTheme_SC_Dark_Colored_Periwinkle
                "white" -> R.style.AppTheme_SC_Dark_Colored_White
                "black" -> R.style.AppTheme_SC_Dark_Colored_Black
                else -> resId
            }
            R.style.AppTheme_AttachmentsPreview_SC -> when (themeAccent) {
                "bluelight" -> R.style.AppTheme_AttachmentsPreview_SC_BlueLight
                "amber" -> R.style.AppTheme_AttachmentsPreview_SC_Amber
                "cyan" -> R.style.AppTheme_AttachmentsPreview_SC_Cyan
                "gold" -> R.style.AppTheme_AttachmentsPreview_SC_Gold
                "lime" -> R.style.AppTheme_AttachmentsPreview_SC_Lime
                "orange" -> R.style.AppTheme_AttachmentsPreview_SC_Orange
                "pink" -> R.style.AppTheme_AttachmentsPreview_SC_Pink
                "purple" -> R.style.AppTheme_AttachmentsPreview_SC_Purple
                "red" -> R.style.AppTheme_AttachmentsPreview_SC_Red
                "teal" -> R.style.AppTheme_AttachmentsPreview_SC_Teal
                "turquoise" -> R.style.AppTheme_AttachmentsPreview_SC_Turquoise
                "yellow" -> R.style.AppTheme_AttachmentsPreview_SC_Yellow
                "carnation" -> R.style.AppTheme_AttachmentsPreview_SC_Carnation
                "denim" -> R.style.AppTheme_AttachmentsPreview_SC_Denim
                "indigo" -> R.style.AppTheme_AttachmentsPreview_SC_Indigo
                "lava" -> R.style.AppTheme_AttachmentsPreview_SC_Lava
                "blue" -> R.style.AppTheme_AttachmentsPreview_SC_Blue
                "greendark" -> R.style.AppTheme_AttachmentsPreview_SC_GreenDark
                "element" -> R.style.AppTheme_AttachmentsPreview_SC_Element
                "vibecoder" -> R.style.AppTheme_AttachmentsPreview_SC_Vibecoder
                "peach" -> R.style.AppTheme_AttachmentsPreview_SC_Peach
                "slate" -> R.style.AppTheme_AttachmentsPreview_SC_Slate
                "rose" -> R.style.AppTheme_AttachmentsPreview_SC_Rose
                "magenta" -> R.style.AppTheme_AttachmentsPreview_SC_Magenta
                "violet" -> R.style.AppTheme_AttachmentsPreview_SC_Violet
                "lavender" -> R.style.AppTheme_AttachmentsPreview_SC_Lavender
                "periwinkle" -> R.style.AppTheme_AttachmentsPreview_SC_Periwinkle
                "white" -> R.style.AppTheme_AttachmentsPreview_SC_White
                "black" -> R.style.AppTheme_AttachmentsPreview_SC_Black
                else -> resId
            }
            R.style.Theme_Vector_Light -> when (themeAccent) {
                "green" -> R.style.Theme_Vector_Light_Green
                "bluelight" -> R.style.Theme_Vector_Light_BlueLight
                "amber" -> R.style.Theme_Vector_Light_Amber
                "cyan" -> R.style.Theme_Vector_Light_Cyan
                "gold" -> R.style.Theme_Vector_Light_Gold
                "lime" -> R.style.Theme_Vector_Light_Lime
                "orange" -> R.style.Theme_Vector_Light_Orange
                "pink" -> R.style.Theme_Vector_Light_Pink
                "purple" -> R.style.Theme_Vector_Light_Purple
                "red" -> R.style.Theme_Vector_Light_Red
                "teal" -> R.style.Theme_Vector_Light_Teal
                "turquoise" -> R.style.Theme_Vector_Light_Turquoise
                "yellow" -> R.style.Theme_Vector_Light_Yellow
                "carnation" -> R.style.Theme_Vector_Light_Carnation
                "denim" -> R.style.Theme_Vector_Light_Denim
                "indigo" -> R.style.Theme_Vector_Light_Indigo
                "lava" -> R.style.Theme_Vector_Light_Lava
                "blue" -> R.style.Theme_Vector_Light_Blue
                "greendark" -> R.style.Theme_Vector_Light_GreenDark
                "vibecoder" -> R.style.Theme_Vector_Light_Vibecoder
                "peach" -> R.style.Theme_Vector_Light_Peach
                "slate" -> R.style.Theme_Vector_Light_Slate
                "rose" -> R.style.Theme_Vector_Light_Rose
                "magenta" -> R.style.Theme_Vector_Light_Magenta
                "violet" -> R.style.Theme_Vector_Light_Violet
                "lavender" -> R.style.Theme_Vector_Light_Lavender
                "periwinkle" -> R.style.Theme_Vector_Light_Periwinkle
                "white" -> R.style.Theme_Vector_Light_White
                "black" -> R.style.Theme_Vector_Light_Black
                else -> resId
            }
            R.style.Theme_Vector_Dark -> when (themeAccent) {
                "green" -> R.style.Theme_Vector_Dark_Green
                "bluelight" -> R.style.Theme_Vector_Dark_BlueLight
                "amber" -> R.style.Theme_Vector_Dark_Amber
                "cyan" -> R.style.Theme_Vector_Dark_Cyan
                "gold" -> R.style.Theme_Vector_Dark_Gold
                "lime" -> R.style.Theme_Vector_Dark_Lime
                "orange" -> R.style.Theme_Vector_Dark_Orange
                "pink" -> R.style.Theme_Vector_Dark_Pink
                "purple" -> R.style.Theme_Vector_Dark_Purple
                "red" -> R.style.Theme_Vector_Dark_Red
                "teal" -> R.style.Theme_Vector_Dark_Teal
                "turquoise" -> R.style.Theme_Vector_Dark_Turquoise
                "yellow" -> R.style.Theme_Vector_Dark_Yellow
                "carnation" -> R.style.Theme_Vector_Dark_Carnation
                "denim" -> R.style.Theme_Vector_Dark_Denim
                "indigo" -> R.style.Theme_Vector_Dark_Indigo
                "lava" -> R.style.Theme_Vector_Dark_Lava
                "blue" -> R.style.Theme_Vector_Dark_Blue
                "greendark" -> R.style.Theme_Vector_Dark_GreenDark
                "vibecoder" -> R.style.Theme_Vector_Dark_Vibecoder
                "peach" -> R.style.Theme_Vector_Dark_Peach
                "slate" -> R.style.Theme_Vector_Dark_Slate
                "rose" -> R.style.Theme_Vector_Dark_Rose
                "magenta" -> R.style.Theme_Vector_Dark_Magenta
                "violet" -> R.style.Theme_Vector_Dark_Violet
                "lavender" -> R.style.Theme_Vector_Dark_Lavender
                "periwinkle" -> R.style.Theme_Vector_Dark_Periwinkle
                "white" -> R.style.Theme_Vector_Dark_White
                "black" -> R.style.Theme_Vector_Dark_Black
                else -> resId
            }
            R.style.Theme_Vector_Black -> when (themeAccent) {
                "green" -> R.style.Theme_Vector_Black_Green
                "bluelight" -> R.style.Theme_Vector_Black_BlueLight
                "amber" -> R.style.Theme_Vector_Black_Amber
                "cyan" -> R.style.Theme_Vector_Black_Cyan
                "gold" -> R.style.Theme_Vector_Black_Gold
                "lime" -> R.style.Theme_Vector_Black_Lime
                "orange" -> R.style.Theme_Vector_Black_Orange
                "pink" -> R.style.Theme_Vector_Black_Pink
                "purple" -> R.style.Theme_Vector_Black_Purple
                "red" -> R.style.Theme_Vector_Black_Red
                "teal" -> R.style.Theme_Vector_Black_Teal
                "turquoise" -> R.style.Theme_Vector_Black_Turquoise
                "yellow" -> R.style.Theme_Vector_Black_Yellow
                "carnation" -> R.style.Theme_Vector_Black_Carnation
                "denim" -> R.style.Theme_Vector_Black_Denim
                "indigo" -> R.style.Theme_Vector_Black_Indigo
                "lava" -> R.style.Theme_Vector_Black_Lava
                "blue" -> R.style.Theme_Vector_Black_Blue
                "greendark" -> R.style.Theme_Vector_Black_GreenDark
                "vibecoder" -> R.style.Theme_Vector_Black_Vibecoder
                "peach" -> R.style.Theme_Vector_Black_Peach
                "slate" -> R.style.Theme_Vector_Black_Slate
                "rose" -> R.style.Theme_Vector_Black_Rose
                "magenta" -> R.style.Theme_Vector_Black_Magenta
                "violet" -> R.style.Theme_Vector_Black_Violet
                "lavender" -> R.style.Theme_Vector_Black_Lavender
                "periwinkle" -> R.style.Theme_Vector_Black_Periwinkle
                "white" -> R.style.Theme_Vector_Black_White
                "black" -> R.style.Theme_Vector_Black_Black
                else -> resId
            }
            else -> resId
        }
    }
}
