/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.marginEndCompat
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber
import java.io.File
import java.text.DateFormat
import javax.inject.Inject

@AndroidEntryPoint
class TimelineArchiveLibraryActivity : VectorBaseActivity<ExportViewBinding>() {
    @Inject lateinit var archives: TimelineArchiveStore
    @Inject lateinit var avatars: AvatarRenderer
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var archiveToolbar: MaterialToolbar
    private var entries = emptyList<TimelineArchiveStore.Entry>()
    private var busy = false
    private var refreshJob: Job? = null
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) lifecycleScope.launch {
            val owner = activeSessionHolder.getSafeActiveSession()?.myUserId ?: return@launch
            busy = true; archiveToolbar.menu.findItem(1).isEnabled = false; progress.isVisible = true
            status.setText(CommonStrings.archive_importing)
            status.isVisible = true
            try {
                val id = withContext(Dispatchers.IO) {
                    val name = im.vector.app.core.intent.getFilenameFromUri(this@TimelineArchiveLibraryActivity, uri).orEmpty()
                    val input = contentResolver.openInputStream(uri) ?: error(getString(CommonStrings.archive_error_read))
                    input.use { archives.importArchive(owner, it, name) { value -> runOnUiThread { progress.progress = value } } }
                }
                startActivity(Intent(this@TimelineArchiveLibraryActivity, TimelineArchiveActivity::class.java).putExtra("archive", id))
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                Timber.e(failure, "ExportTrace archive picker import failed")
                AlertDialog.Builder(this@TimelineArchiveLibraryActivity).setTitle(CommonStrings.archive_import_failed).setMessage(failure.localizedMessage)
                        .setPositiveButton(CommonStrings.ok, null).show()
            } finally { busy = false; archiveToolbar.menu.findItem(1).isEnabled = true; progress.isVisible = false; refresh() }
        }
    }
    override val rootView: View get() = views.root
    override fun getBinding(): ExportViewBinding {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        archiveToolbar = MaterialToolbar(this).apply {
            setTitle(CommonStrings.archive_library)
            navigationIcon = AppCompatResources.getDrawable(context, R.drawable.ic_back_24dp)
            setNavigationOnClickListener { finish() }
            menu.add(0, 1, 0, CommonStrings.archive_import).apply {
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_archive_import)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            setOnMenuItemClickListener {
                if (!busy) picker.launch("*/*")
                true
            }
        }
        root.addView(archiveToolbar, LinearLayout.LayoutParams(-1, -2))
        progress = LinearProgressIndicator(this).apply { max = 100; isVisible = false }
        root.addView(progress, LinearLayout.LayoutParams(-1, -2))
        status = layoutInflater.inflate(R.layout.item_no_result, root, false) as TextView
        status.setTextColor(ThemeUtils.getColor(this, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.setOnItemClickListener { _, _, position, _ ->
            if (!busy) startActivity(Intent(this, TimelineArchiveActivity::class.java).putExtra("archive", entries[position].id))
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (!busy) confirmDelete(entries[position])
            true
        }
        return ExportViewBinding(root)
    }
    override fun onResume() { super.onResume(); if (::list.isInitialized && !busy) refresh() }
    private fun refresh() {
        val owner = activeSessionHolder.getSafeActiveSession()?.myUserId ?: return
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { archives.list(owner) }
            if (busy || activeSessionHolder.getSafeActiveSession()?.myUserId != owner) return@launch
            entries = loaded
            list.adapter = ArchiveRows(owner)
            status.setText(CommonStrings.archive_library_empty)
            status.isVisible = entries.isEmpty()
        }
    }
    private inner class ArchiveRows(private val owner: String) : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            // Avatar size, gap and text styles of the room list's rows.
            val row = convertView as? LinearLayout ?: LinearLayout(parent.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                val margin = resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.layout_horizontal_margin)
                setPadding(margin, dp(8), margin, dp(8))
                val avatarSize = resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.room_avatar_decode_size)
                addView(ImageView(context), LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEndCompat = margin })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        id = android.R.id.text1
                        TextViewCompat.setTextAppearance(this, im.vector.lib.ui.styles.R.style.TextAppearance_Vector_Subtitle)
                        setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
                        setTypeface(typeface, Typeface.BOLD)
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        id = android.R.id.text2
                        TextViewCompat.setTextAppearance(this, im.vector.lib.ui.styles.R.style.TextAppearance_Vector_Body)
                        setTextColor(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val entry = entries[position]
            row.findViewById<TextView>(android.R.id.text1).text = entry.name
            row.findViewById<TextView>(android.R.id.text2).text = resources.getQuantityString(CommonPlurals.archive_library_entry_details, entry.events, entry.events,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(java.util.Date(entry.importedAt)))
            val image = row.getChildAt(0) as ImageView
            // A reused row may still be loading the previous archive's avatar.
            GlideApp.with(image).clear(image)
            val item = MatrixItem.RoomItem("!archive-${entry.id}:archive.invalid", entry.name)
            avatars.renderStatic(item, entry.color ?: Color.GRAY, null, image)
            entry.avatar?.let { path -> avatars.render(item.copy(avatarUrl = Uri.fromFile(File(archives.directory(owner, entry.id), path)).toString()), image) }
            return row
        }
    }
    private fun confirmDelete(entry: TimelineArchiveStore.Entry) {
        AlertDialog.Builder(this).setTitle(CommonStrings.archive_remove).setMessage(getString(CommonStrings.archive_remove_description, entry.name))
                .setNegativeButton(CommonStrings.action_cancel, null).setPositiveButton(CommonStrings.action_delete) { _, _ ->
                    val owner = activeSessionHolder.getSafeActiveSession()?.myUserId ?: return@setPositiveButton
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { archives.delete(owner, entry.id) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Timber.e(failure, "ExportTrace archive delete failed")
                        }
                        refresh()
                    }
                }.show()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
