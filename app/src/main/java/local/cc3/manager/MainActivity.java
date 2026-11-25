package local.cc3.manager;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;

public final class MainActivity extends Screen {
    private static final int VPN_CONSENT = 21;
    private static final int HOME_CONSENT = 22;
    private static final int SPEECH_TEST = 23;
    private static final int TEST_TRACK_ACCESS = 24;
    private static final int BACKGROUND_PHOTO = 25;
    private static final int OBD_PERMISSIONS = 26;
    private static final int OBD_BLUETOOTH_ENABLE = 27;
    private static final int OBD_EXPORT = 28;
    private static final int WEATHER_LOCATION = 29;
    private static final int MEDIA_ACCESS = 30;
    private static final int MEDIA_IMPORT = 31;
    private static final String PENDING_OBD_EXPORT = "obd_pending_export_file";
    private static final String GOOGLE = "com.google.android.googlequicksearchbox";
    private static final String GOOGLE_ASSISTANT = "com.google.android.apps.googleassistant";
    private static final int RAIL_WIDTH = 176;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String section;
    private FrameLayout workspace;
    private LinearLayout navigation;
    private View sectionView;
    private TextView detail;
    private TextView accessAddress;
    private TextView wifiState,accessMask,accessRouter;
    private TextView accessMessage;
    private TextView debugBadge, debugTime, debugTimeLabel;
    private Button enableAdbButton;
    private Button stopAdbButton;
    private TrafficPage trafficPage;
    private int pendingVpnModes;
    private String controlDetail;
    private TextView mobileState;
    private MobileDataPage.Page mobileDetail;
    private MobileDataPage mobilePage;
    private ControlsPage controls;
    private EqualizerPage equalizer;
    private VehiclePage vehiclePage;
    private WeatherPage weatherPage;
    private EntertainmentPage entertainmentPage;
    private String pendingObdExport;
    private TextView homeStatus;
    private View selectedLayoutHeading;
    private Button hannaAction;
    private TextView homeSetupHint;
    private Button defaultHome;
    private DashboardLayout chosenLayout;
    private String chosenBackground;
    private String chosenClockFace;
    private int chosenFavouriteTiles;
    private Boolean chosenPanelHeadings;
    private boolean adbBusy;
    private boolean probing;
    private String adbMessage = "";
    private boolean portOpen;
    private boolean portChecked;
    private boolean voiceBusy;
    private String voiceMessage = "";
    private TextView assistantStatus;
    private TextView recognizerStatus;
    private TextView speechResult;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SecurityBootReceiver.restoreProtection(this);
        pendingObdExport = prefs().getString(PENDING_OBD_EXPORT, null);
        String requestedSection = getIntent().getStringExtra("screen");
        section = requestedSection;
        boolean newRoute = state != null && validSection(requestedSection)
                && (!requestedSection.equals(state.getString("launchScreen"))
                || getIntent().getLongExtra(MANAGER_ROUTE, 0) != state.getLong("launchRoute", 0));
        if (state != null) {
            section = state.getString("section", section);
            pendingVpnModes = state.getInt("pendingVpnModes");
            controlDetail = state.getString("controlDetail");
            String savedMobile = state.getString("mobileDetail");
            if (savedMobile != null) mobileDetail = MobileDataPage.Page.valueOf(savedMobile);
            String layout = state.getString("chosenLayout");
            if (layout != null) chosenLayout = DashboardLayout.fromId(layout);
            chosenBackground = state.getString("chosenBackground");
            chosenClockFace = state.getString("chosenClockFace");
            chosenFavouriteTiles = state.getInt("chosenFavouriteTiles");
            if (state.containsKey("chosenPanelHeadings")) chosenPanelHeadings = state.getBoolean("chosenPanelHeadings");
            pendingObdExport = state.getString("pendingObdExport", pendingObdExport);
        }
        // A fresh shortcut takes precedence over an older task's saved page; rotation keeps its draft.
        if (newRoute) {
            section = requestedSection;
            controlDetail = null;
            mobileDetail = null;
            chosenLayout = null;
            chosenBackground = null;
            chosenClockFace = null;
            chosenFavouriteTiles = 0;
            chosenPanelHeadings = null;
        }
        if ("Weather".equals(section)) {
            section = "Controls";
            controlDetail = "Weather";
        } else if ("Voice".equals(section)) {
            section = "Controls";
            controlDetail = "Voice";
        } else if ("Controls".equals(section) && "Equalizer".equals(controlDetail)) {
            section = "Audio";
            controlDetail = null;
        }
        if (!validSection(section)) section = "Access";
        render();
        openRequestedPage(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String requested = intent.getStringExtra("screen");
        if ("Weather".equals(requested)) showControl("Weather");
        else if ("Voice".equals(requested)) showControl("Voice");
        else if (validSection(requested)) section(requested);
        openRequestedPage(intent);
    }

    private void openRequestedPage(Intent intent) {
        if ("Vehicle".equals(section) && vehiclePage != null
                && intent.getBooleanExtra("vehicle_dashboard", false)) {
            vehiclePage.showDashboard();
            String metric = intent.getStringExtra("home_metric");
            if (metric != null) vehiclePage.openHomeMetric(metric);
        }
        if ("Entertainment".equals(section) && entertainmentPage != null) {
            String tab = intent.getStringExtra("media_tab");
            if ("music".equals(tab) || "video".equals(tab)) entertainmentPage.showVideo("video".equals(tab));
        }
    }

