# Vehicle monitoring research — Veepeak OBDCheck BLE+ / 2018 Fortuner

Research date: 21 November 2026. Research only: no scan, pairing, adapter connection, ECU query, code clearing or vehicle control was performed. The user has confirmed a **2018 Fortuner with the 2.8-litre diesel**. Gearbox, delivery market, build month and ECU calibration remain unconfirmed. No native OBD module has been added to the APK as part of this research.

## Conclusion

A native **Vehicle** section below Controls is practical. Start with a local, read-only dashboard that discovers the ECU's supported standard parameters. The CC3's ability to maintain a connection to this physical adapter is the first acceptance gate. Toyota-specific monitoring should follow as a separate validated profile, rather than being promised from the model year alone.

## Adapter and connection

The photos identify **Veepeak OBDCheck BLE+**, labelled FCC `2BLL9-CAROBD` and IC `29069-BLEPLUS`. Use that identity; there is no verified basis to call it a BLE+2. The manufacturer's current product lists Bluetooth LE and Classic, ELM327 v2.2 command compatibility, and the common OBD-II protocol families: CAN ISO 15765-4, ISO 9141-2, KWP2000 and J1850 PWM/VPW. MS-CAN and SW-CAN are excluded. The advertised supply range is 9–16 V and maximum current 45 mA. [Veepeak product specifications](https://veepeak.com/products/obdcheck-ble-plus)

