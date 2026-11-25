package local.cc3.obd;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** One cancellable worker per adapter session; all vehicle requests are read-only. */
public final class ObdEngine implements Closeable {
    public enum Phase {
        DISCONNECTED,
        CONNECTING,
        ADAPTER_READY,
        FINDING_ECU,
        DISCOVERING,
        MONITORING,
        PAUSED,
        ERROR
    }
    public interface Listener {
        void onUpdate(Snapshot snapshot);
    }

    public static final class Reading {
        public final String id, source, error;
        public final double value;
        /** Wall-clock sample time. A failed read does not refresh the age of the last good value. */
        public final long timestampMillis;
        public final List<Double> history;
        private Reading(String id, double value, long timestampMillis, String source, String error,
                List<Double> history) {
            this.id = id;
            this.value = value;
            this.timestampMillis = timestampMillis;
            this.source = source;
            this.error = error;
            this.history = Collections.unmodifiableList(new ArrayList<>(history));
        }
    }

    public static final class Fault {
        public final String code, category, source;
        private Fault(String code, String category, String source) {
            this.code = code;
            this.category = category;
            this.source = source;
        }
    }

    public static final class Snapshot {
        public final Phase phase;
        public final String status, adapterInfo, protocol, transport, ecu, profileId;
        public final Map<String, Reading> readings;
        public final Set<String> supported;
        public final Map<String, ObdSensorCheck> sensorChecks;
        public final List<Fault> faults;
        public final List<ObdFaultResult> faultResults;
        /** True only after all three requested fault categories returned a valid response. */
        public final boolean faultsLoaded;
        public final boolean faultsAttempted;
        public final String faultStatus;
        public final double queriesPerSecond;
        private Snapshot(ObdVehicleProfile profile, Phase phase, String status, String adapterInfo,
                String protocol, String transport, String ecu, Map<String, Reading> readings,
                Set<String> supported, Map<String, ObdSensorCheck> sensorChecks, List<Fault> faults,
                boolean faultsLoaded, boolean faultsAttempted, String faultStatus,
                List<ObdFaultResult> faultResults, double queriesPerSecond) {
            this.profileId = profile.id;
            this.phase = phase;
            this.status = status;
            this.adapterInfo = adapterInfo;
            this.protocol = protocol;
            this.transport = transport;
            this.ecu = ecu;
            this.readings = Collections.unmodifiableMap(new LinkedHashMap<>(readings));
            this.supported = Collections.unmodifiableSet(new LinkedHashSet<>(supported));
            this.sensorChecks = Collections.unmodifiableMap(new LinkedHashMap<>(sensorChecks));
            this.faults = Collections.unmodifiableList(new ArrayList<>(faults));
            this.faultsLoaded = faultsLoaded;
            this.faultsAttempted = faultsAttempted;
            this.faultResults = Collections.unmodifiableList(new ArrayList<>(faultResults));
            this.faultStatus = faultStatus;
            this.queriesPerSecond = queriesPerSecond;
        }
    }

    private static final long MIN_QUERY_NANOS =
            250000000L; // At most four requests/s, including discovery and faults.
    private final Object lock = new Object();
    private final Listener listener;
    private volatile ObdVehicleProfile profile = ObdProfiles.standard();
    private volatile Snapshot current = empty(Phase.DISCONNECTED, "Adapter disconnected");
    private Session active;
    private long generation;
    private boolean closed;
    private volatile long refreshCycleMillis;
    private Map<String, ObdHistory> retainedHistory = Collections.emptyMap();

