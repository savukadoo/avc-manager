package local.cc3.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.net.Uri;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import java.io.IOException;
import java.io.InputStream;

/** Local-only, shared wallpaper renderer for Manager, preview and Home. */
final class HomeBackground {
    static final String KEY = "home_background";
    static final String[] STYLES = {"landscape", "abstract", "contour", "aurora", "orbital-sunset", "orbital-aurora", "moonrise", "custom", "none"};
    static final String[] LABELS = {"Landscape", "Abstract", "Contour", "Aurora glow", "Orbital sunset", "Aurora Earth", "Moonrise", "Your photo", "Solid colour"};
    private static final String[][] IMAGES = {
        {"mist-forest", "mint-flow"}, {"blue-ridges", "blue-trails"},
        {"violet-lake", "plum-swirl"}, {"amber-ridges", "copper-trails"}
    };
    private static final String[][] NAMES = {
        {"Mist Forest", "Mint Flow"}, {"Blue Ridges", "Blue Trails"},
        {"Violet Lake", "Plum Swirl"}, {"Amber Ridges", "Copper Trails"}
    };
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    private HomeBackground() {}

    static String validStyle(String value) {
        if (value != null && value.startsWith("custom:content://")) return value;
        for (String style : STYLES) if (style.equals(value)) return style;
        return STYLES[0];
    }

    static String selected(SharedPreferences preferences) {
        return validStyle(preferences.getString(KEY, STYLES[0]));
    }

    private static int themeIndex(String theme) {
        for (int i = 0; i < Screen.THEMES.length; i++) if (Screen.THEMES[i].equals(theme)) return i;
        return 0;
    }

    static String name(String theme, String style) {
        if ("landscape".equals(style) || "abstract".equals(style)) return NAMES[themeIndex(theme)]["abstract".equals(style) ? 1 : 0];
        if (style != null && style.startsWith("custom:")) return "Your photo";
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(style)) return LABELS[i];
        return "Solid colour";
    }

    static boolean readable(Context context, String style) { return bitmap(context, style, Screen.THEMES[0]) != null; }

    private static InputStream open(Context context, String style, String theme) throws IOException {
        if (style.startsWith("custom:content://")) return context.getContentResolver().openInputStream(Uri.parse(style.substring(7)));
        String file = ("landscape".equals(style) || "abstract".equals(style))
                ? IMAGES[themeIndex(theme)]["abstract".equals(style) ? 1 : 0] + ".jpg" : style + ".png";
        return context.getAssets().open("wallpapers/" + file);
    }

    private static Bitmap bitmap(Context context, String style, String theme) {
        String key = theme + ":" + style;
        Bitmap bitmap = CACHE.get(key);
        if (bitmap != null) return bitmap;
        try {
            if (style.startsWith("custom:content://")) {
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), Uri.parse(style.substring(7))),
                        (decoder, info, source) -> {
                            float scale = Math.min(1f, Math.min(1600f / info.getSize().getWidth(), 1200f / info.getSize().getHeight()));
                            decoder.setTargetSize(Math.max(1, Math.round(info.getSize().getWidth() * scale)),
                                    Math.max(1, Math.round(info.getSize().getHeight() * scale)));
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        });
                if (bitmap != null) CACHE.put(key, bitmap);
                return bitmap;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream input = open(context, style, theme)) { BitmapFactory.decodeStream(input, null, options); }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 1200) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = open(context, style, theme)) { bitmap = BitmapFactory.decodeStream(input, null, options); }
            if (bitmap != null) CACHE.put(key, bitmap);
        } catch (IOException | SecurityException unavailable) { return null; }
        return bitmap;
    }

    static Drawable drawable(Context context, String theme, String style, boolean thumbnail) {
        Screen.Palette colors = Screen.palette(context, theme);
        if ("contour".equals(style) || "aurora".equals(style)) return new Ambient(colors, style);
        Bitmap image = "none".equals(style) || "custom".equals(style) ? null : bitmap(context, style, theme);
        return new Wallpaper(image, colors.background, thumbnail, thumbnail && "moonrise".equals(style) ? 0 : .5f);
    }

    /** Original theme-coloured artwork, rendered at the screen resolution. */
    private static final class Ambient extends Drawable {
        private final Screen.Palette colors;
        private final String style;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ambient(Screen.Palette colors, String style) { this.colors = colors; this.style = style; }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.save(); canvas.clipRect(b); canvas.translate(b.left, b.top);
            float w = b.width(), h = b.height();
            canvas.drawColor(colors.background);
            int rgb = colors.accent & 0x00ffffff;
            paint.setShader(new RadialGradient(w * .75f, h * .25f, Math.max(w, h) * .85f,
                    new int[] {rgb | 0x60000000, rgb | 0x18000000, rgb}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint); paint.setShader(null);
            if ("contour".equals(style)) {
                paint.setColor(rgb | 0x45000000); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(.7f, w / 1400));
                for (int i = 0; i < 18; i++) {
                    float y = h * .25f + i * h / 27;
                    Path line = new Path(); line.moveTo(-w * .1f, y);
                    line.cubicTo(w * .3f, y + h * .35f, w * .55f, y - h * .35f, w * 1.1f, y + h * .17f);
                    canvas.drawPath(line, paint);
                }
                paint.setStyle(Paint.Style.FILL);
            } else {
                paint.setShader(new RadialGradient(w * .2f, h * .82f, Math.max(w, h) * .65f,
                        new int[] {rgb | 0x38000000, rgb}, null, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, paint); paint.setShader(null);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override @SuppressWarnings("deprecation") public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    private static final class Wallpaper extends Drawable {
        private final Bitmap bitmap;
        private final int base;
        private final boolean thumbnail;
        private final float verticalFocus;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shade = new Paint();
        private final RectF image = new RectF();

        Wallpaper(Bitmap bitmap, int base, boolean thumbnail, float verticalFocus) {
            this.bitmap = bitmap;
            this.base = base;
            this.thumbnail = thumbnail;
            this.verticalFocus = verticalFocus;
        }

        @Override protected void onBoundsChange(Rect bounds) {
            if (bitmap == null || bounds.isEmpty()) return;
            float scale = Math.max(bounds.width() / (float) bitmap.getWidth(), bounds.height() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
            float top = bounds.top - (height - bounds.height()) * verticalFocus;
            image.set(bounds.centerX() - width / 2, top, bounds.centerX() + width / 2, top + height);
            int rgb = base & 0x00ffffff;
            shade.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                    thumbnail ? new int[] {rgb | 0x18000000, rgb | 0x38000000}
                            : new int[] {rgb | 0xd1000000, rgb | 0x9e000000, rgb | 0x8f000000, rgb | 0xb3000000},
                    thumbnail ? null : new float[] {0, .35f, .78f, 1}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.clipRect(getBounds());
            canvas.drawColor(base);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, image, paint);
                canvas.drawRect(getBounds(), shade);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override @SuppressWarnings("deprecation") public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
