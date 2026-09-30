/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.os.CancellationSignal
import android.view.View
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.annotation.RequiresApi
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationControlListenerCompat
import androidx.core.view.WindowInsetsAnimationControllerCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val QUICK_IME_HIDE_MILLIS = 140L

/**
 * Slides the IME away faster than the system's ~340ms hide, driving the inset animation ourselves.
 * Returns false when the system refused control; the caller should then hide the keyboard normally.
 */
@RequiresApi(Build.VERSION_CODES.R)
suspend fun Window.hideImeQuickly(view: View): Boolean = suspendCancellableCoroutine { cont ->
    val insetsController = WindowCompat.getInsetsController(this, view)
    val interpolator = DecelerateInterpolator(1.5f)
    val cancellationSignal = CancellationSignal()
    var animator: ValueAnimator? = null
    cont.invokeOnCancellation {
        animator?.cancel()
        cancellationSignal.cancel()
    }
    insetsController.controlWindowInsetsAnimation(
            WindowInsetsCompat.Type.ime(),
            QUICK_IME_HIDE_MILLIS,
            interpolator,
            cancellationSignal,
            object : WindowInsetsAnimationControlListenerCompat {
                override fun onReady(controller: WindowInsetsAnimationControllerCompat, types: Int) {
                    val shown = controller.shownStateInsets.bottom
                    val hidden = controller.hiddenStateInsets.bottom
                    animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = QUICK_IME_HIDE_MILLIS
                        setInterpolator(LinearInterpolator())
                        addUpdateListener {
                            if (!controller.isReady) return@addUpdateListener
                            val fraction = it.animatedFraction
                            val bottom = shown + ((hidden - shown) * interpolator.getInterpolation(fraction)).toInt()
                            controller.setInsetsAndAlpha(Insets.of(0, 0, 0, bottom), 1f, fraction)
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (controller.isReady) controller.finish(false)
                            }
                        })
                        start()
                    }
                }

                override fun onFinished(controller: WindowInsetsAnimationControllerCompat) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(controller: WindowInsetsAnimationControllerCompat?) {
                    animator?.cancel()
                    if (cont.isActive) cont.resume(false)
                }
            }
    )
}
