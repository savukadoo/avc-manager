package local.cc3.manager;

import android.content.Context;
import android.net.InetAddresses;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class IpGeography {
    private static IpGeography instance;
    private final ByteBuffer ranges;
    private final int ipv4Count;
    private final int ipv6Count;
    private final ArrayList<Country> countries = new ArrayList<>();
    private final Map<String, Country> byCode = new HashMap<>();

    static synchronized IpGeography load(Context context) throws IOException, JSONException {
        if (instance == null) instance = new IpGeography(context);
        return instance;
    }

    private IpGeography(Context context) throws IOException, JSONException {
        try (android.content.res.AssetFileDescriptor asset = context.getAssets().openFd("country-ranges.bin");
                java.io.FileInputStream file = asset.createInputStream()) {
            ranges = file.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    asset.getStartOffset(), asset.getLength());
        }
        if (ranges.getInt() != 0x43433347) throw new IOException("Invalid IP location database");
        ipv4Count = ranges.getInt();
        ipv6Count = ranges.getInt();
        if (12L + ipv4Count * 10L + ipv6Count * 34L != ranges.limit()) {
            throw new IOException("Incomplete IP location database");
        }

        try (InputStream stream = context.getAssets().open("world.json")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int size;
            while ((size = stream.read(chunk)) != -1) buffer.write(chunk, 0, size);
            JSONArray data = new JSONArray(buffer.toString("UTF-8"));
            for (int i = 0; i < data.length(); i++) {
                Country country = new Country(data.getJSONObject(i));
                countries.add(country);
                byCode.putIfAbsent(country.code, country);
            }
        }
    }

    String countryCode(String ip) {
        byte[] address = InetAddresses.parseNumericAddress(ip).getAddress();
        if (address.length == 16 && address[0] == 0 && address[1] == 0x64
                && address[2] == (byte) 0xff && address[3] == (byte) 0x9b) {
            boolean nat64 = true;
            for (int i = 4; i < 12; i++) nat64 &= address[i] == 0;
            if (nat64) address = java.util.Arrays.copyOfRange(address, 12, 16);
        }
        int width = address.length;
        int start = width == 4 ? 12 : 12 + ipv4Count * 10;
        int stride = width * 2 + 2;
        int low = 0;
        int high = (width == 4 ? ipv4Count : ipv6Count) - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int entry = start + middle * stride;
            if (compare(address, entry) < 0) high = middle - 1;
            else if (compare(address, entry + width) > 0) low = middle + 1;
            else return new String(new byte[] {ranges.get(entry + width * 2),
                    ranges.get(entry + width * 2 + 1)}, StandardCharsets.US_ASCII);
        }
        return null;
    }

    private int compare(byte[] address, int entry) {
        for (int i = 0; i < address.length; i++) {
            int difference = (address[i] & 255) - (ranges.get(entry + i) & 255);
            if (difference != 0) return difference;
        }
        return 0;
    }

    static String countryName(String code) {
        return code == null ? "Location unavailable"
                : new Locale("", code).getDisplayCountry();
    }

    private static final class Country {
        final String code;
        final float x;
        final float y;
        final Path outline = new Path();

        Country(JSONObject value) throws JSONException {
            code = value.getString("code");
            JSONArray point = value.getJSONArray("point");
            x = (float) point.getDouble(0) + 180;
            y = 85 - (float) point.getDouble(1);
            JSONArray outlines = value.getJSONArray("outlines");
            for (int i = 0; i < outlines.length(); i++) {
                JSONArray polygon = outlines.getJSONArray(i);
                for (int j = 0; j < polygon.length(); j++) {
                    JSONArray vertex = polygon.getJSONArray(j);
                    float lon = (float) vertex.getDouble(0) + 180;
                    float lat = 85 - (float) vertex.getDouble(1);
                    if (j == 0) outline.moveTo(lon, lat);
                    else outline.lineTo(lon, lat);
                }
                outline.close();
            }
        }
    }

    static final class MapView extends View {
        private static final float WORLD_WIDTH = 360f;
        private static final float WORLD_HEIGHT = 150f;
        private static final float MAX_ZOOM = 8f;
        private final Screen.Palette colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector pinch;
        private final GestureDetector drag;
        private final Consumer<String> markerTap;
        private IpGeography geography;
        private String selected;
        private Map<String, Integer> points = java.util.Collections.emptyMap();
        private float zoom = 1f;
        private float offsetX;
        private float offsetY;
        private float lastFocusX;
        private float lastFocusY;
        private boolean multiTouch;

        MapView(Context context, Screen.Palette colors, Consumer<String> markerTap) {
            super(context);
            this.colors = colors;
            this.markerTap = markerTap;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                    lastFocusX = detector.getFocusX();
                    lastFocusY = detector.getFocusY();
                    return true;
                }

                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float x = detector.getFocusX();
                    float y = detector.getFocusY();
                    scaleBy(detector.getScaleFactor(), lastFocusX, lastFocusY, x, y);
                    lastFocusX = x;
                    lastFocusY = y;
                    return true;
                }
            });
            pinch.setQuickScaleEnabled(false);
            drag = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) {
                    return true;
                }

                @Override public boolean onScroll(MotionEvent start, MotionEvent end, float dx, float dy) {
                    if (zoom <= 1f || multiTouch) return false;
                    offsetX -= dx;
                    offsetY -= dy;
                    constrain();
                    invalidate();
                    return true;
                }

                @Override public boolean onDoubleTap(MotionEvent event) {
                    scaleBy(zoom >= MAX_ZOOM ? 1f / zoom : 1.7f, event.getX(), event.getY(),
                            event.getX(), event.getY());
                    return true;
                }

                @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                    return tapMarker(event.getX(), event.getY());
                }
            });
        }

        void show(IpGeography data, Map<String, Integer> countries, String country) {
            geography = data;
            points = countries;
            selected = country;
            String description = country == null ? "World map, " + points.size() + " destination countries"
                    : "World map, selected destination " + countryName(country);
            if (!description.contentEquals(getContentDescription() == null ? "" : getContentDescription())) {
                setContentDescription(description);
            }
            invalidate();
        }

        void reset() {
            zoom = 1f;
            offsetX = 0f;
            offsetY = 0f;
            invalidate();
        }

        boolean focus(String countryCode) {
            Country country = geography == null ? null : geography.byCode.get(countryCode);
            if (country == null) return false;
            zoom = 3.5f;
            float scale = baseScale() * zoom;
            offsetX = (WORLD_WIDTH / 2f - country.x) * scale;
            offsetY = (WORLD_HEIGHT / 2f - country.y) * scale;
            constrain();
            invalidate();
            return true;
        }

        private boolean tapMarker(float x, float y) {
            float scale = baseScale() * zoom;
            float originX = (getWidth() - WORLD_WIDTH * scale) / 2 + offsetX;
            float originY = (getHeight() - WORLD_HEIGHT * scale) / 2 + offsetY;
            float radius = 30 * getResources().getDisplayMetrics().density;
            float closest = radius * radius;
            String match = null;
            for (String code : points.keySet()) {
                Country point = geography.byCode.get(code);
                if (point == null) continue;
                float dx = originX + point.x * scale - x;
                float dy = originY + point.y * scale - y;
                float distance = dx * dx + dy * dy;
                if (distance < closest) {
                    closest = distance;
                    match = code;
                }
            }
            if (match == null) return false;
            markerTap.accept(match);
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (geography == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                multiTouch = false;
                if (zoom > 1f) getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                multiTouch = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                drag.onTouchEvent(cancel);
                cancel.recycle();
            }
            pinch.onTouchEvent(event);
            if (!multiTouch) drag.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }

        private float baseScale() {
            return Math.min(getWidth() / WORLD_WIDTH, getHeight() / WORLD_HEIGHT);
        }

        private void scaleBy(float factor, float previousX, float previousY, float x, float y) {
            float next = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
            float ratio = next / zoom;
            offsetX = x - getWidth() / 2f - (previousX - getWidth() / 2f - offsetX) * ratio;
            offsetY = y - getHeight() / 2f - (previousY - getHeight() / 2f - offsetY) * ratio;
            zoom = next;
            constrain();
            invalidate();
        }

        private void constrain() {
            float scale = baseScale() * zoom;
            float maxX = Math.max(0f, (WORLD_WIDTH * scale - getWidth()) / 2f);
            float maxY = Math.max(0f, (WORLD_HEIGHT * scale - getHeight()) / 2f);
            offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
            offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (geography == null) return;
            float scale = baseScale() * zoom;
            canvas.save();
            canvas.translate((getWidth() - WORLD_WIDTH * scale) / 2 + offsetX,
                    (getHeight() - WORLD_HEIGHT * scale) / 2 + offsetY);
            canvas.scale(scale, scale);
            paint.setStyle(Paint.Style.FILL);
            for (Country country : geography.countries) {
                paint.setColor(country.code.equals(selected) ? tint(colors.accent, 0.46f)
                        : tint(colors.muted, 0.27f));
                canvas.drawPath(country.outline, paint);
            }
            for (String countryCode : points.keySet()) {
                Country country = geography.byCode.get(countryCode);
                if (country == null) continue;
                boolean chosen = countryCode.equals(selected);
                float dotScale = Math.min(1f, 1.35f / zoom);
                if (chosen) {
                    paint.setColor(tint(colors.accent, 0.42f));
                    canvas.drawCircle(country.x, country.y, 6 * dotScale, paint);
                }
                paint.setColor(colors.accent);
                canvas.drawCircle(country.x, country.y, (chosen ? 3.3f : 2.4f) * dotScale, paint);
            }
            canvas.restore();
        }

        private int tint(int foreground, float share) {
            int background = colors.surface;
            return Color.rgb(Math.round(Color.red(background) * (1 - share) + Color.red(foreground) * share),
                    Math.round(Color.green(background) * (1 - share) + Color.green(foreground) * share),
                    Math.round(Color.blue(background) * (1 - share) + Color.blue(foreground) * share));
        }
    }
}
