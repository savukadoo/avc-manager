package local.cc3.manager;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

final class ControlsPage implements FytModule.Listener {
    private final MainActivity activity;
    private static final int MODE = 4;
    private static final int CURRENT = 31;
    private static final int DAY = 32;
    private static final int NIGHT = 33;
    private final FytModule fyt;
    private final TextView status;
    private final Preset day;
    private final Preset night;
    private final Button fallback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int current = -1;
    private int mode = -1;
    private final Runnable timeout = this::disconnected;

    ControlsPage(MainActivity activity) {
        this.activity = activity;
        fyt = new FytModule(activity, "com.syu.ms.main", new int[] {MODE, CURRENT, DAY, NIGHT}, this);
        LinearLayout display = activity.card();
        display.addView(activity.helpHeading("Screen brightness", 19,
                "Day and night brightness are stored separately. The active mode is shown above the sliders."));
        status = activity.managerText("Reading the TEYES display settings…", 15, activity.colors.muted, 500);
        status.setPadding(0, activity.dp(7), 0, 0);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        display.addView(status);
        day = new Preset(display, "Day brightness", DAY);
        night = new Preset(display, "Night brightness", NIGHT);
        fallback = activity.button("Open TEYES display settings", false,
                () -> activity.open(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        fallback.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams actionBounds = new LinearLayout.LayoutParams(-2, -2);
        actionBounds.topMargin = activity.dp(12);
        display.addView(fallback, actionBounds);
        activity.addControlsDetail("Display settings", display, 840);
    }

    void start() {
        ui.removeCallbacks(timeout);
        ui.postDelayed(timeout, 2800);
        fyt.start();
    }

    void stop() {
        fyt.stop();
        ui.removeCallbacksAndMessages(null);
    }

    @Override public void disconnected() {
        status.setText("Open Display settings to adjust brightness on this unit.");
        fallback.setVisibility(android.view.View.VISIBLE);
        day.slider.setEnabled(false);
        night.slider.setEnabled(false);
    }

    @Override public void update(int field, int[] values) {
        int value = values[0];
        if (field == MODE) mode = value;
        else if (value >= 0 && value <= 100) {
            if (field == CURRENT) current = value;
            if (field == DAY) day.received(value);
            if (field == NIGHT) night.received(value);
        }
        if (day.value >= 0 && night.value >= 0 && day.slider.isEnabled() && night.slider.isEnabled()) {
            ui.removeCallbacks(timeout);
            fallback.setVisibility(android.view.View.GONE);
        }
        String level = current >= 0 ? "Current brightness " + current + "%" : "Day and night presets";
        if (mode >= 0) level += mode == 0 ? "  ·  Day mode" : "  ·  Night mode";
        status.setText(level);
    }

    private final class Preset implements SeekBar.OnSeekBarChangeListener {
        final String label;
        final int field;
        final SeekBar slider;
        final TextView title;
        int value = -1;
        boolean dragging;

        Preset(LinearLayout container, String label, int field) {
            this.label = label;
            this.field = field;
            title = activity.managerText(label, 16, activity.colors.ink, 600);
            title.setPadding(0, activity.dp(14), 0, 0);
            container.addView(title);
            slider = new TactileSlider(activity, false);
            slider.setMax(100);
            slider.setMin(0);
            slider.setContentDescription(label);
            slider.setEnabled(false);
            slider.setOnSeekBarChangeListener(this);
            container.addView(slider, new LinearLayout.LayoutParams(-1, activity.dp(56)));
        }

        void received(int readback) {
            value = readback;
            slider.setEnabled(true);
            if (!dragging) {
                slider.setProgress(readback);
                showLevel(readback);
            }
        }

        void showLevel(int level) {
            title.setText(label + "  ·  " + level + "%");
        }

        @Override public void onProgressChanged(SeekBar bar, int requested, boolean fromUser) {
            if (!fromUser) return;
            int level = Math.max(5, requested);
            showLevel(level);
            if (!dragging) submit(level);
        }

        @Override public void onStartTrackingTouch(SeekBar bar) {
            dragging = true;
        }

        @Override public void onStopTrackingTouch(SeekBar bar) {
            dragging = false;
            submit(Math.max(5, bar.getProgress()));
        }

        void submit(int level) {
            slider.setProgress(level);
            if (level != value && !fyt.send(field == DAY ? 11 : 12, level)) disconnected();
        }
    }

}
