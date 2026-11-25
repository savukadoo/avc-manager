package local.cc3.obd;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Immutable read definition. Vehicle formulas belong to profiles, not the polling engine. */
public final class ObdPid {
    public interface Decoder {
        double decode(byte[] data);
    }
    public final String id, label, unit, header, responseSource, originLabel, category, cardLabel;
    public final double min, max;
    public final int service, pid, bytes;
    public final long intervalMillis;
    public final boolean enhanced, experimental;
    private final Decoder decoder;
    private final String note;
    private final String[] states;

    public ObdPid(String id, String label, String unit, double min, double max, int service, int pid,
            int bytes, long intervalMillis, Decoder decoder, String header, String responseSource,
            String originLabel, String category, String cardLabel, String note, String... states) {
        this(id, label, unit, min, max, service, pid, bytes, intervalMillis, decoder, header, responseSource,
                originLabel, category, cardLabel, note, !header.isEmpty(), states);
    }

    public ObdPid(String id, String label, String unit, double min, double max, int service, int pid,
            int bytes, long intervalMillis, Decoder decoder, String header, String responseSource,
            String originLabel, String category, String cardLabel, String note, boolean experimental,
            String... states) {
        if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,80}") || decoder == null || bytes < 1 || bytes > 4092
                || intervalMillis < 250 || intervalMillis > 60000 || !Double.isFinite(min)
                || !Double.isFinite(max) || max <= min)
            throw new IllegalArgumentException("Invalid sensor definition");
        if ((service != 1 && service != 0x21 && service != 0x22) || pid < 0
                || pid > (service == 0x22 ? 65535 : 255))
            throw new IllegalArgumentException("Only sensor read services01/21/22 are supported");
        if (service == 1 && !header.isEmpty() || service != 1 && header.isEmpty())
            throw new IllegalArgumentException("Sensor addressing");
        if (!header.isEmpty() && (!validHeader(header) || !validHeader(responseSource)))
            throw new IllegalArgumentException("Sensor header/source");
        this.id = id;
        this.label = label;
        this.unit = unit;
        this.min = min;
        this.max = max;
        this.service = service;
        this.pid = pid;
        this.bytes = bytes;
        this.intervalMillis = intervalMillis;
        this.decoder = decoder;
        this.header = header;
        this.responseSource = responseSource;
        this.originLabel = originLabel;
        this.enhanced = !header.isEmpty();
        this.experimental = experimental;
        this.category = category;
        this.cardLabel = cardLabel;
        this.note = note;
        this.states = states.clone();
    }

    public static boolean validHeader(String value) {
        if (value == null || !value.matches("[0-9A-F]{3}|[0-9A-F]{6}|[0-9A-F]{8}"))
            return false;
        long number = Long.parseLong(value, 16);
        return value.length() == 3 ? number <= 0x7FF : value.length() == 8 ? number <= 0x1FFFFFFF : true;
    }
    public static List<ObdPid> all() {
        return ObdProfiles.allSensors();
    }
    public static ObdPid byId(String id) {
        for (ObdPid sensor : all())
            if (sensor.id.equals(id))
                return sensor;
        return null;
    }
    static ObdPid byPid(int pid) {
        for (ObdPid sensor : all())
            if (sensor.service == 1 && sensor.pid == pid)
                return sensor;
        return null;
    }
    public String command() {
        return String.format(Locale.ROOT, service == 0x22 ? "%02X%04X" : "%02X%02X", service, pid);
    }
    public String definitionNote() {
        return note;
    }
    public boolean sameRequest(ObdPid other) {
        return service == other.service && pid == other.pid && header.equals(other.header)
                && responseSource.equals(other.responseSource);
    }
    public double decode(byte[] payload) {
        int prefix = service == 0x22 ? 3 : 2;
        if (payload == null || payload.length < bytes + prefix || (payload[0] & 255) != service + 0x40
                || (service == 0x22 ? (payload[1] & 255) * 256 + (payload[2] & 255) : payload[1] & 255)
                        != pid)
            throw new IllegalArgumentException("Incomplete or mismatched sensor response");
        double value = decoder.decode(Arrays.copyOfRange(payload, prefix, payload.length));
        if (!Double.isFinite(value))
            throw new IllegalArgumentException("Non-finite sensor reading");
        if (states.length > 0 && (value != Math.rint(value) || value < 0 || value >= states.length))
            throw new IllegalArgumentException("Unknown sensor state");
        return value;
    }
    public boolean dpf() {
        return category.equals("dpf");
    }
    public String dashboardLabel() {
        return cardLabel.isEmpty() ? label : cardLabel;
    }
    public boolean categorical() {
        return states.length > 2;
    }
    public double displayValue(double value, boolean imperial) {
        if (!imperial)
            return value;
        if (unit.equals("°C"))
            return value * 9 / 5 + 32;
        if (unit.equals("km/h") || unit.equals("km"))
            return value * .621371192;
        if (unit.startsWith("kPa"))
            return value * .145037738;
        if (unit.equals("MPa"))
            return value * 145.037738;
        if (unit.equals("L/h"))
            return value * .264172052;
        if (unit.equals("g/s"))
            return value * .132277357;
        return value;
    }
    public String unit(boolean imperial) {
        if (unit.equals("state"))
            return "";
        if (!imperial)
            return unit;
        if (unit.equals("°C"))
            return "°F";
        if (unit.equals("km/h"))
            return "mph";
        if (unit.equals("km"))
            return "mi";
        if (unit.equals("kPa abs"))
            return "psi abs";
        if (unit.equals("kPa") || unit.equals("MPa"))
            return "psi";
        if (unit.equals("L/h"))
            return "US gal/h";
        if (unit.equals("g/s"))
            return "lb/min";
        return unit;
    }
    public String valueText(double value, boolean imperial) {
        if (!Double.isFinite(value))
            return "—";
        if (states.length > 0) {
            int index = (int) value;
            return value == index && index >= 0 && index < states.length ? states[index] : "Unknown";
        }
        double display = displayValue(value, imperial);
        if (unit.equals("mm³/stroke"))
            return String.format(Locale.getDefault(), "%+.2f", display);
        if (unit.equals("index"))
            return String.format(Locale.getDefault(), "%.3f", display);
        if (dpf() && unit.equals("kPa"))
            return String.format(Locale.getDefault(), "%.2f", display);
        boolean decimal = unit.equals("MPa") || id.equals("voltage") || id.equals("maf")
                || id.equals("fuel_rate") || imperial && unit.startsWith("kPa");
        return String.format(Locale.getDefault(), decimal ? "%.1f" : "%.0f", display);
    }
    public double averageValue(double value, boolean imperial) {
        return states.length == 2 ? value * 100 : displayValue(value, imperial);
    }
    public String averageUnit(boolean imperial) {
        return states.length == 2 ? "%" : unit(imperial);
    }
    public String averageText(double value, boolean imperial) {
        return String.format(Locale.getDefault(), "%.1f", averageValue(value, imperial));
    }
}
