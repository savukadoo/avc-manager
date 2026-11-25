#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?Set ANDROID_JAR to the Android SDK platform android.jar}"
: "${BUILD_TOOLS:?Set BUILD_TOOLS to the Android SDK build-tools directory}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK (21 used for the local build)}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an Android NDK (r27d used for the local build)}"
export JAVA_HOME
out="$PWD/build"
source "$PWD/tools/signing-options.sh"
work="$(mktemp -d "$out-work.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out" "$work/classes" "$work/dex" "$work/gen"
ndk_compiler=( "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android29-clang )
mkdir -p "$work/native/lib/arm64-v8a"
native="$work/native/lib/arm64-v8a"
vendor=third_party/zdtun
"${ndk_compiler[0]}" -shared -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -DANDROID -DNO_DEBUG -D_LITTLE_ENDIAN \
  -Wl,-z,relro,-z,now,-z,noexecstack -Wl,-soname,libzdtun.so -Wl,-z,max-page-size=16384 -o "$native/libzdtun.so" \
  "$vendor/zdtun.c" "$vendor/utils.c"
"${ndk_compiler[0]}" -shared -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -DNO_DEBUG -I "$vendor" -L "$native" \
  -Wl,-z,relro,-z,now,-z,noexecstack -Wl,-soname,libcc3firewall.so -Wl,-z,max-page-size=16384 \
  -o "$native/libcc3firewall.so" app/src/main/c/firewall.c -lzdtun
mkdir -p "$work/legal-assets/licenses"
cp LICENSE NOTICE LICENSES/*.txt "$work/legal-assets/licenses/"
sed -n '1,/^\*\//p' "$vendor/third_party/uthash.h" > "$work/legal-assets/licenses/uthash-BSD-1-Clause.txt"
"$BUILD_TOOLS/aapt2" compile --dir app/src/main/res -o "$work/resources.zip"
"$BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest app/src/main/AndroidManifest.xml \
  --java "$work/gen" --version-code 61 --version-name 0.5.18 \
  -A app/src/main/assets -A "$work/legal-assets" -0 bin -o "$work/base.apk" "$work/resources.zip"
sources=()
while IFS= read -r -d '' file; do sources+=("$file"); done < <(find app/src/main/java modules/obd-core/src/main/java modules/obd-android/src/main/java "$work/gen" -name '*.java' -print0)
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all,-options -Werror -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$work/classes" "${sources[@]}"
"$JAVA_HOME/bin/jar" cf "$work/classes.jar" -C "$work/classes" .
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 29 --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && /usr/bin/zip -q -0 "$work/base.apk" classes.dex)
(cd "$work/native" && /usr/bin/zip -qr "$work/base.apk" lib)
"$BUILD_TOOLS/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
"$BUILD_TOOLS/apksigner" sign "${signing_options[@]}" --out "$out/AVC-Manager.apk" "$work/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --min-sdk-version 29 --verbose "$out/AVC-Manager.apk"
printf '\n%s\n' "$out/AVC-Manager.apk"
