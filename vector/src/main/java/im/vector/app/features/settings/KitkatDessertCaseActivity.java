/*
 * Copyright (C) 2013 The Android Open Source Project
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

package im.vector.app.features.settings;

import android.app.Activity;

/**
 * Port of KitKat's dessert case (com.android.systemui.DessertCase, AOSP tag android-4.4_r1),
 * minus the SystemUI daydream component it used to enable on first launch.
 */
public class KitkatDessertCaseActivity extends Activity {
    DessertCaseView mView;

    @Override
    public void onStart() {
        super.onStart();

        mView = new DessertCaseView(this);

        DessertCaseView.RescalingContainer container = new DessertCaseView.RescalingContainer(this);

        container.setView(mView);

        setContentView(container);
    }

    @Override
    public void onResume() {
        super.onResume();
        mView.postDelayed(new Runnable() {
            public void run() {
                mView.start();
            }
        }, 1000);
    }

    @Override
    public void onPause() {
        super.onPause();
        mView.stop();
    }
}
