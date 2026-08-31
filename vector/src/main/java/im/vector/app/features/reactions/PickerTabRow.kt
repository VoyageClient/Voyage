/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import im.vector.app.R
import im.vector.app.databinding.ViewPickerTabRowBinding
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

/**
 * The category strip shared by the emoji, emote and sticker pickers: the scrollable tabs, a search key,
 * an optional freeform-reaction key and an optional trailing key (the composer keyboard's backspace).
 * The search key swaps the tabs for an inline field with its own clear button; the freeform key swaps
 * them for a field whose text is sent as a reaction.
 */
class PickerTabRow @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private enum class Mode { TABS, SEARCH, FREEFORM }

    private val views: ViewPickerTabRowBinding

    val tabs: EmojiPickerTabStrip get() = views.pickerTabs

    var onQueryChanged: ((String) -> Unit)? = null
    var onSearchModeChanged: ((Boolean) -> Unit)? = null
    var onFreeformSubmit: ((String) -> Unit)? = null

    val isSearching: Boolean get() = mode == Mode.SEARCH

    private var mode = Mode.TABS
    private var freeformEnabled = false
    private var searchEnabled = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views = ViewPickerTabRowBinding.inflate(LayoutInflater.from(context), this)
        views.pickerSearch.setOnClickListener {
            when (mode) {
                Mode.TABS -> setMode(Mode.SEARCH)
                Mode.SEARCH -> setMode(Mode.TABS)
                Mode.FREEFORM -> submitFreeform()
            }
        }
        views.pickerFreeform.setOnClickListener { setMode(Mode.FREEFORM) }
        views.pickerCancel.setOnClickListener { setMode(Mode.TABS) }
        views.pickerClear.setOnClickListener { views.pickerInput.setText("") }
        views.pickerInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                views.pickerClear.isVisible = mode == Mode.SEARCH && s?.isNotEmpty() == true
                if (mode != Mode.SEARCH) return
                onQueryChanged?.invoke(s?.toString().orEmpty())
            }
        })
        views.pickerInput.setOnEditorActionListener { _, actionId, _ ->
            if (mode == Mode.FREEFORM && actionId == EditorInfo.IME_ACTION_DONE) {
                submitFreeform()
                true
            } else {
                false
            }
        }
    }

    /** Hidden in the composer panel, which has no room to put a search field. */
    fun setSearchEnabled(enabled: Boolean) {
        searchEnabled = enabled
        updateKeyVisibility()
    }

    /** Shows the freeform-reaction key (the reaction picker only). */
    fun setFreeformEnabled(enabled: Boolean) {
        freeformEnabled = enabled
        updateKeyVisibility()
    }

    private fun updateKeyVisibility() {
        views.pickerSearch.isVisible = searchEnabled || mode == Mode.FREEFORM
        views.pickerFreeform.isVisible = freeformEnabled && mode != Mode.FREEFORM
        views.pickerCancel.isVisible = mode == Mode.FREEFORM
        views.pickerClear.isVisible = mode == Mode.SEARCH && views.pickerInput.text?.isNotEmpty() == true
    }

    // Deliberately leaves the field up: the host closes on submit, and swapping back to the tabs first
    // would land the accept key's ripple on whatever replaces it.
    private fun submitFreeform() {
        val text = views.pickerInput.text?.toString().orEmpty().trim()
        if (text.isNotEmpty()) onFreeformSubmit?.invoke(text)
    }

    private fun setMode(target: Mode) {
        if (mode == target) return
        val wasEditing = mode != Mode.TABS
        mode = target
        views.pickerInput.setText("")
        views.pickerTabs.isVisible = target == Mode.TABS
        views.pickerInput.isVisible = target != Mode.TABS
        updateKeyVisibility()
        when (target) {
            Mode.TABS -> {
                views.pickerSearch.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_search))
                tintKey(secondary = true)
                views.pickerInput.clearFocus()
                context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(windowToken, 0)
                onQueryChanged?.invoke("")
                if (wasEditing) onSearchModeChanged?.invoke(false)
            }
            Mode.SEARCH -> {
                views.pickerSearch.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_search))
                tintKey(secondary = true)
                views.pickerInput.hint = context.getString(CommonStrings.search_hint)
                views.pickerInput.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                focusInput(wasEditing)
            }
            Mode.FREEFORM -> {
                views.pickerSearch.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_check_white_24dp))
                tintKey(secondary = false)
                views.pickerInput.hint = context.getString(CommonStrings.freeform_reaction_hint)
                views.pickerInput.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                focusInput(wasEditing)
            }
        }
    }

    private fun tintKey(secondary: Boolean) {
        val attr = if (secondary) im.vector.lib.ui.styles.R.attr.vctr_content_secondary else com.google.android.material.R.attr.colorAccent
        ImageViewCompat.setImageTintList(
                views.pickerSearch,
                android.content.res.ColorStateList.valueOf(ThemeUtils.getColor(context, attr))
        )
    }

    private fun focusInput(alreadyEditing: Boolean) {
        if (!alreadyEditing) onSearchModeChanged?.invoke(true)
        views.pickerInput.requestFocus()
        context.getSystemService<InputMethodManager>()?.showSoftInput(views.pickerInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Show a trailing action key next to the tabs (the keyboard's backspace). Each press fires immediately
     * on ACTION_DOWN — no click-detection delay — so rapid tapping deletes as fast as you tap; holding then
     * repeats like a hardware key. Non-focusable so it never steals focus from the composer.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setTrailingAction(
            @DrawableRes iconRes: Int,
            @StringRes contentDescriptionRes: Int,
            onPressChanged: (pressed: Boolean) -> Unit,
            onClick: () -> Unit,
    ) {
        val button = views.pickerTrailing
        button.isVisible = true
        button.setImageDrawable(AppCompatResources.getDrawable(context, iconRes))
        button.contentDescription = context.getString(contentDescriptionRes)
        button.isFocusable = false
        button.isFocusableInTouchMode = false
        // Accelerating auto-repeat while held, like a hardware key: tapping deletes one per tap, holding
        // ramps up to a fast delete so you don't have to spam-tap to clear a lot of text.
        val repeat = object : Runnable {
            var interval = REPEAT_FIRST_MS
            override fun run() {
                onClick()
                interval = (interval * REPEAT_ACCEL).toLong().coerceAtLeast(REPEAT_MIN_MS)
                repeatHandler.postDelayed(this, interval)
            }
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    // Keep the scrolling tab strip from stealing fast, slightly-imprecise taps mid-gesture.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    onPressChanged(true)
                    onClick()
                    repeatHandler.removeCallbacks(repeat)
                    repeat.interval = REPEAT_FIRST_MS
                    repeatHandler.postDelayed(repeat, REPEAT_INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeatHandler.removeCallbacks(repeat)
                    onPressChanged(false)
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val REPEAT_INITIAL_DELAY_MS = 250L
        private const val REPEAT_FIRST_MS = 110L
        private const val REPEAT_MIN_MS = 28L
        private const val REPEAT_ACCEL = 0.82
    }
}
