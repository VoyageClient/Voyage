#!/usr/bin/env bash
# Builds the olm native library (libce + JNI glue) for a desktop platform.
# Usage: build-native.sh <linux-x86_64|windows-x86_64> <output-dir>
set -euo pipefail

TARGET="$1"
OUT_DIR="$2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBCE_ROOT="$SCRIPT_DIR/../../libce"
JNI_DIR="$LIBCE_ROOT/android/olm-sdk/src/main/jni"

# Version defines, kept in sync with libce/common.mk
MAJOR=0; MINOR=2; PATCH=2

SOURCES=(
    "$LIBCE_ROOT"/src/account.c
    "$LIBCE_ROOT"/src/base64.c
    "$LIBCE_ROOT"/src/cipher.c
    "$LIBCE_ROOT"/src/crypto.c
    "$LIBCE_ROOT"/src/memory.c
    "$LIBCE_ROOT"/src/message.c
    "$LIBCE_ROOT"/src/olm.c
    "$LIBCE_ROOT"/src/pickle.c
    "$LIBCE_ROOT"/src/ratchet.c
    "$LIBCE_ROOT"/src/session.c
    "$LIBCE_ROOT"/src/utility.c
    "$LIBCE_ROOT"/src/pk.c
    "$LIBCE_ROOT"/src/sas.c
    "$LIBCE_ROOT"/src/ed25519.c
    "$LIBCE_ROOT"/src/error.c
    "$LIBCE_ROOT"/src/inbound_group_session.c
    "$LIBCE_ROOT"/src/megolm.c
    "$LIBCE_ROOT"/src/outbound_group_session.c
    "$LIBCE_ROOT"/src/pickle_encoding.c
    "$LIBCE_ROOT"/lib/crypto-algorithms/sha256.c
    "$LIBCE_ROOT"/lib/crypto-algorithms/aes.c
    "$LIBCE_ROOT"/lib/curve25519-donna/curve25519-donna.c
    "$JNI_DIR"/olm_account.c
    "$JNI_DIR"/olm_session.c
    "$JNI_DIR"/olm_jni_helper.c
    "$JNI_DIR"/olm_inbound_group_session.c
    "$JNI_DIR"/olm_outbound_group_session.c
    "$JNI_DIR"/olm_utility.c
    "$JNI_DIR"/olm_manager.c
    "$JNI_DIR"/olm_pk.c
    "$JNI_DIR"/olm_sas.c
)

COMMON_FLAGS=(
    -shared -O3 -std=c99 -Wall
    -fstack-protector-all -D_FORTIFY_SOURCE=2 -Wformat -Wformat-security
    "-DLIBCE_VERSION_MAJOR=$MAJOR" "-DLIBCE_VERSION_MINOR=$MINOR" "-DLIBCE_VERSION_PATCH=$PATCH"
    -I"$LIBCE_ROOT/include" -I"$LIBCE_ROOT/lib" -I"$JNI_DIR"
)

JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"

mkdir -p "$OUT_DIR"

case "$TARGET" in
    linux-x86_64)
        gcc "${COMMON_FLAGS[@]}" -fPIC \
            -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
            -Wl,-z,relro,-z,now \
            -o "$OUT_DIR/libolm.so" "${SOURCES[@]}"
        ;;
    windows-x86_64)
        # jni.h is platform-independent; only jni_md.h is platform-specific, so pair the
        # host JDK's jni.h with the vendored win32 jni_md.h.
        x86_64-w64-mingw32-gcc "${COMMON_FLAGS[@]}" \
            -I"$JAVA_HOME/include" -I"$SCRIPT_DIR/win32-jni" \
            -static-libgcc \
            -o "$OUT_DIR/olm.dll" "${SOURCES[@]}"
        ;;
    *)
        echo "Unknown target: $TARGET" >&2
        exit 1
        ;;
esac

echo "Built olm for $TARGET into $OUT_DIR"
