/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.ui.colorpicker.ColorPalette
import im.vector.app.features.bulk.BulkActionQueue
import im.vector.app.features.home.avatar.DefaultAvatarStyle
import im.vector.app.features.html.highlight.CodePalette
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class TimelineExportActivity : VectorBaseActivity<ExportViewBinding>() {
    @Inject lateinit var store: TimelineExportStore
    @Inject lateinit var builder: TimelineExportBuilder
    @Inject lateinit var renderer: TimelineExportRenderer
    @Inject lateinit var htmlExporter: NativeTimelineHtmlExporter
    @Inject lateinit var queue: BulkActionQueue
    private lateinit var draft: TimelineExportDraft
    private lateinit var preview: ExportPreviewView
    private lateinit var frame: FrameLayout
    private lateinit var editorToolbar: MaterialToolbar
    private lateinit var status: TextView
    private lateinit var editor: ExportEditorView
    private var prepared: TimelineExportSnapshot? = null
    private var preparation: Job? = null
    private var animation: Job? = null
    private var preparing = false
    private var finalizing = false
    private var discarded = false
    private val undo = ArrayDeque<TimelineExportOptions>()
    private val redo = ArrayDeque<TimelineExportOptions>()
    private var outputPopup: AlertDialog? = null
    private var outputSave: View? = null
    private var animatedDocument: TimelineExportRenderer.Document? = null
    private var activePanel = 1
    private val expandedSections = mutableMapOf<Int, MutableMap<Int, Boolean>>()
    private val panelScroll = mutableMapOf<Int, Int>()
    private var htmlPreview: ExportHtmlPreviewView? = null
    private var previewHtml = false
    private var htmlPreparation: Job? = null
    private var htmlRevision = -1L
    private var htmlSnapshot: TimelineExportSnapshot? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) finalizeExport() else showError(getString(CommonStrings.export_storage_permission))
    }
    private var saveJob: Job? = null
    private var preparedIds: List<String> = emptyList()
    private var pendingSnapshot: TimelineExportSnapshot? = null
    private val peoplePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val ids = result.data?.getStringArrayExtra(AnonymizationPeoplePickerActivity.EXTRA_USER_IDS) ?: return@registerForActivityResult
        if (!::draft.isInitialized) return@registerForActivityResult
        val identity = draft.options.identity
        change(draft.options.copy(identity = identity.copy(rule = identity.rule.copy(users = identity.rule.users + ids))))
        refreshPanel()
    }
    private var lastViewport: Pair<Int, Int>? = null

    override fun getOtherThemes() = ActivityOtherThemes.BlackEditor
    override val rootView: View get() = views.root

    override fun getBinding(): ExportViewBinding {
        editor = ExportEditorView(this)
        editorToolbar = editor.toolbar.apply {
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            menu.add(0, 10, 0, CommonStrings.export_undo).setIcon(R.drawable.ic_editor_undo).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 11, 1, CommonStrings.export_redo).setIcon(R.drawable.ic_editor_redo).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 5, 2, CommonStrings.export_add_events)
            menu.add(0, 3, 4, CommonStrings.export_presets)
            menu.add(0, 4, 3, CommonStrings.export_discard)
            setOnMenuItemClickListener {
                if (finalizing || !::draft.isInitialized) return@setOnMenuItemClickListener true
                when (it.itemId) {
                    10 -> if (undo.isNotEmpty()) { redo.addLast(draft.options); change(undo.removeLast(), record = false) }
                    11 -> if (redo.isNotEmpty()) { undo.addLast(draft.options); change(redo.removeLast(), record = false) }
                    5 -> finish(); 3 -> presets(); 4 -> discard(); else -> return@setOnMenuItemClickListener false }
                true
            }
        }
        status = editor.status
        frame = editor.previewContainer
        preview = editor.imagePreview
        editor.exportButton.setOnClickListener { outputOptions() }
        editor.exportButton.isEnabled = false
        editor.onPanelSelected = { index ->
            if (::draft.isInitialized && !finalizing) {
                panelScroll[activePanel] = editor.panelScroll
                val visible = activePanel != index || !editor.panelVisible
                activePanel = index
                editor.selectPanel(index, visible)
                if (visible) refreshPanel(panelScroll[index] ?: 0)
                Timber.d("ExportTrace editor tab selected category=%d visible=%s", index, visible)
            }
        }
        editor.onPreviewSelected = { html -> if (::draft.isInitialized) selectPreview(html) }
        return ExportViewBinding(editor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = activeSessionHolder.getSafeActiveSession() ?: run { finish(); return }
        val roomId = intent.getStringExtra("room") ?: run { finish(); return }
        val threadId = intent.getStringExtra("thread")
        lifecycleScope.launch {
            val (restored, currentDefaults) = withContext(Dispatchers.IO) {
                store.read(session.myUserId, roomId, threadId)?.let { it to builder.defaults(session, roomId) }
            } ?: run { finish(); return@launch }
            if (activeSessionHolder.getSafeActiveSession()?.myUserId != session.myUserId) { finish(); return@launch }
            start(restored, currentDefaults, savedInstanceState)
        }
    }

    private fun start(restored: TimelineExportDraft, currentDefaults: TimelineExportOptions, savedInstanceState: Bundle?) {
        // The timeline saves selections without options; they're filled from the current appearance on first open.
        val options = if (restored.started) restored.options else currentDefaults
        draft = restored.copy(started = true, selectionActive = true, updatedAt = maxOf(System.currentTimeMillis(), restored.updatedAt + 1))
        draft = draft.copy(options = themedOptions(options.copy(
                accentName = options.accentName.takeUnless { it == "default" } ?: currentDefaults.accentName,
                layoutWidth = currentDefaults.layoutWidth, phoneWidth = currentDefaults.phoneWidth, fontScale = currentDefaults.fontScale)))
        saveDraft()
        activePanel = (savedInstanceState?.getInt("export-panel", 1) ?: 1).takeIf { it in 0..2 } ?: 1
        editor.selectPanel(activePanel, savedInstanceState?.getBoolean("export-panel-visible", false) ?: false, animate = false)
        refreshPanel(savedInstanceState?.getInt("export-panel-scroll", 0) ?: 0)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
        preview.renderer = renderer
        preview.onGroupToggle = { id ->
            setGroupExpanded(id, !(draft.options.expandAll || id in draft.options.expandedGroups))
        }
        preview.onViewportChanged = { refreshAnimation() }
        updateHistoryActions()
        if (savedInstanceState?.getBoolean("export-html-preview", false) == true) editor.previewTabs.getTabAt(1)?.select()
        prepare()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("export-panel", activePanel)
        outState.putBoolean("export-panel-visible", editor.panelVisible)
        outState.putInt("export-panel-scroll", editor.panelScroll)
        outState.putBoolean("export-html-preview", previewHtml)
        super.onSaveInstanceState(outState)
    }

    private fun updateHistoryActions() {
        editorToolbar.menu.findItem(10).isEnabled = undo.isNotEmpty() && !finalizing
        editorToolbar.menu.findItem(11).isEnabled = redo.isNotEmpty() && !finalizing
    }

    private fun refreshPanel(scroll: Int = editor.panelScroll) {
        if (!::draft.isInitialized || !editor.panelVisible) return
        editor.panelContent.removeAllViews()
        buildControls(activePanel, ExportControls(this, editor.panelContent, expandedSections.getOrPut(activePanel) { mutableMapOf() }, startInset = dp(16)))
        editor.scrollPanelTo(scroll)
    }

    private fun selectPreview(html: Boolean) {
        if (previewHtml == html) return
        previewHtml = html
        preview.isVisible = !html
        if (html) {
            animation?.cancel()
            if (htmlPreview == null) htmlPreview = ExportHtmlPreviewView(this).also { web ->
                frame.addView(web, FrameLayout.LayoutParams(-1, -1))
            }
            htmlPreview?.isVisible = true
            htmlPreview?.bringToFront()
            htmlPreview?.requestLayout()
            htmlPreview?.onResume()
            prepared?.let { refreshHtml(it) }
        } else {
            htmlPreparation?.cancel()
            htmlPreview?.isVisible = false
            htmlPreview?.onPause()
            if (!preparing) prepared?.let { showCounts(it) }
            refreshAnimation()
        }
        Timber.d("ExportTrace preview mode html=%s", html)
    }

    private fun refreshHtml(snapshot: TimelineExportSnapshot) {
        if (!previewHtml || htmlRevision == snapshot.draftRevision || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (htmlSnapshot?.let { it.copy(options = it.options.withOutput(snapshot.options), draftRevision = snapshot.draftRevision) == snapshot } == true) {
            htmlRevision = snapshot.draftRevision
            htmlSnapshot = snapshot
            return
        }
        val previous = htmlPreparation
        previous?.cancel()
        htmlPreparation = lifecycleScope.launch {
            try {
                previous?.cancelAndJoin()
                delay(150)
                val directory = store.directory(snapshot.owner, snapshot.draftId)
                val startedAt = SystemClock.elapsedRealtime()
                status.setText(CommonStrings.export_preparing)
                val files = htmlExporter.write(this@TimelineExportActivity, snapshot, directory) { current, total ->
                    runOnUiThread { status.text = getString(CommonStrings.export_layout_progress, current * 100 / total.coerceAtLeast(1)) }
                }
                val html = withContext(Dispatchers.IO) { java.io.File(directory, "messages.html").readText() }
                if (previewHtml && prepared?.draftRevision == snapshot.draftRevision) {
                    htmlPreview?.load(directory, html, files + snapshot.assets.map { it.path })
                    htmlRevision = snapshot.draftRevision
                    htmlSnapshot = snapshot
                    showCounts(snapshot)
                    Timber.d("ExportTrace HTML preview prepared assets=%d elapsedMs=%d", files.size, SystemClock.elapsedRealtime() - startedAt)
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                Timber.e(failure, "ExportTrace HTML preview preparation failed")
                status.text = getString(CommonStrings.export_failed)
            }
        }
    }

    private fun showCounts(snapshot: TimelineExportSnapshot) {
        val doc = preview.document ?: return
        val scale = snapshot.options.width.toFloat() / doc.logicalWidth
        val parts = ExportSplitPlanner.split(doc.boundaries(snapshot.options.width), (doc.height * scale).toInt().coerceAtLeast(1), snapshot.options.maxHeight)
        status.text = getString(CommonStrings.export_counts, snapshot.events.size, draft.eventIds.size, parts.size)
    }

    private fun setGroupExpanded(id: String, expanded: Boolean) {
        val options = draft.options
        val groups = if (options.expandAll) prepared?.let { ExportBlocks.build(it.events, options.timeZoneId).mapNotNull { block -> block.groupId }.toSet() }.orEmpty()
        else options.expandedGroups
        change(options.copy(expandAll = false, expandedGroups = if (expanded) groups + id else groups - id))
        if (activePanel == 0) refreshPanel()
    }

    private fun change(options: TimelineExportOptions, record: Boolean = true) {
        if (finalizing || options == draft.options) return
        if (record) {
            undo.addLast(draft.options)
            if (undo.size > 60) undo.removeFirst()
            redo.clear()
        }
        draft = draft.copy(options = options, updatedAt = maxOf(System.currentTimeMillis(), draft.updatedAt + 1))
        Timber.d("ExportTrace settings change panel=%d theme=%s accent=%s bubbles=%s corners=%s tail=%s tint=%s peoplePalette=%s roomPalette=%s",
                activePanel, options.theme, options.accentName, options.bubbleStyle(), options.bubbleCorners, options.bubbleTail, options.bubbleTint,
                options.peoplePalette, options.roomPalette)
        saveDraft()
        updateHistoryActions()
        if (!record) refreshPanel()
        if (::preview.isInitialized && prepared != null && contentKey(prepared!!.options) == contentKey(options) &&
                prepared!!.options.withOutput(options) == options) {
            prepared = prepared!!.copy(options = options, draftRevision = draft.updatedAt)
            preview.document?.snapshot = prepared!!
            preview.splitHeight = options.maxHeight
            preview.invalidate()
            refreshAnimation()
            if (previewHtml) refreshHtml(prepared!!)
        } else prepare()
    }

    private fun saveDraft() {
        saveJob?.cancel()
        val value = draft
        saveJob = lifecycleScope.launch(Dispatchers.IO) { store.save(value) }
    }

    private fun sameGeometry(a: TimelineExportOptions, b: TimelineExportOptions): Boolean =
            a.layoutWidth == b.layoutWidth && a.theme == b.theme && a.peoplePalette == b.peoplePalette && a.bubbles == b.bubbles && a.timestamps == b.timestamps && a.dates == b.dates && a.roomHeader == b.roomHeader &&
                    a.bubbleCorners == b.bubbleCorners && a.bubbleTail == b.bubbleTail && a.avatarShape == b.avatarShape &&
                    a.avatars == b.avatars && a.expandAll == b.expandAll && a.expandedGroups == b.expandedGroups &&
                    a.twelveHourTime == b.twelveHourTime && a.timeZoneId == b.timeZoneId && a.fontScale == b.fontScale && a.codePalette == b.codePalette

    private fun contentKey(o: TimelineExportOptions): List<Any> = listOf(o.visibility, o.revealRedacted, o.media, o.avatars, o.omitAttachments, o.showProfileColors,
            o.editHistory, o.receipts, o.reactionSummary, o.threadSummary, o.identity, o.hideRoomIdentity)

    private fun prepare() {
        val previous = preparation
        val previousHtml = htmlPreparation
        previous?.cancel()
        previousHtml?.cancel()
        animation?.cancel()
        preparing = true
        editor.exportButton.isEnabled = false
        outputSave?.isEnabled = false
        val request = draft
        preparation = lifecycleScope.launch {
            try {
                previous?.join()
                previousHtml?.join()
                val session = activeSessionHolder.getSafeActiveSession()?.takeIf { it.myUserId == request.owner } ?: run { finish(); return@launch }
                val directory = store.directory(request.owner, request.id)
                val before = prepared
                val reusable = before != null && preparedIds == request.eventIds && contentKey(before.options) == contentKey(request.options)
                var snapshot = if (reusable) before!!.withAppearance(request.options).copy(draftRevision = request.updatedAt)
                else {
                    var stage = ExportPreparationStage.LOADING_EVENTS
                    builder.build(session, request, directory, onStage = { next ->
                        stage = next
                        if (next == ExportPreparationStage.COLLECTING_EMOJI) runOnUiThread { status.setText(CommonStrings.export_collecting_emoji) }
                    }) { current, total -> runOnUiThread {
                        if (stage != ExportPreparationStage.COLLECTING_EMOJI) status.text = getString(
                                if (stage == ExportPreparationStage.LOADING_EVENTS) CommonStrings.export_loading_events else CommonStrings.export_processing, current, total)
                    } }
                }
                if (snapshot.options.format == "WEBP" && !snapshot.hasAnimation()) {
                    draft = draft.copy(options = draft.options.copy(format = "PNG"))
                    snapshot = snapshot.copy(options = snapshot.options.copy(format = "PNG"))
                    saveDraft()
                }
                val startedAt = SystemClock.elapsedRealtime()
                val nextDocument = renderer.layout(this@TimelineExportActivity, snapshot, directory, preview.document,
                        reuseMeasurements = reusable && sameGeometry(before!!.options, request.options), progress = { current, total ->
                            runOnUiThread { status.text = getString(CommonStrings.export_layout_progress, current * 100 / total.coerceAtLeast(1)) }
                        })
                try { kotlinx.coroutines.currentCoroutineContext().ensureActive() } catch (cancelled: CancellationException) {
                    withContext(NonCancellable + Dispatchers.Main) { nextDocument.release() }
                    throw cancelled
                }
                prepared = snapshot
                preparedIds = request.eventIds
                Timber.d("ExportTrace preview ready events=%d reusedContent=%s elapsedMs=%d", snapshot.events.size, reusable, SystemClock.elapsedRealtime() - startedAt)
                preview.document?.release()
                preview.document = nextDocument
                preview.splitHeight = request.options.maxHeight
                showCounts(snapshot)
                preparing = false
                editor.exportButton.isEnabled = snapshot.events.isNotEmpty()
                outputSave?.isEnabled = snapshot.events.isNotEmpty()
                refreshAnimation()
                if (activePanel == 0 || (activePanel == 2 && before?.redactionRecoveryAvailable != snapshot.redactionRecoveryAvailable)) refreshPanel()
                if (previewHtml) refreshHtml(snapshot)
            } catch (failure: CancellationException) {
                Timber.w(failure, "ExportTrace preparation cancelled active=%s revision=%d", isActive, request.updatedAt)
                if (!isActive) throw failure
                preparing = false
                editor.exportButton.isEnabled = false
                outputSave?.isEnabled = false
                status.setText(CommonStrings.export_failed)
            } catch (failure: Exception) {
                Timber.e(failure, "Timeline export preview preparation failed")
                preparing = false
                editor.exportButton.isEnabled = false
                status.text = failure.localizedMessage ?: getString(CommonStrings.export_failed)
            }
        }
    }

    private fun refreshAnimation() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val doc = preview.document ?: return
        if (animatedDocument === doc && animation?.isActive == true) return
        animation?.cancel()
        animatedDocument = doc
        lastViewport = null
        if (!preview.isVisible) return
        animation = lifecycleScope.launch {
            var frame = 0
            val animated = doc.snapshot.hasAnimation()
            do {
                val range = preview.visibleRange()
                if (animated || range != lastViewport) renderer.prepareAssets(doc, if (animated) frame * 1000 / draft.options.fps.coerceAtLeast(1) else 0, range.first, range.second, waitForMedia = false)
                if (animated || range != lastViewport) preview.invalidate()
                lastViewport = range
                frame++
                delay(if (animated) 1000L / draft.options.fps.coerceAtLeast(1) else 80)
            } while (isActive)
        }
    }

    private val dialogContext: Context get() = androidx.appcompat.view.ContextThemeWrapper(this,
            ThemeUtils.getApplicationThemeRes(this))

    private fun popup(title: Int, content: LinearLayout, dismissLabel: Int = CommonStrings.done): AlertDialog =
            MaterialAlertDialogBuilder(dialogContext).setTitle(title)
                    .setView(ScrollView(content.context).apply { addView(content) })
                    .setNegativeButton(dismissLabel, null).create()

    private fun outputOptions(usePreviewDefault: Boolean = true) {
        if (finalizing) return
        outputPopup?.dismiss()
        if (usePreviewDefault) change(draft.options.forPreview(previewHtml, prepared?.hasAnimation() == true), record = false)
        val content = LinearLayout(dialogContext).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        buildControls(3, ExportControls(content.context, content, startInset = dp(24)))
        outputPopup = popup(CommonStrings.export_save, content, android.R.string.cancel).apply {
            setButton(AlertDialog.BUTTON_POSITIVE, getString(CommonStrings.export_save)) { _, _ -> }
            setOnShowListener {
                outputSave = getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    isEnabled = !preparing && prepared?.events?.isNotEmpty() == true
                    setOnClickListener { if (!preparing) { dismiss(); requestExport() } }
                }
            }
            show()
        }
        Timber.d("ExportTrace output popup opened format=%s", draft.options.format)
    }

    private fun buildControls(index: Int, controls: ExportControls) {
        val startedAt = SystemClock.elapsedRealtime()
        val o = draft.options
        when (index) {
            0 -> {
                controls.toggle(CommonStrings.export_room_header, o.roomHeader) { change(draft.options.copy(roomHeader = it)) }
                controls.section(CommonStrings.export_selection_visibility, expanded = true) { fields ->
                    val rules = listOf("REDACTED" to CommonStrings.settings_show_redacted, "MEMBERSHIP" to CommonStrings.settings_show_join_leave_messages,
                            "PROFILE_CHANGES" to CommonStrings.settings_show_avatar_display_name_changes_messages,
                            "ROOM_SETTINGS" to CommonStrings.settings_show_room_settings_events, "ACL" to CommonStrings.settings_show_acl_events,
                            "REACTIONS" to CommonStrings.settings_show_reactions, "MESSAGE_MARKERS" to CommonStrings.settings_show_message_markers,
                            "HIDDEN_EVENTS" to CommonStrings.settings_labs_show_hidden_events_in_timeline,
                            "HIDDEN_THREAD_EVENTS" to CommonStrings.settings_labs_show_hidden_thread_events_in_timeline)
                    rules.forEach { (key, label) ->
                        fields.toggle(label, o.visible(key, key !in setOf("ACL", "REACTIONS", "HIDDEN_EVENTS", "HIDDEN_THREAD_EVENTS"))) {
                            change(draft.options.copy(visibility = draft.options.visibility + (key to it)))
                        }
                    }
                }
                controls.section(CommonStrings.export_groups, expanded = true) { fields ->
                    fields.toggle(CommonStrings.export_expand_all, o.expandAll) { change(draft.options.copy(expandAll = it)) }
                    prepared?.let { snapshot ->
                        ExportBlocks.build(snapshot.events, o.timeZoneId).filter { it.groupId != null }.forEach { block ->
                            fields.toggle(resources.getQuantityString(CommonPlurals.export_group_count, block.events.size, block.events.size) + " · " + ExportBlocks.day(block.events.first().timestamp, o.timeZoneId),
                                    o.expandAll || block.groupId in o.expandedGroups) { expanded -> setGroupExpanded(block.groupId!!, expanded) }
                        }
                    }
                }
                controls.section(CommonStrings.export_dates_and_time, expanded = true) { fields ->
                    fields.toggle(CommonStrings.export_timestamps, o.timestamps) { change(draft.options.copy(timestamps = it)) }
                    fields.toggle(CommonStrings.export_12_hour, o.twelveHourTime) { change(draft.options.copy(twelveHourTime = it)) }
                    fields.toggle(CommonStrings.export_utc, o.timeZoneId == "UTC") { change(draft.options.copy(timeZoneId = if (it) "UTC" else TimeZone.getDefault().id)) }
                    fields.toggle(CommonStrings.export_dates, o.dates) { change(draft.options.copy(dates = it)) }
                }
                controls.section(CommonStrings.export_message_details) { fields ->
                    fields.toggle(CommonStrings.export_receipts, o.receipts) { change(draft.options.copy(receipts = it)) }
                    fields.toggle(CommonStrings.export_reactions, o.reactionSummary) { change(draft.options.copy(reactionSummary = it)) }
                    fields.toggle(CommonStrings.export_thread_summary, o.threadSummary) { change(draft.options.copy(threadSummary = it)) }
                    fields.toggle(CommonStrings.settings_reveal_undisclosed_poll_results, o.visible("POLL_RESULTS", vectorPreferences.revealUndisclosedPollResults())) {
                        change(draft.options.copy(visibility = draft.options.visibility + ("POLL_RESULTS" to it)))
                    }
                    fields.toggle(CommonStrings.export_edit_history, o.editHistory) { change(draft.options.copy(editHistory = it)) }
                }
            }
            1 -> {
                controls.preferenceChoice(CommonStrings.settings_theme, R.array.sc_theme_entries, R.array.sc_theme_values, o.theme) { theme ->
                    change(themedOptions(draft.options.copy(theme = theme)))
                }
                val accentNames = resources.getStringArray(R.array.sc_accent_color_light_entries)
                val accentValues = resources.getStringArray(R.array.sc_accent_color_light_values)
                controls.preferenceAction(CommonStrings.settings_accent, accentNames.getOrNull(accentValues.indexOf(o.accentName)).orEmpty()) { accentPicker() }
                controls.preferenceChoice(CommonStrings.settings_sc_bubble_style_title, R.array.sc_bubble_style_entries, R.array.sc_bubble_style_values,
                        o.bubbleStyle()) { change(draft.options.copy(bubbles = it)) }
                controls.section(CommonStrings.settings_sc_bubble_appearance) { fields ->
                    fields.preferenceChoice(CommonStrings.settings_sc_bubble_roundness_title, R.array.sc_bubble_roundness_entries, R.array.sc_bubble_roundness_values,
                            o.bubbleCorners) { change(draft.options.copy(bubbleCorners = it)) }
                    fields.toggle(CommonStrings.settings_sc_bubble_tail_title, o.bubbleTail) { change(draft.options.copy(bubbleTail = it)) }
                    fields.toggle(CommonStrings.settings_sc_bubble_tint_title, o.bubbleTint) { change(draft.options.copy(bubbleTint = it)) }
                }
                val shape = AvatarShape.values().firstOrNull { it.name == o.avatarShape } ?: AvatarShape.CIRCLE
                controls.preferenceAction(CommonStrings.settings_avatar_shape_title, getString(shape.titleRes)) { avatarShapePicker() }
                fun paletteLabel(name: String, room: Boolean): String = getString((ColorPalette.values().firstOrNull { it.name == name }
                        ?: if (room) vectorPreferences.roomColorPalette() else vectorPreferences.peopleColorPalette()).titleRes)
                controls.preferenceAction(CommonStrings.settings_people_color_palette_title, paletteLabel(o.peoplePalette, false)) { palettePicker(false) }
                controls.preferenceAction(CommonStrings.settings_room_color_palette_title, paletteLabel(o.roomPalette, true)) { palettePicker(true) }
                fun avatarLabel(name: String, room: Boolean): String = getString((DefaultAvatarStyle.values().firstOrNull { it.name == name }
                        ?: if (room) vectorPreferences.roomAvatarStyle() else vectorPreferences.peopleAvatarStyle()).titleRes)
                controls.preferenceAction(CommonStrings.settings_people_avatar_style_title, avatarLabel(o.peopleAvatarStyle, false)) { avatarPicker(false) }
                controls.preferenceAction(CommonStrings.settings_room_avatar_style_title, avatarLabel(o.roomAvatarStyle, true)) { avatarPicker(true) }
                controls.toggle(CommonStrings.settings_show_others_profile_colors_title, o.showProfileColors) { change(draft.options.copy(showProfileColors = it)) }
                controls.preferenceAction(CommonStrings.settings_code_palette, getString(CodePalette.of(o.codePalette).titleRes)) {
                    ExportAppearancePickers.codePalette(this, draft.options.codePalette) { change(draft.options.copy(codePalette = it)); refreshPanel() }
                }
                controls.action(CommonStrings.export_reset_appearance) {
                    change(draft.options.withAppearance(builder.defaults(activeSessionHolder.getActiveSession(), draft.roomId)))
                    refreshPanel()
                }
            }
            2 -> {
                controls.section(CommonStrings.export_media, expanded = true) { fields ->
                    fields.choice(CommonStrings.export_media, visibilityChoices, o.media) { change(draft.options.copy(media = it)) }
                    fields.choice(CommonStrings.export_avatars, visibilityChoices, o.avatars) { change(draft.options.copy(avatars = it)) }
                    fields.toggle(CommonStrings.export_omit_attachments, o.omitAttachments) { change(draft.options.copy(omitAttachments = it)) }
                }
                controls.section(CommonStrings.export_reveal_redacted) { fields ->
                    fields.choice(CommonStrings.export_reveal_redacted, listOf("CURRENT" to CommonStrings.export_current, "NONE" to CommonStrings.export_none) +
                            (if (prepared?.redactionRecoveryAvailable == true) listOf("ALL" to CommonStrings.export_everyone, "SELF" to CommonStrings.export_self, "OTHERS" to CommonStrings.export_others) else emptyList()),
                            o.revealRedacted) { change(draft.options.copy(revealRedacted = it)) }
                }
                controls.identity(o.identity, draft.owner, pickPeople = { title, excluded ->
                    peoplePicker.launch(AnonymizationPeoplePickerActivity.intent(this, title, excluded, draft.roomId))
                }, trueName = { userId, onName ->
                    lifecycleScope.launch {
                        val member = withContext(Dispatchers.IO) { activeSessionHolder.getActiveSession().getRoom(draft.roomId)?.membershipService()?.getRoomMember(userId) }
                        (member?.originalDisplayName ?: member?.displayName)?.let(onName)
                    }
                }) { change(draft.options.copy(identity = it)) }
                controls.toggle(CommonStrings.export_hide_room_identity, o.hideRoomIdentity) { change(draft.options.copy(hideRoomIdentity = it)) }
                controls.text(getString(CommonStrings.export_identity_content_note))
            }
            3 -> {
                val formats = listOf("PNG" to CommonStrings.export_png) +
                        (if (prepared?.hasAnimation() == true) listOf("WEBP" to CommonStrings.export_webp) else emptyList()) +
                        listOf("HTML" to CommonStrings.export_html, "JSON" to CommonStrings.export_json)
                controls.choice(CommonStrings.export_format, formats, o.format) {
                    change(draft.options.copy(format = it))
                    outputOptions(usePreviewDefault = false)
                }
                if (o.format == "HTML") {
                    controls.toggle(CommonStrings.export_single_html, o.singleHtml) { change(draft.options.copy(singleHtml = it)) }
                }
                if (o.format in setOf("PNG", "WEBP")) {
                    controls.edit(CommonStrings.export_split_height, o.maxHeight.toString(), true) { value -> value.toIntOrNull()?.takeIf { it == 0 || it in 128..100000 }?.let { change(draft.options.copy(maxHeight = it)) } }
                    controls.text(getString(CommonStrings.export_split_help))
                }
                if (o.format == "WEBP") {
                    controls.slider(CommonStrings.export_duration, o.durationSeconds, 1, 60) { change(draft.options.copy(durationSeconds = it)) }
                    controls.slider(CommonStrings.export_fps, o.fps, 1, 60) { change(draft.options.copy(fps = it)) }
                    controls.slider(CommonStrings.export_quality, o.quality, 1, 100) { change(draft.options.copy(quality = it)) }
                }
            }
        }
        Timber.d("ExportTrace panel opened category=%d elapsedMs=%d", index, SystemClock.elapsedRealtime() - startedAt)
    }

    private fun themedOptions(options: TimelineExportOptions): TimelineExportOptions {
        val context = android.view.ContextThemeWrapper(this, ExportTheme.style(options.theme, options.accentName))
        fun color(attribute: Int): String {
            val value = TypedValue()
            context.theme.resolveAttribute(attribute, value, true)
            val color = if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
            return TimelineExportHtml.cssColor(color)
        }
        return options.copy(accent = color(com.google.android.material.R.attr.colorAccent), background = color(android.R.attr.colorBackground),
                foreground = color(im.vector.lib.ui.styles.R.attr.vctr_content_primary), incoming = color(im.vector.lib.ui.styles.R.attr.sc_message_bg_incoming),
                outgoing = color(im.vector.lib.ui.styles.R.attr.sc_message_bg_outgoing))
    }

    private fun accentPicker() = ExportAppearancePickers.accent(this, draft.options.accentName) {
        change(themedOptions(draft.options.copy(accentName = it)))
        refreshPanel()
    }

    private fun palettePicker(room: Boolean) = ExportAppearancePickers.palette(this, room, if (room) draft.options.roomPalette else draft.options.peoplePalette) {
        change(if (room) draft.options.copy(roomPalette = it) else draft.options.copy(peoplePalette = it))
        refreshPanel()
    }

    private fun avatarPicker(room: Boolean) = ExportAppearancePickers.avatar(this, room, if (room) draft.options.roomAvatarStyle else draft.options.peopleAvatarStyle) {
        change(if (room) draft.options.copy(roomAvatarStyle = it) else draft.options.copy(peopleAvatarStyle = it))
        refreshPanel()
    }

    private fun avatarShapePicker() = ExportAppearancePickers.avatarShape(this, draft.options.avatarShape) {
        change(draft.options.copy(avatarShape = it))
        refreshPanel()
    }

    private fun presets() {
        val saved = store.presets(draft.owner)
        val labels = listOf(getString(CommonStrings.export_save_preset)) + saved.keys
        MaterialAlertDialogBuilder(dialogContext).setTitle(CommonStrings.export_presets).setItems(labels.toTypedArray()) { _, index ->
            if (index == 0) {
                val field = AppCompatEditText(dialogContext)
                val frame = android.widget.FrameLayout(dialogContext).apply { setPadding(dp(24), 0, dp(24), 0); addView(field) }
                MaterialAlertDialogBuilder(dialogContext).setTitle(CommonStrings.export_save_preset).setView(frame).setPositiveButton(android.R.string.ok) { _, _ ->
                    val name = field.text.toString().trim()
                    if (name.isNotEmpty()) store.savePreset(draft.owner, name, TimelineExportOptions().withAppearance(draft.options))
                }.setNegativeButton(android.R.string.cancel, null).show()
            } else {
                val name = saved.keys.elementAt(index - 1)
                AlertDialog.Builder(dialogContext).setTitle(name).setPositiveButton(CommonStrings.export_apply) { _, _ -> change(draft.options.withAppearance(saved.getValue(name))); refreshPanel() }
                        .setNeutralButton(CommonStrings.export_delete_preset) { _, _ -> store.deletePreset(draft.owner, name) }
                        .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
    }

    private fun requestExport() {
        val snapshot = prepared ?: return
        if (preparing || snapshot.events.isEmpty()) return
        if (snapshot.options.format == "WEBP") {
            val doc = preview.document ?: return
            val budget = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(64L * 1024 * 1024)
            val maxHeight = minOf(16383L, budget / (snapshot.options.width * 4L)).toInt().coerceAtLeast(1)
            val scale = snapshot.options.width.toFloat() / doc.logicalWidth
            val parts = ExportSplitPlanner.split(doc.boundaries(snapshot.options.width), (doc.height * scale).toInt(), snapshot.options.maxHeight)
            if (parts.any { it.height > maxHeight }) {
                MaterialAlertDialogBuilder(dialogContext).setTitle(CommonStrings.export_size_limit).setMessage(getString(CommonStrings.export_size_limit_message, maxHeight))
                        .setPositiveButton(CommonStrings.export_split) { _, _ -> change(draft.options.copy(maxHeight = maxHeight)) }
                        .setNeutralButton(CommonStrings.export_smaller) { _, _ -> change(draft.options.copy(width = maxOf(240, draft.options.width / 2))) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                return
            }
        }
        pendingSnapshot = snapshot
        if (snapshot.issues.isNotEmpty()) MaterialAlertDialogBuilder(dialogContext).setTitle(CommonStrings.export_incomplete).setMessage(snapshot.issues.joinToString("\n"))
                .setPositiveButton(CommonStrings.export_continue) { _, _ -> ensurePermission() }
                .setNeutralButton(CommonStrings.export_retry) { _, _ -> prepared = null; prepare() }
                .setNegativeButton(android.R.string.cancel, null).show()
        else ensurePermission()
    }

    private fun ensurePermission() {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else finalizeExport()
    }

    private fun finalizeExport() {
        val snapshot = pendingSnapshot ?: return
        if (finalizing) return
        finalizing = true
        preparing = true
        editor.setEditingEnabled(false)
        updateHistoryActions()
        outputPopup?.dismiss()
        status.setText(CommonStrings.export_preparing)
        val stagedId = UUID.randomUUID().toString()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val source = store.directory(draft.owner, draft.id)
                    val minimumBytes = store.stageBytes(snapshot, source)
                    if (source.usableSpace < minimumBytes) error(getString(CommonStrings.export_not_enough_space))
                    store.stage(snapshot, source, stagedId)
                }
                val name = "Voyage_export_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
                queue.enqueueExport(snapshot.owner, draft.roomId, ExportJobState(stagedId, name, snapshot.selectedCount))
                Toast.makeText(this@TimelineExportActivity, CommonStrings.export_queued, Toast.LENGTH_LONG).show()
                finalizing = false
                preparing = false
                editor.setEditingEnabled(true)
                updateHistoryActions()
                status.setText(CommonStrings.export_queued)
                Timber.d("ExportTrace queued editor retained")
            } catch (failure: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { store.discard(snapshot.owner, stagedId) }
                throw failure
            } catch (failure: Exception) {
                withContext(Dispatchers.IO) { store.discard(snapshot.owner, stagedId) }
                finalizing = false
                preparing = false
                editor.setEditingEnabled(true)
                updateHistoryActions()
                showError(failure.localizedMessage ?: getString(CommonStrings.export_failed))
            }
        }
    }

    private fun discard() {
        MaterialAlertDialogBuilder(dialogContext).setTitle(CommonStrings.export_discard).setMessage(CommonStrings.export_discard_message)
                .setPositiveButton(CommonStrings.export_discard) { _, _ ->
                    discarded = true
                    finalizing = true
                    editor.setEditingEnabled(false)
                    lifecycleScope.launch {
                        saveJob?.cancelAndJoin()
                        preparation?.cancelAndJoin()
                        htmlPreparation?.cancelAndJoin()
                        animation?.cancelAndJoin()
                        withContext(Dispatchers.IO) { store.discard(draft.owner, draft.id) }
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_DISCARDED, true))
                        finish()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showError(message: String) { MaterialAlertDialogBuilder(dialogContext).setMessage(message).setPositiveButton(android.R.string.ok, null).show() }
    override fun onStop() {
        animation?.cancel()
        htmlPreparation?.cancel()
        htmlPreview?.onPause()
        if (::draft.isInitialized && !discarded) {
            val value = draft
            saveJob?.cancel()
            saveJob = lifecycleScope.launch(NonCancellable + Dispatchers.IO) { store.save(value) }
        }
        super.onStop()
    }
    override fun onStart() {
        super.onStart()
        editor.post {
            if (::draft.isInitialized && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (previewHtml) { htmlPreview?.onResume(); prepared?.let { refreshHtml(it) } } else refreshAnimation()
            }
        }
    }
    override fun onDestroy() {
        preparation?.cancel(); animation?.cancel(); htmlPreparation?.cancel()
        preview.document?.release()
        htmlPreview?.let { web -> web.stopLoading(); frame.removeView(web); web.destroy() }
        outputPopup?.dismiss()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DISCARDED = "discard_export"
        val visibilityChoices = listOf("CURRENT" to CommonStrings.export_current, "SHOW" to CommonStrings.export_show, "HIDE" to CommonStrings.export_hide)
        fun intent(context: Context, room: String, thread: String?): Intent = Intent(context, TimelineExportActivity::class.java).putExtra("room", room).putExtra("thread", thread)
    }
}
