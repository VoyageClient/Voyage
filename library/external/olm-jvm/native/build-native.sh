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
MAJOR=0; MINOR=2; PATCH=3

SOURCES=(
    "$LIBCE_ROOT"/src/account.c
    "$LIBCE_ROOT"/src/base64.c
    "$LIBCE_ROOT"/src/cipher.c
    "$LIBCE_ROOT"/src/crypto.c
    "$LIBCE_ROOT"/src/dehydrated_device.c
    "$LIBCE_ROOT"/src/memory.c
    "$LIBCE_ROOT"/src/message.c
    "$LIBCE_ROOT"/src/olm.c
    "$LIBCE_ROOT"/src/pickle.c
    "$LIBCE_ROOT"/src/ratchet.c
    "$LIBCE_ROOT"/src/session.c
    "$LIBCE_ROOT"/src/utility.c
    "$LIBCE_ROOT"/src/pk.c
    "$LIBCE_ROOT"/src/sas.c
    "$LIBCE_ROOT"/src/error.c
    "$LIBCE_ROOT"/src/inbound_group_session.c
    "$LIBCE_ROOT"/src/megolm.c
    "$LIBCE_ROOT"/src/outbound_group_session.c
    "$LIBCE_ROOT"/src/pickle_encoding.c
    "$LIBCE_ROOT"/lib/aes-ct64/aes_ct64.c
    "$LIBCE_ROOT"/lib/aes-ct64/aes_ct64_enc.c
    "$LIBCE_ROOT"/lib/aes-ct64/aes_ct64_dec.c
    "$LIBCE_ROOT"/lib/aes-ct64/aes_ct64_cbcenc.c
    "$LIBCE_ROOT"/lib/aes-ct64/aes_ct64_cbcdec.c
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

# There is no system libsodium to link when cross-compiling, so Windows builds the same
# subset of the vendored submodule that the Android ndk-build does (see its Android.mk).
SODIUM_DIR="$LIBCE_ROOT/lib/libsodium/src/libsodium"
SODIUM_SOURCES=(
    sodium/codecs.c
    sodium/core.c
    sodium/runtime.c
    sodium/utils.c
    sodium/version.c
    randombytes/randombytes.c
    randombytes/internal/randombytes_internal_random.c
    randombytes/sysrandom/randombytes_sysrandom.c
    crypto_verify/verify.c
    crypto_hash/sha256/hash_sha256.c
    crypto_hash/sha256/cp/hash_sha256_cp.c
    crypto_hash/sha512/hash_sha512.c
    crypto_hash/sha512/cp/hash_sha512_cp.c
    crypto_auth/hmacsha256/auth_hmacsha256.c
    crypto_kdf/hkdf/kdf_hkdf_sha256.c
    crypto_core/ed25519/core_ed25519.c
    crypto_core/ed25519/ref10/ed25519_ref10.c
    crypto_core/hchacha20/core_hchacha20.c
    crypto_core/hsalsa20/core_hsalsa20.c
    crypto_core/hsalsa20/ref2/core_hsalsa20_ref2.c
    crypto_core/salsa/ref/core_salsa_ref.c
    crypto_core/softaes/softaes.c
    crypto_scalarmult/curve25519/scalarmult_curve25519.c
    crypto_scalarmult/curve25519/ref10/x25519_ref10.c
    crypto_scalarmult/ed25519/ref10/scalarmult_ed25519_ref10.c
    crypto_sign/ed25519/sign_ed25519.c
    crypto_sign/ed25519/ref10/keypair.c
    crypto_sign/ed25519/ref10/open.c
    crypto_sign/ed25519/ref10/sign.c
    crypto_generichash/blake2b/ref/blake2b-ref.c
    crypto_generichash/blake2b/ref/blake2b-compress-ref.c
    crypto_generichash/blake2b/ref/generichash_blake2b.c
    crypto_onetimeauth/poly1305/onetimeauth_poly1305.c
    crypto_onetimeauth/poly1305/donna/poly1305_donna.c
    crypto_stream/chacha20/stream_chacha20.c
    crypto_stream/chacha20/ref/chacha20_ref.c
    crypto_stream/salsa20/stream_salsa20.c
    crypto_stream/salsa20/ref/salsa20_ref.c
    crypto_pwhash/argon2/argon2-core.c
    crypto_pwhash/argon2/argon2-fill-block-ref.c
    crypto_pwhash/argon2/argon2-fill-block-neon.c
    crypto_pwhash/argon2/blake2b-long.c
    crypto_aead/aegis128l/aead_aegis128l.c
    crypto_aead/aegis128l/aegis128l_soft.c
    crypto_aead/aegis256/aead_aegis256.c
    crypto_aead/aegis256/aegis256_soft.c
    crypto_aead/chacha20poly1305/aead_chacha20poly1305.c
)

JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"

mkdir -p "$OUT_DIR"

case "$TARGET" in
    linux-x86_64)
        # libce delegates curve25519/ed25519/hashing to libsodium; link the system library.
        gcc "${COMMON_FLAGS[@]}" -fPIC \
            -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
            -Wl,-z,relro,-z,now \
            -o "$OUT_DIR/libolm.so" "${SOURCES[@]}" -lsodium
        ;;
    windows-x86_64)
        # jni.h is platform-independent; only jni_md.h is platform-specific, so pair the
        # host JDK's jni.h with the vendored win32 jni_md.h.
        x86_64-w64-mingw32-gcc "${COMMON_FLAGS[@]}" \
            -I"$JAVA_HOME/include" -I"$SCRIPT_DIR/win32-jni" \
            -DCONFIGURED=1 -DSODIUM_STATIC=1 -DHAVE_TI_MODE=1 \
            -I"$LIBCE_ROOT/lib/sodium-generated" -I"$LIBCE_ROOT/lib/sodium-generated/sodium" \
            -I"$SODIUM_DIR/include" -I"$SODIUM_DIR/include/sodium" \
            -static-libgcc \
            -o "$OUT_DIR/olm.dll" "${SOURCES[@]}" "${SODIUM_SOURCES[@]/#/$SODIUM_DIR/}" -ladvapi32
        ;;
    *)
        echo "Unknown target: $TARGET" >&2
        exit 1
        ;;
esac

echo "Built olm for $TARGET into $OUT_DIR"