    private static final class Session {
        final long generation;
        final ObdLink link;
        final ObdVehicleProfile profile;
        final Map<String, Reading> readings = new LinkedHashMap<>();
        final Map<String, ObdSensorCheck> sensorChecks = new LinkedHashMap<>();
        final Set<String> supported = new LinkedHashSet<>();
        final Set<String> advertised = new LinkedHashSet<>();
        final Map<String, Long> nextDue = new LinkedHashMap<>();
        final Map<String, Integer> failures = new LinkedHashMap<>();
        final Deque<Long> queryTimes = new ArrayDeque<>();
        final long started = System.nanoTime();
        final List<Fault> faults = new ArrayList<>();
        final Map<String, ObdHistory> histories = new LinkedHashMap<>();
        final Map<Integer, ObdFaultResult> faultResults = new LinkedHashMap<>();
        volatile boolean cancelled;
        boolean paused, refresh, faultRequest, ready, faultsLoaded, faultsAttempted;
        Phase phase = Phase.CONNECTING;
        String status = "Connecting to adapter", adapterInfo = "", protocol = "", ecu = "", transport;
        String faultStatus = "Fault codes have not been requested";
        long lastQuery, lastEcuResponse;
        boolean physicalHeader, customAddressingUnavailable;
        double responseMillis = 250;
        Thread worker;
        ElmProtocol elm;
        Session(long generation, ObdLink link, ObdVehicleProfile profile) {
            this.generation = generation;
            this.link = link;
            this.profile = profile;
            this.transport = link.description();
        }
    }

    public ObdEngine(Listener listener) {
        this.listener = listener;
    }

    public void connect(ObdLink link) {
        if (link == null)
            throw new IllegalArgumentException("Adapter link is required");
        Session previous;
        Session next;
        synchronized (lock) {
            if (closed) {
                closeLink(link);
                throw new IllegalStateException("OBD engine is closed");
            }
            previous = active;
            if (previous != null)
                previous.cancelled = true;
            next = new Session(++generation, link, profile);
            active = next;
            retainedHistory = Collections.emptyMap();
            current = snapshotLocked(next);
            lock.notifyAll();
        }
        cancel(previous);
        publish(next);
        Thread worker = new Thread(() -> run(next), "cc3-obd-" + next.generation);
        worker.setDaemon(true);
        synchronized (lock) {
            if (active != next || next.cancelled) {
                closeLink(link);
                return;
            }
            next.worker = worker;
        }
        worker.start();
    }

    public void disconnect() {
        Session previous;
        Snapshot update;
        synchronized (lock) {
            previous = active;
            if (previous != null)
                previous.cancelled = true;
            if (previous != null)
                retainedHistory = new LinkedHashMap<>(previous.histories);
            active = null;
            generation++;
            Snapshot old = current;
            List<ObdFaultResult> results = interruptedResults(old.faultResults);
            current = update = new Snapshot(profile, Phase.DISCONNECTED, "Adapter disconnected",
                    old.adapterInfo, old.protocol, old.transport, old.ecu, old.readings, old.supported,
                    old.sensorChecks, old.faults, old.faultsLoaded, old.faultsAttempted,
                    old.faultsAttempted && !old.faultsLoaded ? faultSummary(results) : old.faultStatus,
                    results, 0);
            lock.notifyAll();
        }
        // Close before callback: a blocked Bluetooth read/open must be cancelled immediately.
        cancel(previous);
        synchronized (lock) {
            // A new connection may already have replaced this disconnected state.
            if (current == update && active == null)
                notifyListener(update);
        }
    }

    public void pause() {
        Session session;
        synchronized (lock) {
            session = active;
            if (session == null || session.phase == Phase.ERROR)
                return;
            session.paused = true;
            session.phase = Phase.PAUSED;
            session.status = "Monitoring paused · no new requests are sent";
            lock.notifyAll();
        }
        publish(session);
    }

    public void resume() {
        Session session;
        synchronized (lock) {
            session = active;
            if (session == null || session.phase == Phase.ERROR)
                return;
            session.paused = false;
            session.phase = session.ready ? Phase.MONITORING : Phase.FINDING_ECU;
            session.status = session.ready ? "Monitoring supported sensors" : "Looking for vehicle ECU";
            lock.notifyAll();
        }
        publish(session);
    }

