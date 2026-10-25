#!/usr/bin/env bash
#
# build.sh -- build the Subtitle Stitch APK without Gradle.
#
# Drives the Android SDK command line tools directly:
#   aapt2 compile -> aapt2 link -> javac -> d8 -> package -> zipalign -> apksigner
#
# Configuration (highest priority first):
#   1. command line flags
#   2. environment variables
#   3. local.properties (git-ignored)
#   4. auto-detection ($ANDROID_HOME, ~/Android/Sdk, etc.)
#
# Usage:
#   ./build.sh                 build and sign with an auto-generated debug key
#   ./build.sh --release       label the output as a release build
#   ./build.sh --install       build, then install on the connected device
#   ./build.sh --out DIR       write build products to DIR
#
# Signing:
#   PKCS#12 keystore via local.properties (keystore.path / keystore.pass /
#   keystore.alias) or the STITCH_KEYSTORE / STITCH_KS_PASS / STITCH_KS_ALIAS
#   environment variables. Without a keystore a throwaway debug key is generated.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PACKAGE="com.equentr.subtitlestitch"
VERSION_NAME="1.1.0"
VERSION_CODE="2"
MIN_SDK="29"
TARGET_SDK="36"
BUILD_TOOLS_VERSION="36.0.0"
PLATFORM="android-36"

OUT=""
RELEASE=0
INSTALL=0
SERIAL=""
ADB="adb"

# ---------------------------------------------------------------- arguments

while [ $# -gt 0 ]; do
    case "$1" in
        --release)  RELEASE=1; shift ;;
        --install)  INSTALL=1; shift ;;
        --out)      OUT="$2"; shift 2 ;;
        --serial)   SERIAL="$2"; shift 2 ;;
        --sdk)      ANDROID_SDK_ROOT="$2"; shift 2 ;;
        --java-home) JAVA_HOME="$2"; shift 2 ;;
        -h|--help)  sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

# ------------------------------------------------------------ configuration

# read a key from local.properties if present
prop() {
    local key="$1" file="$ROOT/local.properties"
    [ -f "$file" ] || return 1
    local line
    line="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$file" | tail -1 || true)"
    [ -n "$line" ] || return 1
    printf '%s' "${line#*=}" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$(prop sdk.dir || true)}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    for guess in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/usr/local/lib/android/sdk"; do
        if [ -d "$guess" ]; then SDK="$guess"; break; fi
    done
fi
JAVA_HOME="${JAVA_HOME:-$(prop java.home || true)}"

KS_PATH="${STITCH_KEYSTORE:-$(prop keystore.path || true)}"
KS_PASS="${STITCH_KS_PASS:-$(prop keystore.pass || echo android)}"
KS_ALIAS="${STITCH_KS_ALIAS:-$(prop keystore.alias || echo debug)}"

[ -n "$OUT" ] || OUT="${STITCH_OUT_DIR:-$ROOT/build}"

BUILD_TOOLS="$SDK/build-tools/$BUILD_TOOLS_VERSION"
# Fall back to any installed build-tools that can actually run, so the script is
# not pinned to one exact version (CI images and local SDKs differ).
if [ ! -x "$BUILD_TOOLS/aapt2" ] && [ ! -f "$BUILD_TOOLS/aapt2.exe" ]; then
    for candidate in "$SDK"/build-tools/*/; do
        if [ -x "${candidate}aapt2" ] || [ -f "${candidate}aapt2.exe" ]; then
            BUILD_TOOLS="${candidate%/}"
        fi
    done
fi
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
# Same for the platform: accept the highest android-NN if 36 is unavailable.
if [ ! -f "$ANDROID_JAR" ]; then
    for candidate in "$SDK"/platforms/android-*/android.jar; do
        [ -f "$candidate" ] && ANDROID_JAR="$candidate"
    done
fi

