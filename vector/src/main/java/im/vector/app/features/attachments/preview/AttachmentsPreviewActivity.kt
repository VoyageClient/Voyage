/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivitySimpleBinding
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.lib.core.utils.compat.getParcelableCompat
import im.vector.lib.core.utils.compat.getSerializableExtraCompat
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@AndroidEntryPoint
class AttachmentsPreviewActivity : VectorBaseActivity<ActivitySimpleBinding>() {

    private var stallMonitor: StallMonitor? = null

    override fun onResume() {
        if (DebugLog.enabled) stallMonitor = StallMonitor()
        super.onResume()
    }

    override fun onPause() {
        try {
            super.onPause()
        } finally {
            stallMonitor?.stop()
            stallMonitor = null
        }
    }

    companion object {
        private const val EXTRA_FRAGMENT_ARGS = "EXTRA_FRAGMENT_ARGS"
        private const val ATTACHMENTS_PREVIEW_RESULT = "ATTACHMENTS_PREVIEW_RESULT"
        private const val ATTACHMENTS_PREVIEW_CAPTION_RESULT = "ATTACHMENTS_PREVIEW_CAPTION_RESULT"
        private const val ATTACHMENTS_PREVIEW_SPOILER_RESULT = "ATTACHMENTS_PREVIEW_SPOILER_RESULT"

        fun newIntent(context: Context, args: AttachmentsPreviewArgs): Intent {
            return Intent(context, AttachmentsPreviewActivity::class.java).apply {
                putExtra(EXTRA_FRAGMENT_ARGS, args)
            }
        }

        fun getOutput(intent: Intent): List<ContentAttachmentData> {
            return intent.getSerializableExtraCompat<ArrayList<ContentAttachmentData>>(ATTACHMENTS_PREVIEW_RESULT).orEmpty()
        }

        /** One caption per attachment, in the same order as [getOutput]. */
        fun getCaptionsOutput(intent: Intent): List<String> {
            return intent.getStringArrayListExtra(ATTACHMENTS_PREVIEW_CAPTION_RESULT).orEmpty()
        }

        fun getSpoilersOutput(intent: Intent): List<Boolean> {
            return intent.getBooleanArrayExtra(ATTACHMENTS_PREVIEW_SPOILER_RESULT)?.toList().orEmpty()
        }
    }

    override val drawUnderSystemBars = true

    override fun getOtherThemes() = ActivityOtherThemes.AttachmentsPreview

    override fun getBinding() = ActivitySimpleBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.coordinatorLayout

    override fun initUiAndData() {
        makeSystemBarsTransparent()
        if (isFirstCreation()) {
            val fragmentArgs: AttachmentsPreviewArgs = intent?.extras?.getParcelableCompat(EXTRA_FRAGMENT_ARGS) ?: return
            addFragment(views.simpleFragmentContainer, AttachmentsPreviewFragment::class.java, fragmentArgs)
        }
    }

    fun setResultAndFinish(data: List<ContentAttachmentData>, captions: List<String>, spoilers: List<Boolean>) {
        val resultIntent = Intent().apply {
            putExtra(ATTACHMENTS_PREVIEW_RESULT, ArrayList(data))
            putStringArrayListExtra(ATTACHMENTS_PREVIEW_CAPTION_RESULT, ArrayList(captions))
            putExtra(ATTACHMENTS_PREVIEW_SPOILER_RESULT, spoilers.toBooleanArray())
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private class StallMonitor {
        private val thread = HandlerThread("UploadPreviewStalls").apply { start() }
        private val worker = Handler(thread.looper)
        private val main = Handler(Looper.getMainLooper())
        private val lastResponse = AtomicLong(SystemClock.uptimeMillis())
        private val pending = AtomicBoolean()
        private val stopped = AtomicBoolean()
        private val heartbeat = Runnable {
            lastResponse.set(SystemClock.uptimeMillis())
            pending.set(false)
        }
        private val sample = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                val elapsed = SystemClock.uptimeMillis() - lastResponse.get()
                if (elapsed >= 1000) {
                    val stack = main.looper.thread.stackTrace.take(24).joinToString(" <- ")
                    val runtime = Runtime.getRuntime()
                    DebugLog.w {
                        "UPLOADPREVIEW main stallMs=$elapsed heapUsed=${runtime.totalMemory() - runtime.freeMemory()} " +
                                "heapMax=${runtime.maxMemory()} stack=$stack"
                    }
                }
                if (!stopped.get()) {
                    if (pending.compareAndSet(false, true)) main.post(heartbeat)
                    worker.postDelayed(this, 500)
                }
            }
        }

        init {
            worker.post(sample)
        }

        fun stop() {
            if (stopped.getAndSet(true)) return
            worker.removeCallbacksAndMessages(null)
            main.removeCallbacks(heartbeat)
            thread.quit()
        }
    }
}
