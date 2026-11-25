#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
native_compiler=( "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android29-clang )
native_work="$(mktemp -d "${TMPDIR:-/tmp}/cc3-native-analysis.XXXXXX")"
trap 'rm -rf "$native_work"' EXIT
for file in app/src/main/c/firewall.c third_party/zdtun/zdtun.c third_party/zdtun/utils.c; do
  "${native_compiler[0]}" --analyze -Werror -O2 -DANDROID -DNO_DEBUG -D_LITTLE_ENDIAN -I third_party/zdtun \
    -Xanalyzer -analyzer-output=plist -o "$native_work/$(basename "$file").plist" "$file"
done
python3 - "$native_work" <<'PY'
from pathlib import Path
import plistlib,sys
diagnostics=sum(len(plistlib.loads(p.read_bytes())['diagnostics']) for p in Path(sys.argv[1]).glob('*.plist'))
assert diagnostics==0, str(diagnostics)+' native diagnostics require review'
print('Three Android native translation units: no static-analysis diagnostics')
PY