    public void refreshSensors() {
        synchronized (lock) {
            if (active != null && active.phase != Phase.ERROR) {
                active.refresh = true;
                lock.notifyAll();
            }
        }
    }

    /** Explicit user request only. This never clears or changes a vehicle fault. */
    public void readFaults() {
        synchronized (lock) {
            if (active != null && active.phase != Phase.ERROR) {
                active.faultRequest = true;
                lock.notifyAll();
            }
        }
    }

    public Snapshot snapshot() {
        return current;
    }

    public long refreshCycleMillis() {
        return refreshCycleMillis;
    }
    public ObdVehicleProfile profile() {
        return profile;
    }

    /** A profile change cancels the old session before any new profile may issue requests. */
    public void setProfile(ObdVehicleProfile next) {
        setProfile(next, false);
    }

    /** A new vehicle starts a fresh session even when it shares the same profile. */
    public void setProfile(ObdVehicleProfile next, boolean vehicleChanged) {
        if (next == null)
            throw new IllegalArgumentException("Vehicle profile is required");
        Session previous;
        Snapshot update;
        synchronized (lock) {
            if (closed)
                throw new IllegalStateException("OBD engine is closed");
            if (profile == next && !vehicleChanged)
                return;
            previous = active;
            if (previous != null)
                previous.cancelled = true;
            active = null;
            generation++;
            profile = next;
            retainedHistory = Collections.emptyMap();
            current = update = empty(
                    Phase.DISCONNECTED, "Connect adapter to find sensors · Profile changed");
            lock.notifyAll();
        }
        cancel(previous);
        synchronized (lock) {
            if (current == update && active == null)
                notifyListener(update);
        }
    }

    /** Target minimum per-sensor interval; the global four-query/s limit still applies. */
    public void setRefreshCycleMillis(long millis) {
        if (millis != 0 && millis != 1000 && millis != 2000 && millis != 5000 && millis != 10000)
            throw new IllegalArgumentException("Unsupported refresh cycle");
        synchronized (lock) {
            refreshCycleMillis = millis;
            if (active != null) {
                long now = System.nanoTime(), wall = System.currentTimeMillis();
                for (String id : active.supported) {
                    Reading reading = active.readings.get(id);
                    long elapsed =
                            reading == null ? Long.MAX_VALUE : Math.max(0, wall - reading.timestampMillis);
                    long remaining =
                            reading == null ? 0 : Math.max(0, interval(active.profile.sensor(id)) - elapsed);
                    active.nextDue.put(id, now + remaining * 1000000L);
                }
            }
            lock.notifyAll();
        }
    }

    public List<ObdHistory.Minute> minuteHistory(String id, long nowMillis) {
        synchronized (lock) {
            ObdHistory history = (active == null ? retainedHistory : active.histories).get(id);
            return (history == null ? new ObdHistory() : history).minutes(nowMillis);
        }
    }

    private long interval(ObdPid pid) {
        return Math.max(pid.intervalMillis, refreshCycleMillis);
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
        disconnect();
    }

    private void run(Session session) {
        try {
            session.link.open();
            ensureActive(session);
            session.elm = new ElmProtocol(session.link, () -> session.cancelled, session.profile);
            initialise(session);
            while (isActive(session)) {
                awaitRunning(session);
                boolean refresh;
                synchronized (lock) {
                    refresh = session.refresh;
                    session.refresh = false;
                }
                if (!session.ready || refresh) {
                    if (!discover(session)) {
                        waitFor(session, 3000);
                        continue;
                    }
                }
                awaitRunning(session);
                boolean faults;
                synchronized (lock) {
                    faults = session.faultRequest;
                    session.faultRequest = false;
                }
                if (faults) {
                    loadFaults(session);
                    continue;
                }
                ObdPid selected = nextSensor(session);
                if (selected == null) {
                    waitFor(session, 250);
                    continue;
                }
                poll(session, selected);
                if (System.nanoTime() - session.lastEcuResponse > 15000000000L) {
                    synchronized (lock) {
                        session.ready = false;
                        session.phase = Phase.ADAPTER_READY;
                        session.status = "Adapter connected · ECU readings unavailable; checking ignition";
                    }
                    publish(session);
                    waitFor(session, 3000);
                }
            }
        } catch (IOException | RuntimeException error) {
            if (isActive(session)) {
                synchronized (lock) {
                    session.phase = Phase.ERROR;
                    session.status = friendly(error);
                    session.ready = false;
                    if (session.faultsAttempted && !session.faultsLoaded) {
                        for (ObdFaultResult result :
                                interruptedResults(new ArrayList<>(session.faultResults.values())))
                            session.faultResults.put(result.service, result);
                        session.faultStatus = faultSummary(new ArrayList<>(session.faultResults.values()));
                    }
                }
                publish(session);
            }
        } finally {
            closeLink(session.link);
        }
    }

