package local.cc3.manager;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;
import android.view.View;

/** An illustrated Home layout. The same glyphs identify each function across the six choices. */
final class LayoutThumbnail extends View {
    private static final int RADIO = R.drawable.ic_shortcut_radio;
    private static final int MUSIC = R.drawable.ic_shortcut_usb_music;
    private static final int MAP = R.drawable.ic_navigation_hero;
    private static final int PHONE = R.drawable.ic_shortcut_projection;
    private static final int APPS = R.drawable.drawer_grid;
    private static final int SETTINGS = R.drawable.ic_settings_cog;
    private final java.util.List<Drawable> shortcuts = new java.util.ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<Drawable> glyphs = new SparseArray<>();
    private final DashboardLayout layout;
    private final Screen.Palette palette;
    private final Drawable wallpaper;
    private final String clock;
    private final int favourites;
    private final boolean weather;
    private final boolean sideRail;

    LayoutThumbnail(Screen screen, DashboardLayout layout, java.util.List<DashboardApps.Choice> pinned) {
        super(screen);
        this.layout = layout;
        palette = screen.colors;
        wallpaper = HomeBackground.drawable(screen, screen.currentTheme(), screen.currentBackground(), false);
        wallpaper.setBounds(0, 0, 176, 88);
        clock = screen.currentClockFace();
        favourites = screen.currentFavouriteTiles();
        weather = WeatherRepository.enabled(screen.prefs());
        sideRail = getResources().getConfiguration().screenWidthDp >= 960;
        if (layout == DashboardLayout.BIG_SHORTCUTS || layout == DashboardLayout.CLOCK_FAVORITES) {
            for (DashboardApps.Choice choice : pinned) {
                if (shortcuts.size() == 8) break;
                try { shortcuts.add(choice.icon(screen)); }
                catch (android.content.pm.PackageManager.NameNotFoundException removed) { /* App was removed. */ }
            }
        }
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas c) {
        float scale = Math.min(getWidth() / 176f, getHeight() / 88f);
        if (scale <= 0) return;
        c.save();
        c.translate((getWidth() - 176 * scale) / 2, (getHeight() - 88 * scale) / 2);
        c.scale(scale, scale);
        // The frame keeps pale and photographic wallpapers distinct from the outer selection ring.
        box(c, 0, 0, 176, 88, palette.outline);
        c.save();
        c.clipRect(2, 2, 174, 86);
        wallpaper.draw(c);
        c.drawColor((palette.background & 0x00ffffff) | 0x99000000);
        c.restore();
        switch (layout) {
            case CC3_CLASSIC:
                group(c, 5, 5, 171, 35, RADIO, MUSIC, R.drawable.ic_shortcut_bluetooth);
                group(c, 5, 39, 171, 68, MAP, PHONE);
                dock(c, 5, false);
                break;
            case CC4_CARDS:
                group(c, 5, 5, 86, 35, MAP);
                group(c, 90, 5, 171, 35, PHONE);
                group(c, 5, 39, 86, 68, MUSIC);
                group(c, 90, 39, 171, 68, SETTINGS);
                dock(c, 5, false);
                break;
            case CARPLAY:
                int bottom = sideRail ? 83 : 68;
                int edge = sideRail ? 143 : 171;
                group(c, 5, 5, 80, bottom, MAP);
                group(c, 84, 5, edge, sideRail ? 50 : 42, RADIO);
                group(c, 84, sideRail ? 54 : 46, edge, bottom, PHONE);
                if (sideRail) {
                    box(c, 147, 5, 171, 83, palette.selection);
                    icon(c, MUSIC, 159, 18, 16);
                    icon(c, APPS, 159, 56, 13);
                    icon(c, SETTINGS, 159, 73, 14);
                } else dock(c, 5, false);
                break;
            case ANDROID_AUTO:
                group(c, 5, 5, 102, 68, MAP);
                group(c, 106, 5, 171, 68, MUSIC);
                dock(c, 5, true);
                break;
            case BIG_SHORTCUTS:
                int count = shortcuts.size();
                int columns = DashboardApps.shortcutColumns(getResources().getConfiguration().screenWidthDp, count);
                tiles(c, 5, 5, 171, 68, Math.min(columns, Math.max(count, 1)), count);
                dock(c, 5, false);
                break;
            case CLOCK_FAVORITES:
                box(c, 5, 5, 69, 83, palette.surface);
                clock(c, weather ? 27 : 44);
                if (weather) {
                    box(c, 9, 49, 65, 79, palette.selection);
                    icon(c, R.drawable.ic_weather_conditions, 37, 64, 26);
                }
                tiles(c, 73, 5, 171, 68, favourites / 2, favourites);
                dock(c, 73, false);
                break;
        }
        c.restore();
    }

