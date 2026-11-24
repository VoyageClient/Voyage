/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.setExpandedContentView
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.utils.shareMedia
import im.vector.app.features.home.room.detail.ScrollAnchorAlignment
import im.vector.app.features.home.room.detail.ScrollAnchorMath
import im.vector.app.features.home.room.detail.search.SearchActivity
import im.vector.app.features.home.room.detail.search.SearchArgs
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.action.MessageActionsBottomSheet
import im.vector.app.features.home.room.detail.timeline.action.MessageSharedActionViewModel
import im.vector.app.features.home.room.detail.timeline.item.AbsMessageItem
import im.vector.app.features.home.room.detail.timeline.item.BaseEventItem
import im.vector.app.features.home.room.detail.timeline.item.MessageAudioItem
import im.vector.app.features.home.room.detail.timeline.item.MessageGalleryItem
import im.vector.app.features.home.room.detail.timeline.item.MessageImageVideoItem
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.MessageTextItem
import im.vector.app.features.home.room.detail.timeline.item.MessageVoiceItem
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptData
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptsItem
import im.vector.app.features.home.room.detail.timeline.readreceipts.DisplayReadReceiptsBottomSheet
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VectorAttachmentViewerActivity
import im.vector.app.features.media.VideoContentRenderer
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.roommemberprofile.RoomMemberProfileActivity
import im.vector.app.features.roommemberprofile.RoomMemberProfileArgs
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class TimelineArchiveActivity : VectorBaseActivity<ExportViewBinding>() {
    @Inject lateinit var archives: TimelineArchiveStore
    @Inject lateinit var renderer: TimelineExportRenderer
    private lateinit var timeline: RecyclerView
    private lateinit var archiveToolbar: MaterialToolbar
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var owner: String
    private lateinit var archiveId: String
    private lateinit var directory: File
    private var snapshot: TimelineExportSnapshot? = null
    private var document: TimelineExportRenderer.Document? = null
    private var render: Job? = null
    private var assets: Job? = null
    private var renderedWidth = 0
    private var renderRevision = 0L
    private var initialPosition = 0
    private var initialOffset = 0
    private val audio by lazy { ArchiveAudioPlayback(this) }
    override val rootView: View get() = views.root
    override fun getBinding(): ExportViewBinding {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        archiveToolbar = MaterialToolbar(this).apply {
            navigationIcon = AppCompatResources.getDrawable(context, R.drawable.ic_back_24dp)
            setNavigationOnClickListener { finish() }
            menu.add(0, 1, 0, CommonStrings.export_search).setIcon(R.drawable.ic_search).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, 4, 3, CommonStrings.export_expand_all).isVisible = false
            menu.add(0, 5, 4, CommonStrings.export_collapse_all).isVisible = false
            menu.add(0, 6, 5, CommonStrings.archive_import_details).isVisible = false
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> snapshot?.let { source -> startActivity(SearchActivity.newIntent(this@TimelineArchiveActivity,
                            SearchArgs(archiveRoomId(), source.roomName, null, archiveId))) }
                    6 -> snapshot?.issues?.let { issues -> AlertDialog.Builder(this@TimelineArchiveActivity).setTitle(CommonStrings.archive_import_details).setMessage(issues.joinToString("\n\n")).setPositiveButton(CommonStrings.ok, null).show() }
                    4, 5 -> snapshot?.let { snapshot = it.copy(options = it.options.copy(expandAll = item.itemId == 4, expandedGroups = emptySet())); rebuild() }
                }; true
            }
        }
        root.addView(archiveToolbar, LinearLayout.LayoutParams(-1, -2))
        // Only for a failure; while opening, the bar alone shows progress.
        status = TextView(this).apply { setPadding(dp(16), dp(4), dp(16), dp(4)); isVisible = false }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        progress = LinearProgressIndicator(this)
        root.addView(progress, LinearLayout.LayoutParams(-1, -2))
        timeline = RecyclerView(this).apply {
            // Opens at the newest message, with a short archive resting on the bottom, as the timeline does.
            layoutManager = LinearLayoutManager(this@TimelineArchiveActivity).apply { stackFromEnd = true }
            itemAnimator = null
            isVerticalScrollBarEnabled = true
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { loadVisible() }
            })
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft && right > left && snapshot != null && renderedWidth != right - left) rebuild()
            }
        }
        root.addView(timeline, LinearLayout.LayoutParams(-1, 0, 1f))
        return ExportViewBinding(root)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner = activeSessionHolder.getSafeActiveSession()?.myUserId ?: run { finish(); return }
        archiveId = intent.getStringExtra("archive") ?: run { finish(); return }
        viewModelProvider.get(MessageSharedActionViewModel::class.java).stream()
                .onEach(messageActions::handle).launchIn(lifecycleScope)
        directory = archives.directory(owner, archiveId)
        initialPosition = savedInstanceState?.getInt("position") ?: -1
        initialOffset = savedInstanceState?.getInt("offset") ?: 0
        lifecycleScope.launch {
            try {
                snapshot = withContext(Dispatchers.IO) { archives.read(owner, archiveId) }
                archiveToolbar.title = snapshot!!.roomName
                archiveToolbar.subtitle = getString(CommonStrings.archive_read_only)
                archiveToolbar.menu.findItem(6).isVisible = snapshot!!.issues.isNotEmpty()
                timeline.post { rebuild() }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { failed(failure) }
        }
    }
    private fun rebuild() {
        val source = snapshot ?: return
        if (timeline.width <= 0) return
        val revision = ++renderRevision
        render?.cancel(); assets?.cancel()
        val manager = timeline.layoutManager as LinearLayoutManager
        val position = if (document == null) initialPosition else manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val offset = if (document == null) initialOffset else manager.findViewByPosition(position)?.top ?: 0
        val width = timeline.width
        renderedWidth = width
        progress.isVisible = true
        render = lifecycleScope.launch {
            var next: TimelineExportRenderer.Document? = null
            try {
                next = renderer.layout(this@TimelineArchiveActivity, source.copy(options = source.options.copy(layoutWidth = (width / resources.displayMetrics.density).toInt())), directory) { current, total ->
                    runOnUiThread { if (renderRevision == revision) progress.progress = current * 100 / total.coerceAtLeast(1) }
                }
                next.clearBoundRows()
                prepareInteractions(next)
                timeline.adapter = null
                document?.release()
                document = next
                timeline.setBackgroundColor(Color.parseColor(TimelineExportHtml.color(source.options.background, "#17191c")))
                timeline.adapter = TimelineArchiveAdapter(next) { index, view -> bindInteractions(index, view) }
                if (position < 0) manager.scrollToPosition(next.rows.lastIndex)
                else manager.scrollToPositionWithOffset(position.coerceAtMost(next.rows.lastIndex), offset)
                next = null
                status.isVisible = false
                updateGroupMenu()
                timeline.post { loadVisible(); handleTarget() }
                Timber.i("ExportTrace archive viewer ready events=%d width=%d", source.events.size, width)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed(failure)
            } finally {
                next?.release()
                if (renderRevision == revision) progress.isVisible = false
            }
        }
    }
    private fun updateGroupMenu() {
        val groups = document?.blocks?.mapNotNull { it.groupId }?.toSet().orEmpty()
        val options = snapshot?.options ?: return
        val open = if (options.expandAll) groups else options.expandedGroups.intersect(groups)
        archiveToolbar.menu.findItem(4).isVisible = open.size < groups.size
        archiveToolbar.menu.findItem(5).isVisible = open.isNotEmpty()
    }

    // Search results come back to this instance rather than stacking a second copy of the archive.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("archive") != archiveId) { startActivity(intent.cloneFilter().putExtras(intent)); finish(); return }
        setIntent(intent)
        if (document != null && render?.isActive != true) handleTarget()
    }

    private fun handleTarget() {
        val id = intent.getStringExtra("event") ?: return
        val action = intent.getStringExtra("action")
        val attachment = intent.getStringExtra("attachment")
        intent.removeExtra("event"); intent.removeExtra("action"); intent.removeExtra("attachment")
        jump(id)
        snapshot?.events?.firstOrNull { it.id == id }?.withAttachment(attachment)?.let { event ->
            when (action) { "actions" -> actions(event); "save" -> saveAttachment(event) }
        }
    }

    private fun prepareInteractions(doc: TimelineExportRenderer.Document) {
        doc.models.forEachIndexed { index, model ->
            val event = doc.rows[index].events.firstOrNull() ?: return@forEachIndexed
            if (model is AbsMessageItem<*>) {
                model.attributes = model.attributes.copy(informationData = model.attributes.informationData.copy(isStatic = false),
                        memberClickListener = { openProfile(event.person.id) },
                        avatarCallback = object : TimelineEventController.AvatarCallback {
                            override fun onAvatarClicked(informationData: MessageInformationData) { openProfile(event.person.id) }
                            override fun onMemberNameClicked(informationData: MessageInformationData) { openProfile(event.person.id) }
                        },
                        itemClickListener = { event.attachment?.let { openMedia(event, it, event.attachmentMime) } },
                        itemLongClickListener = View.OnLongClickListener { actions(event); true })
                model.inReplyToClickCallback = object : TimelineEventController.InReplyToClickCallback {
                    override fun onRepliedToEventClicked(sourceEventId: String?, targetEventId: String) { jump(targetEventId) }
                }
            }
            if (model is MessageTextItem) {
                model.urlClickCallback = messageActions
                model.movementMethod = im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod(messageActions)
                if (ExportEditHistory.hasHistory(doc.snapshot.options, event)) {
                    val suffix = getString(CommonStrings.edited_suffix)
                    model.message = model.message?.charSequence?.let { ExportEditHistory.withSuffixClick(it, suffix) { showHistory(event) }.toEpoxyCharSequence() }
                    model.richFooter = model.richFooter?.let { ExportEditHistory.withSuffixClick(it, suffix) { showHistory(event) } }
                }
            }
            if (model is ReadReceiptsItem) {
                val readers = renderer.receiptsOf(doc.rows[index].events).map { receipt ->
                    val color = String.format("#%06X", receipt.person.color and 0xFFFFFF)
                    ReadReceiptData(receipt.person.id, receipt.person.avatar?.let { Uri.fromFile(File(directory, it)).toString() }, receipt.person.name,
                            receipt.timestamp, colorOnLight = color, colorOnDark = color)
                }
                model.clickListener = { DisplayReadReceiptsBottomSheet.newInstance(readers, includeMyself = true).show(supportFragmentManager, "DISPLAY_READ_RECEIPTS") }
            }
            if (model is MessageImageVideoItem) model.clickListener = { event.attachment?.let { openMedia(event, it, event.attachmentMime) } }
            if (model is MessageGalleryItem) {
                model.tileClickListener = { item, _ -> event.extraAttachments.getOrNull(item)?.let { attachment -> attachment.path?.let { openMedia(event.copy(attachmentName = attachment.name), it, attachment.mime) } } }
                model.tileLongClickListener = { item ->
                    if (event.extraAttachments.getOrNull(item) != null) actions(event, item)
                    true
                }
            }
            if (model is MessageAudioItem) {
                model.audioMessagePlaybackTracker = audio.tracker
                model.localSource = event.attachment?.let { Uri.fromFile(File(directory, it)) }
                model.playable = event.attachment != null
                model.playbackControlButtonClickListener = { play(event) }
                model.onSeek = { audio.seek(event.id, it) }
            }
            if (model is MessageVoiceItem) {
                model.audioMessagePlaybackTracker = audio.tracker
                model.playable = event.attachment != null
                model.playbackControlButtonClickListener = { play(event) }
                model.waveformTouchListener = object : MessageVoiceItem.WaveformTouchListener {
                    override fun onWaveformTouchedUp(percentage: Float) { audio.seek(event.id, percentage) }
                    override fun onWaveformMovedTo(percentage: Float) = Unit
                }
            }
        }
    }
    private fun loadVisible() {
        val doc = document ?: return
        val manager = timeline.layoutManager as LinearLayoutManager
        val first = manager.findFirstVisibleItemPosition(); val last = manager.findLastVisibleItemPosition()
        if (first < 0 || last < first) return
        val top = doc.rows.getOrNull(first)?.top ?: return
        val bottom = doc.rows.getOrNull(last)?.let { it.top + it.height } ?: return
        assets?.cancel()
        assets = lifecycleScope.launch {
            delay(24)
            renderer.prepareAssets(doc, top = top, bottom = bottom, waitForMedia = false, bindRows = false)
        }
    }
    private var highlightNonce = 0L

    // Lands and flashes the row as the timeline's own jumps do.
    private fun scrollToEvent(doc: TimelineExportRenderer.Document, id: String): Boolean {
        val index = doc.rows.indexOfFirst { row -> row.groupId == null && !row.implicit && row.events.any { it.id == id } }
        if (index < 0) return false
        val usable = timeline.height - timeline.paddingTop - timeline.paddingBottom
        val offset = ScrollAnchorMath.desiredOffset(ScrollAnchorAlignment.CENTER, usable, doc.rows[index].height).coerceAtLeast(0)
        timeline.stopScroll()
        (timeline.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, offset)
        val nonce = ++highlightNonce
        timeline.doOnNextLayout {
            val holder = timeline.findViewHolderForAdapterPosition(index) as? TimelineArchiveAdapter.RowHolder ?: return@doOnNextLayout
            val eventHolder = holder.epoxy.holder as? BaseEventItem.BaseHolder ?: return@doOnNextLayout
            (doc.models.getOrNull(index) as? BaseEventItem<*>)?.showHighlight(eventHolder, nonce)
        }
        return true
    }
    private fun jump(id: String) {
        val doc = document ?: return
        if (scrollToEvent(doc, id)) return
        val group = doc.blocks.firstOrNull { it.events.any { event -> event.id == id } }?.groupId ?: return
        snapshot = snapshot!!.copy(options = snapshot!!.options.copy(expandedGroups = snapshot!!.options.expandedGroups + group))
        rebuild()
        val pending = render
        lifecycleScope.launch {
            pending?.join()
            document?.takeIf { it !== doc }?.let { scrollToEvent(it, id) }
        }
    }
    private val messageActions by lazy { TimelineArchiveActions(this, supportFragmentManager, { snapshot }, { directory }, ::openProfile, ::jump, ::saveAttachment) }

    private fun archiveRoomId() = "!archive-$archiveId:archive.invalid"

    private fun openProfile(id: String) {
        startActivity(RoomMemberProfileActivity.newIntent(this,
                RoomMemberProfileArgs(id, archiveRoomId(), archiveId)))
    }

    private fun bindInteractions(index: Int, view: View) {
        val doc = document ?: return
        val row = doc.rows[index]
        if (row.groupId != null) view.setOnClickListener {
            val options = snapshot!!.options
            val openGroups = if (options.expandAll) doc.blocks.mapNotNull { it.groupId }.toSet() else options.expandedGroups
            snapshot = snapshot!!.copy(options = options.copy(expandAll = false, expandedGroups = if (row.groupId in openGroups) openGroups - row.groupId else openGroups + row.groupId))
            rebuild()
        }
    }

    private var historyJob: Job? = null
    private var historyDialog: BottomSheetDialog? = null

    private fun showHistory(event: ExportEvent) {
        val source = snapshot ?: return
        historyJob?.cancel()
        historyJob = lifecycleScope.launch {
            var pending: TimelineExportRenderer.Document? = null
            try {
                val options = ExportEditHistory.revisionOptions(source.options).copy(layoutWidth = (timeline.width / resources.displayMetrics.density).toInt())
                pending = renderer.layout(this@TimelineArchiveActivity, source.copy(events = ExportEditHistory.revisions(event), options = options), directory,
                        sheet = TimelineExportRenderer.Sheet.Revisions)
                val doc = pending
                doc.clearBoundRows()
                // The rows are drawn in the archive's theme, so the sheet takes that theme's surface rather than the app's.
                val (surface, titleColor) = NativeTimelineHtmlExporter.sheetColors(doc.context)
                val content = LinearLayout(this@TimelineArchiveActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(surface)
                    setPadding(0, dp(8), 0, 0)
                    addView(TextView(context).apply {
                        TextViewCompat.setTextAppearance(this, im.vector.lib.ui.styles.R.style.TextAppearance_Vector_Subtitle)
                        setTextColor(titleColor)
                        setText(CommonStrings.message_edits)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    })
                    addView(RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        itemAnimator = null
                        adapter = TimelineArchiveAdapter(doc)
                    }, LinearLayout.LayoutParams(-1, -2))
                }
                val images = launch { renderer.prepareAssets(doc, waitForMedia = false, bindRows = false) }
                historyDialog?.dismiss()
                historyDialog = BottomSheetDialog(this@TimelineArchiveActivity).apply {
                    setExpandedContentView(content)
                    setOnDismissListener { images.cancel(); doc.release(); if (historyDialog === this) historyDialog = null }
                    show()
                }
                pending = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.e(failure, "ExportTrace archive edit history failed")
            } finally {
                pending?.release()
            }
        }
    }

    private fun actions(event: ExportEvent, galleryIndex: Int? = null) {
        val model = document?.models?.filterIsInstance<AbsMessageItem<*>>()?.firstOrNull { it.attributes.informationData.eventId == event.id } ?: return
        viewModelProvider.get(MessageSharedActionViewModel::class.java).pendingAction = null
        MessageActionsBottomSheet.newInstance(archiveRoomId(), model.attributes.informationData,
                false, galleryItemIndex = galleryIndex, archiveId = archiveId).show(supportFragmentManager, "MESSAGE_CONTEXTUAL_ACTIONS")
    }

    @Inject lateinit var notifications: NotificationUtils
    private var pendingSave: ExportEvent? = null
    private val savePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingSave?.let(::saveAttachment)
        pendingSave = null
    }
    private fun saveAttachment(event: ExportEvent) {
        if (Build.VERSION.SDK_INT in 23..28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = event; savePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return
        }
        event.attachment?.let { path -> lifecycleScope.launch {
            try { im.vector.app.core.utils.saveMedia(this@TimelineArchiveActivity, File(directory, path), event.attachmentName ?: File(path).name, event.attachmentMime, notifications, System.currentTimeMillis()) } catch (failure: Exception) { Timber.e(failure, "ExportTrace archive save failed") }
        } }
    }
    private fun play(event: ExportEvent) { event.attachment?.let { audio.toggle(event.id, File(directory, it)) } }
    private fun openMedia(event: ExportEvent, path: String, mime: String?) {
        val file = File(directory, path).canonicalFile
        if (!file.path.startsWith(directory.canonicalPath + File.separator) || !file.isFile) return
        if (mime?.startsWith("audio/") == true) { audio.toggle(if (path == event.attachment) event.id else event.id + ":" + path, file); return }
        val image = ImageContentRenderer.Data(event.id, event.attachmentName ?: file.name, mime, null, null, null, 4096, null, 4096,
                preservedFile = file, senderName = event.person.name, timestampMs = event.timestamp)
        val data = if (mime?.startsWith("video/") == true) VideoContentRenderer.Data(event.id, image.filename, mime, null, null, image,
                preservedFile = file, senderName = event.person.name, timestampMs = event.timestamp) else image
        if (mime?.startsWith("image/") != true && mime?.startsWith("video/") != true) { shareMedia(this, file, mime); return }
        startActivity(VectorAttachmentViewerActivity.newIntent(this, data, null, event.id, listOf(data), null,
                standalonePreview = true, hideShowInChat = true, hideForward = true))
    }
    private fun failed(failure: Exception) {
        Timber.e(failure, "ExportTrace archive viewer failed")
        progress.isVisible = false
        status.setText(CommonStrings.archive_open_failed)
        status.isVisible = true
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val manager = timeline.layoutManager as LinearLayoutManager
        val position = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        outState.putInt("position", position); outState.putInt("offset", manager.findViewByPosition(position)?.top ?: 0)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { audio.pause(); super.onPause() }
    override fun onDestroy() { historyDialog?.dismiss(); timeline.adapter = null; audio.release(); assets?.cancel(); document?.release(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