    private void initialise(Session session) throws IOException {
        query(session, "ATZ", 5000);
        ElmProtocol.Reply identity = query(session, "ATI", 2500);
        if (identity.error != null || identity.lines.isEmpty())
            throw new IOException("Adapter identification failed");
        synchronized (lock) {
            session.adapterInfo = identity.text();
        }
        for (String command : new String[] {"ATE0", "ATL0", "ATS1", "ATH1", "ATSP0"}) {
            ElmProtocol.Reply result = query(session, command, 2500);
            if (result.error != null || !result.lines.contains("OK"))
                throw new IOException("Adapter could not enable the required read-only response format");
        }
        phase(session, Phase.ADAPTER_READY, "Adapter connected · vehicle ECU not yet confirmed");
    }

    private boolean discover(Session session) throws IOException {
        restoreFunctionalHeader(session);
        phase(session, Phase.FINDING_ECU, "Adapter connected · looking for vehicle ECU");
        ElmProtocol.Reply first = query(session, "0100", 12000);
        Map<String, Set<Integer>> masks = ElmProtocol.supported(first, 0);
        if (masks.isEmpty()) {
            synchronized (lock) {
                session.ready = false;
            }
            phase(session, Phase.ADAPTER_READY,
                    "Adapter connected · no ECU reply; check ignition and OBD connection");
            return false;
        }
        String selected = masks.containsKey(session.profile.primaryEcu) ? session.profile.primaryEcu
                : masks.containsKey(session.ecu)                        ? session.ecu
                                                 : new TreeSet<>(masks.keySet()).first();
        phase(session, Phase.DISCOVERING, "Discovering sensors from ECU " + selected);
        for (int base = 0x20; base <= 0xE0; base += 0x20) {
            boolean more = false;
            for (Set<Integer> supported : masks.values())
                if (supported.contains(base))
                    more = true;
            if (!more)
                break;
            ElmProtocol.Reply reply = query(session, String.format(Locale.ROOT, "01%02X", base), 4000);
            Map<String, Set<Integer>> range = ElmProtocol.supported(reply, base);
            for (Map.Entry<String, Set<Integer>> item : range.entrySet()) {
                Set<Integer> known = masks.get(item.getKey());
                // A range belongs only to a source that advertised that range in its own preceding bitmap.
                if (known != null && known.contains(base))
                    known.addAll(item.getValue());
            }
        }
        ElmProtocol.Reply protocol = query(session, "ATDP", 2500);
        synchronized (lock) {
            ensureActive(session);
            if (!session.ecu.equals(selected)) {
                session.histories.clear();
                session.faults.clear();
                session.faultResults.clear();
                session.faultsLoaded = false;
                session.faultsAttempted = false;
                session.faultStatus = "Fault codes have not been requested from this ECU";
            }
            session.ecu = selected;
            session.protocol = protocol.error == null ? protocol.text() : "Unknown protocol";
            session.supported.clear();
            session.advertised.clear();
            session.nextDue.clear();
            session.failures.clear();
            session.sensorChecks.clear();
            session.customAddressingUnavailable = false;
            for (ObdPid pid : session.profile.sensors)
                if (!pid.enhanced) {
                    boolean advertised = masks.get(selected).contains(pid.pid);
                    session.sensorChecks.put(pid.id,
                            new ObdSensorCheck(advertised ? ObdSensorCheck.State.ADVERTISED
                                                          : ObdSensorCheck.State.NOT_ADVERTISED,
                                    advertised ? "Selected ECU reports this standard PID."
                                               : "Selected ECU did not advertise this standard PID; no value "
                                                    + "is assumed.",
                                    selected, ""));
                    if (advertised) {
                        session.advertised.add(pid.id);
                        session.supported.add(pid.id);
                        session.nextDue.put(pid.id, 0L);
                    }
                }
            // Values from another ECU must not masquerade as the newly selected ECU's values.
            session.readings.entrySet().removeIf(item
                    -> !session.supported.contains(item.getKey())
                            || !item.getValue().source.equals(selected));
            session.lastEcuResponse = System.nanoTime();
        }
        boolean applicable = session.profile.enhancedApplicable(selected, session.protocol);
        if (applicable && !session.profile.directProbeIds.isEmpty()) {
            phase(session, Phase.DISCOVERING, "Checking " + session.profile.label + " · read-only");
            for (String id : session.profile.directProbeIds) {
                ObdPid sensor = session.profile.sensor(id);
                if (sensor.enhanced && session.customAddressingUnavailable)
                    continue;
                poll(session, sensor);
            }
        }
        synchronized (lock) {
            ensureActive(session);
            for (ObdPid pid : session.profile.sensors)
                if (pid.enhanced && !session.sensorChecks.containsKey(pid.id))
                    session.sensorChecks.put(pid.id,
                            new ObdSensorCheck(ObdSensorCheck.State.NOT_APPLICABLE,
                                    "The selected ECU/protocol does not match this profile; no enhanced "
                                            + "request was sent.",
                                    selected, ""));
            session.ready = true;
            // Discovery can spend several seconds on absent custom channels. Do not
            // treat that time as proof the ordinary engine sensors stopped responding.
            session.lastEcuResponse = System.nanoTime();
        }
        phase(session, Phase.MONITORING,
                session.supported.isEmpty() ? "ECU connected · none of the dashboard sensors are supported"
                                            : "Monitoring supported sensors · ECU " + selected);
        return true;
    }

