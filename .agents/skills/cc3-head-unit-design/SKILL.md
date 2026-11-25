---
name: cc3-head-unit-design
description: Design, implement and visually review AVC Manager and aftermarket Android head-unit interfaces. Use for native Home layouts, hierarchy, icons, typography, themes, driver reach and screenshot-based acceptance on an in-dash display.
---

# AVC Interface Design

Start from the screen in use. Name the first thing the driver needs to recognise, the action they can take, and what can wait until parked. Compare the current native screenshot with the requested outcome. Revise the existing screen around that task. For a genuinely new layout, sketch two distributions of the same real content first, with the system bar and driver-side controls already occupying their space.

## Design in the dashboard's space

- Read the project's display profile. In the reference Fortuner, Android 10 draws into 1280 × 660 usable pixels at 160 dpi and 1.5× font scale. Keep all six Home layouts on one screen. Fixed actions and the driver-side rail must survive long labels, different clock faces, hidden headings and a partially filled grid.
- Let size, weight and placement communicate reading order. Give related figures a common baseline and units. Align icon artwork by visible weight and centre. Leave room for the longest caption when short and wrapped labels share a row. Give radio, projection and other vehicle functions a consistent native icon family. Preserve recognisable third-party app artwork. Distinguish an emulator fixture icon from the actual package art on the unit. If AVC supplies its own symbol for an equivalent vehicle action, make that choice explicit.
- Use surface colours to group information, then give the active destination an additional shape cue. Reserve stronger colour for the action, selection or live status that deserves attention. Pick colour roles once for each theme and apply them across native Views. Check those roles over the wallpaper people actually see.
- Give frequent Home actions a stable, reachable place. Use 76 × 76 dp as the automotive touch benchmark and aim for 4.5:1 contrast for readable text and icons on driving surfaces. Small parked-settings glyphs can have a 48 dp hit area. Keep visual selection, keyboard focus, service state and a momentary press distinguishable. Preserve accessibility names when switching between words and icons.
- Use one renderer for Home and its preview. Make selection, Apply and Back legible throughout a change. Verify that saved layouts, favourites and theme choices still work after relaunch.

## Review the rendered change

Use normal entry points with representative content. Compare before and after at the same viewport, theme, font scale and wallpaper. Inspect the original-size screenshot for visible icon bounds, first text baselines, clipping, glare-prone surfaces and footer alignment. Pair it with the accessibility tree to check hit areas and selected states. Review both factory-inspired themes when shared styling changes. For changes to common Home geometry, exercise the six layouts. Browsable library lists can scroll within their designated space.

A screenshot can establish that radar and All apps align. Reach in the cabin and washout in sunlight need a parked-car check. Report which environment supplied each result. For the Fortuner, start with `tools/cc3-display-profile.json`, `DASHBOARD.md`, `Screen.java` and the affected screen. For other units, measure their window and system bars.

## References

- For production examples of driver-side docks, media-and-map splits or favourites, read the [EV infotainment comparison](references/ev-infotainment.md).
- For the design approaches behind this skill and where each fits, read the [design sources](references/design-sources.md).
