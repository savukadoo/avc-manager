package local.cc3.manager;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import local.cc3.obd.*;
import local.cc3.obd.android.ObdBluetooth;

/** Foreground-only, local OBD monitoring. The dashboard itself never scrolls. */
final class VehiclePage {
    private static final String[] DEFAULT_IDS = {
            "coolant", "rpm", "map", "voltage", "intake", "load", "speed", "throttle", "maf"};
    private static final String[] TABS = {"Dashboard", "Sensors", "Faults"};
    private static final String[] STYLES = {"number", "dial", "trend"};
    private static final long[] REFRESH_CYCLES = {0, 1000, 2000, 5000, 10000};
    private static final long STALE_MS = 10000;
    private final MainActivity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ObdBluetooth bluetooth;
    private final ObdEngine engine;
    private final VehicleSession session;
    private final ObdReconnectPolicy reconnect;
    private static final String AUTO_CONNECT = "obd_auto_connect";
    private final Map<String, ObdBluetooth.DeviceChoice> devices = new LinkedHashMap<>();
    private final List<ObdGaugeView> gauges = new ArrayList<>();
    private final List<Screen.PanelDialog> dialogs = new ArrayList<>();
    private final Map<String, TextView> sensorValues = new LinkedHashMap<>();
    private final Button[] tabs = new Button[TABS.length];
    private final String[] ids = DEFAULT_IDS.clone();
    private final String[] styles = new String[DEFAULT_IDS.length];
    private ObdEngine.Snapshot snapshot;
    private ObdBluetooth.DeviceChoice selected;
    private ObdBluetooth.Transport transport;
    private LinearLayout root, body, deviceRows;
    private TextView statusTitle, statusDetail, adapterStatus, selectedLabel, sensorCount,
            faultState, faultHeading;
    private Button statusBadge, connect, pause, scan, autoConnect, readFaults, refreshSensors, export;
    private String tab = "Dashboard";
    private boolean controlsMode;
    private boolean imperial, active, scanning, verified, faultRequestPending;
    private volatile boolean closed;
    private boolean pendingScan, permissionReturned;
    private long permissionRequestedAt;
    private int count;
    private long refreshMillis;
    private ObdEngine.Phase renderedPhase;
    private Boolean renderedScan, renderedFaultRead, renderedAuto;
    private String scanStatus = "";
    private Screen.PanelDialog configuration;
    private Screen.PanelDialog sensorDialog;
    private String detailId;
    private TextView detailValue, detailUnit, detailQuality;
    private ObdHistoryView detailHistory;
    private TextView dpfCoverage;
    private LinearLayout detailDpfGraph;
    private final Map<String, TextView> detailDpfValues = new LinkedHashMap<>();
    private final Map<String, LinearLayout> detailDpfChoices = new LinkedHashMap<>();
    private Button detailUseCard;
    private int detailCardSlot = -1;

    VehiclePage(MainActivity activity) {
        this.activity = activity;
        session = VehicleSession.get(activity);
        bluetooth = session.bluetooth;
        engine = session.engine;
        reconnect = session.reconnect;
        snapshot = engine.snapshot();
        loadConfiguration();
        engine.setRefreshCycleMillis(refreshMillis);
        restoreAdapter();
    }

    private void onSnapshot(ObdEngine.Snapshot value) {
        if (closed || !matchesProfile(value))
            return;
        ObdEngine.Snapshot previous = snapshot;
        snapshot = value;
        if (value.phase == ObdEngine.Phase.CONNECTING || value.phase == ObdEngine.Phase.DISCONNECTED)
            verified = false;
        if (value.phase == ObdEngine.Phase.CONNECTING || value.phase == ObdEngine.Phase.DISCONNECTED
                || value.phase == ObdEngine.Phase.ERROR || value.phase == ObdEngine.Phase.PAUSED
                || clean(value.status).startsWith("Reading fault"))
            faultRequestPending = false;
        if (value.phase == ObdEngine.Phase.MONITORING || value.phase == ObdEngine.Phase.PAUSED)
            verified = true;
        if (!active || root == null)
            return;
        boolean faultsChanged = previous == null || previous.faultsLoaded != value.faultsLoaded
                || previous.faultsAttempted != value.faultsAttempted
                || !clean(previous.faultStatus).equals(clean(value.faultStatus))
                || !clean(previous.status).equals(clean(value.status))
                || !sameFaults(previous.faults, value.faults);
        if ("Faults".equals(tab) && faultsChanged)
            showTab(tab);
        else
            update();
    }

    private boolean matchesProfile(ObdEngine.Snapshot value) {
        return engine != null && value.profileId.equals(engine.profile().id);
    }

