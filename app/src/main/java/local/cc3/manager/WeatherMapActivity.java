package local.cc3.manager;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Optional foreground viewer of Windy's original embed, inside native AVC controls. */
public final class WeatherMapActivity extends Screen {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WeatherRepository resolver;
    private WeatherRepository.Place place;
    private WebView map;
    private FrameLayout viewport;
    private LinearLayout notice;
    private ProgressBar progress;
    private TextView location;
    private Button radar, forecast, recentre, reload;
    private boolean forecastMode, foreground, failed, resolving;
    private final Runnable timeout = () -> unavailable("Map is taking too long to load",
            "Check your internet connection, then try again.");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        forecastMode = state != null && state.getBoolean("forecast");
        prepareWindow();
        LinearLayout root = column();
        root.setPadding(dp(16), dp(10), dp(16), dp(12));
        root.setBackgroundColor(colors.background);
        LinearLayout top = row();
        LinearLayout title = column();
        LinearLayout name = row();
        TextView heading = text("Weather map", 20, colors.ink, 650); heading.setAccessibilityHeading(true);
        name.addView(heading);
        ImageButton info = infoButton("Windy maps", "");
        info.setContentDescription("Weather map information"); info.setTooltipText("Map details and privacy");
        info.setOnClickListener(view -> information());
        name.addView(info, new LinearLayout.LayoutParams(dp(48), dp(48))); title.addView(name);
        location = text("Weather location", 13, colors.muted, 500);
        location.setSingleLine(true); location.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.addView(location);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        radar = toolbar(top, "Radar", 116, () -> select(false));
        forecast = toolbar(top, "Forecast", 132, () -> select(true));
        recentre = toolbar(top, "Recentre", 128, this::loadMap);
        reload = toolbar(top, "Reload", 112, this::loadMap);
        toolbar(top, "Back", 100, this::finish);
        root.addView(top);
        viewport = new FrameLayout(this);
        viewport.setBackground(shape(colors.surface, 16));
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(-1, 0, 1);
        fill.topMargin = dp(8); root.addView(viewport, fill);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(colors.accent));
        setContentView(root);
        resolver = new WeatherRepository(this, value -> {
            if (!foreground || !resolving) return;
            if (!value.loading && value.forecast == null) {
                resolving = false; resolver.stop();
                showNotice("Choose a weather location", value.status, "Weather settings", this::settings);
            }
        }, (point, fallback) -> {
            if (!foreground) return;
            resolving = false; resolver.stop(); place = point;
            location.setText((fallback ? "Saved location · " : "") + point.label());
            location.setContentDescription("Map location: " + location.getText());
            loadMap();
        });
        updateControls();
    }

    private Button toolbar(LinearLayout row, String label, int width, Runnable action) {
        Button button = button(label, false, action);
        button.setPadding(dp(8), dp(9), dp(8), dp(9));
        button.setSingleLine(true);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(dp(width), dp(56));
        bounds.leftMargin = dp(8); row.addView(button, bounds); return button;
    }

    @Override protected void onResume() {
        super.onResume(); foreground = true;
        if (!WeatherRepository.enabled(prefs())) {
            showNotice("Weather is off", "Turn weather on and choose your location to open a map.", "Weather settings", this::settings);
        } else if (!prefs().getBoolean(WeatherMapPolicy.CONSENT, false)) intro();
        else resolve();
    }

    private void intro() {
        showNotice("Maps by Windy", "Opening the map shares your approximate weather location with Windy and uses an internet connection. "
                + "Windy's map and privacy policy apply. Your OBD readings are not provided to the map.", "Open maps", () -> {
            prefs().edit().putBoolean(WeatherMapPolicy.CONSENT, true).apply(); resolve();
        });
        Button details = button("Map information", false, this::information);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(dp(260), dp(56));
        bounds.topMargin = dp(10); notice.addView(details, bounds);
    }

    private void resolve() {
        if (!foreground) return;
        if (place != null) { loadMap(); return; }
        resolving = true;
        showNotice("Finding weather location", "Using your automatic location or the saved place in Weather settings.", null, null);
        resolver.start(); updateControls();
    }

    private void settings() { open(managerIntent(this, "Weather")); finish(); }

    private void information() {
        PanelDialog dialog = new PanelDialog("Windy maps", 840);
        dialog.message("Maps and map timeline: Windy.com. Radar and Forecast are shortcuts to Windy's layers; "
                + "use these buttons again to restore a layer after changing it inside the map. "
                + "Radar shows recent observed rain. Forecast shows modelled rainfall, not a three-hour radar prediction. "
                + "The map receives your weather location rounded to about one kilometre. Windy's timestamps and legend describe the data shown. "
                + "Recentre returns to that weather location; the map does not track your car. "
                + "Forecast maps require internet; cached imagery can be older than the current conditions.");
        dialog.option("Windy privacy policy", () -> open(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://account.windy.com/agreements/windy-privacy-policy"))));
        dialog.option("Ask before opening maps again", () -> {
            prefs().edit().remove(WeatherMapPolicy.CONSENT).apply(); resolver.stop(); resolving = false;
            dialog.dismiss(); destroyMap(); intro(); updateControls();
        });
        dialog.action("Close", false, dialog::dismiss); dialog.show();
    }

    private void select(boolean value) {
        forecastMode = value; updateControls();
        loadMap();
    }

    private void updateControls() {
        radar.setSelected(!forecastMode); forecast.setSelected(forecastMode);
        styleButton(radar, colors, false, false, !forecastMode);
        styleButton(forecast, colors, false, false, forecastMode);
        boolean ready = foreground && WeatherRepository.enabled(prefs())
                && prefs().getBoolean(WeatherMapPolicy.CONSENT, false) && place != null;
        radar.setEnabled(ready); forecast.setEnabled(ready);
        recentre.setEnabled(ready); reload.setEnabled(ready);
    }

    private void loadMap() {
        if (!foreground || place == null || !WeatherRepository.enabled(prefs())
                || !prefs().getBoolean(WeatherMapPolicy.CONSENT, false)) return;
        String url;
        try { url = WeatherMapPolicy.url(place.latitude, place.longitude, forecastMode, WeatherRepository.fahrenheit(prefs())); }
        catch (IllegalArgumentException invalid) {
            showNotice("Map location unavailable", "Choose a location between 85° north and 85° south.", "Weather settings", this::settings); return;
        }
        destroyMap(); failed = false;
        try { createMap(); }
        catch (RuntimeException unavailable) {
            destroyMap(); showNotice("Map viewer unavailable", "This device needs a working Android System WebView.", "Try again", this::loadMap); return;
        }
        viewport.addView(map, 0, new FrameLayout.LayoutParams(-1, -1));
        viewport.addView(progress, new FrameLayout.LayoutParams(-1, dp(4), Gravity.TOP));
        showNotice("Loading " + (forecastMode ? "forecast" : "radar"), "Connecting to Windy…", null, null);
        viewport.bringChildToFront(progress);
        progress.setVisibility(View.VISIBLE);
        handler.postDelayed(timeout, 30_000);
        map.loadUrl(url); updateControls();
    }

    // Size only the original provider controls. Their handlers, timeline, map scale and attribution
    // stay native to the embed. CSS applies to controls mounted later or swapped on a layer change.
    private static final String TOUCH_STYLE =
            ".embed-map #embed-zoom{transform:none!important;right:14px!important;top:46px!important}"
            + ".embed-map #embed-zoom .zoom-ctrl,.embed-map .play-pause{"
            + "box-sizing:border-box!important;width:60px!important;height:60px!important;"
            + "border:2px solid white!important;border-radius:16px!important;"
            + "color:white!important;background:#192839!important;box-shadow:0 2px 7px #0009!important;"
            + "text-align:center!important;line-height:1!important;}"
            + ".embed-map #embed-zoom .zoom-ctrl{font-size:0!important;line-height:0!important;margin-bottom:8px!important;position:relative}"
            + ".embed-map #embed-zoom .zoom-ctrl:before,.embed-map #embed-zoom .zoom-plus:after{"
            + "content:'';position:absolute;left:50%;top:50%;width:26px;height:4px;border-radius:2px;"
            + "background:#fff;transform:translate(-50%,-50%)}"
            + ".embed-map #embed-zoom .zoom-plus:after{width:4px;height:26px}"
            + ".embed-map .play-pause{font-size:28px!important;background:#edf5ff!important;color:#13273b!important;"
            + "display:grid!important;place-items:center!important;}"
            + ".embed-map .play-pause:before{position:static!important;line-height:1!important}"
            + ".embed-map #plugin-radar,.embed-map #plugin-satellite,.embed-map #plugin-progress-bar{margin-left:40px!important}"
            + ".embed-map #plugin-progress-bar #playpause{top:-45px!important;left:-27px!important}"
            + ".embed-map #plugin-radar .play-pause,"
            + ".embed-map #plugin-satellite .play-pause{top:-39px!important;left:-87px!important}"
            + ".embed-map .zoom-ctrl:active,.embed-map .play-pause:active{outline:3px solid #79dfff}";

    private static void sizeMapControls(WebView view, String url) {
        if (!WeatherMapPolicy.document(url) || !WeatherMapPolicy.document(view.getUrl())) return;
        String css = org.json.JSONObject.quote(TOUCH_STYLE);
        view.evaluateJavascript("(function(){if(!document.head||document.getElementById('avc-touch-size'))return;"
                + "var s=document.createElement('style');s.id='avc-touch-size';s.textContent=" + css
                + ";document.head.appendChild(s)})()", null);
    }

    @SuppressLint("SetJavaScriptEnabled") // Required by the original Windy embed; no native JavaScript bridge.
    private void createMap() {
        map = new WebView(this);
        map.setContentDescription("Windy " + (forecastMode ? "rain forecast" : "weather radar") + " map");
        WebSettings settings = map.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true); settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(map, false);
        map.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        map.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String address = request.getUrl().toString();
                if (WeatherMapPolicy.document(address)) return false;
                if (request.hasGesture() && WeatherMapPolicy.externalLink(address))
                    open(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                return true;
            }
            @Override public void onPageStarted(WebView view, String address, Bitmap icon) {
                if (view != map) return;
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String address) {
                if (view != map || failed) return;
                sizeMapControls(view, address);
                handler.removeCallbacks(timeout); progress.setVisibility(View.GONE);
                if (notice != null) notice.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == map && request.isForMainFrame()) unavailable("Map could not load", "Check your internet connection, then try again.");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (view == map && request.isForMainFrame()) unavailable("Windy is unavailable", "The map provider could not complete the request. Try again shortly.");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (view == map) unavailable("Secure connection unavailable", "Check the head unit's date, time and internet connection.");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == map) { destroyMap(); unavailable("Map viewer stopped", "Reload the map to try again."); }
                return true;
            }
        });
    }

    private void unavailable(String title, String detail) {
        if (!foreground) return;
        failed = true; handler.removeCallbacks(timeout); progress.setVisibility(View.GONE);
        if (map != null) map.stopLoading();
        showNotice(title, detail, "Try again", this::loadMap); updateControls();
    }

    private void showNotice(String title, String message, String action, Runnable run) {
        if (notice != null) viewport.removeView(notice);
        notice = column(); notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(32), dp(24), dp(32), dp(24)); notice.setBackgroundColor(colors.surface);
        TextView heading = text(title, 24, colors.ink, 650); heading.setGravity(Gravity.CENTER);
        heading.setAccessibilityHeading(true); notice.addView(heading);
        TextView explanation = text(message, 15, colors.muted, 450); explanation.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams words = new LinearLayout.LayoutParams(dp(Math.min(720,
                getResources().getConfiguration().screenWidthDp - 120)), -2);
        words.topMargin = dp(14); words.bottomMargin = dp(20); notice.addView(explanation, words);
        if (action != null) notice.addView(button(action, true, run), new LinearLayout.LayoutParams(dp(260), dp(56)));
        viewport.addView(notice, new FrameLayout.LayoutParams(-1, -1));
    }

    private void destroyMap() {
        handler.removeCallbacks(timeout);
        if (map != null) {
            WebView old = map; map = null;
            old.stopLoading(); old.onPause(); viewport.removeView(old);
            old.removeAllViews(); old.destroy();
        }
        if (progress != null) { viewport.removeView(progress); progress.setVisibility(View.GONE); }
    }

    @Override protected void onPause() {
        foreground = false; resolving = false; place = null; resolver.stop(); destroyMap();
        super.onPause();
    }
    @Override protected void onDestroy() {
        resolver.close(); destroyMap(); handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putBoolean("forecast", forecastMode);
    }
}
