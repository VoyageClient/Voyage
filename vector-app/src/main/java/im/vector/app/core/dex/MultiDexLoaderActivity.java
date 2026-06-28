/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.dex;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

// Minimal first-launch loader shown in the ":multidex" process while the secondary dexes are extracted
// on a worker thread. Uses framework APIs only (no Hilt / secondary-dex classes), since those dexes are
// not yet loaded when this runs. A plain framework theme is set in the manifest so it does not need an
// AppCompat-themed context.
public class MultiDexLoaderActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLoadingView());
        if (!MultiDexLoader.isExtractionNeeded(getApplication())) {
            // A retry raced ahead and extraction is already done — just relaunch the app.
            relaunchApp();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                MultiDexLoader.performExtraction(getApplication());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        relaunchApp();
                    }
                });
            }
        }).start();
    }

    private void relaunchApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
        }
        finish();
    }

    private View buildLoadingView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.addView(new ProgressBar(this));
        TextView label = new TextView(this);
        // Hardcoded rather than a string resource: keeps this primary-dex class free of any R class
        // reference that could resolve into a not-yet-loaded secondary dex.
        label.setText("Updating…");
        label.setGravity(Gravity.CENTER);
        layout.addView(label);
        return layout;
    }
}
