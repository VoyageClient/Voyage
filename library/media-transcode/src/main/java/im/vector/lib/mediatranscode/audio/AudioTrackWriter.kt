/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

import android.media.MediaFormat
import im.vector.lib.mediatranscode.MuxerSession

/** Gets sound into the muxer, either by copying the source packets or re-encoding at a new speed. */
internal interface AudioTrackWriter {

    /** Null until the format is known. Only the re-encoding path has to wait for it. */
    val format: MediaFormat?

    /** Puts the track's zero at the same source moment as the video's. */
    fun rebase(baseUs: Long)

    /** Writes sound out to (but not past) [videoPtsUs], in output time. */
    fun pumpUpTo(videoPtsUs: Long, muxer: MuxerSession)

    fun release()
}