    private ObdPid nextSensor(Session session) {
        synchronized (lock) {
            long now = System.nanoTime();
            ObdPid selected = null;
            double highest = -1;
            for (ObdPid pid : session.profile.sensors)
                if (session.supported.contains(pid.id)) {
                    long due = session.nextDue.get(pid.id);
                    if (due > now)
                        continue;
                    double priority = due == 0 ? Double.MAX_VALUE : (now - due) / (interval(pid) * 1000000.0);
                    if (selected == null || priority > highest) {
                        highest = priority;
                        selected = pid;
                    }
                }
            return selected;
        }
    }

    private void poll(Session session, ObdPid pid) throws IOException {
        ElmProtocol.Reply reply = null;
        if (pid.enhanced) {
            try {
                session.physicalHeader = true; // Restore even if the adapter rejects/partially applies setup.
                ElmProtocol.Reply header = query(session, "ATSH" + pid.header, 2500);
                if (header.error == null && header.lines.contains("OK"))
                    reply = query(session, pid.command(), 4000);
            } finally {
                restoreFunctionalHeader(session);
            }
            if (reply == null) {
                synchronized (lock) {
                    ensureActive(session);
                    session.customAddressingUnavailable = true;
                    for (ObdPid member : session.profile.sensors)
                        if (member.enhanced) {
                            session.supported.remove(member.id);
                            session.sensorChecks.put(member.id,
                                    new ObdSensorCheck(ObdSensorCheck.State.UNAVAILABLE,
                                            "Adapter did not accept this profile's physical addressing. "
                                                    + "Standard addressing was restored; standard monitoring "
                                                      + "can "
                                                    + "continue.",
                                            session.ecu, ""));
                        }
                }
                publish(session);
                return;
            }
        } else {
            restoreFunctionalHeader(session);
            reply = query(session, pid.command(), 3500);
        }
        String expectedSource = pid.responseSource.isEmpty() ? session.ecu : pid.responseSource;
        ElmProtocol.Message message = reply.sensor(expectedSource, pid.service, pid.pid);
        synchronized (lock) {
            ensureActive(session);
            for (ObdPid member : session.profile.sensors)
                if (member.sameRequest(pid)) {
                    Double value = null;
                    String failure = reply.responseError;
                    ObdSensorCheck.State state = failure == null ? ObdSensorCheck.State.NO_RESPONSE
                                                                 : ObdSensorCheck.State.INCOMPLETE;
                    int refusal = reply.rejection(expectedSource, pid.service);
                    if (refusal != -1 && refusal != 0x78) {
                        state = ObdSensorCheck.State.DECLINED;
                        failure = String.format(
                                Locale.ROOT, "Selected ECU declined this read (NRC %02X).", refusal);
                    } else if (failure == null && message != null) {
                        try {
                            value = member.decode(message.data);
                        } catch (IllegalArgumentException invalid) {
                            failure = invalid.getMessage();
                            state = failure.contains("not reported") || failure.contains("unavailable")
                                    ? ObdSensorCheck.State.UNAVAILABLE
                                    : ObdSensorCheck.State.INCOMPLETE;
                            if (!member.enhanced && failure.contains("not reported"))
                                session.supported.remove(member.id);
                        }
                    }
                    if (value != null) {
                        record(session, member, value, expectedSource);
                        session.supported.add(member.id);
                        session.sensorChecks.put(member.id,
                                new ObdSensorCheck(ObdSensorCheck.State.RESPONDING,
                                        member.experimental ? "Reply decoded using a " + member.originLabel
                                                        + (" definition; physical sensor and formula still "
                                                                + "need comparison with a reference tool.")
                                                            : "A complete reading was received from the "
                                                        + "selected ECU. Advertised support: "
                                                        + (session.advertised.contains(member.id)
                                                                        ? "yes."
                                                                        : "no; direct check replied."),
                                        expectedSource, raw(message)));
                    } else {
                        if (failure == null)
                            failure = "No complete response from the selected ECU; support is not confirmed.";
                        if (!member.enhanced)
                            failure += " Advertised support: "
                                    + (session.advertised.contains(member.id) ? "yes." : "no.");
                        if ("NO DATA".equals(reply.responseError))
                            state = ObdSensorCheck.State.NO_RESPONSE;
                        session.sensorChecks.put(
                                member.id, new ObdSensorCheck(state, failure, expectedSource, raw(message)));
                        Reading previous = session.readings.get(member.id);
                        if (previous != null)
                            session.readings.put(member.id,
                                    new Reading(member.id, previous.value, previous.timestampMillis,
                                            previous.source, failure, previous.history));
                        session.failures.put(member.id, session.failures.getOrDefault(member.id, 0) + 1);
                    }
                    int failures = session.failures.getOrDefault(member.id, 0);
                    long backoff = failures == 0
                            ? interval(member)
                            : Math.min(30000,
                                      Math.max(2000, interval(member)) * (1L << Math.min(failures, 3)));
                    session.nextDue.put(member.id, System.nanoTime() + backoff * 1000000L);
                }
        }
        publish(session);
    }

