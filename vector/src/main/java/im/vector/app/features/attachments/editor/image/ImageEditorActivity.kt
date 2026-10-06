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
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityImageEditorBinding
import im.vector.app.databinding.ActivityVideoEditorBinding
import im.vector.app.features.attachments.editor.AspectRatioPicker
import im.vector.app.features.attachments.editor.EditorColorBar
import im.vector.app.features.attachments.editor.EditorHistoryCache
import im.vector.app.features.attachments.editor.restoreOriginalResult
import im.vector.app.features.attachments.editor.setEnabledDimmed
import im.vector.app.features.attachments.editor.video.AnimatedImageExporter
import im.vector.app.features.attachments.editor.video.VideoEditorEdits
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.mediatranscode.VideoEditProgressListener
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@AndroidEntryPoint
class ImageEditorActivity : VectorBaseActivity<ActivityImageEditorBinding>() {

    private lateinit var sourceUri: Uri
    private var displayName: String? = null
    private var sourceMimeType: String? = null
    private var initialEdits: ImageEditorEdits? = null
    private var animatedFormat: AnimatedImageFormat? = null
    private var animatedTarget: CustomTarget<Drawable>? = null
    private val animatedRequests by lazy { GlideApp.with(this) }
    private var imageLoaded = false
    private var saving = false
    private var exportJob: Job? = null
    private val exportOverlay by lazy {
        val editor = ActivityVideoEditorBinding.inflate(layoutInflater)
        val overlay = editor.videoEditorExportOverlay
        (overlay.parent as ViewGroup).removeView(overlay)
        editor.videoEditorExportLabel.setText(if (animatedFormat != null) CommonStrings.animated_image_editor_exporting else CommonStrings.please_wait)
        editor.videoEditorExportCancel.setOnClickListener { exportJob?.cancel() }
        views.coordinatorLayout.addView(overlay)
        ViewCompat.setElevation(overlay, 32f * resources.displayMetrics.density)
        overlay
    }
    private var lastCustomAspectRatio: Pair<Int, Int>? = null
    private var activeToolFill: Int = Color.WHITE
    private var activeToolContent: Int = Color.WHITE
    private lateinit var colorBar: EditorColorBar

    override fun getOtherThemes() = ActivityOtherThemes.AttachmentsPreview

    override fun getBinding() = ActivityImageEditorBinding.inflate(layoutInflater)

    override val rootView: View
        get() = views.coordinatorLayout

    override fun initUiAndData() {
        makeSystemBarsTransparent()
        sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.toUri() ?: run { finish(); return }
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        sourceMimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        animatedFormat = intent.getStringExtra(EXTRA_ANIMATED_FORMAT)?.let { name ->
            runCatching { AnimatedImageFormat.valueOf(name) }.getOrNull()
        }

        setupToolbar(views.imageEditorToolbar).allowBack()

        val (fill, onFill) = ThemeUtils.accentFillOnDarkSurface(this)
        activeToolFill = fill
        activeToolContent = onFill
        views.imageEditorSaveButton.backgroundTintList = ColorStateList.valueOf(fill)
        ImageViewCompat.setImageTintList(views.imageEditorSaveButton, ColorStateList.valueOf(onFill))
        views.imageEditorRotateButton.backgroundTintList = ColorStateList.valueOf(INACTIVE_FAB_COLOR)

        views.imageEditorSaveButton.setOnClickListener { save() }
        views.imageEditorCensorButton.setOnClickListener { toggleTool(ImageEditorView.Tool.CENSOR) }
        views.imageEditorDrawButton.setOnClickListener { toggleTool(ImageEditorView.Tool.DRAW) }
        views.imageEditorRotateButton.setOnClickListener { toggleTool(ImageEditorView.Tool.ROTATE) }
        views.imageEditorRotateLeftButton.setOnClickListener { views.imageEditorView.rotateCounterClockwise() }
        views.imageEditorRotateRightButton.setOnClickListener { views.imageEditorView.rotateClockwise() }
        views.imageEditorSnapButton.setOnClickListener { toggleSnapToCenter() }
        views.imageEditorAspectButton.setOnClickListener { showAspectRatioPicker() }
        views.imageEditorAspectButton.backgroundTintList = ColorStateList.valueOf(INACTIVE_FAB_COLOR)

        setupToolOptions()
        views.imageEditorView.onToolChanged = { applyTool(it) }
        views.imageEditorView.onRotationChanged = { views.imageEditorDial.value = it }
        views.imageEditorView.onHistoryChanged = {
            views.imageEditorDial.value = views.imageEditorView.rotationAngle
            invalidateOptionsMenu()
        }
        views.imageEditorView.onCensorSelectionChanged = { color ->
            if (views.imageEditorView.tool == ImageEditorView.Tool.CENSOR) colorBar.setSelected(color ?: views.imageEditorView.censorColor)
        }
        applyTool(ImageEditorView.Tool.CROP)
        applySnapToCenter(vectorPreferences.imageEditorSnapToCenter())
        intent.getFloatExtra(EXTRA_ASPECT_RATIO, 0f).takeIf { it > 0f }?.let {
            views.imageEditorView.cropAspectRatio = it
            views.imageEditorView.ratioTurnsWithImage = false
            // The caller needs this exact shape, so the ratio is not the user's to change.
            views.imageEditorAspectButton.isVisible = false
        }
        initialEdits = intent.getParcelableExtraCompat(EXTRA_EDITS)
        initialEdits?.let { views.imageEditorView.restoreEdits(it) }
        EditorHistoryCache.find(sourceUri.toString(), initialEdits)?.let { views.imageEditorView.resumeHistory(it) }
        views.imageEditorSaveButton.isEnabled = false
        if (animatedFormat == null) loadBitmap() else loadAnimatedImage()
    }

