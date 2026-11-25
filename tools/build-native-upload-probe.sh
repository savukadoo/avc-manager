#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${ANDROID_JAR:?Set ANDROID_JAR}"
: "${BUILD_TOOLS:?Set BUILD_TOOLS}"
probe_out="${1:-$PWD/build/qa-0.5.1/native-upload-probe.jar}"
case "$probe_out" in /*) ;; *) probe_out="$PWD/$probe_out" ;; esac
probe_work="$(mktemp -d "${TMPDIR:-/tmp}/cc3-native-tls-probe.XXXXXX")"
trap 'rm -rf "$probe_work"' EXIT
mkdir -p "$probe_work/classes" "$probe_work/dex" "$(dirname "$probe_out")"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all,-options -Werror -classpath "$ANDROID_JAR" \
    -d "$probe_work/classes" tests/android/cc3/qa/NativeUploadProbe.java
"$JAVA_HOME/bin/jar" cf "$probe_work/classes.jar" -C "$probe_work/classes" .
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 29 --output "$probe_work/dex" "$probe_work/classes.jar"
(cd "$probe_work/dex" && zip -q "$probe_out" classes.dex)
