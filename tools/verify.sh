#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/check-branding.py
bash tools/test-obd-engine.sh
bash tools/test-security.sh
bash tools/test-weather-timing.sh
python3 tools/lint-java.py
bash tools/build-native-upload-probe.sh
python3 tools/sbom.py
bash tools/check-native.sh
python3 -m compileall -q tools tests/local
for script in build.sh tools/*.sh tests/local/*.sh; do bash -n "$script"; done
: "${SEMGREP_BIN:=semgrep}"
"$SEMGREP_BIN" scan --config .semgrep.yml --metrics off --disable-version-check --no-git-ignore --error app modules tools third_party
bash build.sh
python3 tools/check-apk-security.py
