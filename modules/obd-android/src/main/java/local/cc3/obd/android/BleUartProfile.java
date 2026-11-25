package local.cc3.obd.android;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Known UART service layout. Matching properties/CCCD are still verified before any write. */
public final class BleUartProfile {
    public final String label;
    public final UUID service, write, notify;
    public BleUartProfile(String label, String service, String write, String notify) {
        if (label == null || label.trim().isEmpty())
            throw new IllegalArgumentException("UART label");
        this.label = label;
        this.service = UUID.fromString(service);
        this.write = UUID.fromString(write);
        this.notify = UUID.fromString(notify);
    }
    public static List<BleUartProfile> defaults() {
        return Collections.unmodifiableList(Arrays.asList(
                new BleUartProfile("FFE0 / FFE1 UART", "0000ffe0-0000-1000-8000-00805f9b34fb",
                        "0000ffe1-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb"),
                new BleUartProfile("FFF0 UART", "0000fff0-0000-1000-8000-00805f9b34fb",
                        "0000fff2-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb"),
                new BleUartProfile("Nordic UART", "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                        "6e400002-b5a3-f393-e0a9-e50e24dcca9e", "6e400003-b5a3-f393-e0a9-e50e24dcca9e")));
    }
}
