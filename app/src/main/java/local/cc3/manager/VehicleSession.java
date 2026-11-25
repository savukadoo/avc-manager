package local.cc3.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import local.cc3.obd.ObdEngine;
import local.cc3.obd.ObdReconnectPolicy;
import local.cc3.obd.android.ObdBluetooth;

/** A single foreground reader shared by Vehicle and Home. All ownership changes run on the UI thread. */
final class VehicleSession {
    private static VehicleSession instance;
    final ObdBluetooth bluetooth;
    final ObdEngine engine;
    final ObdReconnectPolicy reconnect = new ObdReconnectPolicy();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Object owner;
    private ObdEngine.Listener listener;
    private final Runnable idle = this::disconnectIdle;

    static VehicleSession get(Screen screen) {
        if (instance == null) instance = new VehicleSession(screen.getApplicationContext(), screen.prefs());
        return instance;
    }

    private VehicleSession(Context context, SharedPreferences prefs) {
        bluetooth = new ObdBluetooth(context);
        engine = new ObdEngine(value -> ui.post(() -> {
            if (listener != null) listener.onUpdate(value);
        }));
        VehicleProfiles.restore(prefs, engine);
        long cycle = prefs.getLong("obd_refresh_ms", 0);
        if (cycle == 1000 || cycle == 2000 || cycle == 5000 || cycle == 10000) engine.setRefreshCycleMillis(cycle);
    }

    void enter(Object page, ObdEngine.Listener callback) {
        ui.removeCallbacks(idle);
        owner = page;
        listener = callback;
        reconnect.enter();
    }

    void leave(Object page) {
        if (owner != page) return;
        owner = null;
        listener = null;
        bluetooth.stopScan();
        // Android pauses the source before resuming the destination of a Home/Vehicle tap.
        ui.postDelayed(idle, 600);
    }

    private void disconnectIdle() {
        if (owner != null) return;
        reconnect.leave();
        engine.disconnect();
    }

    static ObdBluetooth.DeviceChoice savedAdapter(SharedPreferences prefs) {
        String address = prefs.getString("obd_adapter_address", "");
        if (address.isEmpty()) return null;
        return new ObdBluetooth.DeviceChoice(prefs.getString("obd_adapter_name", "OBD adapter"), address,
                savedTransport(prefs));
    }

    static ObdBluetooth.Transport savedTransport(SharedPreferences prefs) {
        return "CLASSIC".equals(prefs.getString("obd_transport", "BLE"))
                ? ObdBluetooth.Transport.CLASSIC : ObdBluetooth.Transport.BLE;
    }

    boolean connectDefault(Object page, SharedPreferences prefs, ObdBluetooth.DeviceChoice adapter, boolean blocked) {
        if (owner != page) return false;
        ObdEngine.Phase phase = engine.snapshot().phase;
        boolean idlePhase = phase == ObdEngine.Phase.DISCONNECTED || phase == ObdEngine.Phase.ERROR;
        if (!reconnect.begin(SystemClock.elapsedRealtime(), prefs.getBoolean("obd_auto_connect", false),
                adapter != null, bluetooth.enabled(), !idlePhase || blocked)) return false;
        engine.connect(bluetooth.link(adapter));
        return true;
    }
}