    private void record(Session session, ObdPid pid, double value, String source) {
        Reading previous = session.readings.get(pid.id);
        List<Double> history = previous == null ? new ArrayList<>() : new ArrayList<>(previous.history);
        history.add(value);
        while (history.size() > 40) history.remove(0);
        long timestamp = System.currentTimeMillis();
        session.readings.put(pid.id, new Reading(pid.id, value, timestamp, source, null, history));
        ObdHistory recorded = session.histories.get(pid.id);
        if (recorded == null) {
            recorded = new ObdHistory();
            session.histories.put(pid.id, recorded);
        }
        recorded.record(timestamp, value);
        session.failures.put(pid.id, 0);
        session.lastEcuResponse = System.nanoTime();
    }

    private static String raw(ElmProtocol.Message message) {
        if (message == null)
            return "";
        StringBuilder text = new StringBuilder();
        for (byte value : message.data) {
            if (text.length() > 0)
                text.append(' ');
            text.append(String.format(Locale.ROOT, "%02X", value & 255));
        }
        return text.toString();
    }

    private void restoreFunctionalHeader(Session session) throws IOException {
        if (!session.physicalHeader)
            return;
        ElmProtocol.Reply restored = query(session, "ATSH" + session.profile.functionalHeader, 2500);
        if (restored.error != null || !restored.lines.contains("OK"))
            throw new IOException(
                    "Adapter could not restore standard addressing; reconnect before continuing");
        session.physicalHeader = false;
    }