    private void box(Canvas c, float x, float y, float right, float bottom, int color) {
        paint.setColor(color);
        c.drawRoundRect(x, y, right, bottom, 3, 3, paint);
    }

    private void icon(Canvas c, int resource, float x, float y, float size) {
        Drawable glyph = glyphs.get(resource);
        if (glyph == null) {
            glyph = getResources().getDrawable(resource, getContext().getTheme()).mutate();
            glyph.setTint(resource == RADIO || resource == MAP || resource == MUSIC ? palette.accent : palette.ink);
            glyphs.put(resource, glyph);
        }
        drawGlyph(c, glyph, x, y, size);
    }

    private static void drawGlyph(Canvas c, Drawable glyph, float x, float y, float size) {
        glyph.setBounds(Math.round(x - size / 2), Math.round(y - size / 2),
                Math.round(x + size / 2), Math.round(y + size / 2));
        glyph.draw(c);
    }

    private void group(Canvas c, int x, int y, int right, int bottom, int... apps) {
        box(c, x, y, right, bottom, palette.selection);
        float width = (right - x) / (float) apps.length;
        for (int i = 0; i < apps.length; i++) {
            if (i > 0) {
                paint.setColor(palette.outline);
                c.drawRect(x + width * i, y + 5, x + width * i + 1, bottom - 5, paint);
            }
            icon(c, apps[i], x + width * (i + .5f), (y + bottom) / 2f, Math.min(width - 6, bottom - y - 5));
        }
    }

    private void tiles(Canvas c, int x, int y, int right, int bottom, int columns, int count) {
        if (count == 0) {
            box(c, x, y, right, bottom, palette.surface);
            icon(c, APPS, (x + right) / 2f, (y + bottom) / 2f, 22);
            return;
        }
        float width = (right - x + 3f) / columns;
        float height = (bottom - y + 3f) / ((count + columns - 1) / columns);
        for (int i = 0; i < count; i++) {
            float left = x + i % columns * width;
            float top = y + i / columns * height;
            box(c, left, top, left + width - 3, top + height - 3, palette.selection);
            float cx = left + (width - 3) / 2, cy = top + (height - 3) / 2;
            float size = Math.min(32, Math.min(width, height) - 9);
            if (i < shortcuts.size()) drawGlyph(c, shortcuts.get(i), cx, cy, size);
            else icon(c, R.drawable.ic_shortcut_add, cx, cy, size);

        }
    }

    private void dock(Canvas c, int x, boolean projection) {
        box(c, x, 72, 171, 83, palette.surface);
        float width = 171 - x;
        if (projection) icon(c, PHONE, x + width / 6, 77.5f, 10);
        icon(c, APPS, x + width * (projection ? .5f : .25f), 77.5f, 10);
        icon(c, SETTINGS, x + width * (projection ? 5f / 6 : .75f), 77.5f, 10);
    }

    private void clock(Canvas c, int centerY) {
        if ("analogue".equals(clock)) {
            paint.setColor(palette.accent);
            c.drawCircle(37, centerY, 17, paint);
            paint.setColor(palette.surface);
            c.drawCircle(37, centerY, 15, paint);
            paint.setColor(palette.ink);
            paint.setStrokeWidth(2.5f);
            c.drawLine(37, centerY, 30, centerY - 6, paint);
            c.drawLine(37, centerY, 46, centerY - 8, paint);
        } else if ("stacked".equals(clock)) {
            paint.setColor(palette.ink); paint.setTextSize(18);
            c.drawText("10", 37, centerY - 1, paint);
            c.drawText("08", 37, centerY + 15, paint);
        } else if ("split".equals(clock)) {
            box(c, 9, centerY - 12, 35, centerY + 12, palette.selection);
            box(c, 39, centerY - 12, 65, centerY + 12, palette.selection);
            paint.setColor(palette.ink); paint.setTextSize(17);
            c.drawText("10", 22, centerY + 6, paint);
            c.drawText("08", 52, centerY + 6, paint);
        } else {
            paint.setColor(palette.ink); paint.setTextSize(20);
            c.drawText("10:08", 37, centerY + 7, paint);
        }
    }
}
