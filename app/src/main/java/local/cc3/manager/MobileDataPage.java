package local.cc3.manager;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Intent;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.ConnectException;

final class MobileDataPage {
    enum Page {
        NETWORK("Mobile network"), USAGE("Data usage"), APN("Access point names");
        final String title;
        Page(String title) { this.title = title; }
    }

    private final MainActivity activity;
    private final Page page;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TelephonyManager phone;
    private final TextView facts;
    private final TextView message;
    private final Button action;
    private boolean busy;
    private boolean closed;

    MobileDataPage(MainActivity activity, Page page, Runnable back) {
        this.activity = activity;
        this.page = page;
        phone = activity.getSystemService(TelephonyManager.class);
        LinearLayout heading = activity.row();
        String help;
        switch (page) {
            case USAGE:
                help = "Load an estimate of the last 24 hours and 7 days. Android’s records can differ from your carrier’s totals.";
                break;
            case APN:
                help = "Android manages which access point is active. Use the system editor to change it.";
                break;
            default:
                help = "Mobile data uses your SIM for internet access. Apps can still use Wi-Fi when mobile data is off.";
        }
        View title = activity.helpHeading(page.title, 24, help);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(activity.button("Back to Wireless", false, back));

        LinearLayout panel = activity.card();
        facts = activity.managerText("", 17, activity.colors.ink, 600);
        facts.setLineSpacing(activity.dp(6), 1f);
        panel.addView(facts);
        message = activity.managerText("", 15, activity.colors.muted, 450);
        message.setPadding(0, activity.dp(10), 0, 0);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setVisibility(View.GONE);

        if (page == Page.NETWORK) {
            action = activity.button("Turn mobile data off", true, this::confirmDataChange);
        } else if (page == Page.USAGE) {
            facts.setText("Mobile data usage");
            action = activity.button("Load data usage", true, this::loadUsage);
        } else {
            action = activity.button("Open Android APN settings", false,
                    () -> activity.open(new Intent(Settings.ACTION_APN_SETTINGS)));
        }
        LinearLayout.LayoutParams actionBounds = new LinearLayout.LayoutParams(-2, -2);
        actionBounds.topMargin = activity.dp(14);
        panel.addView(action, actionBounds);
        panel.addView(message);
        activity.addCenteredSection(heading, panel, 700);
        refresh();
    }

