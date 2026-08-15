/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.image

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityImageEditorBinding
import im.vector.app.features.attachments.editor.restoreOriginalResult
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@AndroidEntryPoint
class ImageEditorActivity : VectorBaseActivity<ActivityImageEditorBinding>() {

    private lateinit var sourceUri: Uri
    private var displayName: String? = null
    private var sourceMimeType: String? = null
    private var initialEdits: ImageEditorEdits? = null
    private var activeToolFill: Int = Color.WHITE
    private var activeToolContent: Int = Color.WHITE

    override fun getOtherThemes() = ActivityOtherThemes.AttachmentsPreview

    override fun getBinding() = ActivityImageEditorBinding.inflate(layoutInflater)

    override val rootView: View
        get() = views.coordinatorLayout

    override fun initUiAndData() {
        makeSystemBarsTransparent()
        sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.toUri() ?: run { finish(); return }
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        sourceMimeType = intent.getStringExtra(EXTRA_MIME_TYPE)

        setupToolbar(views.imageEditorToolbar).allowBack()

        val (fill, onFill) = ThemeUtils.accentFillOnDarkSurface(this)
        activeToolFill = fill
        activeToolContent = onFill
        views.imageEditorSaveButton.backgroundTintList = ColorStateList.valueOf(fill)
        ImageViewCompat.setImageTintList(views.imageEditorSaveButton, ColorStateList.valueOf(onFill))
        views.imageEditorRotateButton.backgroundTintList = ColorStateList.valueOf(INACTIVE_FAB_COLOR)

        views.imageEditorSaveButton.setOnClickListener { save() }
        views.imageEditorCensorButton.setOnClickListener { toggleCensorTool() }
        views.imageEditorRotateButton.setOnClickListener { views.imageEditorView.rotateClockwise() }

        views.imageEditorView.onToolChanged = { applyTool(it) }
        applyTool(ImageEditorView.Tool.CROP)
        initialEdits = intent.getParcelableExtraCompat(EXTRA_EDITS)
        initialEdits?.let { views.imageEditorView.restoreEdits(it) }
        loadBitmap()
    }

    private fun toggleCensorTool() {
        val next = if (views.imageEditorView.tool == ImageEditorView.Tool.CENSOR) {
            ImageEditorView.Tool.CROP
        } else {
            ImageEditorView.Tool.CENSOR
        }
        applyTool(next)
        if (next == ImageEditorView.Tool.CENSOR) {
            Toast.makeText(this, getString(CommonStrings.image_editor_censor_hint), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyTool(tool: ImageEditorView.Tool) {
        views.imageEditorView.tool = tool
        val active = tool == ImageEditorView.Tool.CENSOR
        views.imageEditorCensorButton.backgroundTintList =
                ColorStateList.valueOf(if (active) activeToolFill else INACTIVE_FAB_COLOR)
        ImageViewCompat.setImageTintList(
                views.imageEditorCensorButton,
                ColorStateList.valueOf(if (active) activeToolContent else Color.WHITE)
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_image_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.imageEditorResetAction) {
            views.imageEditorView.resetEdits()
            applyTool(ImageEditorView.Tool.CROP)
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun loadBitmap() {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { ImageEditorExporter.loadForDisplay(this@ImageEditorActivity, sourceUri) }.getOrNull()
            }
            if (bitmap == null) {
                Toast.makeText(this@ImageEditorActivity, getString(CommonStrings.image_editor_load_failed), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                views.imageEditorView.setBitmap(bitmap)
            }
        }
    }

    private fun save() {
        val edits = views.imageEditorView.currentEdits()
        // Left exactly as it was opened: the attachment already is this export.
        if (edits == initialEdits) {
            finish()
            return
        }
        if (!edits.hasChanges) {
            setResult(RESULT_OK, restoreOriginalResult())
            finish()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ImageEditorExporter.export(this@ImageEditorActivity, sourceUri, edits, displayName, sourceMimeType)
                }.onFailure { Timber.w(it, "Failed to export edited image") }.getOrNull()
            }
            if (result == null) {
                Toast.makeText(this@ImageEditorActivity, getString(CommonStrings.image_editor_save_failed), Toast.LENGTH_SHORT).show()
            } else {
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_RESULT_URI, result.uri.toString())
                    putExtra(EXTRA_RESULT_WIDTH, result.width)
                    putExtra(EXTRA_RESULT_HEIGHT, result.height)
                    putExtra(EXTRA_RESULT_SIZE, result.size)
                    putExtra(EXTRA_RESULT_MIME_TYPE, result.mimeType)
                    putExtra(EXTRA_RESULT_EDITS, edits)
                })
                finish()
            }
        }
    }

    data class Output(
            val uri: Uri,
            val width: Int,
            val height: Int,
            val size: Long,
            val mimeType: String,
            val edits: ImageEditorEdits
    )

    companion object {
        /** Translucent dark, so the secondary tools read as controls without competing with save. */
        private const val INACTIVE_FAB_COLOR = 0xB0333333.toInt()

        private const val EXTRA_SOURCE_URI = "EXTRA_SOURCE_URI"
        private const val EXTRA_DISPLAY_NAME = "EXTRA_DISPLAY_NAME"
        private const val EXTRA_MIME_TYPE = "EXTRA_MIME_TYPE"
        private const val EXTRA_RESULT_URI = "EXTRA_RESULT_URI"
        private const val EXTRA_RESULT_WIDTH = "EXTRA_RESULT_WIDTH"
        private const val EXTRA_RESULT_HEIGHT = "EXTRA_RESULT_HEIGHT"
        private const val EXTRA_RESULT_SIZE = "EXTRA_RESULT_SIZE"
        private const val EXTRA_RESULT_MIME_TYPE = "EXTRA_RESULT_MIME_TYPE"
        private const val EXTRA_EDITS = "EXTRA_EDITS"
        private const val EXTRA_RESULT_EDITS = "EXTRA_RESULT_EDITS"

        fun newIntent(context: Context, source: Uri, displayName: String?, mimeType: String?, edits: ImageEditorEdits?): Intent {
            return Intent(context, ImageEditorActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_URI, source.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_EDITS, edits)
            }
        }

        fun getOutput(intent: Intent): Output? {
            val uri = intent.getStringExtra(EXTRA_RESULT_URI)?.toUri() ?: return null
            return Output(
                    uri = uri,
                    width = intent.getIntExtra(EXTRA_RESULT_WIDTH, 0),
                    height = intent.getIntExtra(EXTRA_RESULT_HEIGHT, 0),
                    size = intent.getLongExtra(EXTRA_RESULT_SIZE, 0),
                    mimeType = intent.getStringExtra(EXTRA_RESULT_MIME_TYPE).orEmpty(),
                    edits = intent.getParcelableExtraCompat(EXTRA_RESULT_EDITS) ?: ImageEditorEdits()
            )
        }
    }
}
