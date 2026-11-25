package local.cc3.manager;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.SystemClock;
import android.location.Location;
import org.json.JSONException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs through Android app_process: actual platform JSON/Location/Looper classes, no device data. */
public final class WeatherRepositoryTest {
    @SuppressWarnings("deprecation")
    private static void prepareMainLoop(){if(Looper.getMainLooper()==null)Looper.prepareMainLooper();}
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static SharedPreferences preferences(Map<String, Object> values) {
        final SharedPreferences.Editor[] editor = new SharedPreferences.Editor[1];
        editor[0] = (SharedPreferences.Editor) Proxy.newProxyInstance(WeatherRepositoryTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) { values.put((String) args[0], args[1]); return editor[0]; }
                    if (name.equals("remove")) { values.remove(args[0]); return editor[0]; }
                    if (name.equals("clear")) { values.clear(); return editor[0]; }
                    if (name.equals("commit")) return true;
                    return null;
                });
        return (SharedPreferences) Proxy.newProxyInstance(WeatherRepositoryTest.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("edit")) return editor[0];
                    if (name.equals("getAll")) return new HashMap<>(values);
                    if (name.equals("contains")) return values.containsKey(args[0]);
                    if (name.startsWith("get") && args != null && args.length == 2) return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
                    return null;
                });
    }
    private static String response(LocalDate start, String highs, String codes, int days) {
        StringBuilder dates = new StringBuilder();
        for (int i = 0; i < days; i++) { if (i > 0) dates.append(','); dates.append('"').append(start.plusDays(i)).append('"'); }
        return "{\"timezone\":\"Europe/Paris\",\"daily\":{\"time\":[" + dates + "],\"weather_code\":" + codes
                + ",\"temperature_2m_max\":" + highs + ",\"temperature_2m_min\":[18,19,20,18,17,20,21]}}";
    }
    public static void main(String[] args) throws Exception {
        WeatherRepository.Place paris = new WeatherRepository.Place("Paris", "Île-de-France", "France", 48.8566, 2.3522);
        check(paris.latitude == 48.86 && paris.longitude == 2.35, "Location coordinates rounded before URL or persistence");
        check(paris.label().equals("Paris, Île-de-France, France"), "Place label disambiguates region and country");
        String url = WeatherRepository.forecastUrl(paris);
        check(url.contains("latitude=48.86&longitude=2.35") && !url.contains("8566"), "Forecast URL contains rounded coordinates only");
        check(url.contains("forecast_days=7") && url.contains("weather_code,temperature_2m_max,temperature_2m_min"), "Request selects seven daily conditions and highs/lows");
        check(url.contains("relative_humidity_2m,wind_speed_10m,wind_direction_10m")&&url.contains("uv_index_max,precipitation_probability_max")&&url.contains("wind_speed_unit=kmh"),"Request includes current humidity/wind and daily UV/rain probability with explicit wind units");
        check(!WeatherRepository.validCoordinates(Double.NaN, 0) && !WeatherRepository.validCoordinates(0, Double.POSITIVE_INFINITY)
                && !WeatherRepository.validCoordinates(91, 0) && !WeatherRepository.validCoordinates(0, -181), "Invalid coordinates rejected");
        Location fix = new Location("isolated-test"); fix.setLatitude(48.86); fix.setLongitude(2.35); fix.setAccuracy(30);
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - 60_000_000_000L);
        check(WeatherRepository.freshLocation(fix), "A recent synthetic device fix is accepted");
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - 360_000_000_000L);
        check(!WeatherRepository.freshLocation(fix), "A six-minute-old travel location is rejected");
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() + 120_000_000_000L);
        check(!WeatherRepository.freshLocation(fix), "A future-dated location is rejected");
        Map<String, Object> values = new HashMap<>(); SharedPreferences prefs = preferences(values);
        check(!WeatherRepository.enabled(prefs) && !WeatherRepository.automatic(prefs), "Weather defaults off and automatic location unconfigured");
        values.put(WeatherRepository.AUTOMATIC, true);
        check(!WeatherRepository.automatic(prefs), "Stored automatic flag alone is not weather location consent");
        values.put(WeatherRepository.AUTO_CONSENT, true);
        check(WeatherRepository.automatic(prefs), "Separate consent permits an explicitly selected automatic mode");
        WeatherRepository.savePlace(prefs, paris);
        check(!WeatherRepository.automatic(prefs), "Choosing a saved place selects manual mode");
        check(WeatherRepository.savedPlace(prefs).key().equals(paris.key()), "Saved place round-trips without exact coordinates");
        values.put(WeatherRepository.PLACE, "broken json"); check(WeatherRepository.savedPlace(prefs) == null, "Corrupt saved place becomes unavailable"); values.remove(WeatherRepository.PLACE);
        check(!WeatherRepository.preference("weather_cache") && WeatherRepository.preference(WeatherRepository.AUTO_CONSENT), "Only settings keys trigger Home layout changes");
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris")); long now = System.currentTimeMillis();
        String json = response(today, "[28,29,null,30,31,27,28]", "[0,1,null,61,95,3,2]", 7);
        WeatherRepository.Forecast forecast = WeatherRepository.parseForecast(json, paris, now);
        check(forecast.days.size() == 7 && forecast.days.get(3).code == 61, "Seven real daily weather codes parsed");
        check(Double.isNaN(forecast.days.get(2).high) && forecast.days.get(2).code == -1, "Missing temperature or code never becomes zero or a fake condition");
        check(forecast.days.get(0).high == 28 && forecast.days.get(0).low == 18, "Daily high and low preserved");
        check(Double.isNaN(forecast.current.humidity)&&Double.isNaN(forecast.days.get(0).uv)&&Double.isNaN(forecast.days.get(0).rainProbability),"Older caches keep absent extended weather values unknown");
        org.json.JSONObject extended=new org.json.JSONObject(json);
        extended.put("current",new org.json.JSONObject().put("relative_humidity_2m",76).put("wind_speed_10m",13.5).put("wind_direction_10m",225));
        extended.getJSONObject("daily").put("uv_index_max",new org.json.JSONArray("[8.2,null,5,7,9,9,8]")).put("precipitation_probability_max",new org.json.JSONArray("[45,10,20,30,40,50,60]"));
        WeatherRepository.Forecast detailed=WeatherRepository.parseForecast(extended.toString(),paris,now);
        check(detailed.current.humidity==76&&detailed.current.wind==13.5&&detailed.current.direction==225&&detailed.days.get(0).uv==8.2&&detailed.days.get(0).rainProbability==45,"Extended provider values retain their scopes and values");
        extended.getJSONObject("current").put("relative_humidity_2m",150).put("wind_speed_10m",-1).put("wind_direction_10m",400);
        extended.getJSONObject("daily").put("uv_index_max",new org.json.JSONArray("[99]")).put("precipitation_probability_max",new org.json.JSONArray("[-5]"));
        detailed=WeatherRepository.parseForecast(extended.toString(),paris,now);
        check(Double.isNaN(detailed.current.humidity)&&Double.isNaN(detailed.current.wind)&&Double.isNaN(detailed.current.direction)&&Double.isNaN(detailed.days.get(0).uv)&&Double.isNaN(detailed.days.get(0).rainProbability)&&Double.isNaN(detailed.days.get(1).uv),"Out-of-range and incomplete extended arrays remain unknown without breaking the forecast");
        check(forecast.usable(now + WeatherRepository.STALE_MS) && !forecast.usable(now + WeatherRepository.STALE_MS + 1), "Saved data is hidden after 24 hours");
        check(!forecast.usable(now - 60_001), "Implausibly future-dated cache is not live data");
        check(WeatherRepository.coversNextSeven(forecast), "Fresh seven-day coverage accepted");
        WeatherRepository.Forecast yesterday = WeatherRepository.parseForecast(response(today.minusDays(1), "[28,29,30,30,31,27,28]", "[0,1,2,61,95,3,2]", 7), paris, now);
        check(!WeatherRepository.coversNextSeven(yesterday), "Cross-midnight cache is refreshed rather than beginning with yesterday");
        try { WeatherRepository.parseForecast(json.replace("[0,1,null,61,95,3,2]", "[0]"), paris, now); throw new AssertionError("Accepted truncated codes"); }
        catch (JSONException expected) { check(true, "Truncated forecast rejected"); }
        try { WeatherRepository.parseForecast(json.replace(today.plusDays(1).toString(), "not-a-date"), paris, now); throw new AssertionError("Accepted bad date"); }
        catch (JSONException expected) { check(true, "Malformed dates rejected"); }
        String places = "{\"results\":[{\"name\":\"Paris\",\"admin1\":\"Île-de-France\",\"country\":\"France\",\"latitude\":48.8566,\"longitude\":2.3522},{\"name\":\"Broken\",\"latitude\":200,\"longitude\":0},{\"name\":\"Missing coordinates\"},null]}";
        check(WeatherRepository.parsePlaces(places).size() == 1, "Malformed geocoding entries do not remove valid disambiguated results");
        check(WeatherRepository.parsePlaces("{}").isEmpty(), "Empty place results handled");
        byte[] bytes = "forecast".getBytes(StandardCharsets.UTF_8);
        check(WeatherRepository.readBounded(new ByteArrayInputStream(bytes), bytes.length).equals("forecast"), "Bounded response reader accepts exact limit");
        try { WeatherRepository.readBounded(new ByteArrayInputStream(bytes), bytes.length - 1); throw new AssertionError("Accepted oversized response"); }
        catch (IOException expected) { check(true, "Oversized provider response rejected"); }
        prepareMainLoop();
        AtomicInteger services = new AtomicInteger(), permissions = new AtomicInteger();
        Context fakeContext = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
            @Override public Object getSystemService(String name) { services.incrementAndGet(); throw new AssertionError("Unexpected location service access"); }
            @Override public int checkSelfPermission(String permission) { permissions.incrementAndGet(); return PackageManager.PERMISSION_GRANTED; }
        };
        values.clear(); WeatherRepository repository = new WeatherRepository(fakeContext, state -> {}); repository.start();
        check(repository.state().status.equals("Weather is off") && services.get() == 0 && permissions.get() == 0, "Disabled runtime does not query permissions, location or a provider");
        repository.search("Paris", (found, error) -> check(!error.isEmpty() && found.isEmpty(), "Disabled weather does not perform even an explicit place search")); repository.close();
        values.put(WeatherRepository.ENABLED, true); values.put(WeatherRepository.AUTOMATIC, true);
        WeatherRepository noConsent = new WeatherRepository(fakeContext, state -> {}); noConsent.start();
        check(noConsent.state().status.contains("Choose") && services.get() == 0 && permissions.get() == 0, "Existing granted location permission cannot bypass separate weather consent"); noConsent.close();
        values.clear(); values.put(WeatherRepository.ENABLED, true); WeatherRepository.savePlace(prefs, paris);
        Context mapContext = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
            @Override public Object getSystemService(String name) {
                if (Context.CONNECTIVITY_SERVICE.equals(name)) return null;
                throw new AssertionError("Location-only saved place must not request device services");
            }
            @Override public java.io.File getFilesDir() { throw new AssertionError("Location-only mode must not access forecast cache"); }
        };
        WeatherRepository.Place[] resolved = new WeatherRepository.Place[1];
        WeatherRepository locationOnly = new WeatherRepository(mapContext, state -> {}, (point, fallback) -> resolved[0] = point);
        locationOnly.start();
        check(resolved[0] != null && resolved[0].key().equals(paris.key()), "Location-only resolver returns the saved rounded place without forecast/cache requests"); locationOnly.close();
        values.remove(WeatherRepository.PLACE); resolved[0] = null;
        WeatherRepository noPlace = new WeatherRepository(mapContext, state -> {}, (point, fallback) -> resolved[0] = point); noPlace.start();
        check(resolved[0] == null && noPlace.state().status.contains("Choose"), "Missing map location asks for a place instead of substituting coordinates"); noPlace.close();
        values.put(WeatherRepository.ENABLED, false); WeatherRepository.savePlace(prefs, paris);
        WeatherRepository mapOff = new WeatherRepository(mapContext, state -> {}, (point, fallback) -> resolved[0] = point); mapOff.start();
        check(resolved[0] == null && mapOff.state().status.equals("Weather is off"), "Weather off also gates the location-only resolver"); mapOff.close();
        System.out.println("WEATHER CHECKS PASSED: " + checks);
    }
}