    void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
    }

    void refresh() {
        if (page == Page.USAGE || closed) return;
        boolean hasSim = phone != null && phone.hasIccCard();
        String carrier = hasSim ? phone.getNetworkOperatorName() : "";
        String carrierLabel = carrier == null || carrier.isEmpty()
                ? (hasSim ? "SIM detected" : "No SIM detected") : carrier;
        if (page == Page.APN) {
            String apn = connectedApn();
            facts.setText(carrierLabel + "\n" + (apn == null ? "Active APN unavailable" : "Active APN  ·  " + apn));
        } else {
            Boolean enabled = dataEnabled();
            facts.setText(carrierLabel + "\n" + (enabled == null ? "Mobile data status unavailable"
                    : enabled ? "Mobile data is on" : "Mobile data is off"));
            action.setEnabled(!busy && enabled != null);
            if (enabled != null) action.setText(enabled ? "Turn mobile data off" : "Turn mobile data on");
        }
    }

    private Boolean dataEnabled() {
        if (phone == null || !phone.hasIccCard()) return null;
        try {
            return phone.isDataEnabled();
        } catch (SecurityException unavailable) {
            return null;
        }
    }

    // Android10 compatibility: this scoped platform path intentionally retains legacy APIs.
    @SuppressWarnings("deprecation")
    private String connectedApn() {
        ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue;
            android.net.NetworkInfo info = manager.getNetworkInfo(network);
            String extra = info == null ? null : info.getExtraInfo();
            if (extra != null && extra.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,99}")) return extra;
        }
        return null;
    }

    private void confirmDataChange() {
        Boolean enabled = dataEnabled();
        if (busy || enabled == null) return;
        if (!enabled) {
            changeData(true);
            return;
        }
        Screen.PanelDialog dialog = activity.new PanelDialog("Turn mobile data off?", 630);
        dialog.message("Apps will use Wi-Fi for internet access when it’s available.");
        dialog.action("Cancel", false, dialog::dismiss);
        dialog.action("Turn off", true, () -> {
            dialog.dismiss();
            changeData(false);
        });
        dialog.show();
    }

    private LocalAdb openLocalAdb() throws Exception {
        return new LocalAdb(fingerprint -> main.post(() -> {
            if (!closed) activity.showText(message, "Approve AVC Manager in the Android prompt. Fingerprint " + fingerprint);
        }));
    }

    private void changeData(boolean enable) {
        begin("Updating mobile data…");
        new Thread(() -> {
            try (LocalAdb adb = openLocalAdb()) {
                adb.setMobileData(enable);
                for (int attempt = 0; attempt < 6; attempt++) {
                    if (Boolean.valueOf(enable).equals(dataEnabled())) {
                        finish("");
                        return;
                    }
                    Thread.sleep(300);
                }
                finish("The head unit did not confirm the mobile data change.");
            } catch (Exception error) {
                finish(errorMessage(error));
            }
        }, "cc3-mobile-data").start();
    }

    private void loadUsage() {
        if (busy) return;
        AppOpsManager appOps = activity.getSystemService(AppOpsManager.class);
        if (appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
                activity.getPackageName()) != AppOpsManager.MODE_ALLOWED) {
            Screen.PanelDialog dialog = activity.new PanelDialog("Allow data usage access?", 660);
            dialog.message("Enable usage access for AVC Manager in Android Settings, then return here.");
            dialog.action("Cancel", false, dialog::dismiss);
            dialog.action("Open usage access", true, () -> {
                dialog.dismiss();
                Intent access = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        Uri.fromParts("package", activity.getPackageName(), null));
                if (access.resolveActivity(activity.getPackageManager()) == null) access.setData(null);
                activity.open(access);
            });
            dialog.show();
            return;
        }
        begin("Reading mobile usage…");
        new Thread(() -> {
            try {
                NetworkStatsManager stats = activity.getSystemService(NetworkStatsManager.class);
                long now = System.currentTimeMillis();
                long day = 24L * 60 * 60 * 1000;
                long daily = usage(stats, now - day, now);
                long weekly = usage(stats, now - 7 * day, now);
                main.post(() -> {
                    if (closed) return;
                    facts.setText("Last 24 hours  ·  " + Formatter.formatFileSize(activity, daily)
                            + "\nLast 7 days  ·  " + Formatter.formatFileSize(activity, weekly));
                    activity.showText(message, "");
                    busy = false;
                    action.setEnabled(true);
                    action.setText("Refresh data usage");
                });
            } catch (Exception error) {
                finish("Android did not return mobile usage history.");
            }
        }, "cc3-mobile-usage").start();
    }

    // Android10 compatibility: this scoped platform path intentionally retains legacy APIs.
    @SuppressWarnings("deprecation")
    private static long usage(NetworkStatsManager manager, long start, long end) throws Exception {
        NetworkStats.Bucket bucket = manager.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end);
        if (bucket == null) throw new IllegalStateException("Mobile usage unavailable");
        return bucket.getRxBytes() + bucket.getTxBytes();
    }

    private String errorMessage(Exception error) {
        if (error instanceof ConnectException) return "Enable Wi-Fi ADB in Wireless, then try again.";
        return error.getMessage() == null ? "Android did not return mobile data information." : error.getMessage();
    }

    private void begin(String text) {
        busy = true;
        action.setEnabled(false);
        activity.showText(message, text);
    }

    private void finish(String text) {
        main.post(() -> {
            if (closed) return;
            busy = false;
            activity.showText(message, text);
            if (page == Page.USAGE) action.setEnabled(true);
            else refresh();
        });
    }
}
