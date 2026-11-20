#!/usr/bin/env bash
# ============================================================================
# MusicClip Visualizer — offline APK build script (no Gradle, no Android SDK).
#
# Pipeline:  aapt2 compile/link  ->  javac  ->  d8  ->  zipalign  ->  apksigner
#
# Toolchain lives in .toolchain/ (see tools/README-toolchain.md).  If a tool is
# not found in .toolchain it falls back to the system PATH, so this script also
# works on a normal machine that has the Android build-tools installed.
#
# Usage:
#   tools/build_apk.sh
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"
BUILD="$ROOT/build/sandbox"
DIST="$ROOT/dist"
APP="$ROOT/app/src/main"

VERSION_NAME="1.1.1"
VERSION_CODE=4
MIN_SDK=24
TARGET_SDK=33
APK_NAME="MusicClip-Visualizer-v${VERSION_NAME}.apk"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- toolchain
if [ -x "$TC/jdk/bin/java" ]; then
  JAVA="$TC/jdk/bin/java"
  KEYTOOL="$TC/jdk/bin/keytool"
else
  command -v java >/dev/null 2>&1 || die "java not found (expected $TC/jdk or system JDK)"
  JAVA="$(command -v java)"
  KEYTOOL="$(command -v keytool || true)"
fi

if [ -f "$TC/toolsjar/tools.jar" ]; then
  JAVAC_CMD=("$JAVA" -cp "$TC/toolsjar/tools.jar" com.sun.tools.javac.Main)
elif command -v javac >/dev/null 2>&1; then
  JAVAC_CMD=("$(command -v javac)")
else
  die "no javac (expected $TC/toolsjar/tools.jar or system javac)"
fi

AAPT2="${AAPT2:-}"
[ -z "$AAPT2" ] && [ -x "$TC/aapt2" ] && AAPT2="$TC/aapt2"
[ -z "$AAPT2" ] && command -v aapt2 >/dev/null 2>&1 && AAPT2="$(command -v aapt2)"
[ -n "$AAPT2" ] || die "aapt2 not found (expected $TC/aapt2 or PATH)"

ZIPALIGN="${ZIPALIGN:-}"
[ -z "$ZIPALIGN" ] && [ -x "$TC/zipalign" ] && ZIPALIGN="$TC/zipalign"
[ -z "$ZIPALIGN" ] && command -v zipalign >/dev/null 2>&1 && ZIPALIGN="$(command -v zipalign)"

D8_JAR="$TC/lib/d8.jar"
[ -f "$D8_JAR" ] || command -v d8 >/dev/null 2>&1 || die "d8 not found (expected $TC/lib/d8.jar)"
APKSIGNER_JAR="$TC/lib/apksigner.jar"
[ -f "$APKSIGNER_JAR" ] || command -v apksigner >/dev/null 2>&1 || die "apksigner not found"

ANDROID_JAR="$TC/android-33.jar"
[ -f "$ANDROID_JAR" ] || die "android.jar not found (expected $ANDROID_JAR)"

KEYSTORE="$ROOT/tools/release.keystore"
KS_PASS="musicclip"
KS_ALIAS="musicclip"

# zipalign ships with a private libc++ — provide it when running the bundled one
if [ -n "$ZIPALIGN" ] && [ -d "$TC" ]; then
  export LD_LIBRARY_PATH="$TC:${LD_LIBRARY_PATH:-}"
fi

# ------------------------------------------------------------ assets (once)
PYTHON="python3"
[ -x "$TC/venv/bin/python" ] && PYTHON="$TC/venv/bin/python"

if [ ! -f "$APP/assets/music/neon_skyline.wav" ]; then
  log "generating built-in music ..."
  "$PYTHON" "$ROOT/tools/make_music.py" || die "music generation failed (need numpy+scipy)"
fi
if [ ! -f "$APP/res/mipmap-xxxhdpi/ic_launcher.png" ]; then
  log "generating launcher icons ..."
  "$PYTHON" "$ROOT/tools/make_icons.py" || die "icon generation failed (need pillow)"
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

# ------------------------------------------------------- 1) resources (aapt2)
log "aapt2 compile resources"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"

log "aapt2 link (manifest + resources + assets)"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  --manifest "$APP/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  --java "$BUILD/gen" \
  -R "$BUILD/res.zip" \
  -A "$APP/assets" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

# ------------------------------------------------------------- 2) javac
log "javac"
SOURCES="$BUILD/sources.txt"
find "$APP/java" "$BUILD/gen" -name '*.java' > "$SOURCES"
"${JAVAC_CMD[@]}" \
  -encoding UTF-8 \
  -source 8 -target 8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  @"$SOURCES"

# --------------------------------------------------------------- 3) d8 -> dex
log "d8 (classes -> dex)"
( cd "$BUILD/classes" && zip -qr "$BUILD/classes.jar" . )
if [ -f "$D8_JAR" ]; then
  "$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release --min-api "$MIN_SDK" \
    --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" \
    "$BUILD/classes.jar"
else
  d8 --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" "$BUILD/classes.jar"
fi
[ -f "$BUILD/dex/classes.dex" ] || die "d8 produced no classes.dex"

log "add classes.dex to apk"
cp "$BUILD/base.apk" "$BUILD/packed.apk"
( cd "$BUILD/dex" && zip -q -j "$BUILD/packed.apk" classes.dex )

# ------------------------------------------------------------- 4) zipalign
if [ -n "$ZIPALIGN" ]; then
  log "zipalign"
  "$ZIPALIGN" -f 4 "$BUILD/packed.apk" "$BUILD/aligned.apk"
else
  log "zipalign not available — skipping (APK still installable)"
  cp "$BUILD/packed.apk" "$BUILD/aligned.apk"
fi

# ------------------------------------------------------------- 5) keystore
if [ ! -f "$KEYSTORE" ]; then
  [ -n "$KEYTOOL" ] || die "keytool not found, cannot create $KEYSTORE"
  log "generate release keystore"
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=MusicClip, OU=OpenSource, O=MusicClip, L=HoChiMinhCity, C=VN"
fi

# ------------------------------------------------------------- 6) sign
log "apksigner sign"
OUT="$DIST/$APK_NAME"
if [ -f "$APKSIGNER_JAR" ]; then
  "$JAVA" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$OUT" "$BUILD/aligned.apk"
  "$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$OUT" | head -5
else
  apksigner sign \
    --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$OUT" "$BUILD/aligned.apk"
  apksigner verify --print-certs "$OUT" | head -5
fi

# ------------------------------------------------------------- 7) sanity
log "badging"
"$AAPT2" dump badging "$OUT" | head -8

SIZE=$(du -h "$OUT" | cut -f1)
log "BUILD OK -> $OUT ($SIZE)"
