#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK}"
test_out="$(mktemp -d "${TMPDIR:-/tmp}/cc3-obd-tests.XXXXXX")"
trap 'rm -rf "$test_out"' EXIT
core="modules/obd-core/src/main/java"
tests="modules/obd-core/src/test/java"
sources=()
while IFS= read -r -d '' file; do sources+=("$file"); done < <(find "$core" "$tests" -name '*.java' -print0)
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all,-options -encoding UTF-8 -d "$test_out" "${sources[@]}"
"$JAVA_HOME/bin/java" -ea -cp "$test_out" local.cc3.obd.ObdEngineTest
"$JAVA_HOME/bin/java" -ea -cp "$test_out" local.cc3.obd.ObdHistoryTest
if [[ -f "$tests/local/cc3/obd/ObdProfilesTest.java" ]]; then
  "$JAVA_HOME/bin/java" -ea -cp "$test_out" local.cc3.obd.ObdProfilesTest
fi

"$JAVA_HOME/bin/java" -ea -cp "$test_out" local.cc3.obd.ObdTransportBufferTest
