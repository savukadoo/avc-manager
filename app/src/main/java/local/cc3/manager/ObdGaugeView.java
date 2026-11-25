package local.cc3.manager;

import local.cc3.obd.*;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.View;
import java.util.Collections;
import java.util.List;

/** One native, accessible vehicle card. Every plotted point comes from an actual sample. */
final class ObdGaugeView extends View {
    private final Screen screen;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trace = new Path();
    private ObdPid pid;
    private ObdEngine.Reading reading;
    private boolean imperial;
    private boolean usable;
    private String style = "number";
    private String quality = "Not connected";

    ObdGaugeView(Screen screen) {
        super(screen);
        this.screen = screen;
        setFocusable(true);
        setClickable(true);
        setBackground(new RippleDrawable(ColorStateList.valueOf(
                (screen.colors.accent & 0x00ffffff) | 0x24000000),
                screen.shape(screen.colors.surface, 16), null));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void bind(ObdPid pid, ObdEngine.Reading reading, boolean imperial,
            String style, String quality, boolean usable) {
        this.pid = pid;
        this.reading = reading;
        this.imperial = imperial;
        this.style = pid != null && pid.categorical() ? "number" : style;
        this.quality = quality;
        this.usable = usable;
        String value = hasSample() ? pid.valueText(reading.value, imperial) + " " + pid.unit(imperial)
                : "No reading";
        String description = (pid == null ? "Choose sensor" : pid.dashboardLabel()) + ", " + value
                + (quality.isEmpty() ? "" : ", " + quality) + ". Open sensor details.";
        if (!TextUtils.equals(description, getContentDescription())) setContentDescription(description);
        invalidate();
    }

    private boolean hasSample() {
        return pid != null && reading != null && reading.timestampMillis > 0
                && !Double.isNaN(reading.value) && !Double.isInfinite(reading.value);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics()); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Screen.Palette c = screen.colors;
        float w = getWidth(), h = getHeight();
        boolean compact = h < dp(190);
        float inset = dp(compact ? 16 : 18);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(c.ink);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        paint.setTextSize(sp(compact ? 14 : 15));
        String label = pid == null ? "Choose sensor" : pid.dashboardLabel();
        label = TextUtils.ellipsize(label, new android.text.TextPaint(paint),
                w - inset * 2, TextUtils.TruncateAt.END).toString();
        float labelY = dp(compact ? 12 : 17) - paint.ascent();
        canvas.drawText(label, inset, labelY, paint);
        float contentTop = labelY + paint.descent() + dp(compact ? 7 : 11);

        paint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        paint.setTextSize(sp(compact ? 10 : 11));
        float footerCenter = h - dp(compact ? 16 : 21);
        float footerBaseline = footerCenter - (paint.ascent() + paint.descent()) / 2;
        float contentBottom = footerBaseline + paint.ascent() - dp(compact ? 8 : 12);

        String value = hasSample() ? pid.valueText(reading.value, imperial) : "—";
        String unit = pid == null ? "" : pid.unit(imperial);
        int valueColor = usable ? c.accent : c.muted;
        if ("dial".equals(style)) {
            float stroke = dp(compact ? 6 : 8);
            float radius = Math.max(dp(8), Math.min((w - inset * 2 - stroke) / 2,
                    (contentBottom - contentTop - stroke) / 1.5f));
            float cx = w / 2, cy = contentTop + radius + stroke / 2;
            RectF arc = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(stroke);
            paint.setColor(c.outline);
            canvas.drawArc(arc, 150, 240, false, paint);
            if (hasSample() && pid.max > pid.min) {
                float amount = (float)Math.max(0, Math.min(1,
                        (reading.value - pid.min) / (pid.max - pid.min)));
                paint.setColor(valueColor);
                canvas.drawArc(arc, 150, 240 * amount, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            drawNumber(canvas, value, unit, w / 2, contentTop + radius * 0.35f,
                    contentBottom, Math.min(sp(34), radius * 0.9f), sp(compact ? 10 : 12),
                    radius * 2 - dp(14), valueColor, c.muted, dp(compact ? 2 : 4));
        } else if ("trend".equals(style)) {
            float unitSize = sp(compact ? 11 : 12);
            paint.setTextSize(unitSize);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            float unitWidth = paint.measureText(unit);
            float numberHeight = Math.max(dp(12), (contentBottom - contentTop) * (compact ? 0.58f : 0.43f));
            float numberSize = fittedSize(sp(compact ? 30 : 34), numberHeight, 650);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, 650, false));
            paint.setTextSize(numberSize);
            float valueY = contentTop - paint.ascent();
            float valueBottom = valueY + paint.descent();
            drawValue(canvas, value, inset, valueY, numberSize,
                    Math.max(dp(30), w - inset * 2 - unitWidth - dp(12)), valueColor, false);
            paint.setTextSize(unitSize);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            paint.setColor(c.muted);
            canvas.drawText(unit, w - inset - unitWidth, valueY, paint);
            drawTrend(canvas, inset, valueBottom + dp(compact ? 6 : 10), w - inset, contentBottom, valueColor);
        } else {
            drawNumber(canvas, value, unit, w / 2, contentTop, contentBottom,
                    sp(compact ? 34 : 43), sp(compact ? 11 : 13),
                    w - inset * 2, valueColor, c.muted, dp(compact ? 3 : 5));
        }

        paint.setStyle(Paint.Style.FILL);
        if (quality.isEmpty()) return;
        paint.setColor(usable ? c.accent : c.muted);
        canvas.drawCircle(inset + dp(3), footerCenter, dp(3), paint);
        paint.setTextSize(sp(compact ? 10 : 11));
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        paint.setColor(c.muted);
        String state = TextUtils.ellipsize(quality, new android.text.TextPaint(paint),
                w - inset * 2 - dp(15), TextUtils.TruncateAt.END).toString();
        canvas.drawText(state, inset + dp(14), footerBaseline, paint);
    }

