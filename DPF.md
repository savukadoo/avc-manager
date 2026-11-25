# Toyota DPF and related engine monitoring

The installed0.4.3 release remains the previous physical baseline. The local0.5.0 candidate uses a modular earlier-130kW1GD profile for the2018 Fortuner2.8 diesel automatic. Custom readings are labelled **Toyota test** until checked against a reference scanner.

## Dashboard categories

Choose **Vehicle → Configure → a card** and select a category:

| Category | Default primary stat | Related graphs |
| --- | --- | --- |
| DPF | Accumulation | Available exhaust temperatures, Toyota pressure candidate, restriction/correction/fuel-feedback candidates and flagged standard inlet/outlet fields |
| Fuel rail | Actual rail pressure, inMPa orpsi | ECU-reported target rail pressure |
| Injectors | Cylinder1 feedback, inmm³/stroke | Cylinder2/3/4 feedback corrections |

Tap the card to see its ten-minute, one-minute-average graph and related readings. Browsing does not change the saved main stat. **Use on card** makes the selected available reading the primary; **On card ✓** confirms it. Missing samples remain gaps. A responding enhanced field becomes selectable; a partial response cannot invent the missing later fields.

## Removed from the Toyota profile

At the owner's request, engine-oil temperature, regeneration status/type/trigger/intervals, service-regeneration status and inferred/unmapped regeneration counters are excluded. No015C,2151 or018B query is sent by this profile. Standard oil temperature remains a separate advertised-only option for other vehicles using the Standard profile.

The signed017A differential-pressure comparison is excluded from the Toyota profile because earlier real captures produced implausible values. The community2104 offset interpretation remains experimental, with its raw reply and formula available in details. Standard signed pressure remains separately defined for other profiles. The ECU supplies the raw parameter; the community equation converts its encoding to engineering units and is not a validated diagnosis.

## Definitions and current limits

- Toyota2138: accumulationJ×2, restrictionF/G×0.001−32.768 (experimental index), pressure correctionH/I÷100−327.68, exhaust-fuel feedbackM/N÷100−327.68. No blockage/regeneration threshold is inferred.
- Toyota2104: pressureJ/K÷100−327.68kPa. The earlier raw017A/2104 word matched, but its physical pressure scale has not been compared with a reference scanner.
- Toyota2105: actualD/E and targetB/C×10kPa, displayed asMPa. Target is a reading, not an app control.
- Toyota213D: feedbackP/Q/R/S÷12.8−10mm³/stroke. Signed display preserves negative values; no injector-fault threshold is applied.
- Standard0178/017A inlet-outlet/017C: full replies and each field's presence flag are required. EGT numbering does not establish physical sensor position.

The previous actual-ECU sessions returned some DPF/EGT candidates and refused017C/018B; those are historical findings from the earlier build. Fuel-rail/injector availability on this specific ECU has not yet been established. The profile's34registered definitions are not34validated measurements.

The aggregate four-query/second cap includes adapter, header and discovery traffic. The profile applies enhanced requests only to its selected7E8/11-bit-CAN context and restores functional addressing. It includes only2104,2105,2138,213D,2182 and2185 manufacturer sensor reads. It provides no raw terminal, forced regeneration, vehicle actuation, programming or code clearing.

## Reports and extension

**Sensors → DPF checks → Save report**, or **Save report** in a category graph, saves a local CSV with selected profile ID, sample/check outcomes, definitions, raw replies and ECU source. A failed read never refreshes a previous sample's timestamp.

[Source register](research/toyota-1gd-custom-pids.md) · [Module/adapter extension guide](OBD_MODULES.md).
