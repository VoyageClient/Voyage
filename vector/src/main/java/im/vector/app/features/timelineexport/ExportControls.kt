/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.viewbinding.ViewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.profile.AnonymizationConfig
import org.matrix.android.sdk.api.session.profile.AnonymizationRule
import androidx.appcompat.widget.AppCompatEditText as EditText
import androidx.appcompat.widget.AppCompatTextView as TextView

class ExportViewBinding(private val view: View) : ViewBinding {
    override fun getRoot(): View = view
}

// The parent must not pad horizontally: rows inset themselves so their ripples span its full width.
class ExportControls(
        val context: Context,
        val parent: LinearLayout,
        private val expandedSections: MutableMap<Int, Boolean> = mutableMapOf(),
        private val startInset: Int = 0,
        private val endInset: Int = startInset,
) {
    private val dialogContext get() = ContextThemeWrapper(context, ThemeUtils.getApplicationThemeRes(context))

    private fun View.padRow(top: Int, bottom: Int) = ViewCompat.setPaddingRelative(this, startInset, top, endInset, bottom)

    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply {
        MarginLayoutParamsCompat.setMarginStart(this, startInset)
        MarginLayoutParamsCompat.setMarginEnd(this, endInset)
    }

    private fun View.selectableBackground() {
        val style = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        backgroundCompat = style.getDrawable(0)
        style.recycle()
    }

    fun heading(id: Int) {
        parent.addView(TextView(context).apply {
            setText(id)
            TextViewCompat.setTextAppearance(this, androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
            padRow(dp(16), dp(8))
        })
    }
    fun text(value: String) { parent.addView(TextView(context).apply { text = value; padRow(dp(8), dp(8)) }) }
    fun action(id: Int, run: () -> Unit): TextView = action(context.getString(id), run)
    fun action(label: String, run: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minimumHeight = dp(48); padRow(dp(12), dp(12))
        selectableBackground()
        setOnClickListener { run() }
        this@ExportControls.parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun section(id: Int, expanded: Boolean = false, build: (ExportControls) -> Unit) {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expandedSections[id] ?: expanded) View.VISIBLE else View.GONE
        }
        var built = false
        fun populate() { if (!built) { build(ExportControls(context, body, expandedSections, startInset + dp(16), endInset)); built = true } }
        val arrow = AppCompatImageView(context).apply {
            val drawable = AppCompatResources.getDrawable(context, im.vector.app.R.drawable.ic_arrow_right)?.mutate()
            drawable?.let { DrawableCompat.setTint(it, Color.LTGRAY) }
            setImageDrawable(drawable)
            rotation = if (body.visibility == View.VISIBLE) 90f else 0f
            ViewCompat.setImportantForAccessibility(this, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO)
        }
        var arrowAnimation: ValueAnimator? = null
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            padRow(0, 0)
            selectableBackground()
            addView(TextView(context).apply { setText(id); textSize = 16f; setPadding(0, dp(16), dp(12), dp(12)) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(arrow, LinearLayout.LayoutParams(dp(16), dp(16)))
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                if (open) populate()
                if (ViewCompat.isLaidOut(this@ExportControls.parent)) {
                    TransitionManager.beginDelayedTransition(this@ExportControls.parent, TransitionSet().apply {
                        ordering = TransitionSet.ORDERING_TOGETHER
                        addTransition(ChangeBounds())
                        addTransition(Fade())
                        duration = 180
                    })
                }
                body.visibility = if (open) View.VISIBLE else View.GONE
                expandedSections[id] = open
                arrowAnimation?.cancel()
                arrowAnimation = ValueAnimator.ofFloat(arrow.rotation, if (open) 90f else 0f).apply {
                    duration = 180
                    addUpdateListener { arrow.rotation = it.animatedValue as Float }
                    start()
                }
            }
        }
        parent.addView(heading, LinearLayout.LayoutParams(-1, -2))
        if (body.visibility == View.VISIBLE) populate()
        parent.addView(body)
    }

    fun preferenceAction(id: Int, value: String, run: () -> Unit): TextView = action(id, run).apply {
        text = preferenceLabel(id, value)
    }

    private fun preferenceLabel(id: Int, value: String): CharSequence = preferenceLabel(context.getString(id), value)

    private fun preferenceLabel(title: String, value: String): CharSequence {
        val label = SpannableStringBuilder(title).append('\n').append(value)
        val start = title.length + 1
        val style = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val color = style.getColor(0, Color.LTGRAY)
        style.recycle()
        label.setSpan(RelativeSizeSpan(.85f), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(ForegroundColorSpan(color), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return label
    }

    fun toggle(id: Int, value: Boolean, update: (Boolean) -> Unit) = toggle(context.getString(id), value, update)

    fun toggle(label: String, value: Boolean, update: (Boolean) -> Unit) {
        parent.addView(SwitchCompat(context).apply {
            text = label; isChecked = value; padRow(dp(6), dp(6))
            setOnCheckedChangeListener { _, checked -> update(checked) }
        })
    }

    fun choice(id: Int, choices: List<Pair<String, Int>>, value: String, update: (String) -> Unit) {
        choiceLabels(id, choices.map { it.first to context.getString(it.second) }, value, update)
    }

    fun preferenceChoice(id: Int, entries: Int, values: Int, value: String, update: (String) -> Unit) {
        val labels = context.resources.getStringArray(entries)
        val keys = context.resources.getStringArray(values)
        choiceLabels(id, keys.zip(labels), value, update)
    }

    private fun choiceLabels(id: Int, choices: List<Pair<String, String>>, value: String, update: (String) -> Unit) {
        var selected = choices.indexOfFirst { it.first == value }.coerceAtLeast(0)
        lateinit var button: TextView
        button = preferenceAction(id, choices[selected].second) {
            MaterialAlertDialogBuilder(dialogContext).setTitle(id).setSingleChoiceItems(choices.map { it.second }.toTypedArray(), selected) { dialog, index ->
                selected = index
                button.text = preferenceLabel(id, choices[selected].second)
                dialog.dismiss()
                update(choices[index].first)
            }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    fun slider(id: Int, value: Int, min: Int, max: Int, update: (Int) -> Unit) {
        val label = TextView(context).apply { text = context.getString(CommonStrings.export_label_value, context.getString(id), value); padRow(dp(12), 0) }
        parent.addView(label)
        parent.addView(AppCompatSeekBar(context).apply {
            this.max = max - min; progress = value.coerceIn(min, max) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { update(progress + min) }
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = context.getString(CommonStrings.export_label_value, context.getString(id), progress + min)
                }
            })
        }, rowParams())
    }

    fun edit(id: Int, value: String, numeric: Boolean = false, update: (String) -> Unit) {
        parent.addView(TextView(context).apply { setText(id); padRow(dp(12), 0) })
        parent.addView(EditText(context).apply {
            setSingleLine(true); setText(value)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { update(s.toString()) }
            })
        }, rowParams())
    }

    /**
     * The settings screens' anonymization choices for an export: on or off, who is anonymized, and the people listed.
     * @param pickPeople opens the people picker; the host adds whoever is chosen to the list
     * @param trueName looks up how a listed person is really called, never their alias
     */
    fun identity(config: AnonymizationConfig, self: String, pickPeople: (title: String, excluded: Collection<String>) -> Unit,
                 trueName: (userId: String, onName: (String) -> Unit) -> Unit, update: (AnonymizationConfig) -> Unit) {
        var current = config
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lateinit var render: () -> Unit

        // Rebuilt after the change lands: a control must not be removed from inside its own listener.
        fun applyRule(next: AnonymizationRule) { current = current.copy(rule = next); update(current); body.post { render() } }
        fun apply(next: AnonymizationConfig) { current = next; update(next) }
        render = {
            body.removeAllViews()
            val fields = ExportControls(context, body, expandedSections, startInset, endInset)
            val rule = current.rule
            fields.toggle(CommonStrings.anonymization_enabled, rule.enabled) { applyRule(rule.copy(enabled = it)) }
            if (rule.enabled) {
                fields.choice(CommonStrings.anonymization_list_mode, listOf("WHITELIST" to CommonStrings.anonymization_whitelist,
                        "BLACKLIST" to CommonStrings.anonymization_blacklist), if (rule.onlyListed) "BLACKLIST" else "WHITELIST") {
                    applyRule(rule.copy(onlyListed = it == "BLACKLIST"))
                }
                fields.choice(CommonStrings.export_alias_style, listOf("WORDS" to CommonStrings.export_two_words, "SHARED" to CommonStrings.export_shared_name), current.naming) {
                    apply(current.copy(naming = it)); body.post { render() }
                }
                if (current.naming == "SHARED") fields.edit(CommonStrings.export_shared_label, current.sharedName()) { if (it.isNotBlank()) apply(current.copy(sharedLabel = it)) }
                if (rule.includes(self)) fields.edit(CommonStrings.export_self_label, current.selfName()) { if (it.isNotBlank()) apply(current.copy(selfLabel = it)) }
                fields.toggle(CommonStrings.export_shared_avatar, current.sharedAvatar) { apply(current.copy(sharedAvatar = it)) }
                fields.heading(CommonStrings.anonymization_exclusions)
                fields.action(CommonStrings.anonymization_add_exclusion) { pickPeople(context.getString(CommonStrings.anonymization_exclusions), rule.users) }
                rule.users.sorted().forEach { userId ->
                    val status = if (rule.includes(userId)) context.getString(CommonStrings.anonymization_person_anonymized, current.name(userId, self))
                    else context.getString(CommonStrings.anonymization_person_visible)
                    var name = userId
                    val row = fields.action(userId) {
                        MaterialAlertDialogBuilder(dialogContext).setTitle(name).setMessage(CommonStrings.anonymization_remove_exclusion)
                                .setPositiveButton(CommonStrings.action_delete) { _, _ -> applyRule(rule.copy(users = rule.users - userId)) }
                                .setNegativeButton(CommonStrings.action_cancel, null).show()
                    }
                    row.text = preferenceLabel(userId, status)
                    trueName(userId) { found -> name = found; row.text = preferenceLabel(found, status) }
                }
            }
        }
        parent.addView(body)
        render()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