    /** Fit the complete value/unit stack inside the measured content area at the user's font scale. */
    private void drawNumber(Canvas canvas, String value, String unit, float x, float top, float bottom,
            float desiredSize, float unitSize, float width, int valueColor, int unitColor, float gap) {
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        paint.setTextSize(unitSize);
        float unitAscent = paint.ascent(), unitDescent = paint.descent();
        float unitHeight = unitDescent - unitAscent;
        float size = fittedSize(desiredSize, Math.max(dp(8), bottom - top - unitHeight - gap), 650);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, 650, false));
        paint.setTextSize(size);
        if (paint.measureText(value) > width) {
            size *= Math.max(dp(8), width) / paint.measureText(value);
            paint.setTextSize(size);
        }
        float ascent = paint.ascent(), descent = paint.descent();
        float groupHeight = descent - ascent + gap + unitHeight;
        float baseline = top + (bottom - top - groupHeight) / 2 - ascent;
        drawValue(canvas, value, x, baseline, size, width, valueColor);
        drawCentered(canvas, unit, x, baseline + descent + gap - unitAscent, unitSize, unitColor, 500);
    }

    private float fittedSize(float requested, float height, int weight) {
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        paint.setTextSize(requested);
        float actual = paint.descent() - paint.ascent();
        return actual > height && actual > 0 ? requested * height / actual : requested;
    }

    private void drawValue(Canvas canvas, String value, float x, float y, float size,
            float available, int color) { drawValue(canvas, value, x, y, size, available, color, true); }

    private void drawValue(Canvas canvas, String value, float x, float y, float size,
            float available, int color, boolean centered) {
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, 650, false));
        paint.setTextSize(size);
        if (paint.measureText(value) > available) paint.setTextSize(size * available / paint.measureText(value));
        paint.setColor(color);
        canvas.drawText(value, centered ? x - paint.measureText(value) / 2 : x, y, paint);
    }

    private void drawCentered(Canvas canvas, String label, float x, float y, float size, int color, int weight) {
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        paint.setColor(color);
        canvas.drawText(label, x - paint.measureText(label) / 2, y, paint);
    }

    private void drawTrend(Canvas canvas, float left, float top, float right, float bottom, int color) {
        if (bottom <= top) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(screen.colors.outline);
        canvas.drawLine(left, bottom, right, bottom, paint);
        List<Double> history = reading == null || reading.history == null
                ? Collections.emptyList() : reading.history;
        if (history.size() < 2) { paint.setStyle(Paint.Style.FILL); return; }
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (Double point : history) if (point != null && !point.isNaN() && !point.isInfinite()) {
            low = Math.min(low, point); high = Math.max(high, point);
        }
        if (!Double.isFinite(low) || !Double.isFinite(high)) { paint.setStyle(Paint.Style.FILL); return; }
        if (high - low < 0.001) { low -= 1; high += 1; }
        double margin = (high - low) * 0.1;
        low -= margin; high += margin;
        trace.reset();
        boolean started = false;
        for (int i = 0; i < history.size(); i++) {
            Double point = history.get(i);
            if (point == null || point.isNaN() || point.isInfinite()) { started = false; continue; }
            float x = left + (right - left) * i / (history.size() - 1);
            float y = bottom - (float)((point - low) / (high - low)) * (bottom - top);
            if (!started) trace.moveTo(x, y); else trace.lineTo(x, y);
            started = true;
        }
        paint.setStrokeWidth(dp(3));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        canvas.drawPath(trace, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