    private fun showAspectRatioPicker() {
        val censor = views.imageEditorView.isCensorSelected()
        AspectRatioPicker.show(
                // The editor's own theme is a black full-bleed one, which a dialog cannot use.
                context = ContextThemeWrapper(this, ThemeUtils.getApplicationThemeRes(this)),
                current = views.imageEditorView.selectionAspectRatio(),
                // A censor is a box over a detail; the image's own ratio says nothing about it.
                suggested = lastCustomAspectRatio ?: views.imageEditorView.displayedAspectRatio().takeUnless { censor },
        ) { ratio, custom ->
            custom?.let { lastCustomAspectRatio = it }
            views.imageEditorView.applySelectionAspectRatio(ratio)
        }
    }

    private fun toggleSnapToCenter() {
        val enabled = !views.imageEditorView.snapToCenter
        vectorPreferences.setImageEditorSnapToCenter(enabled)
        applySnapToCenter(enabled)
        Toast.makeText(
                this,
                getString(if (enabled) CommonStrings.image_editor_snap_on else CommonStrings.image_editor_snap_off),
                Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupToolOptions() {
        val editor = views.imageEditorView
        editor.brushSizeDp = vectorPreferences.imageEditorBrushSizeDp()
        colorBar = EditorColorBar(
                container = views.imageEditorColorRow,
                dialogContext = ContextThemeWrapper(this, ThemeUtils.getApplicationThemeRes(this)),
        ) { color ->
            if (editor.tool == ImageEditorView.Tool.DRAW) editor.brushColor = color else editor.applyCensorColor(color)
        }
        views.imageEditorBrushSize.max = (MAX_BRUSH_DP - MIN_BRUSH_DP).toInt()
        views.imageEditorBrushSize.progress = (editor.brushSizeDp - MIN_BRUSH_DP).toInt()
        views.imageEditorBrushSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                editor.brushSizeDp = MIN_BRUSH_DP + progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                vectorPreferences.setImageEditorBrushSizeDp(editor.brushSizeDp)
            }
        })
        views.imageEditorDial.onChanged = { degrees, moving -> editor.setRotationAngle(degrees, final = !moving) }
    }

    private fun applySnapToCenter(enabled: Boolean) {
        views.imageEditorView.snapToCenter = enabled
        views.imageEditorDial.snapEnabled = enabled
        views.imageEditorSnapButton.backgroundTintList =
                ColorStateList.valueOf(if (enabled) activeToolFill else INACTIVE_FAB_COLOR)
        ImageViewCompat.setImageTintList(
                views.imageEditorSnapButton,
                ColorStateList.valueOf(if (enabled) activeToolContent else Color.WHITE)
        )
    }

    private fun toggleTool(tool: ImageEditorView.Tool) {
        applyTool(if (views.imageEditorView.tool == tool) ImageEditorView.Tool.CROP else tool)
    }

    private fun applyTool(tool: ImageEditorView.Tool) {
        val editor = views.imageEditorView
        editor.tool = tool
        styleToolButton(views.imageEditorCensorButton, tool == ImageEditorView.Tool.CENSOR)
        styleToolButton(views.imageEditorDrawButton, tool == ImageEditorView.Tool.DRAW)
        styleToolButton(views.imageEditorRotateButton, tool == ImageEditorView.Tool.ROTATE)
        val colored = tool == ImageEditorView.Tool.DRAW || tool == ImageEditorView.Tool.CENSOR
        views.imageEditorOptions.isVisible = colored || tool == ImageEditorView.Tool.ROTATE
        views.imageEditorColorScroll.isVisible = colored
        views.imageEditorBrushSize.isVisible = tool == ImageEditorView.Tool.DRAW
        views.imageEditorRotateRow.isVisible = tool == ImageEditorView.Tool.ROTATE
        when (tool) {
            ImageEditorView.Tool.DRAW -> colorBar.setSelected(editor.brushColor)
            ImageEditorView.Tool.CENSOR -> colorBar.setSelected(editor.censorColor)
            ImageEditorView.Tool.ROTATE -> views.imageEditorDial.value = editor.rotationAngle
            ImageEditorView.Tool.CROP -> Unit
        }
    }

    private fun styleToolButton(button: FloatingActionButton, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (active) activeToolFill else INACTIVE_FAB_COLOR)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(if (active) activeToolContent else Color.WHITE))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_image_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (saving) {
            if (item.itemId == android.R.id.home) exportJob?.cancel()
            return true
        }
        return when (item.itemId) {
            R.id.imageEditorResetAction -> {
                views.imageEditorView.resetEdits()
                applyTool(ImageEditorView.Tool.CROP)
                true
            }
            R.id.imageEditorUndoAction -> {
                views.imageEditorView.undo()
                true
            }
            R.id.imageEditorRedoAction -> {
                views.imageEditorView.redo()
                true
            }
            else -> super.onOptionsItemSelected(item)
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
                imageLoaded = true
                views.imageEditorSaveButton.isEnabled = true
                views.imageEditorView.detailDecoder = withContext(Dispatchers.IO) {
                    runCatching { ImageDetailDecoder.create(this@ImageEditorActivity, sourceUri, bitmap) }.getOrNull()
                }
            }
        }
    }

    private fun loadAnimatedImage() {
        // Native size, as the media viewer loads it, so zooming in shows the frames' real pixels.
        val target = object : CustomTarget<Drawable>(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL) {
            private var drawable: Drawable? = null

            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                drawable = resource
                views.imageEditorView.setAnimatedDrawable(resource)
                imageLoaded = true
                views.imageEditorSaveButton.isEnabled = true
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                views.imageEditorView.setAnimatedDrawable(null)
                drawable = null
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                Toast.makeText(this@ImageEditorActivity, getString(CommonStrings.image_editor_load_failed), Toast.LENGTH_SHORT).show()
                finish()
            }

            override fun onStart() { if (!saving) (drawable as? Animatable)?.start() }
            override fun onStop() { (drawable as? Animatable)?.stop() }
        }
        animatedTarget = target
        animatedRequests.load(sourceUri).dontTransform().into(target)
    }

    private suspend fun exportAnimatedImage(edits: ImageEditorEdits): ImageEditorExporter.Result {
        val source = File.createTempFile("avatar-crop-", ".source", cacheDir)
        try {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(sourceUri)?.use { input ->
                    source.outputStream().use { input.copyTo(it) }
                } ?: error("Unable to read animated avatar")
            }
            val result = AnimatedImageExporter.export(
                    this, source, animatedFormat, displayName,
                    VideoEditorEdits(rotationDegrees = edits.userRotation, crop = edits.crop, tiltDegrees = edits.tiltDegrees),
                    targetSize = null,
                    progressListener = VideoEditProgressListener { percent ->
                        runOnUiThread { if (saving) exportOverlay.findViewById<ProgressBar>(R.id.videoEditorExportProgress).progress = percent }
                    }, censors = edits.censors, strokes = edits.strokes
            )
            return ImageEditorExporter.Result(result.uri, result.width, result.height, result.size, result.mimeType)
        } finally {
            source.delete()
        }
    }

    override fun onDestroy() {
        animatedTarget?.let { animatedRequests.clear(it) }
        views.imageEditorView.detailDecoder = null
        super.onDestroy()
    }

    private fun save() {
        if (!imageLoaded || saving) return
        val edits = views.imageEditorView.currentEdits()
        // Left exactly as it was opened: the attachment already is this export.
        if (edits == initialEdits) {
            keepHistory(initialEdits)
            finish()
            return
        }
        if (!edits.hasChanges) {
            keepHistory(edits)
            setResult(RESULT_OK, restoreOriginalResult(sourceUri))
            finish()
            return
        }
        saving = true
        animatedTarget?.onStop()
        views.imageEditorSaveButton.isEnabled = false
        exportOverlay.findViewById<ProgressBar>(R.id.videoEditorExportProgress).progress = 0
        exportOverlay.isVisible = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        invalidateOptionsMenu()
        exportJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (animatedFormat != null) exportAnimatedImage(edits) else {
                        ImageEditorExporter.export(this@ImageEditorActivity, sourceUri, edits, displayName, sourceMimeType)
                    }
                } ?: error("Unable to export edited image")
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_RESULT_URI, result.uri.toString())
                    putExtra(EXTRA_RESULT_WIDTH, result.width)
                    putExtra(EXTRA_RESULT_HEIGHT, result.height)
                    putExtra(EXTRA_RESULT_SIZE, result.size)
                    putExtra(EXTRA_RESULT_MIME_TYPE, result.mimeType)
                    putExtra(EXTRA_RESULT_EDITS, edits)
                })
                keepHistory(edits)
                finish()
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                Timber.w(error, "Failed to export edited image")
                val message = if (error is AnimatedImageExporter.TransparencyUnsupportedException) {
                    CommonStrings.animated_image_editor_no_transparency
                } else CommonStrings.image_editor_save_failed
                Toast.makeText(this@ImageEditorActivity, getString(message), Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
                exportOverlay.isVisible = false
                views.imageEditorSaveButton.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                invalidateOptionsMenu()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) animatedTarget?.onStart()
            }
        }
    }

    private fun keepHistory(edits: ImageEditorEdits?) =
            EditorHistoryCache.put(sourceUri.toString(), edits, views.imageEditorView.historyToKeep())

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        for (index in 0 until menu.size()) menu.getItem(index).isEnabled = !saving
        menu.findItem(R.id.imageEditorUndoAction)?.setEnabledDimmed(!saving && views.imageEditorView.canUndo)
        menu.findItem(R.id.imageEditorRedoAction)?.setEnabledDimmed(!saving && views.imageEditorView.canRedo)
        return super.onPrepareOptionsMenu(menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (saving) exportJob?.cancel() else super.onBackPressed()
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
        private const val MIN_BRUSH_DP = 2f
        private const val MAX_BRUSH_DP = 40f

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
        private const val EXTRA_ANIMATED_FORMAT = "EXTRA_ANIMATED_FORMAT"
        private const val EXTRA_ASPECT_RATIO = "EXTRA_ASPECT_RATIO"

        fun newIntent(
                context: Context,
                source: Uri,
                displayName: String?,
                mimeType: String?,
                edits: ImageEditorEdits?,
                aspectRatio: Float? = null,
                animatedFormat: AnimatedImageFormat? = null,
        ): Intent {
            return Intent(context, ImageEditorActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_URI, source.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_ANIMATED_FORMAT, animatedFormat?.name)
                putExtra(EXTRA_EDITS, edits)
                aspectRatio?.let { putExtra(EXTRA_ASPECT_RATIO, it) }
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