    View build() {
        controlsMode=false;
        if("Adapter".equals(tab))tab="Dashboard";
        root = activity.column();
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        LinearLayout heading = activity.row();
        heading.addView(
                activity.helpHeading("Vehicle", 24,
                        "Read-only OBD monitoring for the selected vehicle profile. Connect your "
                                + "plugged-in adapter to discover the sensors the ECU actually supplies. "
                                + "All readings stay on this device. The connection continues between "
                                + "Vehicle settings and visible Home metric tiles. Leaving these screens disconnects the "
                                + "adapter and stops polling. "
                                + "This version does not clear codes, operate vehicle systems or "
                                + "change ECU settings."),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        statusBadge = activity.button("Not connected  ›", false, () -> activity.showControl("Vehicle"));
        statusBadge.setTextSize(12);
        statusBadge.setMinHeight(dp(48));
        statusBadge.setSingleLine(true);
        statusBadge.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams badgeBounds = new LinearLayout.LayoutParams(dp(262), dp(48));
        badgeBounds.rightMargin = dp(8);
        heading.addView(statusBadge, badgeBounds);
        Button configure = activity.button("Configure", false, this::showConfiguration);
        heading.addView(configure, new LinearLayout.LayoutParams(-2, dp(56)));
        root.addView(heading, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout navigation = activity.row();
        for (int i = 0; i < TABS.length; i++) {
            final String name = TABS[i];
            tabs[i] = activity.button(name, false, () -> showTab(name));
            fixedButton(navigation, tabs[i], 1, 56);
        }
        LinearLayout.LayoutParams navBounds = new LinearLayout.LayoutParams(-1, dp(64));
        navBounds.topMargin = dp(5);
        root.addView(navigation, navBounds);

        body = activity.column();
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(-1, 0, 1);
        fill.topMargin = dp(10);
        root.addView(body, fill);
        showTab(tab);
        return root;
    }

    View buildControls() {
        controlsMode=true;
        root=activity.column();root.setPadding(dp(16),dp(12),dp(16),dp(16));
        root.addView(activity.controlsHeader("Vehicle settings",
                "Choose the vehicle profile and manage its OBD adapter. The selected profile applies to Dashboard, Sensors and Faults."));
        statusBadge=null;
        body=activity.column();LinearLayout.LayoutParams fill=new LinearLayout.LayoutParams(-1,0,1);fill.topMargin=dp(10);root.addView(body,fill);
        showTab("Adapter");return root;
    }

    private LinearLayout connectionPanel() {
        LinearLayout connection = activity.card();
        LinearLayout summary = activity.column();
        statusTitle = line("Not connected", 20, activity.colors.ink, 650);
        statusDetail = activity.text("", 12, activity.colors.muted, 450);
        statusDetail.setMaxLines(2);
        statusTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        summary.addView(statusTitle, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams detailBounds = new LinearLayout.LayoutParams(-1, -2);
        detailBounds.topMargin = dp(3);
        summary.addView(statusDetail, detailBounds);
        connection.addView(summary, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = activity.row();
        pause = activity.button("Pause", false, this::togglePause);
        LinearLayout.LayoutParams pauseBounds = new LinearLayout.LayoutParams(0, dp(56), 1);
        actions.addView(pause, pauseBounds);
        connect = activity.button("Connect", true, this::toggleConnection);
        LinearLayout.LayoutParams connectBounds = new LinearLayout.LayoutParams(0, dp(56), 1);
        connectBounds.leftMargin = dp(8);
        actions.addView(connect, connectBounds);
        connection.addView(actions);
        return connection;
    }

    void onResume() {
        if (closed)
            return;
        active = true;
        session.enter(this, this::onSnapshot);
        if (pendingScan)
            reconnect.suppress();
        snapshot = engine.snapshot();
        verified = isMonitoring() || phase() == ObdEngine.Phase.PAUSED;
        refreshPaired();
        update();
        ui.removeCallbacks(tick);
        ui.post(tick);
        finishPermissionAction();
        connectDefaultIfReady();
    }

    void onLeave() {
        active = false;
        scanning = false;
        scanStatus = "";
        session.leave(this);
        verified = false;
        ui.removeCallbacks(tick);
        for (Screen.PanelDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        dialogs.clear();
        configuration = null;
        clearSensorDetails();
    }

    void close() {
        if (closed)
            return;
        onLeave();
        closed = true;
        ui.removeCallbacksAndMessages(null);
    }

    void onPermissionsResult() {
        if (closed)
            return;
        permissionReturned = true;
        refreshPaired();
        if (active) {
            update();
            connectDefaultIfReady();
            finishPermissionAction();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active || closed)
                return;
            update();
            connectDefaultIfReady();
            ui.postDelayed(this, 1000);
        }
    };

    private void finishPermissionAction() {
        if (!active || !permissionReturned)
            return;
        permissionReturned = false;
        boolean run = pendingScan && System.currentTimeMillis() - permissionRequestedAt < 60000;
        pendingScan = false;
        if (run && activity.hasObdScanPermission() && activity.hasObdConnectPermission()
                && bluetooth.enabled())
            beginScan();
        else
            update();
    }

    private void loadConfiguration() {
        int savedCount = activity.prefs().getInt("obd_tile_count", 6);
        count = savedCount == 4 || savedCount == 9 ? savedCount : 6;
        imperial = activity.prefs().getBoolean("obd_imperial", false);
        refreshMillis = activity.prefs().getLong("obd_refresh_ms", 0);
        boolean validCycle = false;
        for (long cycle : REFRESH_CYCLES)
            if (cycle == refreshMillis)
                validCycle = true;
        if (!validCycle)
            refreshMillis = 0;
        for (int i = 0; i < ids.length; i++) {
            String id = activity.prefs().getString("obd_tile_" + i, DEFAULT_IDS[i]);
            ids[i] = engine.profile().sensor(id) != null ? id
                    : engine.profile().sensor(DEFAULT_IDS[i]) != null
                    ? DEFAULT_IDS[i]
                    : engine.profile().sensors.get(i % engine.profile().sensors.size()).id;
            String style = activity.prefs().getString("obd_style_" + i, "number");
            styles[i] = "dial".equals(style) || "trend".equals(style) ? style : "number";
        }
    }

    private void saveConfiguration() {
        android.content.SharedPreferences.Editor edit = activity.prefs()
                                                                .edit()
                                                                .putInt("obd_tile_count", count)
                                                                .putBoolean("obd_imperial", imperial)
                                                                .putLong("obd_refresh_ms", refreshMillis);
        for (int i = 0; i < ids.length; i++)
            edit.putString("obd_tile_" + i, ids[i]).putString("obd_style_" + i, styles[i]);
        edit.apply();
        if ("Dashboard".equals(tab))
            showTab(tab);
        else
            update();
    }

    private void restoreAdapter() {
        transport = VehicleSession.savedTransport(activity.prefs());
        selected = VehicleSession.savedAdapter(activity.prefs());
    }

    private void selectAdapter(ObdBluetooth.DeviceChoice device) {
        selected = device;
        transport = device.transport;
        activity.prefs()
                .edit()
                .putString("obd_transport", transport.name())
                .putString("obd_adapter_name", deviceName(device))
                .putString("obd_adapter_address", device.address)
                .apply();
        reconnect.reset();
        if ("Adapter".equals(tab))
            showTab(tab);
        else
            update();
    }

    void showDashboard() {
        for (Screen.PanelDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        showTab("Dashboard");
    }

    private void showTab(String value) {
        if("Adapter".equals(value) && !controlsMode){activity.showControl("Vehicle");return;}
        tab = value;
        renderedPhase = null;
        renderedScan = null;
        renderedFaultRead = null;
        renderedAuto = null;
        if (body == null)
            return;
        for (int i = 0; i < tabs.length; i++)
            if(tabs[i]!=null)activity.styleButton(tabs[i], activity.colors, false, false,
                    TABS[i].equals(tab) || (TABS[i].equals("Sensors") && tab.equals("DPF checks")));
        body.removeAllViews();
        gauges.clear();
        sensorValues.clear();
        dpfCoverage = null;
        statusTitle = null;
        statusDetail = null;
        connect = null;
        pause = null;
        adapterStatus = null;
        selectedLabel = null;
        sensorCount = null;
        scan = null;
        autoConnect = null;
        readFaults = null;
        refreshSensors = null;
        deviceRows = null;
        faultState = null;
        faultHeading = null;
        export = null;
        if ("Dashboard".equals(tab))
            dashboard();
        else if ("DPF checks".equals(tab))
            dpfChecks();
        else if ("Sensors".equals(tab))
            sensors();
        else if ("Faults".equals(tab))
            faults();
        else
            adapter();
        update();
    }

    private void dashboard() {
        gauges.addAll(VehicleDashboard.build(activity,body,count,slot->sensorDetails(ids[slot],slot)));
    }

    private String checkLabel(String id) {
        if (snapshot == null || snapshot.sensorChecks.get(id) == null)
            return "Not checked";
        return snapshot.sensorChecks.get(id).label();
    }

    boolean handleBack() {
        if (tab.equals("DPF checks")) {
            showTab("Sensors");
            return true;
        }
        return false;
    }

    private void dpfChecks() {
        LinearLayout heading = activity.row();
        heading.addView(activity.helpHeading("DPF support checks", 18,
                                "Read-only checks for this vehicle profile. A reply establishes transport "
                                        + "support, not a custom definition's accuracy. "
                                        + "Tap a reading for its definition, raw reply and history. Failed "
                                        + "checks remain visible for review, and responding channels can "
                                        + "be selected in Configure. "
                                        + "Recheck repeats the fixed discovery set; it does not search "
                                        + "arbitrary PIDs or command regeneration."),
                new LinearLayout.LayoutParams(0, dp(56), 1));
        Button back = activity.button("‹ Sensors", false, () -> showTab("Sensors"));
        LinearLayout.LayoutParams backBounds = new LinearLayout.LayoutParams(dp(160), dp(56));
        backBounds.rightMargin = dp(8);
        heading.addView(back, backBounds);
        refreshSensors = activity.button("Recheck", false, () -> {
            if (isMonitoring()) {
                verified = false;
                engine.refreshSensors();
            }
        });
        heading.addView(refreshSensors, new LinearLayout.LayoutParams(dp(160), dp(56)));
        Button report = activity.button("Save report", false, this::saveLocalReport);
        LinearLayout.LayoutParams reportBounds = new LinearLayout.LayoutParams(dp(176), dp(56));
        reportBounds.leftMargin = dp(8);
        heading.addView(report, reportBounds);
        body.addView(heading);
        dpfCoverage = line("Connect to check DPF channels", 13, activity.colors.muted, 500);
        dpfCoverage.setPadding(dp(4), dp(4), 0, dp(8));
        body.addView(dpfCoverage);
        LinearLayout list = activity.column();
        dpfHeading(list, "Live channels",
                "The three exhaust-temperature channels keep their original sensor numbers. DPF inlet/outlet "
                        + "temperatures require their own support flags; their position is not inferred from "
                        + "EGT1/2/3.");
        for (ObdPid pid : categorySensors("dpf")) {
            String id = pid.id;
            LinearLayout entry = activity.row();
            entry.setPadding(dp(16), dp(12), dp(16), dp(12));
            entry.setBackground(activity.shape(activity.colors.surface, 12));
            TextView label =
                    line(pid.label + (pid.experimental ? " · Test" : ""), 15, activity.colors.ink, 600);
            entry.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView value = line("Not checked", 13, activity.colors.muted, 500);
            value.setGravity(Gravity.END);
            entry.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            entry.setMinimumHeight(dp(76));
            entry.setClickable(true);
            entry.setFocusable(true);
            entry.setContentDescription(pid.label + ", open DPF reading details");
            entry.setOnClickListener(v -> sensorDetails(id, -1));
            sensorValues.put(id, value);
            addSpaced(list, entry, 6);
        }
        body.addView(scroll(list), new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void dpfHeading(LinearLayout list, String title, String help) {
        LinearLayout heading = activity.helpHeading(title, 17, help);
        heading.setPadding(dp(4), dp(8), 0, 0);
        list.addView(heading, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private void updateDpf() {
        if (dpfCoverage == null)
            return;
        int replies = 0, unconfirmed = 0;
        for (ObdPid pid : engine.profile().sensors)
            if (pid.dpf()) {
                ObdSensorCheck check = snapshot == null ? null : snapshot.sensorChecks.get(pid.id);
                if (check != null && check.state == ObdSensorCheck.State.RESPONDING)
                    replies++;
                else
                    unconfirmed++;
            }
        setText(dpfCoverage,
                replies + " responding · " + unconfirmed
                        + " unconfirmed · Custom definitions require comparison");
    }

    private void showVehicleProfiles() {
        Screen.PanelDialog panel = activity.new PanelDialog("Vehicle profile", 900,
                "Choose the profile for this vehicle. Standard OBD-II sends no manufacturer requests. "
                        + "Enhanced profiles use fixed read-only requests and apply only to matching "
                        + "ECUs/protocols. "
                        + "Changing profiles disconnects the adapter and clears the current session; "
                        + "reconnect here in Vehicle settings. Saved adapter and dashboard settings are retained.");
        for (ObdVehicleProfile profile : ObdProfiles.all()) {
            boolean selected = engine.profile().id.equals(profile.id);
            Button choice = activity.button(profile.label + (selected ? "  ✓" : ""), false, () -> {
                if (engine.profile().id.equals(profile.id)) {
                    panel.dismiss();
                    return;
                }
                reconnect.suppress();
                VehicleProfiles.select(activity.prefs(),engine,profile);
                snapshot = engine.snapshot();
                verified = false;
                faultRequestPending = false;
                loadConfiguration();
                panel.dismiss();
                showTab(controlsMode?"Adapter":"Sensors");
            });
            choice.setSelected(selected);
            activity.styleButton(choice, activity.colors, false, false, selected);
            addSpaced(panel.body, choice, 6);
            TextView help = line(profile.description, 13, activity.colors.muted, 450);
            help.setSingleLine(false);
            help.setEllipsize(null);
            addSpaced(panel.body, help, 4);
        }
        panel.action("Sources", false, this::showProfileSources);
        panel.action("Close", true, panel::dismiss);
        showDialog(panel);
    }

    private void showProfileSources() {
        Screen.PanelDialog panel = activity.new PanelDialog(engine.profile().label + " · Sources", 900);
        panel.message(engine.profile().sources);
        panel.action("Close", true, panel::dismiss);
        showDialog(panel);
    }

    private List<ObdPid> categorySensors(String category) {
        List<ObdPid> result = new ArrayList<>();
        for (ObdPid pid : engine.profile().sensors)
            if (pid.category.equals(category))
                result.add(pid);
        return result;
    }

    private void sensors() {
        LinearLayout heading = activity.row();
        heading.addView(activity.helpHeading("Available sensors", 18,
                                "Support is discovered from the connected ECU. A registered PID is not proof "
                                        + "that this vehicle supplies it. "
                                        + "Manifold pressure is absolute pressure, not calculated boost. ECU "
                                        + "voltage is separate from adapter voltage. "
                                        + "Tap a sensor for its source and sample age."),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        refreshSensors = activity.button("Refresh sensors", false, () -> {
            if (isMonitoring()) {
                verified = false;
                engine.refreshSensors();
            }
        });
        heading.addView(refreshSensors, new LinearLayout.LayoutParams(-2, dp(56)));
        Button dpfChecks = activity.button("DPF checks", false, () -> showTab("DPF checks"));
        LinearLayout.LayoutParams dpfBounds = new LinearLayout.LayoutParams(dp(160), dp(56));
        dpfBounds.leftMargin = dp(8);
        if (engine.profile().category("dpf") != null)
            heading.addView(dpfChecks, dpfBounds);
        body.addView(heading);
        sensorCount = activity.text("Not yet verified", 13, activity.colors.muted, 450);
        sensorCount.setPadding(dp(4), dp(4), 0, dp(8));
        body.addView(sensorCount);
        LinearLayout list = activity.column();
        for (ObdPid pid : engine.profile().sensors) {
            LinearLayout entry = activity.row();
            entry.setPadding(dp(16), dp(12), dp(16), dp(12));
            entry.setBackground(activity.shape(activity.colors.surface, 12));
            TextView label = line(pid.label, 16, activity.colors.ink, 600);
            entry.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView value = line("—", 14, activity.colors.muted, 500);
            value.setGravity(Gravity.END);
            entry.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            entry.setMinimumHeight(dp(72));
            entry.setClickable(true);
            entry.setFocusable(true);
            entry.setContentDescription(pid.label + ", open sensor details");
            entry.setOnClickListener(v -> sensorDetails(pid.id, -1));
            sensorValues.put(pid.id, value);
            addSpaced(list, entry, 6);
        }
        body.addView(scroll(list), new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void faults() {
        LinearLayout heading = activity.row();
        heading.addView(
                activity.helpHeading("Diagnostic codes", 18,
                        "Read stored, pending and permanent standard powertrain codes. Empty results "
                                + "describe only the services that responded; "
                                + "they do not establish that every vehicle system is fault-free. A code "
                                + "identifies a diagnostic condition, not a confirmed part failure. "
                                + "This version never clears codes or resets readiness."),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        readFaults = activity.button("Read codes", true, () -> {
            if (isMonitoring()) {
                faultRequestPending = true;
                engine.readFaults();
                update();
            }
        });
        heading.addView(readFaults, new LinearLayout.LayoutParams(-2, dp(56)));
        export = activity.button("Export report", false, this::exportReport);
        LinearLayout.LayoutParams exportBounds = new LinearLayout.LayoutParams(-2, dp(56));
        exportBounds.leftMargin = dp(8);
        heading.addView(export, exportBounds);
        body.addView(heading);
        LinearLayout categories = activity.row();
        for (int service : new int[] {3, 7, 10}) {
            ObdFaultResult result = ObdFaultResult.waiting(service);
            if (snapshot != null)
                for (ObdFaultResult candidate : snapshot.faultResults)
                    if (candidate.service == service)
                        result = candidate;
            LinearLayout card = activity.column();
            card.setPadding(dp(12), dp(8), dp(12), dp(8));
            card.setBackground(activity.shape(activity.colors.surface, 12));
            card.addView(activity.helpHeading(result.category, 16,
                                 result.detail
                                         + (" This result applies to the engine ECU selected during sensor "
                                                 + "discovery.")
                                         + " No response is not the same as no codes."),
                    new LinearLayout.LayoutParams(-1, dp(48)));
            TextView status = line(result.label(), 18,
                    result.state == ObdFaultResult.State.READ ? activity.colors.accent
                                                              : activity.colors.muted,
                    600);
            status.setMaxLines(2);
            status.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
            card.setContentDescription(result.category + ": " + result.label() + ". " + result.detail);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
            if (categories.getChildCount() > 0)
                cell.leftMargin = dp(8);
            categories.addView(card, cell);
        }
        LinearLayout.LayoutParams categoryBounds = new LinearLayout.LayoutParams(-1, dp(112));
        categoryBounds.topMargin = dp(8);
        body.addView(categories, categoryBounds);
        LinearLayout list = activity.column();
        list.setPadding(dp(14), dp(12), dp(14), dp(12));
        list.setBackground(activity.shape(activity.colors.surface, 12));
        faultHeading = activity.text("", 21, activity.colors.ink, 600);
        list.addView(faultHeading, new LinearLayout.LayoutParams(-1, -2));
        faultState = activity.text("", 14, activity.colors.muted, 450);
        faultState.setPadding(0, dp(8), 0, dp(8));
        list.addView(faultState);
        if (snapshot == null || snapshot.faults.isEmpty()) {
            list.setGravity(Gravity.CENTER);
            faultHeading.setGravity(Gravity.CENTER);
            faultState.setGravity(Gravity.CENTER);
        } else {
            for (ObdEngine.Fault fault : snapshot.faults) {
                LinearLayout card = activity.card();
                LinearLayout row = activity.row();
                row.addView(activity.text(fault.code, 24, activity.colors.accent, 650),
                        new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(activity.text(fault.category, 15, activity.colors.ink, 600));
                card.addView(row);
                TextView source = activity.text("ECU " + clean(fault.source), 12, activity.colors.muted, 450);
                source.setPadding(0, dp(7), 0, 0);
                card.addView(source);
                addSpaced(list, card, 8);
            }
        }
        ScrollView scroll = scroll(list);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(-1, 0, 1);
        fill.topMargin = dp(8);
        body.addView(scroll, fill);
    }

    private void adapter() {
        LinearLayout overview = activity.row();
        overview.setGravity(Gravity.TOP);
        LinearLayout profile = activity.card();
        profile.addView(line("Vehicle profile", 13, activity.colors.muted, 500));
        TextView profileName = line(engine.profile().label, 17, activity.colors.ink, 650);
        profile.addView(profileName, new LinearLayout.LayoutParams(-1, 0, 1));
        profile.addView(activity.button("Choose profile", false, this::showVehicleProfiles),
                new LinearLayout.LayoutParams(-1, dp(56)));
        overview.addView(profile, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams statusBounds = new LinearLayout.LayoutParams(0, -1, 1);
        statusBounds.leftMargin = dp(12);
        overview.addView(connectionPanel(), statusBounds);
        body.addView(overview, new LinearLayout.LayoutParams(-1, dp(176)));
        LinearLayout panel = activity.row();
        panel.setGravity(Gravity.TOP);
        LinearLayout settings = activity.card();
        LinearLayout connectionHeading = activity.row();
        connectionHeading.addView(line("Connection", 18, activity.colors.ink, 650));
        android.widget.ImageButton connectionInfo = activity.infoButton("Connection details", "");
        connectionInfo.setOnClickListener(view -> showConnectionDetails());
        connectionHeading.addView(connectionInfo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        settings.addView(connectionHeading);
        adapterStatus = activity.text("", 13, activity.colors.muted, 450);
        adapterStatus.setPadding(dp(4), 0, dp(4), dp(8));
        settings.addView(adapterStatus);
        LinearLayout routes = activity.row();
        Button ble =
                activity.button("Bluetooth LE", false, () -> changeTransport(ObdBluetooth.Transport.BLE));
        Button classic =
                activity.button("Classic", false, () -> changeTransport(ObdBluetooth.Transport.CLASSIC));
        activity.styleButton(ble, activity.colors, false, false, transport == ObdBluetooth.Transport.BLE);
        activity.styleButton(
                classic, activity.colors, false, false, transport == ObdBluetooth.Transport.CLASSIC);
        fixedButton(routes, ble, 1, 56);
        fixedButton(routes, classic, 1, 56);
        addSpaced(settings, routes, 6);
        TextView defaultLabel = line("Default adapter", 13, activity.colors.muted, 500);
        defaultLabel.setPadding(dp(4), dp(16), dp(4), dp(4));
        settings.addView(defaultLabel);
        selectedLabel = line("", 17, activity.colors.ink, 600);
        selectedLabel.setPadding(dp(4), 0, dp(4), dp(12));
        settings.addView(selectedLabel);
        autoConnect = activity.button("", false, this::toggleAutoConnect);
        settings.addView(autoConnect, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout.LayoutParams settingsBounds = new LinearLayout.LayoutParams(0, -1, 1);
        panel.addView(scroll(settings), settingsBounds);

        LinearLayout available = activity.column();
        available.setPadding(dp(14), dp(8), dp(14), dp(10));
        available.setBackground(activity.shape(activity.colors.surface, 12));
        available.addView(activity.helpHeading("Adapters", 18,
                "Paired devices appear without a scan when Android permits access. BLE adapters need not be "
                        + "paired in Android. "
                        + "A device appearing in this list is not proof of OBD compatibility. Select your "
                        + "Veepeak; do not connect a phone or audio receiver."));
        scan = activity.button("Scan for adapters", false, this::scanPressed);
        addSpaced(available, scan, 6);
        deviceRows = activity.column();
        available.addView(scroll(deviceRows), new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams listBounds = new LinearLayout.LayoutParams(0, -1, 1);
        listBounds.leftMargin = dp(12);
        panel.addView(available, listBounds);
        LinearLayout.LayoutParams panelBounds = new LinearLayout.LayoutParams(-1, 0, 1);
        panelBounds.topMargin = dp(10);
        body.addView(panel, panelBounds);
        refreshPaired();
        renderDevices();
    }

    private void showConnectionDetails() {
        Screen.PanelDialog panel = activity.new PanelDialog("Connection details", 800);
        String details = "Profile · " + engine.profile().label;
        if (snapshot != null) {
            if (!clean(snapshot.adapterInfo).isEmpty()) details += "\n" + clean(snapshot.adapterInfo);
            if (!clean(snapshot.protocol).isEmpty()) details += "\nProtocol · " + clean(snapshot.protocol);
            if (!clean(snapshot.transport).isEmpty()) details += "\nTransport · " + clean(snapshot.transport);
            if (!clean(snapshot.ecu).isEmpty()) details += "\nECU · " + clean(snapshot.ecu);
            if (isConnected()) details += String.format(Locale.getDefault(), "\n%.1f queries/s", snapshot.queriesPerSecond);
        }
        panel.message(details);
        panel.message("Auto-connect uses your saved adapter when you open Vehicle or its settings, with up to three attempts per visit. Disconnect or scanning pauses it for this visit. Bluetooth LE is preferred for Veepeak OBDCheck BLE+; Classic is a fallback.");
        panel.action("Close", false, panel::dismiss);
        showDialog(panel);
    }

    private void update() {
        if (root == null || closed)
            return;
        ObdEngine.Phase phase = phase();
        boolean connected = isConnected();
        boolean phaseChanged = renderedPhase != phase;
        setText(statusTitle, phaseTitle(phase));
        setText(statusDetail,
                phase == ObdEngine.Phase.DISCONNECTED && automatic() && reconnect.suppressed()
                        ? "Auto-connect paused for this visit"
                        : snapshot == null ? "Select an adapter to begin"
                                           : clean(snapshot.status));
        if (statusTitle != null)
            statusTitle.setTextColor(
                    phase == ObdEngine.Phase.MONITORING ? activity.colors.accent : activity.colors.ink);
        if (statusBadge != null) {
            setText(statusBadge, "●  " + phaseTitle(phase) + "  ›");
            String description = "Adapter · " + phaseTitle(phase)
                    + (snapshot == null ? "" : ". " + clean(snapshot.status));
            if (!TextUtils.equals(description, statusBadge.getContentDescription()))
                statusBadge.setContentDescription(description);
            if (phaseChanged)
                activity.styleButton(statusBadge, activity.colors, false, false,
                        phase == ObdEngine.Phase.MONITORING || phase == ObdEngine.Phase.PAUSED);
        }
        if (connect != null) {
            setText(connect,
                    connected ? (phase == ObdEngine.Phase.CONNECTING ? "Cancel" : "Disconnect") : "Connect");
            if (phaseChanged)
                activity.styleButton(connect, activity.colors, connected, false, connected);
        }
        if (pause != null) {
            setText(pause, phase == ObdEngine.Phase.PAUSED ? "Resume" : "Pause");
            pause.setEnabled(phase == ObdEngine.Phase.MONITORING || phase == ObdEngine.Phase.PAUSED);
            if (phaseChanged)
                activity.styleButton(pause, activity.colors, false, false, phase == ObdEngine.Phase.PAUSED);
        }
        for (int i = 0; i < gauges.size(); i++) {
            ObdPid pid = ObdPid.byId(ids[i]);
            ObdEngine.Reading reading = reading(ids[i]);
            gauges.get(i).bind(pid, reading, imperial, styles[i], cardQuality(ids[i], reading), fresh(reading));
        }
        for (Map.Entry<String, TextView> entry : sensorValues.entrySet()) {
            ObdPid pid = ObdPid.byId(entry.getKey());
            ObdEngine.Reading reading = reading(pid.id);
            String state = cardQuality(pid.id, reading);
            String value = hasSample(reading) ? pid.valueText(reading.value, imperial) + " " + pid.unit(imperial)
                    + "  ·  " + state : state.isEmpty() ? "— " + pid.unit(imperial) : state;
            setText(entry.getValue(), value);
            entry.getValue().setTextColor(fresh(reading) ? activity.colors.accent : activity.colors.muted);
        }
        updateDpf();
        if (sensorCount != null)
            setText(sensorCount,
                    verified && snapshot != null
                            ? snapshot.supported.size() + " available channels · " + engine.profile().label
                            : engine.profile().label + " · Support not yet verified");
        if (refreshSensors != null)
            refreshSensors.setEnabled(isMonitoring());

        if (readFaults != null) {
            setText(readFaults, readingFaults() ? "Reading…" : "Read codes");
            readFaults.setEnabled(isMonitoring() && !readingFaults());
            if (renderedFaultRead == null || renderedFaultRead != readingFaults())
                activity.styleButton(readFaults, activity.colors, readingFaults(), false, readingFaults());
            renderedFaultRead = readingFaults();
        }
        if (export != null)
            export.setEnabled(snapshot != null && (snapshot.faultsAttempted || !snapshot.readings.isEmpty()));
        if (faultState != null) {
            boolean attempted = snapshot != null && snapshot.faultsAttempted;
            int read = 0;
            if (snapshot != null)
                for (ObdFaultResult result : snapshot.faultResults)
                    if (result.state == ObdFaultResult.State.READ)
                        read++;
            setText(faultHeading,
                    readingFaults()      ? "Reading diagnostic codes"
                            : !attempted ? "Codes not read"
                            : snapshot.faultsLoaded
                            ? snapshot.faults.isEmpty() ? "No codes reported" : "Reported codes"
                            : read > 0 || !snapshot.faults.isEmpty() ? "Partial code results"
                                                                     : "No completed code results");
            setText(faultState,
                    readingFaults() ? "Each category updates as its response arrives. No codes are cleared."
                            : attempted      ? "Last scan · " + read + " of 3 engine categories read"
                            : isMonitoring() ? "Ready to read the three standard engine-code categories."
                                             : "Connect and resume readings first.");
        }
        if (adapterStatus != null) {
            String available = !activity.hasObdConnectPermission() ? "Bluetooth access required"
                                                                   : bluetooth.availability();
            setText(adapterStatus, scanStatus.isEmpty() ? available : scanStatus);
            setText(selectedLabel,
                    selected == null ? "No default adapter"
                                     : deviceName(selected) + " · "
                                    + transportLabel(selected.transport));
            setText(autoConnect, automatic() ? "Auto-connect On  ✓" : "Auto-connect Off");
            autoConnect.setEnabled(selected != null || automatic());
            autoConnect.setSelected(automatic());
            if (renderedAuto == null || renderedAuto != automatic())
                activity.styleButton(autoConnect, activity.colors, false, false, automatic());
            renderedAuto = automatic();
            if (automatic() && reconnect.exhausted() && !isConnected())
                setText(adapterStatus, "Auto-connect paused after three attempts. Tap Connect to retry.");
            setText(scan,
                    scanning                         ? "Stop scan"
                            : !bluetooth.supported() ? "Bluetooth unavailable"
                            : !activity.hasObdScanPermission() || !activity.hasObdConnectPermission()
                            ? "Allow Bluetooth access"
                            : !bluetooth.enabled() ? "Enable Bluetooth"
                                                   : "Scan for adapters");
            scan.setEnabled(bluetooth.supported());
            if (renderedScan == null || renderedScan != scanning)
                activity.styleButton(scan, activity.colors, scanning, false, scanning);
            renderedScan = scanning;
        }
        if (phaseChanged && deviceRows != null)
            renderDevices();
        updateSensorDetails();
        renderedPhase = phase;
    }

    private static void setText(TextView view, String value) {
        if (view != null && !value.contentEquals(view.getText()))
            view.setText(value);
    }

    private void toggleConnection() {
        if (isConnected()) {
            reconnect.suppress();
            engine.disconnect();
            return;
        }
        reconnect.suppress();
        if (!activity.hasObdConnectPermission()) {
            activity.requestObdPermissions();
            return;
        }
        if (!bluetooth.supported()) {
            showTab("Adapter");
            notice(bluetooth.availability());
            return;
        }
        if (!bluetooth.enabled()) {
            activity.requestObdBluetoothEnable();
            return;
        }
        if (selected == null) {
            showTab("Adapter");
            return;
        }
        bluetooth.stopScan();
        scanning = false;
        scanStatus = "";
        verified = false;
        engine.connect(bluetooth.link(selected));
    }

    private boolean automatic() {
        return activity.prefs().getBoolean(AUTO_CONNECT, false);
    }

    private void toggleAutoConnect() {
        boolean enabled = !automatic();
        activity.prefs().edit().putBoolean(AUTO_CONNECT, enabled).apply();
        if (enabled)
            reconnect.reset();
        else
            reconnect.suppress();
        update();
        connectDefaultIfReady();
    }

    private void connectDefaultIfReady() {
        if (!active || closed)
            return;
        if (session.connectDefault(this, activity.prefs(), selected,
                scanning || pendingScan || !activity.hasObdConnectPermission())) {
            verified = false;
            scanStatus = "";
        }
    }

    private void togglePause() {
        if (phase() == ObdEngine.Phase.PAUSED)
            engine.resume();
        else if (phase() == ObdEngine.Phase.MONITORING)
            engine.pause();
    }

    private void scanPressed() {
        reconnect.suppress();
        if (scanning) {
            bluetooth.stopScan();
            scanning = false;
            scanStatus = "Scan stopped";
            update();
            return;
        }
        if (isConnected()) {
            notice("Disconnect before scanning for another adapter.");
            return;
        }
        if (!bluetooth.supported()) {
            update();
            return;
        }
        if (!activity.hasObdScanPermission() || !activity.hasObdConnectPermission()) {
            pendingScan = true;
            permissionReturned = false;
            permissionRequestedAt = System.currentTimeMillis();
            activity.requestObdPermissions();
            return;
        }
        if (!bluetooth.enabled()) {
            pendingScan = true;
            permissionReturned = false;
            permissionRequestedAt = System.currentTimeMillis();
            activity.requestObdBluetoothEnable();
            return;
        }
        beginScan();
    }

    private void beginScan() {
        if (!active || closed || scanning)
            return;
        reconnect.suppress();
        devices.clear();
        refreshPaired();
        scanning = true;
        scanStatus = "Looking for nearby adapters…";
        renderDevices();
        update();
        bluetooth.scan(new ObdBluetooth.ScanListener() {
            @Override
            public void onDevice(ObdBluetooth.DeviceChoice device) {
                if (!active || closed || !scanning)
                    return;
                devices.put(deviceKey(device), device);
                renderDevices();
            }
            @Override
            public void onStatus(String status) {
                if (!active || closed || !scanning)
                    return;
                scanStatus = clean(status);
                update();
            }
            @Override
            public void onFinished() {
                if (!active || closed)
                    return;
                scanning = false;
                if (scanStatus.startsWith("Looking") || scanStatus.startsWith("Scanning")
                        || scanStatus.isEmpty())
                    scanStatus = "Scan finished";
                renderDevices();
                update();
            }
        });
    }

    private void refreshPaired() {
        if (closed || !activity.hasObdConnectPermission())
            return;
        for (ObdBluetooth.DeviceChoice device : bluetooth.paired()) devices.put(deviceKey(device), device);
        renderDevices();
    }

    private void changeTransport(ObdBluetooth.Transport value) {
        if (isConnected()) {
            notice("Disconnect before changing transport.");
            return;
        }
        transport = value;
        activity.prefs().edit().putString("obd_transport", value.name()).apply();
        if (selected != null && selected.transport != value) {
            selected = null;
            activity.prefs().edit().remove("obd_adapter_address").remove("obd_adapter_name").apply();
        }
        showTab("Adapter");
    }

    private void renderDevices() {
        if (deviceRows == null)
            return;
        deviceRows.removeAllViews();
        int shown = 0;
        if (selected != null && selected.transport == transport
                && !devices.containsKey(deviceKey(selected))) {
            addDeviceChoice(selected, true);
            shown++;
        }
        for (ObdBluetooth.DeviceChoice device : devices.values()) {
            if (device.transport != transport)
                continue;
            addDeviceChoice(device, false);
            shown++;
        }
        if (shown == 0) {
            TextView empty = activity.text(
                    scanning ? "Scanning…" : "No " + transportLabel(transport) + " adapters listed", 15,
                    activity.colors.muted, 450);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(22), dp(12), dp(22));
            deviceRows.addView(empty);
        }
    }

    private void addDeviceChoice(ObdBluetooth.DeviceChoice device, boolean saved) {
        boolean chosen = selected != null && deviceKey(selected).equals(deviceKey(device));
        String detail = saved ? "Saved default · " + (isConnected() ? phaseTitle(phase()) : "Not scanned")
                              : device.address;
        Button choose =
                activity.button(deviceName(device) + (chosen ? "  ✓" : "") + "\n" + detail, false, () -> {
                    if (isConnected()) {
                        notice("Disconnect before selecting another adapter.");
                        return;
                    }
                    selectAdapter(device);
                });
        choose.setTextSize(14);
        choose.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        choose.setMinHeight(dp(80));
        choose.setContentDescription(deviceName(device) + ", " + transportLabel(device.transport)
                + (saved ? ", saved default" : "") + (chosen ? ", selected" : ", select adapter"));
        activity.styleButton(choose, activity.colors, false, false, chosen);
        addSpaced(deviceRows, choose, 6);
    }

    private void showConfiguration() {
        configuration = activity.new PanelDialog("Dashboard settings", 1000,
                "Choose four, six or nine cards, metric or imperial units, and a display style for every "
                        + "card. Tap a sensor name to change it. "
                        + "The refresh cycle is a requested interval; real update speed depends on the "
                        + "adapter and the number of supported sensors. "
                        + "Only sensors reported by the connected ECU can be chosen after discovery. While "
                        + "disconnected the registry is available for planning; "
                        + "support has not yet been verified. Unsupported saved cards remain visible so they "
                        + "can be replaced.");
        configurationBody();
        configuration.action("Done", true, () -> {
            configuration.dismiss();
            configuration = null;
        });
        showDialog(configuration);
    }

    private void configurationBody() {
        if (configuration == null)
            return;
        configuration.body.removeAllViews();
        LinearLayout choices = activity.row();
        Button four = activity.button("4 cards", false, () -> {
            count = 4;
            saveConfiguration();
            configurationBody();
        });
        Button six = activity.button("6 cards", false, () -> {
            count = 6;
            saveConfiguration();
            configurationBody();
        });
        Button nine = activity.button("9 cards", false, () -> {
            count = 9;
            saveConfiguration();
            configurationBody();
        });
        Button metric = activity.button("Metric", false, () -> {
            imperial = false;
            saveConfiguration();
            configurationBody();
        });
        Button us = activity.button("Imperial", false, () -> {
            imperial = true;
            saveConfiguration();
            configurationBody();
        });
        activity.styleButton(four, activity.colors, false, false, count == 4);
        activity.styleButton(six, activity.colors, false, false, count == 6);
        activity.styleButton(nine, activity.colors, false, false, count == 9);
        activity.styleButton(metric, activity.colors, false, false, !imperial);
        activity.styleButton(us, activity.colors, false, false, imperial);
        for (Button button : new Button[] {four, six, nine, metric, us}) fixedButton(choices, button, 1, 56);
        configuration.body.addView(choices, new LinearLayout.LayoutParams(-1, dp(56)));
        Button refresh = activity.button(
                "Refresh cycle · " + refreshLabel(refreshMillis) + "  ›", false, this::pickRefreshCycle);
        refresh.setTextSize(14);
        refresh.setMinHeight(dp(48));
        LinearLayout.LayoutParams refreshBounds = new LinearLayout.LayoutParams(-1, dp(48));
        refreshBounds.topMargin = dp(6);
        configuration.body.addView(refresh, refreshBounds);
        int columns = count == 4 ? 2 : 3;
        for (int first = 0; first < count; first += columns) {
            LinearLayout row = activity.row();
            row.setGravity(Gravity.TOP);
            for (int column = 0; column < columns && first + column < count; column++) {
                final int slot = first + column;
                LinearLayout cell = activity.column();
                ObdPid pid = ObdPid.byId(ids[slot]);
                Button sensor = activity.button(
                        (slot + 1) + " · " + pid.dashboardLabel(), false, () -> pickSensor(slot));
                sensor.setTextSize(14);
                sensor.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                sensor.setSingleLine(true);
                sensor.setEllipsize(TextUtils.TruncateAt.END);
                sensor.setContentDescription(
                        "Card " + (slot + 1) + " sensor · " + pid.label + ". Change sensor");
                cell.addView(sensor, new LinearLayout.LayoutParams(-1, dp(56)));
                Button style =
                        activity.button(styleLabel(styles[slot]) + "  ›", false, () -> pickStyle(slot));
                style.setMinHeight(dp(48));
                style.setContentDescription(
                        "Card " + (slot + 1) + " style · " + styleLabel(styles[slot]) + ". Change style");
                LinearLayout.LayoutParams styleBounds = new LinearLayout.LayoutParams(-1, dp(48));
                styleBounds.topMargin = dp(4);
                cell.addView(style, styleBounds);
                LinearLayout.LayoutParams cellBounds = new LinearLayout.LayoutParams(0, dp(108), 1);
                if (column > 0)
                    cellBounds.leftMargin = dp(6);
                row.addView(cell, cellBounds);
            }
            LinearLayout.LayoutParams rowBounds = new LinearLayout.LayoutParams(-1, dp(108));
            rowBounds.topMargin = dp(6);
            configuration.body.addView(row, rowBounds);
        }
    }

    private void pickRefreshCycle() {
        Screen.PanelDialog picker = activity.new PanelDialog("Refresh cycle", 700,
                "Choose the requested sensor refresh cycle. Fastest uses the adapter's safe polling "
                        + "schedule. "
                        + "Longer cycles reduce polling, but an adapter that responds slowly may take longer "
                        + "than the selected interval. "
                        + "The graph still groups actual samples into one-minute averages.");
        for (long cycle : REFRESH_CYCLES) {
            Button choice = activity.button(
                    refreshLabel(cycle) + (refreshMillis == cycle ? "  ✓" : ""), false, () -> {
                        refreshMillis = cycle;
                        engine.setRefreshCycleMillis(cycle);
                        saveConfiguration();
                        configurationBody();
                        picker.dismiss();
                    });
            activity.styleButton(choice, activity.colors, false, false, refreshMillis == cycle);
            addSpaced(picker.body, choice, 5);
        }
        picker.action("Back", false, picker::dismiss);
        showDialog(picker);
    }

    private static String refreshLabel(long cycle) {
        return cycle == 0 ? "Fastest" : cycle == 1000 ? "1 second" : cycle / 1000 + " seconds";
    }

    private void pickSensor(int slot) {
        Screen.PanelDialog picker = activity.new PanelDialog("Card " + (slot + 1) + " sensor", 800,
                "Choose a sensor category or an individual reading. Category cards open a graph with related "
                        + "readings. "
                        + "Missing measurements stay unavailable. "
                        + (verified ? "Other choices are readings reported by the connected ECU."
                                    : "Availability has not yet been checked on this vehicle."));
        for (ObdCategory category : engine.profile().categories) {
            List<ObdPid> members = categorySensors(category.id);
            boolean available = !verified;
            for (ObdPid pid : members)
                if (snapshot != null && snapshot.supported.contains(pid.id))
                    available = true;
            if (!available)
                continue;
            boolean selected = engine.profile().sensor(ids[slot]).category.equals(category.id);
            Button choice = activity.button(category.label + (selected ? "  ✓" : "") + "  ›", false, () -> {
                if (!engine.profile().sensor(ids[slot]).category.equals(category.id)) {
                    String primary = category.primaryId;
                    if (verified && !snapshot.supported.contains(primary))
                        for (ObdPid member : members)
                            if (snapshot.supported.contains(member.id)) {
                                primary = member.id;
                                break;
                            }
                    ids[slot] = primary;
                }
                saveConfiguration();
                configurationBody();
                picker.dismiss();
            });
            choice.setSelected(selected);
            activity.styleButton(choice, activity.colors, false, false, selected);
            addSpaced(picker.body, choice, 5);
        }
        List<ObdPid> choices = new ArrayList<>();
        for (ObdPid pid : engine.profile().sensors)
            if (pid.experimental)
                choices.add(pid);
        for (ObdPid pid : engine.profile().sensors)
            if (!pid.experimental)
                choices.add(pid);
        for (ObdPid pid : choices) {
            if (!pid.category.isEmpty())
                continue;
            if (verified && (snapshot == null || !snapshot.supported.contains(pid.id)))
                continue;
            Button choice = activity.button(pid.label + (pid.experimental ? " · " + pid.originLabel : "")
                            + (ids[slot].equals(pid.id) ? "  ✓" : ""),
                    false, () -> {
                        ids[slot] = pid.id;
                        saveConfiguration();
                        configurationBody();
                        picker.dismiss();
                    });
            activity.styleButton(choice, activity.colors, false, false, ids[slot].equals(pid.id));
            addSpaced(picker.body, choice, 5);
        }
        if (picker.body.getChildCount() == 0)
            picker.message("No supported sensors were reported.");
        picker.action("Back", false, picker::dismiss);
        showDialog(picker);
    }

    private void pickStyle(int slot) {
        Screen.PanelDialog picker = activity.new PanelDialog("Card " + (slot + 1) + " style", 700);
        for (String value : STYLES) {
            Button choice = activity.button(
                    styleLabel(value) + (styles[slot].equals(value) ? "  ✓" : ""), false, () -> {
                        styles[slot] = value;
                        saveConfiguration();
                        configurationBody();
                        picker.dismiss();
                    });
            activity.styleButton(choice, activity.colors, false, false, styles[slot].equals(value));
            addSpaced(picker.body, choice, 5);
        }
        picker.action("Back", false, picker::dismiss);
        showDialog(picker);
    }

    private String sensorTechnical(ObdPid pid) {
        String id = pid.id;
        ObdEngine.Reading reading = reading(id);
        String technical = pid.originLabel + " · Read " + pid.command() + ". "
                + "ECU source · "
                + (reading == null || clean(reading.source).isEmpty() ? "not yet reported"
                                                                      : clean(reading.source))
                + ". "
                + ("Values and history contain only actual samples from the current or last adapter session. "
                        + "Missing samples remain gaps. ");
        if (reading != null && !clean(reading.error).isEmpty())
            technical += "Latest query · " + clean(reading.error) + ". ";
        technical += pid.definitionNote() + " ";
        ObdSensorCheck checked = snapshot == null ? null : snapshot.sensorChecks.get(id);
        if (checked != null)
            technical += checked.label() + ". " + checked.detail + " "
                    + (checked.raw.isEmpty() ? "" : "Raw selected-ECU reply: " + checked.raw + ". ");
        if ("map".equals(id))
            technical += "Absolute manifold pressure; no boost estimate is applied. ";
        if ("voltage".equals(id))
            technical += "ECU-reported supply voltage; this is not an adapter-voltage reading. ";
        return technical;
    }

    void openHomeMetric(String id) {
        if (engine.profile().sensor(id) != null) sensorDetails(id, -1);
    }

    private void sensorDetails(String id, int slot) {
        if (sensorDialog != null)
            sensorDialog.dismiss();
        ObdPid pid = ObdPid.byId(id);
        if (!pid.category.isEmpty()) {
            categoryDetails(pid.category, id, slot);
            return;
        }
        ObdEngine.Reading reading = reading(id);
        String technical = sensorTechnical(pid);
        Screen.PanelDialog details = activity.new PanelDialog(pid.label, 960, technical);
        sensorDialog = details;
        detailId = id;
        LinearLayout valueRow = activity.row();
        valueRow.setPadding(dp(4), 0, dp(4), 0);
        detailValue = activity.text("—", 36, activity.colors.ink, 650);
        valueRow.addView(detailValue, new LinearLayout.LayoutParams(-2, -2));
        detailUnit = activity.text(pid.unit(imperial), 16, activity.colors.muted, 550);
        LinearLayout.LayoutParams unitBounds = new LinearLayout.LayoutParams(-2, -2);
        unitBounds.leftMargin = dp(12);
        valueRow.addView(detailUnit, unitBounds);
        details.body.addView(valueRow, new LinearLayout.LayoutParams(-1, dp(64)));
        detailQuality = line("", 14, activity.colors.muted, 500);
        detailQuality.setPadding(dp(4), dp(3), dp(4), dp(5));
        details.body.addView(detailQuality, new LinearLayout.LayoutParams(-1, dp(36)));
        if (pid.categorical()) {
            details.message("This is a category, so numeric one-minute averages would be misleading. See its "
                    + "current state and raw reply in the info panel.");
            if (slot >= 0)
                details.action("Change card", false, () -> {
                    details.dismiss();
                    showConfiguration();
                });
            details.action("Close", true, details::dismiss);
            showDialog(details);
            updateSensorDetails();
            return;
        }
        details.body.addView(activity.helpHeading("Last 10 minutes", 18,
                                     "Each point averages actual samples in a one-minute interval within the "
                                             + "last ten minutes. The window moves as new samples arrive. "
                                             + "Minutes without samples are gaps, not zero values. Only the "
                                             + "current or last adapter session is retained; "
                                             + "a new connection starts a new history. Lock-up averages show "
                                             + "the percentage of samples active; reported-gear averages "
                                             + "may be fractional."),
                new LinearLayout.LayoutParams(-1, dp(48)));
        detailHistory = new ObdHistoryView(activity);
        details.body.addView(detailHistory, new LinearLayout.LayoutParams(-1, dp(230)));
        TextView caption =
                activity.text("1-minute averages · Session history", 12, activity.colors.muted, 450);
        caption.setPadding(dp(4), dp(5), dp(4), 0);
        details.body.addView(caption, new LinearLayout.LayoutParams(-1, dp(28)));
        if (slot >= 0)
            details.action("Change card", false, () -> {
                details.dismiss();
                showConfiguration();
            });
        details.action("Close", true, details::dismiss);
        showDialog(details);
        updateSensorDetails();
    }

    private void categoryDetails(String categoryId, String initial, int slot) {
        ObdCategory category = engine.profile().category(categoryId);
        Screen.PanelDialog details = activity.new PanelDialog(category.label, 1120,
                category.help
                        + (" Select a related reading here to change the graph, then use Use on card to "
                                + "choose a different main stat. Browsing alone leaves the card unchanged. ")
                        + ("Unavailable measurements remain unknown. Custom definitions need comparison with "
                                + "a reference tool. The info icon beside each selected reading shows its "
                                + "source, "
                                + "definition and raw reply."));
        sensorDialog = details;
        LinearLayout split = activity.row();
        split.setGravity(Gravity.TOP);
        split.setBaselineAligned(false);
        detailDpfGraph = activity.column();
        split.addView(detailDpfGraph, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout related = activity.column();
        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(dp(340), -1);
        side.leftMargin = dp(16);
        split.addView(related, side);
        related.addView(activity.helpHeading("Related readings", 16,
                                "Select a reading to show its value and graph on the left. A related reading "
                                        + "changes only this graph until Use on card is selected."),
                new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout choices = activity.column();
        for (ObdPid pid : categorySensors(categoryId)) {
            LinearLayout choice = activity.column();
            choice.setPadding(dp(12), dp(10), dp(12), dp(10));
            TextView name = line(pid.label, 14, activity.colors.ink, 600);
            choice.addView(name);
            TextView value = line("—", 12, activity.colors.muted, 500);
            LinearLayout.LayoutParams valueBounds = new LinearLayout.LayoutParams(-1, -2);
            valueBounds.topMargin = dp(5);
            choice.addView(value, valueBounds);
            choice.setMinimumHeight(dp(76));
            choice.setClickable(true);
            choice.setFocusable(true);
            choice.setOnClickListener(v -> selectDpfDetail(pid.id));
            detailDpfChoices.put(pid.id, choice);
            detailDpfValues.put(pid.id, value);
            addSpaced(choices, choice, 6);
        }
        related.addView(scroll(choices), new LinearLayout.LayoutParams(-1, 0, 1));
        details.body.addView(split, new LinearLayout.LayoutParams(-1, dp(430)));
        details.action("Save report", false, this::saveLocalReport);
        detailCardSlot = slot;
        if (slot >= 0)
            detailUseCard = details.action("On card ✓", false, () -> {
                if (detailId != null) {
                    ids[slot] = detailId;
                    saveConfiguration();
                    updateSensorDetails();
                }
            });
        if (slot >= 0)
            details.action("Change card", false, () -> {
                details.dismiss();
                showConfiguration();
            });
        details.action("Close", true, details::dismiss);
        showDialog(details);
        selectDpfDetail(initial);
    }

    private void selectDpfDetail(String id) {
        if (detailDpfGraph == null)
            return;
        detailId = id;
        detailHistory = null;
        detailDpfGraph.removeAllViews();
        ObdPid pid = ObdPid.byId(id);
        detailDpfGraph.addView(activity.helpHeading(pid.label, 18, sensorTechnical(pid)),
                new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout value = activity.row();
        detailValue = activity.text("—", 32, activity.colors.ink, 650);
        value.addView(detailValue, new LinearLayout.LayoutParams(-2, -2));
        detailUnit = activity.text(pid.unit(imperial), 16, activity.colors.muted, 550);
        LinearLayout.LayoutParams unitBounds = new LinearLayout.LayoutParams(-2, -2);
        unitBounds.leftMargin = dp(12);
        value.addView(detailUnit, unitBounds);
        detailDpfGraph.addView(value, new LinearLayout.LayoutParams(-1, dp(60)));
        detailQuality = line("", 13, activity.colors.muted, 500);
        detailDpfGraph.addView(detailQuality, new LinearLayout.LayoutParams(-1, dp(30)));
        if (pid.categorical()) {
            TextView note = activity.text(
                    "This reading is a state. Numeric averages would not describe it accurately.", 15,
                    activity.colors.muted, 450);
            note.setPadding(0, dp(22), dp(12), 0);
            detailDpfGraph.addView(note);
        } else {
            detailHistory = new ObdHistoryView(activity);
            detailDpfGraph.addView(detailHistory, new LinearLayout.LayoutParams(-1, 0, 1));
            TextView caption = line("Last 10 minutes · 1-minute averages", 12, activity.colors.muted, 450);
            caption.setPadding(0, dp(8), 0, 0);
            detailDpfGraph.addView(caption, new LinearLayout.LayoutParams(-1, dp(32)));
        }
        for (Map.Entry<String, LinearLayout> entry : detailDpfChoices.entrySet()) {
            boolean selected = entry.getKey().equals(id);
            entry.getValue().setSelected(selected);
            android.graphics.drawable.GradientDrawable background =
                    activity.shape(selected ? activity.colors.selection : activity.colors.surface, 12);
            if (selected)
                background.setStroke(dp(2), activity.colors.accent);
            entry.getValue().setBackground(background);
        }
        updateSensorDetails();
    }

    private void updateSensorDetails() {
        if (sensorDialog == null || !sensorDialog.isShowing() || detailId == null)
            return;
        ObdPid pid = ObdPid.byId(detailId);
        if (pid == null)
            return;
        ObdEngine.Reading reading = reading(detailId);
        setText(detailValue, hasSample(reading) ? pid.valueText(reading.value, imperial) : "—");
        detailValue.setTextColor(fresh(reading) ? activity.colors.accent : activity.colors.ink);
        setText(detailUnit, pid.unit(imperial));
        setText(detailQuality, quality(detailId, reading));
        if (detailHistory != null)
            detailHistory.bind(pid, engine.minuteHistory(detailId, System.currentTimeMillis()), imperial);
        if (detailUseCard != null && detailCardSlot >= 0) {
            boolean chosen = ids[detailCardSlot].equals(detailId);
            setText(detailUseCard, chosen ? "On card ✓" : "Use on card");
            detailUseCard.setEnabled(
                    !chosen && (!verified || snapshot != null && snapshot.supported.contains(detailId)));
            detailUseCard.setSelected(chosen);
            activity.styleButton(detailUseCard, activity.colors, false, false, chosen);
        }
        for (Map.Entry<String, TextView> entry : detailDpfValues.entrySet()) {
            ObdPid related = ObdPid.byId(entry.getKey());
            ObdEngine.Reading sample = reading(related.id);
            String value = hasSample(sample)
                    ? related.valueText(sample.value, imperial) + " " + related.unit(imperial)
                    : "—";
            String status = hasSample(sample) ? related.experimental ? related.originLabel
                            : fresh(sample)                          ? "Live"
                                                                     : "Stale"
                                              : checkLabel(related.id);
            setText(entry.getValue(), value + " · " + status);
            entry.getValue().setTextColor(fresh(sample) ? activity.colors.accent : activity.colors.muted);
            LinearLayout choice = detailDpfChoices.get(related.id);
            choice.setContentDescription(related.label + ", " + value + ", " + status + ", "
                    + (related.id.equals(detailId) ? "selected" : "show graph"));
        }
    }

    private void clearSensorDetails() {
        sensorDialog = null;
        detailId = null;
        detailValue = null;
        detailUnit = null;
        detailQuality = null;
        detailHistory = null;
        detailDpfGraph = null;
        detailDpfValues.clear();
        detailDpfChoices.clear();
        detailUseCard = null;
        detailCardSlot = -1;
    }

    private void showDialog(Screen.PanelDialog dialog) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> {
            dialogs.remove(dialog);
            if (configuration == dialog)
                configuration = null;
            if (sensorDialog == dialog)
                clearSensorDetails();
        });
        dialog.show();
    }

    private void exportReport() {
        String csv = reportCsv();
        if (csv != null)
            activity.exportObdCsv(csv);
    }

    private void saveLocalReport() {
        final String csv = reportCsv();
        if (csv == null) {
            Toast.makeText(activity, "Connect and check sensors before saving a report.", Toast.LENGTH_LONG)
                    .show();
            return;
        }
        new Thread(() -> {
            String result;
            try {
                java.io.File folder = activity.getExternalFilesDir("vehicle-reports");
                if (folder == null)
                    throw new java.io.IOException("Local storage unavailable");
                if (!folder.isDirectory() && !folder.mkdirs())
                    throw new java.io.IOException("Cannot create report folder");
                java.io.File file =
                        new java.io.File(folder, "cc3-vehicle-" + System.currentTimeMillis() + ".csv");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                result = "Report saved · " + file.getName();
            } catch (java.io.IOException error) {
                result = "The report could not be saved.";
            }
            final String message = result;
            ui.post(() -> {
                if (!closed && active)
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            });
        }, "cc3-dpf-report").start();
    }

    private String reportCsv() {
        return VehicleReport.create(snapshot,engine.profile(),imperial,(pid,value)->quality(pid.id,value));
    }

    private ObdEngine.Reading reading(String id) {
        return snapshot == null ? null : snapshot.readings.get(id);
    }
    private ObdEngine.Phase phase() {
        return snapshot == null ? ObdEngine.Phase.DISCONNECTED : snapshot.phase;
    }
    private boolean isMonitoring() {
        return phase() == ObdEngine.Phase.MONITORING;
    }
    private boolean readingFaults() {
        return faultRequestPending || snapshot != null && clean(snapshot.status).startsWith("Reading fault");
    }
    private boolean isConnected() {
        return phase() != ObdEngine.Phase.DISCONNECTED && phase() != ObdEngine.Phase.ERROR;
    }

    private boolean fresh(ObdEngine.Reading reading) {
        return isMonitoring() && hasSample(reading) && clean(reading.error).isEmpty()
                && System.currentTimeMillis() - reading.timestampMillis >= 0
                && System.currentTimeMillis() - reading.timestampMillis <= staleWindow(reading);
    }

    private long staleWindow(ObdEngine.Reading reading) {
        ObdPid pid = reading == null ? null : ObdPid.byId(reading.id);
        long interval = Math.max(pid == null ? 0 : pid.intervalMillis, engine.refreshCycleMillis());
        return Math.max(STALE_MS, interval * 2 + 2500);
    }

    private String cardQuality(String id, ObdEngine.Reading reading) {
        if (phase() == ObdEngine.Phase.DISCONNECTED || phase() == ObdEngine.Phase.ERROR) {
            if (hasSample(reading)) return "Last reading · " + age(reading.timestampMillis);
            ObdSensorCheck check = snapshot == null ? null : snapshot.sensorChecks.get(id);
            if (check != null && !snapshot.supported.contains(id)) return check.label();
            if (reading != null && !clean(reading.error).isEmpty()) return "No response";
            return "";
        }
        return quality(id, reading);
    }

    private String quality(String id, ObdEngine.Reading reading) {
        if (phase() == ObdEngine.Phase.DISCONNECTED)
            return hasSample(reading) ? "Disconnected · last " + age(reading.timestampMillis)
                                      : "Not connected";
        if (phase() == ObdEngine.Phase.ERROR)
            return hasSample(reading) ? "Connection lost · last " + age(reading.timestampMillis)
                                      : "Connection error";
        if (verified && !snapshot.supported.contains(id))
            return checkLabel(id);
        if (phase() == ObdEngine.Phase.PAUSED)
            return hasSample(reading) ? "Paused · last " + age(reading.timestampMillis)
                                      : "Paused · no sample";
        if (!hasSample(reading))
            return reading != null && !clean(reading.error).isEmpty() ? "No response"
                    : verified                                        ? "Waiting for sample"
                                                                      : "Not yet verified";
        if (!isMonitoring())
            return phaseTitle(phase()) + " · last " + age(reading.timestampMillis);
        if (System.currentTimeMillis() < reading.timestampMillis)
            return "Sample time changed";
        if (!clean(reading.error).isEmpty())
            return "No response · last " + age(reading.timestampMillis);
        if (System.currentTimeMillis() - reading.timestampMillis > staleWindow(reading))
            return "Stale · " + age(reading.timestampMillis);
        ObdPid pid = ObdPid.byId(id);
        return (pid != null && pid.experimental ? pid.originLabel + " · " : "Live · ")
                + age(reading.timestampMillis);
    }

    private static String phaseTitle(ObdEngine.Phase phase) {
        switch (phase) {
            case CONNECTING:
                return "Connecting adapter";
            case ADAPTER_READY:
                return "Adapter connected";
            case FINDING_ECU:
                return "Finding vehicle ECU";
            case DISCOVERING:
                return "Discovering sensors";
            case MONITORING:
                return "Monitoring";
            case PAUSED:
                return "Readings paused";
            case ERROR:
                return "Connection error";
            default:
                return "Not connected";
        }
    }

    private static boolean hasSample(ObdEngine.Reading value) {
        return value != null && value.timestampMillis > 0 && Double.isFinite(value.value);
    }

    private static boolean sameFaults(List<ObdEngine.Fault> first, List<ObdEngine.Fault> second) {
        if (first.size() != second.size())
            return false;
        for (int i = 0; i < first.size(); i++) {
            ObdEngine.Fault a = first.get(i), b = second.get(i);
            if (!clean(a.code).equals(clean(b.code)) || !clean(a.category).equals(clean(b.category))
                    || !clean(a.source).equals(clean(b.source)))
                return false;
        }
        return true;
    }

    private static String age(long timestamp) {
        if (System.currentTimeMillis() < timestamp)
            return "time changed";
        long seconds = Math.max(0, (System.currentTimeMillis() - timestamp) / 1000);
        return seconds < 1       ? "just now"
                : seconds < 60   ? seconds + "s ago"
                : seconds < 3600 ? seconds / 60 + "m ago"
                                 : seconds / 3600 + "h ago";
    }
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
    private static String deviceName(ObdBluetooth.DeviceChoice device) {
        return clean(device.name).isEmpty() ? "Unnamed adapter" : clean(device.name);
    }
    private static String deviceKey(ObdBluetooth.DeviceChoice device) {
        return device.transport.name() + ":" + device.address;
    }
    private static String transportLabel(ObdBluetooth.Transport value) {
        return value == ObdBluetooth.Transport.BLE ? "Bluetooth LE" : "Classic";
    }
    private static String styleLabel(String value) {
        return "dial".equals(value) ? "Dial" : "trend".equals(value) ? "Trend" : "Number";
    }
    private void notice(String value) {
        Toast.makeText(activity, value, Toast.LENGTH_LONG).show();
    }
    private int dp(int value) {
        return activity.dp(value);
    }

    private TextView line(String value, int size, int color, int weight) {
        TextView view = activity.text(value, size, color, weight);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private ScrollView scroll(View contents) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.addView(contents, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private void addSpaced(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2);
        bounds.topMargin = dp(gap);
        parent.addView(view, bounds);
    }

    private void fixedButton(LinearLayout parent, Button button, float weight, int height) {
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(0, dp(height), weight);
        bounds.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(button, bounds);
    }
}
