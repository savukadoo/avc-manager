---
name: android-head-unit-app
description: Build, review and validate Android apps installed directly on aftermarket vehicle head units, including Home launchers, vehicle dashboards, local media and vendor controls. Use for AVC Manager, TEYES/FYT and comparable units when work depends on the in-dash display, vehicle hardware or an in-car update. Phone-projected Android Auto and Android Automotive OS templates have different host contracts.
---

# Android head-unit apps

Start with the requested driver or parked-user task. Identify where the code runs, which app or service owns the state, and what the user should see or hear after acting. A screen labelled “CarPlay” can simply launch an installed projection app. A Home layout can be a saved configuration of one launcher. Establish those boundaries before choosing an API or changing the navigation.

## Choose the relevant workflow

- **Home, Appearance or another screen.** Start from the normal entry point. Trace selection, preview, Apply, Home and Back wherever those states exist. Use the same renderer and stable preference identifiers for Home and preview. Keep frequent driver actions in reach on the correct side for the vehicle. Check the actual painted screen with [Landscape visual acceptance](references/visual-acceptance.md).
- **Vehicle readings, DSP, media, weather or connectivity.** Identify the supplier of each value and the signal that confirms an action. Keep user intent, confirmed state and last known value separate. Use the relevant section of [Vehicle and device features](references/device-features.md) for capability detection, offline behaviour and hardware checks.
- **Preparing an update.** Use the project's build and signing path. An in-place upgrade needs the existing application ID, a compatible signing identity and an acceptable version code. Preserve the managed media library, dashboard slots, selected vehicle and adapter. When deployment is requested, target the confirmed device, verify the installed version, and reopen the changed feature. A weak link warrants checking the installed state before retrying. Report local build, emulator acceptance, installation and physical operation separately wherever they apply.

## When working in AVC Manager

The narrower repository skill `cc3-head-unit-design` contains the CC3 design references. Locate the checkout by its files. `DASHBOARD.md` and `tools/cc3-display-profile.json` define Home and the measured test viewport. `Screen.java` owns shared colours and controls. `OBD_MODULES.md`, `ENTERTAINMENT.md`, `WEATHER_MAP.md` and `SECURITY_HARDENING.md` describe the individual feature contracts. Start from the one the change touches.

For a different head unit, use its own window measurements, lifecycle, vehicle profile, build tools and integration evidence. The CC3 project and a physical car are optional for this skill. Say exactly which acceptance checks the available environment supports.
