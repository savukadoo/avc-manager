package local.cc3.manager;

import java.net.URI;
import java.util.Locale;

/** Provider boundary checks: no device location, web content or network required. */
public final class WeatherMapPolicyTest {
    private static int checks;
    private static void check(boolean value) { if (!value) throw new AssertionError("Map policy check " + checks); checks++; }
    public static void main(String[] args) {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String radar = WeatherMapPolicy.url(48.85661, 2.35221, false, false);
            check(radar.contains("lat=48.86&lon=2.35"));
            check(!radar.contains("85661") && !radar.contains("35221"));
            check(radar.contains("overlay=radar&product=radar"));
            check(radar.contains("metricRain=mm") && radar.contains("metricTemp=%C2%B0C"));
            check(URI.create(radar).getHost().equals("embed.windy.com"));
            check(WeatherMapPolicy.document(radar));
            String forecast = WeatherMapPolicy.url(48.86, 2.35, true, true);
            check(forecast.contains("overlay=rain&product=ecmwf"));
            check(forecast.contains("metricTemp=%C2%B0F"));
            check(!forecast.contains("apikey") && !forecast.contains("token"));
            for (String address : new String[] {null, "", "http://embed.windy.com/embed.html",
                    "https://embed.windy.com.attacker.example/embed.html", "https://windy.com/embed.html",
                    "https://embed.windy.com@attacker.example/embed.html", "https://user@embed.windy.com/embed.html",
                    "https://embed.windy.com:444/embed.html", "file:///sdcard/file.html", "content://local/file",
                    "javascript:alert(1)", "intent://windy.com", "https://embed.windy.com/elsewhere",
                    "https://embed.windy.com/%65mbed.html"}) check(!WeatherMapPolicy.document(address));
            check(WeatherMapPolicy.document("https://embed.windy.com:443/embed2.html"));
            for (String address : new String[] {"https://www.windy.com/?radar", "https://account.windy.com/agreements/windy-privacy-policy",
                    "https://www.openstreetmap.org/copyright"}) check(WeatherMapPolicy.externalLink(address));
            for (String address : new String[] {"http://windy.com", "https://windy.com.attacker.example/",
                    "https://notwindy.com/", "https://user@windy.com/", "https://windy.com:444/", "intent://windy.com"})
                check(!WeatherMapPolicy.externalLink(address));
            for (double[] point : new double[][] {{Double.NaN, 0}, {0, Double.POSITIVE_INFINITY}, {86, 0}, {0, 181}}) {
                boolean rejected = false;
                try { WeatherMapPolicy.url(point[0], point[1], false, false); }
                catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected);
            }
        } finally { Locale.setDefault(before); }
        System.out.println(checks + " weather map boundary checks passed");
    }
}
