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

#ifndef CODEC_OGG_OPUS_DECODER_H
#define CODEC_OGG_OPUS_DECODER_H

/**
 * Decodes an Ogg/Opus file to a 16-bit PCM WAV file using libopusfile. Android's MediaPlayer only
 * supports Opus-in-Ogg from API 24, so on older devices we transcode to WAV (which MediaPlayer can
 * always play) instead of decoding/streaming ourselves.
 */
class CodecOggOpusDecoder {
public:
    // Returns 0 on success, or a negative libopusfile error code on failure.
    int decodeToWav(const char* inputPath, const char* outputPath);
};

#endif
