/*
 * Copyright (c) 2026 New Vector Ltd
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

#include "CodecOggOpusDecoder.h"

#include <opusfile.h>
#include <cstdio>
#include <cstdint>

// libopusfile always decodes to 48 kHz.
static const int kSampleRate = 48000;

namespace {

// Android targets are all little-endian, matching the WAV byte order, so a raw write is correct.
void writeLe32(FILE* f, uint32_t v) { fwrite(&v, sizeof(v), 1, f); }
void writeLe16(FILE* f, uint16_t v) { fwrite(&v, sizeof(v), 1, f); }

void writeWavHeader(FILE* f, int channels, uint32_t dataBytes) {
    const uint32_t byteRate = kSampleRate * channels * 2;
    fwrite("RIFF", 1, 4, f);
    writeLe32(f, 36 + dataBytes);
    fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f);
    writeLe32(f, 16);
    writeLe16(f, 1); // PCM
    writeLe16(f, (uint16_t) channels);
    writeLe32(f, kSampleRate);
    writeLe32(f, byteRate);
    writeLe16(f, (uint16_t) (channels * 2)); // block align
    writeLe16(f, 16); // bits per sample
    fwrite("data", 1, 4, f);
    writeLe32(f, dataBytes);
}

} // namespace

int CodecOggOpusDecoder::decodeToWav(const char* inputPath, const char* outputPath) {
    int error = 0;
    OggOpusFile* of = op_open_file(inputPath, &error);
    if (of == nullptr) {
        return error != 0 ? error : -1;
    }

    int channels = op_channel_count(of, -1);
    if (channels < 1) {
        op_free(of);
        return -1;
    }

    FILE* out = fopen(outputPath, "wb");
    if (out == nullptr) {
        op_free(of);
        return -1;
    }

    // Reserve space for the 44-byte header; patch it once the data size is known.
    uint8_t placeholder[44] = {0};
    fwrite(placeholder, 1, sizeof(placeholder), out);

    // Largest Opus frame is 120 ms = 5760 samples/channel at 48 kHz; size generously for stereo.
    short pcm[5760 * 2];
    uint32_t dataBytes = 0;
    int result = 0;
    for (;;) {
        int samplesPerChannel = op_read(of, pcm, sizeof(pcm) / sizeof(short), nullptr);
        if (samplesPerChannel < 0) {
            result = samplesPerChannel;
            break;
        }
        if (samplesPerChannel == 0) {
            break; // end of stream
        }
        size_t shorts = (size_t) samplesPerChannel * channels;
        fwrite(pcm, sizeof(short), shorts, out);
        dataBytes += shorts * sizeof(short);
    }

    fseek(out, 0, SEEK_SET);
    writeWavHeader(out, channels, dataBytes);
    fclose(out);
    op_free(of);

    return result;
}
