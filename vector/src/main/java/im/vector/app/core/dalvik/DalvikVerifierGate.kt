/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.dalvik

import android.app.Activity
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import timber.log.Timber
import java.io.File
import kotlin.system.exitProcess

/**
 * On Android 4.0–4.3 (Dalvik, API 14–18) the app crashes on opening a room unless the bytecode
 * verifier is disabled — the verifier eagerly loads referenced classes and overflows Dalvik's fixed
 * 8 MB LinearAlloc region. The only lever is the zygote-global `dalvik.vm.extra-opts=-Xverify:none`,
 * which must be set before the VM starts; with root we persist it in /data/local.prop and reboot so
 * init applies it early in boot.
 */
object DalvikVerifierGate {

    private const val EXTRA_OPTS_PROP = "dalvik.vm.extra-opts"
    private const val VERIFY_NONE = "-Xverify:none"
    private const val LOCAL_PROP = "/data/local.prop"

    private val SU_PATHS = arrayOf(
            "/sbin/su", "/system/bin/su", "/system/xbin/su", "/system/sbin/su",
            "/vendor/bin/su", "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/data/local/su", "/system/bin/failsafe/su", "/system/sd/xbin/su", "/magisk/.core/bin/su"
    )

    /**
     * Shows a blocking gate when running on Dalvik without verification disabled, and returns true so
     * the caller halts normal startup (the dialog either quits the app or triggers a reboot).
     * Returns false when the app may start normally (KitKat+, or the flag is already active).
     */
    fun gateIfNeeded(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) return false
        if (isVerificationDisabled()) return false

        if (isRootAvailable()) {
            Timber.w("DalvikVerifierGate: verifier active on Dalvik, root present — prompting to enable $VERIFY_NONE")
            showEnablePrompt(activity)
        } else {
            Timber.w("DalvikVerifierGate: verifier active on Dalvik, no root — app cannot run")
            showBlockingErrorAndQuit(activity, CommonStrings.dalvik_verifier_needs_root_title, CommonStrings.dalvik_verifier_needs_root_message)
        }
        return true
    }

    private fun isVerificationDisabled(): Boolean = getSystemProperty(EXTRA_OPTS_PROP).contains(VERIFY_NONE)

    private fun isRootAvailable(): Boolean = SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    private fun dialogBuilder(activity: Activity): MaterialAlertDialogBuilder {
        val themed = ContextThemeWrapper(activity, ThemeUtils.getApplicationThemeRes(activity))
        return MaterialAlertDialogBuilder(themed)
    }

    private fun showEnablePrompt(activity: Activity) {
        dialogBuilder(activity)
                .setTitle(CommonStrings.dalvik_verifier_dialog_title)
                .setMessage(CommonStrings.dalvik_verifier_dialog_message)
                .setCancelable(false)
                .setPositiveButton(CommonStrings.dalvik_verifier_action_enable) { _, _ -> onEnableConfirmed(activity) }
                .setNegativeButton(CommonStrings.dalvik_verifier_action_quit) { _, _ -> quit(activity) }
                .show()
    }

    private fun onEnableConfirmed(activity: Activity) {
        val progress = dialogBuilder(activity)
                .setMessage(CommonStrings.dalvik_verifier_applying)
                .setCancelable(false)
                .show()
        // su blocks on the superuser grant prompt, so keep it off the main thread.
        Thread {
            val ok = enableAndReboot()
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                runCatching { progress.dismiss() }
                // On success the device reboots and kills us shortly.
                if (!ok) {
                    showBlockingErrorAndQuit(activity, CommonStrings.dalvik_verifier_failed_title, CommonStrings.dalvik_verifier_failed_message)
                }
            }
        }.start()
    }

    private fun enableAndReboot(): Boolean {
        // Persist the flag in local.prop (deduped); init re-reads it on every boot and applies it before
        // the VM starts. sync first so the write survives the reboot.
        val script = """
            P=$LOCAL_PROP
            V='$VERIFY_NONE'
            C=${'$'}(cat ${'$'}P 2>/dev/null)
            case "${'$'}C" in
              *"${'$'}V"*) : ;;
              *) echo "$EXTRA_OPTS_PROP=${'$'}V" >> ${'$'}P ;;
            esac
            chmod 644 ${'$'}P
            sync
            reboot
        """.trimIndent()
        return runAsRoot(script)
    }

    private fun runAsRoot(script: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.use { os ->
                os.write((script + "\nexit\n").toByteArray())
                os.flush()
            }
            val code = process.waitFor()
            Timber.i("DalvikVerifierGate: su exited with $code")
            code == 0
        } catch (t: Throwable) {
            Timber.e(t, "DalvikVerifierGate: su execution failed")
            false
        }
    }

    private fun showBlockingErrorAndQuit(activity: Activity, @StringRes titleRes: Int, @StringRes messageRes: Int) {
        dialogBuilder(activity)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setCancelable(false)
                .setPositiveButton(CommonStrings.ok) { _, _ -> quit(activity) }
                .show()
    }

    private fun quit(activity: Activity) {
        ActivityCompat.finishAffinity(activity)
        exitProcess(0)
    }

    private fun getSystemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String).orEmpty()
        } catch (t: Throwable) {
            readGetprop(key)
        }
    }

    private fun readGetprop(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (t: Throwable) {
            ""
        }
    }
}
