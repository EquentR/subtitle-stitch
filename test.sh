#!/usr/bin/env bash
#
# test.sh -- run the pure-JVM logic tests.
#
# Only StitchEngine (crop arithmetic and the linked-crop rules) plus the test
# harness are compiled. android.jar is used purely as a compile-time classpath:
# the tested code never touches a real Android class at runtime, so the tests run
# on any desktop JVM.
#
# Usage: ./test.sh [--sdk DIR] [--java-home DIR]
# Exit code 0 means every test passed.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="android-36"
OUT="$ROOT/build/testclasses"

while [ $# -gt 0 ]; do
    case "$1" in
        --sdk)       ANDROID_SDK_ROOT="$2"; shift 2 ;;
        --java-home) JAVA_HOME="$2"; shift 2 ;;
        -h|--help)   sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

prop() {
    local file="$ROOT/local.properties"
    [ -f "$file" ] || return 1
    local line
    line="$(grep -E "^[[:space:]]*${1}[[:space:]]*=" "$file" | tail -1 || true)"
    [ -n "$line" ] || return 1
    printf '%s' "${line#*=}" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$(prop sdk.dir || true)}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    for guess in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
        if [ -d "$guess" ]; then SDK="$guess"; break; fi
    done
fi
JAVA_HOME="${JAVA_HOME:-$(prop java.home || true)}"
if [ -n "$JAVA_HOME" ] && [ -d "$JAVA_HOME/bin" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
ok=1
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found at $ANDROID_JAR" >&2; ok=0; }
command -v javac >/dev/null 2>&1 || { echo "javac not found" >&2; ok=0; }
command -v java  >/dev/null 2>&1 || { echo "java not found"  >&2; ok=0; }
[ "$ok" -eq 1 ] || { echo "Set ANDROID_SDK_ROOT / JAVA_HOME, or local.properties." >&2; exit 2; }

rm -rf "$OUT"
mkdir -p "$OUT"

echo "==> compile"
javac -encoding UTF-8 -nowarn -classpath "$ANDROID_JAR" -d "$OUT" \
    "$ROOT/src/com/equentr/subtitlestitch/StitchEngine.java" \
    "$ROOT/test/com/equentr/subtitlestitch/CropLogicTest.java"

echo "==> run"
java -cp "$OUT" com.equentr.subtitlestitch.CropLogicTest
