#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK9 or later (tested with21)}"
core_out="${1:-$PWD/build/obd-core.jar}"
core_work="$(mktemp -d "${TMPDIR:-/tmp}/cc3-obd-core.XXXXXX")"
trap 'rm -rf "$core_work"' EXIT
mkdir -p "$(dirname "$core_out")"
core_sources=()
while IFS= read -r -d '' file; do core_sources+=("$file"); done < <(find modules/obd-core/src/main/java -name '*.java' -print0)
"$JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 -d "$core_work" "${core_sources[@]}"
mkdir -p "$core_work/META-INF"
cp LICENSE "$core_work/META-INF/LICENSE"
cp modules/obd-core/NOTICE "$core_work/META-INF/NOTICE"
"$JAVA_HOME/bin/jar" cf "$core_out" -C "$core_work" .
printf '%s\n' "$core_out"
