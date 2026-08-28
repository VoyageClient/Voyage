/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import im.vector.app.core.extensions.useCompat
import im.vector.app.core.utils.DeviceCapabilities

/**
 * The end of the Crypto-version tap countdown: a chromeless, fullscreen, letterboxed video with
 * audio that fades into [KitkatPlatLogoActivity] when it ends. No touch handling at all — the
 * system back button is the only early way out. A deliberately bare [Activity]: no AppCompat
 * toolbar, no gesture plumbing, nothing shared with the media viewer.
 */
class KitkatVideoActivity : Activity(), SurfaceHolder.Callback {

    private var videoPlayer: MediaPlayer? = null
    private var advancing = false
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Immersive from the very first frame; onWindowFocusChanged only re-applies after swipes.
        hideSystemUi()
        root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyLetterbox()
        }
        surfaceView.holder.addCallback(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        // The manifest theme already drops the title and status bars on every API level; sticky
        // immersive additionally hides the navigation bar where the platform supports it (K+).
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        val video = createPlayer(assetFor()) ?: run {
            finish()
            return
        }
        video.setDisplay(holder)
        video.setOnCompletionListener {
            if (advancing) return@setOnCompletionListener
            advancing = true
            startActivity(Intent(this@KitkatVideoActivity, KitkatPlatLogoActivity::class.java))
            // A cross-fade can't animate a SurfaceView's own window, it only exposes the torn-down
            // surface. Cut, and stay alive underneath until the logo draws: finishing releases the
            // player, and the last frame would vanish before anything covers it.
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            root.postDelayed({ finish() }, HAND_OFF_DELAY_MS)
        }
        videoPlayer = video
        applyLetterbox()
        video.start()
    }

    private fun createPlayer(assetName: String): MediaPlayer? {
        return runCatching {
            assets.openFd(assetName).useCompat { fd ->
                val player = MediaPlayer()
                try {
                    player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    player.prepare()
                    player
                } catch (failure: Throwable) {
                    player.release()
                    throw failure
                }
            }
        }.getOrNull()
    }

    // MediaPlayer scales frames to the surface's exact bounds, so aspect is preserved by sizing
    // the surface itself: fit scale, centered — the theme's black window forms the bars.
    private fun applyLetterbox() {
        val player = videoPlayer ?: return
        val videoWidth = player.videoWidth
        val videoHeight = player.videoHeight
        val containerWidth = root.width
        val containerHeight = root.height
        if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) return
        val scale = minOf(containerWidth.toFloat() / videoWidth, containerHeight.toFloat() / videoHeight)
        surfaceView.layoutParams = FrameLayout.LayoutParams(
                (videoWidth * scale).toInt(),
                (videoHeight * scale).toInt(),
                Gravity.CENTER,
        )
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoPlayer?.release()
        videoPlayer = null
    }

    companion object {
        private const val HAND_OFF_DELAY_MS = 600L

        // Bundled at assets/; the easter egg silently stays dormant until the file exists.
        private const val VIDEO_ASSET = "gnu_kitkat.mp4"

        // The full-size cut is H.264 High@5.1 at 1620x2160; the decoders that ship with low-power
        // hardware reject it outright, so those devices get a Baseline 480x640 cut instead.
        private const val SMALL_VIDEO_ASSET = "gnu_kitkat_small.mp4"

        private fun assetFor(): String =
                if (DeviceCapabilities.isLowPerformanceHardware) SMALL_VIDEO_ASSET else VIDEO_ASSET

        /** Launches the video when the asset is bundled; no-op otherwise. */
        fun start(context: Context) {
            val present = runCatching { context.assets.openFd(assetFor()).close() }.isSuccess
            if (present) context.startActivity(Intent(context, KitkatVideoActivity::class.java))
        }
    }
}
