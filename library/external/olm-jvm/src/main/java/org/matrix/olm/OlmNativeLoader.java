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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads the bundled olm native library on desktop JVMs, sqlite-jdbc style: the platform's
 * shared library is extracted from jar resources to a temp file and loaded from there.
 * A library already reachable via java.library.path wins.
 */
final class OlmNativeLoader {

    private static boolean loaded = false;

    private OlmNativeLoader() {
    }

    static synchronized void load() {
        if (loaded) {
            return;
        }
        try {
            System.loadLibrary("olm");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) {
            // fall through to the bundled library
        }
        String platform = platform();
        String libName = System.mapLibraryName("olm");
        String resource = "/org/matrix/olm/natives/" + platform + "/" + libName;
        try (InputStream in = OlmNativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("No bundled olm native library for " + platform + " (missing resource " + resource + ")");
            }
            String suffix = libName.substring(libName.lastIndexOf('.'));
            Path tmp = Files.createTempFile("olm-native", suffix);
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract bundled olm native library: " + e);
        }
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osPart;
        if (os.contains("linux")) {
            osPart = "linux";
        } else if (os.contains("windows")) {
            osPart = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osPart = "macos";
        } else {
            osPart = os.replaceAll("\\s", "");
        }
        String archPart;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archPart = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archPart = "aarch64";
        } else {
            archPart = arch;
        }
        return osPart + "-" + archPart;
    }
}
