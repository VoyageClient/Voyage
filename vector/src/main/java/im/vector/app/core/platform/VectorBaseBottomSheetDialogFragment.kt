/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.platform

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.CallSuper
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.airbnb.mvrx.MavericksView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.EntryPointAccessors
import im.vector.app.core.di.ActivityEntryPoint
import im.vector.app.core.extensions.toMvRxBundle
import im.vector.app.core.extensions.withLayerCompat
import im.vector.app.core.utils.DimensionConverter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import reactivecircus.flowbinding.android.view.clicks
import timber.log.Timber

/**
 * Add Mavericks capabilities, handle DI and bindings.
 */
abstract class VectorBaseBottomSheetDialogFragment<VB : ViewBinding> : BottomSheetDialogFragment(), MavericksView {
    /* ==========================================================================================
     * View
     * ========================================================================================== */

    private var _binding: VB? = null

    // This property is only valid between onCreateView and onDestroyView.
    protected val views: VB
        get() = _binding!!

    abstract fun getBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    /* ==========================================================================================
     * View model
     * ========================================================================================== */

    private lateinit var viewModelFactory: ViewModelProvider.Factory

    protected val activityViewModelProvider
        get() = ViewModelProvider(requireActivity(), viewModelFactory)

    protected val fragmentViewModelProvider
        get() = ViewModelProvider(this, viewModelFactory)

    /* ==========================================================================================
     * BottomSheetBehavior
     * ========================================================================================== */

    private var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>? = null

    private var enterPrepared = false

    private var enterPostponed = false

    private var slideIn: (() -> Unit)? = null

    val vectorBaseActivity: VectorBaseActivity<*> by lazy {
        activity as VectorBaseActivity<*>
    }

    open val showExpanded = false

    interface ResultListener {
        fun onBottomSheetResult(resultCode: Int, data: Any?)

        companion object {
            const val RESULT_OK = 1
            const val RESULT_CANCEL = 0
        }
    }

