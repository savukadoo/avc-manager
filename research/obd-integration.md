# CC3 Manager OBD integration proposal

Research and native-code inspection: 21 November 2026. This is a design proposal, not a claim that the Veepeak adapter has connected to this CC3 or that vehicle data has been read.

## Recommendation and feasibility

Add a **Vehicle** item immediately below **Controls** in the right navigation rail. Its page heading can be **OBD diagnostics**. Build a local, read-only monitoring session first, with configurable dashboard tiles, supported-sensor discovery and diagnostic-code viewing. Keep adapter connection controls separate from vehicle-changing commands.

The pictured Veepeak OBDCheck BLE+ should be treated as a dual-transport device. The manufacturer lists Bluetooth LE and Classic; its current Android setup instructions recommend LE when supported by the selected app, whereas earlier instructions use Classic pairing with PIN 1234. Do not assume that “BLE” in its name makes it iOS-only, and do not relabel it as a different BLE+2 product. Veepeak explicitly cautions that head-unit Bluetooth profile support varies. [Veepeak product](https://veepeak.com/products/obdcheck-ble-plus), [current Android guide](https://veepeak.com/pages/how-to-connect-car-scanner-to-obdcheck-ble-and-obdcheck-ble-on-android), [head-unit support guidance](https://veepeak.com/pages/obdcheck-ble-plus)

A bounded, read-only check of the physical CC3 succeeded after reconnecting its ADB transport. It advertised `android.hardware.bluetooth` and `android.hardware.bluetooth_le`; `bluetooth_manager` existed and reported enabled; `bluetooth_on` was `1`; both the FYT Bluetooth app and Android Bluetooth package were installed. No scan, pairing, adapter connection or OBD command was performed. These observations make standard Android Bluetooth integration plausible; they do not prove that an application can use this particular adapter on this firmware.

**Next feasibility gate:** an app-level connection test with the actual plugged-in adapter, followed by a valid ELM prompt/identity response and generic supported-PID reply. Prefer a working BLE route, retain Classic as fallback, and report which transport actually succeeded. Avoid promising a proprietary FYT Bluetooth workaround before one is needed and verified.

## Fit with the current native app

The project is Java/native Android Views, min/target API 29, built with `aapt2`, `javac`, D8 and the local signing key. It already has shared palettes, buttons, dialogs and a persistent navigation rail. It does not need Compose, a web dashboard or a cloud service for this feature.

Relevant existing files:

- `MainActivity.java`: section routing, the five current rail entries and activity lifecycle.
- `Screen.java`: palettes, text, touch targets, buttons, dialogs and shared preferences.
- `FytModule.java`: binder access to FYT services currently used for sound/display. This is not an existing generic OBD transport.
- `AndroidManifest.xml`: currently has no Bluetooth or location permissions and no OBD service.
- `tools/cc3-display-profile.json`: actual 1280 × 720 display, 160 dpi, 1.5× text, top system bar 60 px, app bounds `(0,60)-(1280,720)`.

A sixth 76 dp rail entry can fit this unit’s 660 px application height. Its implementation should extend the existing section validation, saved-section state, routing and navigation button array; it should not be hidden inside Appearance. Use the same visual components as the revised Manager.

Suggested classes:

| Component | Responsibility |
| --- | --- |
| `ObdPage` | Vehicle page, connection status, Dashboard/Sensors/Codes/Connection views |
| `ObdSession` | Connection state machine, initialization and cancellation |
| `ObdTransport` | Shared `connect`, byte-stream send/receive and `close` contract |
| `BleObdTransport` | GATT discovery, validated serial characteristics, notifications and queued writes |
| `ClassicObdTransport` | RFCOMM/SDP connection, input/output streams and worker thread |
| `ElmProtocol` | Prompt framing, bounded response parsing, echo/header/multiline handling and command allowlist |
| `PidRegistry` | Supported sensor definitions, units, formulas and per-ECU support masks |
| `PollScheduler` | One outstanding command, priorities, deadlines and bounded retry/backoff |
| `ObdRepository` | Timestamped values with fresh/stale/unsupported/error quality states |
| `ObdDashboardView` | Shared-theme six-tile native gauge/number/trend renderer |
| `ObdLogStore` | Optional private, size-limited history and user-initiated export |

Keep transport, parsing and polling outside the activity. The existing FYT IPC helper should not be repurposed into an undocumented raw CAN writer.

## Bluetooth and permissions

For API 29, add normal `BLUETOOTH` permission for communication. Device discovery also needs `BLUETOOTH_ADMIN`, and foreground scans require runtime `ACCESS_FINE_LOCATION`. Start discovery only from the connection page after a user action; explain that this permission enables nearby-device discovery and that the app is not collecting GPS tracks. Existing paired Classic-device selection can be offered before scanning. Do not request background-location access for the proposed foreground-only discovery workflow. `CompanionDeviceManager` is an alternative user-mediated association path worth evaluating if its flow works on the CC3. [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [Android 10 Bluetooth privacy changes](https://developer.android.com/about/versions/10/privacy/changes), [device discovery](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices)

Declare Bluetooth features as optional so Manager still installs and its other controls work on units without a usable radio. Check `BluetoothManager.getAdapter()`, adapter enabled state and available transports at runtime. A feature flag alone is insufficient. If unavailable, show a precise connection explanation rather than dummy readings. [Android setup](https://developer.android.com/develop/connectivity/bluetooth/setup)

Classic should use the adapter’s advertised serial service via SDP/RFCOMM, not an arbitrary generated UUID or hidden reflection channel shortcut. Connect/read on a worker thread and close the socket to cancel blocked work. [Android RFCOMM connection model](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)

BLE needs service discovery and the correct adapter UART write/notification characteristics. No authoritative Veepeak UART UUID specification was located during this pass. Do not assume every ELM adapter uses the same `FFE0`/`FFF0`/Nordic profile, or write to every writable characteristic. Discover metadata, validate the actual service profile, and use a known-good mapping. Serialize GATT operations and complete them from callbacks, including descriptor notification setup; fit writes to the negotiated MTU. Use the API-29-compatible characteristic API, with a future modern-API branch if the target is later raised. [GATT connection lifecycle](https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server), [service discovery and notifications](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data), [BluetoothGatt API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)

An OBD feature does not require changing the app’s target SDK during this work. A future Android 12+ target update would need a separate Nearby Devices permission implementation.

## Session, discovery and polling

Use explicit states: **Disconnected → Connecting → Adapter ready → Finding ECU → Discovering sensors → Monitoring**, plus **Paused**, **Reconnecting** and a named error. “Paired” must not imply that the ECU is responding.

After the byte channel is validated, an ELM-style session can identify the adapter, configure response formatting, choose automatic protocol detection and request supported generic sensors. Handle CR/LF variation, echoed commands, `SEARCHING`, `NO DATA`, stopped/error responses, multiple responding ECUs and prompt completion. Bound buffers and deadlines so a truncated response never hangs the interface. The ELM documentation describes automatic protocol selection and the distinction between adapter commands and OBD requests. [ELM327 data sheet](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf)

For supported-PID discovery, follow generic Mode 01 support masks (`0100`, then successive support pages only when indicated). Keep support per ECU response source. A supported bit should still be confirmed by a valid real reply before offering that metric as a reliable gauge. Unknown or unsupported values are unavailable, not zero.

The initial sensor set and Toyota-specific caveats are covered in [obd-monitoring.md](obd-monitoring.md). Do not import the complete OBDb Fortuner database or bulk-poll debug/custom commands: the sibling review found debug entries and questionable year/powertrain filters. Enhanced Toyota definitions need a hand-validated engine/ECU-specific profile and licence review.

**Proposed starting policy, to be measured on the car:** cap the entire session around five queries per second, prioritize the visible dashboard, and poll slow temperatures less often than RPM. For example, RPM 2 Hz, speed 1 Hz, manifold pressure 1 Hz, load 0.5 Hz and temperatures/voltage around 0.2 Hz, subject to response latency. These are design budgets, not a promised adapter speed. Maintain one in-flight query and reduce the schedule when responses are slow. Retry timeouts a small number of times, then mark the stream stale and back off.

A display refresh must not be presented as a new measurement. Timestamp every value, expose its age, and stop presenting a frozen number as live. Derived values such as boost or estimated fuel use must be labelled as calculated and only appear when their inputs and vehicle-specific assumptions are valid.

## Dashboard and configuration

The main Vehicle page should show a compact connection/status row and a fixed **three-column by two-row** dashboard at this display profile. Six large cards fit better than a scrolling list while monitoring. Each card has a clear metric name, current value, unit and stale/paused indicator. Dials and a short local trend can be alternative visual styles; do not cram multiple gauges into one small card.

Recommended configurable elements:

- Four or six visible tiles; metric and number/dial/trend style per tile.
- Units, metric order, sensor-specific thresholds and optional local alert cues.
- A supported-sensor picker driven by actual discovery, with an explanation for unavailable sensors.
- Saved user profiles such as Everyday, Diesel temperatures and Diagnostics, assembled only from readings this vehicle actually supplies.
- Separate connection details: selected adapter, successful transport, detected OBD protocol, last response and measured query rate.

A sensible initial generic dashboard is RPM, speed, coolant temperature, engine load, manifold pressure and voltage, provided each is supported. Keep adapter supply voltage distinct from an ECU-reported voltage. DPF, transmission and other enhanced Toyota values remain optional until independently validated.

An optional demo preview may illustrate layout choices, but it must be explicitly labelled **Demo** and must never be substituted for a failed vehicle connection. Actual monitoring should start with empty/unavailable cards until real data arrives.

## What the user can control

| Adapter/session controls in version 1 | Behaviour |
| --- | --- |
| Select adapter/transport | Choose the intended device and validated BLE or Classic route |
| Connect / Disconnect | Open or close the transport; disconnect does not remove power from the OBD socket |
| Pause / Resume readings | Stop or resume the read-only polling queue |
| Reconnect | Re-establish the session without changing ECU settings |
| Refresh supported sensors | Rerun supported-PID discovery |
| Configure dashboard | Change displayed metrics, units, layout and alerts locally |
| Start / Stop recording | Explicitly enable or stop bounded local history |
| View / Export trouble codes | Read and export supported code/status information |

Do not present code clearing, DPF regeneration, injector tests, adaptations, EPB/service resets, immobilizer/key operations or ECU flashing as supported controls. They are outside the proposed read-only version; Veepeak itself excludes maintenance/relearning functions from its product scope. [Veepeak capabilities](https://veepeak.com/products/obdcheck-ble-plus)

“Read-only” describes the vehicle diagnostic services allowed by the app. Sending a query still requires writing bytes to the Bluetooth channel; that is not the same as an ECU-changing diagnostic operation. Enforce an explicit request allowlist in code, not only by hiding dangerous buttons. No raw command terminal in version 1.

## Persistence, privacy and lifecycle

Store layout, units and non-sensitive connection preferences in the existing private preference pattern. Do not store or expose VIN by default. Cache validated sensor support against a user-labelled vehicle profile and protocol/ECU identity rather than exporting a public identifier.

Keep readings local. Recording should be opt-in, bounded by size/time, deletable in the app and exportable only on user request through Android’s document picker. Do not enable Bluetooth HCI snoop logging globally or export device addresses, VINs or unfiltered raw frames as routine diagnostics. The network firewall’s VPN capture covers IP traffic; RFCOMM/GATT sensor traffic is a separate local transport and should not be falsely shown as an internet destination.

For the first iteration, stop polling when the Vehicle page is left. If background trip recording is later enabled explicitly, use a separate non-exported connected-device foreground service with a visible Stop action. Do not attach OBD polling to `FirewallService`, silently restart it at boot or reconnect indefinitely after ignition loss. Close GATT/socket resources on session stop. [Android GATT resource lifecycle](https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server)

## Acceptance gates before a live release

1. Run app-level Bluetooth capability checks on this firmware; preserve the existing phone/audio connection during the test.
2. With the adapter plugged in and ignition on, verify one real transport and valid adapter identification. No actuator or fault-clear commands.
3. Obtain valid generic support masks and a small number of real readings; compare them against a trusted reader sequentially, not with competing concurrent OBD sessions.
4. Verify absent/unsupported PIDs, multiple ECU replies, timeouts, adapter unplugging, Bluetooth loss and ignition-off behaviour.
5. Verify all six dashboard cards, units, stale states and menus at the measured 1280×660 app viewport and 1.5× text scale, then inspect real CC3 screenshots.
6. Verify foreground/background transitions and local log retention/export without transmitting vehicle data externally.
7. Consider enhanced Toyota readings only after the precise engine/fuel variant and actual ECU responses have been checked.

No real adapter connection, supported-PID response, throughput, Toyota enhanced sensor or audible alert has been validated in this research pass.
