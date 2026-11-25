#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME}"
security_work="$(mktemp -d "${TMPDIR:-/tmp}/cc3-security-tests.XXXXXX")"
trap 'rm -rf "$security_work"' EXIT
sources=()
while IFS= read -r -d '' file; do sources+=("$file"); done < <(find modules/obd-core/src/main/java -name '*.java' -print0)
for name in DebugLease FirewallRestartPolicy VehicleReport TlsCertificate; do sources+=("app/src/main/java/local/cc3/manager/$name.java"); done
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all,-options -Werror -d "$security_work" "${sources[@]}" tests/java/SecurityPolicyTest.java
"$JAVA_HOME/bin/java" -ea -cp "$security_work" local.cc3.manager.SecurityPolicyTest
python3 tests/local/test-media-upload.py
python3 tests/local/test-secure-upload.py
python3 tests/local/test-signing-inputs.py
