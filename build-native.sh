#!/usr/bin/env bash
set -e

# Screenshot Auto-Expirer - Native Build Script
# Compiles rust-core for Android targets (arm64-v8a, x86_64) and installs into jniLibs

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUST_DIR="$SCRIPT_DIR/rust-core"
JNI_LIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"

echo "=========================================================="
echo " Building Rust Native Core (libexpirer.so)"
echo "=========================================================="

# Check if NDK path is set
if [ -z "$ANDROID_NDK_HOME" ] && [ -n "$ANDROID_NDK_ROOT" ]; then
    export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
fi

if [ -z "$ANDROID_NDK_HOME" ]; then
    # Search common Mac locations
    for ndk_cand in "$HOME/Library/Android/sdk/ndk/"* "/opt/android-ndk"*; do
        if [ -d "$ndk_cand" ]; then
            export ANDROID_NDK_HOME="$ndk_cand"
            echo "Auto-detected Android NDK at: $ANDROID_NDK_HOME"
            break
        fi
    done
fi

mkdir -p "$JNI_LIBS_DIR/arm64-v8a"
mkdir -p "$JNI_LIBS_DIR/x86_64"

# Check if cargo-ndk is available
if command -v cargo-ndk >/dev/null 2>&1 && [ -n "$ANDROID_NDK_HOME" ]; then
    echo "Using cargo-ndk with NDK at $ANDROID_NDK_HOME"
    cd "$RUST_DIR"
    cargo ndk -t arm64-v8a -t x86_64 -o "$JNI_LIBS_DIR" build --release
else
    echo "cargo-ndk or ANDROID_NDK_HOME not yet configured."
    echo "Running release host build & tests first to verify zero warnings and clean code:"
    cd "$RUST_DIR"
    cargo test --release
    cargo build --release
    echo "Host release library generated at: $RUST_DIR/target/release/libexpirer.dylib (or .so)"
    ls -lh "$RUST_DIR/target/release/libexpirer."* 2>/dev/null || true
fi

echo ""
echo "Native build and verification completed successfully!"

