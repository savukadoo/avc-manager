package local.cc3.manager;

import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TrafficPage {
    interface NetworkControl {
        void change(int modes);
    }

    private final MainActivity activity;
    private final TrafficLog log;
    private final Button power;
    private final Button monitor;
    private final TextView firewallStatus;
    private final TextView monitorStatus;
    private final TextView networkError;
    private final NetworkControl control;
    private TextView emptyConnections;
    private final Button connections;
    private final Button mapTab;
    private final LinearLayout body;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long version = -1;
    private List<TrafficLog.Row> rows = Collections.emptyList();
    private String view = "Connections";
    private TrafficAdapter adapter;
    private IpGeography geography;
    private boolean locating;
    private boolean lookupFailed;
    private IpGeography.MapView map;
    private TextView selection;
    private String selectedIp;
    private String selectedCountry;
    private LinearLayout countryButtons;
    private Button destinationIps;
    private final Map<String, Button> countryControls = new LinkedHashMap<>();
    private long requestTime;
    private int pendingModes = -1;
    private int styledModes = -1;
    private final Runnable pollModes = this::waitForModes;

    TrafficPage(MainActivity activity, NetworkControl control) {
        this.activity = activity;
        this.control = control;
        log = FirewallService.traffic(activity);
        activity.pageHeading("Firewall", "Rule enforcement blocks saved apps and IP addresses. Traffic capture records connection metadata for the list and map. You can run either one independently.");
        boolean splitModes = activity.paneWidth() >= 750;
        LinearLayout modes = splitModes ? activity.row() : activity.column();
        modes.setGravity(android.view.Gravity.TOP);
        firewallStatus = activity.managerText("", 16, activity.colors.ink, 600);
        power = activity.button("Start firewall", true, () -> toggle(FirewallService.BLOCKING));
        addMode(modes, "Rule enforcement", firewallStatus, power);
        monitorStatus = activity.managerText("", 16, activity.colors.ink, 600);
        monitor = activity.button("Start capture", false, () -> toggle(FirewallService.MONITORING));
        addMode(modes, "Traffic capture", monitorStatus, monitor);
        activity.addSection(modes);
        networkError = activity.managerText("", 14, activity.colors.muted, 450);
        activity.addSection(networkError);

        LinearLayout views = activity.row();
        connections = activity.button("Connections", false, () -> show("Connections"));
        mapTab = activity.button("Map", false, () -> show("Map"));
        activity.tile(views, connections);
        activity.tile(views, mapTab);
        View gap = new View(activity);
        views.addView(gap, new LinearLayout.LayoutParams(activity.dp(18), 1));
        Button rulesAction = activity.button("Block rules", false, this::rules);
        Button protectionAction = activity.button("Protection", false, this::protection);
        activity.styleButton(rulesAction, activity.colors, false, true, false);
        activity.styleButton(protectionAction, activity.colors, false, true, false);
        activity.tile(views, rulesAction);
        activity.tile(views, protectionAction);
        activity.addSection(views);

        body = activity.card();
        if (activity.usesFixedConnections()) {
            LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(-1, 0, 1);
            fill.topMargin = activity.dp(10);
            activity.content.addView(body, fill);
        } else {
            activity.addSection(body);
        }
        show("Connections");
    }

    private void show(String name) {
        if (name.equals(view) && adapter != null && !"Map".equals(name)) return;
        view = name;
        styleTab(connections, "Connections".equals(view));
        styleTab(mapTab, "Map".equals(view));
        if ("Map".equals(view)) showMap();
        else {
            activity.showWorkspace(null);
            body.removeAllViews();
            showConnections();
        }
        refresh();
    }

    boolean closeMap() {
        if (!"Map".equals(view)) return false;
        show("Connections");
        return true;
    }

    private void styleTab(Button tab, boolean active) {
        tab.setSelected(active);
        activity.styleButton(tab, activity.colors, false, false, active);
    }

    void refresh() {
        refreshPower();
        long currentVersion = log.version();
        if (version != currentVersion) {
            version = currentVersion;
            rows = log.snapshot();
        }
        if ("Map".equals(view)) updateMap();
        else if (adapter != null) adapter.update(rows);
    }

    private void addMode(LinearLayout group, String title, TextView label, Button button) {
        LinearLayout panel = activity.card();
        TextView heading = activity.managerText(title, 17, activity.colors.ink, 650);
        heading.setAccessibilityHeading(true);
        panel.addView(heading);
        label.setPadding(0, activity.dp(4), 0, activity.dp(10));
        label.setTextColor(activity.colors.muted);
        panel.addView(label);
        panel.addView(button);
        boolean split = group.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams bounds = split ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, -2);
        if (group.getChildCount() > 0) {
            if (split) bounds.leftMargin = activity.dp(10);
            else bounds.topMargin = activity.dp(8);
        }
        group.addView(panel, bounds);
    }

    private void toggle(int mode) {
        if (pendingModes >= 0 || FirewallService.stopping()) return;
        pendingModes = FirewallService.modes() ^ mode;
        requestTime = android.os.SystemClock.elapsedRealtime();
        refreshPower();
        control.change(pendingModes);
        ui.postDelayed(pollModes, 75);
    }

    void networkChangeCanceled() {
        pendingModes = -1;
        ui.removeCallbacks(pollModes);
        refreshPower();
    }

    private void refreshPower() {
        int modes = FirewallService.modes();
        boolean blocking = (modes & FirewallService.BLOCKING) != 0;
        boolean logging = (modes & FirewallService.MONITORING) != 0;
        boolean pending = pendingModes >= 0 || FirewallService.stopping();
        if (styledModes != modes) {
            styledModes = modes;
            if (adapter != null) adapter.notifyDataSetChanged();
            stylePower(power, blocking, true);
            stylePower(monitor, logging, false);
        }
        firewallStatus.setText(blocking ? "On · Enforcing rules" : "Off · Rules are inactive");
        power.setEnabled(!FirewallService.alwaysOn());
        monitorStatus.setText(logging ? "On · Logging connections" : "Off · Connections are not logged");
        boolean firewallChanging = pendingModes >= 0 && ((pendingModes ^ modes) & FirewallService.BLOCKING) != 0;
        boolean captureChanging = pendingModes >= 0 && ((pendingModes ^ modes) & FirewallService.MONITORING) != 0;
        power.setText(firewallChanging ? (blocking ? "Stopping firewall…" : "Starting firewall…")
                : blocking ? "Stop firewall" : "Start firewall");
        monitor.setText(captureChanging ? (logging ? "Stopping capture…" : "Starting capture…")
                : logging ? "Stop capture" : "Start capture");
        power.setEnabled(!pending && !FirewallService.alwaysOn());
        monitor.setEnabled(!pending);
        String status = FirewallService.stopping() ? "Stopping the local VPN…" : FirewallService.status();
        networkError.setText(status);
        networkError.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        if (emptyConnections != null) emptyConnections.setText(logging
                ? "Waiting for internet connections…" : "Start capture to see internet connections here.");
    }

    private void stylePower(Button button, boolean active, boolean firewall) {
        if (active && firewall) activity.styleButton(button, 0xff226543, 0xfff3fff6, 0xff6bd89b, 1, 0xfff3fff6);
        else activity.styleButton(button, activity.colors, firewall || active, !firewall && !active, false);
    }

    private void waitForModes() {
        if ((FirewallService.modes() == pendingModes && !FirewallService.stopping())
                || android.os.SystemClock.elapsedRealtime() - requestTime >= 5000) {
            networkChangeCanceled();
        } else {
            ui.postDelayed(pollModes, 75);
        }
    }

    private void showConnections() {
        map = null;
        body.addView(activity.helpHeading("Recent connections", 17,
                "Select a connection to inspect its destination or add a block rule."));
        ListView list = new ListView(activity);
        list.setNestedScrollingEnabled(true);
        list.setDividerHeight(activity.dp(1));
        list.setDivider(new android.graphics.drawable.ColorDrawable(activity.colors.background));
        adapter = new TrafficAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, item, index, id) -> connection(adapter.getItem(index)));
        TextView empty = activity.managerText("", 15, activity.colors.muted, 400);
        emptyConnections = empty;
        boolean fixed = activity.usesFixedConnections();
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12));
        android.widget.FrameLayout results = new android.widget.FrameLayout(activity);
        results.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
        results.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        body.addView(results, fixed ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(-1, activity.dp(245)));
        list.setEmptyView(empty);
        if (!fixed) list.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
            else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
    }

    private final class TrafficAdapter extends BaseAdapter {
        private List<TrafficLog.Row> items = Collections.emptyList();

        void update(List<TrafficLog.Row> value) {
            if (items != value) {
                items = value;
                notifyDataSetChanged();
            }
        }

        @Override public int getCount() { return items.size(); }
        @Override public TrafficLog.Row getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row = recycled == null ? null : (LinearLayout) recycled;
            if (row == null) {
                row = activity.column();
                row.setMinimumHeight(activity.dp(76));
                row.setPadding(activity.dp(6), activity.dp(10), activity.dp(6), activity.dp(10));
                row.addView(activity.managerText("", 16, activity.colors.ink, 650));
                row.addView(activity.managerText("", 13, activity.colors.muted, 400));
            }
            TrafficLog.Row entry = getItem(position);
            TrafficLog.Flow flow = entry.flow;
            boolean blocked = log.blocked(flow);
            boolean enforcing = (FirewallService.modes() & FirewallService.BLOCKING) != 0;
            ((TextView) row.getChildAt(0)).setText(flow.app.label
                    + (blocked ? (enforcing ? "  ·  Blocked" : "  ·  Block rule saved") : ""));
            ((TextView) row.getChildAt(1)).setText(flow.ip + "  ·  " + flow.protocolLabel()
                    + "  ·  " + bytes(entry.sent + entry.received)
                    + (entry.denied > 0 ? "  ·  " + entry.denied + " drops" : ""));
            return row;
        }
    }

    private void connection(TrafficLog.Row entry) {
        TrafficLog.Flow flow = entry.flow;
        boolean ipBlocked = log.ipBlocked(flow.ip);
        boolean appBlocked = log.appBlocked(flow.app.key);
        Screen.PanelDialog dialog = activity.new PanelDialog(flow.app.label, 650);
        dialog.message(flow.ip + "  ·  " + flow.protocolLabel() + "\n"
                + bytes(entry.sent) + " sent  ·  " + bytes(entry.received) + " received"
                + (flow.app.key == null ? "\nApp-level blocking needs a unique Android app identity." : ""));
        dialog.option(ipBlocked ? "Unblock this IP" : "Block this IP", () -> {
            dialog.dismiss();
            updateIp(flow.ip, !ipBlocked);
        });
        if (flow.app.key != null) {
            dialog.option(appBlocked ? "Unblock this app" : "Block this app", () -> {
                dialog.dismiss();
                log.setApp(flow.app.key, !appBlocked);
                refresh();
            });
        }
        dialog.option("Show IP on map", () -> {
            dialog.dismiss();
            selectedIp = flow.ip;
            selectedCountry = null;
            show("Map");
        });
        dialog.action("Close", false, dialog::dismiss);
        dialog.show();
    }

    private void protection(){
        Screen.PanelDialog dialog=activity.new PanelDialog("Firewall protection",900,
                "Keep protection running restarts rule enforcement after an Android service restart, reboot or app update. Capture stays manually controlled. Android's Always-on and Block connections without VPN settings provide stronger protection during process crashes; lockdown can stop local uploads and remote access. Local/private routes intentionally bypass the ordinary firewall. App identity is unavailable for some shared, system and unknown traffic.");
        boolean persistent=FirewallService.persistent(activity);
        Button keep=activity.button(persistent?"✓  Keep protection running":"Keep protection running",false,()->{
            boolean next=!FirewallService.persistent(activity);
            activity.prefs().edit().putBoolean("firewall_persistent",next).apply();
            dialog.dismiss();
            if(next)control.change(FirewallService.modes()|FirewallService.BLOCKING);
            protection();
        });
        activity.styleButton(keep,activity.colors,false,false,persistent);
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,-2);gap.topMargin=activity.dp(8);dialog.body.addView(keep,gap);
        Button unknown=activity.button(log.unknownBlocked()?"✓  Unidentified traffic: Blocked":"Unidentified traffic: Allowed",false,()->{
            Screen.PanelDialog confirm=activity.new PanelDialog("Unidentified traffic policy",800);
            confirm.message("Blocking this group also covers shared Android/TEYES services and may interrupt navigation, DNS or other features. Destination rules still apply when it is allowed. It does not include local routes outside the VPN.");
            confirm.action("Cancel",false,confirm::dismiss);
            confirm.action(log.unknownBlocked()?"Allow group":"Block group",true,()->{log.setUnknownBlocked(!log.unknownBlocked());confirm.dismiss();dialog.dismiss();protection();});confirm.show();
        });
        activity.styleButton(unknown,activity.colors,false,false,log.unknownBlocked());
        LinearLayout.LayoutParams nextGap=new LinearLayout.LayoutParams(-1,-2);nextGap.topMargin=activity.dp(8);dialog.body.addView(unknown,nextGap);
        dialog.option("Android always-on / lockdown",()->activity.open(new android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)));
        dialog.action("Close",true,dialog::dismiss);dialog.show();
    }

    private void rules() {
        List<String> ips = log.ipRules();
        List<String> apps = log.appRules();
        Screen.PanelDialog dialog = activity.new PanelDialog("Block rules", 640);
        if (ips.isEmpty() && apps.isEmpty()) {
            dialog.message("No block rules yet. Select a recorded connection or enter an IP address.");
            dialog.option("View connections", () -> { dialog.dismiss(); show("Connections"); });
        }
        for (String ip : ips) {
            dialog.option(ip, () -> {
                dialog.dismiss();
                confirmRule(ip, false, this::rules);
            });
        }
        for (String app : apps) {
            String label = log.appLabel(app) + "  ·  App";
            dialog.option(label, () -> {
                dialog.dismiss();
                Screen.PanelDialog remove = activity.new PanelDialog("Remove block rule?", 560);
                remove.message(label);
                remove.action("Cancel", false, remove::dismiss);
                remove.action("Unblock", true, () -> {
                    remove.dismiss();
                    log.setApp(app, false);
                    refresh();
                    rules();
                });
                remove.show();
            });
        }
        dialog.action("Close", false, dialog::dismiss);
        dialog.action("Add IP", true, () -> {
            dialog.dismiss();
            addIp(false);
        });
        dialog.show();
    }

    private void addIp(boolean locate) {
        Screen.PanelDialog dialog = activity.new PanelDialog(locate ? "Locate an IP" : "Block an IP", 610);
        EditText field = new EditText(activity);
        field.setSingleLine();
        field.setTextSize(16);
        field.setTextColor(activity.colors.ink);
        field.setHintTextColor(activity.colors.muted);
        field.setPadding(activity.dp(14), 0, activity.dp(14), 0);
        field.setMinHeight(activity.dp(58));
        android.graphics.drawable.GradientDrawable fieldFrame = activity.shape(activity.colors.surface, 12);
        fieldFrame.setStroke(activity.dp(1), activity.colors.outline);
        field.setBackground(fieldFrame);
        field.setHint("IPv4 or IPv6 address");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        dialog.body.setFocusableInTouchMode(true);
        dialog.body.requestFocus();
        dialog.body.addView(field);
        dialog.action("Cancel", false, dialog::dismiss);
        dialog.action(locate ? "Locate" : "Block", true, () -> {
            try {
                String ip = TrafficLog.publicIp(field.getText().toString().trim());
                if (locate) {
                    selectedIp = ip;
                    selectedCountry = geography == null ? null : geography.countryCode(ip);
                    updateMap();
                    if (selectedCountry != null) map.focus(selectedCountry);
                } else {
                    log.setIp(ip, true);
                    refresh();
                }
                dialog.dismiss();
            } catch (IllegalArgumentException | IllegalStateException error) {
                field.setError(error.getMessage());
            }
        });
        dialog.show();
    }

    private void confirmRule(String ip, boolean block, Runnable after) {
        Screen.PanelDialog dialog = activity.new PanelDialog(block ? "Block this IP?" : "Remove block rule?", 560);
        dialog.message(ip);
        dialog.action("Cancel", false, dialog::dismiss);
        dialog.action(block ? "Block IP" : "Unblock", true, () -> {
            dialog.dismiss();
            updateIp(ip, block);
            after.run();
        });
        dialog.show();
    }

    private void updateIp(String ip, boolean block) {
        try {
            log.setIp(ip, block);
            refresh();
        } catch (IllegalStateException error) {
            android.widget.Toast.makeText(activity, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void showMap() {
        adapter = null;
        LinearLayout page = activity.column();
        page.setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12));
        page.setBackgroundColor(activity.colors.background);
        LinearLayout heading = activity.row();
        Button back = activity.button("Back to Firewall", false, this::closeMap);
        activity.styleButton(back, activity.colors, false, true, false);
        heading.addView(back);
        View title = activity.helpHeading("Destination map", 24,
                "Select a country or tap a dot to see destination IPs. IP geolocation shows an approximate country. Data by DB-IP and Natural Earth.");
        title.setPadding(activity.dp(16), 0, activity.dp(10), 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button reset = activity.button("Reset zoom", false, () -> map.reset());
        LinearLayout.LayoutParams resetLayout = new LinearLayout.LayoutParams(-2, -2);
        resetLayout.rightMargin = activity.dp(8);
        activity.styleButton(reset, activity.colors, false, true, false);
        heading.addView(reset, resetLayout);
        Button locate = activity.button("Locate an IP", false, () -> addIp(true));
        activity.styleButton(locate, activity.colors, false, true, false);
        heading.addView(locate);
        if (activity.paneWidth() >= 815) page.addView(heading);
        else {
            heading.removeView(title);
            heading.removeAllViews();
            activity.tile(heading, back);
            activity.tile(heading, reset);
            activity.tile(heading, locate);
            title.setPadding(0, 0, 0, activity.dp(8));
            page.addView(title);
            page.addView(heading);
        }
        HorizontalScrollView countries = new HorizontalScrollView(activity);
        countries.setHorizontalScrollBarEnabled(false);
        countryButtons = activity.row();
        countryControls.clear();
        countries.addView(countryButtons);
        page.addView(countries);
        map = new IpGeography.MapView(activity, activity.colors, this::showDestinations);
        map.setBackground(activity.shape(activity.colors.surface, 16));
        map.setClipToOutline(true);
        LinearLayout.LayoutParams canvas = new LinearLayout.LayoutParams(-1, 0, 1);
        canvas.topMargin = activity.dp(6);
        canvas.bottomMargin = activity.dp(8);
        page.addView(map, canvas);
        LinearLayout mapFooter = activity.row();
        selection = activity.managerText("", 15, activity.colors.ink, 500);
        mapFooter.addView(selection, new LinearLayout.LayoutParams(0, -2, 1));
        destinationIps = activity.button("Destination IPs", false, () -> showDestinations(selectedCountry));
        activity.styleButton(destinationIps, activity.colors, false, true, false);
        mapFooter.addView(destinationIps);
        page.addView(mapFooter);
        activity.showWorkspace(page);
        if (selectedCountry != null) map.post(() -> {
            if (map != null && selectedCountry != null) map.focus(selectedCountry);
        });
        if (geography == null && !locating && !lookupFailed) {
            locating = true;
            new Thread(() -> {
                IpGeography data = null;
                try {
                    data = IpGeography.load(activity.getApplicationContext());
                } catch (java.io.IOException | org.json.JSONException failure) {
                    android.util.Log.w("CC3Firewall", "Could not load offline map", failure);
                }
                IpGeography result = data;
                ui.post(() -> {
                    locating = false;
                    lookupFailed = result == null;
                    geography = result;
                    updateMap();
                    if (map != null && selectedCountry != null) map.focus(selectedCountry);
                });
            }, "cc3-offline-map").start();
        }
    }

    private void updateMap() {
        if (map == null) return;
        if (geography == null) {
            destinationIps.setVisibility(View.GONE);
            selection.setText(lookupFailed ? "The offline map is unavailable." : "Loading offline map…");
            return;
        }
        Map<String, Integer> countries = new LinkedHashMap<>();
        for (TrafficLog.Row row : rows) {
            String country = geography.countryCode(row.flow.ip);
            if (country != null) countries.merge(country, 1, Integer::sum);
        }
        if (selectedIp != null && selectedCountry == null) selectedCountry = geography.countryCode(selectedIp);
        if (selectedCountry != null) countries.putIfAbsent(selectedCountry, 0);
        syncCountryButtons(countries);
        if (selectedIp != null) {
            selection.setText(selectedIp + "  ·  " + IpGeography.countryName(selectedCountry));
        } else if (selectedCountry != null) {
            selection.setText(IpGeography.countryName(selectedCountry));
        } else if (countries.isEmpty()) {
            selection.setText("Start capture or look up an IP address.");
        } else {
            selection.setText("Select a country or dot");
        }
        destinationIps.setVisibility(selectedCountry == null ? View.GONE : View.VISIBLE);
        map.show(geography, countries, selectedCountry);
    }

    private void syncCountryButtons(Map<String, Integer> countries) {
        if (!countryControls.keySet().equals(countries.keySet())) {
            countryButtons.removeAllViews();
            countryControls.clear();
            List<String> codes = new ArrayList<>(countries.keySet());
            codes.sort(java.util.Comparator.comparing(IpGeography::countryName));
            for (String code : codes) {
                Button button = activity.button(IpGeography.countryName(code), false, () -> {
                    if (!code.equals(selectedCountry)) selectedIp = null;
                    selectedCountry = code;
                    updateMap();
                    map.focus(code);
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.setMargins(0, activity.dp(4), activity.dp(8), activity.dp(4));
                countryButtons.addView(button, params);
                countryControls.put(code, button);
            }
        }
        for (Map.Entry<String, Button> entry : countryControls.entrySet()) {
            boolean chosen = entry.getKey().equals(selectedCountry);
            Button button = entry.getValue();
            if (button.isSelected() == chosen && button.getTag() != null) continue;
            button.setTag(Boolean.TRUE);
            button.setSelected(chosen);
            activity.styleButton(button, activity.colors, false, true, chosen);
        }
    }

    private void showDestinations(String country) {
        if (country == null || geography == null) return;
        if (!country.equals(selectedCountry)) selectedIp = null;
        selectedCountry = country;
        updateMap();
        java.util.LinkedHashSet<String> addresses = new java.util.LinkedHashSet<>();
        if (selectedIp != null) addresses.add(selectedIp);
        for (TrafficLog.Row row : rows) {
            if (country.equals(geography.countryCode(row.flow.ip))) addresses.add(row.flow.ip);
        }
        Screen.PanelDialog dialog = activity.new PanelDialog(IpGeography.countryName(country) + " destinations", 670);
        if (addresses.isEmpty()) dialog.message("No observed IPs for this country.");
        for (String ip : addresses) {
            boolean blocked = log.ipBlocked(ip);
            LinearLayout entry = activity.row();
            entry.setBackground(activity.shape(activity.colors.surface, 12));
            entry.setPadding(activity.dp(14), activity.dp(6), activity.dp(8), activity.dp(6));
            LinearLayout info = activity.column();
            info.addView(activity.managerText(ip, 16, activity.colors.ink, 600));
            if (blocked) info.addView(activity.managerText("Blocked", 13, activity.colors.muted, 450));
            entry.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            Button details = activity.button("IP details", false, () -> {
                dialog.dismiss();
                lookupDetails(ip, () -> showDestinations(country));
            });
            details.setContentDescription("IP details for " + ip);
            activity.styleButton(details, activity.colors, false, true, false);
            LinearLayout.LayoutParams lookup = new LinearLayout.LayoutParams(-2, -2);
            lookup.rightMargin = activity.dp(8);
            entry.addView(details, lookup);
            Button action = activity.button(blocked ? "Unblock IP" : "Block IP", false, () -> {
                dialog.dismiss();
                confirmRule(ip, !blocked, () -> showDestinations(country));
            });
            action.setContentDescription((blocked ? "Unblock " : "Block ") + ip);
            activity.styleButton(action, activity.colors, false, true, false);
            entry.addView(action);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = activity.dp(8);
            dialog.body.addView(entry, params);
        }
        dialog.action("Close", false, dialog::dismiss);
        dialog.show();
    }

    private void lookupDetails(String ip, Runnable back) {
        Screen.PanelDialog dialog = activity.new PanelDialog("IP details", 690);
        dialog.message(ip);
        TextView dns = activity.managerText("Reverse DNS", 15, activity.colors.ink, 500);
        dns.setPadding(activity.dp(4), 0, activity.dp(4), activity.dp(12));
        dialog.body.addView(dns);
        TextView registration = activity.managerText("WHOIS / RDAP", 15, activity.colors.ink, 500);
        registration.setPadding(activity.dp(4), 0, activity.dp(4), activity.dp(8));
        dialog.body.addView(registration);
        dialog.message("An online lookup sends this IP to your DNS resolver and its network registry.");
        dialog.action("Back", false, () -> {
            dialog.dismiss();
            back.run();
        });
        dialog.action("Look up", true, () -> {
            Button control = (Button) dialog.findViewById(android.R.id.button1);
            if (control != null) control.setEnabled(false);
            dns.setText("Looking up reverse DNS…");
            registration.setText("Looking up network registration…");
            new Thread(() -> {
                String result;
                try {
                    result = "Reverse DNS  ·  " + IpDetails.reverseDns(ip);
                } catch (RuntimeException unavailable) {
                    result = "Reverse DNS lookup unavailable";
                }
                String text = result;
                ui.post(() -> { if (dialog.isShowing()) dns.setText(text); });
            }, "cc3-reverse-dns").start();
            new Thread(() -> {
                String result;
                try {
                    result = IpDetails.registration(activity.getApplicationContext(), ip);
                } catch (java.io.IOException | org.json.JSONException | IllegalArgumentException error) {
                    result = "Network registration unavailable";
                }
                String text = result;
                ui.post(() -> {
                    if (dialog.isShowing()) {
                        registration.setText("WHOIS / RDAP\n" + text);
                        if (control != null) control.setEnabled(true);
                    }
                });
            }, "cc3-network-registration").start();
        }).setId(android.R.id.button1);
        dialog.show();
    }

    private static String bytes(long amount) {
        if (amount < 1024) return amount + " B";
        if (amount < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", amount / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", amount / (1024.0 * 1024));
    }
}
