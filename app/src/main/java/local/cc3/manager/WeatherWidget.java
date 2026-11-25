package local.cc3.manager;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageButton;
import android.net.Uri;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** A compact seven-day forecast. Caller explicitly starts/stops it with foreground lifecycle. */
final class WeatherWidget extends FrameLayout implements java.io.Closeable {
    static final int HOME_HEIGHT_DP = 308;
    private static final int DETAILS_TOP_DP = 171;
    static final int DETAILS_BOTTOM_DP = 6;
    static final int DETAILS_DIVIDER_GAP_DP = 8;
    static final int HOME_DETAILS_HEIGHT_DP = HOME_HEIGHT_DP - DETAILS_TOP_DP - DETAILS_BOTTOM_DP;
    private final Screen screen;
    private final WeatherRepository repository;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Button mapButton;
    private boolean started;
    private Screen.PanelDialog creditsDialog;
    private WeatherRepository.State state = new WeatherRepository.State(null, "Weather is off", false, false);
    private final Runnable ageTick = new Runnable() {
        @Override public void run() { if (started) { invalidate(); describe(); handler.postDelayed(this, 60_000); } }
    };

    WeatherWidget(Screen screen) {
        super(screen);
        this.screen = screen;
        repository = new WeatherRepository(screen, value -> { state = value; describe(); invalidate(); });
        setWillNotDraw(false);
        setMinimumHeight(screen.dp(150));
        setClickable(true); setFocusable(true);
        setOnClickListener(view -> screen.open(Screen.managerIntent(screen, "Weather")));
        ImageButton credits = screen.infoButton("Weather data", "");
        credits.setContentDescription("Weather data by Open-Meteo · Update time, details and credits");
        credits.setTooltipText("Weather details · Update time and credits");
        credits.setOnClickListener(view -> showCredits());
        addView(credits, new FrameLayout.LayoutParams(screen.dp(48), screen.dp(48), Gravity.TOP | Gravity.END));
        mapButton = screen.button("Radar", false, () -> screen.open(new Intent(screen, WeatherMapActivity.class)));
        mapButton.setContentDescription("Open weather radar map"); mapButton.setTooltipText("Weather radar and forecast map");
        mapButton.setTextSize(12); mapButton.setMinWidth(0); mapButton.setMinimumWidth(0);
        mapButton.setGravity(Gravity.CENTER); mapButton.setPadding(screen.dp(4), screen.dp(12), screen.dp(4), screen.dp(12));
        Drawable icon = screen.getDrawable(R.drawable.ic_weather_map).mutate();
        icon.setTint(screen.colors.accent); icon.setBounds(0, 0, screen.dp(28), screen.dp(28));
        InsetDrawable spacedIcon = new InsetDrawable(icon, 0, 0, 0, screen.dp(8));
        spacedIcon.setBounds(0, 0, screen.dp(28), screen.dp(36));
        SpannableString caption = new SpannableString("\ufffc\nRadar");
        caption.setSpan(new ImageSpan(spacedIcon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mapButton.setText(caption); mapButton.setIncludeFontPadding(false);
        mapButton.setVisibility(View.GONE);
        addView(mapButton, new FrameLayout.LayoutParams(screen.dp(76), screen.dp(HOME_DETAILS_HEIGHT_DP), Gravity.TOP | Gravity.END));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        // Decide before the child measure/layout pass, including the first Home frame.
        int height = getMeasuredHeight();
        boolean extended = height >= screen.dp(HOME_HEIGHT_DP);
        mapButton.setVisibility(extended ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams bounds = (FrameLayout.LayoutParams) mapButton.getLayoutParams();
        bounds.topMargin = screen.dp(DETAILS_TOP_DP); bounds.setMarginEnd(screen.dp(5));
        bounds.height = Math.max(screen.dp(76), height - screen.dp(DETAILS_TOP_DP + DETAILS_BOTTOM_DP));
        super.onMeasure(widthSpec, heightSpec);
    }

    private void showCredits() {
        if (creditsDialog != null) creditsDialog.dismiss();
        Screen.PanelDialog dialog = screen.new PanelDialog("Weather details", 760);
        creditsDialog = dialog;
        WeatherRepository.Forecast forecast=state.forecast;
        if(forecast!=null)dialog.message((state.loading?"Updating forecast…\n":"")+"Updated "+age(Math.max(0,System.currentTimeMillis()-forecast.fetchedAt))
                +" · refreshes hourly"+(state.fallback||state.status.startsWith("Could not")?"\n"+state.status:"")
                +"\n\nHumidity and wind: current conditions.\nUV and rain chance: today's forecast maximums.\nWind: km/h. Unavailable readings: —.");
        else dialog.message(state.status);
        dialog.message("Forecast: Open-Meteo · CC BY 4.0.\nPlaces: GeoNames via Open-Meteo.\nFormatting and unit conversion: AVC Manager.");
        dialog.option("Open-Meteo", () -> screen.open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/"))));
        dialog.option("CC BY 4.0 licence", () -> screen.open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://creativecommons.org/licenses/by/4.0/"))));
        dialog.action("Close", false, dialog::dismiss);
        dialog.show();
    }

    void start() {
        if (started) return;
        started = true; repository.start(); ageTick.run();
    }
    void stop() {
        started = false; handler.removeCallbacksAndMessages(null); repository.stop();
        if (creditsDialog != null) { creditsDialog.dismiss(); creditsDialog = null; }
    }
    void refresh() { repository.refresh(true); }
    @Override public void close() { stop(); repository.close(); }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics()); }
    private void text(Canvas canvas, String value, float x, float baseline, float size, int color, boolean bold, boolean centered, float width) {
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? 600 : 450, false));
        paint.setTextSize(size); paint.setStyle(Paint.Style.FILL); paint.setColor(color);
        paint.setTextAlign(centered ? Paint.Align.CENTER : Paint.Align.LEFT);
        String shown = value;
        while (shown.length() > 1 && paint.measureText(shown) > width) shown = shown.substring(0, shown.length() - 1);
        if (!shown.equals(value) && shown.length() > 1) shown = shown.substring(0, shown.length() - 1) + "…";
        canvas.drawText(shown, x, baseline, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), pad = dp(5);
        Screen.Palette colors = screen.colors;
        paint.setColor(colors.outline); paint.setStrokeWidth(dp(1)); canvas.drawLine(pad, dp(1), w - pad, dp(1), paint);
        WeatherRepository.Forecast forecast = state.forecast;
        if (!WeatherRepository.enabled(screen.prefs())) {
            text(canvas, "Weather is off", pad, h * .45f, sp(14), colors.muted, false, false, w - pad * 2);
            text(canvas, "Tap to configure", pad, h * .7f, sp(11), colors.muted, false, false, w - pad * 2); return;
        }
        if (forecast == null || !forecast.usable(System.currentTimeMillis())) {
            text(canvas, "Seven-day forecast", pad, dp(28), sp(14), colors.ink, true, false, w - pad * 2 - dp(48));
            text(canvas, state.status, pad, dp(70), sp(12), colors.muted, false, false, w - pad * 2);
            text(canvas, "Tap to choose a place or view settings", pad, dp(100), sp(10.5f), colors.muted, false, false, w - pad * 2);
            return;
        }
        long age = Math.max(0, System.currentTimeMillis() - forecast.fetchedAt);
        boolean stale = age >= WeatherRepository.FRESH_MS;
        text(canvas, forecast.place.name, pad, dp(26), sp(14), colors.ink, true, false, w - pad * 2 - dp(48));
        LocalDate today = today(forecast);
        float cell = (w - pad * 2) / 7f;
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colors.selection);
        canvas.drawRoundRect(pad, dp(48), pad + cell, dp(151), dp(10), dp(10), paint);
        for (int i = 0; i < 7; i++) {
            float x = pad + cell * (i + .5f);
            LocalDate date = today.plusDays(i);
            String dayLabel = i == 0 ? "Today" : date.format(DateTimeFormatter.ofPattern("EEE", locale));
            WeatherRepository.Day day = find(forecast, date);
            text(canvas, dayLabel, x, dp(71), sp(10.5f), i == 0 ? colors.accent : colors.muted, i == 0, true, cell - dp(2));
            icon(canvas, x, dp(89), dp(12), day == null ? -1 : day.code, stale ? colors.muted : colors.accent,
                    i == 0 ? colors.selection : colors.surface);
            text(canvas, temperature(day == null ? Double.NaN : day.high), x, dp(122), sp(i == 0 ? 14.5f : 13), stale ? colors.muted : colors.ink, true, true, cell - dp(2));
            text(canvas, temperature(day == null ? Double.NaN : day.low), x, dp(143), sp(10.5f), colors.muted, false, true, cell - dp(2));
        }
        if(h>=dp(HOME_HEIGHT_DP)){
            WeatherRepository.Day day=find(forecast,today);
            float dividerTop = screen.dp(DETAILS_TOP_DP - DETAILS_DIVIDER_GAP_DP);
            paint.setColor(colors.outline);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(pad, dividerTop, w - pad, dividerTop + screen.dp(1), paint);
            float gap=dp(8), column=(w-pad*2-dp(76)-gap*2)/2;
            float top=dp(DETAILS_TOP_DP), row=(h-top-dp(DETAILS_BOTTOM_DP)-gap)/2;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((colors.ink & 0x00ffffff) | 0x0d000000);
            canvas.drawRoundRect(pad, top, pad + 2 * column + gap, h - dp(DETAILS_BOTTOM_DP), dp(10), dp(10), paint);
            paint.setColor(colors.outline);
            float rowDivider = top + row + gap / 2;
            canvas.drawRect(pad + dp(12), rowDivider, pad + 2 * column + gap - dp(12), rowDivider + dp(1), paint);
            metric(canvas,"Humidity",percent(forecast.current.humidity),pad,top,column,row);
            String wind=Double.isFinite(forecast.current.wind)?Math.round(forecast.current.wind)+" km/h":"—";
            String windLabel="Wind"+(Double.isFinite(forecast.current.direction)?" · "+direction(forecast.current.direction):"");
            metric(canvas,windLabel,wind,pad+column+gap,top,column,row);
            metric(canvas,"UV · today",day!=null&&Double.isFinite(day.uv)?String.format(Locale.getDefault(),"%.1f",day.uv):"—",pad,top+row+gap,column,row);
            metric(canvas,"Rain · today",day==null?"—":percent(day.rainProbability),pad+column+gap,top+row+gap,column,row);
        }
    }
    private void metric(Canvas canvas,String label,String value,float x,float y,float width,float height){
        float labelBaseline = y + height / 2f - dp(9);
        text(canvas,label,x+dp(10),labelBaseline,sp(10.5f),screen.colors.muted,false,false,width-dp(20));
        text(canvas,value,x+dp(10),labelBaseline+dp(27),sp(16.5f),screen.colors.ink,true,false,width-dp(20));
    }
    private static String percent(double value){return Double.isFinite(value)?Math.round(value)+"%":"—";}
    private static String direction(double value){String[] names={"N","NE","E","SE","S","SW","W","NW"};return names[(int)Math.round(value/45)%8];}
    private String temperature(double value) {
        if (!Double.isFinite(value)) return "—";
        if (WeatherRepository.fahrenheit(screen.prefs())) value = value * 9 / 5 + 32;
        return Math.round(value) + "°";
    }
    static String age(long millis) {
        long minutes = millis / 60_000;
        return minutes < 1 ? "just now" : minutes < 60 ? minutes + "m ago" : (minutes / 60) + "h ago";
    }
    private static LocalDate today(WeatherRepository.Forecast forecast) {
        try { return LocalDate.now(ZoneId.of(forecast.timezone)); }
        catch (RuntimeException invalid) { return LocalDate.now(); }
    }
    private static WeatherRepository.Day find(WeatherRepository.Forecast forecast, LocalDate date) {
        for (WeatherRepository.Day day : forecast.days) if (date.toString().equals(day.date)) return day;
        return null;
    }
    static String condition(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code == 51 || code == 53 || code == 55) return "Drizzle";
        if (code == 56 || code == 57) return "Freezing drizzle";
        if (code == 61 || code == 63 || code == 65) return "Rain";
        if (code == 66 || code == 67) return "Freezing rain";
        if (code == 71 || code == 73 || code == 75 || code == 77) return "Snow";
        if (code == 80 || code == 81 || code == 82) return "Rain showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code == 95) return "Thunderstorm";
        if (code == 96 || code == 99) return "Thunderstorm with hail";
        return "Weather unavailable";
    }

    private void describe() {
        WeatherRepository.Forecast forecast = state.forecast;
        if (forecast == null) { setContentDescription("Seven-day forecast. " + state.status + ". Open weather settings."); return; }
        StringBuilder description = new StringBuilder(forecast.place.label()).append(". ").append(state.status)
                .append(". Updated ").append(age(Math.max(0, System.currentTimeMillis() - forecast.fetchedAt))).append(". ");
        for (WeatherRepository.Day day : forecast.days) description.append(day.date).append(": ").append(condition(day.code))
                .append(", high ").append(temperature(day.high)).append(", low ").append(temperature(day.low)).append(". ");
        WeatherRepository.Day day=find(forecast,today(forecast));
        description.append("Current humidity ").append(percent(forecast.current.humidity)).append(". Current wind ")
                .append(Double.isFinite(forecast.current.wind)?Math.round(forecast.current.wind)+" kilometres per hour":"unavailable")
                .append(Double.isFinite(forecast.current.direction)?" "+direction(forecast.current.direction):"")
                .append(". Today's maximum UV index ").append(day!=null&&Double.isFinite(day.uv)?String.format(Locale.getDefault(),"%.1f",day.uv):"unavailable")
                .append(". Today's maximum precipitation probability ").append(day==null?"unavailable":percent(day.rainProbability)).append(". ");
        description.append(WeatherRepository.fahrenheit(screen.prefs()) ? "Fahrenheit. " : "Celsius. ")
                .append("Forecast from Open-Meteo, CC BY 4.0. Open weather settings.");
        setContentDescription(description.toString());
    }

    private void icon(Canvas canvas, float x, float y, float r, int code, int color, int background) {
        paint.setColor(color); paint.setStrokeWidth(dp(1.7f)); paint.setStrokeCap(Paint.Cap.ROUND);
        if ("Weather unavailable".equals(condition(code))) { canvas.drawLine(x - r / 2, y, x + r / 2, y, paint); return; }
        boolean sun = code <= 2;
        if (sun) {
            float sx = code == 0 ? x : x - r * .38f, sy = code == 0 ? y : y - r * .25f;
            paint.setStyle(Paint.Style.STROKE); canvas.drawCircle(sx, sy, r * .46f, paint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                canvas.drawLine(sx + (float)Math.cos(angle) * r * .67f, sy + (float)Math.sin(angle) * r * .67f,
                        sx + (float)Math.cos(angle) * r * .9f, sy + (float)Math.sin(angle) * r * .9f, paint);
            }
        }
        if (code > 0) {
            Path cloud = new Path(); cloud.moveTo(x - r * .8f, y + r * .48f);
            cloud.cubicTo(x - r * 1.3f, y + r * .2f, x - r * .8f, y - r * .6f, x - r * .3f, y - r * .35f);
            cloud.cubicTo(x - r * .3f, y - r, x + r * .8f, y - r * .8f, x + r * .72f, y - r * .15f);
            cloud.cubicTo(x + r * 1.3f, y - r * .1f, x + r * 1.1f, y + r * .5f, x + r * .65f, y + r * .5f);
            cloud.close();
            paint.setColor(background); paint.setStyle(Paint.Style.FILL); canvas.drawPath(cloud, paint);
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); canvas.drawPath(cloud, paint);
            if (code == 45 || code == 48) {
                canvas.drawLine(x - r * .9f, y + r * .8f, x + r * .9f, y + r * .8f, paint);
                canvas.drawLine(x - r * .7f, y + r * 1.1f, x + r * .7f, y + r * 1.1f, paint);
            } else if (code >= 95) {
                Path bolt = new Path(); bolt.moveTo(x + r * .1f, y + r * .48f); bolt.lineTo(x - r * .3f, y + r * .95f);
                bolt.lineTo(x + r * .1f, y + r * .95f); bolt.lineTo(x - r * .1f, y + r * 1.3f); canvas.drawPath(bolt, paint);
            } else if (code >= 51) {
                for (int i = -1; i <= 1; i++) {
                    float sx = x + i * r * .55f;
                    if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
                        canvas.drawLine(sx - r * .12f, y + r * .9f, sx + r * .12f, y + r * .9f, paint);
                        canvas.drawLine(sx, y + r * .76f, sx, y + r * 1.04f, paint);
                    } else canvas.drawLine(sx + r * .08f, y + r * .75f, sx - r * .08f, y + r * 1.08f, paint);
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
