package local.cc3.manager;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only, optional weather. No location, IP lookup or automatic request while disabled. */
final class WeatherRepository implements java.io.Closeable {
    static final String ENABLED = "weather_enabled";
    static final String AUTOMATIC = "weather_automatic";
    static final String PLACE = "weather_saved_place";
    static final String FAHRENHEIT = "weather_fahrenheit";
    static final String AUTO_CONSENT = "weather_automatic_consent";
    static final long FRESH_MS = WeatherRefreshPolicy.INTERVAL;
    static final long STALE_MS = 24 * 60 * 60 * 1000L;
    private static final long LOCATION_MAX_AGE_MS = 5 * 60 * 1000L;
    private static final int MAX_RESPONSE = 256 * 1024;

    interface Listener { void onWeather(State state); }
    interface PlaceListener { void onPlace(Place place, boolean fallback); }
    interface SearchListener { void onResults(List<Place> places, String error); }

    static final class Place {
        final String name, region, country;
        final double latitude, longitude;
        Place(String name, String region, String country, double latitude, double longitude) {
            if (!validCoordinates(latitude, longitude)) throw new IllegalArgumentException("Invalid coordinates");
            this.name = clean(name, 100); this.region = clean(region, 100); this.country = clean(country, 100);
            this.latitude = rounded(latitude); this.longitude = rounded(longitude);
        }
        String label() {
            String label = name;
            if (!region.isEmpty() && !region.equals(name)) label += ", " + region;
            if (!country.isEmpty() && !country.equals(region)) label += ", " + country;
            return label;
        }
        String key() { return String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude); }
        JSONObject json() throws JSONException {
            return new JSONObject().put("name", name).put("region", region).put("country", country)
                    .put("latitude", latitude).put("longitude", longitude);
        }
        static Place parse(JSONObject object) throws JSONException {
            return new Place(object.optString("name", ""), object.optString("region", ""),
                    object.optString("country", ""), object.getDouble("latitude"), object.getDouble("longitude"));
        }
    }

    static final class Day {
        final String date;
        final int code;
        final double high, low, uv, rainProbability;
        Day(String date, int code, double high, double low) {
            this(date, code, high, low, Double.NaN, Double.NaN);
        }
        Day(String date, int code, double high, double low, double uv, double rainProbability) {
            LocalDate.parse(date);
            this.date = date; this.code = code; this.high = high; this.low = low;
            this.uv = uv; this.rainProbability = rainProbability;
        }
    }

    static final class Current {
        final double humidity, wind, direction;
        Current(double humidity, double wind, double direction) {
            this.humidity=humidity; this.wind=wind; this.direction=direction;
        }
        static Current unknown() { return new Current(Double.NaN,Double.NaN,Double.NaN); }
    }

    static final class Forecast {
        final Place place;
        final String timezone;
        final List<Day> days;
        final long fetchedAt;
        final Current current;
        Forecast(Place place, String timezone, List<Day> days, long fetchedAt) {
            this(place,timezone,days,fetchedAt,Current.unknown());
        }
        Forecast(Place place, String timezone, List<Day> days, long fetchedAt, Current current) {
            this.place = place; this.timezone = timezone;
            this.days = Collections.unmodifiableList(new ArrayList<>(days)); this.fetchedAt = fetchedAt;
            this.current=current;
        }
        boolean usable(long now) { return fetchedAt <= now + 60_000L && now - fetchedAt <= STALE_MS && !days.isEmpty(); }
    }

    static final class State {
        final Forecast forecast;
        final String status;
        final boolean loading, fallback;
        State(Forecast forecast, String status, boolean loading, boolean fallback) {
            this.forecast = forecast; this.status = status; this.loading = loading; this.fallback = fallback;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final PlaceListener placeListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cc3-weather"); thread.setDaemon(true); return thread;
    });
    private final Object networkLock = new Object();
    private volatile HttpURLConnection request;
    private volatile boolean active, closed;
    private volatile int generation;
    private volatile int searchGeneration;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean locating;
    private Forecast cache;
    private static boolean startupRefreshCompleted;
    private android.net.ConnectivityManager connectivity;
    private android.net.ConnectivityManager.NetworkCallback connectionCallback;
    private boolean needsNetworkRefresh;
    private int failedUpdates;
    private State state = new State(null, "Weather is off", false, false);
    private final Runnable refreshTick = new Runnable() {
        @Override public void run() { if (active && !closed) refresh(needsNetworkRefresh); }
    };
    private final Runnable connectionRefresh = () -> {
        if (WeatherRefreshPolicy.networkRefresh(active&&!closed,state.loading,needsNetworkRefresh,
                cache==null?0:cache.fetchedAt,System.currentTimeMillis())) refresh(true);
    };

    WeatherRepository(Context context, Listener listener) {
        this(context, listener, null);
    }

    /** Resolve the same consented foreground place without requesting forecast data. */
    WeatherRepository(Context context, Listener listener, PlaceListener placeListener) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("cc3_manager", Context.MODE_PRIVATE);
        this.listener = listener;
        this.placeListener = placeListener;
    }

    static boolean enabled(SharedPreferences prefs) { return prefs.getBoolean(ENABLED, false); }
    static boolean automatic(SharedPreferences prefs) { return prefs.getBoolean(AUTOMATIC, false) && consented(prefs); }
    static boolean consented(SharedPreferences prefs) { return prefs.getBoolean(AUTO_CONSENT, false); }
    static boolean fahrenheit(SharedPreferences prefs) { return prefs.getBoolean(FAHRENHEIT, false); }
    static boolean preference(String key) {
        return ENABLED.equals(key) || AUTOMATIC.equals(key) || PLACE.equals(key) || FAHRENHEIT.equals(key) || AUTO_CONSENT.equals(key);
    }
    static void setEnabled(SharedPreferences prefs, boolean on) { prefs.edit().putBoolean(ENABLED, on).apply(); }
    static void setAutomatic(SharedPreferences prefs, boolean on) { prefs.edit().putBoolean(AUTOMATIC, on).apply(); }
    static Place savedPlace(SharedPreferences prefs) {
        try { String saved = prefs.getString(PLACE, ""); return saved.isEmpty() ? null : Place.parse(new JSONObject(saved)); }
        catch (RuntimeException | JSONException invalid) { return null; }
    }
    static void savePlace(SharedPreferences prefs, Place place) {
        try { prefs.edit().putString(PLACE, place.json().toString()).putBoolean(AUTOMATIC, false).apply(); }
        catch (JSONException impossible) { throw new IllegalArgumentException("Invalid place", impossible); }
    }
    State state() { return state; }

    void start() {
        if (closed || active) return;
        active = true;
        boolean force=false;
        if(enabled(prefs)&&(automatic(prefs)||savedPlace(prefs)!=null)){
            synchronized(WeatherRepository.class){force=!startupRefreshCompleted;}
        }
        needsNetworkRefresh=force;
        watchConnection();
        refresh(force);
    }

    private void watchConnection() {
        if (!enabled(prefs) || (!automatic(prefs) && savedPlace(prefs)==null)) return;
        try {
            connectivity=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(connectivity==null)return;
            connectionCallback=new android.net.ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(android.net.Network network){
                    main.removeCallbacks(connectionRefresh);main.postDelayed(connectionRefresh,750);
                }
                @Override public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities capabilities){
                    if(capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        main.removeCallbacks(connectionRefresh); main.postDelayed(connectionRefresh,750);
                    }
                }
            };
            connectivity.registerDefaultNetworkCallback(connectionCallback);
        } catch(RuntimeException unavailable){connectionCallback=null;}
    }

    // Android10 compatibility: this scoped platform path intentionally retains legacy APIs.
    @SuppressWarnings("deprecation")
    void refresh(boolean force) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> refresh(force)); return; }
        if (closed || !active) return;
        cancelLocation();
        int token = ++generation;
        cancelNetwork();
        if (!enabled(prefs)) { emit(new State(null, "Weather is off", false, false)); return; }
        if (!automatic(prefs)) { useSaved(token, force, false); return; }
        boolean coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!coarse && !fine) { useSaved(token, force, true); return; }
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) { useSaved(token, force, true); return; }
        List<String> providers = new ArrayList<>();
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER);
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER);
            Location best = null;
            for (String provider : providers) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (freshLocation(candidate) && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            }
            if (best != null) { load(new Place("Current location", "", "", best.getLatitude(), best.getLongitude()), token, force, false); return; }
        } catch (RuntimeException unavailable) { providers.clear(); }
        if (providers.isEmpty()) { useSaved(token, force, true); return; }
        locating = true;
        emit(new State(null, "Finding device location…", true, false));
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!current(token) || !locating || !freshLocation(location)) return;
                cancelLocation();
                load(new Place("Current location", "", "", location.getLatitude(), location.getLongitude()), token, force, false);
            }
            @Override @SuppressWarnings("deprecation") public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        try {
            for (String provider : providers) locationManager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper());
        } catch (RuntimeException unavailable) { cancelLocation(); useSaved(token, force, true); return; }
        main.postDelayed(() -> {
            if (current(token) && locating) { cancelLocation(); useSaved(token, force, true); }
        }, 8_000);
    }

    private void useSaved(int token, boolean force, boolean fallback) {
        Place saved = savedPlace(prefs);
        if (saved == null) emit(new State(null, fallback ? "Location unavailable · choose a place" : "Choose a forecast location", false, fallback));
        else load(saved, token, force, fallback);
    }

    private boolean current(int token) { return !closed && active && enabled(prefs) && token == generation; }
    private void load(Place place, int token, boolean force, boolean fallback) {
        if (!current(token)) return;
        if (placeListener != null) { placeListener.onPlace(place, fallback); return; }
        worker.execute(() -> {
            if (!current(token)) return;
            Forecast existing = cache;
            if (existing == null) existing = readCache();
            cache = existing;
            long now = System.currentTimeMillis();
            Forecast usable = existing != null && existing.place.key().equals(place.key()) && existing.usable(now)
                    ? new Forecast(place, existing.timezone, existing.days, existing.fetchedAt,existing.current) : null;
            if (!force && usable != null && now - usable.fetchedAt < FRESH_MS && coversNextSeven(usable)) {
                State ready = new State(usable, fallback ? "Using saved place · device location unavailable" : "Forecast updated", false, fallback);
                main.post(() -> { if (current(token)) {needsNetworkRefresh=false;failedUpdates=0;emit(ready);} }); return;
            }
            main.post(() -> { if (current(token)) emit(new State(usable, "Updating forecast…", true, fallback)); });
            try {
                String data = fetch(forecastUrl(place), token, false);
                Forecast forecast = parseForecast(data, place, System.currentTimeMillis());
                if (!current(token)) return;
                cache = forecast;
                writeCache(forecast);
                main.post(() -> { if (current(token)) {
                    synchronized(WeatherRepository.class){startupRefreshCompleted=true;}
                    needsNetworkRefresh=false;failedUpdates=0;emit(new State(forecast,
                        fallback ? "Using saved place · device location unavailable" : "Forecast updated", false, fallback));} });
            } catch (IOException | JSONException | RuntimeException error) {
                main.post(() -> { if (current(token)) {needsNetworkRefresh=true;emit(new State(usable,
                        usable == null ? "Forecast unavailable · try Refresh" : "Could not update · saved forecast", false, fallback));} });
            }
        });
    }

    void search(String query, SearchListener callback) {
        final String text = clean(query, 100);
        int token = ++searchGeneration;
        if (closed || !enabled(prefs)) { callback.onResults(Collections.emptyList(), "Enable weather before searching for a place."); return; }
        if (text.length() < 2) { callback.onResults(Collections.emptyList(), "Enter at least two letters or a postcode."); return; }
        worker.execute(() -> {
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(text, "UTF-8") + "&count=6&language=en&format=json";
                String data = fetch(url, token, true);
                List<Place> places = parsePlaces(data);
                main.post(() -> { if (!closed && token == searchGeneration && enabled(prefs)) callback.onResults(places,
                        places.isEmpty() ? "No places found. Try a nearby town or add the country." : ""); });
            } catch (IOException | JSONException | RuntimeException error) {
                main.post(() -> { if (!closed && token == searchGeneration && enabled(prefs)) callback.onResults(Collections.emptyList(), "Place search could not connect. Try again."); });
            }
        });
    }

    private String fetch(String address, int token, boolean search) throws IOException {
        if (closed || !enabled(prefs) || (search ? token != searchGeneration : !current(token))) throw new IOException("Cancelled");
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || !("api.open-meteo.com".equals(url.getHost())
                || "geocoding-api.open-meteo.com".equals(url.getHost()))) throw new IOException("Unknown weather provider");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(8_000); connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "AVCManager/0.5.1 (personal forecast)");
        synchronized (networkLock) { request = connection; }
        try {
            if (closed || !enabled(prefs) || (search ? token != searchGeneration : !current(token))) throw new IOException("Cancelled");
            if (connection.getResponseCode() != 200) throw new IOException("Weather request failed");
            try (InputStream input = connection.getInputStream()) { return readBounded(input, MAX_RESPONSE); }
        } finally {
            connection.disconnect();
            synchronized (networkLock) { if (request == connection) request = null; }
        }
    }

    static String forecastUrl(Place place) {
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                + "&current=relative_humidity_2m,wind_speed_10m,wind_direction_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,uv_index_max,precipitation_probability_max"
                + "&forecast_days=7&temperature_unit=celsius&wind_speed_unit=kmh&timezone=auto", place.latitude, place.longitude);
    }
    static Forecast parseForecast(String response, Place place, long fetchedAt) throws JSONException {
        JSONObject root = new JSONObject(response);
        if (root.optBoolean("error", false)) throw new JSONException("Forecast error");
        JSONObject daily = root.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time"), codes = daily.getJSONArray("weather_code"),
                highs = daily.getJSONArray("temperature_2m_max"), lows = daily.getJSONArray("temperature_2m_min");
        int length = Math.min(7, dates.length());
        if (length < 1 || codes.length() < length || highs.length() < length || lows.length() < length) throw new JSONException("Incomplete daily forecast");
        List<Day> days = new ArrayList<>();
        try {
            LocalDate first = LocalDate.parse(dates.getString(0));
            for (int i = 0; i < length; i++) {
                String date = dates.getString(i);
                if (!first.plusDays(i).toString().equals(date)) throw new JSONException("Unordered daily forecast");
                days.add(new Day(date, codes.isNull(i) ? -1 : codes.getInt(i), temperature(highs, i), temperature(lows, i),
                        optional(daily.optJSONArray("uv_index_max"),i,0,30),
                        optional(daily.optJSONArray("precipitation_probability_max"),i,0,100)));
            }
        } catch (RuntimeException invalid) { throw new JSONException("Invalid forecast date"); }
        JSONObject current=root.optJSONObject("current");
        Current conditions=current==null?Current.unknown():new Current(
                bounded(current.optDouble("relative_humidity_2m",Double.NaN),0,100),
                bounded(current.optDouble("wind_speed_10m",Double.NaN),0,500),
                bounded(current.optDouble("wind_direction_10m",Double.NaN),0,360));
        return new Forecast(place, root.optString("timezone", ""), days, fetchedAt,conditions);
    }
    private static double bounded(double value,double low,double high){return Double.isFinite(value)&&value>=low&&value<=high?value:Double.NaN;}
    private static double optional(JSONArray values,int index,double low,double high){
        return values==null||index>=values.length()||values.isNull(index)?Double.NaN:bounded(values.optDouble(index,Double.NaN),low,high);
    }
    static List<Place> parsePlaces(String response) throws JSONException {
        JSONObject root = new JSONObject(response);
        if (root.optBoolean("error", false)) throw new JSONException("Geocoding error");
        JSONArray results = root.optJSONArray("results");
        List<Place> places = new ArrayList<>();
        if (results == null) return places;
        for (int i = 0; i < Math.min(6, results.length()); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            try {
                Place place = new Place(item.getString("name"), item.optString("admin1", ""), item.optString("country", ""),
                        item.getDouble("latitude"), item.getDouble("longitude"));
                if (!place.name.isEmpty()) places.add(place);
            } catch (IllegalArgumentException | JSONException invalid) { /* Preserve the other valid place results. */ }
        }
        return Collections.unmodifiableList(places);
    }
    private static double temperature(JSONArray values, int i) throws JSONException {
        if (values.isNull(i)) return Double.NaN;
        double value = values.getDouble(i);
        return Double.isNaN(value) || Double.isInfinite(value) || value < -100 || value > 70 ? Double.NaN : value;
    }

    private Forecast readCache() {
        File file = new File(context.getFilesDir(), "weather-forecast.json");
        if (!file.isFile() || file.length() > MAX_RESPONSE) return null;
        try (InputStream input = new FileInputStream(file)) {
            JSONObject saved = new JSONObject(readBounded(input, MAX_RESPONSE));
            Place place = Place.parse(saved.getJSONObject("place"));
            return parseForecast(saved.getJSONObject("response").toString(), place, saved.getLong("fetched_at"));
        } catch (IOException | JSONException | RuntimeException invalid) { return null; }
    }
    private void writeCache(Forecast forecast) {
        File temporary = new File(context.getFilesDir(), "weather-forecast.tmp");
        File destination = new File(context.getFilesDir(), "weather-forecast.json");
        try {
            JSONObject daily = new JSONObject();
            JSONArray dates = new JSONArray(), codes = new JSONArray(), high = new JSONArray(), low = new JSONArray(), uv=new JSONArray(), rain=new JSONArray();
            for (Day day : forecast.days) {
                dates.put(day.date); codes.put(day.code < 0 ? JSONObject.NULL : day.code);
                high.put(Double.isNaN(day.high) ? JSONObject.NULL : day.high); low.put(Double.isNaN(day.low) ? JSONObject.NULL : day.low);
                uv.put(Double.isNaN(day.uv)?JSONObject.NULL:day.uv);rain.put(Double.isNaN(day.rainProbability)?JSONObject.NULL:day.rainProbability);
            }
            daily.put("time", dates).put("weather_code", codes).put("temperature_2m_max", high).put("temperature_2m_min", low);
            daily.put("uv_index_max",uv).put("precipitation_probability_max",rain);
            JSONObject response = new JSONObject().put("timezone", forecast.timezone).put("daily", daily);
            JSONObject now=new JSONObject();
            now.put("relative_humidity_2m",Double.isNaN(forecast.current.humidity)?JSONObject.NULL:forecast.current.humidity);
            now.put("wind_speed_10m",Double.isNaN(forecast.current.wind)?JSONObject.NULL:forecast.current.wind);
            now.put("wind_direction_10m",Double.isNaN(forecast.current.direction)?JSONObject.NULL:forecast.current.direction);
            response.put("current",now);
            byte[] bytes = new JSONObject().put("place", forecast.place.json()).put("fetched_at", forecast.fetchedAt)
                    .put("response", response).toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); }
            if (!temporary.renameTo(destination)) temporary.delete();
        } catch (IOException | JSONException failure) { temporary.delete(); }
    }

    private void emit(State value) {
        state = value; if (listener != null) listener.onWeather(value);
        main.removeCallbacks(refreshTick);
        if(active && !closed && enabled(prefs) && !value.loading && (automatic(prefs)||savedPlace(prefs)!=null)) {
            long delay=WeatherRefreshPolicy.nextDelay(System.currentTimeMillis(),value.forecast==null?0:value.forecast.fetchedAt,
                    needsNetworkRefresh,failedUpdates);
            if(needsNetworkRefresh)failedUpdates++;
            main.postDelayed(refreshTick,delay);
        }
    }
    void stop() {
        active = false; ++generation; ++searchGeneration;
        main.removeCallbacks(refreshTick); main.removeCallbacks(connectionRefresh); cancelLocation(); cancelNetwork();
        if(connectivity!=null && connectionCallback!=null)try{connectivity.unregisterNetworkCallback(connectionCallback);}catch(RuntimeException ignored){}
        connectionCallback=null;connectivity=null;
    }
    private void cancelNetwork() {
        synchronized (networkLock) { if (request != null) { request.disconnect(); request = null; } }
    }
    private void cancelLocation() {
        locating = false;
        if (locationManager != null && locationListener != null) try { locationManager.removeUpdates(locationListener); }
        catch (RuntimeException ignored) { }
        locationListener = null;
    }
    @Override public void close() { if (closed) return; stop(); closed = true; main.removeCallbacksAndMessages(null); worker.shutdownNow(); }

    static boolean freshLocation(Location location) {
        if (location == null || !validCoordinates(location.getLatitude(), location.getLongitude())) return false;
        if (location.hasAccuracy() && (!Float.isFinite(location.getAccuracy()) || location.getAccuracy() > 25_000)) return false;
        long age = location.getElapsedRealtimeNanos() > 0
                ? (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000L
                : System.currentTimeMillis() - location.getTime();
        return age >= -60_000L && age <= LOCATION_MAX_AGE_MS;
    }
    static boolean validCoordinates(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }
    static boolean coversNextSeven(Forecast forecast) {
        LocalDate today;
        try { today = LocalDate.now(ZoneId.of(forecast.timezone)); }
        catch (RuntimeException invalid) { today = LocalDate.now(); }
        for (int i = 0; i < 7; i++) {
            String expected = today.plusDays(i).toString(); boolean found = false;
            for (Day day : forecast.days) if (expected.equals(day.date)) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }
    static double rounded(double value) { return Math.round(value * 100d) / 100d; }
    static String clean(String text, int limit) {
        String value = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").trim();
        return value.length() > limit ? value.substring(0, limit) : value;
    }
    static String readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Weather response too large");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