if [ -n "$JAVA_HOME" ] && [ -d "$JAVA_HOME/bin" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# --------------------------------------------------------------- preflight

fail=0
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    echo "Android SDK not found. Set ANDROID_SDK_ROOT or sdk.dir in local.properties." >&2
    fail=1
else
    [ -f "$BUILD_TOOLS/aapt2" ] || [ -f "$BUILD_TOOLS/aapt2.exe" ] || {
        echo "aapt2 missing: install build-tools;$BUILD_TOOLS_VERSION" >&2; fail=1; }
    [ -f "$ANDROID_JAR" ] || {
        echo "android.jar missing: install platforms;$PLATFORM" >&2; fail=1; }
fi
command -v javac >/dev/null 2>&1 || { echo "javac not found: install JDK 17+ or set JAVA_HOME" >&2; fail=1; }
command -v zip   >/dev/null 2>&1 || { echo "zip not found: install the zip package"   >&2; fail=1; }
command -v unzip >/dev/null 2>&1 || { echo "unzip not found: install the unzip package" >&2; fail=1; }
[ "$fail" -eq 0 ] || exit 2

echo "build output dir: $OUT"
mkdir -p "$OUT"

# wipe stale intermediates so a failed build cannot mix with a fresh one
rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res-compiled"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res-compiled"

# ------------------------------------------------------------------ helpers

step() { printf '==> %s\n' "$1"; }

# aapt2 runs as aapt2 / aapt2.exe depending on the host
AAPT2="$BUILD_TOOLS/aapt2"
[ -x "$AAPT2" ] || AAPT2="$BUILD_TOOLS/aapt2.exe"
ZIPALIGN="$BUILD_TOOLS/zipalign"
[ -x "$ZIPALIGN" ] || ZIPALIGN="$BUILD_TOOLS/zipalign.exe"
APKSIGNER="$BUILD_TOOLS/apksigner"
[ -x "$APKSIGNER" ] || APKSIGNER="$BUILD_TOOLS/apksigner.bat"
D8="$BUILD_TOOLS/d8"
[ -x "$D8" ] || D8="$BUILD_TOOLS/d8.bat"

# ------------------------------------------------------------------- res

step "aapt2 compile"
find "$ROOT/res" -type f -print0 | while IFS= read -r -d '' f; do
    "$AAPT2" compile -o "$OUT/res-compiled" "$f"
done

step "aapt2 link"
flats=("$OUT"/res-compiled/*.flat)
if [ ! -e "${flats[0]}" ]; then
    echo "no resources compiled" >&2; exit 1
fi
"$AAPT2" link \
    -o "$OUT/res.zip" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --auto-add-overlay \
    "${flats[@]}"

# ------------------------------------------------------------------ java

step "javac"
# collect sources into a list file: arg lists can exceed the shell limit
: > "$OUT/sources.txt"
find "$ROOT/src" "$OUT/gen" -name '*.java' -print >> "$OUT/sources.txt"
src_count="$(wc -l < "$OUT/sources.txt" | tr -d ' ')"
javac -source 17 -target 17 -encoding UTF-8 -nowarn \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt" 2>&1 | grep -v 'uses unchecked or unsafe' || true
[ -f "$OUT/classes/$PACKAGE/MainActivity.class" ] || {
    echo "compilation produced no classes" >&2; exit 1; }
echo "    compiled $src_count source files"

# ------------------------------------------------------------------- dex

step "d8"
find "$OUT/classes" -name '*.class' -print > "$OUT/classes.txt"
"$D8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
    --output "$OUT/dex" $(cat "$OUT/classes.txt")

[ -f "$OUT/dex/classes.dex" ] || { echo "d8 produced no classes.dex" >&2; exit 1; }

# --------------------------------------------------------------- package

step "package"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/SubtitleStitch.apk"
# Repack aapt2's output, replacing compression for resources.arsc (must be
# STORED and 4-byte aligned, enforced by zipalign afterwards) and adding the dex.
pack_apk() {
    # Use zip if available; otherwise fall back to java's jar tool.
    local tmp="$OUT/pack"
    rm -rf "$tmp"; mkdir -p "$tmp"
    (cd "$tmp" && unzip -q "$OUT/res.zip")
    rm -rf "$tmp/META-INF"
    cp "$OUT/dex/classes.dex" "$tmp/classes.dex"
    (cd "$tmp" && zip -q -r -X "$OUT/unsigned.apk" . )
    rm -rf "$tmp"
}
pack_apk

step "zipalign"
"$ZIPALIGN" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ------------------------------------------------------------------ sign

step "generate debug keystore"
if [ -z "$KS_PATH" ] || [ ! -f "$KS_PATH" ]; then
    KS_PATH="$OUT/debug.keystore"
    if [ ! -f "$KS_PATH" ]; then
        keytool -genkeypair -keystore "$KS_PATH" -storetype PKCS12 \
            -storepass android -keypass android -alias debug \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
    fi
    KS_PASS=android
    KS_ALIAS=debug
    echo "    no keystore configured, signing with the debug key"
fi

step "apksigner"
"$APKSIGNER" sign \
    --ks "$KS_PATH" \
    --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" \
    --key-pass "pass:$KS_PASS" \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --out "$OUT/SubtitleStitch.apk" \
    "$OUT/aligned.apk"

step "verify"
"$APKSIGNER" verify --print-certs "$OUT/SubtitleStitch.apk" >/dev/null

size="$(wc -c < "$OUT/SubtitleStitch.apk" | tr -d ' ')"
kind="debug"; [ "$RELEASE" -eq 1 ] && kind="release"
printf '\nBUILD OK (%s): %s (%s bytes)\n' "$kind" "$OUT/SubtitleStitch.apk" "$size"

# --------------------------------------------------------------- install

if [ "$INSTALL" -eq 1 ]; then
    adb_args=()
    [ -n "$SERIAL" ] && adb_args=(-s "$SERIAL")
    step "install"
    "$ADB" "${adb_args[@]}" push "$OUT/SubtitleStitch.apk" /data/local/tmp/stitch.apk
    "$ADB" "${adb_args[@]}" shell pm install -r /data/local/tmp/stitch.apk
fi