    private boolean validSection(String name) {
        return "Access".equals(name) || "Traffic".equals(name) || "Appearance".equals(name)
                || "Audio".equals(name) || "Voice".equals(name) || "Controls".equals(name)
                || "Vehicle".equals(name) || "Weather".equals(name) || "Entertainment".equals(name);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("section", section);
        state.putString("launchScreen", getIntent().getStringExtra("screen"));
        state.putLong("launchRoute", getIntent().getLongExtra(MANAGER_ROUTE, 0));
        state.putInt("pendingVpnModes", pendingVpnModes);
        state.putString("controlDetail", controlDetail);
        if (mobileDetail != null) state.putString("mobileDetail", mobileDetail.name());
        if (chosenLayout != null) state.putString("chosenLayout", chosenLayout.id);
        state.putString("chosenBackground", chosenBackground);
        state.putString("chosenClockFace", chosenClockFace);
        state.putInt("chosenFavouriteTiles", chosenFavouriteTiles);
        if (chosenPanelHeadings != null) state.putBoolean("chosenPanelHeadings", chosenPanelHeadings);
        state.putString("pendingObdExport", pendingObdExport);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
        if (controls != null) controls.start();
        if (equalizer != null) equalizer.start();
        if (vehiclePage != null && vehicleArea()) vehiclePage.onResume();
        if (entertainmentPage != null && "Entertainment".equals(section)) entertainmentPage.onResume();
        if (weatherPage != null && "Controls".equals(section) && "Weather".equals(controlDetail)) weatherPage.onResume();
        if (homeStatus != null) refreshHomeStatus();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        if (controls != null) controls.stop();
        if (equalizer != null) equalizer.stop();
        if (vehiclePage != null) vehiclePage.onLeave();
        if (entertainmentPage != null) entertainmentPage.onLeave();
        if (weatherPage != null) weatherPage.onLeave();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (mobilePage != null) mobilePage.close();
        if (vehiclePage != null) vehiclePage.close();
        if (weatherPage != null) weatherPage.close();
        if (entertainmentPage != null) entertainmentPage.close();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void section(String value) {
        if ("Vehicle".equals(section) && "Vehicle".equals(value)) return;
        if (vehiclePage != null && !"Vehicle".equals(value)) vehiclePage.onLeave();
        if (weatherPage != null) weatherPage.onLeave();
        if (entertainmentPage != null) entertainmentPage.onLeave();
        if ("Appearance".equals(section) && !"Appearance".equals(value)) {
            chosenLayout = null;
            chosenBackground = null;
            chosenClockFace = null;
            chosenFavouriteTiles = 0;
            chosenPanelHeadings = null;
        }
        controlDetail = null;
        mobileDetail = null;
        section = value;
        render();
    }

    private void render() {
        if (mobilePage != null) mobilePage.close();
        mobilePage = null;
        if (controls != null) controls.stop();
        if (equalizer != null) equalizer.stop();
        controls = null;
        equalizer = null;
        trafficPage = null;
        mobileState = null;
        detail = null;
        debugBadge = debugTime = debugTimeLabel = null;
        enableAdbButton = null;
        stopAdbButton = null;
        hannaAction = null;
        assistantStatus = null;
        recognizerStatus = null;
        speechResult = null;
        homeStatus = null;
        homeSetupHint = null;
        defaultHome = null;
        buildWorkspace();
        if ("Traffic".equals(section)) traffic();
        else if ("Appearance".equals(section)) appearance();
        else if ("Audio".equals(section)) {
            equalizer = new EqualizerPage(this);
            equalizer.start();
        }
        else if ("Vehicle".equals(section)) {
            if (vehiclePage == null) vehiclePage = new VehiclePage(this);
            showWorkspace(vehiclePage.build());
            vehiclePage.onResume();
        }
        else if ("Entertainment".equals(section)) {
            if (entertainmentPage == null) {
                entertainmentPage = new EntertainmentPage(this);
                entertainmentPage.setUploadAction(() -> open(new Intent(this, MediaTransferActivity.class)));
                entertainmentPage.setImportAction(this::importMedia);
                entertainmentPage.setStorageAccessAction(() -> requestPermissions(
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, MEDIA_ACCESS));
            }
            showWorkspace(entertainmentPage.build());
            entertainmentPage.onResume();
        }
        else if ("Controls".equals(section)) {
            if("Vehicle".equals(controlDetail)){
                if(vehiclePage==null)vehiclePage=new VehiclePage(this);
                showWorkspace(vehiclePage.buildControls());vehiclePage.onResume();
            } else if ("Display".equals(controlDetail)) {
                controls = new ControlsPage(this);
                controls.start();
            } else if ("Voice".equals(controlDetail)) voice();
            else if ("Weather".equals(controlDetail)) {
                if (weatherPage == null) weatherPage = new WeatherPage(this);
                showWorkspace(weatherPage.build());
                weatherPage.onResume();
            }
            else showControlsMenu();
        }
        else if (mobileDetail != null) mobilePage = new MobileDataPage(this, mobileDetail, () -> section("Access"));
        else access();
        refreshStatus();
    }

    private void showControlsMenu() {
        pageHeading("Controls");
        LinearLayout controls = column();
        addControlEntry(controls, "Display settings", "Day and night screen brightness", "Display", R.drawable.ic_display_brightness);
        addControlEntry(controls, "Voice", "Hanna, digital assistant and speech recognition", "Voice", R.drawable.ic_voice_mic);
        addControlEntry(controls, "Home app", "Current Home and other installed launchers", "Home", R.drawable.ic_home_outline);
        addControlEntry(controls, "Weather", "Seven-day clock forecast, current location or saved place", "Weather", R.drawable.ic_weather_conditions);
        addControlEntry(controls, "Vehicle", "Vehicle profile, OBD adapter and automatic connection", "Vehicle", R.drawable.ic_nav_vehicle);
        addSection(controls);
    }

    private void addControlEntry(LinearLayout controls, String label, String description, String destination, int glyph) {
        LinearLayout entry = row();
        entry.setPadding(dp(8), dp(8), dp(12), dp(8));
        entry.setBackground(shape(colors.surface, 16));
        Button action = button(label, false, () -> {
            if ("Home".equals(destination)) showOtherHomeApps();
            else showControl(destination);
        });
        action.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.selection),
                shape(android.graphics.Color.TRANSPARENT, 12), null));
        action.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        action.setTextSize(18);
        action.setPadding(dp(16), dp(12), dp(16), dp(12));
        action.setMinHeight(dp(72));
        android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_chevron_right).mutate();
        arrow.setTint(colors.muted);
        arrow.setBounds(0, 0, dp(24), dp(24));
        Drawable leading = consoleIcon(glyph);
        leading.setBounds(0, 0, dp(44), dp(44));
        action.setCompoundDrawablesRelative(leading, null, arrow, null);
        action.setCompoundDrawablePadding(dp(14));
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(0, -1, 1);
        entry.addView(action, bounds);
        LinearLayout.LayoutParams info = new LinearLayout.LayoutParams(dp(48), dp(48));
        info.leftMargin = dp(8);
        entry.addView(infoButton(label, description), info);
        LinearLayout.LayoutParams rowBounds = new LinearLayout.LayoutParams(-1, dp(88));
        if (controls.getChildCount() > 0) rowBounds.topMargin = dp(10);
        controls.addView(entry, rowBounds);
    }

    void addCenteredSection(View heading, View panel, int maxWidth) {
        LinearLayout group = column();
        group.addView(heading);
        addSection(group, panel);
        content.addView(new View(this), new LinearLayout.LayoutParams(1, dp(10), 1));
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(dp(Math.min(maxWidth, paneWidth())), -2);
        bounds.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(group, bounds);
        content.addView(new View(this), new LinearLayout.LayoutParams(1, dp(10), 1));
    }

    LinearLayout controlsHeader(String title, String help) {
        LinearLayout header = row();
        View label = help == null ? pageTitle(title) : helpHeading(title, 24, help);
        header.addView(label, new LinearLayout.LayoutParams(0, dp(56), 1));
        header.addView(button("Back to Controls", false, () -> showControl(null)),
                new LinearLayout.LayoutParams(-2, dp(56)));
        return header;
    }

    void addControlsDetail(String title, View panel, int maxWidth) {
        content.addView(controlsHeader(title, null));
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(dp(Math.min(maxWidth, paneWidth())), -2);
        bounds.gravity = Gravity.CENTER_HORIZONTAL;
        bounds.topMargin = dp(12);
        content.addView(panel, bounds);
    }

    private boolean vehicleArea(){return "Vehicle".equals(section)||("Controls".equals(section)&&"Vehicle".equals(controlDetail));}

    void showControl(String name) {
        if (vehiclePage != null && !"Vehicle".equals(name)) vehiclePage.onLeave();
        if (entertainmentPage != null) entertainmentPage.onLeave();
        if (weatherPage != null && !"Weather".equals(name)) weatherPage.onLeave();
        section = "Controls";
        controlDetail = name;
        render();
    }

    // Android10 compatibility: this scoped platform path intentionally retains legacy APIs.
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (entertainmentPage != null && entertainmentPage.exitFullscreen()) return;
        if(vehicleArea() && vehiclePage!=null && vehiclePage.handleBack())return;
        if (trafficPage != null && trafficPage.closeMap()) return;
        if ("Access".equals(section) && mobileDetail != null) section("Access");
        else if ("Controls".equals(section) && controlDetail != null) section("Controls");
        else super.onBackPressed();
    }

    private Button tab(String label, String page) {
        boolean active = section.equals(page);
        Button tab = button(label, false, () -> section(page));
        tab.setContentDescription(label);
        tab.setTooltipText(label);
        if (usesNavigationRail() && NavigationStyle.icons(prefs())) {
            Drawable icon = getDrawable(NavigationStyle.icon(page)).mutate();
            icon.setTint(active ? colors.accent : colors.ink);
            icon.setBounds(0, 0, dp(32), dp(32));
            android.text.SpannableString glyph = new android.text.SpannableString("\ufffc");
            glyph.setSpan(new android.text.style.ImageSpan(icon), 0, 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tab.setText(glyph);
            tab.setGravity(Gravity.CENTER);
        }
        tab.setSelected(active);
        if (usesNavigationRail()) {
            styleButton(tab, active ? colors.selection : colors.surface,
                    active ? colors.ink : colors.muted, 0, 0, colors.accent);
            if (active) {
                GradientDrawable marker = shape(colors.accent, 2);
                marker.setSize(dp(4), dp(28));
                android.graphics.drawable.LayerDrawable indicator =
                        new android.graphics.drawable.LayerDrawable(new Drawable[]{tab.getBackground(), marker});
                indicator.setLayerGravity(1, Gravity.START | Gravity.CENTER_VERTICAL);
                indicator.setLayerInsetStart(1, dp(2));
                tab.setBackground(indicator);
            }
        } else {
            styleButton(tab, colors, false, false, active);
        }
        return tab;
    }

    private boolean usesNavigationRail() {
        return getResources().getConfiguration().screenWidthDp >= 930
                && getResources().getConfiguration().screenHeightDp >= 540;
    }

    int paneWidth() {
        return getResources().getConfiguration().screenWidthDp - (usesNavigationRail() ? navigationWidth() : 0) - 32;
    }

    private int navigationWidth() { return NavigationStyle.icons(prefs()) ? 96 : RAIL_WIDTH; }

    boolean usesFixedConnections() {
        return usesNavigationRail() && paneWidth() >= 750
                && getResources().getConfiguration().screenHeightDp >= 580;
    }

    private void buildWorkspace() {
        prepareWindow();
        boolean rail = usesNavigationRail();
        LinearLayout root = rail ? row() : column();
        root.setBackground(workspaceBackground());
        root.setGravity(Gravity.TOP);
        workspace = new FrameLayout(this);
        content = column();
        content.setPadding(dp(16), dp(12), dp(16), dp(16));
        if (("Traffic".equals(section) && usesFixedConnections()) || "Vehicle".equals(section) || "Entertainment".equals(section)
                || ("Controls".equals(section) && ("Weather".equals(controlDetail)||"Vehicle".equals(controlDetail)))) {
            sectionView = content;
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(content);
            sectionView = scroll;
        }
        showWorkspace(null);

        Button[] tabs = {tab("Wireless", "Access"), tab("Firewall", "Traffic"),
                tab("Sound", "Audio"), tab("Appearance", "Appearance"), tab("Controls", "Controls"),
                tab("Vehicle", "Vehicle"), tab("Entertainment", "Entertainment")};
        navigation = column();
        navigation.setBackgroundColor(colors.surface);
        navigation.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (rail) {
            root.addView(workspace, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(navigation, new LinearLayout.LayoutParams(dp(navigationWidth()), -1));
            addManagerBrand(navigation);
            for (Button item : tabs) {
                item.setPadding(dp(10), dp(9), dp(10), dp(9));
                item.setSingleLine(true);
                item.setMinHeight(dp(48));
                item.setMinimumHeight(dp(48));
                item.setGravity(NavigationStyle.icons(prefs()) ? Gravity.CENTER : Gravity.CENTER_VERTICAL | Gravity.START);
                LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, 0, 1);
                bounds.topMargin = dp(10);
                navigation.addView(item, bounds);
            }
        } else {
            root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
            root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
            addNavigationTabs(navigation, tabs);
        }
        setContentView(root);
    }

    @SuppressWarnings("deprecation")
    void setMediaFullscreen(boolean fullscreen) {
        if (navigation != null) navigation.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(fullscreen
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : 0);
    }

    private void addManagerBrand(LinearLayout navigation) {
        boolean icons = NavigationStyle.icons(prefs());
        Button brand = button(icons ? "" : "Home", true, () ->
                open(new Intent(this, DashboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        brand.setTooltipText("Open Home");
        Drawable house = getDrawable(R.drawable.ic_home_outline).mutate();
        house.setTint(colors.onAccent);
        if (icons) {
            // A centred image on a filled button keeps Home distinct from the current-section marker.
            house.setBounds(0, 0, dp(34), dp(34));
            android.text.SpannableString glyph = new android.text.SpannableString("\ufffc");
            glyph.setSpan(new ButtonIconSpan(house), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            brand.setText(glyph);
            brand.setGravity(Gravity.CENTER);
        } else inlineButtonIcon(brand, house);
        brand.setMinHeight(dp(60));
        brand.setPadding(dp(6), dp(4), dp(6), dp(4));
        brand.setContentDescription("AVC Manager · Open Home");
        navigation.addView(brand);
    }

    private void addNavigationTabs(LinearLayout navigation, Button[] tabs) {
        int columns = paneWidth() >= 660 ? tabs.length : 3;
        for (int i = 0; i < tabs.length; i += columns) {
            LinearLayout line = row();
            for (int j = i; j < Math.min(i + columns, tabs.length); j++) tile(line, tabs[j]);
            navigation.addView(line);
        }
    }

    void showWorkspace(View page) {
        View shown = page == null ? sectionView : page;
        if (shown.getParent() == workspace) return;
        workspace.removeAllViews();
        workspace.addView(shown, new FrameLayout.LayoutParams(-1, -1));
    }

    TextView pageTitle(String label) {
        TextView title = managerText(label, 24, colors.ink, 650);
        title.setAccessibilityHeading(true);
        return title;
    }

    void pageHeading(String label) {
        content.addView(pageTitle(label));
    }

    void pageHeading(String label, String help) {
        content.addView(helpHeading(label, 24, help));
    }

    private void access() {
        pageHeading("Wireless","Manage Wi-Fi, mobile data and temporary computer access. Network settings affect connectivity; computer access is a separate timed maintenance session.");
        boolean wide=paneWidth()>=750;
        LinearLayout networks=wide?row():column();
        LinearLayout wifi = card();
        wifi.addView(helpHeading("Wi-Fi",19,"The local Wi-Fi address, subnet mask and default router are read from the active physical Wi-Fi connection."));
        wifiState=managerText("Checking connection…",13,colors.muted,550);wifi.addView(wifiState);
        TextView ipLabel=managerText("IP address",12,colors.muted,500);ipLabel.setPadding(0,dp(12),0,0);wifi.addView(ipLabel);
        accessAddress=managerText("—",26,colors.ink,650);accessAddress.setSingleLine(true);wifi.addView(accessAddress);
        LinearLayout local=row();
        accessMask=networkValue(local,"Subnet mask");accessRouter=networkValue(local,"Default router");
        LinearLayout.LayoutParams infoBounds=new LinearLayout.LayoutParams(-1,-2);infoBounds.topMargin=dp(12);wifi.addView(local,infoBounds);
        wifi.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        Button settings=button("Wi-Fi settings",false,()->open(new Intent(Settings.ACTION_WIFI_SETTINGS)));stylePanelAction(settings);
        wifi.addView(settings,new LinearLayout.LayoutParams(-1,dp(56)));
        boolean mobile=getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        networks.addView(wifi,new LinearLayout.LayoutParams(wide&&mobile?0:-1,wide?dp(320):-2,wide&&mobile?1:0));
        if(mobile){
            LinearLayout data=mobileDataCard();
            LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(wide?0:-1,wide?dp(320):-2,wide?1:0);
            if(wide)bounds.leftMargin=dp(12);else bounds.topMargin=dp(12);
            networks.addView(data,bounds);
        }
        addSection(networks);

        LinearLayout connection = card();
        LinearLayout debugHeading = row();
        debugHeading.addView(helpHeading("Computer access", 19,
                "Wi-Fi debugging lets an authorised computer maintain the head unit. A timed session lasts up to 15 minutes and ends when the trusted Wi-Fi connection changes. Approve only computers you recognise."),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        debugBadge = managerText("Checking", 13, colors.muted, 650);
        debugBadge.setSingleLine(true);
        debugBadge.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        debugBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        debugHeading.addView(debugBadge);
        connection.addView(debugHeading);
        LinearLayout summary = row();
        LinearLayout description = column();
        detail = managerText("", 16, colors.ink, 550);
        detail.setMaxLines(2);
        description.addView(detail);
        accessMessage = managerText("", 13, colors.muted, 450);
        accessMessage.setMaxLines(2);
        accessMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        accessMessage.setVisibility(View.GONE);
        description.addView(accessMessage);
        summary.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout timer = column();
        timer.setGravity(Gravity.END);
        debugTime = managerText("15 min", 26, colors.ink, 650);
        debugTime.setFontFeatureSettings("tnum");
        debugTime.setGravity(Gravity.END);
        debugTimeLabel = managerText("Session limit", 11, colors.muted, 500);
        debugTimeLabel.setGravity(Gravity.END);
        timer.addView(debugTime, new LinearLayout.LayoutParams(-1, -2));
        timer.addView(debugTimeLabel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams timerBounds = new LinearLayout.LayoutParams(dp(142), -2);
        timerBounds.leftMargin = dp(12);
        summary.addView(timer, timerBounds);
        connection.addView(summary);
        LinearLayout debugActions = row();
        enableAdbButton = button("Enable for 15 minutes", true, this::confirmAdb);
        tile(debugActions, enableAdbButton);
        stopAdbButton = button("Turn off", false, () -> setAdb(false));
        stylePanelAction(stopAdbButton);
        tile(debugActions, stopAdbButton);
        LinearLayout.LayoutParams actionBounds = new LinearLayout.LayoutParams(-1, -2);
        actionBounds.topMargin = dp(8);
        connection.addView(debugActions, actionBounds);
        addSection(connection);
    }

    private TextView networkValue(LinearLayout parent,String label){
        LinearLayout item=column();item.addView(managerText(label,11,colors.muted,500));
        TextView value=managerText("—",15,colors.ink,600);value.setSingleLine(true);item.addView(value);
        parent.addView(item,new LinearLayout.LayoutParams(0,-2,1));return value;
    }

    private LinearLayout mobileDataCard(){
        LinearLayout mobile=card();mobile.addView(helpHeading("Mobile data",19,"Configure the SIM connection and access point. Data usage requires Android usage access."));
        mobileState=managerText("",14,colors.muted,500);mobileState.setPadding(0,0,0,dp(8));mobile.addView(mobileState);
        mobile.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        String[] names={"Mobile network","Data usage","Access points"};
        MobileDataPage.Page[] pages={MobileDataPage.Page.NETWORK,MobileDataPage.Page.USAGE,MobileDataPage.Page.APN};
        for(int i=0;i<names.length;i++){
            final MobileDataPage.Page page=pages[i];Button action=button(names[i],false,()->showMobile(page));stylePanelAction(action);
            LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-1,dp(56));if(i>0)bounds.topMargin=dp(8);mobile.addView(action,bounds);
        }
        return mobile;
    }

    private void showMobile(MobileDataPage.Page page) {
        mobileDetail = page;
        render();
    }

    private void stylePanelAction(Button control) {
        styleButton(control, colors, false, true, false);
    }

    private void refreshMobile() {
        if (mobileState == null) return;
        TelephonyManager phone = getSystemService(TelephonyManager.class);
        if (phone == null || !phone.hasIccCard()) {
            mobileState.setText("No SIM detected");
            return;
        }
        String state;
        try {
            state = phone.isDataEnabled() ? "Mobile data enabled" : "Mobile data off";
        } catch (SecurityException unavailable) {
            state = "SIM detected";
        }
        String carrier = phone.getNetworkOperatorName();
        mobileState.setText(carrier.isEmpty() ? state : state + "  ·  " + carrier);
    }

    TextView managerText(String value, int size, int color, int weight) {
        return text(value, size, color, weight);
    }

    LinearLayout card() {
        LinearLayout result = column();
        result.setPadding(dp(16), dp(14), dp(16), dp(14));
        result.setBackground(shape(colors.surface, 12));
        return result;
    }

    void addSection(View view) {
        addSection(content, view);
    }

    private void addSection(LinearLayout parent, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(view, params);
    }

    private void traffic() {
        trafficPage = new TrafficPage(this, this::requestNetworkModes);
    }

    private void requestNetworkModes(int modes) {
        Intent service = new Intent(this, FirewallService.class);
        if (modes == 0) {
            startService(service.setAction(FirewallService.STOP));
        } else if (FirewallService.modes() != 0) {
            startService(service.setAction(FirewallService.START).putExtra(FirewallService.MODES, modes));
        } else {
            Intent consent = VpnService.prepare(this);
            if (consent == null) startNetworkService(modes);
            else {
                pendingVpnModes = modes;
                startActivityForResult(consent, VPN_CONSENT);
            }
        }
        handler.postDelayed(this::refreshStatus, 150);
    }

    private void startNetworkService(int modes) {
        startForegroundService(new Intent(this, FirewallService.class).setAction(FirewallService.START)
                .putExtra(FirewallService.MODES, modes));
    }

    void requestTestTrackAccess() {
        requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, TEST_TRACK_ACCESS);
    }

    boolean hasObdScanPermission() {
        if (Build.VERSION.SDK_INT >= 31) return checkSelfPermission("android.permission.BLUETOOTH_SCAN") == PackageManager.PERMISSION_GRANTED
                && hasObdConnectPermission();
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasObdConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    void requestObdPermissions() {
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[] {
                "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}, OBD_PERMISSIONS);
        else requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, OBD_PERMISSIONS);
    }

    void requestObdBluetoothEnable() {
        try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), OBD_BLUETOOTH_ENABLE); }
        catch (RuntimeException unavailable) { Toast.makeText(this, "Bluetooth cannot be enabled on this firmware.", Toast.LENGTH_LONG).show(); }
    }

    boolean hasWeatherLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void requestWeatherLocationPermission() {
        requestPermissions(new String[] {Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION}, WEATHER_LOCATION);
    }

    void exportObdCsv(String csv) {
        if (csv == null || csv.isEmpty()) return;
        byte[] bytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 2 * 1024 * 1024) {
            Toast.makeText(this, "The report is too large to export.", Toast.LENGTH_LONG).show();
            return;
        }
        java.io.File[] previousReports = getCacheDir().listFiles();
        if (previousReports != null) for (java.io.File previous : previousReports)
            if (previous.getName().startsWith("cc3-obd-export-") && !previous.getName().equals(pendingObdExport)) previous.delete();
        java.io.File file = new java.io.File(getCacheDir(), "cc3-obd-export-" + System.nanoTime() + ".csv");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
            output.write(bytes);
        } catch (IOException error) {
            Toast.makeText(this, "The local report could not be prepared.", Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingObdExport != null) new java.io.File(getCacheDir(), pendingObdExport).delete();
        pendingObdExport = file.getName();
        prefs().edit().putString(PENDING_OBD_EXPORT, pendingObdExport).commit();
        Intent export = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "CC3-vehicle-report.csv");
        try { startActivityForResult(export, OBD_EXPORT); }
        catch (RuntimeException unavailable) {
            file.delete(); pendingObdExport = null;
            prefs().edit().remove(PENDING_OBD_EXPORT).apply();
            Toast.makeText(this, "The document picker is unavailable.", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == TEST_TRACK_ACCESS && equalizer != null) {
            equalizer.testTrackAccessResult(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED);
        }
        if (request == OBD_PERMISSIONS && vehiclePage != null && vehicleArea()) vehiclePage.onPermissionsResult();
        if (request == MEDIA_ACCESS && entertainmentPage != null) entertainmentPage.refreshLibrary();
        if (request == WEATHER_LOCATION && weatherPage != null && "Controls".equals(section)
                && "Weather".equals(controlDetail)) weatherPage.onPermissionsResult();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == MEDIA_IMPORT) {
            if (result != RESULT_OK || data == null || entertainmentPage == null) return;
            ArrayList<android.net.Uri> files = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                android.net.Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null && "content".equals(uri.getScheme())) files.add(uri);
            } else if (data.getData() != null && "content".equals(data.getData().getScheme())) files.add(data.getData());
            if (!files.isEmpty()) entertainmentPage.importDocuments(files);
        } else if (request == OBD_BLUETOOTH_ENABLE) {
            if (vehiclePage != null && vehicleArea()) vehiclePage.onPermissionsResult();
        } else if (request == OBD_EXPORT) {
            String name = pendingObdExport == null ? prefs().getString(PENDING_OBD_EXPORT, null) : pendingObdExport;
            pendingObdExport = null;
            prefs().edit().remove(PENDING_OBD_EXPORT).apply();
            if (name == null || !name.startsWith("cc3-obd-export-") || name.contains("/")) return;
            java.io.File source = new java.io.File(getCacheDir(), name);
            android.net.Uri destination = data == null ? null : data.getData();
            if (result != RESULT_OK || destination == null || !"content".equals(destination.getScheme())) { source.delete(); return; }
            new Thread(() -> {
                boolean saved = false;
                try (java.io.InputStream input = new java.io.FileInputStream(source);
                        java.io.OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new IOException("No document output");
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
                    saved = true;
                } catch (IOException | RuntimeException unavailable) { /* Show the result on the screen. */ }
                finally { source.delete(); }
                final boolean complete = saved;
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) Toast.makeText(this,
                        complete ? "Vehicle report saved." : "The report could not be saved.", Toast.LENGTH_LONG).show(); });
            }, "cc3-obd-export").start();
        } else if (request == BACKGROUND_PHOTO) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            android.net.Uri uri = data.getData();
            if (!"content".equals(uri.getScheme())) return;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException unavailable) { Toast.makeText(this, "Choose a photo from Files so access can be kept after restart.", Toast.LENGTH_LONG).show(); return; }
            String style = "custom:" + uri;
            new Thread(() -> {
                boolean readable = HomeBackground.readable(this, style);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (readable) { chosenBackground = style; refreshAppearanceSelection(); }
                    else Toast.makeText(this, "That photo could not be opened.", Toast.LENGTH_LONG).show();
                });
            }, "cc3-photo").start();
        } else if (request == VPN_CONSENT) {
            int modes = pendingVpnModes;
            pendingVpnModes = 0;
            if (result == RESULT_OK && modes != 0) startNetworkService(modes);
            else if (trafficPage != null) trafficPage.networkChangeCanceled();
        } else if (request == HOME_CONSENT) {
            DashboardLayout.finishHomeSelection(prefs(), dashboardIsHome() || result == RESULT_OK);
            if (homeStatus != null) refreshAppearanceSelection();
        } else if (request == SPEECH_TEST && speechResult != null) {
            ArrayList<String> phrases = data == null ? null
                    : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            showText(speechResult, result == RESULT_OK && phrases != null && !phrases.isEmpty()
                    ? "Heard “" + phrases.get(0) + "”"
                    : result == RESULT_CANCELED ? "Voice test closed" : "No speech was returned");
        }
    }

    private void importMedia() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
        try { startActivityForResult(intent, MEDIA_IMPORT); }
        catch (ActivityNotFoundException | SecurityException unavailable) { open(new Intent(this, MediaImportActivity.class)); }
    }

    private void appearance() {
        pageHeading("Appearance", "App colours update Home immediately. Preview a layout, then use Apply to Home to save it.");
        if (chosenLayout == null) chosenLayout = DashboardLayout.selected(prefs());
        if (chosenBackground == null) chosenBackground = super.currentBackground();
        if (chosenClockFace == null) chosenClockFace = super.currentClockFace();
        if (chosenFavouriteTiles == 0) chosenFavouriteTiles = super.currentFavouriteTiles();
        if (chosenPanelHeadings == null) chosenPanelHeadings = super.currentPanelHeadings();
        LinearLayout launcher = column();
        boolean wide = paneWidth() >= 960;
        if (wide) {
            LinearLayout columns = row();
            columns.setBaselineAligned(false);
            columns.setGravity(Gravity.TOP);
            columns.addView(launcher, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams styleBounds = new LinearLayout.LayoutParams(dp(320), -1);
            styleBounds.leftMargin = dp(12);
            columns.addView(appearanceOverview(), styleBounds);
            addSection(columns);
            fitAppearanceColumns(columns);
        } else addSection(launcher);

        LinearLayout active = card();
        homeStatus = managerText("", 15, colors.muted, 500);
        homeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        active.addView(homeStatus);
        selectedLayoutHeading = helpHeading("Selected layout · " + chosenLayout.title, "Selected layout", 17,
                "Select a layout, background and clock options. Full-screen preview leaves your saved Home unchanged. Apply to Home saves them together. Choose Home shortcuts selects installed apps and AVC Manager functions for the layouts.");
        active.addView(selectedLayoutHeading);
        homeSetupHint = managerText("Choose AVC Dashboard when Android first asks for your Home app.", 14, colors.muted, 500);
        active.addView(homeSetupHint);
        launcher.addView(active);
        DashboardLayout[] layouts = DashboardLayout.values();
        java.util.List<DashboardApps.Choice> pinned = DashboardApps.pinned(this);
        int count = paneWidth() >= 740 ? 3 : 2;
        for (int first = 0; first < layouts.length; first += count) {
            LinearLayout cards = row();
            cards.setBaselineAligned(false);
            for (int i = first; i < Math.min(first + count, layouts.length); i++) {
                LinearLayout choice = layoutCard(layouts[i], layouts[i] == chosenLayout, wide, pinned);
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
                if (i > first) cell.leftMargin = dp(10);
                cards.addView(choice, cell);
            }
            LinearLayout.LayoutParams rowBounds = new LinearLayout.LayoutParams(-1, wide ? 0 : -2, wide ? 1 : 0);
            rowBounds.topMargin = dp(10);
            launcher.addView(cards, rowBounds);
        }
        if (!wide) addSection(appearanceOverview());

        defaultHome = button("", true, this::requestHome);
        Button preview = button("Full-screen preview", false, () -> open(DashboardPreviewActivity.intent(this, chosenLayout, currentTheme(), chosenBackground, chosenClockFace, chosenFavouriteTiles, chosenPanelHeadings)));
        Button apps = button("Choose Home shortcuts", false, () -> DashboardApps.showPicker(this, this::refreshAppearanceSelection));
        LinearLayout actions = row();
        tile(actions, defaultHome); tile(actions, preview); tile(actions, apps);
        LinearLayout footer = column();
        footer.setPadding(dp(8), dp(8), dp(8), dp(8));
        footer.setBackgroundColor(colors.surface);
        footer.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams pageBounds = (FrameLayout.LayoutParams) sectionView.getLayoutParams();
        pageBounds.bottomMargin = dp(80);
        sectionView.setLayoutParams(pageBounds);
        workspace.addView(footer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        footer.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            FrameLayout.LayoutParams current = (FrameLayout.LayoutParams) sectionView.getLayoutParams();
            int height = bottom - top;
            if (height > 0 && current.bottomMargin != height) {
                current.bottomMargin = height;
                sectionView.setLayoutParams(current);
            }
        });
        refreshHomeStatus();
    }

    private void fitAppearanceColumns(LinearLayout columns) {
        Runnable fit = () -> {
            if (sectionView.getHeight() <= 0 || content.getChildCount() == 0) return;
            LinearLayout.LayoutParams bounds = (LinearLayout.LayoutParams) columns.getLayoutParams();
            int available = sectionView.getHeight() - content.getPaddingTop() - content.getPaddingBottom()
                    - content.getChildAt(0).getMeasuredHeight() - bounds.topMargin;
            if (available > 0 && bounds.height != available) {
                bounds.height = available;
                columns.setLayoutParams(bounds);
            }
        };
        sectionView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> fit.run());
        sectionView.post(fit);
    }

    private LinearLayout appearanceOverview() {
        LinearLayout overview = column();
        View colours = appearanceEntry("App colours", currentTheme(), shape(palette(this, currentTheme()).accent, 14), this::showColourPicker);
        colours.setId(R.id.appearance_colours);
        View background = appearanceEntry("Background", HomeBackground.name(currentTheme(), chosenBackground),
                HomeBackground.drawable(this, currentTheme(), chosenBackground, true), this::showBackgroundPicker);
        background.setId(R.id.appearance_background);
        View clock = appearanceEntry("Clock & tiles", DashboardStyle.faceName(chosenClockFace) + " · " + chosenFavouriteTiles + " tiles",
                getDrawable(android.R.drawable.ic_menu_recent_history), this::showClockOptions);
        clock.setId(R.id.appearance_clock);
        Drawable sidebarIcon = getDrawable(R.drawable.ic_sidebar).mutate();
        sidebarIcon.setTint(colors.accent);
        View sidebar = appearanceEntry("Sidebar", NavigationStyle.icons(prefs()) ? "Icons" : "Words",
                sidebarIcon, this::showNavigationStyle);
        for (View entry : new View[] {colours, background, clock, sidebar}) {
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, 0, 1);
            if (entry != colours) bounds.topMargin = dp(12);
            overview.addView(entry, bounds);
        }
        overview.setMinimumHeight(dp(420));
        return overview;
    }

    private void showNavigationStyle() {
        PanelDialog dialog = new PanelDialog("Navigation sidebar", 760,
                "Choose words or icons for the Manager sidebar. Changes save immediately. Hold an icon to see its name.");
        LinearLayout options = row();
        for (String style : new String[]{"words", "icons"}) {
            boolean selected = NavigationStyle.icons(prefs()) == "icons".equals(style);
            Button choice = button(("icons".equals(style) ? "Icons" : "Words") + (selected ? "  ✓" : ""), false, () -> {
                prefs().edit().putString(NavigationStyle.KEY, style).apply();
                dialog.dismiss();
                render();
            });
            choice.setSelected(selected);
            styleButton(choice, colors, false, false, selected);
            tile(options, choice);
        }
        dialog.body.addView(options);
        dialog.action("Close", false, dialog::dismiss);
        dialog.show();
    }

    private LinearLayout appearanceEntry(String title, String value, Drawable image, Runnable action) {
        LinearLayout entry = row();
        entry.setPadding(dp(16), dp(12), dp(12), dp(12));
        entry.setMinimumHeight(dp(96));
        entry.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30888888), shape(colors.surface, 14), null));
        entry.setFocusable(true);
        entry.setContentDescription(title + ", " + value);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(image); icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        entry.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout labels = column(); labels.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(managerText(title, 18, colors.ink, 600));
        TextView detail = managerText(value, 14, colors.muted, 450); detail.setPadding(0, dp(6), 0, 0); labels.addView(detail);
        entry.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = managerText("›", 28, colors.accent, 450);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        entry.addView(arrow);
        entry.setOnClickListener(view -> action.run());
        return entry;
    }

    @Override String currentBackground() {
        return "Appearance".equals(section) && chosenBackground != null ? chosenBackground : super.currentBackground();
    }

    @Override String currentClockFace() { return "Appearance".equals(section) && chosenClockFace != null ? chosenClockFace : super.currentClockFace(); }
    @Override int currentFavouriteTiles() { return "Appearance".equals(section) && chosenFavouriteTiles != 0 ? chosenFavouriteTiles : super.currentFavouriteTiles(); }
    @Override boolean currentPanelHeadings() { return "Appearance".equals(section) && chosenPanelHeadings != null ? chosenPanelHeadings : super.currentPanelHeadings(); }

    private void showColourPicker() {
        PanelDialog dialog = new PanelDialog("App colours", 860,
                "Choose a colour for AVC Manager and your AVC Home screen. The change takes effect immediately.");
        for (int first = 0; first < THEMES.length; first += 2) {
            LinearLayout row = row();
            for (int i = first; i < Math.min(first + 2, THEMES.length); i++) {
                String theme = THEMES[i]; boolean selected = theme.equals(currentTheme()); Palette palette = palette(this, theme);
                String label = theme + (selected ? "  ✓" : "");
                Button option = button(label, false, () -> {
                    DashboardLayout.applyColour(prefs(), theme);
                    dialog.dismiss();
                    refreshAppearanceSelection();
                });
                android.text.SpannableString caption = new android.text.SpannableString(label + "\n" + palette.description);
                int subtitle = label.length() + 1;
                caption.setSpan(new android.text.style.RelativeSizeSpan(0.82f), subtitle, caption.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                caption.setSpan(new android.text.style.ForegroundColorSpan(palette.muted), subtitle, caption.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                option.setText(caption);
                option.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                GradientDrawable swatch = shape(palette.accent, 14);
                swatch.setBounds(0, 0, dp(24), dp(24));
                option.setCompoundDrawablesRelative(swatch, null, null, null);
                option.setCompoundDrawablePadding(dp(12));
                option.setMinHeight(dp(88));
                styleButton(option, palette, false, false, selected);
                option.setSelected(selected);
                option.setContentDescription(theme + ", " + palette.description + (selected ? ", selected theme" : ""));
                tile(row, option);
            }
            dialog.body.addView(row);
        }
        dialog.action("Close", false, dialog::dismiss); dialog.show();
    }

    private LinearLayout galleryTile(String label, View preview, boolean selected, Runnable action) {
        LinearLayout tile = column();
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        tile.setBackground(choiceSurface(selected));
        tile.setFocusable(true); tile.setSelected(selected);
        tile.setContentDescription(label + (selected ? ", selected" : ""));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(preview, new LinearLayout.LayoutParams(-1, dp(80)));
        TextView caption = managerText(label + (selected ? "  ✓" : ""), 15, colors.ink, 550);
        caption.setSingleLine(true); caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setGravity(Gravity.CENTER); caption.setPadding(0, dp(6), 0, 0);
        tile.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        tile.setOnClickListener(view -> action.run());
        return tile;
    }

    private Drawable choiceSurface(boolean selected) {
        GradientDrawable background = shape(selected ? colors.selection : colors.surface, 14);
        if (selected) background.setStroke(dp(2), colors.accent);
        GradientDrawable focus = shape(colors.selection, 14); focus.setStroke(dp(2), colors.accent);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focus); states.addState(new int[0], background);
        return new RippleDrawable(ColorStateList.valueOf(0x30888888), states, null);
    }

    private void showBackgroundPicker() {
        PanelDialog dialog = new PanelDialog("Background", 1060,
                "Choose a locally stored background, original theme artwork or your own downloaded photo. Apply to Home saves the selection. External photo websites may require their own licence or credit.");
        int columns = paneWidth() >= 740 ? 3 : 2;
        for (int first = 0; first < HomeBackground.STYLES.length; first += columns) {
            LinearLayout row = row(); row.setBaselineAligned(false);
            for (int i = first; i < Math.min(first + columns, HomeBackground.STYLES.length); i++) {
                String style = HomeBackground.STYLES[i];
                boolean selected = style.equals(chosenBackground) || ("custom".equals(style) && chosenBackground.startsWith("custom:"));
                View thumbnail;
                if ("custom".equals(style) && !selected) {
                    ImageView choose = new ImageView(this);
                    choose.setImageResource(android.R.drawable.ic_menu_gallery);
                    choose.setImageTintList(ColorStateList.valueOf(colors.accent));
                    choose.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    choose.setPadding(dp(20), dp(12), dp(20), dp(12));
                    choose.setBackground(shape(colors.selection, 10));
                    thumbnail = choose;
                } else {
                    thumbnail = new View(this);
                    thumbnail.setBackground(HomeBackground.drawable(this, currentTheme(), "custom".equals(style) && selected ? chosenBackground : style, true));
                }
                String label = HomeBackground.name(currentTheme(), style);
                LinearLayout choice = galleryTile(label, thumbnail, selected, () -> {
                    dialog.dismiss();
                    if ("custom".equals(style)) {
                        Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try { startActivityForResult(choose, BACKGROUND_PHOTO); }
                        catch (RuntimeException unavailable) { Toast.makeText(this, "Photo picker is unavailable on this unit.", Toast.LENGTH_LONG).show(); }
                    } else { chosenBackground = style; refreshAppearanceSelection(); }
                });
                LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(0, -1, 1);
                bounds.setMargins(dp(4), dp(4), dp(4), dp(4)); row.addView(choice, bounds);
            }
            dialog.body.addView(row);
        }
        dialog.action("Image credits", false, this::showBackgroundCredits);
        dialog.action("Close", false, dialog::dismiss); dialog.show();
    }

    private void showBackgroundCredits() {
        PanelDialog credits = new PanelDialog("Image credits", 800);
        credits.message("Contour and Aurora glow are original AVC Manager artwork. The three space photographs are credited to NASA and supplied from NASA’s official desktop wallpaper collection. No NASA endorsement is implied.");
        credits.message("NASA: nasa.gov/international-space-station/desktop-and-mobile-wallpapers/\nUsage: nasa.gov/nasa-brand-center/images-and-media/");
        credits.message("Original Pixabay photographs and illustrations were published before 9 January 2019 under CC0.\nMist Forest — Arcaion, 2816314\nMint Flow — BarbaraALane, 1438671\nBlue Ridges — 1985027\nBlue Trails — Humusak, 545049\nViolet Lake — travis_blessing, 3633084\nPlum Swirl — kalhh, 1073756\nAmber Ridges — freenaturestock, 768308\nCopper Trails — tommyvideo, 1771361");
        credits.action("Close", false, credits::dismiss); credits.show();
    }

    private void showClockOptions() {
        final String originalFace = chosenClockFace; final int originalTiles = chosenFavouriteTiles;
        final boolean originalHeadings = chosenPanelHeadings;
        PanelDialog dialog = new PanelDialog("Clock & tiles", 1040,
                "Clock faces and tile count apply to Clock & Favourites. Four tiles use two columns; six tiles use three. Empty tiles let you add shortcuts. Panel headings apply to all Home layouts. Select Done, then Apply to Home to save these choices.");
        LinearLayout faces = row(); faces.setBaselineAligned(false);
        java.util.List<LinearLayout> choices = new java.util.ArrayList<>();
        for (int i = 0; i < DashboardStyle.FACES.length; i++) {
            String face = DashboardStyle.FACES[i]; String name = DashboardStyle.FACE_NAMES[i];
            ClockFaceView preview = new ClockFaceView(this, face);
            LinearLayout choice = galleryTile(name, preview, face.equals(chosenClockFace), () -> {});
            choice.setOnClickListener(view -> {
                chosenClockFace = face;
                for (int j = 0; j < choices.size(); j++) {
                    LinearLayout cell = choices.get(j); boolean selected = DashboardStyle.FACES[j].equals(face);
                    cell.setSelected(selected); cell.setBackground(choiceSurface(selected));
                    ((TextView)cell.getChildAt(1)).setText(DashboardStyle.FACE_NAMES[j] + (selected ? "  ✓" : ""));
                    cell.setContentDescription(DashboardStyle.FACE_NAMES[j] + (selected ? ", selected" : ""));
                }
            });
            choices.add(choice);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1); cell.setMargins(dp(4), dp(4), dp(4), dp(4)); faces.addView(choice, cell);
        }
        dialog.body.addView(faces);
        LinearLayout count = row();
        Button four = button("Four tiles · 2 × 2", false, () -> {});
        Button six = button("Six tiles · 3 × 2", false, () -> {});
        Runnable sync = () -> { styleButton(four, colors, false, false, chosenFavouriteTiles == 4); styleButton(six, colors, false, false, chosenFavouriteTiles == 6); four.setSelected(chosenFavouriteTiles == 4); six.setSelected(chosenFavouriteTiles == 6); four.setText("Four tiles · 2 × 2" + (chosenFavouriteTiles == 4 ? "  ✓" : "")); six.setText("Six tiles · 3 × 2" + (chosenFavouriteTiles == 6 ? "  ✓" : "")); };
        four.setOnClickListener(view -> { chosenFavouriteTiles = 4; sync.run(); });
        six.setOnClickListener(view -> { chosenFavouriteTiles = 6; sync.run(); });
        sync.run(); tile(count, four); tile(count, six); dialog.body.addView(count);
        View heading = helpHeading("Panel headings", 17,
                "Show or hide card titles such as Local time, Favourites, Navigation and Media across all Home layouts. Clock timezone info, app names and weather readings remain available. Done keeps the draft; Apply to Home saves it.");
        heading.setPadding(0, dp(8), 0, 0); dialog.body.addView(heading);
        LinearLayout headingOptions = row();
        Button show = button("Show headings", false, () -> {}), hide = button("Hide headings", false, () -> {});
        Runnable syncHeadings = () -> {
            styleButton(show, colors, false, false, chosenPanelHeadings); show.setSelected(chosenPanelHeadings);
            styleButton(hide, colors, false, false, !chosenPanelHeadings); hide.setSelected(!chosenPanelHeadings);
            show.setText("Show headings" + (chosenPanelHeadings ? "  ✓" : ""));
            hide.setText("Hide headings" + (!chosenPanelHeadings ? "  ✓" : ""));
        };
        show.setOnClickListener(view -> { chosenPanelHeadings = true; syncHeadings.run(); });
        hide.setOnClickListener(view -> { chosenPanelHeadings = false; syncHeadings.run(); });
        syncHeadings.run(); tile(headingOptions, show); tile(headingOptions, hide); dialog.body.addView(headingOptions);
        dialog.setOnCancelListener(ignored -> { chosenClockFace = originalFace; chosenFavouriteTiles = originalTiles; chosenPanelHeadings = originalHeadings; });
        dialog.action("Cancel", false, () -> { chosenClockFace = originalFace; chosenFavouriteTiles = originalTiles; chosenPanelHeadings = originalHeadings; dialog.dismiss(); });
        dialog.action("Done", true, () -> { dialog.dismiss(); refreshAppearanceSelection(); }); dialog.show();
    }

    private static final class ThemeCheck extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ThemeCheck(int color) {
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float size = bounds.width();
            float x = bounds.left;
            float y = bounds.top;
            paint.setStrokeWidth(size / 7);
            canvas.drawLine(x + size * .18f, y + size * .51f, x + size * .42f, y + size * .75f, paint);
            canvas.drawLine(x + size * .42f, y + size * .75f, x + size * .86f, y + size * .25f, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override @SuppressWarnings("deprecation") public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private void refreshAppearanceSelection() {
        int position = sectionView.getScrollY();
        render();
        View updated = sectionView;
        updated.post(() -> updated.scrollTo(0, position));
    }

    private LinearLayout layoutCard(DashboardLayout layout, boolean selected, boolean fillRow, java.util.List<DashboardApps.Choice> pinned) {
        LinearLayout result = card();
        GradientDrawable background = shape(selected ? colors.selection : colors.surface, 12);
        if (selected) background.setStroke(dp(2), colors.accent);
        result.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40888888), background, null));
        result.setSelected(selected);
        result.setFocusable(true);
        result.setPadding(dp(9), dp(8), dp(9), dp(5));
        result.addView(new LayoutThumbnail(this, layout, pinned),
                new LinearLayout.LayoutParams(-1, fillRow ? 0 : dp(102), fillRow ? 1 : 0));
        LinearLayout name = row();
        name.setBaselineAligned(false);
        name.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = text(layout.title, 14, colors.ink, 650);
        caption.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        caption.setMaxLines(2);
        caption.setIncludeFontPadding(false);
        name.setMinimumHeight(dp(48));
        name.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        name.addView(infoButton(layout.title, layout.description), new LinearLayout.LayoutParams(dp(48), dp(48)));
        result.addView(name);
        result.setContentDescription(layout.title + (selected ? ", selected" : ""));
        result.setOnClickListener(view -> {
            chosenLayout = layout;
            refreshAppearanceSelection();
        });
        return result;
    }

    private void refreshHomeStatus() {
        ResolveInfo home = currentHome();
        boolean active = home != null && getPackageName().equals(home.activityInfo.packageName);
        DashboardLayout current = DashboardLayout.selected(prefs());
        selectedLayoutHeading.setVisibility(active && current == chosenLayout ? View.GONE : View.VISIBLE);
        homeStatus.setText(active ? "On Home · " + current.title
                : "Current Home · " + (home == null ? "Choose a launcher" : homeLabel(home)));
        homeSetupHint.setVisibility(active ? View.GONE : View.VISIBLE);
        boolean unchanged = current == chosenLayout
                && super.currentBackground().equals(chosenBackground)
                && super.currentClockFace().equals(chosenClockFace) && super.currentFavouriteTiles() == chosenFavouriteTiles
                && super.currentPanelHeadings() == chosenPanelHeadings;
        defaultHome.setText(active && unchanged ? "Open Home" : "Apply to Home");
        if (!unchanged) homeStatus.append(" · Changes ready to apply");
    }

    private void requestHome() {
        DashboardLayout layout = chosenLayout;
        if (dashboardIsHome()) {
            layout.save(prefs(), currentTheme(), chosenBackground, chosenClockFace, chosenFavouriteTiles, chosenPanelHeadings);
            refreshAppearanceSelection();
            open(homeIntent());
            return;
        }
        PanelDialog setup = new PanelDialog("Set up AVC Dashboard", 680);
        TextView selection = managerText(layout.title, 18, colors.ink, 650);
        selection.setPadding(dp(4), 0, dp(4), dp(8));
        setup.body.addView(selection);
        setup.message("To open this layout when you press Home, select AVC Dashboard on the next screen, then tap Set as default.");
        setup.action("Back", false, setup::dismiss);
        setup.action("Continue to Android", true, () -> {
            setup.dismiss();
            requestHomeRole(layout);
        });
        setup.show();
    }

    private void requestHomeRole(DashboardLayout layout) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, getPackageName() + ".DashboardHome"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        if (!layout.stageForHome(prefs(), currentTheme(), chosenBackground, chosenClockFace, chosenFavouriteTiles, chosenPanelHeadings)) {
            Toast.makeText(this, "Could not save the selected layout. Try again.", Toast.LENGTH_LONG).show();
            return;
        }
        RoleManager manager = getSystemService(RoleManager.class);
        if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            try {
                startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_HOME), HOME_CONSENT);
                return;
            } catch (RuntimeException unavailable) {
                // Some FYT releases expose Home selection only through Settings.
            }
        }
        openHomeSettings();
    }

    private String homeLabel(ResolveInfo home) {
        String name = home.activityInfo.packageName;
        if ("com.android.launcher4".equals(name)) return "Original TEYES Home";
        if ("com.android.launcher6".equals(name)) return "TEYES CC3N Home";
        if ("com.android.launcher8".equals(name)) return "TEYES CC3 Home";
        return home.loadLabel(getPackageManager()).toString();
    }

    private void showOtherHomeApps() {
        PanelDialog dialog = new PanelDialog("Other Home apps", 760,
                "AVC Dashboard uses the layouts and colours in Appearance. Android Home settings can switch to any of these separate launchers.");
        for (ResolveInfo home : getPackageManager().queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)) {
            String name = home.activityInfo.packageName;
            if (getPackageName().equals(name) || "com.android.settings.FallbackHome".equals(home.activityInfo.name)) continue;
            String label = homeLabel(home);
            String systemName = home.loadLabel(getPackageManager()).toString();
            dialog.message(label.equals(systemName) ? label : label + "  ·  Android label: " + systemName);
        }
        dialog.action("Close", false, dialog::dismiss);
        dialog.action("Open Android Home settings", true, () -> {
            dialog.dismiss();
            openHomeSettings();
        });
        dialog.show();
    }

    private void openHomeSettings() {
        try {
            startActivityForResult(new Intent(Settings.ACTION_HOME_SETTINGS), HOME_CONSENT);
        } catch (ActivityNotFoundException unavailable) {
            DashboardLayout.finishHomeSelection(prefs(), false);
            Toast.makeText(this, "Home settings are unavailable on this unit.", Toast.LENGTH_LONG).show();
        }
    }

    private void voice() {
        LinearLayout voiceContent = column();
        LinearLayout assistant = card();
        assistantStatus = voiceCardTitle(assistant, "Digital assistant",
                "This is the assistant Android opens for its assistant shortcut.");
        Button settings = button("Choose default assistant", false, this::openAssistantSettings);
        stylePanelAction(settings);
        assistant.addView(settings);
        Intent googleAssistant = getPackageManager().getLaunchIntentForPackage(GOOGLE_ASSISTANT);
        if (googleAssistant != null) {
            Button openGoogle = button("Open Google Assistant", false, () -> open(googleAssistant));
            stylePanelAction(openGoogle);
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
            space.topMargin = dp(8);
            assistant.addView(openGoogle, space);
        }

        LinearLayout recognition = card();
        recognizerStatus = voiceCardTitle(recognition, "Speech recognition",
                "This provider turns speech into text. Test voice input checks which words it recognises.");
        Button test = button("Test voice input", true, this::testSpeech);
        test.setEnabled(speechIntent().resolveActivity(getPackageManager()) != null);
        recognition.addView(test);
        speechResult = managerText("", 14, colors.muted, 450);
        speechResult.setPadding(0, dp(8), 0, 0);
        speechResult.setVisibility(View.GONE);
        speechResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        recognition.addView(speechResult);
        if (paneWidth() >= 790) {
            LinearLayout panels = row();
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, 1);
            left.rightMargin = dp(12);
            panels.addView(assistant, left);
            panels.addView(recognition, new LinearLayout.LayoutParams(0, -1, 1));
            voiceContent.addView(panels);
        } else {
            voiceContent.addView(assistant);
            addSection(voiceContent, recognition);
        }

        LinearLayout hanna = card();
        detail = voiceCardTitle(hanna, "Hanna",
                "Hanna handles TEYES voice commands. Changing its state requires Wi-Fi ADB.");
        hannaAction = button("", false, () -> {
            Boolean enabled = hannaEnabled();
            if (enabled != null) changeVoice(!enabled);
        });
        stylePanelAction(hannaAction);
        hanna.addView(hannaAction);
        addSection(voiceContent, hanna);
        addControlsDetail("Voice", voiceContent, paneWidth());
    }

    private TextView voiceCardTitle(LinearLayout panel, String heading, String help) {
        panel.addView(helpHeading(heading, 19, help));
        TextView status = managerText("", 15, colors.muted, 500);
        status.setPadding(0, dp(7), 0, dp(11));
        panel.addView(status);
        return status;
    }

    private void openAssistantSettings() {
        Intent settings = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
        if (settings.resolveActivity(getPackageManager()) == null) {
            settings.setAction(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
        }
        open(settings);
    }

    private Intent speechIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    private void testSpeech() {
        PanelDialog dialog = new PanelDialog("Test voice input", 690);
        dialog.message("Your speech provider may send test audio to its servers. Say a short phrase such as “test voice input”.");
        dialog.action("Cancel", false, dialog::dismiss);
        dialog.action("Start test", true, () -> {
            dialog.dismiss();
            try {
                startActivityForResult(speechIntent(), SPEECH_TEST);
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                if (speechResult != null) showText(speechResult, "Speech recognition is unavailable.");
            }
        });
        dialog.show();
    }

    private String voiceProvider(String key, String absent, String googleLabel) {
        String configured = Settings.Secure.getString(getContentResolver(), key);
        ComponentName provider = configured == null ? null : ComponentName.unflattenFromString(configured);
        if (provider == null) return absent;
        String packageName = provider.getPackageName();
        try {
            android.content.pm.ApplicationInfo app = getPackageManager().getApplicationInfo(packageName, 0);
            if (!app.enabled) return absent;
            return GOOGLE.equals(packageName) ? googleLabel : app.loadLabel(getPackageManager()).toString();
        } catch (PackageManager.NameNotFoundException unavailable) {
            return absent;
        }
    }

    private void changeVoice(boolean enable) {
        if (voiceBusy) return;
        voiceBusy = true;
        voiceMessage = "Checking local ADB…";
        refreshStatus();
        new Thread(() -> {
            String result;
            try (LocalAdb adb = new LocalAdb(fingerprint -> handler.post(() -> {
                voiceMessage = "Approve AVC Manager in the Android prompt. Fingerprint " + fingerprint;
                refreshStatus();
            }))) {
                result = adb.setEnabled(enable) ? "Hanna is enabled." : "Hanna is disabled.";
            } catch (IOException error) {
                result = error instanceof java.net.ConnectException
                        ? "Enable Wi-Fi ADB in Wireless, then try again." : error.getMessage();
            } catch (Exception error) {
                result = "Could not authorize AVC Manager with Android.";
            }
            String message = result;
            handler.post(() -> {
                voiceBusy = false;
                voiceMessage = message == null ? "Hanna action could not be completed."
                        : message.equals("Hanna is enabled.") || message.equals("Hanna is disabled.") ? "" : message;
                refreshStatus();
            });
        }, "cc3-hanna-control").start();
    }

    private void confirmAdb() {
        if (adbBusy) return;
        if (wifiAddress(wifiLink()) == null) {
            message("Connect the CC3 to Wi-Fi first.");
            return;
        }
        if (Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
            message("Turn on USB debugging in Developer options first.");
            return;
        }
        PanelDialog dialog = new PanelDialog("Enable Wi-Fi debugging for 15 minutes?", 680);
        dialog.message("Legacy debugging is not encrypted. Approve only trusted computers. Access stops after 15 minutes or when this Wi-Fi connection changes. Android force-stop can prevent the timer from running; turn debugging off when finished.");
        dialog.action("Cancel", false, dialog::dismiss);
        dialog.action("Enable", true, () -> {
            dialog.dismiss();
            setAdb(true);
        });
        dialog.show();
    }

    private void setAdb(boolean enable) {
        startForegroundService(new Intent(this,WirelessDebugService.class)
                .setAction(enable?WirelessDebugService.ENABLE:WirelessDebugService.STOP));
        handler.postDelayed(this::refreshStatus,100);
    }

    private void message(String text) {
        adbMessage = text;
        refreshStatus();
    }

    // Android10 compatibility: this scoped platform path intentionally retains legacy APIs.
    @SuppressWarnings("deprecation")
    private LinkProperties wifiLink() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(net);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            LinkProperties properties = cm.getLinkProperties(net);
            if (properties != null) return properties;
        }
        return null;
    }

    private LinkAddress wifiAddress(LinkProperties properties) {
        if (properties == null) return null;
        for (LinkAddress address : properties.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) return address;
        }
        return null;
    }

    private String wifiDetails() {
        LinkProperties properties = wifiLink();
        if (properties == null) return "Wi-Fi disconnected";
        LinkAddress address = wifiAddress(properties);
        if (address == null) return "No IPv4 address";
        String router = "Unavailable";
        for (RouteInfo route : properties.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (route.isDefaultRoute() && gateway instanceof Inet4Address && !gateway.isAnyLocalAddress()) {
                router = gateway.getHostAddress();
                break;
            }
        }
        int prefix = address.getPrefixLength();
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        String subnet = (mask >>> 24) + "." + ((mask >>> 16) & 255) + "." + ((mask >>> 8) & 255) + "." + (mask & 255);
        return "IP address  " + address.getAddress().getHostAddress()
                + "\nSubnet mask  " + subnet + "\nDefault router  " + router;
    }

    private boolean checkPort() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", FytAdbController.ADB_PORT), 400);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private void refreshStatus() {
        if (trafficPage != null) trafficPage.refresh();
        if (mobilePage != null) mobilePage.refresh();
        if (detail == null) return;
        if ("Controls".equals(section) && "Voice".equals(controlDetail)) {
            assistantStatus.setText(voiceProvider("assistant", "No default assistant selected", "Google"));
            recognizerStatus.setText(voiceProvider("voice_recognition_service", "No speech provider configured",
                    "Google speech recognition"));
            Boolean enabled = hannaEnabled();
            hannaAction.setVisibility(enabled == null ? View.GONE : View.VISIBLE);
            if (enabled != null) {
                hannaAction.setText(enabled ? "Disable Hanna" : "Enable Hanna");
                hannaAction.setEnabled(!voiceBusy);
            }
            detail.setText(enabled == null ? "Hanna is unavailable on this unit."
                    : !voiceMessage.isEmpty() ? voiceMessage : enabled ? "Hanna is enabled." : "Hanna is disabled.");
            return;
        }
        if (!"Access".equals(section)) return;
        showAdbStatus();
        refreshMobile();
        if (adbBusy || probing) return;
        probing = true;
        new Thread(() -> {
            boolean listening = checkPort();
            handler.post(() -> {
                probing = false;
                if (isDestroyed() || adbBusy) return;
                portOpen = listening;
                portChecked = true;
                if ("Access".equals(section) && detail != null) showAdbStatus();
            });
        }, "cc3-adb-status").start();
    }

    private Boolean hannaEnabled() {
        try {
            return getPackageManager().getApplicationInfo("com.teyes.hanna", 0).enabled;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private void showAdbStatus() {
        boolean debugging = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        WirelessDebugService.State lease=WirelessDebugService.state();
        boolean managed=lease.active && lease.remaining>0;
        boolean active=debugging && portChecked && portOpen;
        boolean stopping = lease.busy && lease.message.startsWith("Turning off");
        String state = lease.busy ? stopping ? "Stopping…" : "Starting…"
                : managed ? "On ✓" : !debugging ? "Unavailable" : !portChecked ? "Checking…" : active ? "On" : "Off";
        debugBadge.setText(state);
        boolean on = managed || active;
        GradientDrawable badge = shape(on ? colors.selection : colors.background, 12);
        badge.setStroke(dp(1), on ? colors.accent : colors.outline);
        debugBadge.setBackground(badge);
        debugBadge.setTextColor(on ? colors.accent : colors.muted);
        detail.setText(lease.busy ? stopping ? "Turning off computer access…" : "Starting computer access…"
                : managed ? "Wi-Fi debugging session active"
                : !debugging ? "USB debugging is off" : !portChecked ? "Checking Wi-Fi debugging…"
                : active ? "Wi-Fi debugging has no session timer" : "Wi-Fi debugging is off");
        long seconds = Math.max(0, (lease.remaining + 999) / 1000);
        debugTime.setText(managed ? String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                : lease.busy || active ? "—" : "15 min");
        debugTime.setTextColor(managed ? colors.accent : colors.ink);
        debugTimeLabel.setText(managed ? "Remaining" : lease.busy ? "Please wait" : active ? "No timer" : "Session limit");
        enableAdbButton.setSelected(managed);
        enableAdbButton.setText(managed?"Session active  ✓":"Enable for 15 minutes");
        styleButton(enableAdbButton,colors,!managed,false,managed);
        enableAdbButton.setEnabled(!lease.busy && portChecked && !managed);
        stopAdbButton.setEnabled(!lease.busy && (managed || active || WirelessDebugService.hasLease(this)));
        styleButton(stopAdbButton, colors, on && !lease.busy, false, false);
        String network=wifiDetails();
        String[] lines=network.split("\n");
        boolean connected=lines.length==3;
        String address=connected?lines[0].replace("IP address  ",""):"—";
        if(!address.contentEquals(accessAddress.getText()))accessAddress.setText(address);
        wifiState.setText(connected?"Connected to local network":network);
        wifiState.setTextColor(connected?colors.accent:colors.muted);
        accessMask.setText(connected?lines[1].replace("Subnet mask  ",""):"—");
        accessRouter.setText(connected?lines[2].replace("Default router  ",""):"—");
        String message = lease.message.isEmpty() ? adbMessage : lease.message;
        if ("Wi-Fi debugging is on".equals(message) || "Wi-Fi debugging is off".equals(message) || lease.busy) message = "";
        showText(accessMessage, message);
    }

}
