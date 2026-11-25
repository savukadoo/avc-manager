package local.cc3.manager;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Arrays;
import java.util.function.IntConsumer;

final class SpeakerPosition {
    static final int POSITION_UPDATE = 8;
    static final int MODE_UPDATE = 55;
    static final int GAIN_UPDATE = 53;
    private static final int SET_POSITION = 3;
    private static final int SET_EASY_MODE = 55;
    private static final int CENTER = 8;
    private static final int MAX = 16;
    private static final String ENABLE = "Enable Easy Sound Field to use these controls. This replaces manual speaker levels.";

    interface Control {
        boolean send(int command, int... values);
    }

    static final class SoundField {
        private static final int[] CHANNELS = {69632, 73728, 135168, 139264};
        private final int[] speakerGains = new int[CHANNELS.length];
        int balance;
        int fader;
        int easyMode;

        SoundField() {
            clear();
        }

        void clear() {
            balance = -1;
            fader = -1;
            easyMode = -1;
            Arrays.fill(speakerGains, -1);
        }

        boolean available() {
            return valid(balance) && valid(fader) && easyMode >= 0;
        }

        boolean update(int field, int[] values) {
            switch (field) {
                case POSITION_UPDATE:
                    if (values.length < 2 || !valid(values[0]) || !valid(values[1])) return false;
                    balance = values[0];
                    fader = values[1];
                    return true;
                case MODE_UPDATE:
                    if (values.length == 0 || (values[0] != 0 && values[0] != 1)) return false;
                    easyMode = values[0];
                    return true;
                case GAIN_UPDATE:
                    if (values.length < 2 || values[1] < 0 || values[1] > 40) return false;
                    for (int i = 0; i < CHANNELS.length; i++) {
                        if (CHANNELS[i] == values[0]) {
                            speakerGains[i] = values[1];
                            return true;
                        }
                    }
                    return false;
                default: return false;
            }
        }

        boolean applied(int x, int y) {
            // C32107S Easy Sound Field maps the two axes to four attenuation values.
            return easyMode == 1 && balance == x && fader == y
                    && speakerGains[0] == gain(x, y) && speakerGains[1] == gain(MAX - x, y)
                    && speakerGains[2] == gain(x, MAX - y) && speakerGains[3] == gain(MAX - x, MAX - y);
        }

        private static int gain(int x, int y) {
            return 40 - (Math.max(x, y) * 5 + 1) / 2;
        }
    }

    private final MainActivity activity;
    private final Handler main;
    private final Control control;
    private final SoundField field;
    private final Screen.PanelDialog dialog;
    private final TactileSlider balanceSlider;
    private final TactileSlider faderSlider;
    private final Button enable;
    private final Button center;
    private final TextView status;
    private final PositionPad pad;
    private final Runnable send = this::sendPosition;
    private final Runnable confirm = this::checkPosition;
    private int balance;
    private int fader;
    private int sentBalance;
    private int sentFader;
    private int displayedMode;
    private boolean enabling;
    private boolean awaitingPosition;
    private boolean available = true;

    SpeakerPosition(MainActivity activity, Handler main, SoundField field, Runnable dismissed, Control control) {
        this.activity = activity;
        this.main = main;
        this.field = field;
        this.control = control;
        balance = sentBalance = field.balance;
        fader = sentFader = field.fader;
        displayedMode = field.easyMode;
        dialog = activity.new PanelDialog("Speaker position", 870,
                "Drag the sliders or the dot to shift the sound. Changes apply live.");

        LinearLayout layout = activity.row();
        LinearLayout controls = activity.column();
        balanceSlider = slider(controls, "Balance", balance, "Left", "Right", value -> move(value, this.fader));
        faderSlider = slider(controls, "Fader", fader, "Front", "Rear", value -> move(this.balance, value));
        layout.addView(controls, new LinearLayout.LayoutParams(0, -2, 1));

        pad = new PositionPad();
        pad.setBackground(activity.shape(activity.colors.surface, 16));
        LinearLayout.LayoutParams canvas = new LinearLayout.LayoutParams(activity.dp(235), activity.dp(184));
        canvas.leftMargin = activity.dp(16);
        if (activity.getResources().getConfiguration().screenWidthDp >= 700) layout.addView(pad, canvas);
        dialog.body.addView(layout);
        status = activity.managerText("", 14, activity.colors.muted, 450);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(activity.dp(4), activity.dp(10), 0, 0);
        dialog.body.addView(status);
        enable = dialog.action("Enable Easy mode", false, this::enableEasyMode);
        center = dialog.action("Center", false, () -> move(CENTER, CENTER));
        dialog.action("Done", true, this::close);
        updateControls();
        dialog.setOnDismissListener(window -> {
            flush();
            main.removeCallbacks(confirm);
            dismissed.run();
        });
    }

    private static boolean valid(int value) {
        return value >= 0 && value <= MAX;
    }

    void show() {
        dialog.show();
    }

    void close() {
        flush();
        main.removeCallbacks(confirm);
        dialog.dismiss();
    }

    void unavailable() {
        available = false;
        main.removeCallbacks(send);
        main.removeCallbacks(confirm);
        updateControls();
    }

