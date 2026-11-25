package local.cc3.manager;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.SeekBar;

final class TactileSlider extends SeekBar {
    private final Screen screen;
    private final boolean centerOrigin;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    TactileSlider(Screen screen, boolean centerOrigin) {
        super(screen);
        this.screen = screen;
        this.centerOrigin = centerOrigin;
        setPadding(screen.dp(18), 0, screen.dp(18), 0);
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float centerY = getHeight() / 2f;
        float position = getProgress() / (float) getMax();
        float centerX = isLayoutRtl() ? right - (right - left) * position
                : left + (right - left) * position;
        float radius = screen.dp(7);
        paint.setColor(screen.colors.muted);
        paint.setAlpha(isEnabled() ? 110 : 60);
        canvas.drawRoundRect(left - radius, centerY - radius, right + radius,
                centerY + radius, radius, radius, paint);
        if (!isEnabled()) return;

        paint.setColor(screen.colors.accent);
        float start = centerOrigin ? (left + right) / 2f : isLayoutRtl() ? right : left;
        canvas.drawRoundRect(Math.min(start, centerX) - radius, centerY - radius,
                Math.max(start, centerX) + radius, centerY + radius, radius, radius, paint);
        if (isFocused() || isPressed()) {
            paint.setAlpha(82);
            canvas.drawCircle(centerX, centerY, screen.dp(22), paint);
            paint.setAlpha(255);
        }
        canvas.drawCircle(centerX, centerY, screen.dp(15), paint);
        paint.setColor(screen.colors.onAccent);
        paint.setStrokeWidth(screen.dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(centerX - screen.dp(3), centerY - screen.dp(4),
                centerX - screen.dp(3), centerY + screen.dp(4), paint);
        canvas.drawLine(centerX + screen.dp(3), centerY - screen.dp(4),
                centerX + screen.dp(3), centerY + screen.dp(4), paint);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    private boolean isLayoutRtl() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }
}
