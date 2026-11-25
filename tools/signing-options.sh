#!/usr/bin/env bash
# Sourced by APK/QA builders; password values never enter command arguments.
# A release key must already exist. Debug signing is an explicit separate mode.
signing_mode="${CC3_BUILD_MODE:-release}"
if [[ "$signing_mode" == debug ]]; then
  mkdir -p "$out"
  signing_key="$out/development-only.keystore"
  if [[ ! -f "$signing_key" ]]; then
    (umask 077; "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$signing_key" \
      -storepass android -keypass android -alias "${CC3_SIGNING_ALIAS:-avc-manager}" -keyalg RSA -keysize 3072 \
      -validity 3650 -dname "CN=AVC Manager Development Only")
  fi
  signing_options=( --ks "$signing_key" --ks-pass pass:android --key-pass pass:android )
else
  [[ "$signing_mode" == release ]] || { echo "Unknown CC3_BUILD_MODE" >&2; exit 1; }
  : "${CC3_SIGNING_KEY:?Set an existing release key outside the checkout}"
  : "${CC3_SIGNING_PASSWORD_FILE:?Set a private password file outside the checkout}"
  signing_key="$CC3_SIGNING_KEY"
  [[ -f "$signing_key" && -f "$CC3_SIGNING_PASSWORD_FILE" ]] || { echo "Release signing files are missing; no key was generated." >&2; exit 1; }
  python3 tools/check-signing-inputs.py "$signing_key" "$CC3_SIGNING_PASSWORD_FILE"
  signing_options=( --ks "$signing_key" --ks-pass "file:$CC3_SIGNING_PASSWORD_FILE" )
fi

# With one signing key, apksigner selects it independently of its alias.
if [[ -n "${CC3_SIGNING_ALIAS:-}" ]]; then
  signing_options+=( --ks-key-alias "$CC3_SIGNING_ALIAS" )
fi
