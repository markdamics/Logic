#!/usr/bin/env bash
# Builds the Logic desktop app: frontend -> backend jar (frontend embedded,
# same as the Docker image) -> trimmed jlink runtime -> Tauri AppImage/deb.
#
# Requires: node/npm, mvn, a JDK with jlink+jdeps and its jmods (on openSUSE:
# `sudo zypper install java-25-openjdk-jmods`), rust/cargo.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESKTOP="$ROOT/desktop"
BACKEND="$ROOT/backend"
FRONTEND="$ROOT/frontend"
RESOURCES="$DESKTOP/src-tauri/resources"

echo "==> Building frontend"
(cd "$FRONTEND" && npm ci && npm run build)

echo "==> Embedding frontend into backend static resources"
STATIC_DIR="$BACKEND/src/main/resources/static"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -r "$FRONTEND"/dist/* "$STATIC_DIR"/
cleanup() { rm -rf "$STATIC_DIR"; }
trap cleanup EXIT

echo "==> Building backend jar"
(cd "$BACKEND" && mvn -B -q -DskipTests package)
JAR="$(ls "$BACKEND"/target/log-analyzer-backend-*.jar | grep -v original)"
echo "    jar: $JAR"

echo "==> Computing minimal module set with jdeps"
EXTRACT_DIR="$(mktemp -d)"
(cd "$EXTRACT_DIR" && jar xf "$JAR")
LIB_JARS=$(find "$EXTRACT_DIR/BOOT-INF/lib" -name '*.jar' ! -name 'jakarta.transaction-api-*.jar')
CP=$(echo "$LIB_JARS" | tr '\n' ':')
MODULES=$(jdeps --multi-release 25 --ignore-missing-deps --print-module-deps \
  --class-path "$CP" \
  "$EXTRACT_DIR/BOOT-INF/classes" $LIB_JARS 2>/dev/null | tail -1)
# jdk.crypto.ec isn't picked up by static analysis (it's loaded via
# ServiceLoader for TLS elliptic-curve support) but is needed for outbound
# HTTPS, e.g. APM/tracing links and any TLS-based log source connectivity.
MODULES="${MODULES},jdk.crypto.ec"
echo "    modules: $MODULES"
rm -rf "$EXTRACT_DIR"

echo "==> Building trimmed JRE with jlink"
rm -rf "$RESOURCES/jre"
jlink --add-modules "$MODULES" \
  --output "$RESOURCES/jre" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6
# jlink preserves the JDK's read-only (0444) license files. Tauri's resource
# copy step can't overwrite a read-only destination on a rebuild, so make
# everything owner-writable - the writable bit then carries through to the
# copies Tauri makes, keeping later rebuilds unblocked too.
chmod -R u+w "$RESOURCES/jre"

echo "==> Copying jar into Tauri resources"
mkdir -p "$RESOURCES"
cp "$JAR" "$RESOURCES/app.jar"

echo "==> Building Tauri app (AppImage + deb)"
APPDIR="$DESKTOP/src-tauri/target/release/bundle/appimage/Logic.AppDir"
# APPIMAGE_EXTRACT_AND_RUN: linuxdeploy/appimagetool are themselves shipped
# as AppImages that normally self-mount via FUSE - this makes them extract
# to a temp dir and run instead, which also works in sandboxes/containers
# without a working FUSE mount.
# LD_LIBRARY_PATH: OpenJDK's libjava.so has RPATH=$ORIGIN but its libjvm.so
# dependency actually lives in a sibling server/ dir (loaded via dlopen at
# real JVM startup, not the normal linker path) - without this, linuxdeploy's
# static ELF dependency walker can't find it and aborts the whole bundle.
(cd "$DESKTOP" && \
  APPIMAGE_EXTRACT_AND_RUN=1 \
  LD_LIBRARY_PATH="$APPDIR/usr/lib/Logic/jre/lib/server:$APPDIR/usr/lib/Logic/jre/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  npx tauri build)

echo "==> Done. Bundles in desktop/src-tauri/target/release/bundle/"
