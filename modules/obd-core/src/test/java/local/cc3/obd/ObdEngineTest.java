package local.cc3.obd;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/** Host-only protocol tests. All transport traffic terminates in FakeLink below. */
public final class ObdEngineTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        reconnectPolicy();
        formulas();
        allowlist();
        parsing();
        faultResults();
        framing();
        engine();
        mixedEcuFaultReplies();
        interruptedFaultScan();
        refreshAndHistory();
        toyotaDefinitions();
        dpfDefinitions();
        toyotaProfile();
        noEcu();
        cancelOpen();
        fatalTimeoutAndCancelRead();
        System.out.println("OBD host tests passed: " + assertions + " assertions; fake links only");
    }

    private static void formulas() {
        String[] ids = {"rpm", "speed", "coolant", "load", "map", "voltage", "intake", "throttle", "maf",
                "fuel_level", "baro", "oil_temp", "fuel_rate"};
        int[][] values = {{0x1A, 0xF8}, {100}, {130}, {255}, {180}, {0x35, 0xE8}, {65}, {128}, {0x01, 0xF4},
                {0}, {100}, {140}, {0x00, 0xC8}};
        double[] expected = {1726, 100, 90, 100, 180, 13.8, 25, 128 * 100.0 / 255, 5, 0, 100, 100, 10};
        for (ObdVehicleProfile profile : ObdProfiles.all())
            check(ObdPid.all().containsAll(profile.sensors), "Registry includes the sensors for " + profile.id);
        for (int i = 0; i < ids.length; i++) {
            ObdPid pid = ObdPid.byId(ids[i]);
            byte[] data = new byte[values[i].length + 2];
            data[0] = 0x41;
            data[1] = (byte) pid.pid;
            for (int j = 0; j < values[i].length; j++) data[j + 2] = (byte) values[i][j];
            near(pid.decode(data), expected[i], 0.00001, "formula " + ids[i]);
        }
        near(ObdPid.byId("coolant").displayValue(0, true), 32, 0.00001, "Fahrenheit");
        near(ObdPid.byId("speed").displayValue(100, true), 62.1371192, 0.00001, "mph");
        check(ObdPid.byId("fuel_rate").unit(true).equals("US gal/h"), "gallon convention explicit");
        check(ObdPid.byId("unknown") == null, "unknown not fabricated");
        check(ObdPid.byId("rpm").valueText(0, false).equals("0"), "real zero formatted");
        check(ObdPid.byId("rpm").valueText(Double.NaN, false).equals("—"), "invalid not zero");
        expect(IllegalArgumentException.class,
                () -> ObdPid.byId("rpm").decode(new byte[] {0x41, 0x0C, 0x01}));
    }

    private static void reconnectPolicy() {
        ObdReconnectPolicy policy = new ObdReconnectPolicy();
        check(!policy.begin(0, true, true, true, false), "auto-connect does not run outside Vehicle");
        policy.enter();
        check(!policy.begin(0, false, true, true, false), "automatic preference must be enabled");
        check(!policy.begin(0, true, false, true, false), "default adapter required");
        check(!policy.begin(0, true, true, false, false),
                "automatic connection waits for radio and permission");
        check(!policy.begin(0, true, true, true, true), "no auto-connect while scanning or connected");
        check(policy.begin(0, true, true, true, false), "eligible default connects on entry");
        policy.enter();
        check(!policy.begin(1, true, true, true, false),
                "duplicate lifecycle resume does not restart connection");
        check(!policy.begin(4999, true, true, true, false), "retry budget enforces initial delay");
        check(policy.begin(5000, true, true, true, false), "second fresh connection attempt");
        check(!policy.begin(19999, true, true, true, false), "later retry delay enforced");
        check(policy.begin(20000, true, true, true, false), "third fresh connection attempt");
        check(policy.exhausted() && !policy.begin(60000, true, true, true, false),
                "three-attempt visit limit");
        policy.leave();
        check(!policy.begin(60001, true, true, true, false), "leaving Vehicle cancels automatic connection");
        policy.enter();
        check(policy.begin(60002, true, true, true, false),
                "returning to Vehicle starts a new bounded visit");
        policy.suppress();
        check(policy.suppressed() && !policy.begin(90000, true, true, true, false),
                "manual disconnect or scan wins");
        policy.reset();
        check(policy.begin(90001, true, true, true, false),
                "explicit re-enable or default change permits connection");
    }

    private static void allowlist() {
        ObdVehicleProfile toyota = ObdProfiles.byId("toyota-1gd-130kw");
        for (String allowed : new String[] {"AT Z", "ATI", "ATE0", "ATL0", "ATS1", "ATH1", "ATSP0", "ATDP",
                     "ATRV", "0100", "0120", "0140", "0160", "0180", "01A0", "01C0", "01E0", "010C", "0178",
                     "03", "07", "0a"})
            check(ElmProtocol.allowedCommand(allowed) != null, "standard allowed " + allowed);
        for (String allowed :
                new String[] {"ATSH7E0", "ATSH7DF", "2182", "2138", "2185", "2104", "2105", "213D"}) {
            check(ElmProtocol.allowedCommand(allowed, toyota).equals(allowed), "profile allowed " + allowed);
            expect(IllegalArgumentException.class, () -> ElmProtocol.allowedCommand(allowed));
        }
        for (String denied : new String[] {"04", "0400", "0902", "22F190", "ATSH7E1", "ATSH700", "2183",
                     "2151", "018B", "ATMA", "ATD", "ATCV1234", "03 04", "010C\r04", "010C\n", "010C1",
                     "01FF", "0101", "", "ATZ;04"}) {
            expect(IllegalArgumentException.class, () -> ElmProtocol.allowedCommand(denied));
            expect(IllegalArgumentException.class, () -> ElmProtocol.allowedCommand(denied, toyota));
        }
    }

    private static void parsing() throws Exception {
        ElmProtocol.Reply reply =
                ElmProtocol.parse("010C\rSEARCHING...\r7E9 04 41 0C 0F A0\r7E8 04 41 0C 00 00\r", "010C");
        near(ObdPid.byId("rpm").decode(reply.pid("7E8", 0x0C).data), 0, 0, "selected ECU real zero");
        check(reply.pid("7E9", 0x0C) != null, "multiple ECUs retained separately");
        reply = ElmProtocol.parse("7E804410C1AF8", "010C");
        near(ObdPid.byId("rpm").decode(reply.pid("7E8", 0x0C).data), 1726, 0, "compact CAN11");
        for (String line : new String[] {
                     "18DAF110 04 41 0C 1A F8", "18 DA F1 10 04 41 0C 1A F8", "18DAF11004410C1AF8"}) {
            reply = ElmProtocol.parse(line, "010C");
            near(ObdPid.byId("rpm").decode(reply.pid("18DAF110", 0x0C).data), 1726, 0, "CAN29 " + line);
        }
        reply = ElmProtocol.parse("7E8 8 04 41 0C 1A F8 00 00 00", "010C");
        check(reply.pid("7E8", 0x0C).data.length == 4, "DLC and padding removed");
        reply = ElmProtocol.parse("48 6B 10 41 0C 1A F8 00", "010C");
        near(ObdPid.byId("rpm").decode(reply.pid("486B10", 0x0C).data), 1726, 0,
                "legacy header checksum removed");
        reply = ElmProtocol.parse("41 05 28", "0105");
        near(ObdPid.byId("coolant").decode(reply.pid("ECU", 0x05).data), 0, 0, "headerless zero coolant");
        reply = ElmProtocol.parse("NO DATA", "010C");
        check(reply.messages.isEmpty() && reply.error.equals("NO DATA"), "no data stays absent");
        reply = ElmProtocol.parse("7E8 03 7F 01 12", "010C");
        check(reply.pid("7E8", 0x0C) == null && reply.error != null, "negative response not sensor");
        reply = ElmProtocol.parse(
                maskLine("7E9", 0, 0x10) + "\r" + maskLine("7E8", 0, 0x05, 0x0C, 0x20), "0100");
        Map<String, Set<Integer>> masks = ElmProtocol.supported(reply, 0);
        check(masks.get("7E8").containsAll(Arrays.asList(5, 12, 32)), "bitmap boundaries and continuation");
        check(!masks.get("7E8").contains(16) && masks.get("7E9").contains(16), "bitmap per ECU, no union");
        reply = ElmProtocol.parse("7E8 06 43 02 01 33 D0 16", "03");
        check(ElmProtocol.troubleCodes(reply.messages.get(0), 3).equals(Arrays.asList("P0133", "U1016")),
                "CAN DTC count decoded");
        reply = ElmProtocol.parse("48 6B 10 43 01 33 D0 16 00 00 00", "03");
        check(ElmProtocol.troubleCodes(reply.messages.get(0), 3).equals(Arrays.asList("P0133", "U1016")),
                "legacy DTC pairs without count");
        reply = ElmProtocol.parse("7E8 02 43 00", "03");
        check(ElmProtocol.troubleCodes(reply.messages.get(0), 3).isEmpty(), "valid empty DTC category");
        reply = ElmProtocol.parse("7E8 04 43 03 01 33", "03");
        check(ElmProtocol.troubleCodes(reply.messages.get(0), 3) == null,
                "truncated DTC count not clean scan");
        reply = ElmProtocol.parse("43", "03");
        check(ElmProtocol.troubleCodes(reply.messages.get(0), 3) == null,
                "service byte alone is not a clean scan");
        reply = ElmProtocol.parse("7E8 10 0C 43 05 01 33 02 10\r7E9 10 0A 43 04 03 00 04 00\r"
                        + "7E8 21 C1 23 80 01 00 02 00\r7E9 21 05 00 06 00 00 00 00",
                "03");
        check(reply.messages.size() == 2 && reply.error == null, "interleaved ISO-TP complete per source");
        check(ElmProtocol.troubleCodes(reply.messages.get(0), 3)
                        .equals(Arrays.asList("P0133", "P0210", "U0123", "B0001", "P0002")),
                "multi-frame codes");
        reply = ElmProtocol.parse("7E8 10 0C 43 05 01 33 02 10\r7E8 22 C1 23 80 01 00 02 00", "03");
        check(reply.messages.isEmpty() && reply.error != null, "out of order discarded");
        reply = ElmProtocol.parse("00A\r0: 43 04 01 33 02 10\r1: C1 23 80 01", "03");
        check(reply.messages.size() == 1 && ElmProtocol.troubleCodes(reply.messages.get(0), 3).size() == 4,
                "numbered ELM formatting");
    }

    private static void framing() throws Exception {
        FakeLink link = new FakeLink();
        link.chunkSize = 2;
        link.responses.put("010C", ">\r010C\r7E8 04 41 0C 1A F8\r>\r");
        link.open();
        ElmProtocol elm = new ElmProtocol(link, () -> false);
        near(ObdPid.byId("rpm").decode(elm.query("010C", 1000).pid("7E8", 0x0C).data), 1726, 0,
                "fragmented prompt and initial idle prompt");
        link.responses.put("010C", "NO DATA\r>");
        check(elm.query("010C", 1000).error.equals("NO DATA"), "prompt-bound no data");
        int before = link.commands.size();
        expect(IllegalArgumentException.class, () -> elm.query("04", 1000));
        check(link.commands.size() == before, "rejected command never reaches transport");
        link.responses.put("010C", "");
        long start = System.nanoTime();
        expect(SocketTimeoutException.class, () -> elm.query("010C", 60));
        check((System.nanoTime() - start) / 1000000 < 500, "deadline bounded");
        link.responses.put("010C", repeat('A', ElmProtocol.MAX_RESPONSE + 1));
        expect(IOException.class, () -> elm.query("010C", 1000));
        link.chunkSize = 512;
        link.responses.put("010C", "NO DATA\r>7E8 04 41 0C 00 00\r>");
        expect(IOException.class, () -> elm.query("010C", 1000));
        link.close();
        expect(IOException.class, () -> elm.query("010C", 1000));
    }

    private static void engine() throws Exception {
        FakeLink link = profile();
        List<ObdEngine.Snapshot> snapshots = new CopyOnWriteArrayList<>();
        ObdEngine engine = new ObdEngine(snapshots::add);
        engine.connect(link);
        await(() -> engine.snapshot().readings.size() == 3, 10000, "engine readings");
        ObdEngine.Snapshot ready = engine.snapshot();
        check(ready.phase == ObdEngine.Phase.MONITORING && ready.ecu.equals("7E8"), "preferred engine ECU");
        check(ready.supported.equals(
                      new java.util.LinkedHashSet<>(Arrays.asList("rpm", "coolant", "voltage"))),
                "selected ECU supported IDs");
        near(ready.readings.get("rpm").value, 0, 0, "zero remains live reading");
        check(!link.commands.contains("0110") && !link.commands.contains("0111"),
                "other ECU-only sensors never polled");
        check(link.commands.contains("0120") && link.commands.contains("0140")
                        && !link.commands.contains("0160"),
                "per-ECU mask continuation followed");
        check(!link.commands.contains("03") && !link.commands.contains("07") && !link.commands.contains("0A"),
                "faults only on request");
        check(snapshots.stream().anyMatch(s -> s.phase == ObdEngine.Phase.ADAPTER_READY && s.ecu.isEmpty()),
                "adapter ready distinct from ECU connected");
        expect(UnsupportedOperationException.class, () -> ready.readings.clear());
        expect(UnsupportedOperationException.class, () -> ready.readings.get("rpm").history.add(99.0));
        engine.pause();
        Thread.sleep(350); // A request already in flight may finish; no further request starts.
        int pausedCount = link.commands.size();
        engine.readFaults();
        Thread.sleep(650);
        check(engine.snapshot().phase == ObdEngine.Phase.PAUSED && link.commands.size() == pausedCount,
                "pause stops polling and deferred fault reads");
        engine.resume();
        await(() -> engine.snapshot().faultsLoaded, 5000, "explicit fault scan");
        check(engine.snapshot().faults.size() == 3, "fault categories read");
        check(engine.snapshot().faultsAttempted && engine.snapshot().faultStatus.contains("Permanent: read"),
                "fault scan state explicit");
        check(engine.snapshot().faults.stream().anyMatch(
                      f -> f.code.equals("P0210") && f.category.equals("Permanent")),
                "permanent category kept");
        link.responses.put("07", "NO DATA\r>");
        engine.readFaults();
        await(()
                        -> engine.snapshot().faultStatus.contains("Pending: no response")
                        && !engine.snapshot().status.startsWith("Reading fault"),
                5000, "partial fault scan");
        check(!engine.snapshot().faultsLoaded && engine.snapshot().faults.size() == 3,
                "partial scan keeps real codes without claiming clear categories");
        long previousStamp = engine.snapshot().readings.get("rpm").timestampMillis;
        link.failRpm = true;
        await(() -> engine.snapshot().readings.get("rpm").error != null, 5000, "sensor no data state");
        ObdEngine.Reading failed = engine.snapshot().readings.get("rpm");
        check(failed.timestampMillis == previousStamp && failed.value == 0,
                "failure retains actual value and sample age");
        for (int i = 1; i < link.writeTimes.size(); i++)
            check(link.writeTimes.get(i) - link.writeTimes.get(i - 1) >= 235000000L, "request rate floor");
        engine.disconnect();
        check(link.closed && engine.snapshot().phase == ObdEngine.Phase.DISCONNECTED,
                "disconnect immediately closes link");
        int count = link.commands.size();
        Thread.sleep(350);
        check(link.commands.size() == count && engine.snapshot().phase == ObdEngine.Phase.DISCONNECTED,
                "cancelled generation never resumes");
        engine.close();
    }

    private static void mixedEcuFaultReplies() throws Exception {
        FakeLink link = profile();
        link.responses.put("03", "7E8 02 43 00\r7E9 03 7F 03 11\r>");
        link.responses.put("07", "7E8 02 47 00\r7E9 03 7F 07 11\r>");
        link.responses.put("0A", "7E8 02 4A 00\r7E9 03 7F 0A 11\r>");
        ObdEngine engine = new ObdEngine(null);
        try {
            engine.connect(link);
            await(() -> engine.snapshot().phase == ObdEngine.Phase.MONITORING, 8000, "mixed ECU ready");
            engine.readFaults();
            await(()
                            -> engine.snapshot().faultStatus.contains("Permanent: read")
                            && !engine.snapshot().status.startsWith("Reading fault"),
                    5000, "mixed ECU scan finished");
            check(engine.snapshot().faultsLoaded && engine.snapshot().faults.isEmpty(),
                    "another ECU rejection must not invalidate the selected ECU's valid empty code results");
            check(engine.snapshot().faultResults.size() == 3
                            && engine.snapshot().faultResults.stream().allMatch(
                                    r -> r.state == ObdFaultResult.State.READ),
                    "selected ECU category results retained independently");
            expect(UnsupportedOperationException.class, () -> engine.snapshot().faultResults.clear());
            link.responses.put("07", "NO DATA\r>");
            link.responses.put("0A", "7E8 03 7F 0A 11\r>");
            engine.readFaults();
            await(()
                            -> engine.snapshot().faultStatus.contains("Permanent: not supported")
                            && !engine.snapshot().status.startsWith("Reading fault"),
                    5000, "partial empty category results");
            check(!engine.snapshot().faultsLoaded && engine.snapshot().faults.isEmpty(),
                    "no data never becomes an all-clear");
            check(engine.snapshot().faultResults.get(0).state == ObdFaultResult.State.READ
                            && engine.snapshot().faultResults.get(1).state == ObdFaultResult.State.NO_RESPONSE
                            && engine.snapshot().faultResults.get(2).state
                                    == ObdFaultResult.State.NOT_SUPPORTED,
                    "valid empty, no response and explicit unsupported are distinct");
            link.responses.put("0100", maskLine("7E9", 0, 5, 12) + "\r>");
            engine.refreshSensors();
            await(()
                            -> engine.snapshot().ecu.equals("7E9")
                            && engine.snapshot().phase == ObdEngine.Phase.MONITORING,
                    5000, "rediscovery selects another ECU");
            check(!engine.snapshot().faultsAttempted && !engine.snapshot().faultsLoaded
                            && engine.snapshot().faultResults.isEmpty() && engine.snapshot().faults.isEmpty(),
                    "a newly selected ECU cannot inherit the previous ECU's code scan");
        } finally {
            engine.close();
        }
    }

    private static void faultResults() throws Exception {
        String[] replies = {"NO DATA", "7E8 03 7F 03 11", "7E8 03 7F 03 22", "7E8 03 7F 03 78",
                "7E9 02 43 00", "7E8 04 43 03 01 33", "7E8 02 43 00\rCAN ERROR",
                "7E8 03 7F 03 78\r7E8 02 43 00", "7E8 02 43 00\r7E8 03 7F 03 11",
                "7E8 02 43 00\r7E9 03 7F 03 11", "7E8 02 43 00\r7E8 03 7F 07 11"};
        ObdFaultResult.State[] expected = {ObdFaultResult.State.NO_RESPONSE,
                ObdFaultResult.State.NOT_SUPPORTED, ObdFaultResult.State.DECLINED,
                ObdFaultResult.State.INCOMPLETE, ObdFaultResult.State.OTHER_ECU,
                ObdFaultResult.State.INCOMPLETE, ObdFaultResult.State.ERROR, ObdFaultResult.State.READ,
                ObdFaultResult.State.ERROR, ObdFaultResult.State.READ, ObdFaultResult.State.READ};
        for (int i = 0; i < replies.length; i++) {
            ObdFaultResult result = ObdFaultResult.decode(ElmProtocol.parse(replies[i], "03"), "7E8", 3);
            check(result.state == expected[i], "scoped code outcome " + i);
            check(result.state == ObdFaultResult.State.READ || !result.label().equals("No codes reported"),
                    "unknown category cannot claim no codes " + i);
        }
        ObdFaultResult result =
                ObdFaultResult.decode(ElmProtocol.parse("7E8 04 43 01 01 33", "03"), "7E8", 3);
        check(result.count == 1 && result.label().equals("1 code reported"),
                "reported code count belongs to selected ECU");
        check(ObdFaultResult.reading(7).interrupted().state == ObdFaultResult.State.INTERRUPTED,
                "interrupted request not left reading");
        check(ObdFaultResult.waiting(10).interrupted().state == ObdFaultResult.State.NOT_READ,
                "unrequested category stays not read");
    }

    private static void interruptedFaultScan() throws Exception {
        FakeLink link = profile();
        link.responses.put("03", "7E8 04 43 01 01 33\r>");
        link.responses.put("07", "");
        ObdEngine engine = new ObdEngine(null);
        try {
            engine.connect(link);
            await(()
                            -> engine.snapshot().phase == ObdEngine.Phase.MONITORING,
                    8000, "interruption ECU ready");
            engine.readFaults();
            await(()
                            -> engine.snapshot().faultResults.size() == 3
                            && engine.snapshot().faultResults.get(1).state == ObdFaultResult.State.READING,
                    5000, "pending category reading");
            engine.disconnect();
            ObdEngine.Snapshot snapshot = engine.snapshot();
            check(snapshot.phase == ObdEngine.Phase.DISCONNECTED && !snapshot.faultsLoaded
                            && snapshot.faults.size() == 1 && snapshot.faults.get(0).code.equals("P0133"),
                    "disconnect preserves already reported codes without completing scan");
            check(snapshot.faultResults.get(0).state == ObdFaultResult.State.READ
                            && snapshot.faultResults.get(1).state == ObdFaultResult.State.INTERRUPTED
                            && snapshot.faultResults.get(2).state == ObdFaultResult.State.NOT_READ,
                    "disconnect marks only the active code category interrupted");
        } finally {
            engine.close();
        }
    }

    private static void noEcu() throws Exception {
        FakeLink link = profile();
        link.responses.put("0100", "NO DATA\r>");
        ObdEngine engine = new ObdEngine(null);
        engine.connect(link);
        await(()
                        -> engine.snapshot().phase == ObdEngine.Phase.ADAPTER_READY
                        && engine.snapshot().status.contains("no ECU reply"),
                7000, "adapter without ECU");
        check(engine.snapshot().ecu.isEmpty() && engine.snapshot().supported.isEmpty()
                        && engine.snapshot().readings.isEmpty(),
                "no ECU never fabricates readings");
        engine.disconnect();
    }

    private static void refreshAndHistory() throws Exception {
        ObdEngine engine = new ObdEngine(null);
        FakeLink link = profile();
        check(engine.refreshCycleMillis() == 0, "default refresh preserves adaptive timing");
        expect(IllegalArgumentException.class, () -> engine.setRefreshCycleMillis(777));
        try {
            engine.connect(link);
            await(() -> engine.snapshot().readings.size() == 3, 8000, "samples before refresh change");
            engine.pause();
            Thread.sleep(350);
            List<ObdHistory.Minute> history = engine.minuteHistory("rpm", System.currentTimeMillis());
            check(history.stream().anyMatch(p -> p.samples > 0 && p.average == 0),
                    "real zero recorded in minute history");
            engine.setRefreshCycleMillis(5000);
            int count = link.commands.size();
            engine.resume();
            Thread.sleep(600);
            check(engine.refreshCycleMillis() == 5000 && link.commands.size() == count,
                    "slower refresh defers already sampled sensors");
            engine.setRefreshCycleMillis(0);
            await(() -> link.commands.size() > count, 2500, "live refresh change resumes due reads");
            engine.disconnect();
            check(engine.minuteHistory("rpm", System.currentTimeMillis())
                            .stream()
                            .anyMatch(p -> p.samples > 0),
                    "disconnect retains history for local inspection");
            engine.connect(profile());
            check(engine.minuteHistory("rpm", System.currentTimeMillis())
                            .stream()
                            .allMatch(p -> p.samples == 0),
                    "new connection starts a separate history");
        } finally {
            engine.close();
        }
    }

    private static void cancelOpen() throws Exception {
        FakeLink blocked = profile();
        blocked.blockOpen = true;
        ObdEngine engine = new ObdEngine(null);
        engine.connect(blocked);
        await(() -> blocked.openStarted, 1000, "blocked open started");
        long start = System.nanoTime();
        engine.disconnect();
        check((System.nanoTime() - start) / 1000000 < 150 && blocked.closed,
                "disconnect cancels blocked open promptly");
        FakeLink next = profile();
        engine.connect(next);
        await(() -> engine.snapshot().phase == ObdEngine.Phase.MONITORING, 7000, "replacement generation");
        check(engine.snapshot().phase == ObdEngine.Phase.MONITORING && next.openStarted,
                "stale cancelled session cannot replace snapshot");
        engine.close();
    }

    private static void fatalTimeoutAndCancelRead() throws Exception {
        FakeLink stalled = profile();
        ObdEngine engine = new ObdEngine(null);
        engine.connect(stalled);
        await(() -> engine.snapshot().readings.containsKey("rpm"), 8000, "sample before timeout");
        stalled.responses.put("010C", ""); // No prompt: retrying would risk consuming a late old response.
        await(()
                        -> stalled.commands.size() > 0
                        && stalled.commands.get(stalled.commands.size() - 1).equals("010C")
                        && stalled.waitingRead,
                5000, "stalled PID read");
        long timestamp = engine.snapshot().readings.get("rpm").timestampMillis;
        await(() -> engine.snapshot().phase == ObdEngine.Phase.ERROR, 5000, "unframed timeout is terminal");
        await(() -> stalled.closed, 1000, "timeout closes link");
        int commands = stalled.commands.size();
        stalled.inject("7E8 04 41 0C FF FF\r>"); // A late response cannot become a new reading.
        engine.resume();
        engine.refreshSensors();
        engine.readFaults();
        Thread.sleep(350);
        check(engine.snapshot().phase == ObdEngine.Phase.ERROR && stalled.commands.size() == commands,
                "no retry or raw recovery after lost prompt framing");
        check(engine.snapshot().readings.get("rpm").timestampMillis == timestamp
                        && engine.snapshot().readings.get("rpm").value == 0,
                "late reply cannot refresh sample");
        engine.disconnect();

        FakeLink blocked = profile();
        blocked.responses.put("ATI", "");
        engine.connect(blocked);
        await(() -> blocked.commands.contains("ATI") && blocked.waitingRead, 2000, "blocked read started");
        long start = System.nanoTime();
        engine.disconnect();
        check(blocked.closed && (System.nanoTime() - start) / 1000000 < 150,
                "disconnect closes blocked read promptly");
        Thread.sleep(100);
        check(engine.snapshot().phase == ObdEngine.Phase.DISCONNECTED,
                "blocked read cancellation does not publish error");
        engine.close();
    }

    private static void toyotaDefinitions() throws Exception {
        byte[] atf = {0x61, (byte) 0x82, 0x4E, (byte) 0xC0, 0x56, 0x40};
        near(ObdPid.byId("atf_pan").decode(atf), 38, 0, "Toyota ATF pan source capture");
        near(ObdPid.byId("atf_converter").decode(atf), 46, 0, "Toyota ATF converter source capture");
        expect(IllegalArgumentException.class,
                () -> ObdPid.byId("atf_pan").decode(new byte[] {0x41, (byte) 0x82, 0x4E, 0, 0, 0}));
        expect(IllegalArgumentException.class,
                () -> ObdPid.byId("atf_converter").decode(new byte[] {0x61, (byte) 0x82, 0x4E}));
        byte[] soot = {0x61, 0x38, 0, 0, 0, 0, 0, 0, 0, 0, 0, 65};
        near(ObdPid.byId("dpf_soot").decode(soot), 130, 0, "DPF accumulation above 100 preserved");
        byte[] gear = {0x61, (byte) 0x85, 3, (byte) 0x80};
        near(ObdPid.byId("reported_gear").decode(gear), 3, 0, "reported gear");
        near(ObdPid.byId("converter_lock").decode(gear), 1, 0, "converter lock bit");
        near(ObdPid.byId("converter_lock").averageValue(.6, false), 60, 0,
                "lock history is percentage locked, not rounded state");
        check(ObdPid.byId("converter_lock").valueText(0, false).equals("Unlocked"), "unlock text");
        near(ObdPid.byId("reported_gear").averageValue(2.4, false), 2.4, 0,
                "fractional gear average preserved");
        byte[] egt = {0x41, 0x78, 7, 3, (byte) 0xE8, 7, (byte) 0xD0, 0x0B, (byte) 0xB8, 0, 0};
        near(ObdPid.byId("egt_1").decode(egt), 60, 0, "EGT1");
        near(ObdPid.byId("egt_2").decode(egt), 160, 0, "EGT2");
        near(ObdPid.byId("egt_3").decode(egt), 260, 0, "EGT3");
        egt[2] = 1;
        expect(IllegalArgumentException.class, () -> ObdPid.byId("egt_2").decode(egt));
        ElmProtocol.Reply reply = ElmProtocol.parse(frame("7E8", soot) + "\r7E9 03 7F 21 12", "2138");
        near(ObdPid.byId("dpf_soot").decode(reply.sensor("7E8", 0x21, 0x38).data), 130, 0,
                "Toyota ISO-TP with other ECU rejection");
        check(reply.sensor("7E8", 0x21, 0x82) == null, "wrong Toyota identifier cannot match");
    }

    private static void dpfDefinitions() throws Exception {
        byte[] pressure = {0x41, 0x7A, 7, (byte) 0xFF, (byte) 0xF8, 0x27, 0x10, 0x26, (byte) 0xAC};
        near(ObdPid.byId("dpf_pressure").decode(pressure), -.08, .000001,
                "SAE signed pressure preserves negative zero-offset reading");
        near(ObdPid.byId("dpf_inlet_pressure").decode(pressure), 100, 0, "inlet pressure");
        near(ObdPid.byId("dpf_outlet_pressure").decode(pressure), 99, 0, "outlet pressure");
        pressure[3] = 0x7F;
        pressure[4] = (byte) 0xF8;
        near(ObdPid.byId("dpf_pressure").decode(pressure), 327.6, .000001,
                "Toyota encoding conflict is not silently corrected");
        pressure[2] = 0;
        expect(IllegalArgumentException.class, () -> ObdPid.byId("dpf_pressure").decode(pressure));
        byte[] temp = {0x41, 0x7C, 3, 0x19, 0, 0x1B, 0x58};
        near(ObdPid.byId("dpf_inlet_temp").decode(temp), 600, 0, "DPF inlet temperature");
        near(ObdPid.byId("dpf_outlet_temp").decode(temp), 660, 0, "DPF outlet temperature");
        byte[] toyota = new byte[17];
        toyota[0] = 0x61;
        toyota[1] = 0x38;
        toyota[7] = (byte) 0x80;
        toyota[9] = (byte) 0x80;
        toyota[11] = 65;
        toyota[14] = (byte) 0x80;
        toyota[15] = 100;
        toyota[16] = 2;
        near(ObdPid.byId("dpf_restriction").decode(toyota), 0, .000001, "candidate restriction offset");
        near(ObdPid.byId("dpf_pressure_correction").decode(toyota), 0, .000001,
                "pressure correction separate");
        near(ObdPid.byId("dpf_fuel_feedback").decode(toyota), 1, .000001, "exhaust fuel feedback");
        byte[] legacy = {0x61, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x7F, (byte) 0xF8};
        near(ObdPid.byId("dpf_pressure_toyota").decode(legacy), -.08, .000001,
                "earlier Toyota offset pressure");
        for (ObdVehicleProfile profile : ObdProfiles.all()) {
            for (ObdPid pid : profile.sensors) {
                if (!pid.dpf())
                    continue;
                check(ElmProtocol.allowedCommand(pid.command(), profile).equals(pid.command()),
                        "Fixed DPF read allowlisted in " + profile.id + " for " + pid.id);
                expect(IllegalArgumentException.class, () -> pid.decode(new byte[0]));
                expect(IllegalArgumentException.class,
                        () -> pid.decode(new byte[] {0x7F, (byte) pid.service, 0x12}));
            }
        }
        for (String command :
                new String[] {"0800", "3101", "3000", "2139", "2105", "213D", "22F48B", "ATSH700", "04"})
            expect(IllegalArgumentException.class, () -> ElmProtocol.allowedCommand(command));
    }

    private static FakeLink toyotaLink() {
        FakeLink link = profile();
        link.responses.put("0140", maskLine("7E8", 64, 66, 96) + "\r>");
        link.responses.put("0160", maskLine("7E8", 96, 120) + "\r>");
        link.responses.put("0178",
                frame("7E8",
                        new byte[] {0x41, 0x78, 7, 3, (byte) 0xE8, 7, (byte) 0xD0, 0x0B, (byte) 0xB8, 0, 0})
                        + "\r>");
        link.responses.put("2182", "7E8 06 61 82 4E C0 56 40\r>");
        link.responses.put(
                "2138", frame("7E8", new byte[] {0x61, 0x38, 0, 0, 0, 0, 0, 0, 0, 0, 0, 65}) + "\r>");
        link.responses.put("2185", "7E8 04 61 85 03 80\r>");
        link.responses.put("2104",
                frame("7E8", new byte[] {0x61, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80, 0}) + "\r>");
        link.responses.put(
                "2105", frame("7E8", new byte[] {0x61, 5, 0, 0x0D, (byte) 0xAC, 0x0C, (byte) 0xE4}) + "\r>");
        byte[] injectors = new byte[21];
        injectors[0] = 0x61;
        injectors[1] = 0x3D;
        injectors[17] = (byte) 128;
        injectors[18] = (byte) 144;
        injectors[19] = (byte) 112;
        injectors[20] = (byte) 160;
        link.responses.put("213D", frame("7E8", injectors) + "\r>");
        return link;
    }

    private static void toyotaProfile() throws Exception {
        FakeLink link = toyotaLink();
        try (ObdEngine engine = new ObdEngine(null)) {
            engine.setProfile(ObdProfiles.byId("toyota-1gd-130kw"));
            engine.connect(link);
            await(()
                            -> engine.snapshot().phase == ObdEngine.Phase.MONITORING
                            && engine.snapshot().readings.containsKey("egt_3"),
                    12000, "Toyota discovery and EGT ready");
            ObdEngine.Snapshot state = engine.snapshot();
            for (String id : new String[] {"atf_pan", "atf_converter", "dpf_soot", "reported_gear",
                         "converter_lock", "fuel_rail_actual", "fuel_rail_target", "injector_1", "injector_2",
                         "injector_3", "injector_4"})
                check(state.supported.contains(id) && state.readings.containsKey(id),
                        "responding channel selectable " + id);
            check(state.sensorChecks.get("fuel_rail_actual").detail.contains("test definition"),
                    "enhanced reply not called reference validated");
            check(!link.commands.contains("015C") && !link.commands.contains("2151")
                            && !link.commands.contains("018B"),
                    "removed oil and regeneration requests never sent");
            expect(UnsupportedOperationException.class, () -> state.sensorChecks.clear());
            check(link.header.equals("7DF"), "functional addressing restored after discovery");
            engine.readFaults();
            await(() -> engine.snapshot().faultsLoaded, 8000, "faults still read after Toyota addressing");
            check(engine.minuteHistory("atf_pan", System.currentTimeMillis())
                            .stream()
                            .anyMatch(p -> p.samples > 0),
                    "Toyota history records real decoded samples");
            engine.setProfile(ObdProfiles.standard());
            check(link.closed && engine.snapshot().phase == ObdEngine.Phase.DISCONNECTED,
                    "profile switch cancels old session");
            check(engine.snapshot().readings.isEmpty() && engine.snapshot().sensorChecks.isEmpty(),
                    "profile switch clears prior values and support");
        }
        link = toyotaLink();
        link.responses.put("2182", "7E9 06 61 82 4E C0 56 40\r>");
        link.responses.put("2138", "7E8 10 0C 61 38 00 00 00 00\r>");
        link.responses.put("2185", "7E8 03 7F 21 12\r>");
        try (ObdEngine engine = new ObdEngine(null)) {
            engine.setProfile(ObdProfiles.byId("toyota-1gd-130kw"));
            engine.connect(link);
            await(()
                            -> engine.snapshot().phase == ObdEngine.Phase.MONITORING,
                    10000, "Toyota unavailable discovery completes");
            ObdEngine.Snapshot state = engine.snapshot();
            check(state.sensorChecks.get("atf_pan").state == ObdSensorCheck.State.NO_RESPONSE,
                    "wrong ECU cannot confirm ATF");
            check(state.sensorChecks.get("dpf_soot").state == ObdSensorCheck.State.INCOMPLETE,
                    "short ISO-TP cannot confirm DPF");
            check(state.sensorChecks.get("reported_gear").state == ObdSensorCheck.State.DECLINED,
                    "negative Toyota reply distinct");
            check(!state.sensorChecks.containsKey("oil_temp")
                            && !state.sensorChecks.containsKey("toyota_oil_temp"),
                    "removed oil channels absent from profile results");
            check(!state.supported.contains("atf_pan"), "failed channels not offered as available");
        }
        link = toyotaLink();
        link.responses.put("ATSH7E0", "?\r>");
        try (ObdEngine engine = new ObdEngine(null)) {
            engine.setProfile(ObdProfiles.byId("toyota-1gd-130kw"));
            engine.connect(link);
            await(()
                            -> engine.snapshot().phase == ObdEngine.Phase.MONITORING,
                    8000, "optional addressing failure preserves standard monitoring");
            check(engine.snapshot().sensorChecks.get("atf_pan").state == ObdSensorCheck.State.UNAVAILABLE,
                    "adapter addressing failure explicit");
            check(!link.commands.contains("2182"), "no Toyota request after rejected header");
        }
        link = toyotaLink();
        link.responses.put("ATSH7DF", "?\r>");
        try (ObdEngine engine = new ObdEngine(null)) {
            engine.setProfile(ObdProfiles.byId("toyota-1gd-130kw"));
            engine.connect(link);
            await(()
                            -> engine.snapshot().phase == ObdEngine.Phase.ERROR,
                    8000, "restoration failure stops worker");
            int size = link.commands.size();
            Thread.sleep(350);
            check(link.commands.size() == size, "no further traffic after restore failure");
        }
        link = toyotaLink();
        link.responses.put("ATDP", "ISO 15765-4 (CAN 29/500)\r>");
        try (ObdEngine engine = new ObdEngine(null)) {
            engine.setProfile(ObdProfiles.byId("toyota-1gd-130kw"));
            engine.connect(link);
            await(()
                            -> engine.snapshot().phase == ObdEngine.Phase.MONITORING,
                    7000, "29bit standard monitoring preserved");
            check(!link.commands.contains("ATSH7E0")
                            && engine.snapshot().sensorChecks.get("atf_pan").state
                                    == ObdSensorCheck.State.NOT_APPLICABLE,
                    "11bit profile never applied to 29bit network");
        }
    }

    private static String frame(String source, byte[] data) {
        StringBuilder text = new StringBuilder();
        if (data.length <= 7) {
            text.append(String.format(Locale.ROOT, "%s %02X", source, data.length));
            for (byte v : data) text.append(String.format(Locale.ROOT, " %02X", v & 255));
            return text.toString();
        }
        text.append(String.format(
                Locale.ROOT, "%s %02X %02X", source, 0x10 | (data.length >> 8), data.length & 255));
        int offset = 0;
        while (offset < 6 && offset < data.length)
            text.append(String.format(Locale.ROOT, " %02X", data[offset++] & 255));
        int seq = 1;
        while (offset < data.length) {
            text.append(String.format(Locale.ROOT, "\r%s %02X", source, 0x20 | (seq++ & 15)));
            int used = 0;
            while (used++ < 7) {
                text.append(
                        String.format(Locale.ROOT, " %02X", offset < data.length ? data[offset++] & 255 : 0));
            }
        }
        return text.toString();
    }

    private static FakeLink profile() {
        FakeLink link = new FakeLink();
        link.responses.put(
                "0100", maskLine("7E9", 0, 0x10, 0x11) + "\r" + maskLine("7E8", 0, 5, 12, 32) + "\r>");
        link.responses.put("0120", maskLine("7E8", 32, 64) + "\r>");
        link.responses.put("0140", maskLine("7E8", 64, 66) + "\r>");
        link.responses.put("010C", "7E9 04 41 0C 1A F8\r7E8 04 41 0C 00 00\r>");
        link.responses.put("0105", "7E8 03 41 05 82\r>");
        link.responses.put("0142", "7E8 04 41 42 35 E8\r>");
        link.responses.put("03", "7E8 06 43 02 01 33 D0 16\r>");
        link.responses.put("07", "7E8 02 47 00\r>");
        link.responses.put("0A", "7E8 04 4A 01 02 10\r>");
        return link;
    }

    private static String maskLine(String source, int base, int... pids) {
        long mask = 0;
        for (int pid : pids) mask |= 1L << (32 - (pid - base));
        return String.format(Locale.ROOT, "%s 06 41 %02X %02X %02X %02X %02X", source, base,
                (mask >> 24) & 255, (mask >> 16) & 255, (mask >> 8) & 255, mask & 255);
    }

    private static final class FakeLink implements ObdLink {
        final Map<String, String> responses = Collections.synchronizedMap(new LinkedHashMap<>());
        final List<String> commands = new CopyOnWriteArrayList<>();
        final List<Long> writeTimes = new CopyOnWriteArrayList<>();
        volatile boolean closed, blockOpen, openStarted, failRpm, waitingRead;
        String header = "7DF";
        int chunkSize = 512;
        private byte[] response = new byte[0];
        private int offset;
        @Override
        public synchronized void open() throws IOException {
            openStarted = true;
            while (blockOpen && !closed) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Cancelled");
                }
            }
            if (closed)
                throw new IOException("Closed");
        }
        @Override
        public synchronized void write(byte[] bytes) throws IOException {
            if (closed)
                throw new IOException("Closed");
            String command = new String(bytes, StandardCharsets.US_ASCII).trim();
            // This independent assertion makes any future accidental write command fail at the fake transport
            // too.
            if (!(command.startsWith("AT")
                        || command.matches("01[0-9A-F]{2}|21(82|38|85|04|05|3D)|03|07|0A")))
                throw new AssertionError("Unexpected vehicle command " + command);
            if (command.startsWith("21") && !header.equals("7E0"))
                throw new AssertionError("Toyota request without physical header");
            if ((command.startsWith("01") || command.matches("03|07|0A")) && !header.equals("7DF"))
                throw new AssertionError("Standard request before restoring functional header");
            commands.add(command);
            writeTimes.add(System.nanoTime());
            String value = responses.get(command);
            if (failRpm && command.equals("010C"))
                value = "NO DATA\r>";
            if (value == null) {
                if (command.equals("ATZ") || command.equals("ATI"))
                    value = "ELM327 v2.2\r>";
                else if (command.equals("ATDP"))
                    value = "ISO 15765-4 (CAN 11/500)\r>";
                else if (command.startsWith("AT"))
                    value = "OK\r>";
                else
                    value = "NO DATA\r>";
            }
            if (command.equals("ATSH7E0") && value.contains("OK"))
                header = "7E0";
            if (command.equals("ATSH7DF") && value.contains("OK"))
                header = "7DF";
            response = value.getBytes(StandardCharsets.US_ASCII);
            offset = 0;
            notifyAll();
        }
        @Override
        public synchronized int read(byte[] target, int start, int length, long timeoutMs)
                throws IOException {
            if (closed)
                throw new IOException("Closed");
            if (offset == response.length) {
                waitingRead = true;
                try {
                    wait(timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Cancelled");
                } finally {
                    waitingRead = false;
                }
                if (closed)
                    throw new IOException("Closed");
            }
            int count = Math.min(Math.min(length, chunkSize), response.length - offset);
            System.arraycopy(response, offset, target, start, count);
            offset += count;
            return count;
        }
        @Override
        public String description() {
            return "Fake · no physical adapter";
        }
        synchronized void inject(String value) {
            response = value.getBytes(StandardCharsets.US_ASCII);
            offset = 0;
            notifyAll();
        }
        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    private interface Throwing {
        void run() throws Exception;
    }
    private static void expect(Class<? extends Throwable> type, Throwing action) {
        try {
            action.run();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (Throwable error) {
            if (!type.isInstance(error))
                throw new AssertionError("Expected " + type.getSimpleName(), error);
            assertions++;
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
        assertions++;
    }
    private static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance,
                message + ": got " + actual + ", expected " + expected);
    }
    private static void await(BooleanSupplier condition, long timeout, String message) throws Exception {
        long deadline = System.nanoTime() + timeout * 1000000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(15);
        check(condition.getAsBoolean(), message);
    }
    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