    var resultListener: ResultListener? = null
    var bottomSheetResult: Int = ResultListener.RESULT_CANCEL
    var bottomSheetResultData: Any? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        resultListener?.onBottomSheetResult(bottomSheetResult, bottomSheetResultData)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = getBinding(inflater, container)
        return views.root
    }

    @CallSuper
    override fun onDestroyView() {
        _binding = null
        slideIn = null
        super.onDestroyView()
    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onAttach(context: Context) {
        val activityEntryPoint = EntryPointAccessors.fromActivity(vectorBaseActivity, ActivityEntryPoint::class.java)
        viewModelFactory = activityEntryPoint.viewModelFactory()
        super.onAttach(context)
    }

    override fun onResume() {
        super.onResume()
        Timber.i("onResume BottomSheet ${javaClass.simpleName}")
    }

    @Suppress("DEPRECATION")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            val dialog = this as? BottomSheetDialog
            // Material only takes the sheet edge to edge when the window's navigation bar is not opaque.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dialog?.window?.navigationBarColor = Color.TRANSPARENT
            }
            bottomSheetBehavior = dialog?.behavior
            bottomSheetBehavior?.setPeekHeight(DimensionConverter(resources).dpToPx(400), false)
            if (showExpanded) {
                bottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    override fun onStart() {
        super.onStart()
        paintNavigationBarStrip()
        // This ensures that invalidate() is called for static screens that don't
        // subscribe to a ViewModel.
        postInvalidate()
    }

    /**
     * The dialog dims the whole screen behind it, the navigation bar strip included, so the sheet has to
     * cover that strip itself rather than leave it to the screen underneath.
     */
    private fun paintNavigationBarStrip() {
        val sheetContext = dialog?.context ?: return
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val surface = MaterialColors.getColor(sheetContext, com.google.android.material.R.attr.colorSurface, Color.TRANSPARENT)
        // The sheet slides in from off screen, so for those frames the strip is this band's to paint.
        val band = addNavigationBarBand(sheet, surface)
        animateSheetIn(sheet, band)
        // The background arrives with the first layout pass, and is replaced again on later ones.
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> paintSheetSurface(sheet, surface) }
        paintSheetSurface(sheet, surface)
    }

    /**
     * The sheet's window is not floating, so that it can reach past the system bars to paint the
     * navigation strip. Material's enter animation is a window animation, which a full-screen window
     * would play on the scrim as well, so the sheet slides itself in instead.
     */
    private fun animateSheetIn(sheet: View, band: View?) {
        if (enterPrepared) return
        enterPrepared = true
        // Off screen from the start: the first layout is what reveals the sheet, and waiting for it to
        // arrive before moving the sheet down shows one frame of it already in place.
        sheet.translationY = resources.displayMetrics.heightPixels.toFloat()
        band?.translationY = sheet.translationY
        sheet.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (v.height == 0) return
                v.removeOnLayoutChangeListener(this)
                slideIn = {
                    // What the resting state shows, not the sheet's full height: one peeking at half the
                    // screen would otherwise start a screen down and slide that whole distance.
                    val visibleHeight = ((v.parent as? View)?.height ?: v.height) - v.top
                    v.translationY = visibleHeight.toFloat()
                    band?.translationY = v.translationY
                    v.slideToRest()
                    band?.slideToRest()
                }
                if (!enterPostponed) startSlideIn()
            }
        })
        if (enterPostponed) {
            // The content it is waiting for may never arrive (no room, a failed read); the sheet still has
            // to appear.
            sheet.postDelayed({ startSlideIn() }, POSTPONED_ENTER_TIMEOUT_MS)
        }
    }

    // withLayer: the sheet holds a screen of list rows, and re-rasterising those every frame is what
    // the slide cannot afford.
    private fun View.slideToRest() {
        animate()
                .translationY(0f)
                .setDuration(ENTER_ANIMATION_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .withLayerCompat(this)
                .start()
    }

    private fun startSlideIn() {
        enterPostponed = false
        slideIn?.invoke()
        slideIn = null
    }

    /** Hold the sheet off screen until [startPostponedEnter], so it slides in with its content in place. */
    protected fun postponeEnter() {
        enterPostponed = true
    }

    protected fun startPostponedEnter() {
        if (!enterPostponed) return
        startSlideIn()
    }

    private fun addNavigationBarBand(sheet: View, @ColorInt surface: Int): View? {
        val container = (sheet.parent as? View)?.parent as? ViewGroup ?: return null
        val band = View(container.context).apply { setBackgroundColor(surface) }
        // Behind the sheet's coordinator, so it takes none of the touches that dismiss the sheet.
        container.addView(band, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            band.updateLayoutParams { height = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom }
            insets
        }
        return band
    }

    /** The strip the sheet covers is its own background, so it has to be exactly the sheet's surface. */
    private fun paintSheetSurface(sheet: View, @ColorInt surface: Int) {
        when (val background = sheet.background) {
            is MaterialShapeDrawable -> {
                // The elevation overlay only tints a fill that is colorSurface, which would leave the strip
                // lighter than the flat surface the sheet's own content is painted with.
                background.elevation = 0f
                if (background.fillColor?.defaultColor != surface) background.fillColor = ColorStateList.valueOf(surface)
            }
            else -> if ((background as? ColorDrawable)?.color != surface) sheet.setBackgroundColor(surface)
        }
    }

    @CallSuper
    override fun invalidate() {
        forceExpandState()
    }

    protected fun setPeekHeightAsScreenPercentage(@FloatRange(from = 0.0, to = 1.0) percentage: Float, animate: Boolean = true) {
        context?.let {
            val screenHeight = it.resources.displayMetrics.heightPixels
            bottomSheetBehavior?.setPeekHeight((screenHeight * percentage).toInt(), animate)
        }
    }

    protected fun forceExpandState() {
        if (showExpanded) {
            // Force the bottom sheet to be expanded
            bottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    protected fun setArguments(args: Parcelable? = null) {
        arguments = args.toMvRxBundle()
    }

    /* ==========================================================================================
     * Views
     * ========================================================================================== */

    protected fun View.debouncedClicks(onClicked: () -> Unit) {
        clicks()
                .onEach { onClicked() }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    /* ==========================================================================================
     * ViewEvents
     * ========================================================================================== */

    protected fun <T : VectorViewEvents> VectorViewModel<*, *, T>.observeViewEvents(
            observer: (T) -> Unit,
    ) {
        val tag = this@VectorBaseBottomSheetDialogFragment::class.simpleName.toString()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewEvents
                        .stream(tag)
                        .collect {
                            observer(it)
                        }
            }
        }
    }

    companion object {
        // Matches the dismiss, which is BottomSheetBehavior settling the sheet through ViewDragHelper.
        private const val ENTER_ANIMATION_DURATION_MS = 350L
        private const val POSTPONED_ENTER_TIMEOUT_MS = 350L
    }
}
