package local.cc3.manager;

import local.cc3.obd.*;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Native ten-minute graph. Each dot is an arithmetic mean of actual valid samples. */
final class ObdHistoryView extends View {
    private final Screen screen;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private List<ObdHistory.Minute> points = Collections.emptyList();
    private ObdPid pid;
    private boolean imperial;
    private int selected = -1;

    ObdHistoryView(Screen screen) {
        super(screen); this.screen = screen;
        setBackground(screen.shape(screen.colors.surface, 12));
        setFocusable(true); setClickable(true);
    }
    void bind(ObdPid pid, List<ObdHistory.Minute> points, boolean imperial) {
        this.pid = pid; this.points = points; this.imperial = imperial;
        updateDescription();
        invalidate();
    }
    private void updateDescription() {
        if (pid == null) return;
        int populated = 0; ObdHistory.Minute latest = null;
        for (ObdHistory.Minute minute : points) if (valid(minute)) { populated++; latest = minute; }
        String spoken = pid.label + ", last ten minutes, one-minute averages. " + populated + " minutes have samples."
                + (latest == null ? " No history collected." : " Latest average " + pid.averageText(latest.average, imperial) + " " + pid.averageUnit(imperial) + ".")
                + " Missing minutes are gaps. Tap a point for its time and average."
                + (selected >= 0 && selected < points.size() ? " Selected " + pointCaption(points.get(selected)) + "." : "");
        if (!TextUtils.equals(spoken, getContentDescription())) setContentDescription(spoken);
    }
    private String pointCaption(ObdHistory.Minute point) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return format.format(new Date(point.startMillis)) + "–" + format.format(new Date(point.endMillis)) + " · "
                + (valid(point) ? pid.averageText(point.average,imperial) + " " + pid.averageUnit(imperial) + " · " + point.samples + " samples" : "No samples");
    }
    private boolean valid(ObdHistory.Minute point) { return point.samples > 0 && Double.isFinite(point.average); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics()); }
    private void text(Canvas canvas, String value, float x, float y, float size, int color, Paint.Align align) {
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(size); paint.setColor(color); paint.setTextAlign(align);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false)); canvas.drawText(value,x,y,paint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pid == null) return;
        Screen.Palette colors = screen.colors;
        float left = dp(82), right = getWidth()-dp(24), top = dp(40), bottom = getHeight()-dp(44);
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (ObdHistory.Minute point : points) if (valid(point)) {
            double value = pid.averageValue(point.average,imperial); low=Math.min(low,value); high=Math.max(high,value);
        }
        if (!Double.isFinite(low)) {
            text(canvas,"No history yet",getWidth()/2f,getHeight()/2f,sp(18),colors.ink,Paint.Align.CENTER);
            text(canvas,"One-minute averages appear as data arrives",getWidth()/2f,getHeight()/2f+dp(28),sp(12),colors.muted,Paint.Align.CENTER);
            return;
        }
        if (high-low < 0.001) { low-=1; high+=1; }
        double margin=(high-low)*.12;low-=margin;high+=margin;
        for (int row=0;row<=4;row++) {
            float y=top+(bottom-top)*row/4;
            paint.setColor(colors.outline);paint.setStrokeWidth(dp(1));canvas.drawLine(left,y,right,y,paint);
            double value=high-(high-low)*row/4;
            double tickValue = Math.rint(value * (high-low<10?10:1)) == 0 ? 0 : value;
            text(canvas,String.format(Locale.getDefault(),high-low<10?"%.1f":"%.0f",tickValue),left-dp(10),y+sp(4),sp(12),colors.muted,Paint.Align.RIGHT);
        }
        text(canvas,pid.averageUnit(imperial),left,dp(24),sp(12),colors.muted,Paint.Align.LEFT);
        text(canvas,"−10 min",left,bottom+dp(28),sp(12),colors.muted,Paint.Align.LEFT);
        text(canvas,"−5 min",(left+right)/2,bottom+dp(28),sp(12),colors.muted,Paint.Align.CENTER);
        text(canvas,"Now",right,bottom+dp(28),sp(12),colors.muted,Paint.Align.RIGHT);
        line.reset();boolean started=false;
        for (int index=0;index<points.size();index++) {
            ObdHistory.Minute point=points.get(index);
            if (!valid(point)) { started=false;continue; }
            float x=left+(right-left)*(index+.5f)/10;
            float y=bottom-(float)((pid.averageValue(point.average,imperial)-low)/(high-low))*(bottom-top);
            if (started) line.lineTo(x,y);else line.moveTo(x,y); started=true;
        }
        paint.setTextAlign(Paint.Align.LEFT);paint.setStyle(Paint.Style.STROKE);paint.setColor(colors.accent);
        paint.setStrokeWidth(dp(3));paint.setStrokeJoin(Paint.Join.ROUND);canvas.drawPath(line,paint);
        paint.setStyle(Paint.Style.FILL);
        for (int index=0;index<points.size();index++) if (valid(points.get(index))) {
            ObdHistory.Minute point=points.get(index);
            float x=left+(right-left)*(index+.5f)/10;
            float y=bottom-(float)((pid.averageValue(point.average,imperial)-low)/(high-low))*(bottom-top);
            paint.setColor(colors.accent);canvas.drawCircle(x,y,dp(index==selected?6:4),paint);
        }
        if (selected>=0 && selected<points.size()) {
            ObdHistory.Minute point=points.get(selected);
            text(canvas,pointCaption(point),right,dp(24),sp(12),colors.ink,Paint.Align.RIGHT);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction()==MotionEvent.ACTION_DOWN || event.getAction()==MotionEvent.ACTION_MOVE) {
            float left=dp(82),width=getWidth()-dp(24)-left;
            if (width>0) selected=Math.max(0,Math.min(9,(int)((event.getX()-left)/width*10)));
            updateDescription();invalidate();return true;
        }
        if (event.getAction()==MotionEvent.ACTION_UP) { performClick();return true; }
        return super.onTouchEvent(event);
    }
    @Override public boolean performClick() { super.performClick();return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }
    private boolean selectMinute(int direction) {
        if (points.isEmpty()) return false;
        selected = selected < 0 ? (direction > 0 ? 0 : points.size()-1)
                : Math.max(0, Math.min(points.size()-1, selected + direction));
        updateDescription();invalidate();announceForAccessibility(pointCaption(points.get(selected)));return true;
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return selectMinute(1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return selectMinute(-1);
        return super.performAccessibilityAction(action, arguments);
    }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return selectMinute(1);
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) return selectMinute(-1);
        return super.onKeyDown(code, event);
    }
}
