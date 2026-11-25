# AVC Manager 0.5.1 security hardening

This guide describes the security changes introduced in version 0.5.1 and the controls available to maintainers and device owners.

## Implemented changes

| Review item | Result |
| --- | --- |
| Wireless debugging exposure | A foreground lease, monotonic deadline, expiry alarm and Wi-Fi callbacks limit explicit access to 15 minutes/the approved connection. The app checks for the ADB authentication challenge and displays selected state/countdown. A reboot never enables debugging. An app update restores only an existing unexpired lease. |
| Plain HTTP uploads | The production listener requires HTTPS. A non-exportable AndroidKeyStore P-256 key supplies the per-device identity. TLS 1.2/1.3 and forward-secret AEAD suites are enabled; session cookies are Secure/HttpOnly/SameSite. The app shows a full certificate fingerprint, with a pinned laptop client for verification before any request is sent. |
| Slow unauthenticated requests | TLS, headers and pairing have an absolute three-second deadline, per-peer pending limits and separate capacity for paired peer addresses. Per-request authentication/CSRF and bounded streaming remain enforced. |
| Firewall lifecycle | Optional **Keep protection running** restores rule enforcement after process death, reboot, package replacement and reopening after force-stop. Worker retries retain the old TUN until replacement. Capture is not automatically resumed. Deliberate Stop clears the saved enforcement intent. |
| Unknown app identity | An explicit, initially disabled **Block unidentified public traffic** policy covers unattributed/shared/system app groups on routed public destinations. Changing it updates the rule revision and existing flows. A confirmation explains possible disruption. |
| Native hardening | Both libraries use stack-protector-strong, FORTIFY and existing RELRO/NOW/non-executable-stack protections. Native initialization leaks/dead stores and bounded/reentrant Linux example helpers were cleaned up. |
| Orphaned tests | The 16 receive-buffer checks moved into the core test source set and standard runner. The obsolete DPF/regen driver is clearly retired. |
| Signing | The original certificate is retained in an externally stored, privately protected keystore. Release signing fails for missing/in-project/permissive files. A generated password is read from a private file, not process arguments. Debug signing is a separate explicit mode. |
| Code organization | Vehicle dashboard composition, profile persistence/migration and CSV generation have separate boundaries. The report writer uses its supplied profile, preserves negative values, escapes CSV and neutralizes spreadsheet expressions. Debugging lifecycle moved out of the Activity. |
| Quality gate | Modern Bluetooth value APIs are version gated; deliberate Android 10 compatibility calls have narrow annotations. Java builds reject new warnings. Bounded diagnostics omit exception messages, addresses, tokens and user data. Shared dialogs are tracked/dismissed with their Activity. |
| Supply chain | Immutable GitHub Action commits, pinned scanner/tool versions, a reviewed vendored source lock and generated CycloneDX software inventory are included. Native source and licence notices remain present. |

## Owner controls

**Wireless → Enable for 15 minutes** starts a temporary session after confirmation. It never makes debugging permanent. The selected button and remaining time show managed access; **Turn off Wi-Fi debugging** ends it early. Older/unmanaged debugging is identified separately so it can be replaced by a timed session. Approve only a recognised computer's RSA prompt.

**Entertainment → Upload → Start uploads** shows an HTTPS address and a fresh pairing code. **Verify secure connection** shows the certificate SHA-256 fingerprint. Compare it in browser certificate details before trusting the connection or entering the pairing code. A changed IP may produce a new certificate. The alternative Python client verifies the pin before sending credentials or media:

```sh
python3 tools/cc3-upload.py --url https://192.0.2.10:8765 --fingerprint 'FULL SHA-256 FROM CC3' music.mp3
```

The address above is an example; read the current one on the device. The pairing code is entered at a private prompt. The CLI has no external dependency beyond Python 3's standard library.

**Firewall → Protection** contains persistence and the unidentified-traffic policy. They default off. The heading's small info icon explains scope and consequences. **Android always-on / lockdown** opens the system-controlled VPN security settings, which an ordinary app cannot silently set. Do not enable lockdown without checking local access and the firmware's behaviour. Existing local/private route bypasses are retained.
## Verification

Run `bash tools/verify.sh` with the Android toolchain configured as described in the [build guide](README.md#build). The workflow runs the host tests, signed APK checks and native parser checks.

For a keystore containing several signing keys, set `CC3_SIGNING_ALIAS` to the alias of the existing release certificate. Builds with a single key select it automatically.
