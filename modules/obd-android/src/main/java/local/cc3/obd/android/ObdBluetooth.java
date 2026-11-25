package local.cc3.obd.android;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import local.cc3.obd.ObdByteQueue;
import local.cc3.obd.ObdLink;

/** Foreground discovery and byte transports only. No ELM, PID or vehicle commands. */
public final class ObdBluetooth implements java.io.Closeable {
    public enum Transport { BLE, CLASSIC }

    public static final class DeviceChoice {
        public final String name, address;
        public final Transport transport;

        public DeviceChoice(String name, String address, Transport transport) {
            this.name = cleanName(name);
            this.address = address == null ? "" : address;
            if (transport == null)
                throw new NullPointerException("transport");
            this.transport = transport;
        }

        @Override
        public String toString() {
            return name + " · " + (transport == Transport.BLE ? "BLE" : "Classic");
        }
    }

    public interface ScanListener {
        void onDevice(DeviceChoice device);
        void onStatus(String status);
        void onFinished();
    }

    private static final long SCAN_PHASE_MS = 12000;
    private static final long OPEN_MS = 20000;
    private static final long GATT_OP_MS = 5000;
    public static final UUID DEFAULT_SPP_UUID = uuid("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = uuid("00002902-0000-1000-8000-00805f9b34fb");
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cc3-obd-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private final Context context;
    private final List<BleUartProfile> uartProfiles;
    private final UUID sppUuid;
    private final BluetoothAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<ObdLink> links = Collections.synchronizedSet(new HashSet<ObdLink>());
    private volatile boolean closed;
    private ScanRun scan;

    public ObdBluetooth(Context context) {
        this(context, BleUartProfile.defaults(), DEFAULT_SPP_UUID);
    }

    public ObdBluetooth(Context context, List<BleUartProfile> uartProfiles, UUID sppUuid) {
        if (uartProfiles == null || uartProfiles.contains(null) || sppUuid == null)
            throw new IllegalArgumentException("Adapter transport configuration");
        this.uartProfiles = Collections.unmodifiableList(new ArrayList<>(uartProfiles));
        this.sppUuid = sppUuid;
        this.context = context.getApplicationContext();
        BluetoothManager manager =
                (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter found = null;
        try {
            if (manager != null)
                found = manager.getAdapter();
        } catch (RuntimeException ignored) {
        }
        adapter = found;
    }

    public boolean supported() {
        return adapter != null;
    }

    public boolean enabled() {
        try {
            return adapter != null && canConnect() && adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String availability() {
        if (closed)
            return "Bluetooth connection manager is closed.";
        if (!supported())
            return "Bluetooth is unavailable on this device.";
        if (!canConnect())
            return "Allow nearby-device access to use the adapter.";
        if (!enabled())
            return "Turn on Bluetooth to connect an adapter.";
        return hasBle() ? "Bluetooth ready"
                        : "Bluetooth Classic available. This unit does not advertise BLE support.";
    }

    public List<DeviceChoice> paired() {
        List<DeviceChoice> result = new ArrayList<>();
        if (closed || !enabled())
            return result;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                int type = device.getType();
                if (type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL)
                    result.add(choice(device, Transport.BLE, null));
                if (type != BluetoothDevice.DEVICE_TYPE_LE)
                    result.add(choice(device, Transport.CLASSIC, null));
            }
            Collections.sort(result,
                    Comparator.comparing((DeviceChoice d) -> d.name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(d -> d.transport.name()));
        } catch (RuntimeException ignored) { /* Permission/state can change while reading bonded devices. */
        }
        return result;
    }

    /** BLE for at most 12s, then Classic for at most 12s. No auto-connect or pairing. */
    public void scan(ScanListener listener) {
        if (listener == null)
            throw new NullPointerException("listener");
        onMain(() -> {
            stopCurrent();
            ScanRun next = new ScanRun(listener);
            scan = next;
            if (closed || !enabled()) {
                next.status(availability());
                next.finish();
                return;
            }
            String permission = scanPermissionProblem();
            if (permission != null) {
                next.status(permission);
                next.finish();
                return;
            }
            for (DeviceChoice device : paired()) next.device(device);
            next.startBle();
        });
    }

    public void stopScan() {
        onMain(this::stopCurrent);
    }

    public ObdLink link(DeviceChoice device) {
        if (device == null)
            throw new NullPointerException("device");
        stopScan();
        ObdLink result = device.transport == Transport.BLE ? new BleLink(device) : new ClassicLink(device);
        links.add(result);
        if (closed)
            result.close();
        return result;
    }

    @Override
    public void close() {
        closed = true;
        stopScan();
        ObdLink[] active;
        synchronized (links) {
            active = links.toArray(new ObdLink[0]);
            links.clear();
        }
        for (ObdLink link : active) link.close();
    }

    private void stopCurrent() {
        if (scan != null)
            scan.finish();
    }
    private void onMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper())
            runnable.run();
        else
            main.post(runnable);
    }
    private boolean hasBle() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }
    private boolean granted(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean canConnect() {
        return granted(Build.VERSION.SDK_INT >= 31 ? "android.permission.BLUETOOTH_CONNECT"
                                                   : Manifest.permission.BLUETOOTH);
    }

    private String scanPermissionProblem() {
        if (Build.VERSION.SDK_INT >= 31) {
            return granted("android.permission.BLUETOOTH_SCAN")
                    ? null
                    : "Allow nearby-device scanning, then try again.";
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION))
            return "Allow location permission for nearby Bluetooth discovery. No GPS data is collected.";
        try {
            LocationManager location = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (location != null && !location.isLocationEnabled())
                return "Turn on Android Location for Bluetooth discovery. No GPS data is collected.";
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private DeviceChoice choice(BluetoothDevice device, Transport transport, String advertisedName) {
        String name = advertisedName;
        try {
            if (name == null || name.trim().isEmpty())
                name = device.getName();
        } catch (SecurityException ignored) {
        }
        return new DeviceChoice(name, device.getAddress(), transport);
    }

    private final class ScanRun {
        final ScanListener listener;
        final Set<String> seen = new HashSet<>();
        boolean done, classicOwned, receiverRegistered;
        BluetoothLeScanner scanner;
        final Runnable bleDeadline = this::startClassic;
        final Runnable classicDeadline = this::finish;
        final ScanCallback bleCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                onMain(() -> bleResult(result));
            }
            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                onMain(() -> {
                    for (ScanResult result : results) bleResult(result);
                });
            }
            @Override
            public void onScanFailed(int errorCode) {
                onMain(() -> {
                    if (current() && scanner != null) {
                        status("BLE scan could not start (" + errorCode + "). Trying Classic.");
                        startClassic();
                    }
                });
            }
        };
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context unused, Intent intent) {
                if (!current())
                    return;
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    BluetoothDevice device = BluetoothCompat.discovered(intent);
                    if (device != null) {
                        try {
                            device(choice(device, Transport.CLASSIC,
                                    intent.getStringExtra(BluetoothDevice.EXTRA_NAME)));
                        } catch (RuntimeException ignored) {
                        }
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                    classicOwned = false;
                    finish();
                }
            }
        };

        ScanRun(ScanListener listener) {
            this.listener = listener;
        }
        boolean current() {
            return !done && scan == this;
        }
        void status(String text) {
            if (current())
                listener.onStatus(text);
        }
        void device(DeviceChoice device) {
            if (current() && seen.add(device.address + ":" + device.transport))
                listener.onDevice(device);
        }
        void bleResult(ScanResult result) {
            if (!current() || scanner == null || result == null)
                return;
            try {
                device(choice(result.getDevice(), Transport.BLE,
                        result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName()));
            } catch (RuntimeException ignored) {
            }
        }
        void startBle() {
            if (!current())
                return;
            if (!hasBle()) {
                startClassic();
                return;
            }
            try {
                scanner = adapter.getBluetoothLeScanner();
                if (scanner == null) {
                    status("BLE scanner unavailable. Trying Classic.");
                    startClassic();
                    return;
                }
                status("Scanning BLE · up to 12 seconds. Classic discovery follows.");
                scanner.startScan(null,
                        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                        bleCallback);
                main.postDelayed(bleDeadline, SCAN_PHASE_MS);
            } catch (RuntimeException e) {
                status("BLE scanning unavailable. Trying Classic.");
                startClassic();
            }
        }
        void stopBle() {
            main.removeCallbacks(bleDeadline);
            if (scanner != null) {
                try {
                    scanner.stopScan(bleCallback);
                } catch (RuntimeException ignored) {
                }
                scanner = null;
            }
        }
        void startClassic() {
            if (!current() || receiverRegistered)
                return;
            stopBle();
            try {
                if (adapter.isDiscovering()) {
                    status("Another Bluetooth discovery is running. Stop that scan and try again; paired "
                           + "devices remain available.");
                    finish();
                    return;
                }
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                if (Build.VERSION.SDK_INT >= 33)
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                else
                    context.registerReceiver(receiver, filter);
                receiverRegistered = true;
                status("Scanning Classic Bluetooth · up to 12 seconds.");
                classicOwned = adapter.startDiscovery();
                if (!classicOwned) {
                    status("Classic discovery did not start. Try a paired device or scan again.");
                    finish();
                    return;
                }
                main.postDelayed(classicDeadline, SCAN_PHASE_MS);
            } catch (RuntimeException e) {
                status("Classic discovery unavailable. Check Bluetooth and scan permissions.");
                finish();
            }
        }
        void finish() {
            if (done)
                return;
            done = true;
            stopBle();
            main.removeCallbacks(classicDeadline);
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (RuntimeException ignored) {
                }
                receiverRegistered = false;
            }
            if (classicOwned) {
                try {
                    adapter.cancelDiscovery();
                } catch (RuntimeException ignored) {
                }
                classicOwned = false;
            }
            if (scan == this)
                scan = null;
            listener.onFinished();
        }
    }

    private abstract class BaseLink implements ObdLink {
        final DeviceChoice device;
        final Object state = new Object();
        final ObdByteQueue incoming = new ObdByteQueue(32768);
        boolean started, ready, ended;
        IOException failure;

        BaseLink(DeviceChoice device) {
            this.device = device;
        }
        BluetoothDevice remote() throws IOException {
            if (!supported() || !enabled())
                throw new IOException(availability());
            try {
                return adapter.getRemoteDevice(device.address);
            } catch (RuntimeException e) {
                throw new IOException("Invalid adapter selection or Bluetooth permission unavailable.", e);
            }
        }
        void begin() throws IOException {
            workerOnly();
            synchronized (state) {
                if (started)
                    throw new IOException("This OBD link has already been used; create a new connection.");
                check();
                started = true;
            }
        }
        void check() throws IOException {
            if (failure != null)
                throw failure;
            if (ended || ObdBluetooth.this.closed)
                throw new IOException("OBD connection closed.");
        }
        @Override
        public int read(byte[] target, int offset, int length, long timeoutMs) throws IOException {
            workerOnly();
            synchronized (state) {
                if (!started || (!ready && !ended))
                    throw new IOException("OBD connection is not open.");
            }
            return incoming.read(target, offset, length, timeoutMs);
        }
        void waitState(long deadline) throws IOException {
            check();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
                throw new SocketTimeoutException("Bluetooth adapter connection timed out.");
            try {
                state.wait(remaining / 1000000L, (int) (remaining % 1000000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("OBD operation interrupted.", e);
            }
            check();
        }
        void fail(IOException error) {
            synchronized (state) {
                if (ended)
                    return;
                failure = error;
            }
            incoming.fail(error);
            close();
        }
    }

    private final class ClassicLink extends BaseLink {
        BluetoothSocket socket;
        OutputStream output;
        final Object writes = new Object();
        ClassicLink(DeviceChoice device) {
            super(device);
        }
        @Override
        public String description() {
            return "Bluetooth Classic · SPP";
        }

        @Override
        public void open() throws IOException {
            begin();
            Thread connector = new Thread(() -> {
                BluetoothSocket candidate = null;
                try {
                    candidate = remote().createRfcommSocketToServiceRecord(sppUuid);
                    synchronized (state) {
                        check();
                        socket = candidate;
                    }
                    // Only this manager's discovery is stopped by link(); never reset the radio.
                    candidate.connect();
                    InputStream input = candidate.getInputStream();
                    OutputStream destination = candidate.getOutputStream();
                    synchronized (state) {
                        check();
                        output = destination;
                        ready = true;
                        state.notifyAll();
                    }
                    byte[] buffer = new byte[1024];
                    for (;;) {
                        int count = input.read(buffer);
                        if (count < 0) {
                            close();
                            return;
                        }
                        if (count > 0 && !incoming.offer(buffer, 0, count)) {
                            fail(new IOException("OBD receive buffer overflow; reconnect the adapter."));
                            return;
                        }
                    }
                } catch (IOException e) {
                    fail(e);
                } catch (RuntimeException e) {
                    fail(new IOException(
                            "Classic Bluetooth connection failed. Check pairing and serial-profile support.",
                            e));
                } finally {
                    if (candidate != null)
                        try {
                            candidate.close();
                        } catch (IOException ignored) {
                        }
                }
            }, "cc3-obd-rfcomm");
            connector.setDaemon(true);
            connector.start();
            try {
                long deadline = deadline(OPEN_MS);
                synchronized (state) {
                    while (!ready) waitState(deadline);
                    check();
                }
            } catch (IOException e) {
                fail(e);
                close();
                throw e;
            }
        }

        @Override
        public void write(byte[] data) throws IOException {
            workerOnly();
            if (data == null)
                throw new NullPointerException("data");
            synchronized (writes) {
                OutputStream stream;
                synchronized (state) {
                    check();
                    if (!ready)
                        throw new IOException("OBD connection is not open.");
                    stream = output;
                }
                ScheduledFuture<?> timeout = WATCHDOG.schedule(
                        ()
                                -> fail(new SocketTimeoutException("Classic Bluetooth write timed out.")),
                        GATT_OP_MS, TimeUnit.MILLISECONDS);
                try {
                    stream.write(data);
                    stream.flush();
                    synchronized (state) {
                        check();
                    }
                } catch (IOException e) {
                    fail(e);
                    throw e;
                } finally {
                    timeout.cancel(false);
                }
            }
        }

        @Override
        public void close() {
            BluetoothSocket old;
            synchronized (state) {
                ended = true;
                old = socket;
                socket = null;
                state.notifyAll();
            }
            incoming.finish();
            if (old != null)
                try {
                    old.close();
                } catch (IOException ignored) {
                }
            links.remove(this);
        }
    }

    private final class BleLink extends BaseLink {
        final Object operations = new Object();
        BluetoothGatt gatt;
        volatile BluetoothGattCharacteristic tx, rx;
        boolean connected, completed;
        int pending, operationStatus;
        String profile = "known UART profile";
        static final int SERVICE = 1, DESCRIPTOR = 2, WRITE = 3;
        BleLink(DeviceChoice device) {
            super(device);
        }
        @Override
        public String description() {
            synchronized (state) {
                return "Bluetooth LE · " + profile;
            }
        }

        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt source, int status, int newState) {
                synchronized (state) {
                    if (ended || (gatt != null && gatt != source))
                        return;
                    if (gatt == null)
                        gatt = source;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(new IOException("BLE connection failed (GATT " + status + ")."));
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED)
                    synchronized (state) {
                        connected = true;
                        state.notifyAll();
                    }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED)
                    fail(new IOException("BLE adapter disconnected."));
            }
            @Override
            public void onServicesDiscovered(BluetoothGatt source, int status) {
                completed(source, SERVICE, status);
            }
            @Override
            public void onDescriptorWrite(
                    BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
                if (CCCD.equals(descriptor.getUuid()) && rx != null
                        && rx.getUuid().equals(descriptor.getCharacteristic().getUuid()))
                    completed(source, DESCRIPTOR, status);
            }
            @Override
            public void onCharacteristicWrite(
                    BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
                if (tx != null && tx.getUuid().equals(characteristic.getUuid()))
                    completed(source, WRITE, status);
            }
            // Required callback on Android10; API33+ uses the immutable-value overload below.
            @Override @SuppressWarnings("deprecation")
            public void onCharacteristicChanged(
                    BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
                byte[] value = characteristic.getValue();
                receive(source, characteristic, value == null ? null : value.clone());
            }
            @Override
            public void onCharacteristicChanged(
                    BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
                receive(source, characteristic, value);
            }
        };

        void completed(BluetoothGatt source, int kind, int status) {
            synchronized (state) {
                if (ended || source != gatt || pending != kind)
                    return;
                operationStatus = status;
                completed = true;
                state.notifyAll();
            }
        }
        void receive(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
            synchronized (state) {
                if (ended || source != gatt || rx == null || !rx.getUuid().equals(characteristic.getUuid()))
                    return;
            }
            if (value != null && value.length > 0 && !incoming.offer(value, 0, value.length))
                fail(new IOException("OBD receive buffer overflow; reconnect the adapter."));
        }
        void prepare(int kind) throws IOException {
            synchronized (state) {
                check();
                pending = kind;
                completed = false;
                operationStatus = -1;
            }
        }
        void awaitOperation(long until) throws IOException {
            synchronized (state) {
                while (!completed) waitState(until);
                check();
                pending = 0;
                if (operationStatus != BluetoothGatt.GATT_SUCCESS)
                    throw new IOException("BLE operation failed (GATT " + operationStatus + ").");
            }
        }
        @Override
        public void open() throws IOException {
            begin();
            synchronized (operations) {
                BluetoothGatt candidate = null;
                try {
                    long until = deadline(OPEN_MS);
                    candidate = remote().connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                    synchronized (state) {
                        check();
                        if (candidate == null)
                            throw new IOException("BLE connection could not start.");
                        gatt = candidate;
                    }
                    synchronized (state) {
                        while (!connected) waitState(until);
                    }
                    prepare(SERVICE);
                    if (!candidate.discoverServices())
                        throw new IOException("BLE service discovery could not start.");
                    awaitOperation(until);
                    selectProfile(candidate.getServices());
                    if (!candidate.setCharacteristicNotification(rx, true))
                        throw new IOException("BLE notifications could not be enabled.");
                    BluetoothGattDescriptor descriptor = rx.getDescriptor(CCCD);
                    byte[] subscription=(rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0
                            ?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    prepare(DESCRIPTOR);
                    if (!BluetoothCompat.subscribe(candidate,descriptor,subscription))
                        throw new IOException("BLE notification subscription could not start.");
                    awaitOperation(until);
                    synchronized (state) {
                        check();
                        ready = true;
                        state.notifyAll();
                    }
                } catch (IOException e) {
                    fail(e);
                    close();
                    throw e;
                } catch (RuntimeException e) {
                    IOException error = new IOException(
                            "BLE connection failed. Check permissions and adapter UART support.", e);
                    fail(error);
                    close();
                    throw error;
                } finally {
                    // close() can race with connectGatt() before its return value is installed.
                    if (candidate != null) {
                        boolean closeCandidate;
                        synchronized (state) {
                            closeCandidate = !ready || ended;
                        }
                        if (closeCandidate)
                            try {
                                candidate.close();
                            } catch (RuntimeException ignored) {
                            }
                    }
                }
            }
        }
        void selectProfile(List<BluetoothGattService> available) throws IOException {
            // Explicit, property-checked UART layouts only. None is asserted as the Veepeak's actual profile.
            for (BleUartProfile mapping : uartProfiles)
                for (BluetoothGattService service : available) {
                    if (!service.getUuid().equals(mapping.service))
                        continue;
                    BluetoothGattCharacteristic write = service.getCharacteristic(mapping.write);
                    BluetoothGattCharacteristic notify = service.getCharacteristic(mapping.notify);
                    if (write == null || notify == null)
                        continue;
                    int writes = BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
                    int notifications = BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE;
                    if ((write.getProperties() & writes) == 0 || (notify.getProperties() & notifications) == 0
                            || notify.getDescriptor(CCCD) == null)
                        continue;
                    synchronized (state) {
                        tx = write;
                        rx = notify;
                        profile = mapping.label;
                    }
                    return;
                }
            throw new IOException("Unknown BLE UART profile. No writable characteristic was tried. Choose "
                                  + "Classic SPP or validate this adapter's profile.");
        }
        @Override
        public void write(byte[] data) throws IOException {
            workerOnly();
            if (data == null)
                throw new NullPointerException("data");
            synchronized (operations) {
                try {
                    BluetoothGatt active;
                    synchronized (state) {
                        check();
                        if (!ready)
                            throw new IOException("OBD connection is not open.");
                        active = gatt;
                    }
                    for (int offset = 0; offset < data.length; offset += 20) {
                        byte[] part = Arrays.copyOfRange(data, offset, Math.min(data.length, offset + 20));
                        int type = (tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                        prepare(WRITE);
                        if (!BluetoothCompat.write(active,tx,part,type))
                            throw new IOException("BLE write could not start.");
                        awaitOperation(deadline(GATT_OP_MS));
                    }
                } catch (IOException e) {
                    fail(e);
                    close();
                    throw e;
                } catch (RuntimeException e) {
                    IOException error = new IOException("BLE write failed.", e);
                    fail(error);
                    close();
                    throw error;
                }
            }
        }
        @Override
        public void close() {
            BluetoothGatt old;
            synchronized (state) {
                ended = true;
                old = gatt;
                gatt = null;
                state.notifyAll();
            }
            incoming.finish();
            if (old != null) {
                try {
                    old.disconnect();
                } catch (RuntimeException ignored) {
                }
                try {
                    old.close();
                } catch (RuntimeException ignored) {
                }
            }
            links.remove(this);
        }
    }

    private static void workerOnly() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IOException("Run OBD connection and I/O on a worker thread.");
    }
    private static long deadline(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }
    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
    private static String cleanName(String value) {
        if (value == null || value.trim().isEmpty())
            return "Unnamed Bluetooth device";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }
}
