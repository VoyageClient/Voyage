/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityGradientImageSamplerBinding
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.parcelize.Parcelize

@Parcelize
data class GradientImageSamplerArgs(
        // One for the eyedropper, else how many colors the gradient still has room for.
        val maxPoints: Int,
        val imageUri: Uri,
) : Parcelable

/** The image editor with nothing but its eyedropper: each color the gradient gets is a point on the image. */
@AndroidEntryPoint
class GradientImageSamplerActivity : VectorBaseActivity<ActivityGradientImageSamplerBinding>() {

    private val samplerViewModel: GradientImageSamplerViewModel by viewModels()
    private val swatchAdapter = GradientSamplerSwatchAdapter { views.samplerView.select(it) }
    private var pointCount = 0
    private var hasSelection = false

    // Pre-21 status bars are opaque and system-drawn, so there is nothing to draw under.
    override val drawUnderSystemBars = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    override fun getOtherThemes() = ActivityOtherThemes.AttachmentsPreview

    override fun getBinding() = ActivityGradientImageSamplerBinding.inflate(layoutInflater)

    override val rootView: View
        get() = views.coordinatorLayout

    override fun initUiAndData() {
        makeSystemBarsTransparent()
        val args = intent.getParcelableExtraCompat<GradientImageSamplerArgs>(EXTRA_ARGS) ?: run { finish(); return }
        applyInsets()
        setupToolbar(views.samplerToolbar).allowBack()
        supportActionBar?.title = getString(if (args.maxPoints == 1) CommonStrings.gradient_pick_from_image else CommonStrings.gradient_from_image)

        val (fill, onFill) = ThemeUtils.accentFillOnDarkSurface(this)
        views.samplerSaveButton.backgroundTintList = ColorStateList.valueOf(fill)
        ImageViewCompat.setImageTintList(views.samplerSaveButton, ColorStateList.valueOf(onFill))
        views.samplerSaveButton.setOnClickListener { finishWithColors() }

        views.samplerColorRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        views.samplerColorRow.adapter = swatchAdapter
        ItemTouchHelper(SwatchReorderCallback()).attachToRecyclerView(views.samplerColorRow)

        views.samplerView.maxPoints = args.maxPoints.coerceAtLeast(1)
        views.samplerView.onPointsChanged = ::renderPoints

        samplerViewModel.state.onEach(::render).launchIn(lifecycleScope)
        samplerViewModel.loadFailures
                .onEach { Toast.makeText(this, CommonStrings.image_editor_load_failed, Toast.LENGTH_SHORT).show() }
                .launchIn(lifecycleScope)
        if (samplerViewModel.state.value == GradientImageSamplerViewModel.State.Idle) samplerViewModel.load(this, args.imageUri)
    }

    override fun onDestroy() {
        samplerViewModel.points = views.samplerView.pointsState()
        samplerViewModel.selectedPoint = views.samplerView.selectedIndex
        super.onDestroy()
    }

    private fun applyInsets() {
        if (!drawUnderSystemBars) return
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(views.samplerAppBar) { v, insets ->
            val bars = insets.getInsets(types)
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            insets
        }
        val controlsPaddingBottom = views.samplerControls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(views.samplerControls) { v, insets ->
            val bars = insets.getInsets(types)
            v.updatePadding(left = bars.left, right = bars.right, bottom = controlsPaddingBottom + bars.bottom)
            insets
        }
    }

    private fun render(state: GradientImageSamplerViewModel.State) {
        views.samplerLoading.isVisible = state == GradientImageSamplerViewModel.State.Loading
        when (state) {
            is GradientImageSamplerViewModel.State.Loaded -> {
                views.samplerView.setBitmap(state.bitmap)
                // Only once, after a recreation.
                samplerViewModel.points?.let { views.samplerView.restorePoints(it, samplerViewModel.selectedPoint) }
                samplerViewModel.points = null
            }
            GradientImageSamplerViewModel.State.Failed -> finish()
            else -> Unit
        }
        updateSaveButton()
    }

    private fun renderPoints(colors: List<Int>, selected: Int) {
        swatchAdapter.submit(colors, selected)
        val countChanged = pointCount != colors.size
        if (countChanged || hasSelection != (selected >= 0)) {
            pointCount = colors.size
            hasSelection = selected >= 0
            invalidateOptionsMenu()
            updateSaveButton()
        }
        if (countChanged && colors.isNotEmpty()) views.samplerColorRow.scrollToPosition(colors.lastIndex)
    }

    private fun updateSaveButton() {
        views.samplerSaveButton.isEnabled = samplerViewModel.state.value is GradientImageSamplerViewModel.State.Loaded && pointCount > 0
    }

    private inner class SwatchReorderCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            swatchAdapter.move(from, to)
            views.samplerView.movePoint(from, to)
            return true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_gradient_image_sampler, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.samplerMenuRemoveColor)?.isVisible = hasSelection
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.samplerMenuRemoveColor -> {
                views.samplerView.removeSelected()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun finishWithColors() {
        val colors = views.samplerView.colors
        if (colors.isEmpty()) return
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COLORS, colors.map { it and 0xFFFFFF }.toIntArray()))
        finish()
    }

    companion object {
        private const val EXTRA_ARGS = "EXTRA_ARGS"
        const val EXTRA_COLORS = "EXTRA_COLORS"

        fun imagePickerIntent() = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        fun newIntent(context: Context, maxPoints: Int, imageUri: Uri): Intent {
            return Intent(context, GradientImageSamplerActivity::class.java).apply {
                putExtra(EXTRA_ARGS, GradientImageSamplerArgs(maxPoints, imageUri))
                // Carries the picker's read grant over to this activity.
                data = imageUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
