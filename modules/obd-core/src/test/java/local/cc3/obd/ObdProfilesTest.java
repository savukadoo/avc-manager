package local.cc3.obd;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import local.cc3.obd.profiles.*;

/** Pure-Java profile and extension acceptance. All wire bytes terminate in a local fake. */
public final class ObdProfilesTest {
    private static int assertions;
    private static final ObdVehicleProfile TOYOTA = ObdProfiles.byId(Toyota1gdProfile.ID);
    public static void main(String[] args) throws Exception {
        definitions();
        migrationAndBoundaries();
        catalogChoices();
        fuelAndInjectors();
        partialAndWrongSource();
        extensionAndSwitch();
        System.out.println(
                "OBD profile tests passed: " + assertions + " assertions; no Android or real adapter");
    }
    private static void check(boolean value, String label) {
        if (!value)
            throw new AssertionError(label);
        assertions++;
    }
    private static void near(double a, double b, String label) {
        check(Math.abs(a - b) < .000001, label + ": " + a + " != " + b);
    }
    private static void denied(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected rejection");
        } catch (IllegalArgumentException expected) {
            assertions++;
        }
    }
    private static void await(BooleanSupplier done, long ms) throws Exception {
        long end = System.nanoTime() + ms * 1000000L;
        while (System.nanoTime() < end) {
            if (done.getAsBoolean())
                return;
            Thread.sleep(15);
        }
        throw new AssertionError("Timed out");
    }
    private static void definitions() {
        check(TOYOTA.sensors.size() == 34, "Toyota selected sensor set");
        for (String id : new String[] {"oil_temp", "toyota_oil_temp", "dpf_regen", "dpf_regen_type",
                     "dpf_service_regen", "dpf_trigger", "dpf_avg_time", "dpf_avg_distance", "dpf_pressure"})
            check(TOYOTA.sensor(id) == null, "Removed from Toyota profile: " + id);
        check(ObdProfiles.standard().sensor("oil_temp") != null,
                "Other cars may discover standard oil without enabling it on Toyota");
        check(ObdProfiles.standard().sensor("dpf_pressure") != null,
                "SAE pressure remains a separate standard definition");
        byte[] rail = {0x61, 5, 0, 0x0D, (byte) 0xAC, 0x0C, (byte) 0xE4};
        near(TOYOTA.sensor("fuel_rail_actual").decode(rail), 33, "Actual D/E pressure in MPa");
        near(TOYOTA.sensor("fuel_rail_target").decode(rail), 35, "Target B/C pressure in MPa");
        near(TOYOTA.sensor("fuel_rail_actual").displayValue(1, true), 145.037738, "MPa to psi");
        check(TOYOTA.sensor("fuel_rail_actual").unit(true).equals("psi"), "Imperial pressure label");
        byte[] feedback = feedback();
        double[] expected = {0, 1.25, -1.25, 2.5};
        for (int i = 0; i < 4; i++) {
            ObdPid p = TOYOTA.sensor("injector_" + (i + 1));
            near(p.decode(feedback), expected[i], "Injector position P/Q/R/S " + i);
            check(p.valueText(expected[i], false)
                            .equals(i == 0           ? "+0.00"
                                            : i == 1 ? "+1.25"
                                            : i == 2 ? "-1.25"
                                                     : "+2.50"),
                    "Signed two-decimal display");
            denied(() -> p.decode(Arrays.copyOf(feedback, p.bytes + 1)));
            byte[] wrong = feedback.clone();
            wrong[1] = 0x38;
            denied(() -> p.decode(wrong));
            check(p.category.equals("injectors") && p.experimental, "Injectors grouped and experimental");
        }
        byte[] zero = feedback.clone();
        zero[17] = 0;
        near(TOYOTA.sensor("injector_1").decode(zero), -10, "Unsigned input gives negative feedback");
        zero[17] = (byte) 255;
        near(TOYOTA.sensor("injector_1").decode(zero), 9.921875,
                "Upper byte preserves scale without rounding early");
        check(TOYOTA.category("fuel_rail").primaryId.equals("fuel_rail_actual"), "Rail category primary");
        check(TOYOTA.category("injectors").primaryId.equals("injector_1"), "Injector category primary");
    }
    private static void migrationAndBoundaries() {
        check(ObdProfiles.migratedId(null, null, false).equals("generic-obd2"),
                "New installs default generic");
        check(ObdProfiles.migratedId(null, null, true).equals(TOYOTA.id), "Legacy configured CC3 migration");
        check(ObdProfiles.migratedId(null, true, false).equals(TOYOTA.id),
                "Explicit legacy Toyota flag retained");
        check(ObdProfiles.migratedId(null, false, true).equals("generic-obd2"),
                "Explicit legacy standard flag retained");
        check(ObdProfiles.migratedId("generic-obd2", true, true).equals("generic-obd2"),
                "New profile ID overrides legacy flag");
        check(ObdProfiles.migratedId("missing-profile", true, true).equals("generic-obd2"),
                "Unknown saved profile fails to generic");
        for (ObdVehicleProfile profile : ObdProfiles.all()) {
            ObdCommandPolicy policy = new ObdCommandPolicy(profile);
            for (String command : new String[] {"04", "0902", "3101", "ATMA", "010C\r04"})
                denied(() -> policy.allow(command));
        }
        ObdCommandPolicy toyotaPolicy = new ObdCommandPolicy(TOYOTA);
        for (String command : new String[] {"2151", "018B", "221F5C"})
            denied(() -> toyotaPolicy.allow(command));
        for (String command : new String[] {"2105", "213D", "2182", "ATSH7E0"})
            denied(() -> new ObdCommandPolicy(ObdProfiles.standard()).allow(command));
        for (ObdPid pid : TOYOTA.sensors)
            check(new ObdCommandPolicy(TOYOTA).allow(pid.command()).equals(pid.command()),
                    "Only declared sensors admitted");
        denied(()
                        -> new ObdPid("bad", "Actuator", "", 0, 1, 0x2F, 1, 1, 1000,
                                a -> 0, "7E0", "7E8", "Test", "", "", ""));
        denied(()
                        -> new ObdVehicleProfile("bad", "Bad", "", "",
                                Arrays.asList(TOYOTA.sensor("injector_1"), TOYOTA.sensor("injector_1")),
                                Collections.emptyList(), Collections.emptyList(), "", "", "7DF"));
    }
    private static void fuelAndInjectors() throws Exception {
        Link link = toyota();
        try (ObdEngine engine = new ObdEngine(null)) {
            engine.setProfile(TOYOTA);
            engine.connect(link);
            await(()
                            -> engine.snapshot().phase == ObdEngine.Phase.MONITORING
                            && engine.snapshot().readings.containsKey("injector_4"),
                    18000);
            ObdEngine.Snapshot s = engine.snapshot();
            for (String id : new String[] {"fuel_rail_actual", "fuel_rail_target", "injector_1", "injector_2",
                         "injector_3", "injector_4"}) {
                check(s.supported.contains(id), "Valid responding field selectable: " + id);
                check(s.readings.get(id).source.equals("7E8"), "Selected source retained");
                check(s.sensorChecks.get(id).raw.startsWith("61 "), "Raw manufacturer reply retained");
                check(engine.minuteHistory(id, System.currentTimeMillis())
                                .stream()
                                .anyMatch(m -> m.samples > 0),
                        "Profile metric enters ten-minute history");
            }
            check(Collections.frequency(link.commands, "213D") == 1,
                    "One initial query decodes all four injector fields");
            check(Collections.frequency(link.commands, "2105") == 1,
                    "One initial query decodes actual and target rail pressure");
            check(link.header.equals("7DF"), "Functional header restored");
            for (String removed : new String[] {"015C", "2151", "018B"})
                check(!link.commands.contains(removed), "No removed request sent");
            for (int i = 1; i < link.times.size(); i++)
                check(link.times.get(i) - link.times.get(i - 1) >= 235000000L,
                        "Global query cap includes enhanced/header traffic");
        }
        // Generic discovery never sends a manufacturer read even when fake responses exist.
        link = toyota();
        try (ObdEngine engine = new ObdEngine(null)) {
            engine.connect(link);
            await(() -> engine.snapshot().phase == ObdEngine.Phase.MONITORING, 8000);
            check(link.commands.stream().noneMatch(c -> c.startsWith("21") || c.startsWith("ATSH")),
                    "Generic vehicle stays generic");
        }
    }
    private static void partialAndWrongSource() throws Exception {
        Link link = toyota();
        link.responses.put("213D", frame("7E8", Arrays.copyOf(feedback(), 18)));
        link.responses.put("2105", frame("7E9", new byte[] {0x61, 5, 0, 1, 2, 3, 4}));
        try (ObdEngine engine = new ObdEngine(null)) {
            engine.setProfile(TOYOTA);
            engine.connect(link);
            await(() -> engine.snapshot().phase == ObdEngine.Phase.MONITORING, 18000);
            ObdEngine.Snapshot s = engine.snapshot();
            check(s.supported.contains("injector_1"),
                    "Complete first injector retained in short group response");
            for (String id : new String[] {"injector_2", "injector_3", "injector_4"})
                check(!s.supported.contains(id)
                                && s.sensorChecks.get(id).state == ObdSensorCheck.State.INCOMPLETE,
                        "Missing later bytes remain incomplete");
            check(!s.readings.containsKey("fuel_rail_actual") && !s.readings.containsKey("fuel_rail_target"),
                    "Another ECU cannot supply selected Toyota fields");
        }
    }
    private static void catalogChoices() {
        ObdVehicleCatalog catalog = ObdProfiles.catalog();
        check(ObdProfiles.standard().models.isEmpty() && catalog.manufacturers().contains("Toyota"),
                "Standard is a separate choice");
        for (String model : Arrays.asList("Fortuner", "Hilux", "Prado"))
            check(catalog.models("toyota").contains(model) && catalog.engines("Toyota", model).contains(TOYOTA),
                    "Existing Toyota engine ID under " + model);
        check(TOYOTA.modelName("hilux").equals("Hilux") && TOYOTA.modelName("Corolla").isEmpty(),
                "Saved model restores only when declared for the profile");

        ObdVehicleProfile otherEngine = catalogProfile("demo-engine", "toyota", "Example engine", "fortuner");
        ObdVehicleProfile otherMake = catalogProfile("demo-make", "Example Motors", "Example engine", "Fortuner");
        ObdVehicleCatalog extended = new ObdVehicleCatalog(Arrays.asList(TOYOTA, otherEngine, otherMake));
        check(new ArrayList<>(extended.manufacturers()).equals(Arrays.asList("Example Motors", "Toyota")),
                "Manufacturer names are grouped case-insensitively");
        check(new ArrayList<>(extended.models("toyota")).equals(Arrays.asList("Fortuner", "Hilux", "Prado")),
                "Model names are grouped and sorted within the manufacturer");
        check(new ArrayList<>(extended.engines("TOYOTA", "fortuner")).equals(Arrays.asList(TOYOTA, otherEngine)),
                "Different engines appear under the same make and model");
        check(!extended.engines("Toyota", "Hilux").contains(otherEngine), "Engine variants stay with their models");
        check(new ArrayList<>(extended.engines("Example Motors", "Fortuner")).equals(Arrays.asList(otherMake)),
                "Equal model names stay separate across manufacturers");
        check(extended.models("Unknown").isEmpty() && extended.engines("Toyota", "Unknown").isEmpty(),
                "Unknown catalog branches return no matches");
        denied(() -> new ObdVehicleCatalog(Arrays.asList(TOYOTA,
                             catalogProfile("duplicate", "toyota", TOYOTA.engine, "FORTUNER"))));
        denied(() -> catalogProfile("missing-engine", "Toyota", " ", "Fortuner"));
    }

    private static ObdVehicleProfile catalogProfile(String id, String manufacturer, String engine, String model) {
        ObdVehicleProfile standard = ObdProfiles.standard();
        return new ObdVehicleProfile(id, id, "Test profile", "Test fixture", standard.sensors,
                standard.categories, Collections.emptyList(), "", "", "", manufacturer, engine, model);
    }

    private static void extensionAndSwitch() throws Exception {
        ObdPid custom = new ObdPid("demo.rpm", "Demo RPM", "rpm", 0, 10000, 0x22, 0xF40C, 2, 1000,
                a
                -> ((a[0] & 255) * 256 + (a[1] & 255)) / 4.0,
                "7E1", "7E9", "Demo test", "", "", "Synthetic extension test only", false);
        check(custom.enhanced && !custom.experimental, "Addressing is independent of formula confidence");
        List<ObdPid> sensors = new ArrayList<>(StandardSensors.all());
        sensors.add(custom);
        ObdVehicleProfile demo = new ObdVehicleProfile("demo-uds", "Demo UDS", "Test only", "Synthetic",
                sensors, ObdProfiles.standard().categories, Arrays.asList(custom.id), "7E8", ".*CAN.*11/.*",
                "7DF");
        check(new ObdCommandPolicy(demo).allow("22F40C").equals("22F40C"),
                "Compiled extension request registered without engine edits");
        denied(() -> new ObdCommandPolicy(TOYOTA).allow("22F40C"));
        denied(() -> new ObdCommandPolicy(demo).allow("22F190"));
        Link link = new Link();
        link.responses.put("22F40C", frame("7E9", new byte[] {0x62, (byte) 0xF4, 0x0C, 0x1A, (byte) 0xF8}));
        try (ObdEngine engine = new ObdEngine(null)) {
            engine.setProfile(demo);
            engine.connect(link);
            await(() -> engine.snapshot().readings.containsKey(custom.id), 10000);
            near(engine.snapshot().readings.get(custom.id).value, 1726, "UDS two-byte identifier decoded");
            check(engine.snapshot().readings.get(custom.id).source.equals("7E9"),
                    "Declared response ECU distinct from primary");
            engine.setRefreshCycleMillis(1000);
            check(engine.snapshot().profileId.equals(demo.id), "Snapshot identifies compiled profile");
            engine.setProfile(demo);
            check(!link.closed && engine.snapshot().readings.containsKey(custom.id),
                    "Reselecting the same profile keeps its session");
            engine.setProfile(demo, true);
            check(link.closed && engine.snapshot().profileId.equals(demo.id)
                            && engine.snapshot().readings.isEmpty()
                            && engine.minuteHistory(custom.id, System.currentTimeMillis())
                                       .stream()
                                       .allMatch(m -> m.samples == 0),
                    "Changing to a different vehicle clears its predecessor's session even with shared definitions");
            Link replacement = new Link();
            replacement.responses.putAll(link.responses);
            engine.connect(replacement);
            await(() -> engine.snapshot().readings.containsKey(custom.id), 10000);
            engine.setProfile(ObdProfiles.standard());
            int count = replacement.commands.size();
            Thread.sleep(350);
            check(replacement.closed && replacement.commands.size() == count,
                    "Profile switch closes/cancels old transport");
            check(engine.snapshot().readings.isEmpty()
                            && engine.minuteHistory(custom.id, System.currentTimeMillis())
                                       .stream()
                                       .allMatch(m -> m.samples == 0),
                    "No old profile values/history leak");
        }
    }
    private static byte[] feedback() {
        byte[] a = new byte[21];
        a[0] = 0x61;
        a[1] = 0x3D;
        a[17] = (byte) 128;
        a[18] = (byte) 144;
        a[19] = (byte) 112;
        a[20] = (byte) 160;
        return a;
    }
    private static Link toyota() {
        Link link = new Link();
        link.responses.put(
                "2105", frame("7E8", new byte[] {0x61, 5, 0, 0x0D, (byte) 0xAC, 0x0C, (byte) 0xE4}));
        link.responses.put("213D", frame("7E8", feedback()));
        return link;
    }
    private static String frame(String source, byte[] a) {
        StringBuilder b = new StringBuilder();
        if (a.length <= 7) {
            b.append(String.format(Locale.ROOT, "%s %02X", source, a.length));
            for (byte v : a) b.append(String.format(Locale.ROOT, " %02X", v & 255));
            return b.toString();
        }
        b.append(String.format(Locale.ROOT, "%s %02X %02X", source, 0x10 | (a.length >> 8), a.length & 255));
        int at = 0;
        while (at < 6) b.append(String.format(Locale.ROOT, " %02X", a[at++] & 255));
        int seq = 1;
        while (at < a.length) {
            b.append(String.format(Locale.ROOT, "\r%s %02X", source, 0x20 | (seq++ & 15)));
            for (int n = 0; n < 7; n++)
                b.append(String.format(Locale.ROOT, " %02X", at < a.length ? a[at++] & 255 : 0));
        }
        return b.toString();
    }
    private static final class Link implements ObdLink {
        final Map<String, String> responses = new HashMap<>();
        final List<String> commands = new CopyOnWriteArrayList<>();
        final List<Long> times = new CopyOnWriteArrayList<>();
        String header = "7DF";
        boolean closed;
        byte[] data = {};
        int at;
        public void open() {}
        public String description() {
            return "Synthetic profile test";
        }
        public synchronized void write(byte[] bytes) throws IOException {
            if (closed)
                throw new IOException("closed");
            String c = new String(bytes, StandardCharsets.US_ASCII).trim();
            commands.add(c);
            times.add(System.nanoTime());
            if (c.startsWith("ATSH"))
                header = c.substring(4);
            String r = responses.get(c);
            if (r == null) {
                if (c.equals("ATZ") || c.equals("ATI"))
                    r = "ELM327 test";
                else if (c.equals("ATDP"))
                    r = "ISO15765-4(CAN11/500)";
                else if (c.startsWith("AT"))
                    r = "OK";
                else if (c.equals("0100"))
                    r = "7E8 06 41 00 08 10 00 00";
                else if (c.equals("010C"))
                    r = "7E8 04 41 0C 1A F8";
                else if (c.equals("0105"))
                    r = "7E8 03 41 05 50";
                else
                    r = "NO DATA";
            }
            data = (r + "\r>").getBytes(StandardCharsets.US_ASCII);
            at = 0;
        }
        public synchronized int read(byte[] target, int offset, int length, long timeout) throws IOException {
            if (closed)
                throw new IOException("closed");
            int n = Math.min(length, data.length - at);
            if (n > 0) {
                System.arraycopy(data, at, target, offset, n);
                at += n;
            }
            return n;
        }
        public synchronized void close() {
            closed = true;
        }
    }
}
