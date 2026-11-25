package local.cc3.manager;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.TextUtils;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import local.cc3.manager.DashboardApps.App;
import local.cc3.obd.ObdEngine;
import local.cc3.obd.ObdPid;
import local.cc3.obd.ObdSensorCheck;
import local.cc3.obd.android.ObdBluetooth;

public class DashboardActivity extends Screen {
    private static final String STOCK_HOME = "com.android.launcher4";
    private static final int TARGET_SIZE = 76;

    private List<DashboardApps.Choice> pinned;
    private final Map<App, Intent> installedApps = new EnumMap<>(App.class);
    private final List<DashboardApps.Choice> additionalApps = new ArrayList<>();
    private final Handler homeHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshDashboard = this::renderDashboard;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged = (prefs, key) -> {
        if (DashboardLayout.isLayoutPreference(key) || APPEARANCE.equals(key) || HomeBackground.KEY.equals(key)
                || DashboardStyle.preference(key) || WeatherRepository.preference(key)) {
            homeHandler.removeCallbacks(refreshDashboard);
            homeHandler.post(refreshDashboard);
        }
    };
    private LinearLayout workspace;
    private LinearLayout launcherBody;
    private LinearLayout bottomDock;
    private boolean preview;
    private VehicleSession vehicle;
    private ObdBluetooth.DeviceChoice adapter;
    private final Map<Button, ObdPid> metricTiles = new java.util.LinkedHashMap<>();
    private WeatherWidget weatherWidget;
    private HomeNowPlayingView nowPlaying;
    private LinearLayout homeHeader;
    private final Runnable playbackTick = new Runnable() {
        @Override public void run() {
            if (nowPlaying != null) { nowPlaying.refresh(); updateHeaderVisibility(); }
            if (vehicle != null) {
                vehicle.connectDefault(DashboardActivity.this, prefs(), adapter, false);
                updateMetrics();
            }
            homeHandler.postDelayed(this, 500);
        }
    };

    DashboardLayout previewLayout() {
        return null;
    }

    @Override protected void onResume() {
        super.onResume();
        if (previewLayout() == null && dashboardIsHome()) {
            DashboardLayout.finishHomeSelection(prefs(), true);
        }
        prefs().registerOnSharedPreferenceChangeListener(preferencesChanged);
        renderDashboard();
        homeHandler.removeCallbacks(playbackTick);
        homeHandler.post(playbackTick);
    }

    @Override protected void onPause() {
        prefs().unregisterOnSharedPreferenceChangeListener(preferencesChanged);
        homeHandler.removeCallbacks(refreshDashboard);
        homeHandler.removeCallbacks(playbackTick);
        if (vehicle != null) { vehicle.leave(this); vehicle = null; }
        if (weatherWidget != null) weatherWidget.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        homeHandler.removeCallbacksAndMessages(null);
        if (weatherWidget != null) weatherWidget.close();
        super.onDestroy();
    }

