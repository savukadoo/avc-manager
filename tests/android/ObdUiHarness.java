package local.cc3.manager.obdqa;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import local.cc3.obd.ObdLink;

/**
 * QA APK ONLY. Not part of app/src/main, the Manager APK or any production data path.
 * This runner refuses physical devices and never opens a Bluetooth or vehicle transport.
 * Leave this instrumentation running while the host captures native screenshots.
 */
public final class ObdUiHarness extends Instrumentation {
    private static final String TARGET = "local.cc3.manager";
    private static final String WARNING = "SIMULATED OBD QA — NO VEHICLE DATA";
    private static final String DUMP_ACTION = "local.cc3.manager.obdqa.DUMP";
    private static final String FINISH_ACTION = "local.cc3.manager.obdqa.FINISH";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Bundle arguments;
    private Activity activity;
    private Object engine;
    private Method disconnect;
    private TextView warning;
    private int dumpEveryMs = 500, dumpSequence;
    private String lastDumpError = "";
    private boolean dumpReceiverRegistered;
    private volatile boolean finished;
    private volatile boolean stopRequested;

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            requireEmulator();
            int duration = integer("durationSec", 120, 10, 600);
            int staleAfter = integer("staleAfterSec", 0, 0, 600);
            int pauseAfter = integer("pauseAfterSec", 0, 0, 600);
            dumpEveryMs = integer("dumpEveryMs", 500, 0, 10000);
            boolean readFaults = bool("faults", false);
            String theme=arguments.getString("themeFixture", "");
            if(!theme.isEmpty()){
                if(!Arrays.asList("Obsidian","Midnight Blue","Dusk","Copper").contains(theme))throw new IllegalArgumentException("Unknown theme fixture");
                getTargetContext().getSharedPreferences("cc3_manager",Context.MODE_PRIVATE).edit().putString("appearance",theme).apply();
            }
            String historyFixture = arguments.getString("historyFixture", "none");
            if (!Arrays.asList("none", "full", "gaps").contains(historyFixture))
                throw new IllegalArgumentException("Unknown history fixture");
            String profile = arguments.getString("profile", "full");
            String toyota = arguments.getString("toyotaFixture", "none");
            if (!Arrays.asList("none", "full", "missing-oil", "partial").contains(toyota))
                throw new IllegalArgumentException("Unknown Toyota fixture");
            if (!toyota.equals("none") && !profile.equals("full")) throw new IllegalArgumentException("Toyota fixtures require profile=full");
            if (!"full".equals(profile) && !"limited".equals(profile) && !"none".equals(profile))
                throw new IllegalArgumentException("profile must be full, limited or none");
            boolean dpfFixture=bool("dpfFixture",false);
            if(dpfFixture && !toyota.equals("full"))throw new IllegalArgumentException("DPF fixture requires full Toyota fixture");
            int expected=dpfFixture?34:!toyota.equals("none")?toyota.equals("partial")?20:28
                    :"none".equals(profile)?0:"limited".equals(profile)?3:13;
            getTargetContext().getSharedPreferences("cc3_manager",Context.MODE_PRIVATE).edit()
                    .putString("obd_vehicle_profile",toyota.equals("none")?"generic-obd2":"toyota-1gd-130kw").apply();
            String faultProfile = arguments.getString("faultProfile", "partial");
            if (!Arrays.asList("partial", "mixed-empty", "partial-empty", "pending-final", "no-response").contains(faultProfile))
                throw new IllegalArgumentException("Unknown code-result fixture");
            FakeLink fake = new FakeLink(staleAfter, arguments.getString("stalePid", "all"), profile, faultProfile, toyota);
            fake.dpf=dpfFixture;
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("screen", "Vehicle");
            activity = startActivitySync(intent);
            runOnMain(() -> {
                Field pageField = activity.getClass().getDeclaredField("vehiclePage");
                pageField.setAccessible(true);
                Object page = pageField.get(activity);
                if (page == null) throw new IllegalStateException("VehiclePage was not created by screen=Vehicle");
                Field engineField = page.getClass().getDeclaredField("engine");
                engineField.setAccessible(true);
                engine = engineField.get(page);
                disconnect = engine.getClass().getMethod("disconnect");
                attachWarning();
                IntentFilter filter = new IntentFilter(DUMP_ACTION);
                filter.addAction(FINISH_ACTION);
                if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(dumpReceiver, filter, Context.RECEIVER_EXPORTED);
                else activity.registerReceiver(dumpReceiver, filter);
                dumpReceiverRegistered = true;
                engine.getClass().getMethod("connect", ObdLink.class).invoke(engine, fake);
                dumpUi();
            });
            main.post(keepWarning);
            if (dumpEveryMs > 0) main.post(dumpPeriodically);
            Object ready = awaitReadings(expected, 35000);
            if (!"none".equals(historyFixture)) runOnMain(() -> seedHistory(historyFixture));
            status("SIMULATED OBD QA ready; " + readingCount(ready) + " genuine engine-parsed fake-link samples; "
                    + "durationSec=" + duration + ", staleAfterSec=" + staleAfter + ", profile=" + profile);
            if (readFaults && !stopRequested) runOnMain(() -> engine.getClass().getMethod("readFaults").invoke(engine));
            long began = System.nanoTime();
            boolean paused = false;
            while (!stopRequested && (System.nanoTime() - began) / 1000000000L < duration) {
                long elapsed = (System.nanoTime() - began) / 1000000000L;
                if (!paused && pauseAfter > 0 && elapsed >= pauseAfter) {
                    runOnMain(() -> engine.getClass().getMethod("pause").invoke(engine));
                    paused = true;
                    status("SIMULATED OBD QA paused by pauseAfterSec; host may Resume using the app button.");
                }
                Thread.sleep(250);
            }
            result.putString("stream", "SIMULATED OBD QA complete. Fake link closed; no Bluetooth or vehicle transport was opened.\n");
            result.putInt("commands", fake.commands);
            result.putInt("samples_expected", expected);
            result.putBoolean("finish_requested", stopRequested);
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            Throwable cause = error.getCause() == null ? error : error.getCause();
            result.putString("stream", "OBD QA failed: " + cause + "\n");
            result.putString("error", cause.toString());
        } finally {
            finished = true;
            main.removeCallbacks(keepWarning);
            main.removeCallbacks(dumpPeriodically);
            try {
                runOnMain(() -> {
                    if (engine != null && disconnect != null) disconnect.invoke(engine);
                    if (dumpReceiverRegistered) { activity.unregisterReceiver(dumpReceiver); dumpReceiverRegistered = false; }
                    if (warning != null && warning.getParent() instanceof ViewGroup)
                        ((ViewGroup)warning.getParent()).removeView(warning);
                });
            } catch (Exception ignored) { }
            finish(code, result);
        }
    }

    private Object awaitReadings(int expected, long timeout) throws Exception {
        long deadline = System.nanoTime() + timeout * 1000000L;
        Method snapshot = engine.getClass().getMethod("snapshot");
        String phase = "";
        while (System.nanoTime() < deadline) {
            Object state = snapshot.invoke(engine);
            if (stopRequested) return state;
            phase = String.valueOf(state.getClass().getField("phase").get(state));
            if ("ERROR".equals(phase))
                throw new IllegalStateException(String.valueOf(state.getClass().getField("status").get(state)));
            if ("MONITORING".equals(phase) && readingCount(state) == expected) return state;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Timed out waiting for " + expected + " simulated readings; phase=" + phase);
    }

    private static int readingCount(Object state) throws Exception {
        return ((Map<?, ?>) state.getClass().getField("readings").get(state)).size();
    }

    /** Explicit synthetic timestamps for chart rendering only; this runner refuses real devices. */
    private void seedHistory(String fixture) throws Exception {
        Object guard = appField(engine, "lock");
        synchronized (guard) {
            Object session = appField(engine, "active");
            @SuppressWarnings("unchecked") Map<String, Object> histories = (Map<String, Object>)appField(session, "histories");
            Class<?> type = activity.getClassLoader().loadClass("local.cc3.obd.ObdHistory");
            Object history = type.getConstructor().newInstance();
            Method record = type.getMethod("record", long.class, double.class);
            long now = System.currentTimeMillis();
            for (int minute = 0; minute < 10; minute++) {
                if ("gaps".equals(fixture) && (minute == 3 || minute == 6)) continue;
                long middle = now - 570000L + minute * 60000L;
                record.invoke(history, middle - 1000, 58.0 + minute * 4);
                record.invoke(history, middle + 1000, 62.0 + minute * 4);
            }
            histories.put("coolant", history);
        }
        status("SIMULATED history fixture: " + fixture + "; ten minute coolant timestamps, not recorded vehicle data.");
    }

    private void attachWarning() {
        if (activity == null || activity.isFinishing()) return;
        if (warning == null) {
            warning = new TextView(activity);
            warning.setText(WARNING);
            warning.setTextSize(10);
            warning.setIncludeFontPadding(false);
            warning.setGravity(Gravity.CENTER);
            warning.setSingleLine(true);
            warning.setTextColor(Color.WHITE);
            warning.setBackgroundColor(Color.rgb(112, 40, 12));
            warning.setContentDescription(WARNING);
            warning.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            warning.setClickable(false);
            warning.setFocusable(false);
        }
        if (warning.getParent() == null)
            activity.addContentView(warning, new FrameLayout.LayoutParams(-1, 18, Gravity.BOTTOM));
        warning.bringToFront();
    }

    private final Runnable keepWarning = new Runnable() {
        @Override public void run() {
            if (finished) return;
            attachWarning();
            main.postDelayed(this, 350);
        }
    };

    private final BroadcastReceiver dumpReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (finished) return;
            if (DUMP_ACTION.equals(intent.getAction())) dumpUi();
            else if (FINISH_ACTION.equals(intent.getAction())) {
                stopRequested = true;
                status("SIMULATED OBD QA finish requested; closing the fake link and runner.");
            }
        }
    };

    private final Runnable dumpPeriodically = new Runnable() {
        @Override public void run() {
            if (finished || dumpEveryMs <= 0) return;
            dumpUi();
            main.postDelayed(this, dumpEveryMs);
        }
    };

    /** Public View APIs plus application-field reflection only; never UiAutomation/hidden OS state. */
    private void dumpUi() {
        if (activity == null || activity.isFinishing()) return;
        try {
            JSONObject output = new JSONObject();
            output.put("simulated_qa", true);
            output.put("warning", WARNING);
            output.put("captured_at_ms", System.currentTimeMillis());
            output.put("sequence", ++dumpSequence);
            Object page=appField(activity,"vehiclePage");
            output.put("same_engine",page!=null && appField(page,"engine")==engine);
            if(engine!=null){
                Object snapshot=engine.getClass().getMethod("snapshot").invoke(engine);
                output.put("phase",String.valueOf(snapshot.getClass().getField("phase").get(snapshot)));
            }
            Dialog dialog = showingDialog();
            View decor = dialog == null ? activity.getWindow().getDecorView() : dialog.getWindow().getDecorView();
            output.put("window", dialog == null ? "activity" : "dialog");
            output.put("window_class", dialog == null ? activity.getClass().getName() : dialog.getClass().getName());
            JSONArray nodes = new JSONArray();
            dumpView(decor, -1, nodes);
            output.put("nodes", nodes);
            File target = new File(activity.getFilesDir(), "obd-qa-ui.json");
            File temporary = new File(activity.getFilesDir(), ".obd-qa-ui.json.tmp");
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(output.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(target)) throw new IOException("Could not atomically replace " + target.getName());
            lastDumpError = "";
        } catch (Exception error) {
            String message = error.toString();
            if (!message.equals(lastDumpError)) { status("QA hierarchy dump failed: " + message); lastDumpError = message; }
        }
    }

    private Dialog showingDialog() throws Exception {
        Object page = appField(activity, "vehiclePage");
        Object dialogs = page == null ? null : appField(page, "dialogs");
        if (dialogs instanceof List<?>) {
            List<?> list = (List<?>)dialogs;
            for (int i = list.size() - 1; i >= 0; i--) {
                Object item = list.get(i);
                if (item instanceof Dialog && ((Dialog)item).isShowing() && ((Dialog)item).getWindow() != null)
                    return (Dialog)item;
            }
        }
        Object weather = appField(activity, "weatherPage");
        Object place = weather == null ? null : appField(weather, "placeDialog");
        if (place instanceof Dialog && ((Dialog)place).isShowing() && ((Dialog)place).getWindow() != null)
            return (Dialog)place;
        return null;
    }

    private static Object appField(Object owner, String name) throws Exception {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true); return field.get(owner);
        } catch (NoSuchFieldException absent) { return null; }
    }

    private void dumpView(View view, int parent, JSONArray nodes) throws Exception {
        if (!view.isShown() || view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        Rect visible = new Rect();
        if (!view.getLocalVisibleRect(visible) || visible.isEmpty()) return;
        int[] location = new int[2]; view.getLocationOnScreen(location);
        // Local visible coordinates include the View's content scroll (e.g. a centred
        // single-line TextView). Convert back to viewport coordinates before screen offset.
        visible.offset(location[0] - view.getScrollX(), location[1] - view.getScrollY());
        JSONObject node = new JSONObject();
        int index = nodes.length();
        node.put("index", index); node.put("parent", parent);
        node.put("class", view.getClass().getName());
        node.put("text", view instanceof TextView ? ((TextView)view).getText().toString() : "");
        node.put("contentDesc", view.getContentDescription() == null ? "" : view.getContentDescription().toString());
        node.put("bounds", new JSONArray(new int[] {location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight()}));
        node.put("visibleBounds", new JSONArray(new int[] {visible.left, visible.top, visible.right, visible.bottom}));
        node.put("selected",view.isSelected()); node.put("activated",view.isActivated()); node.put("enabled", view.isEnabled()); node.put("clickable", view.isClickable());
        node.put("focusable", view.isFocusable()); node.put("focused", view.isFocused());
        node.put("scrollable", view.canScrollVertically(1) || view.canScrollVertically(-1)
                || view.canScrollHorizontally(1) || view.canScrollHorizontally(-1));
        nodes.put(node);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) dumpView(group.getChildAt(i), index, nodes);
        }
    }

    private interface CheckedAction { void run() throws Exception; }
    private void runOnMain(CheckedAction action) throws Exception {
        Throwable[] failed = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failed[0] = error; } });
        if (failed[0] != null) throw new Exception(failed[0]);
    }

    private void status(String message) {
        Bundle status = new Bundle(); status.putString("stream", message + "\n"); sendStatus(0, status);
    }

    private int integer(String key, int fallback, int min, int max) {
        String value = arguments.getString(key);
        if (value == null || value.isEmpty()) return fallback;
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        return parsed;
    }

    private boolean bool(String key, boolean fallback) {
        String value = arguments.getString(key);
        return value == null ? fallback : "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static void requireEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        String fingerprint = Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        if (!hardware.contains("ranchu") && !hardware.contains("goldfish") && !fingerprint.startsWith("generic"))
            throw new IllegalStateException("This simulation harness runs only on an Android emulator, never on the CC3 or a physical vehicle device.");
    }

    /** Deterministic, in-memory ELM text channel. No Context, sockets, Bluetooth or vehicle code. */
    private static final class FakeLink implements ObdLink {
        private static final Set<String> SETUP = new HashSet<>(Arrays.asList("ATE0", "ATL0", "ATS1", "ATH1", "ATSP0", "ATSH7E0", "ATSH7DF"));
        private static final int[] FULL = {0x04,0x05,0x0B,0x0C,0x0D,0x0F,0x10,0x11,0x2F,0x33,0x42,0x5C,0x5E};
        private static final int[] LIMITED = {0x05,0x0C,0x42};
        private static final int[] NONE = {};
        private final int staleAfter;
        private final String faultProfile;
        private final String toyota;
        private String header = "7DF";
        private final String stalePid;
        private final int[] pids;
        private byte[] reply = new byte[0];
        private int offset;
        private long openedAt;
        private boolean opened, closed;
        volatile int commands;
        boolean dpf;

        FakeLink(int staleAfter, String stalePid, boolean limited) {
            this(staleAfter, stalePid, limited ? "limited" : "full");
        }

        FakeLink(int staleAfter, String stalePid, String profile) {
            this(staleAfter, stalePid, profile, "partial", "none");
        }
        FakeLink(int staleAfter, String stalePid, String profile, String faultProfile, String toyota) {
            this.toyota = toyota;
            this.staleAfter = staleAfter;
            this.faultProfile = faultProfile;
            this.stalePid = stalePid == null ? "ALL" : stalePid.toUpperCase(Locale.ROOT);
            if (!"ALL".equals(this.stalePid) && !this.stalePid.matches("01[0-9A-F]{2}"))
                throw new IllegalArgumentException("stalePid must be all or a Mode 01 request such as 0105");
            pids = "none".equals(profile) ? NONE : "limited".equals(profile) ? LIMITED : FULL;
        }

        @Override public synchronized void open() throws IOException {
            if (closed || opened) throw new IOException("QA fake link is single-use");
            opened = true; openedAt = System.nanoTime();
        }

        @Override public synchronized void write(byte[] data) throws IOException {
            if (!opened || closed) throw new IOException("QA fake link is closed");
            if (offset < reply.length) throw new IOException("Previous simulated response was not consumed");
            String command = new String(data, StandardCharsets.US_ASCII).trim().toUpperCase(Locale.ROOT);
            if (command.startsWith("21") && !header.equals("7E0")) throw new IOException("Toyota header missing");
            if ((command.startsWith("01") || command.matches("03|07|0A")) && !header.equals("7DF")) throw new IOException("Functional header not restored");
            if (command.equals("ATSH7E0")) header="7E0";
            if (command.equals("ATSH7DF")) header="7DF";
            commands++;
            String response;
            if (command.equals("ATZ") || command.equals("ATI")) response = "SIMULATED QA ELM327 v2.2";
            else if (SETUP.contains(command)) response = "OK";
            else if (command.equals("ATDP")) response = "SIMULATED ISO 15765-4 (CAN 11/500)";
            else if (command.equals("0100")) response = mask(0);
            else if (command.equals("0120")) response = mask(0x20);
            else if (command.equals("0140")) response = mask(0x40);
            else if (command.equals("0160")) response = mask(0x60);
            else if (command.startsWith("21")) response = toyotaResponse(command);
            else if (command.equals("03") || command.equals("07") || command.equals("0A")) response = codeResponse(command);
            else if (command.matches("01[0-9A-F]{2}")) response = sensor(command);
            else throw new IOException("Disallowed command reached QA transport: " + command);
            reply = (response + "\r>").getBytes(StandardCharsets.US_ASCII); offset = 0;
            notifyAll();
        }

        private String toyotaResponse(String command) {
            if (toyota.equals("none")) return "NO DATA";
            if (command.equals("2182")) return "7E8 06 61 82 6E 00 74 00";
            if (command.equals("2138")) return dpf?responseFrame(new int[]{0x61,0x38,0,0,0,0,0,0x80,0xC8,0x80,0,65,0,0,0x80,0x64,0}):responseFrame(new int[]{0x61,0x38,0,0,0,0,0,0,0,0,0,65});
            if (command.equals("2104")) return dpf?responseFrame(new int[]{0x61,4,0,0,0,0,0,0,0,0,0,0x80,0x64}):"NO DATA";
            if (command.equals("2185")) return toyota.equals("partial") ? "7E8 03 7F 21 12" : "7E8 04 61 85 03 80";
            if(command.equals("2105"))return responseFrame(new int[]{0x61,5,0,0x0D,0xAC,0x0C,0xE4});
            if(command.equals("213D"))return toyota.equals("partial")?"NO DATA":responseFrame(new int[]{0x61,0x3D,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,128,144,112,160});
            throw new IllegalArgumentException("Unexpected custom command " + command);
        }
        private static String responseFrame(int[] data) {
            StringBuilder text=new StringBuilder();
            if(data.length<=7){text.append(String.format(Locale.ROOT,"7E8 %02X",data.length));for(int v:data)text.append(String.format(Locale.ROOT," %02X",v));return text.toString();}
            text.append(String.format(Locale.ROOT,"7E8 %02X %02X",0x10|(data.length>>8),data.length&255));
            int offset=0;while(offset<6)text.append(String.format(Locale.ROOT," %02X",data[offset++]));
            int sequence=1;while(offset<data.length){text.append(String.format(Locale.ROOT,"\r7E8 %02X",0x20|(sequence++&15)));for(int i=0;i<7;i++)text.append(String.format(Locale.ROOT," %02X",offset<data.length?data[offset++]:0));}
            return text.toString();
        }

        private String codeResponse(String command) {
            int service = Integer.parseInt(command, 16);
            String empty = String.format(Locale.ROOT, "7E8 02 %02X 00", service + 0x40);
            if ("no-response".equals(faultProfile)) return "NO DATA";
            if ("mixed-empty".equals(faultProfile)) return empty + "\r7E9 03 7F " + command + " 11";
            if ("partial-empty".equals(faultProfile)) return command.equals("03") ? empty
                    : command.equals("07") ? "NO DATA" : "7E8 03 7F 0A 11";
            if ("pending-final".equals(faultProfile)) return command.equals("03")
                    ? "7E8 03 7F 03 78\r7E8 04 43 01 01 33" : empty;
            return command.equals("03") ? "7E8 04 43 01 01 33" : command.equals("07") ? "NO DATA" : empty;
        }

        private String mask(int base) {
            long bits = 0;
            for (int pid : pids) if (pid > base && pid <= base + 32 && !(pid == 0x5C && toyota.equals("missing-oil"))) bits |= 1L << (32 - (pid - base));
            if(dpf && base==0x60)for(int pid:new int[]{0x7A,0x7C})bits|=1L<<(32-(pid-base));
            if (!toyota.equals("none") && base == 0x60) bits |= 1L << (32 - (0x78-base));
            if ((base < 0x40 || !toyota.equals("none") && base == 0x40) && pids.length > 0) bits |= 1; // Follow only actual continuation pages.
            return String.format(Locale.ROOT, "7E8 06 41 %02X %02X %02X %02X %02X", base,
                    (bits >>> 24) & 255, (bits >>> 16) & 255, (bits >>> 8) & 255, bits & 255);
        }

        private String sensor(String command) {
            int pid = Integer.parseInt(command.substring(2), 16);
            if(dpf){
                if(pid==0x7A)return responseFrame(new int[]{0x41,0x7A,7,0,100,0x27,0x10,0x26,0xAC});
                if(pid==0x7C)return responseFrame(new int[]{0x41,0x7C,3,0x19,0,0x1B,0x58});
                if(pid==0x8B)return responseFrame(new int[]{0x41,0x8B,0x73,3,0x80,0,90,1,44});
            }
            if (pid == 0x5C && toyota.equals("missing-oil")) return "NO DATA";
            if (pid == 0x78 && !toyota.equals("none")) return responseFrame(new int[]{0x41,0x78,toyota.equals("partial")?1:7,3,0xE8,7,0xD0,0x0B,0xB8,0,0});
            boolean supported = false;
            for (int known : pids) if (known == pid) supported = true;
            if (!supported) return "NO DATA";
            long seconds = (System.nanoTime() - openedAt) / 1000000000L;
            if (staleAfter > 0 && seconds >= staleAfter && ("ALL".equals(stalePid) || command.equals(stalePid))) return "NO DATA";
            switch (pid) {
                case 0x04: return "7E8 03 41 04 6B"; // 42% load.
                case 0x05: return "7E8 03 41 05 82"; // 90°C coolant.
                case 0x0B: return "7E8 03 41 0B B4"; // 180 kPa absolute.
                case 0x0C: return "7E8 04 41 0C 00 00"; // An actual valid zero, never missing-data replacement.
                case 0x0D: return "7E8 03 41 0D 00";
                case 0x0F: return "7E8 03 41 0F 46"; // 30°C intake air.
                case 0x10: return "7E8 04 41 10 03 84"; // 9 g/s air flow.
                case 0x11: return "7E8 03 41 11 26";
                case 0x2F: return "7E8 03 41 2F B2";
                case 0x33: return "7E8 03 41 33 64";
                case 0x42: return "7E8 04 41 42 35 E8"; // 13.8 V ECU supply.
                case 0x5C: return "7E8 03 41 5C 83";
                case 0x5E: return "7E8 04 41 5E 00 2C";
                default: return "NO DATA";
            }
        }

        @Override public synchronized int read(byte[] target, int start, int length, long timeoutMs) throws IOException {
            if (closed) throw new IOException("QA fake link closed");
            if (length == 0) return 0;
            if (offset >= reply.length) {
                try { wait(Math.max(1, Math.min(timeoutMs, 100))); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("QA read interrupted"); }
            }
            if (closed) throw new IOException("QA fake link closed");
            if (offset >= reply.length) return 0;
            int size = Math.min(Math.min(length, 11), reply.length - offset);
            System.arraycopy(reply, offset, target, start, size); offset += size; return size;
        }

        @Override public String description() { return "SIMULATED QA · in-memory ASCII"; }
        @Override public synchronized void close() { closed = true; reply = new byte[0]; offset = 0; notifyAll(); }
    }
}
