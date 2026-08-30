/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.settings

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AnticipateOvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import im.vector.app.R

/**
 * KitKat's `com.android.internal.app.PlatLogoActivity` (AOSP tag android-4.4_r1) without the
 * tappable "K" phase: its long-press transformation (red panel wipe, logo overshoot, caption fade)
 * plays directly as the entrance, continuing the video that launches this. Long-pressing the logo
 * opens the ported dessert case, where the original fired the platform's PLATLOGO intent. The
 * caption is ours; the internal platlogo drawable is a bundled copy of the original asset.
 */
class KitkatPlatLogoActivity : Activity() {

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immersive from the very first frame — waiting for onWindowFocusChanged lets the
        // navigation bar flash in during the fade from the video.
        hideSystemUi()

        val metrics = resources.displayMetrics

        val light = Typeface.create("sans-serif-light", Typeface.NORMAL)

        val content = FrameLayout(this)
        content.setBackgroundColor(0xC0000000.toInt())

        val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER
        // platlogo is a nodpi 920px bitmap, so on a narrower screen it measures wider than the window
        // and sits edge to edge. The margins cap that, and CENTER_INSIDE scales the artwork to fit.
        val logoMargin = (24 * metrics.density).toInt()
        lp.leftMargin = logoMargin
        lp.rightMargin = logoMargin

        val bg = View(this)
        bg.setBackgroundColor(BGCOLOR)
        bg.alpha = 0f

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.platlogo)
        logo.scaleType = ImageView.ScaleType.CENTER_INSIDE

        val p = (4 * metrics.density).toInt()

        val tv = TextView(this)
        if (light != null) tv.typeface = light
        tv.textSize = 30f
        tv.setPadding(p, p, p, p)
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.gravity = Gravity.CENTER
        tv.isAllCaps = true
        tv.text = "GNU/KitKat"
        tv.visibility = View.INVISIBLE

        content.addView(bg)
        content.addView(logo, lp)

        // The FrameLayout.LayoutParams copy constructor is API 19+.
        val lp2 = FrameLayout.LayoutParams(lp.width, lp.height)
        lp2.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp2.bottomMargin = 10 * p

        content.addView(tv, lp2)

        logo.setOnLongClickListener {
            startActivity(Intent(this, KitkatDessertCaseActivity::class.java))
            finish()
            true
        }

        // The original long-press transformation, played as the entrance (the K it used to replace
        // is skipped): red panel wipes in, logo overshoots in, caption fades up.
        bg.scaleX = 0.01f
        logo.alpha = 0f
        logo.scaleX = 0.5f
        logo.scaleY = 0.5f
        tv.alpha = 0f
        tv.visibility = View.VISIBLE

        setContentView(content)

        if (savedInstanceState != null) {
            // A rotation is not a fresh reveal; land on the finished state instead of replaying it.
            bg.alpha = 1f
            bg.scaleX = 1f
            logo.alpha = 1f
            logo.scaleX = 1f
            logo.scaleY = 1f
            tv.alpha = 1f
            return
        }

        // Started once the tree is attached: from onCreate the start-delay clock runs while nothing is
        // on screen, so on slow hardware the first frame lands mid-animation.
        content.post {
            bg.animate().alpha(1f).scaleX(1f).start()
            // Scale and alpha need separate animators: anticipation drives its input below zero at
            // the start, and on alpha that clamps and blinks the logo out just as it appears.
            AnimatorSet().apply {
                duration = 1000
                play(ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f))
                        .with(ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.5f, 1f).apply {
                            interpolator = AnticipateOvershootInterpolator()
                        })
                        .with(ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.5f, 1f).apply {
                            interpolator = AnticipateOvershootInterpolator()
                        })
            }.start()
            tv.animate().alpha(1f).setDuration(1000).setStartDelay(500).start()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // Same sticky-immersive treatment as the dessert case: no bars at all, transient controls on swipe.
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    companion object {
        private val BGCOLOR = 0xffed1d24.toInt()
    }
}
