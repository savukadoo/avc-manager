package local.cc3.obd.profiles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.cc3.obd.*;

/** Earlier130kW1GD candidates, selected explicitly. Formulas are not reference-tool validation. */
public final class Toyota1gdProfile {
    public static final String ID = "toyota-1gd-130kw";
    private static int word(byte[] a, int at) {
        return (a[at] & 255) * 256 + (a[at + 1] & 255);
    }
    private static ObdPid sensor(String id, String label, String unit, double min, double max, int pid,
            int bytes, long interval, ObdPid.Decoder decoder, String category, String cardLabel, String note,
            String... states) {
        return new ObdPid(id, label, unit, min, max, 0x21, pid, bytes, interval, decoder, "7E0", "7E8",
                "Toyota test", category, cardLabel, note, states);
    }
    public static ObdVehicleProfile create() {
        List<ObdPid> list = new ArrayList<>();
        for (ObdPid p : StandardSensors.all())
            if (!p.id.equals("oil_temp") && !p.id.equals("dpf_pressure"))
                list.add(p);
        list.add(sensor("atf_pan", "Transmission pan", "°C", -40, 215, 0x82, 4, 5000,
                a
                -> (a[0] & 255) - 40,
                "", "", "Toyota2182: pan A−40°C; compare with a reference scan tool."));
        list.add(sensor("atf_converter", "Torque converter", "°C", -40, 215, 0x82, 4, 5000,
                a
                -> (a[2] & 255) - 40,
                "", "", "Toyota2182: converter C−40°C; compare with a reference scan tool."));
        list.add(sensor("dpf_soot", "DPF accumulation", "%", 0, 510, 0x38, 10, 10000,
                a
                -> (a[9] & 255) * 2,
                "dpf", "DPF · Accumulation",
                "Toyota2138: J×2. Can exceed100%; no diagnostic threshold is applied."));
        list.add(sensor("dpf_pressure_toyota", "DPF pressure (Toyota)", "kPa", -327.68, 327.67, 0x04, 11,
                10000,
                a
                -> word(a, 9) / 100.0 - 327.68,
                "dpf", "DPF · Pressure",
                "Community earlier130kW2104 candidate: U16(J,K)/100−327.68kPa. Conflicts with SAE signed "
                        + "encoding. Reference-tool validation remains required."));
        list.add(sensor("dpf_restriction", "Catalyst restriction", "index", -32.768, 32.767, 0x38, 7, 10000,
                a
                -> word(a, 5) * .001 - 32.768,
                "dpf", "DPF · Restriction",
                "Toyota2138: F/G×0.001−32.768, experimental restriction index; units/scale unresolved."));
        list.add(sensor("dpf_pressure_correction", "DPF pressure correction", "kPa", -327.68, 327.67, 0x38, 9,
                10000,
                a
                -> word(a, 7) / 100.0 - 327.68,
                "dpf", "DPF · Correction",
                "Toyota2138: H/I/100−327.68. Sensor correction is distinct from measured filter pressure "
                        + "drop."));
        list.add(sensor("dpf_fuel_feedback", "Exhaust fuel feedback", "index", -327.68, 327.67, 0x38, 14,
                10000,
                a
                -> word(a, 12) / 100.0 - 327.68,
                "dpf", "DPF · Fuel feedback",
                "Toyota2138: M/N/100−327.68, community candidate; physical identity and units require "
                        + "reference comparison."));
        list.add(sensor("reported_gear", "Reported gear", "gear", 0, 6, 0x85, 2, 2500, a -> {
            int n = a[0] & 255;
            if (n > 6)
                throw new IllegalArgumentException("Unknown reported gear");
            return n;
        }, "", "", "Toyota2185: A. Does not identify P/N/R selector position."));
        list.add(sensor("converter_lock", "Converter lock-up", "state", 0, 1, 0x85, 2, 2500,
                a -> ((a[1] & 255) >> 7) & 1, "", "", "Toyota2185: bit7 ofB.", "Unlocked", "Locked"));
        list.add(sensor("fuel_rail_actual", "Fuel rail pressure", "MPa", 0, 250, 0x05, 5, 2500,
                a
                -> word(a, 3) * .01,
                "fuel_rail", "Fuel rail · Actual",
                "Community2105: U16(D,E)×10kPa, displayed as MPa. A valid reply is not reference validation. "
                        + "Read-only pressure measurement."));
        list.add(sensor("fuel_rail_target", "Fuel rail target", "MPa", 0, 250, 0x05, 3, 2500,
                a
                -> word(a, 1) * .01,
                "fuel_rail", "Fuel rail · Target",
                "Community2105: U16(B,C)×10kPa, displayed as MPa. Target is an ECU-reported value; this app "
                        + "does not command pressure."));
        for (int i = 0; i < 4; i++) {
            final int offset = 15 + i;
            list.add(sensor("injector_" + (i + 1), "Injector correction " + (i + 1), "mm³/stroke", -10, 10,
                    0x3D, 16 + i, 5000,
                    a
                    -> (a[offset] & 255) / 12.8 - 10,
                    "injectors", "Injectors · Cylinder " + (i + 1),
                    "Community213D: " + (char) ('P' + i)
                            + ("/12.8−10mm³/stroke. Signed feedback candidate; compare all cylinders with "
                                    + "Toyota live data at matching conditions. No fault diagnosis threshold "
                                      + "is "
                                    + "applied.")));
        }
        return new ObdVehicleProfile(ID, "Toyota 1GD · early 130 kW",
                "For earlier Toyota 2.8L diesel Fortuner, Hilux and Prado models (130 kW). Adds "
                + "transmission, "
                        + "DPF, fuel-rail and injector readings using experimental definitions.",
                "Independent decoder implementations with source attribution:\n\nptommo59,23Feb2026, "
                        + "earlier130kW1GD list (2105 pressure and213D injector "
                        + "corrections):\nhttps://www.newhilux.net/phpbb/"
                        + "viewtopic.php?t=45103\n\nGodzilla73,25Jan2019,2138 "
                        + "accumulation:\nhttps://www.newhilux.net/phpbb/"
                          + "viewtopic.php?start=220&t=26849\n\nGeeWhizz/"
                        + "Aquanaught, PradoPoint2182/2185 captures and2018 verification. Cross-check: "
                          + "ScanGauge "
                        + "Australia Toyota2016–2020 definitions.\n\nFormulas remain experimental until "
                          + "compared "
                        + "with this vehicle's reference scan tool. No full forum CSV, prose or attachment "
                          + "is "
                        + "redistributed.",
                list,
                Arrays.asList(
                        new ObdCategory("dpf", "DPF", "dpf_soot",
                                "DPF accumulation and related exhaust/pressure candidates. No "
                                        + "oil-temperature or regeneration status/counters are queried."),
                        new ObdCategory("fuel_rail", "Fuel rail", "fuel_rail_actual",
                                "Actual and ECU-reported target rail pressure. Read-only community "
                                        + "definitions; compare with a reference tool."),
                        new ObdCategory("injectors", "Injectors", "injector_1",
                                "Four cylinder feedback corrections, not injection commands. No automatic "
                                        + "diagnosis or warning thresholds.")),
                Arrays.asList("atf_pan", "dpf_soot", "reported_gear", "dpf_pressure_toyota",
                        "fuel_rail_actual", "injector_1", "dpf_inlet_temp"),
                "7E8", ".*CAN.*11/.*", "7DF");
    }
    private Toyota1gdProfile() {}
}
