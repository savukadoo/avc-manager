#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME}"
weather_work="$(mktemp -d "${TMPDIR:-/tmp}/avc-weather-timing.XXXXXX")"
trap 'rm -rf "$weather_work"' EXIT
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all,-options -Werror -d "$weather_work" app/src/main/java/local/cc3/manager/WeatherRefreshPolicy.java tests/java/WeatherRefreshPolicyTest.java app/src/main/java/local/cc3/manager/WeatherMapPolicy.java tests/java/WeatherMapPolicyTest.java
"$JAVA_HOME/bin/java" -ea -cp "$weather_work" local.cc3.manager.WeatherRefreshPolicyTest
"$JAVA_HOME/bin/java" -ea -cp "$weather_work" local.cc3.manager.WeatherMapPolicyTest
