/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import im.vector.lib.attachmentviewer.VideoDecoderCapabilities;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class VideoDecoderCapabilitiesTest {
    @Test
    public void waitsForFormatWhenDecoderInitializesFirst() {
        int[] queries = {0};
        VideoDecoderCapabilities capabilities = new VideoDecoderCapabilities((name, mime) -> {
            assertEquals("sample.decoder", name);
            assertEquals("video/example", mime);
            queries[0]++;
            return 3;
        });

        capabilities.onDecoderInitialized("sample.decoder");
        assertFalse(capabilities.getCanRetainSource());
        assertEquals(0, queries[0]);
        capabilities.onFormatChanged("video/example");

        assertTrue(capabilities.getCanRetainSource());
        assertEquals(1, queries[0]);
    }

    @Test
    public void handlesFormatArrivingBeforeDecoder() {
        VideoDecoderCapabilities capabilities = new VideoDecoderCapabilities((name, mime) -> 3);

        capabilities.onFormatChanged("video/example");
        assertFalse(capabilities.getCanRetainSource());
        capabilities.onDecoderInitialized("sample.decoder");

        assertTrue(capabilities.getCanRetainSource());
    }

    @Test
    public void clearsPreviousSourceBeforeCheckingAnotherDecoder() {
        int[] queries = {0};
        VideoDecoderCapabilities capabilities = new VideoDecoderCapabilities((name, mime) -> {
            queries[0]++;
            return name.equals("sample.decoder") ? 3 : 1;
        });
        capabilities.onDecoderInitialized("sample.decoder");
        capabilities.onFormatChanged("video/example");
        assertTrue(capabilities.getCanRetainSource());

        capabilities.clear();
        capabilities.onDecoderInitialized("single.decoder");
        assertFalse(capabilities.getCanRetainSource());
        assertEquals(1, queries[0]);
        capabilities.onFormatChanged("video/example");

        assertFalse(capabilities.getCanRetainSource());
        assertEquals(2, queries[0]);
    }

    @Test
    public void usesSingleDecoderWhenCapabilityQueryFails() {
        VideoDecoderCapabilities capabilities = new VideoDecoderCapabilities((name, mime) -> {
            throw new IllegalArgumentException("Unknown decoder");
        });

        capabilities.onDecoderInitialized("unknown.decoder");
        capabilities.onFormatChanged("video/example");

        assertFalse(capabilities.getCanRetainSource());
    }
}
