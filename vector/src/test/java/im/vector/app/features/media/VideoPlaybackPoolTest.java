/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import im.vector.lib.attachmentviewer.VideoPlayback;
import im.vector.lib.attachmentviewer.VideoPlaybackPool;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class VideoPlaybackPoolTest {
    @Test
    public void reusesPreparedPlayers() {
        VideoPlaybackPool pool = new VideoPlaybackPool();
        FakeVideo video = new FakeVideo(true);

        pool.recycle(video.player, "clip-a");
        assertSame(video.player, pool.acquire("clip-a"));
        pool.recycle(video.player, "clip-a");
        assertSame(video.player, pool.acquire("clip-a"));
        assertEquals(0, video.releases);
        pool.close();
    }

    @Test
    public void releasesBackendsThatCannotReuseTheirDecoder() {
        VideoPlaybackPool pool = new VideoPlaybackPool();
        FakeVideo legacy = new FakeVideo(false);

        pool.recycle(legacy.player, "clip-a");
        pool.close();

        assertEquals(1, legacy.releases);
    }

    @Test
    public void keepsOnlyTwoIdlePlayers() {
        VideoPlaybackPool pool = new VideoPlaybackPool();
        FakeVideo first = new FakeVideo(true);
        FakeVideo second = new FakeVideo(true);
        FakeVideo third = new FakeVideo(true);

        pool.recycle(first.player, "clip-a");
        pool.recycle(second.player, "clip-b");
        pool.recycle(third.player, "clip-c");

        assertEquals(1, first.releases);
        assertSame(second.player, pool.acquire("clip-b"));
        pool.close();
        assertEquals(1, second.releases);
        assertEquals(1, third.releases);
    }

    @Test
    public void keepsPreparedSourcesWhenSwipingBetweenVideos() {
        VideoPlaybackPool pool = new VideoPlaybackPool();
        FakeVideo first = new FakeVideo(true, true);
        FakeVideo second = new FakeVideo(true, true);

        pool.recycle(first.player, "clip-a");
        pool.recycle(second.player, "clip-b");
        assertSame(first.player, pool.acquire("clip-a"));
        pool.recycle(first.player, "clip-a");
        assertSame(second.player, pool.acquire("clip-b"));
        pool.close();

        assertEquals(1, first.releases);
        assertEquals(1, second.releases);
    }

    @Test
    public void retainsPreparedVideoWhenAnotherSourceStarts() {
        VideoPlaybackPool pool = new VideoPlaybackPool();
        FakeVideo first = new FakeVideo(true, true);
        pool.recycle(first.player, "clip-a");

        assertNotSame(first.player, pool.acquire("clip-b"));
        assertSame(first.player, pool.acquire("clip-a"));
        pool.close();
    }

    @Test
    public void usesSingleDecoderFallbackWhenMultipleInstancesAreUnsupported() {
        VideoPlaybackPool pool = new VideoPlaybackPool();
        FakeVideo first = new FakeVideo(true);
        pool.recycle(first.player, "clip-a");

        assertSame(first.player, pool.acquire("clip-b"));
        pool.close();
    }

    @Test
    public void repurposesOldestPreparedSourceWhenCapacityIsReached() {
        VideoPlaybackPool pool = new VideoPlaybackPool();
        FakeVideo first = new FakeVideo(true, true);
        FakeVideo second = new FakeVideo(true, true);
        pool.recycle(first.player, "clip-a");
        pool.recycle(second.player, "clip-b");

        assertSame(first.player, pool.acquire("clip-c"));
        assertSame(second.player, pool.acquire("clip-b"));
        pool.close();
    }

    @Test
    public void decoderFailureReleasesIdleSourcesAndDisablesMultipleInstances() {
        VideoPlaybackPool pool = new VideoPlaybackPool();
        FakeVideo first = new FakeVideo(true, true);
        FakeVideo second = new FakeVideo(true, true);
        pool.recycle(first.player, "clip-a");

        assertTrue(pool.fallbackToSingleDecoder());
        assertEquals(1, first.releases);
        assertFalse(pool.fallbackToSingleDecoder());
        pool.recycle(second.player, "clip-b");
        assertSame(second.player, pool.acquire("clip-c"));
        pool.close();
    }

    @Test
    public void closingReleasesActivePlayersAndPreventsLateRestarts() {
        VideoPlaybackPool pool = new VideoPlaybackPool();
        FakeVideo video = new FakeVideo(true);
        pool.recycle(video.player, "clip-a");
        pool.acquire("clip-a");

        pool.close();

        assertEquals(1, video.releases);
        assertNull(pool.acquire("clip-a"));
        pool.recycle(video.player, "clip-a");
        assertNull(pool.acquire("clip-a"));
        assertEquals(1, video.releases);
    }

    private static class FakeVideo {
        int releases;
        final VideoPlayback player;

        FakeVideo(boolean reusable) {
            this(reusable, false);
        }

        FakeVideo(boolean reusable, boolean retainSource) {
            player = (VideoPlayback) Proxy.newProxyInstance(
                    VideoPlayback.class.getClassLoader(), new Class<?>[]{VideoPlayback.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "prepareForReuse": return reusable;
                            case "getCanRetainPreparedSource": return retainSource;
                            case "release": releases++; return null;
                            case "equals": return proxy == args[0];
                            case "hashCode": return System.identityHashCode(proxy);
                            default: throw new AssertionError("Unexpected player call: " + method.getName());
                        }
                    });
        }
    }
}
