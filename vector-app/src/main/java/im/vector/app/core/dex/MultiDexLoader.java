/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.dex;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.multidex.MultiDex;

import java.io.File;
import java.util.List;

// Legacy-multidex first-launch loader. On Dalvik (API < 21) the first launch (and each upgrade) must
// extract + dexopt the secondary dexes, which blocks for tens of seconds. Doing that synchronously in
// Application.attachBaseContext risks an ANR (notably a second tap landing on the frozen process).
// Instead the slow extraction runs in a throwaway ":multidex" process that shows a spinner and absorbs
// input; the main process waits for it, then installs from the now-cached dexes and starts normally.
// Written in Java on purpose: it runs before the secondary dexes are loaded, so it must not pull in any
// kotlin-stdlib class that might live in one of them.
public final class MultiDexLoader {

    private static final String LOADER_PROCESS_SUFFIX = ":multidex";
    private static final String PREFS = "multidex_loader";
    // Keyed on the APK's last-modified time (same trigger MultiDex uses), so a rebuild/upgrade that
    // changes the APK — even at the same versionCode — re-runs extraction through the loader.
    private static final String KEY_INSTALLED_APK = "installed_apk_timestamp";

    private MultiDexLoader() { }

    public static boolean isLoaderProcess(Context context) {
        String name = currentProcessName(context);
        return name != null && name.endsWith(LOADER_PROCESS_SUFFIX);
    }

    // ART (API 21+) has native multidex; Dalvik needs extraction only until we record it for this APK.
    public static boolean isExtractionNeeded(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        return prefs(context).getLong(KEY_INSTALLED_APK, -1L) != apkTimestamp(context);
    }

    /**
     * Returns true when app-level init must be skipped — either we are the throwaway loader process,
     * or we just delegated the slow first-time extraction to it and are exiting. Otherwise installs
     * the (already-extracted, fast) secondary dexes and returns false so normal startup proceeds.
     */
    public static boolean installOrDelegate(Application app) {
        if (isLoaderProcess(app)) {
            return true;
        }
        if (!isExtractionNeeded(app)) {
            MultiDex.install(app);
            return false;
        }
        // First launch on Dalvik: extraction is slow. Run it in the loader process and abort this one
        // before it (or the pending launcher Activity) touches a not-yet-extracted secondary dex. The
        // loader relaunches the app once the dexes are ready (extraction is then cached → fast).
        Intent intent = new Intent(app, MultiDexLoaderActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(intent);
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
        return true; // unreachable
    }

    // Runs in the loader process on a worker thread.
    public static void performExtraction(Application app) {
        MultiDex.install(app);
        prefs(app).edit().putLong(KEY_INSTALLED_APK, apkTimestamp(app)).commit();
    }

    @SuppressWarnings("deprecation") // MODE_MULTI_PROCESS is the intended cross-process channel on Dalvik.
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    private static long apkTimestamp(Context context) {
        try {
            return new File(context.getApplicationInfo().sourceDir).lastModified();
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String currentProcessName(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return null;
        }
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) {
            return null;
        }
        int pid = android.os.Process.myPid();
        for (ActivityManager.RunningAppProcessInfo proc : procs) {
            if (proc.pid == pid) {
                return proc.processName;
            }
        }
        return null;
    }
}