    private void loadFaults(Session session) throws IOException {
        restoreFunctionalHeader(session);
        synchronized (lock) {
            ensureActive(session);
            session.faultsAttempted = true;
            session.faultsLoaded = false;
            session.faults.clear();
            session.faultResults.clear();
            for (int service : new int[] {3, 7, 10})
                session.faultResults.put(service, ObdFaultResult.waiting(service));
            session.faultStatus = "Reading stored, pending and permanent codes";
        }
        phase(session, Phase.MONITORING, "Reading fault codes · no codes are cleared");
        List<Fault> faults = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        boolean complete = true;
        int[] services = {3, 7, 10};
        for (int index = 0; index < services.length; index++) {
            int service = services[index];
            synchronized (lock) {
                ensureActive(session);
                session.faultResults.put(service, ObdFaultResult.reading(service));
                session.faultStatus = faultSummary(new ArrayList<>(session.faultResults.values()));
            }
            publish(session);
            ElmProtocol.Reply reply = query(session, String.format(Locale.ROOT, "%02X", service), 5000);
            ObdFaultResult result = ObdFaultResult.decode(reply, session.ecu, service);
            for (ElmProtocol.Message message : reply.messages) {
                if ((message.data[0] & 255) != service + 0x40)
                    continue;
                List<String> decoded = ElmProtocol.troubleCodes(message, service);
                if (decoded == null)
                    continue;
                for (String code : decoded) {
                    String key = result.category + ":" + message.source + ":" + code;
                    if (unique.add(key))
                        faults.add(new Fault(code, result.category, message.source));
                }
            }
            complete &= result.state == ObdFaultResult.State.READ;
            synchronized (lock) {
                ensureActive(session);
                session.faults.clear();
                session.faults.addAll(faults);
                session.faultResults.put(service, result);
                session.faultStatus = faultSummary(new ArrayList<>(session.faultResults.values()))
                        + (index < services.length - 1 ? " · reading remaining categories" : "");
            }
            publish(session);
        }
        synchronized (lock) {
            ensureActive(session);
            session.faults.clear();
            session.faults.addAll(faults);
            session.faultsLoaded = complete;
        }
        phase(session, Phase.MONITORING,
                complete ? "Engine code scan complete · no codes were cleared"
                         : "Partial code results · see each category; no codes were cleared");
    }

    private ElmProtocol.Reply query(Session session, String command, long timeoutMillis) throws IOException {
        // A slow adapter naturally reduces polling; the floor caps all traffic, including fault reads.
        long adaptive =
                Math.max(MIN_QUERY_NANOS, Math.min(1500000000L, (long) (session.responseMillis * 1200000L)));
        while (true) {
            awaitRunning(session);
            ensureActive(session);
            long remaining = session.lastQuery + adaptive - System.nanoTime();
            if (remaining > 0) {
                waitFor(session, Math.min(250, Math.max(1, remaining / 1000000L)));
                continue;
            }
            synchronized (lock) {
                ensureActive(session);
                if (session.paused)
                    continue;
                session.lastQuery = System.nanoTime();
                session.queryTimes.addLast(session.lastQuery);
                break;
            }
        }
        long start = System.nanoTime();
        ElmProtocol.Reply result = session.elm.query(command, timeoutMillis);
        ensureActive(session);
        session.responseMillis = session.responseMillis * 0.8 + (System.nanoTime() - start) / 1000000.0 * 0.2;
        return result;
    }