    private void renderDashboard() {
        if (weatherWidget != null) { weatherWidget.close(); weatherWidget = null; }
        metricTiles.clear();
        installedApps.clear();
        additionalApps.clear();
        pinned = DashboardApps.pinned(this);
        for (DashboardApps.Choice choice : pinned) {
            App installed = null;
            for (App app : App.values()) if (app.packageName.equals(choice.id)) installed = app;
            if (installed == null) additionalApps.add(choice);
            else {
                Intent launch = choice.intent(this);
                if (launch != null) installedApps.put(installed, launch);
            }
        }
        DashboardLayout sample = previewLayout();
        preview = sample != null;
        DashboardLayout layout = preview ? sample : DashboardLayout.selected(prefs());
        prepareWindow();
        workspace = column();
        workspace.setBackground(workspaceBackground());
        addHeader();
        content = column();
        content.setPadding(dp(16), 0, dp(16), dp(12));
        launcherBody = column();
        launcherBody.setGravity(Gravity.TOP);
        content.addView(launcherBody, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomDock = null;
        workspace.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (layout) {
            case CARPLAY: navigationSideDock(); break;
            case ANDROID_AUTO: navigationBottomDock(); break;
            case CC4_CARDS: fourPanelGrid(); break;
            case CC3_CLASSIC: radioAndMedia(); break;
            case BIG_SHORTCUTS: largeShortcuts(); break;
            case CLOCK_FAVORITES: clockAndFavourites(); break;
        }
        if (bottomDock != null) {
            LinearLayout.LayoutParams dockBounds = new LinearLayout.LayoutParams(-1, -2);
            dockBounds.topMargin = dp(10);
            content.addView(bottomDock, dockBounds);
        }
        setContentView(workspace);
        if (!preview && !metricTiles.isEmpty()) {
            vehicle = VehicleSession.get(this);
            adapter = VehicleSession.savedAdapter(prefs());
            vehicle.enter(this, null);
            updateMetrics();
        } else if (vehicle != null) {
            vehicle.leave(this);
            vehicle = null;
        }
        if (weatherWidget != null) weatherWidget.start();
    }

    private void addHeader() {
        LinearLayout header = row();
        homeHeader = header;
        header.setPadding(dp(20), dp(4), dp(20), dp(4));
        nowPlaying = new HomeNowPlayingView(this);
        if (preview) {
            header.addView(text("Home preview", 13, colors.muted, 500), new LinearLayout.LayoutParams(0, -2, 1));
            int width = getResources().getConfiguration().screenWidthDp;
            LinearLayout.LayoutParams playingBounds = new LinearLayout.LayoutParams(dp(Math.min(420, width / 3)), -2);
            playingBounds.setMarginEnd(dp(12)); header.addView(nowPlaying, playingBounds);
            Button back = target("‹  Back to Appearance", false, this::finish);
            back.setMinHeight(dp(56)); header.addView(back);
        } else header.addView(nowPlaying, new LinearLayout.LayoutParams(-1, -2));
        workspace.addView(header);
        updateHeaderVisibility();
    }

    private void updateHeaderVisibility() {
        if (homeHeader != null) homeHeader.setVisibility(preview || nowPlaying.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    private void radioAndMedia() {
        addSection(mediaPanel("Radio & media", 4));
        addSection(appPanel("Navigation & phone", 3, App.CARLINK, App.MAPS, App.WAZE));
        bottomDock = dock(false);
    }

    private void fourPanelGrid() {
        addSection(split(appPanel("Navigation", 2, App.MAPS, App.WAZE), 1,
                appPanel("Phone connection", 2, App.CARLINK, App.BLUETOOTH), 1, 680));
        LinearLayout system = panel("Favourites");
        List<Button> extras = new ArrayList<>();
        boolean arranged = DashboardApps.customOrder(prefs());
        if (!arranged) extras.addAll(metricButtons());
        for (DashboardApps.Choice choice : additionalApps) {
            if (arranged && extras.size() < 3 && !choice.id.equals(DashboardApps.Function.CONTROLS.id)) {
                extras.add(choiceButton(choice));
            } else if (!arranged && extras.size() < 2 && choice.metric == null && !choice.id.equals(DashboardApps.Function.CONTROLS.id)) {
                extras.add(choiceButton(choice));
                break;
            }
        }
        if (extras.isEmpty()) extras.add(target("Choose apps", false, this::showAppPicker));
        Button controls = target("Controls", false, () -> open(managerIntent(this, "Controls")));
        useShortcutIcon(controls, consoleIcon(NavigationStyle.icon("Controls")));
        if (extras.size() < 4) extras.add(controls);
        addShortcuts(system, 2, extras);
        addSection(split(appPanel("Audio", 3, App.FM, App.USB, App.SPOTIFY), 1, system, 1, 680));
        bottomDock = dock(false);
    }

    private void navigationSideDock() {
        LinearLayout navigation = panel("Navigation");
        boolean wide = getResources().getConfiguration().screenWidthDp >= 960;
        App destination = installedApps.containsKey(App.MAPS) ? App.MAPS : App.WAZE;
        Intent launch = installedApps.get(destination);
        if (launch != null) {
            Button primary = target(destination.label, false, () -> open(launch));
            Drawable arrow = getDrawable(R.drawable.ic_navigation_hero).mutate();
            arrow.setTint(colors.accent);
            InsetDrawable spaced = new InsetDrawable(arrow, 0, 0, 0, dp(10));
            spaced.setBounds(0, 0, dp(132), dp(142));
            SpannableString label = new SpannableString("\ufffc\nOpen " + destination.label);
            label.setSpan(new ImageSpan(spaced), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            primary.setText(label);
            primary.setContentDescription("Open " + destination.label);
            primary.setGravity(Gravity.CENTER);
            navigation.addView(primary, new LinearLayout.LayoutParams(-1, wide ? 0 : dp(200), wide ? 1 : 0));
            if (destination == App.MAPS && installedApps.containsKey(App.WAZE))
                addFullWidth(navigation, target("Waze", false, () -> open(installedApps.get(App.WAZE))));
        } else {
            addFullWidth(navigation, target("Choose navigation apps", false, this::showAppPicker));
        }
        DashboardApps.Choice favourite = wide ? driverFavourite() : null;
        LinearLayout stack = stack(mediaPanel("Media", 2, favourite),
                appPanel("Phone projection", 1, App.CARLINK));
        View panes = split(navigation, wide ? 6 : 7, stack, wide ? 5 : 3, 720);
        // The Australian Fortuner has its driver on the right.
        if (wide) {
            LinearLayout.LayoutParams mediaBounds = new LinearLayout.LayoutParams(-1, 0, 1.55f);
            stack.getChildAt(0).setLayoutParams(mediaBounds);
            LinearLayout.LayoutParams phoneBounds = new LinearLayout.LayoutParams(-1, 0, 1);
            phoneBounds.topMargin = dp(16);
            stack.getChildAt(1).setLayoutParams(phoneBounds);
            LinearLayout layout = row();
            layout.setGravity(Gravity.TOP);
            layout.addView(panes, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams rail = new LinearLayout.LayoutParams(driverRailWidth(), -1);
            rail.leftMargin = dp(16);
            layout.addView(driverRail(favourite), rail);
            addSection(layout, true);
        } else {
            addSection(panes);
            bottomDock = dock(false);
        }
    }

    private void navigationBottomDock() {
        LinearLayout navigation = appPanel("Navigation", 2, App.MAPS, App.WAZE);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout media = mediaPanel("Media", 2);
        addSection(split(navigation, 3, media, 2, 680));
        bottomDock = dock(false, App.CARLINK);
    }

    private LinearLayout panel(String title) {
        LinearLayout panel = column();
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(shape((colors.surface & 0x00ffffff) | 0xeb000000, 20));
        if (currentPanelHeadings()) {
            TextView heading = text(title, 17, colors.ink, 650);
            heading.setAccessibilityHeading(true);
            heading.setPadding(dp(5), 0, dp(5), dp(7));
            panel.addView(heading);
        }
        return panel;
    }

    private LinearLayout appPanel(String title, int columns, App... apps) {
        LinearLayout panel = panel(title);
        addAppButtons(panel, getResources().getConfiguration().screenWidthDp < 650
                ? Math.min(columns, 2) : columns, apps);
        return panel;
    }

    private LinearLayout mediaPanel(String title, int columns) {
        return mediaPanel(title, columns, null);
    }

    private LinearLayout mediaPanel(String title, int columns, DashboardApps.Choice railFavourite) {
        if (DashboardApps.customOrder(prefs())) {
            List<Button> ordered = new ArrayList<>();
            boolean allMedia = true;
            for (DashboardApps.Choice choice : pinned) {
                if (railFavourite != null && railFavourite.id.equals(choice.id)) continue;
                if (choice.id.equals(App.CARLINK.packageName) || choice.id.equals(App.MAPS.packageName)
                        || choice.id.equals(App.WAZE.packageName)) continue;
                ordered.add(choiceButton(choice));
                allMedia &= choice.isMedia(this) || isFactoryMedia(choice);
                if (ordered.size() == 4) break;
            }
            LinearLayout panel = panel(allMedia ? title : "Favourites");
            addShortcuts(panel, columns, ordered);
            return panel;
        }
        List<Button> shortcuts = metricButtons();
        boolean mediaOnly = shortcuts.isEmpty();
        for (App app : new App[] {App.FM, App.BLUETOOTH, App.USB, App.SPOTIFY}) {
            if (shortcuts.size() >= 4) break;
            if (railFavourite != null && railFavourite.id.equals(app.packageName)) continue;
            Intent intent = installedApps.get(app);
            if (intent != null) shortcuts.add(shortcut(app, intent));
        }
        for (DashboardApps.Choice choice : additionalApps) {
            if (shortcuts.size() >= 4) break;
            if (choice == railFavourite || choice.metric != null) continue;
            shortcuts.add(choiceButton(choice));
            mediaOnly &= choice.isMedia(this);
        }
        LinearLayout panel = panel(mediaOnly ? title : "Favourites");
        addShortcuts(panel, columns, shortcuts);
        return panel;
    }

    private static boolean isFactoryMedia(DashboardApps.Choice choice) {
        return choice.id.equals(App.FM.packageName) || choice.id.equals(App.USB.packageName)
                || choice.id.equals(App.SPOTIFY.packageName) || choice.id.equals(App.BLUETOOTH.packageName);
    }

    private void empty(LinearLayout panel, String message) {
        TextView label = text(message, 16, colors.muted, false);
        label.setPadding(dp(5), dp(10), dp(5), dp(10));
        panel.addView(label);
    }

    private LinearLayout stack(View first, View second) {
        LinearLayout stacked = column();
        stacked.addView(first, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lower = new LinearLayout.LayoutParams(-1, -2);
        lower.topMargin = dp(16);
        stacked.addView(second, lower);
        return stacked;
    }

    private LinearLayout split(View left, float leftWeight, View right, float rightWeight, int threshold) {
        if (getResources().getConfiguration().screenWidthDp < threshold) return stack(left, right);
        LinearLayout split = row();
        split.setGravity(Gravity.TOP);
        split.addView(left, new LinearLayout.LayoutParams(0, -1, leftWeight));
        LinearLayout.LayoutParams trailing = new LinearLayout.LayoutParams(0, -1, rightWeight);
        trailing.leftMargin = dp(16);
        split.addView(right, trailing);
        return split;
    }

    private void addSection(View section) {
        addSection(section, true);
    }

    private void addSection(View section, boolean stretch) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, stretch ? 0 : -2);
        params.topMargin = dp(10);
        if (stretch) params.weight = 1;
        launcherBody.addView(section, params);
    }

    private LinearLayout dock(boolean vertical, App... apps) {
        LinearLayout dock = vertical ? column() : row();
        dock.setBaselineAligned(false);
        dock.setPadding(dp(4), dp(4), dp(4), dp(4));
        dock.setBackground(shape(colors.surface, 12));
        if (vertical) dock.setGravity(Gravity.BOTTOM);
        if (apps.length > 0) {
            for (App app : apps) {
                Intent intent = installedApps.get(app);
                if (intent == null) continue;
                Button projection = target(app.label, false, () -> open(intent));
                try { inlineButtonIcon(projection, shortcutIcon(app, intent)); }
                catch (PackageManager.NameNotFoundException ignored) { /* Keep the text label. */ }
                addDockAction(dock, vertical, projection);
            }
        }
        addDockAction(dock, vertical, allAppsButton());
        addDockAction(dock, vertical, managerButton());
        if (!vertical) for (int i = 0; i < dock.getChildCount(); i++) dock.getChildAt(i).getLayoutParams().height = -1;
        return dock.getChildCount() == 0 ? null : dock;
    }

    private void addDockAction(LinearLayout dock, boolean vertical, Button action) {
        if (vertical) addFullWidth(dock, action);
        else tile(dock, action);
    }

    private Button target(String label, boolean primary, Runnable action) {
        Button button = button(label, primary, action);
        button.setMinHeight(dp(TARGET_SIZE));
        styleButton(button, colors, primary, false, false);
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            boolean dragged;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getX(); startY = event.getY(); dragged = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    if (Math.hypot(event.getX() - startX, event.getY() - startY) > slop || event.getPointerCount() > 1) {
                        dragged = true; view.setPressed(false); view.cancelLongPress();
                    }
                    if (dragged) return true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    boolean cancel = dragged; dragged = false;
                    if (cancel) { view.setPressed(false); return true; }
                } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) dragged = false;
                return false;
            }
        });
        return button;
    }

    private Drawable shortcutIcon(App app, Intent intent) throws PackageManager.NameNotFoundException {
        return app.glyph == 0 ? getPackageManager().getActivityIcon(intent)
                : consoleIcon(app.glyph);
    }

    private void useShortcutIcon(Button button, Drawable icon) {
        String label = button.getText().toString();
        InsetDrawable spacedIcon = new InsetDrawable(icon, 0, 0, 0, dp(10));
        spacedIcon.setBounds(0, 0, dp(64), dp(74));
        SpannableString iconAndLabel = new SpannableString("\ufffc\n" + label);
        iconAndLabel.setSpan(new ImageSpan(spacedIcon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setMinHeight(dp(124));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(label);
        button.setText(iconAndLabel);
    }

    private void addFullWidth(LinearLayout layout, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        layout.addView(button, params);
    }

    private void addAppButtons(LinearLayout panel, int columns, App... apps) {
        List<Button> shortcuts = new ArrayList<>();
        for (App app : apps) {
            Intent intent = installedApps.get(app);
            if (intent != null) shortcuts.add(shortcut(app, intent));
        }
        if (DashboardApps.customOrder(prefs())) {
            shortcuts.sort(java.util.Comparator.comparingInt(view -> {
                for (int i = 0; i < pinned.size(); i++) if (pinned.get(i).id.equals(view.getTag())) return i;
                return Integer.MAX_VALUE;
            }));
        }
        addShortcuts(panel, columns, shortcuts);
    }

    private void arrangeOnHold(Button tile, String id) {
        tile.setTag(id);
        tile.setOnLongClickListener(view -> {
            DashboardApps.showPicker(this, this::renderDashboard, id);
            return true;
        });
    }

    private Button shortcut(App app, Intent intent) {
        Button button = target(app.label, false, () -> open(intent));
        arrangeOnHold(button, app.packageName);
        try { useShortcutIcon(button, shortcutIcon(app, intent)); }
        catch (PackageManager.NameNotFoundException ignored) { /* Keep the accessible label. */ }
        return button;
    }

    private void addShortcuts(LinearLayout panel, int columns, List<Button> shortcuts) {
        if (shortcuts.isEmpty()) {
            empty(panel, "No shortcuts selected");
            return;
        }
        columns = Math.min(columns, shortcuts.size());
        int rowMinimum = dp(TARGET_SIZE + 8);
        for (Button shortcut : shortcuts) rowMinimum = Math.max(rowMinimum, shortcut.getMinHeight() + dp(8));
        int rows = (shortcuts.size() + columns - 1) / columns;
        panel.setMinimumHeight(panel.getPaddingTop() + panel.getPaddingBottom() + dp(currentPanelHeadings() ? 28 : 0) + rows * rowMinimum);
        for (int i = 0; i < shortcuts.size(); i += columns) {
            LinearLayout line = row();
            line.setMinimumHeight(rowMinimum);
            for (int column = 0; column < columns; column++) {
                int index = i + column;
                if (index < shortcuts.size()) tile(line, shortcuts.get(index));
                else {
                    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, 0, 1);
                    space.setMargins(dp(4), 0, dp(4), 0);
                    line.addView(new View(this), space);
                }
            }
            panel.addView(line, new LinearLayout.LayoutParams(-1, 0, 1));
            for (int child = 0; child < line.getChildCount(); child++) {
                View shortcut = line.getChildAt(child);
                if (shortcut instanceof Button) shortcut.getLayoutParams().height = -1;
            }
        }
    }

    private List<Button> metricButtons() {
        List<Button> buttons = new ArrayList<>();
        for (DashboardApps.Choice choice : additionalApps) {
            if (choice.metric != null) buttons.add(choiceButton(choice));
        }
        return buttons;
    }

    private void updateMetrics() {
        ObdEngine.Snapshot snapshot = vehicle.engine.snapshot();
        boolean imperial = prefs().getBoolean("obd_imperial", false);
        long now = System.currentTimeMillis();
        for (Map.Entry<Button, ObdPid> entry : metricTiles.entrySet()) {
            ObdPid pid = entry.getValue();
            ObdEngine.Reading reading = snapshot.readings.get(pid.id);
            String value;
            if (reading != null && reading.timestampMillis > 0 && Double.isFinite(reading.value)) {
                long age = now - reading.timestampMillis;
                long window = Math.max(10000, Math.max(pid.intervalMillis, vehicle.engine.refreshCycleMillis()) * 2 + 2500);
                boolean live = snapshot.phase == ObdEngine.Phase.MONITORING && age >= 0 && age <= window
                        && (reading.error == null || reading.error.isEmpty());
                value = (live ? "" : "Last · ") + pid.valueText(reading.value, imperial) + " " + pid.unit(imperial);
            } else {
                ObdSensorCheck check = snapshot.sensorChecks.get(pid.id);
                if (snapshot.phase == ObdEngine.Phase.ERROR) value = "Connection lost";
                else if (snapshot.phase == ObdEngine.Phase.DISCONNECTED) value = "Disconnected";
                else if (snapshot.phase == ObdEngine.Phase.PAUSED) value = "Paused";
                else if (check != null && !snapshot.supported.contains(pid.id)) value = check.label();
                else value = "Waiting…";
            }
            setMetricText(entry.getKey(), pid, value.trim());
        }
    }

    private void setMetricText(Button tile, ObdPid pid, String value) {
        String text = value + "\n" + pid.label;
        if (text.contentEquals(tile.getText())) return;
        SpannableString caption = new SpannableString(text);
        caption.setSpan(new android.text.style.RelativeSizeSpan(1.22f), 0, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tile.setText(caption);
        tile.setContentDescription(pid.label + ", " + value + ", open history");
    }

    private Button choiceButton(DashboardApps.Choice choice) {
        Button button = target(choice.label, false, () -> open(choice.intent(this)));
        arrangeOnHold(button, choice.id);
        if (choice.metric != null) {
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(6), dp(4), dp(6), dp(4));
            button.setMaxLines(3);
            button.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            setMetricText(button, choice.metric, "—");
            metricTiles.put(button, choice.metric);
            return button;
        }
        try { useShortcutIcon(button, choice.icon(this)); }
        catch (PackageManager.NameNotFoundException ignored) { /* Keep the text label. */ }
        return button;
    }

    private List<Button> chosenShortcuts(int capacity) {
        List<Button> shortcuts = new ArrayList<>();
        for (DashboardApps.Choice choice : pinned) {
            if (shortcuts.size() == capacity) break;
            shortcuts.add(choiceButton(choice));
        }
        return shortcuts;
    }

    private void largeShortcuts() {
        LinearLayout panel = panel("Home shortcuts");
        List<Button> shortcuts = chosenShortcuts(8);
        for (Button shortcut : shortcuts) shortcut.setMinHeight(dp(126));
        int width = getResources().getConfiguration().screenWidthDp;
        int columns = DashboardApps.shortcutColumns(width, shortcuts.size());
        addShortcuts(panel, columns, shortcuts);
        addSection(panel);
        bottomDock = dock(false);
    }

    private void alignFavourite(Button tile) {
        // Centre an icon with room for two label lines, including when only one is used.
        tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tile.addOnLayoutChangeListener((view, left, top, right, bottom, oldL, oldT, oldR, oldB) -> {
            Layout layout = tile.getLayout();
            if (layout == null || layout.getLineCount() == 0) return;
            int reserved = layout.getLineTop(1) + 2 * tile.getLineHeight();
            int contentHeight = layout.getHeight();
            int inset = Math.max(0, (bottom - top - Math.max(reserved, contentHeight)) / 2);
            if (tile.getPaddingTop() != inset) tile.setPadding(dp(12), inset, dp(12), 0);
        });
    }

    private void clockAndFavourites() {
        LinearLayout time = panel("Local time");
        ClockFaceView clock = new ClockFaceView(this, currentClockFace(), true);
        time.addView(clock, new LinearLayout.LayoutParams(-1, 0, 1));
        if (WeatherRepository.enabled(prefs())) {
            weatherWidget = new WeatherWidget(this);
            LinearLayout.LayoutParams weatherBounds = new LinearLayout.LayoutParams(-1, dp(WeatherWidget.HOME_HEIGHT_DP));
            weatherBounds.topMargin = dp(6);
            time.addView(weatherWidget, weatherBounds);
        }
        int capacity = currentFavouriteTiles();
        List<Button> favourites = chosenShortcuts(capacity);
        String title = pinned.size() > capacity ? "Favourites · " + capacity + " of " + pinned.size() : "Favourites";
        LinearLayout apps = panel(title);
        while (favourites.size() < capacity) {
            Button add = target("Add shortcut", false, this::showAppPicker);
            Drawable glyph = getDrawable(R.drawable.ic_shortcut_add).mutate();
            glyph.setTint(colors.muted);
            useShortcutIcon(add, glyph);
            styleButton(add, colors.surface, colors.muted, colors.outline, 1, colors.accent);
            add.setContentDescription("Add favourite shortcut");
            favourites.add(add);
        }
        for (Button favourite : favourites) if (!metricTiles.containsKey(favourite)) alignFavourite(favourite);
        int columns = capacity == 6 && getResources().getConfiguration().screenWidthDp >= 900 ? 3 : 2;
        addShortcuts(apps, columns, favourites);
        if (getResources().getConfiguration().screenWidthDp < 720) {
            addSection(split(time, 2, apps, 3, 720));
            bottomDock = dock(false);
        } else if (weatherWidget != null) {
            addWeatherAlignedDock(apps);
            addSection(split(time, 2, apps, 3, 720));
        } else {
            LinearLayout right = column();
            right.addView(apps, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams dockBounds = new LinearLayout.LayoutParams(-1, -2);
            dockBounds.topMargin = dp(10);
            right.addView(dock(false), dockBounds);
            addSection(split(time, 2, right, 3, 720));
        }
    }

    private void addWeatherAlignedDock(LinearLayout panel) {
        // Both columns share the panel insets. Use the weather footer's geometry for
        // the actual button fills and divider, including in full-screen preview.
        View divider = new View(this);
        divider.setBackgroundColor(colors.outline);
        LinearLayout.LayoutParams rule = new LinearLayout.LayoutParams(-1, dp(1));
        rule.setMargins(dp(4), dp(8), dp(4), dp(WeatherWidget.DETAILS_DIVIDER_GAP_DP) - rule.height);
        panel.addView(divider, rule);

        LinearLayout actions = dock(false);
        actions.setPadding(0, 0, 0, 0);
        actions.setBackground(null);
        for (int i = 0; i < actions.getChildCount(); i++) {
            LinearLayout.LayoutParams button = (LinearLayout.LayoutParams) actions.getChildAt(i).getLayoutParams();
            button.topMargin = 0;
            button.bottomMargin = 0;
        }
        LinearLayout.LayoutParams footer = new LinearLayout.LayoutParams(-1, dp(WeatherWidget.HOME_DETAILS_HEIGHT_DP));
        footer.bottomMargin = dp(WeatherWidget.DETAILS_BOTTOM_DP);
        panel.addView(actions, footer);
    }

    private void showAllApps() {
        LauncherDrawer.show(this);
    }

    private Button allAppsButton() {
        Button apps = target("All apps", false, this::showAllApps);
        Drawable grid = getDrawable(R.drawable.drawer_grid).mutate();
        grid.setTint(colors.ink);
        inlineButtonIcon(apps, grid);
        return apps;
    }

    private Button managerButton() {
        Button manager = target("AVC Manager", false, preview ? this::finish : this::openManager);
        Drawable cog = getDrawable(R.drawable.ic_settings_cog).mutate();
        cog.setTint(colors.ink);
        inlineButtonIcon(manager, cog);
        manager.setTooltipText(preview ? "Back to Appearance" : "Open AVC Manager");
        return manager;
    }

    private DashboardApps.Choice driverFavourite() {
        for (DashboardApps.Choice choice : additionalApps)
            if (choice.metric == null && !choice.isMedia(this)) return choice;
        for (DashboardApps.Choice choice : additionalApps) if (choice.metric == null) return choice;
        // Keep a source in the main media panel when the driver only pinned one.
        App first = null;
        for (App app : new App[] {App.FM, App.BLUETOOTH, App.USB, App.SPOTIFY}) {
            if (!installedApps.containsKey(app)) continue;
            if (first != null) return new DashboardApps.Choice(first.label, first.packageName);
            first = app;
        }
        return null;
    }

    private LinearLayout driverRail(DashboardApps.Choice favourite) {
        LinearLayout rail = column();
        rail.setPadding(dp(8), dp(8), dp(8), dp(8));
        rail.setBackground(shape((colors.surface & 0x00ffffff) | 0xeb000000, 20));
        if (favourite != null) {
            Button shortcut = railAction(favourite.label, () -> open(favourite.intent(this)));
            arrangeOnHold(shortcut, favourite.id);
            shortcut.setSingleLine(false);
            shortcut.setMaxLines(3);
            addFullWidth(rail, shortcut);
        } else {
            Button choose = railAction("Choose shortcuts", this::showAppPicker);
            choose.setSingleLine(false);
            choose.setMaxLines(2);
            addFullWidth(rail, choose);
        }
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        addFullWidth(rail, railAction("All apps", this::showAllApps));
        Button manager = managerButton();
        manager.setTextSize(13);
        manager.setIncludeFontPadding(false);
        manager.setPadding(dp(8), dp(9), dp(8), dp(9));
        // The driver rail is narrow; keep the same named action with the icon above it.
        Drawable cog = getDrawable(R.drawable.ic_settings_cog).mutate();
        cog.setTint(colors.ink); cog.setBounds(0, 0, dp(28), dp(28));
        manager.setCompoundDrawables(null, cog, null, null);
        manager.setCompoundDrawablePadding(dp(4)); manager.setText("AVC Manager");
        manager.setSingleLine(true);
        addFullWidth(rail, manager);
        return rail;
    }

    private int driverRailWidth() {
        Paint measure = new Paint();
        measure.setTypeface(Typeface.create(Typeface.DEFAULT, 550, false));
        measure.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 15, getResources().getDisplayMetrics()));
        return Math.max(dp(116), (int) Math.ceil(measure.measureText("Appearance")) + dp(48));
    }

    private Button railAction(String label, Runnable action) {
        Button button = target(label, false, action);
        button.setPadding(dp(8), dp(9), dp(8), dp(9));
        button.setSingleLine(true);
        button.setMinHeight(dp(TARGET_SIZE));
        button.setMinimumHeight(dp(TARGET_SIZE));
        button.setEllipsize(TextUtils.TruncateAt.END);
        return button;
    }

    private void showAppPicker() {
        DashboardApps.showPicker(this, this::renderDashboard);
    }

    private void openManager() {
        open(managerIntent(this, "Appearance"));
    }

    private void openSettings() {
        open(new Intent(Settings.ACTION_SETTINGS));
    }

    private void openStockHome() {
        open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(STOCK_HOME, STOCK_HOME + ".Launcher")));
    }
}