There is an important documentation change: the old support page defaults Android to Classic Bluetooth with PIN `1234`; the latest manufacturer instructions, V3.2610, recommend **BLE on Android when the app supports it**, with Classic as a fallback. BLE is therefore not an iOS-only option. Veepeak still warns that some Android head units lack suitable profiles or have manufacturer restrictions. Implement both transports, inspect actual services/characteristics or RFCOMM availability, and record which one works on this CC3. [Latest manufacturer manual](https://veepeak.com/cdn/shop/files/Plus_User_Instructions-English_new_v3.2610_af74b6c0-580c-4493-a6a1-49bddbb1d1ad.pdf?v=8614334253225844916), [Android connection guide](https://veepeak.com/pages/how-to-connect-car-scanner-to-obdcheck-ble-and-obdcheck-ble-on-android), [older support page](https://veepeak.com/pages/obdcheck-ble-plus)

## Monitoring we can offer

Every field below is conditional on a supported response from the connected ECU. A standard PID existing does not mean this particular Fortuner supplies it.

| Data | Standard route / limitation |
| --- | --- |
| Engine RPM and vehicle speed | Mode 01 PIDs `0C`, `0D` |
| Coolant and intake-air temperature | `05`, `0F` |
| Engine load and throttle position | `04`, `11`; throttle position is not necessarily accelerator-pedal position |
| Manifold pressure and barometric pressure | `0B`, `33`; derived boost requires both, otherwise label the reading absolute MAP |
| Air flow and fuel flow | `10`, `5E`; fuel economy estimates need suitable inputs and calibration |
| Fuel level | `2F`, if reported |
| ECU supply voltage | `42`, if reported |
| Oil temperature, exhaust temperatures and DPF pressure/temperature | Additional standard PIDs exist, but support is vehicle-dependent |
| Diagnostics | Check-engine/MIL state, readiness, supported fault categories, freeze-frame and vehicle information where available |

The app should populate its sensor picker from discovery results, not a fixed list presented as available. [Developer-maintained SAE PID catalogue and support caveat](https://www.obdsoftware.net/software/obdfusion)

ELM supported-PID queries identify the standard sensor set. Read services include current data (`01`), freeze-frame (`02`), stored codes (`03`), pending codes (`07`), vehicle information (`09`) and permanent codes (`0A`) where supported. `AT RV` is an adapter voltage measurement and should be labelled separately from ECU-reported voltage. Adapter calibration is a different operation and should not be exposed as a normal dashboard setting. [ELM Electronics command/data-sheet reference](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327L_Data_Sheet.pdf)

## Fortuner-specific readings: useful candidates, not yet confirmed

The likely engine is **1GD-FTV**: Toyota identifies its 2.8-litre GD diesel by that code, and Toyota's August 2018 Fortuner specification sheet lists it. This identifies the published model specification; it does not verify the engine fitted to this particular vehicle or its current ECU calibration. Keep the app's confirmed profile as “2018 Fortuner · 2.8 diesel” until identification is available. [Toyota engine announcement](https://global.toyota/en/detail/8348091), [Toyota-authored August 2018 Fortuner specification sheet, archived copy](https://s3-ap-southeast-2.amazonaws.com/imotor-cms/files_cms/33899_toyota_fortuner_spec_aug2018.pdf)

Transmission-fluid temperatures, selected gear/lock-up, DPF soot/load and regeneration status, extra EGT sensors, oil pressure and ABS/wheel data may require Toyota-specific requests and suitable sensors. Do not show guessed zeroes or derive oil pressure from temperature.

ScanGauge's Australian Toyota tables publish transmission-temperature requests for 2016–2020 vehicles, plus other model-specific DPF/EGT/gear definitions. Their model/year notes vary, so those are reference candidates rather than proof for this 2018 Fortuner. [Toyota X-Gauge definitions](https://www.scangauge.com.au/toyota-x-gauges/)

The open [OBDb Toyota-Fortuner repository](https://github.com/OBDb/Toyota-Fortuner) is another candidate source, under CC-BY-SA-4.0. Inspection of `signalsets/v3/default.json` found 194 commands, many marked as debug candidates, inconsistent year filters and CVT/hybrid-related entries. Do not ship or bulk-poll that file as a validated 2018 profile. Any reuse also needs its attribution/share-alike obligations reviewed.

OBD Fusion demonstrates that Toyota enhanced diagnostics can exist, but its desktop Toyota add-on explicitly targets North American vehicles. That is not a compatibility guarantee for an Australian Fortuner. [Vendor coverage limitation](https://www.obdsoftware.net/software/toyotaenhancedaddon)

For the confirmed 2018 2.8 diesel, prioritise **DPF regeneration status, PM/soot load, DPF differential pressure and exhaust temperatures** as enhanced research candidates. Expose each only after a supported, correctly decoded response has been validated. Derive “distance since regeneration” only from a validated ECU counter, or clearly label it as a locally observed estimate that can miss events while disconnected. Do not infer regeneration solely from high exhaust temperature or idle speed.

Toyota's Australian DPF booklet groups a 2018 GD Fortuner under the pre-May-2020 system and distinguishes it from the later system. Its later-system PM indicator scale must not be copied as a confirmed 2018 dashboard scale. It also distinguishes switch availability by build date; the manual switch is not proof that the same operation or data is available through this adapter. These publications do not promise that an arbitrary OBD app can read every DPF field. Market, build month and any software updates still matter. [Toyota Australia DPF booklet, hosted by Toyota NZ](https://www.toyota.co.nz/globalassets/owners/dpf/tso2021_01.pdf)

Before choosing an enhanced profile, confirm **automatic or manual gearbox, Australian-delivered versus import, and build month**. Prioritise ATF temperature and lock-up/gear data only if automatic transmission is confirmed. Oil temperature, oil pressure, rail pressure and injector-related values remain conditional; do not fill missing sensors with estimates. Generic monitoring can be developed before those answers. An optional local VIN/calibration read can help identify the ECU; the user need not publish their VIN.

## Proposed visual dashboard

- **Vehicle → Dashboard:** selectable four- or six-card layouts with large values, units, small trend traces, and a persistent connection/data-age indicator. Use the measured 1280×720, 160 dpi, 1.5× text profile and 60-pixel TEYES bar. Dashboard pages should not scroll.
- **Configure:** choose only discovered sensors; set card order, numeric/arc/trend presentation, units and user-defined alerts. Thresholds should come from the applicable vehicle documentation or user choice, not generic internet limits.
- **Sensors:** inspect all supported channels and raw response details when parked. Unsupported, stale and disconnected are distinct states; never substitute zero.
- **Faults:** separate stored, pending and permanent codes, readiness and freeze-frame. Save a local report before any later clearing feature.
- **Adapter:** select VEEPEAK, transport and vehicle profile; connect/disconnect, rescan capabilities, pause/resume sampling and opt-in reconnect. Logs and CSV export remain local by default.

For this 2.8 diesel, the first six-card **Engine** page should prioritise coolant temperature, RPM, MAP/boost, voltage, intake-air temperature and engine load, replacing unsupported channels with another user-selected supported sensor. **Diesel/DPF** should follow only after the compatible enhanced channels above are confirmed. **Towing** can combine coolant, boost, voltage and verified EGT/oil readings, with ATF temperature only for an automatic. Fuel flow/economy is optional and calculated economy or power must be labelled as an estimate. Poll a small chosen set adaptively; measure real query throughput before promising update rates. These are design recommendations, not measured adapter performance.

## What “control the device” should mean

The first release should control the connection, adapter setup, polling, gauge choices and local logging. Resetting the adapter connection is separate from resetting vehicle fault information.

A future code-clear action is a vehicle-changing command: Mode `04` clears diagnostic information; readiness can be reset and diagnostic evidence lost. Permanent DTCs cannot be erased with a normal scan-tool clear operation; the vehicle clears them after its own verification. Keep clearing out of the initial read-only release and require a concrete confirmation if it is added. [ELM service definitions](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327L_Data_Sheet.pdf), [California BAR technical explanation of permanent codes](https://www.bar.ca.gov/obd-test-reference)

Do not present ECU flashing, tuning, ABS/brake actuation, forced DPF regeneration, service resets or coding as supported controls. Veepeak expressly lists several service functions as unsupported; Toyota describes regeneration as a specific high-temperature vehicle operation. A generic Bluetooth scanner is not a substitute for a validated Toyota service tool. [Veepeak stated limits](https://veepeak.com/products/obdcheck-ble-plus), [Toyota DPF guidance](https://www.toyota.co.nz/globalassets/owners/dpf/tso2021_01.pdf)

Software disconnect does not physically unplug the adapter. A battery-conscious design should pause polling when the ECU stops responding and make automatic reconnection opt-in; adapter sleep/power commands need separate verification.

## Acceptance gates

1. Confirm Android Bluetooth access on this CC3, then, only with the user's adapter plugged in and ignition on, test BLE and Classic as needed.
2. Identify adapter, vehicle protocol and supported standard PIDs; distinguish adapter-connected from ECU-connected.
3. Validate readings and units against a known app/instruments; exercise disconnect, sleep/wake and stale-data states.
4. Verify all dashboard layouts at the physical display profile, with local data handling and no fabricated live readings.
5. Add enhanced Toyota channels individually after engine/ECU, transmission and market confirmation and measured validation.
