/*
 * Copyright 2026 New Vector Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.olm;

/**
 * Desktop-JVM replacement for the Android OlmManager: same package and native surface, but the
 * library is loaded from bundled jar resources instead of the APK, and there is no
 * Context-based detailed version.
 */
public class OlmManager {

    public OlmManager() {
    }

    static {
        OlmNativeLoader.load();
    }

    public String getVersion() {
        return "olm-jvm";
    }

    /**
     * Provide the native OLM lib version.
     * @return the lib version as a string
     */
    public String getOlmLibVersion() {
        return getOlmLibVersionJni();
    }

    public native String getOlmLibVersionJni();
}
