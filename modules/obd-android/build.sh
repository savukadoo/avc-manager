#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK}"
: "${ANDROID_JAR:?Set ANDROID_JAR to the Android SDK android.jar}"
android_out="${1:-$PWD/build/obd-android.jar}"
android_work="$(mktemp -d "${TMPDIR:-/tmp}/cc3-obd-android.XXXXXX")"
trap 'rm -rf "$android_work"' EXIT
mkdir -p "$(dirname "$android_out")" "$android_work/classes"
bash modules/obd-core/build.sh "$android_work/obd-core.jar"
android_sources=()
while IFS= read -r -d '' file; do android_sources+=("$file"); done < <(find modules/obd-android/src/main/java -name '*.java' -print0)
"$JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR:$android_work/obd-core.jar" -d "$android_work/classes" "${android_sources[@]}"
mkdir -p "$android_work/classes/META-INF"
cp LICENSE "$android_work/classes/META-INF/LICENSE"
cp modules/obd-core/NOTICE "$android_work/classes/META-INF/NOTICE"
"$JAVA_HOME/bin/jar" cf "$android_out" -C "$android_work/classes" .
printf '%s\n' "$android_out"
