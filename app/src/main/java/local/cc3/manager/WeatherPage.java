package local.cc3.manager;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Native weather settings, with separate consent before device-location forecasts. */
final class WeatherPage implements java.io.Closeable {
    private static final String HELP = "Show a seven-day forecast below the clock in Clock & Favourites. Weather is off until enabled. Choose a saved city without location permission, or explicitly allow device location. Forecasts and place searches use Open-Meteo. No background weather location service, IP location lookup or analytics.";
    private final MainActivity activity;
    private final WeatherRepository search;
    private WeatherWidget preview;
    private PlaceDialog placeDialog;
    private boolean visible, closed;
    private long lastRefresh;

    WeatherPage(MainActivity activity) { this.activity = activity; search = new WeatherRepository(activity, null); }

    View build() {
        if (preview != null) preview.close();
        LinearLayout root = activity.column();
        root.setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12));
        root.addView(activity.controlsHeader("Weather", HELP));
        LinearLayout columns = activity.row(); columns.setGravity(Gravity.TOP); columns.setBaselineAligned(false);
        LinearLayout left = activity.column(), right = activity.column();
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -1, 1.05f);
        first.rightMargin = activity.dp(12); columns.addView(left, first);
        columns.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, 0, 1); body.topMargin = activity.dp(8);
        root.addView(columns, body);
        boolean enabled = WeatherRepository.enabled(activity.prefs());
        LinearLayout enabledCard = card();
        enabledCard.addView(activity.helpHeading("Clock forecast", 18, "On displays weather below the clock. Off cancels weather updates and device-location requests. Your settings and saved city remain local for next time."));
        LinearLayout toggles = activity.row();
        option(toggles, "On", enabled, () -> setEnabled(true));
        option(toggles, "Off", !enabled, () -> setEnabled(false));
        enabledCard.addView(toggles); left.addView(enabledCard);
        LinearLayout location = card();
        location.addView(activity.helpHeading("Forecast location", 18, "Saved place uses the selected city or postcode without device-location permission. Automatic uses a foreground device fix only after separate consent. If unavailable, it uses your saved place and labels the fallback. With no saved place it asks you to choose one. No IP location lookup."));
        LinearLayout modes = activity.row();
        option(modes, "Automatic", WeatherRepository.automatic(activity.prefs()), this::automatic);
        option(modes, "Saved place", !WeatherRepository.automatic(activity.prefs()), () -> {
            if (!enabled()) return;
            if (WeatherRepository.savedPlace(activity.prefs()) == null) choosePlace();
            else { WeatherRepository.setAutomatic(activity.prefs(), false); rebuild(); }
        });
        location.addView(modes);
        WeatherRepository.Place place = WeatherRepository.savedPlace(activity.prefs());
        TextView saved = activity.text(place == null ? "No saved place" : place.label(), 15, activity.colors.ink, 550);
        saved.setMaxLines(2);
        saved.setEllipsize(android.text.TextUtils.TruncateAt.END);
        saved.setContentDescription(place == null ? "No saved place" : place.label());
        saved.setPadding(activity.dp(4), activity.dp(12), activity.dp(4), activity.dp(8)); location.addView(saved);
        TextView mode = activity.text(WeatherRepository.automatic(activity.prefs()) ? "Device location with saved-place fallback" : "Use a selected city or postcode", 13, activity.colors.muted, 450);
        mode.setPadding(activity.dp(4), 0, activity.dp(4), activity.dp(8)); location.addView(mode);
        location.addView(new View(activity), new LinearLayout.LayoutParams(1, 0, 1));
        Button choose = activity.button(place == null ? "Choose a place" : "Change saved place", false, this::choosePlace);
        choose.setEnabled(enabled); location.addView(choose, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams locationBounds = new LinearLayout.LayoutParams(-1, 0, 1);
        locationBounds.topMargin = activity.dp(10); locationBounds.bottomMargin = activity.dp(10);
        left.addView(location, locationBounds);
        LinearLayout units = card();
        units.addView(activity.helpHeading("Temperature", 18, "Daily high and low temperatures. Unit changes are local and reuse the existing forecast."));
        LinearLayout unitOptions = activity.row();
        option(unitOptions, "Celsius · °C", !WeatherRepository.fahrenheit(activity.prefs()), () -> setUnits(false));
        option(unitOptions, "Fahrenheit · °F", WeatherRepository.fahrenheit(activity.prefs()), () -> setUnits(true));
        units.addView(unitOptions); left.addView(units);
        LinearLayout forecast = card();
        forecast.addView(activity.helpHeading("Seven-day preview", 18, "Forecast conditions and daily high/low temperatures. A saved forecast is marked with its age; data older than 24 hours is hidden. Missing values remain unavailable. This is a forecast, not an observation of current conditions."));
        preview = new WeatherWidget(activity); preview.setOnClickListener(view -> choosePlace());
        forecast.addView(preview, new LinearLayout.LayoutParams(-1, activity.dp(166)));
        Button refresh = activity.button("Refresh forecast", false, () -> {
            if (!enabled()) return;
            long now = SystemClock.elapsedRealtime(); if (now - lastRefresh < 15_000) return;
            lastRefresh = now; preview.refresh();
        });
        refresh.setEnabled(enabled); forecast.addView(refresh, new LinearLayout.LayoutParams(-1, -2)); right.addView(forecast);
        LinearLayout credits = card();
        credits.addView(activity.helpHeading("Data & privacy", 18, "Forecast: Open-Meteo, CC BY 4.0. Places: GeoNames via Open-Meteo. This private non-commercial app uses the free API. Searches and forecast coordinates go to Open-Meteo. Automatic coordinates are rounded to about one kilometre first. Provider request logs may contain coordinates and be kept for up to 90 days. Your separate weather consent is required before device-location requests.\n\nForecast: https://open-meteo.com\nPrivacy: https://open-meteo.com/en/terms\nLicence: https://creativecommons.org/licenses/by/4.0/\nPlaces: https://www.geonames.org"));
        credits.addView(link("Open-Meteo", "https://open-meteo.com/"), new LinearLayout.LayoutParams(-1, -2)); gap(credits, 6);
        credits.addView(link("Provider privacy", "https://open-meteo.com/en/terms"), new LinearLayout.LayoutParams(-1, -2)); gap(credits, 6);
        credits.addView(link("Data credits", "https://open-meteo.com/en/licence"), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams creditBounds = new LinearLayout.LayoutParams(-1, 0, 1); creditBounds.topMargin = activity.dp(10); right.addView(credits, creditBounds);
        return root;
    }
    private void option(LinearLayout row, String label, boolean selected, Runnable action) {
        Button button = activity.button(label + (selected ? "  ✓" : ""), false, action);
        button.setSelected(selected); activity.styleButton(button, activity.colors, false, false, selected); activity.tile(row, button);
    }
    private LinearLayout card() {
        LinearLayout card = activity.column(); card.setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10));
        card.setBackground(activity.shape(activity.colors.surface, 18)); return card;
    }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(activity), new LinearLayout.LayoutParams(1, activity.dp(height))); }
    private Button link(String label, String url) { return activity.button(label, false, () -> activity.open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))); }
    private boolean enabled() {
        if (WeatherRepository.enabled(activity.prefs())) return true;
        Toast.makeText(activity, "Turn weather on to configure a forecast location.", Toast.LENGTH_SHORT).show(); return false;
    }
    private void setEnabled(boolean on) { WeatherRepository.setEnabled(activity.prefs(), on); rebuild(); }
    private void setUnits(boolean fahrenheit) { activity.prefs().edit().putBoolean(WeatherRepository.FAHRENHEIT, fahrenheit).apply(); rebuild(); }
    private void automatic() {
        if (!enabled()) return;
        if (!WeatherRepository.consented(activity.prefs())) {
            Screen.PanelDialog consent = activity.new PanelDialog("Automatic weather location", 740);
            consent.message("Open-Meteo will receive your device location rounded to about one kilometre to request forecasts. Its request logs may be kept for up to 90 days. AVC Manager uses a foreground location fix and has no background weather tracking.");
            consent.action("Choose a place", false, () -> { consent.dismiss(); choosePlace(); });
            consent.action("Cancel", false, consent::dismiss);
            consent.action("Use location", true, () -> {
                consent.dismiss(); activity.prefs().edit().putBoolean(WeatherRepository.AUTO_CONSENT, true).putBoolean(WeatherRepository.AUTOMATIC, true).apply(); useAutomatic();
            }); consent.show();
        } else { WeatherRepository.setAutomatic(activity.prefs(), true); useAutomatic(); }
    }
    private void useAutomatic() { if (!activity.hasWeatherLocationPermission()) activity.requestWeatherLocationPermission(); else rebuild(); }
    private void choosePlace() {
        if (!enabled() || closed) return;
        if (placeDialog != null) placeDialog.dismiss(); placeDialog = new PlaceDialog(); placeDialog.show();
    }
    private void rebuild() {
        if (closed || !visible || activity.isFinishing() || activity.isDestroyed()) return;
        activity.showWorkspace(build()); onResume();
    }
    void onResume() { if (closed) return; visible = true; if (preview != null) preview.start(); }
    void onLeave() {
        visible = false; if (preview != null) preview.stop(); search.stop();
        if (placeDialog != null) { placeDialog.dismiss(); placeDialog = null; }
    }
    void onPermissionsResult() { if (!closed) { visible = true; rebuild(); } }
    @Override public void close() { if (closed) return; onLeave(); closed = true; if (preview != null) preview.close(); search.close(); }

    private final class PlaceDialog extends Dialog {
        private final LinearLayout panel, results;
        private final EditText query;
        private final TextView status;
        private final Button submit;
        private final Rect frame = new Rect();
        private int lastWidth, lastHeight;
        private final ViewTreeObserver.OnGlobalLayoutListener resize = this::resize;
        // Android10 compatibility: this scoped platform path intentionally retains legacy APIs.
        @SuppressWarnings("deprecation")
        PlaceDialog() {
            super(activity); requestWindowFeature(Window.FEATURE_NO_TITLE); setCanceledOnTouchOutside(true);
            panel = activity.column(); panel.setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(14)); panel.setBackground(activity.shape(activity.colors.background, 22));
            LinearLayout heading = activity.row();
            TextView title = activity.text("Choose a forecast location", 22, activity.colors.ink, 650); title.setAccessibilityHeading(true);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); heading.addView(activity.button("Close", false, this::dismiss)); panel.addView(heading);
            LinearLayout searchRow = activity.row();
            query = new EditText(activity); query.setSingleLine(true); query.setTextSize(16); query.setTextColor(activity.colors.ink); query.setHintTextColor(activity.colors.muted);
            query.setHint("City, town or postcode"); query.setContentDescription("Search forecast locations"); query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            query.setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10)); query.setMinHeight(activity.dp(56)); query.setBackground(activity.shape(activity.colors.surface, 12));
            searchRow.addView(query, new LinearLayout.LayoutParams(0, -2, 1)); submit = activity.button("Search", true, this::searchPlaces);
            LinearLayout.LayoutParams submitBounds = new LinearLayout.LayoutParams(-2, -2); submitBounds.leftMargin = activity.dp(8); searchRow.addView(submit, submitBounds);
            LinearLayout.LayoutParams searchBounds = new LinearLayout.LayoutParams(-1, -2); searchBounds.topMargin = activity.dp(10); panel.addView(searchRow, searchBounds);
            status = activity.text("Searches are sent to Open-Meteo.", 13, activity.colors.muted, 450); status.setPadding(activity.dp(2), activity.dp(9), 0, activity.dp(8)); panel.addView(status);
            ScrollView scroll = new ScrollView(activity); results = activity.column(); scroll.addView(results); panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            query.setOnEditorActionListener((view, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { searchPlaces(); return true; } return false; });
            setContentView(panel); getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT)); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            setOnDismissListener(dialog -> {
                search.stop();
                InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class); if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
                getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(resize);
            });
        }
        @Override public void show() { super.show(); getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(resize); resize(); }
        private void resize() {
            if (!isShowing()) return;
            getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
            if (frame.width() <= 0 || frame.height() <= 0) activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
            int width = Math.min(activity.dp(1020), Math.max(activity.dp(300), frame.width() - activity.dp(28)));
            int height = Math.min(activity.dp(600), Math.max(activity.dp(230), frame.height() - activity.dp(28)));
            if (width != lastWidth || height != lastHeight) { lastWidth = width; lastHeight = height; getWindow().setLayout(width, height); }
        }
        private void searchPlaces() {
            String text = query.getText().toString(); if (text.trim().length() < 2) { status.setText("Enter at least two letters or a postcode."); return; }
            InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class); if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
            submit.setEnabled(false); status.setText("Searching…"); results.removeAllViews();
            search.search(text, (places, error) -> {
                if (!isShowing() || closed || !visible) return;
                submit.setEnabled(true); status.setText(error.isEmpty() ? "Select the matching place and country." : error);
                for (WeatherRepository.Place place : places) {
                    Button choice = activity.button(place.label(), false, () -> { WeatherRepository.savePlace(activity.prefs(), place); dismiss(); rebuild(); });
                    choice.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                    LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2); bounds.topMargin = activity.dp(6); results.addView(choice, bounds);
                }
            });
        }
    }
}
