/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

/**
 * Blanks the metadata that says where a file was made and what made it, and leaves the rest alone:
 * the title, artist, album and cover art of a music file are worth keeping; a camera's model is not.
 *
 * Identifying boxes are overwritten as `free` space rather than removed, so the file keeps its
 * length and every chunk offset in its index stays correct — which is what makes this a handful of
 * four-byte writes instead of a full re-mux.
 */
internal object Mp4MetadataScrubber {

    enum class Outcome {
        /** Not a container this understands; the caller decides what else to do. */
        UNSUPPORTED,

        /** Understood, and there was nothing identifying in it. */
        NOTHING_TO_STRIP,

        /** Understood, and the identifying boxes have been blanked. */
        SCRUBBED,
    }

    fun scrub(file: File): Outcome = runCatching {
        RandomAccessFile(file, "rw").use { raf ->
            if (!looksLikeMp4(raf)) return@use Outcome.UNSUPPORTED
            val edits = mutableListOf<Edit>()
            walk(raf, 0, raf.length(), "", edits)
            if (edits.isEmpty()) return@use Outcome.NOTHING_TO_STRIP
            edits.forEach { edit ->
                raf.seek(edit.position)
                raf.write(edit.bytes)
            }
            Timber.d("## Metadata: blanked ${edits.size} identifying boxes in ${file.name}")
            Outcome.SCRUBBED
        }
    }.getOrElse {
        Timber.w(it, "## Metadata: cannot read ${file.name} as mp4")
        Outcome.UNSUPPORTED
    }

    private fun looksLikeMp4(raf: RandomAccessFile): Boolean {
        if (raf.length() < HEADER_SIZE) return false
        raf.seek(4)
        return ByteArray(4).also { raf.readFully(it) }.toType() == "ftyp"
    }

    /** A pending overwrite: [bytes] written at [position]. */
    private class Edit(val position: Long, val bytes: ByteArray)

    /** One box: where it starts, how long it is, and where its own children begin. */
    private class Box(val start: Long, val size: Long, val type: String, val headerSize: Long) {
        val end get() = start + size
        val contentStart get() = start + headerSize
        val typePosition get() = start + 4
    }

    private fun readBox(raf: RandomAccessFile, position: Long, limit: Long): Box? {
        if (position + HEADER_SIZE > limit) return null
        raf.seek(position)
        val declared = raf.readInt().toLong() and 0xFFFFFFFFL
        val type = ByteArray(4).also { raf.readFully(it) }.toType()
        var headerSize = HEADER_SIZE.toLong()
        val size = when (declared) {
            1L -> {
                headerSize = LARGE_HEADER_SIZE.toLong()
                raf.readLong()
            }
            0L -> limit - position
            else -> declared
        }
        if (size < headerSize || position + size > limit) return null
        return Box(position, size, type, headerSize)
    }

    private fun walk(raf: RandomAccessFile, from: Long, to: Long, path: String, edits: MutableList<Edit>) {
        var position = from
        while (true) {
            val box = readBox(raf, position, to) ?: return
            val childPath = if (path.isEmpty()) box.type else "$path/${box.type}"
            when {
                path.isIdentifyingContainer() && box.type in IDENTIFYING_BOXES -> edits.add(Edit(box.typePosition, FREE_TYPE))
                childPath in CONTAINERS -> walk(raf, box.contentStart, box.end, childPath, edits)
                box.type == "meta" -> walkMeta(raf, box, edits)
            }
            position = box.end
        }
    }

    /**
     * A `meta` box carries a version and flags before its children in ISO files and nothing at all
     * in QuickTime ones, so which it is has to be read rather than assumed. Its entries may also be
     * named in a sibling `keys` box and referred to from `ilst` by number, so both are read before
     * anything is decided.
     */
    private fun walkMeta(raf: RandomAccessFile, meta: Box, edits: MutableList<Edit>) {
        val immediate = readBox(raf, meta.contentStart, meta.end)
        val start = if (immediate?.type in META_CHILDREN) meta.contentStart else meta.contentStart + FULL_BOX_EXTRA

        var keyNames = emptyList<String>()
        var position = start
        while (true) {
            val box = readBox(raf, position, meta.end) ?: break
            if (box.type == "keys") keyNames = readKeyNames(raf, box)
            position = box.end
        }

        position = start
        while (true) {
            val box = readBox(raf, position, meta.end) ?: return
            if (box.type == "ilst") blankIdentifyingEntries(raf, box, keyNames, edits)
            position = box.end
        }
    }

