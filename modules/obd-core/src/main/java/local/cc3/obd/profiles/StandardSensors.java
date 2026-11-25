package local.cc3.obd.profiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import local.cc3.obd.ObdPid;

/** Shared SAE-style sensor definitions. Inclusion never asserts ECU support. */
public final class StandardSensors {
    private static final List<ObdPid> ALL = build();
    public static List<ObdPid> all() {
        return ALL;
    }
    private static ObdPid sensor(String id, String label, String unit, double min, double max, int pid,
            int bytes, long interval, ObdPid.Decoder decoder, String note) {
        boolean dpf = id.startsWith("dpf_") || id.startsWith("egt_");
        return new ObdPid(id, label, unit, min, max, 1, pid, bytes, interval, decoder, "", "", "Standard",
                dpf ? "dpf" : "", dpf ? "DPF · " + label.replaceFirst("^DPF ", "") : "", note);
    }
    private static int word(byte[] a, int at) {
        return (a[at] & 255) * 256 + (a[at + 1] & 255);
    }
    private static void flag(byte[] a, int bit) {
        if (((a[0] & 255) & (1 << bit)) == 0)
            throw new IllegalArgumentException("Sensor field not reported as supported");
    }
    private static List<ObdPid> build() {
        List<ObdPid> list = new ArrayList<>();
        String generic = "Standard sensor; support and valid replies are checked on the selected ECU.";
        list.add(sensor("rpm", "Engine speed", "rpm", 0, 6000, 0x0C, 2, 700, a -> word(a, 0) / 4.0, generic));
        list.add(sensor("speed", "Vehicle speed", "km/h", 0, 200, 0x0D, 1, 900, a -> a[0] & 255, generic));
        list.add(
                sensor("coolant", "Coolant", "°C", -40, 150, 0x05, 1, 2500, a -> (a[0] & 255) - 40, generic));
        list.add(sensor(
                "load", "Engine load", "%", 0, 100, 0x04, 1, 1800, a -> (a[0] & 255) * 100.0 / 255, generic));
        list.add(sensor(
                "map", "Manifold pressure", "kPa abs", 0, 300, 0x0B, 1, 1500, a -> a[0] & 255, generic));
        list.add(sensor(
                "voltage", "ECU voltage", "V", 0, 20, 0x42, 2, 5000, a -> word(a, 0) / 1000.0, generic));
        list.add(sensor(
                "intake", "Intake air", "°C", -40, 150, 0x0F, 1, 4000, a -> (a[0] & 255) - 40, generic));
        list.add(sensor("throttle", "Throttle position", "%", 0, 100, 0x11, 1, 1800,
                a -> (a[0] & 255) * 100.0 / 255, generic));
        list.add(sensor("maf", "Air flow", "g/s", 0, 300, 0x10, 2, 2500, a -> word(a, 0) / 100.0, generic));
        list.add(sensor("fuel_level", "Fuel level", "%", 0, 100, 0x2F, 1, 15000,
                a -> (a[0] & 255) * 100.0 / 255, generic));
        list.add(sensor(
                "baro", "Barometric pressure", "kPa", 0, 150, 0x33, 1, 15000, a -> a[0] & 255, generic));
        // Available to other cars only when advertised. The earlier Toyota profile excludes this sensor.
        list.add(sensor("oil_temp", "Oil temperature", "°C", -40, 180, 0x5C, 1, 5000,
                a -> (a[0] & 255) - 40, generic));
        list.add(sensor(
                "fuel_rate", "Fuel flow", "L/h", 0, 80, 0x5E, 2, 4000, a -> word(a, 0) / 20.0, generic));
        for (int i = 0; i < 3; i++) {
            final int n = i;
            list.add(sensor("egt_" + (i + 1), "Exhaust temperature " + (i + 1), "°C", -40, 1000, 0x78,
                    3 + i * 2, 5000,
                    a
                    -> {
                        flag(a, n);
                        return word(a, 1 + n * 2) / 10.0 - 40;
                    },
                    "Standard0178: individual sensor-present flag required. Physical sensor position is not "
                    + "inferred."));
        }
        list.add(sensor("dpf_pressure", "DPF differential pressure", "kPa", -327.68, 327.67, 0x7A, 3, 5000,
                a
                -> {
                    flag(a, 0);
                    return ((short) word(a, 1)) / 100.0;
                },
                "SAE017A signed16/100kPa. Not included in the earlier Toyota profile because its encoding "
                + "conflicts with captured Toyota raw data."));
        list.add(sensor("dpf_inlet_pressure", "DPF inlet pressure", "kPa", 0, 655.35, 0x7A, 5, 5000, a -> {
            flag(a, 1);
            return word(a, 3) / 100.0;
        }, "SAE017A: inlet field presence flag required; unsigned16/100kPa."));
        list.add(sensor("dpf_outlet_pressure", "DPF outlet pressure", "kPa", 0, 655.35, 0x7A, 7, 5000, a -> {
            flag(a, 2);
            return word(a, 5) / 100.0;
        }, "SAE017A: outlet field presence flag required; unsigned16/100kPa."));
        list.add(sensor("dpf_inlet_temp", "DPF inlet temperature", "°C", -40, 1000, 0x7C, 3, 5000, a -> {
            flag(a, 0);
            return word(a, 1) / 10.0 - 40;
        }, "SAE017C: inlet field support flag required; not inferred from EGT numbering."));
        list.add(sensor("dpf_outlet_temp", "DPF outlet temperature", "°C", -40, 1000, 0x7C, 5, 5000, a -> {
            flag(a, 1);
            return word(a, 3) / 10.0 - 40;
        }, "SAE017C: outlet field support flag required; not inferred from EGT numbering."));
        return Collections.unmodifiableList(list);
    }
    private StandardSensors() {}
}