    void readback() {
        if (!available) return;
        if (field.easyMode != displayedMode) {
            displayedMode = field.easyMode;
            if (displayedMode == 0) {
                main.removeCallbacks(send);
                main.removeCallbacks(confirm);
                enabling = false;
                awaitingPosition = false;
            } else if (enabling) {
                enabling = false;
                awaitingPosition = true;
                sentBalance = field.balance;
                sentFader = field.fader;
            }
            updateControls();
        }
        if (awaitingPosition && field.applied(sentBalance, sentFader)) {
            awaitingPosition = false;
            main.removeCallbacks(confirm);
            updateControls();
        }
        if (enabling || awaitingPosition || main.hasCallbacks(send)) return;
        sentBalance = field.balance;
        sentFader = field.fader;
        display(sentBalance, sentFader);
    }

    private boolean canMove() {
        return available && field.easyMode == 1;
    }

    private void updateControls() {
        boolean active = canMove();
        balanceSlider.setEnabled(active);
        faderSlider.setEnabled(active);
        center.setEnabled(active);
        pad.setEnabled(active);
        enable.setVisibility(available && field.easyMode == 0 ? View.VISIBLE : View.GONE);
        enable.setEnabled(!enabling);
        if (!available) activity.showText(status, "Speaker position is unavailable.");
        else if (enabling) activity.showText(status, "Enabling Easy Sound Field…");
        else if (active) activity.showText(status, awaitingPosition ? "Waiting for the DSP speaker levels…" : "");
        else activity.showText(status, ENABLE);
    }

    private void enableEasyMode() {
        if (!available || enabling || field.easyMode != 0) return;
        if (!control.send(SET_EASY_MODE, 1)) {
            unavailable();
            return;
        }
        enabling = true;
        updateControls();
        main.postDelayed(confirm, 1200);
    }

    private TactileSlider slider(LinearLayout container, String name, int start, String low, String high,
            IntConsumer change) {
        TextView label = activity.managerText("", 16, activity.colors.ink, 600);
        container.addView(label);
        TactileSlider slider = new TactileSlider(activity, true);
        slider.setMax(MAX);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                String level = progress == CENTER ? "Centered" : (progress < CENTER ? low : high)
                        + " " + Math.abs(progress - CENTER);
                label.setText(name + "  ·  " + level);
                bar.setContentDescription(name + ", " + level);
                if (fromUser) change.accept(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { flush(); }
        });
        slider.setProgress(start == 0 ? 1 : start);
        slider.setProgress(start);
        container.addView(slider, new LinearLayout.LayoutParams(-1, activity.dp(58)));
        return slider;
    }

    private void move(int balance, int fader) {
        if (!canMove() || (balance == this.balance && fader == this.fader)) return;
        display(balance, fader);
        if (!main.hasCallbacks(send)) main.postDelayed(send, 75);
    }

    private void display(int balance, int fader) {
        this.balance = balance;
        this.fader = fader;
        balanceSlider.setProgress(balance);
        faderSlider.setProgress(fader);
        pad.invalidate();
    }

    private void flush() {
        if (!main.hasCallbacks(send)) return;
        main.removeCallbacks(send);
        sendPosition();
    }

    private void sendPosition() {
        if (!canMove() || (balance == sentBalance && fader == sentFader)) return;
        if (!control.send(SET_POSITION, balance, fader)) {
            unavailable();
            return;
        }
        sentBalance = balance;
        sentFader = fader;
        awaitingPosition = true;
        updateControls();
        main.removeCallbacks(confirm);
        main.postDelayed(confirm, 1200);
    }

    private void checkPosition() {
        if (enabling) {
            enabling = false;
            updateControls();
            activity.showText(status, "Easy Sound Field did not turn on. Enabling it replaces manual speaker levels.");
            return;
        }
        if (!awaitingPosition) return;
        awaitingPosition = false;
        if (!main.hasCallbacks(send)) {
            sentBalance = field.balance;
            sentFader = field.fader;
            display(sentBalance, sentFader);
        }
        activity.showText(status, "The DSP did not confirm the speaker levels.");
    }

    private final class PositionPad extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF area = new RectF();

        PositionPad() {
            super(activity);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            area.set(activity.dp(37), activity.dp(38), width - activity.dp(37), height - activity.dp(38));
        }

        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(activity.colors.outline);
            paint.setStrokeWidth(activity.dp(1));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(area, activity.dp(10), activity.dp(10), paint);
            canvas.drawLine(area.centerX(), area.top, area.centerX(), area.bottom, paint);
            canvas.drawLine(area.left, area.centerY(), area.right, area.centerY(), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(activity.colors.muted);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
            canvas.drawText("Front", area.centerX(), area.top - activity.dp(13), paint);
            canvas.drawText("Rear", area.centerX(), area.bottom + activity.dp(24), paint);
            float x = area.left + area.width() * balance / MAX;
            float y = area.top + area.height() * fader / MAX;
            paint.setColor(activity.colors.accent);
            canvas.drawCircle(x, y, activity.dp(10), paint);
            paint.setColor(activity.colors.onAccent);
            canvas.drawCircle(x, y, activity.dp(3), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!canMove()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                move(axis(event.getX(), area.left, area.width()), axis(event.getY(), area.top, area.height()));
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
                flush();
            }
            if (action == MotionEvent.ACTION_UP) performClick();
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        private int axis(float value, float start, float length) {
            return Math.max(0, Math.min(MAX, Math.round((value - start) * MAX / length)));
        }
    }
}
