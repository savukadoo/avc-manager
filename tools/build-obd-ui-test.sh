#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Builds a separate, emulator-only instrumentation APK. Nothing is added to app/src/main.
# Runner arguments: durationSec=120 (10..600), faults=true, pauseAfterSec=15,
# staleAfterSec=30 (since fake open), stalePid=all|0105, profile=full|limited|none.
# faultProfile=partial|mixed-empty|partial-empty|pending-final|no-response.
# historyFixture=none|full|gaps seeds synthetic coolant timestamps for graph layout QA only.
# NO DATA retains real sample age and shows No response; the normal engine may
# rediscover an unresponsive ECU after 15s. Use stalePid=0105 to keep other sensors live.
# Leaving Vehicle closes the fake link; restart instrumentation to reconnect it.
# QA view tree: files/obd-qa-ui.json, periodic dumpEveryMs=500 (0 disables), or
# adb shell am broadcast -a local.cc3.manager.obdqa.DUMP -p local.cc3.manager
# Active tracked Vehicle/Weather dialogs replace the background activity tree.
# Finish a long runner after QA/SAF completes: adb shell am broadcast
# -a local.cc3.manager.obdqa.FINISH -p local.cc3.manager
qa_java="${JAVA_HOME:-/private/tmp/cc3-build-tools/jdk/jdk-21.0.13.1+2/Contents/Home}"
qa_android="${ANDROID_JAR:-/private/tmp/cc3-build-tools/platforms/android-35/android.jar}"
qa_tools="${BUILD_TOOLS:-/private/tmp/cc3-build-tools/build-tools/android-15}"
export JAVA_HOME="$qa_java"
out="$PWD/build"
source "$PWD/tools/signing-options.sh"
qa_key="$signing_key"
qa_output="${1:-$PWD/build/qa-obd/CC3-Obd-Ui-QA.apk}"
qa_work="$(mktemp -d "${TMPDIR:-/tmp}/cc3-obd-ui-apk.XXXXXX")"
trap 'rm -rf "$qa_work"' EXIT
mkdir -p "$qa_work/interface" "$qa_work/test" "$qa_work/dex" "$(dirname "$qa_output")"
[[ -r "$qa_key" ]] || { echo "Manager signing key not found: $qa_key" >&2; exit 1; }

cat > "$qa_work/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="local.cc3.manager.obdqa">
  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="29"/>
  <application android:label="CC3 OBD UI QA — SIMULATED" android:debuggable="true" android:allowBackup="false"/>
  <instrumentation android:name="local.cc3.manager.obdqa.ObdUiHarness" android:targetPackage="local.cc3.manager"
    android:label="Emulator-only simulated OBD QA" android:functionalTest="true" android:handleProfiling="false"/>
</manifest>
MANIFEST

# ObdLink is compile-only. It must be resolved from the target app at runtime.
"$qa_java/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -d "$qa_work/interface" \
  modules/obd-core/src/main/java/local/cc3/obd/ObdLink.java
"$qa_java/bin/jar" cf "$qa_work/interface.jar" -C "$qa_work/interface" .
"$qa_java/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 \
  -classpath "$qa_android:$qa_work/interface.jar" -d "$qa_work/test" tests/android/ObdUiHarness.java
"$qa_java/bin/jar" cf "$qa_work/harness.jar" -C "$qa_work/test" .
"$qa_tools/d8" --lib "$qa_android" --classpath "$qa_work/interface.jar" --min-api 29 \
  --output "$qa_work/dex" "$qa_work/harness.jar"

# Assert the test DEX defines only harness classes; no duplicated production interface/engine.
python3 - "$qa_work/dex/classes.dex" <<'PY'
import struct, sys
data = open(sys.argv[1], 'rb').read()
word = lambda offset: struct.unpack_from('<I', data, offset)[0]
strings_size, strings_off = word(56), word(60)
types_size, types_off = word(64), word(68)
classes_size, classes_off = word(96), word(100)
strings = []
for index in range(strings_size):
    pos = word(strings_off + index * 4)
    while data[pos] & 128:
        pos += 1
    pos += 1
    strings.append(data[pos:data.index(b'\0', pos)].decode('utf-8', 'replace'))
types = [strings[word(types_off + index * 4)] for index in range(types_size)]
defined = [types[word(classes_off + index * 32)] for index in range(classes_size)]
assert defined, 'No instrumentation classes in test DEX'
assert all(name.startswith('Llocal/cc3/manager/obdqa/ObdUiHarness') for name in defined), defined
assert 'Llocal/cc3/manager/ObdLink;' not in defined, 'Compile-only ObdLink leaked into test DEX'
print('QA-only DEX verified: ' + str(len(defined)) + ' harness classes; no production classes')
PY

"$qa_tools/aapt2" link -I "$qa_android" --manifest "$qa_work/AndroidManifest.xml" \
  --version-code 1 --version-name 1.0-qa -o "$qa_work/base.apk"
(cd "$qa_work/dex" && /usr/bin/zip -q -0 "$qa_work/base.apk" classes.dex)
"$qa_tools/zipalign" -f 4 "$qa_work/base.apk" "$qa_work/aligned.apk"
"$qa_tools/apksigner" sign --ks "$qa_key" --ks-key-alias "$signing_alias" \
  "${signing_password[@]}" --out "$qa_output" "$qa_work/aligned.apk"
"$qa_tools/apksigner" verify --min-sdk-version 29 --verbose "$qa_output"
printf '\nQA APK: %s\n' "$qa_output"
printf '%s\n' 'Install ONLY on emulator. Run: adb -s emulator-5558 shell am instrument -w -e durationSec 120 local.cc3.manager.obdqa/local.cc3.manager.obdqa.ObdUiHarness'
