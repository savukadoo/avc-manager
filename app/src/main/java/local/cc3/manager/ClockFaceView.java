package local.cc3.manager;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.util.TypedValue;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextClock;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Four local clock faces; no network, permissions or continuous animation. */
final class ClockFaceView extends FrameLayout {
    private final Screen screen;
    private final String face;
    private LinearLayout dateRow;
    private TextClock dateText;
    private TextClock analogueTime;
    private float dialRadius;
    private float dialCenterY;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };

    ClockFaceView(Screen screen, String face) {
        this(screen, face, false);
    }

    ClockFaceView(Screen screen, String face, boolean timezoneAction) {
        super(screen);
        this.screen = screen;
        this.face = DashboardStyle.validFace(face);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (timezoneAction) {
            addDateRow();
            if ("analogue".equals(this.face)) {
                analogueTime = new TextClock(screen);
                analogueTime.setFormat24Hour("HH:mm");
                analogueTime.setFormat12Hour("h:mm a");
                analogueTime.setSingleLine(true);
                analogueTime.setIncludeFontPadding(false);
                analogueTime.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
                analogueTime.setTextColor(screen.colors.ink);
                analogueTime.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(analogueTime, new LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
            }
        }
    }

    private void addDateRow() {
        dateRow = new LinearLayout(screen);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        dateRow.setBaselineAligned(false);
        dateText = new TextClock(screen);
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMyyyy");
        dateText.setFormat24Hour(pattern);
        dateText.setFormat12Hour("digital".equals(face) ? "a '·' " + pattern : pattern);
        dateText.setSingleLine(true);
        dateText.setIncludeFontPadding(false);
        dateText.setTypeface(Typeface.create(Typeface.DEFAULT, 450, false));
        dateText.setTextColor(screen.colors.muted);
        // The clock already announces time, date and zone together.
        dateText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dateRow.addView(dateText, new LinearLayout.LayoutParams(-2, -2));
        ImageButton info = screen.infoButton("Clock timezone", "");
        info.setContentDescription("Clock timezone");
        info.setTooltipText("View clock timezone");
        info.setOnClickListener(view -> showTimezone());
        dateRow.addView(info, new LinearLayout.LayoutParams(screen.dp(48), screen.dp(48)));
        addView(dateRow, new LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
    }

    private float clockSize(int width, int height) {
        return Math.min(sp(52), Math.min(width * .26f, height * .28f));
    }

    private float dateSize(int height) {
        return Math.min(sp(16), height * .075f);
    }

    private float dateBaseline(int w, int h) {
        float size = clockSize(w, h), small = dateSize(h);
        switch (face) {
            case "analogue": return h * .43f + Math.min(w * .37f, h * .31f) + small * 2.9f;
            case "split": return h / 2f + size * .55f + small * 2;
            case "stacked": return h / 2f + Math.min(size * 1.2f, h * .26f) * .95f + small * 1.8f;
            default: return h / 2f + size * .15f + small * 2.3f;
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (dateRow != null) {
            int maxWidth = Math.max(0, MeasureSpec.getSize(widthSpec) - screen.dp(56));
            float size = dateSize(MeasureSpec.getSize(heightSpec));
            if (analogueTime != null) {
                float readoutSize = Math.max(sp(13), size * 1.2f);
                if (analogueTime.getTextSize() != readoutSize) analogueTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, readoutSize);
                // In very short windows the dial itself still gives the time.
                analogueTime.setVisibility(MeasureSpec.getSize(heightSpec) < screen.dp(118) ? GONE : VISIBLE);
            }
            if (dateText.getMaxWidth() != maxWidth) dateText.setMaxWidth(maxWidth);
            if (dateText.getTextSize() != size) dateText.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (analogueTime != null) {
            layoutAnalogue();
        } else if (dateRow != null) {
            int baselineInRow = dateText.getTop() + dateText.getBaseline();
            int top = Math.round(dateBaseline(getWidth(), getHeight())) - baselineInRow;
            top = Math.max(0, Math.min(top, getHeight() - dateRow.getHeight()));
            dateRow.offsetTopAndBottom(top - dateRow.getTop());
        }
    }

    private void layoutAnalogue() {
        int height = getHeight();
        boolean showReadout = analogueTime.getVisibility() == VISIBLE;
        int dialGap = screen.dp(showReadout ? 16 : 8);
        int captionHeight = showReadout ? analogueTime.getMeasuredHeight() + screen.dp(10) : 0;
        int reserved = dialGap + captionHeight + dateRow.getHeight();
        int diameter = Math.max(0, Math.min(height - reserved - 2 * screen.dp(6),
                Math.round(Math.min(getWidth() * .74f, height * .62f))));
        int top = Math.max(0, (height - diameter - reserved) / 2);
        dialRadius = diameter / 2f;
        dialCenterY = top + dialRadius;
        int captionTop = top + diameter + dialGap;
        if (showReadout) analogueTime.offsetTopAndBottom(captionTop - analogueTime.getTop());
        dateRow.offsetTopAndBottom(captionTop + captionHeight - dateRow.getTop());
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); tick.run(); }
    @Override protected void onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow(); }

    private float sp(float size) { return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size, getResources().getDisplayMetrics()); }

    void showTimezone() {
        Calendar now = Calendar.getInstance();
        TimeZone zone = now.getTimeZone();
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        boolean daylight = zone.inDaylightTime(now.getTime());
        Screen.PanelDialog dialog = screen.new PanelDialog("Clock timezone", 660);
        dialog.body.addView(screen.text(zone.getID(), 22, screen.colors.ink, 650));
        dialog.message(zone.getDisplayName(daylight, TimeZone.LONG, locale));
        dialog.message(utcOffset(zone.getOffset(now.getTimeInMillis()))
                + (daylight ? " · Daylight saving time" : " · Standard time"));
        dialog.message("The clock’s date and time use the head unit’s system timezone.");
        dialog.action("Close", false, dialog::dismiss);
        dialog.show();
    }

    private static String utcOffset(int milliseconds) {
        int minutes = Math.abs(milliseconds / 60000);
        return String.format(Locale.ROOT, "UTC%s%02d:%02d", milliseconds < 0 ? "-" : "+",
                minutes / 60, minutes % 60);
    }

    private void label(Canvas canvas, String value, float x, float y, float size, int color, int weight, float maxWidth) {
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        paint.setTextSize(size);
        if (paint.measureText(value) > maxWidth) paint.setTextSize(size * maxWidth / paint.measureText(value));
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, x, y, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Calendar now = Calendar.getInstance();
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        boolean hours24 = DateFormat.is24HourFormat(getContext());
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (!hours24) hour = hour % 12 == 0 ? 12 : hour % 12;
        String hours = String.format(locale, hours24 ? "%02d" : "%d", hour);
        String minutes = String.format(locale, "%02d", now.get(Calendar.MINUTE));
        String time = hours + ":" + minutes;
        String date = new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMy"), locale).format(now.getTime());
        String period = hours24 ? "" : new SimpleDateFormat("a", locale).format(now.getTime());
        String spoken = time + " " + period + ", " + date + ", " + now.getTimeZone().getID()
                + ", " + utcOffset(now.getTimeZone().getOffset(now.getTimeInMillis()));
        if (!spoken.contentEquals(getContentDescription() == null ? "" : getContentDescription())) setContentDescription(spoken);
        float w = getWidth(), h = getHeight(), x = w / 2, y = h / 2;
        float font = clockSize(getWidth(), getHeight());
        float small = dateSize(getHeight());
        Screen.Palette c = screen.colors;
        if ("analogue".equals(face)) {
            float radius = analogueTime == null ? Math.min(w * .37f, h * .31f) : dialRadius;
            float cy = analogueTime == null ? h * .43f : dialCenterY;
            paint.setColor(c.selection); paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, cy, radius, paint);
            paint.setColor(c.accent); paint.setStrokeWidth(Math.max(1, radius * .018f)); paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(x, cy, radius, paint); paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI / 6;
                float inner = radius * (i % 3 == 0 ? .83f : .9f);
                paint.setColor(c.muted); paint.setStrokeWidth(Math.max(1, radius * .012f));
                canvas.drawLine(x + (float)Math.sin(angle) * inner, cy - (float)Math.cos(angle) * inner,
                        x + (float)Math.sin(angle) * radius * .95f, cy - (float)Math.cos(angle) * radius * .95f, paint);
            }
            double minute = now.get(Calendar.MINUTE) * Math.PI / 30;
            double hourAngle = (now.get(Calendar.HOUR) + now.get(Calendar.MINUTE) / 60d) * Math.PI / 6;
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(c.ink); paint.setStrokeWidth(radius * .065f);
            canvas.drawLine(x, cy, x + (float)Math.sin(hourAngle) * radius * .52f, cy - (float)Math.cos(hourAngle) * radius * .52f, paint);
            paint.setColor(c.accent); paint.setStrokeWidth(radius * .035f);
            canvas.drawLine(x, cy, x + (float)Math.sin(minute) * radius * .77f, cy - (float)Math.cos(minute) * radius * .77f, paint);
            canvas.drawCircle(x, cy, Math.max(3, radius * .05f), paint);
            if (analogueTime == null) {
                label(canvas, time + (period.isEmpty() ? "" : " " + period), x,
                        cy + radius + small * 1.5f, small * 1.2f, c.ink, 600, w - 20);
            }
        } else if ("split".equals(face)) {
            float gap = Math.max(8, w * .025f), box = (w - gap * 3) / 2;
            float top = y - font, bottom = y + font * .55f;
            paint.setColor(c.selection);
            canvas.drawRoundRect(gap, top, gap + box, bottom, 16, 16, paint);
            canvas.drawRoundRect(gap * 2 + box, top, w - gap, bottom, 16, 16, paint);
            label(canvas, hours, gap + box / 2, y + font * .2f, font, c.ink, 600, box - 16);
            label(canvas, minutes, gap * 2 + box * 1.5f, y + font * .2f, font, c.accent, 600, box - 16);
        } else if ("stacked".equals(face)) {
            float big = Math.min(font * 1.2f, h * .26f);
            label(canvas, hours, x, y - big * .12f, big, c.ink, 600, w - 20);
            label(canvas, minutes, x, y + big * .95f, big, c.accent, 500, w - 20);
        } else {
            label(canvas, time, x, y + font * .15f, font, c.ink, 500, w - 20);
        }
        if (dateRow == null && (!"analogue".equals(face) || h > 150)) {
            String caption = "digital".equals(face) && !period.isEmpty() ? period + " · " + date : date;
            label(canvas, caption, x, dateBaseline(getWidth(), getHeight()), small, c.muted, 450, w - 20);
        }
    }
}
