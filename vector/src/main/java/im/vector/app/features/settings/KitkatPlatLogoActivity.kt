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

        val lp2 = FrameLayout.LayoutParams(lp)
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
        bg.animate().alpha(1f).scaleX(1f).setStartDelay(500).start()
        logo.alpha = 0f
        logo.scaleX = 0.5f
        logo.scaleY = 0.5f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(1000).setStartDelay(500)
                .setInterpolator(AnticipateOvershootInterpolator())
                .start()
        tv.alpha = 0f
        tv.visibility = View.VISIBLE
        tv.animate().alpha(1f).setDuration(1000).setStartDelay(1000).start()

        setContentView(content)
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