    /** The `keys` box: a count, then one entry per key, each naming what an `ilst` index means. */
    private fun readKeyNames(raf: RandomAccessFile, keys: Box): List<String> {
        val names = mutableListOf<String>()
        var position = keys.contentStart + FULL_BOX_EXTRA + 4
        while (true) {
            val entry = readBox(raf, position, keys.end) ?: return names
            val length = (entry.size - HEADER_SIZE).toInt().coerceIn(0, MAX_KEY_LENGTH)
            raf.seek(entry.contentStart)
            names.add(ByteArray(length).also { raf.readFully(it) }.toType())
            position = entry.end
        }
    }

    private fun blankIdentifyingEntries(raf: RandomAccessFile, ilst: Box, keyNames: List<String>, edits: MutableList<Edit>) {
        var position = ilst.contentStart
        var index = 0
        while (true) {
            val entry = readBox(raf, position, ilst.end) ?: return
            index++
            // A named entry says what it is; a numbered one is an index into the keys box.
            val name = if (entry.type.firstOrNull()?.code == 0) keyNames.getOrNull(index - 1) else entry.type
            val identifying = entry.type in IDENTIFYING_BOXES ||
                    (name != null && IDENTIFYING_KEYS.any { name.contains(it, ignoreCase = true) })
            if (identifying) blankEntryValues(raf, entry, edits)
            position = entry.end
        }
    }

    /**
     * Neutralise an `ilst` entry without touching a single box header. Its type is a key index, not a
     * real box type, and `meta` is walked as part of `moov`, so overwriting either — as blanking a
     * plain box does — makes Android's extractor abort the whole `moov` and report a file with no
     * tracks. Only the value bytes inside each `data` box are zeroed; every type and size stays as the
     * muxer wrote it.
     */
    private fun blankEntryValues(raf: RandomAccessFile, entry: Box, edits: MutableList<Edit>) {
        var position = entry.contentStart
        while (true) {
            val box = readBox(raf, position, entry.end) ?: return
            if (box.type == "data") {
                val valueStart = box.contentStart + DATA_VALUE_PREFIX
                val length = (box.end - valueStart).toInt()
                if (length > 0) edits.add(Edit(valueStart, ByteArray(length)))
            }
            position = box.end
        }
    }

    private fun String.isIdentifyingContainer() = this == "moov/udta" || this == "moov/trak/udta"

    private fun ByteArray.toType() = String(this, Charsets.ISO_8859_1)

    private const val HEADER_SIZE = 8
    private const val LARGE_HEADER_SIZE = 16
    private const val FULL_BOX_EXTRA = 4
    private const val MAX_KEY_LENGTH = 255

    // A `data` box opens with a 4-byte type indicator and a 4-byte locale before the value itself.
    private const val DATA_VALUE_PREFIX = 8

    private val FREE_TYPE = "free".toByteArray(Charsets.ISO_8859_1)

    private val CONTAINERS = setOf("moov", "moov/udta", "moov/trak", "moov/trak/udta")
    private val META_CHILDREN = setOf("hdlr", "keys", "ilst", "mdta")

    /** Where it was taken and what took it. Titles, artists, albums and artwork are not here. */
    private val IDENTIFYING_BOXES = setOf(
            "©xyz", "xyz ", "loci", "gps ", "gpmd",
            "©mak", "©mod", "©swr", "©too", "©enc",
            "©day", "©dat",
            "auth", "©aut",
    )

    /** The same again, spelled out, for the reverse-DNS keys Apple and Android write. */
    private val IDENTIFYING_KEYS = setOf(
            "location", "gps", "make", "model", "software", "creationdate",
            "camera", "device", "android.version", "author", "owner", "serial",
    )
}
