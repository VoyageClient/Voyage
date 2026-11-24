/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import androidx.transition.ChangeBounds
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import timber.log.Timber

class ExportEditorView(context: Context) : LinearLayout(context) {
    val toolbar = MaterialToolbar(context)
    val previewContainer = FrameLayout(context)
    val imagePreview = ExportPreviewView(context)
    val status = AppCompatTextView(context)
    val previewTabs = TabLayout(context)
    val panelContent = LinearLayout(context).apply { orientation = VERTICAL }
    val exportButton = FloatingActionButton(context)
    var onPanelSelected: ((Int) -> Unit)? = null
    var onPreviewSelected: ((Boolean) -> Unit)? = null
    private val accent = ThemeUtils.accentFillOnDarkSurface(context)
    private val panelButtons = linkedMapOf<Int, FloatingActionButton>()
    private val panelLabels = linkedMapOf<Int, AppCompatTextView>()
    private val panel = SettingsScroll(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        toolbar.setTitle(CommonStrings.export_editor_title)
        toolbar.setTitleTextColor(Color.WHITE)
        toolbar.popupTheme = ThemeUtils.getApplicationThemeRes(context)
        toolbar.navigationIcon = AppCompatResources.getDrawable(context, R.drawable.ic_back_24dp)?.mutate()?.apply {
            DrawableCompat.setTint(this, Color.WHITE)
        }
        addView(toolbar, LayoutParams(-1, dp(56)))
        val modeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
            minimumHeight = dp(48)
        }
        previewTabs.apply {
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            setSelectedTabIndicatorColor(accent.first)
            setTabTextColors(Color.LTGRAY, accent.first)
            addTab(newTab().setText(CommonStrings.export_preview_image))
            addTab(newTab().setText(CommonStrings.export_html))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { onPreviewSelected?.invoke(tab.position == 1) }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        modeRow.addView(previewTabs, LayoutParams(dp(152), dp(48)))
        status.apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), 0, dp(4))
            setText(CommonStrings.export_preparing)
        }
        modeRow.addView(status, LayoutParams(0, -2, 1f))
        addView(modeRow, LayoutParams(-1, -2))
        previewContainer.addView(imagePreview, FrameLayout.LayoutParams(-1, -1))
        addView(previewContainer, LayoutParams(-1, 0, 1f))
        panel.apply {
            setBackgroundColor(Color.rgb(17, 17, 17))
            isFillViewport = false
            panelContent.setPadding(0, 0, 0, dp(8))
            addView(panelContent, FrameLayout.LayoutParams(-1, -2))
        }
        addView(panel, LayoutParams(-1, -2))
        val toolRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(17, 17, 17))
        }
        val tools = listOf(
                Triple(1, CommonStrings.export_appearance, R.drawable.ic_room_profile_personalization),
                Triple(0, CommonStrings.settings_category_timeline, R.drawable.ic_room_profile_tune),
                Triple(2, CommonStrings.export_privacy, R.drawable.ic_settings_root_security_privacy),
        )
        tools.forEach { (index, label, icon) ->
            val button = FloatingActionButton(context).apply {
                size = FloatingActionButton.SIZE_MINI
                setEnsureMinTouchTargetSize(true)
                setImageDrawable(AppCompatResources.getDrawable(context, icon))
                contentDescription = context.getString(label)
                setOnClickListener { onPanelSelected?.invoke(index) }
            }
            panelButtons[index] = button
            toolRow.addView(toolSlot(button, label).also { panelLabels[index] = it.getChildAt(1) as AppCompatTextView },
                    LayoutParams(dp(64), -2).apply { rightMargin = dp(6) })
        }
        exportButton.apply {
            size = FloatingActionButton.SIZE_NORMAL
            setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_download_anim))
            contentDescription = context.getString(CommonStrings.export_save)
            backgroundTintList = ColorStateList.valueOf(accent.first)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(accent.second))
        }
        toolRow.addView(toolSlot(exportButton, CommonStrings.export_save), LayoutParams(dp(64), -2))
        addView(toolRow, LayoutParams(-1, -2))
        selectPanel(1, false, animate = false)
    }

    private fun toolSlot(button: FloatingActionButton, label: Int) = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(FrameLayout(context).apply {
            addView(button, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }, LayoutParams(-1, dp(64)))
        addView(AppCompatTextView(context).apply {
            setText(label)
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            ViewCompat.setImportantForAccessibility(this, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO)
        }, LayoutParams(-1, -2))
    }

    fun selectPanel(index: Int, visible: Boolean, animate: Boolean = true) {
        if (animate && panelVisible != visible && ViewCompat.isLaidOut(this)) {
            TransitionManager.beginDelayedTransition(this, TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(ChangeBounds())
                addTransition(Slide(Gravity.BOTTOM).addTarget(panel))
                duration = 200
            })
        }
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        panelButtons.forEach { (key, button) ->
            val selected = visible && key == index
            button.isSelected = selected
            button.backgroundTintList = ColorStateList.valueOf(if (selected) accent.first else 0xB0333333.toInt())
            ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(if (selected) accent.second else Color.WHITE))
            panelLabels[key]?.setTextColor(if (selected) accent.first else Color.LTGRAY)
        }
    }

    val panelVisible: Boolean get() = panel.visibility == View.VISIBLE
    val panelScroll: Int get() = panel.scrollY
    fun scrollPanelTo(y: Int) { panel.post { panel.scrollTo(0, y) } }

    fun setEditingEnabled(enabled: Boolean) {
        fun update(view: View) {
            view.isEnabled = enabled
            (view as? ViewGroup)?.let { group -> (0 until group.childCount).forEach { update(group.getChildAt(it)) } }
        }
        update(panelContent)
        update(previewTabs)
        panelButtons.values.forEach { it.isEnabled = enabled }
        exportButton.isEnabled = enabled
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val previewReserve = dp(56 + 48 + 92 + 120)
        panel.maximumHeight = minOf((availableHeight * .36f).toInt(), (availableHeight - previewReserve).coerceAtLeast(dp(72)))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) Timber.d("ExportTrace editor layout width=%d height=%d previewHeight=%d panelHeight=%d", width, height, previewContainer.height, panel.height)
    }

    private class SettingsScroll(context: Context) : ScrollView(context) {
        var maximumHeight = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximumHeight else minOf(size, maximumHeight)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap.coerceAtLeast(0), MeasureSpec.AT_MOST))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