    private void awaitRunning(Session session) throws IOException {
        synchronized (lock) {
            while (session.paused && isActiveLocked(session)) {
                try {
                    lock.wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Connection cancelled");
                }
            }
            ensureActive(session);
        }
    }

    private void waitFor(Session session, long millis) throws IOException {
        synchronized (lock) {
            ensureActive(session);
            try {
                lock.wait(Math.max(1, millis));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Connection cancelled");
            }
            ensureActive(session);
        }
    }

    private void phase(Session session, Phase phase, String status) throws IOException {
        synchronized (lock) {
            ensureActive(session);
            if (!session.paused) {
                session.phase = phase;
                session.status = status;
            }
        }
        publish(session);
    }

    private void publish(Session session) {
        synchronized (lock) {
            if (!isActiveLocked(session))
                return;
            current = snapshotLocked(session);
            // Serialize callback delivery with generation changes. Listeners should only marshal to the UI.
            notifyListener(current);
        }
    }

    private Snapshot snapshotLocked(Session session) {
        long now = System.nanoTime();
        while (!session.queryTimes.isEmpty() && session.queryTimes.peekFirst() < now - 5000000000L)
            session.queryTimes.removeFirst();
        double seconds = Math.max(1, Math.min(5, (now - session.started) / 1000000000.0));
        double rate =
                session.paused || session.phase == Phase.ERROR ? 0 : session.queryTimes.size() / seconds;
        return new Snapshot(session.profile, session.phase, session.status, session.adapterInfo,
                session.protocol, session.transport, session.ecu, session.readings, session.supported,
                session.sensorChecks, session.faults, session.faultsLoaded, session.faultsAttempted,
                session.faultStatus, new ArrayList<>(session.faultResults.values()), rate);
    }

    private static List<ObdFaultResult> interruptedResults(List<ObdFaultResult> results) {
        List<ObdFaultResult> stopped = new ArrayList<>();
        for (ObdFaultResult result : results) stopped.add(result.interrupted());
        return stopped;
    }
    private static String faultSummary(List<ObdFaultResult> results) {
        List<String> summaries = new ArrayList<>();
        for (ObdFaultResult result : results) summaries.add(result.summary());
        return String.join(" · ", summaries);
    }

    private boolean isActive(Session session) {
        synchronized (lock) {
            return isActiveLocked(session);
        }
    }
    private boolean isActiveLocked(Session session) {
        return active == session && !session.cancelled && generation == session.generation;
    }
    private void ensureActive(Session session) throws IOException {
        synchronized (lock) {
            if (!isActiveLocked(session))
                throw new IOException("Connection cancelled");
        }
    }
    private void notifyListener(Snapshot snapshot) {
        if (listener != null) {
            try {
                listener.onUpdate(snapshot);
            } catch (RuntimeException ignored) {
            }
        }
    }
    private static void cancel(Session session) {
        if (session == null)
            return;
        session.cancelled = true;
        closeLink(session.link);
        if (session.worker != null)
            session.worker.interrupt();
    }
    private static void closeLink(ObdLink link) {
        try {
            link.close();
        } catch (RuntimeException ignored) {
        }
    }
    private static String friendly(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Adapter connection failed; reconnect to retry"
                                                           : message;
    }
    private Snapshot empty(Phase phase, String status) {
        return new Snapshot(profile, phase, status, "", "", "", "", Collections.emptyMap(),
                Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), false, false,
                "Fault codes have not been requested", Collections.emptyList(), 0);
    }
}
