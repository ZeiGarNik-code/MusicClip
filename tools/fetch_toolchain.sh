#!/usr/bin/env bash
# ============================================================================
# Bootstrap .toolchain/ for tools/build_apk.sh — NO Gradle, NO Android SDK.
#
# All downloads come from public mirrors that require no special account:
#   * JDK runtime (java/keytool)  -> PyPI package jdk4py            (pypi.org)
#   * javac (OpenJDK 8 tools.jar) -> npm package dataslope-tools-jar (npmjs.org)
#   * android.jar (API 33)        -> github.com/Sable/android-platforms
#   * aapt2 / d8 / apksigner /
#     zipalign (build-tools 33)   -> github.com/lipeedev/gendroid
#   * python deps (numpy/scipy/pillow) -> PyPI
#
# Usage:  tools/fetch_toolchain.sh
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"
mkdir -p "$TC/lib" "$TC/toolsjar"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# gh (if present) handles GitHub API auth + large-file raw downloads;
# plain curl works too for anonymous use (rate limit ~60 req/h is plenty here).
fetch_gh() { # $1 = "owner/repo/contents/path"
  if command -v gh >/dev/null 2>&1; then
    gh api -H 'Accept: application/vnd.github.raw' "repos/$1"
  else
    curl -fsSL -H 'Accept: application/vnd.github.raw' "https://api.github.com/repos/$1"
  fi
}

# ---------------------------------------------------------------- 1) JDK
if [ ! -x "$TC/jdk/bin/java" ]; then
  log "download JDK runtime (jdk4py wheel from PyPI)"
  WHEEL_URL="$(curl -fsSL https://pypi.org/pypi/jdk4py/json | python3 -c "
import json,sys
d=json.load(sys.stdin)
for u in d['urls']:
    if 'manylinux' in u['filename'] and 'x86_64' in u['filename']:
        print(u['url']); break
")"
  [ -n "$WHEEL_URL" ] || die "jdk4py wheel not found on PyPI"
  curl -fsSL -o "$TC/jdk4py.whl" "$WHEEL_URL"
  python3 - "$TC/jdk4py.whl" "$TC/jdk" <<'PY'
import sys, zipfile, os
whl, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(whl) as z:
    z.extractall(dest + ".tmp")
# wheel contains jdk4py/java-runtime/...
src = os.path.join(dest + ".tmp", "jdk4py", "java-runtime")
os.rename(src, dest)
import shutil; shutil.rmtree(dest + ".tmp", ignore_errors=True)
PY
  chmod -R +x "$TC/jdk/bin/"
  rm -f "$TC/jdk4py.whl"
fi

# ------------------------------------------------- 2) javac (JDK 8 tools.jar)
if [ ! -f "$TC/toolsjar/tools.jar" ]; then
  log "download javac (OpenJDK8 tools.jar via npm)"
  curl -fsSL -o "$TC/toolsjar/tools.tgz" \
    "https://registry.npmjs.org/dataslope-tools-jar/-/dataslope-tools-jar-1.0.0.tgz"
  tar xzf "$TC/toolsjar/tools.tgz" -C "$TC/toolsjar" package/tools.jar
  mv "$TC/toolsjar/package/tools.jar" "$TC/toolsjar/tools.jar"
  rm -rf "$TC/toolsjar/tools.tgz" "$TC/toolsjar/package"
fi

# ------------------------------------------------------- 3) android.jar API33
if [ ! -f "$TC/android-33.jar" ]; then
  log "download android.jar (API 33)"
  fetch_gh "Sable/android-platforms/contents/android-33/android.jar" \
    > "$TC/android-33.jar"
fi

# -------------------------------------- 4) build-tools: aapt2 d8 apksigner ...
BT="lipeedev/gendroid/contents/build-tools/33.0.2-2"
if [ ! -x "$TC/aapt2" ]; then
  log "download build-tools 33 (aapt2, d8, apksigner, zipalign)"
  fetch_gh "$BT/aapt2" > "$TC/aapt2"
  fetch_gh "$BT/zipalign" > "$TC/zipalign"
  fetch_gh "$BT/lib/d8.jar" > "$TC/lib/d8.jar"
  fetch_gh "$BT/lib/apksigner.jar" > "$TC/lib/apksigner.jar"
  fetch_gh "$BT/lib64/libc++.so" > "$TC/libc++.so"
  fetch_gh "$BT/lib64/libc++.so.1" > "$TC/libc++.so.1"
  chmod +x "$TC/aapt2" "$TC/zipalign"
fi

# ------------------------------------------------------- 5) python tooling
if [ ! -x "$TC/venv/bin/python" ]; then
  log "create python venv (numpy, scipy, pillow)"
  python3 -m venv "$TC/venv"
  "$TC/venv/bin/pip" install --quiet numpy scipy pillow
fi

log "toolchain ready:"
"$TC/jdk/bin/java" -version 2>&1 | head -1
"$TC/aapt2" version
"$TC/jdk/bin/java" -cp "$TC/lib/d8.jar" com.android.tools.r8.D8 --version
"$TC/jdk/bin/java" -jar "$TC/lib/apksigner.jar" --version
"$TC/jdk/bin/java" -cp "$TC/toolsjar/tools.jar" com.sun.tools.javac.Main -version
echo "OK -> $TC"
