package local.cc3.manager;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Fixed provider URLs and navigation boundaries; no keys, arbitrary URLs or native bridge. */
final class WeatherMapPolicy {
    static final String CONSENT = "weather_map_windy_consent_v1";

    static String url(double latitude, double longitude, boolean forecast, boolean fahrenheit) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 85 || Math.abs(longitude) > 180)
            throw new IllegalArgumentException("Map location is outside the supported range");
        return String.format(Locale.ROOT,
                "https://embed.windy.com/embed.html?type=map&location=coordinates"
                        + "&metricRain=mm&metricTemp=%s&metricWind=km%%2Fh&zoom=8"
                        + "&overlay=%s&product=%s&level=surface&lat=%.2f&lon=%.2f&message=true",
                fahrenheit ? "%C2%B0F" : "%C2%B0C",
                forecast ? "rain" : "radar", forecast ? "ecmwf" : "radar", latitude, longitude);
    }

    static boolean document(String address) {
        URI uri = secure(address);
        return uri != null && "embed.windy.com".equalsIgnoreCase(uri.getHost())
                && ("/embed.html".equals(uri.getRawPath()) || "/embed2.html".equals(uri.getRawPath()));
    }

    static boolean externalLink(String address) {
        URI uri = secure(address);
        if (uri == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("windy.com") || host.endsWith(".windy.com")
                || host.equals("openstreetmap.org") || host.equals("www.openstreetmap.org");
    }

    private static URI secure(String address) {
        if (address == null) return null;
        try {
            URI uri = new URI(address);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    ? uri : null;
        } catch (URISyntaxException invalid) { return null; }
    }

    private WeatherMapPolicy() {}
}
