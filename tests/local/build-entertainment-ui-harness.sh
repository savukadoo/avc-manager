#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

# Separate observation-only instrumentation APK. Never included in the Manager APK.
# Refuses physical devices. Plays no fixtures; host QA imports/uploads real synthetic files.
# Run up to 15 min: adb -s emulator-5558 shell am instrument -w -e durationSec 600 \
#   local.cc3.manager.mediaqa/local.cc3.manager.mediaqa.EntertainmentUiHarness
# Dump: adb -s emulator-5558 exec-out run-as local.cc3.manager cat files/mediaqa-ui.json
# Stop: adb -s emulator-5558 shell am broadcast -a local.cc3.manager.mediaqa.FINISH -p local.cc3.manager
# Optional stopPlaybackOnFinish=false leaves services for follow-up checks.
qa_java="${JAVA_HOME:-/private/tmp/cc3-build-tools/jdk/jdk-21.0.13.1+2/Contents/Home}"
qa_android="${ANDROID_JAR:-/private/tmp/cc3-build-tools/platforms/android-35/android.jar}"
qa_tools="${BUILD_TOOLS:-/private/tmp/cc3-build-tools/build-tools/android-15}"
export JAVA_HOME="$qa_java"
out="$PWD/build"
source "$PWD/tools/signing-options.sh"
qa_key="$signing_key"
qa_output="${1:-$PWD/build/qa-entertainment/CC3-Entertainment-Ui-QA.apk}"
qa_work="$(mktemp -d "${TMPDIR:-/tmp}/cc3-entertainment-ui-apk.XXXXXX")"
trap 'rm -rf "$qa_work"' EXIT
mkdir -p "$qa_work/test" "$qa_work/dex" "$(dirname "$qa_output")"
[[ -r "$qa_key" ]] || { echo "Manager signing key not found: $qa_key" >&2; exit 1; }

cat > "$qa_work/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="local.cc3.manager.mediaqa">
  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="29"/>
  <application android:label="CC3 Entertainment UI QA" android:debuggable="true" android:allowBackup="false"/>
  <instrumentation android:name="local.cc3.manager.mediaqa.EntertainmentUiHarness" android:targetPackage="local.cc3.manager"
    android:label="Emulator-only Entertainment UI observation" android:functionalTest="true" android:handleProfiling="false"/>
</manifest>
MANIFEST

"$qa_java/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -classpath "$qa_android" \
  -d "$qa_work/test" tests/android/EntertainmentUiHarness.java
"$qa_java/bin/jar" cf "$qa_work/harness.jar" -C "$qa_work/test" .
"$qa_tools/d8" --lib "$qa_android" --min-api 29 --output "$qa_work/dex" "$qa_work/harness.jar"

# Assert that the QA DEX defines only harness classes, with no production copies.
python3 - "$qa_work/dex/classes.dex" <<'PY'
import struct, sys
data = open(sys.argv[1], 'rb').read()
word = lambda offset: struct.unpack_from('<I', data, offset)[0]
strings = []
for index in range(word(56)):
    pos = word(word(60) + index * 4)
    while data[pos] & 128:
        pos += 1
    pos += 1
    strings.append(data[pos:data.index(b'\0', pos)].decode('utf-8', 'replace'))
types = [strings[word(word(68) + index * 4)] for index in range(word(64))]
defined = [types[word(word(100) + index * 32)] for index in range(word(96))]
assert defined and all(name.startswith('Llocal/cc3/manager/mediaqa/EntertainmentUiHarness') for name in defined), defined
print('QA-only DEX verified: ' + str(len(defined)) + ' observer classes; no production classes')
PY

"$qa_tools/aapt2" link -I "$qa_android" --manifest "$qa_work/AndroidManifest.xml" \
  --version-code 1 --version-name 1.0-qa -o "$qa_work/base.apk"
(cd "$qa_work/dex" && /usr/bin/zip -q -0 "$qa_work/base.apk" classes.dex)
"$qa_tools/zipalign" -f 4 "$qa_work/base.apk" "$qa_work/aligned.apk"
"$qa_tools/apksigner" sign --ks "$qa_key" --ks-key-alias "$signing_alias" \
  "${signing_password[@]}" --out "$qa_output" "$qa_work/aligned.apk"
"$qa_tools/apksigner" verify --min-sdk-version 29 --verbose "$qa_output"
printf '\nQA APK: %s\n' "$qa_output"
printf '%s\n' 'Install ONLY on emulator. Launch with: adb -s emulator-5558 shell am instrument -w -e durationSec 600 local.cc3.manager.mediaqa/local.cc3.manager.mediaqa.EntertainmentUiHarness'
