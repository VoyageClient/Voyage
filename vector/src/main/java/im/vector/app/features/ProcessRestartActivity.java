/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

// Runs in the ":multidex" process, which skips all app init. Being the top of the task while the main
// process dies stops Android from relaunching the activity that was below it. Java and framework-only
// because on Dalvik the secondary dexes aren't loaded in that process.
public class ProcessRestartActivity extends Activity {

    static final String EXTRA_MAIN_PID = "main_pid";
    static final String EXTRA_NEXT_INTENT = "next_intent";

    @Override
    @SuppressWarnings("deprecation") // The typed getParcelableExtra is API 33.
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int mainPid = getIntent().getIntExtra(EXTRA_MAIN_PID, 0);
        if (mainPid > 0) {
            Process.killProcess(mainPid);
        }
        Intent next = getIntent().getParcelableExtra(EXTRA_NEXT_INTENT);
        if (next != null) {
            startActivity(next);
        }
        finish();
        Runtime.getRuntime().exit(0);
    }
}
