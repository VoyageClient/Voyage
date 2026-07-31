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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Desktop-portability proof: the bundled native library loads on a plain JVM and a full
 * olm session handshake (identity keys, one-time keys, encrypt, decrypt) round-trips.
 */
public class OlmJvmSmokeTest {

    @Test
    public void nativeLibraryLoadsAndReportsVersion() {
        String version = new OlmManager().getOlmLibVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
    }

    @Test
    public void olmSessionEncryptDecryptRoundTrip() throws Exception {
        OlmAccount alice = new OlmAccount();
        OlmAccount bob = new OlmAccount();
        try {
            String bobIdentityKey = bob.identityKeys().get("curve25519");
            bob.generateOneTimeKeys(1);
            String bobOneTimeKey = bob.oneTimeKeys().get("curve25519").values().iterator().next();

            OlmSession aliceSession = new OlmSession();
            try {
                aliceSession.initOutboundSession(alice, bobIdentityKey, bobOneTimeKey);
                String clearMessage = "Hello from the desktop JVM!";
                OlmMessage encrypted = aliceSession.encryptMessage(clearMessage);
                assertNotNull(encrypted);

                OlmSession bobSession = new OlmSession();
                try {
                    bobSession.initInboundSession(bob, encrypted.mCipherText);
                    assertEquals(clearMessage, bobSession.decryptMessage(encrypted));
                } finally {
                    bobSession.releaseSession();
                }
            } finally {
                aliceSession.releaseSession();
            }
        } finally {
            alice.releaseAccount();
            bob.releaseAccount();
        }
    }
}
