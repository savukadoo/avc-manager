package local.cc3.manager;

import android.content.Context;
import android.net.InetAddresses;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class IpDetails {
    private IpDetails() { }

    static String reverseDns(String ip) {
        InetAddress address = InetAddresses.parseNumericAddress(ip);
        String name = address.getCanonicalHostName();
        return InetAddresses.isNumericAddress(name) ? "No hostname found" : name;
    }

    static String registration(Context context, String ip) throws IOException, JSONException {
        InetAddress address = InetAddresses.parseNumericAddress(ip);
        URL url = new URL(registry(context, address.getAddress()) + "ip/" + address.getHostAddress());
        for (int redirect = 0; redirect < 4; redirect++) {
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) {
                throw new IOException("Registry did not provide a secure lookup.");
            }
            HttpsURLConnection request = (HttpsURLConnection) url.openConnection();
            request.setConnectTimeout(5000);
            request.setReadTimeout(5000);
            request.setInstanceFollowRedirects(false);
            request.setRequestProperty("Accept", "application/rdap+json");
            try {
                int code = request.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = request.getHeaderField("Location");
                    if (location == null) break;
                    url = new URL(url, location);
                    continue;
                }
                if (code == 404) return "No registration record found";
                if (code != 200) throw new IOException("Registry returned HTTP " + code);
                try (InputStream body = request.getInputStream()) {
                    return describe(new JSONObject(read(body, 512 * 1024)), url.getHost());
                }
            } finally {
                request.disconnect();
            }
        }
        throw new IOException("Registry redirected the lookup too many times.");
    }

    private static String registry(Context context, byte[] address) throws IOException, JSONException {
        String file = address.length == 4 ? "rdap-ipv4.json" : "rdap-ipv6.json";
        JSONArray services;
        try (InputStream asset = context.getAssets().open(file)) {
            services = new JSONObject(read(asset, 64 * 1024)).getJSONArray("services");
        }
        int bestLength = -1;
        String best = null;
        for (int i = 0; i < services.length(); i++) {
            JSONArray service = services.getJSONArray(i);
            JSONArray networks = service.getJSONArray(0);
            JSONArray urls = service.getJSONArray(1);
            String https = null;
            for (int j = 0; j < urls.length(); j++) {
                String candidate = urls.getString(j);
                if (candidate.startsWith("https://")) {
                    https = candidate;
                    break;
                }
            }
            if (https == null) continue;
            for (int j = 0; j < networks.length(); j++) {
                String[] parts = networks.getString(j).split("/");
                int prefix = Integer.parseInt(parts[1]);
                if (prefix > bestLength && contains(address,
                        InetAddresses.parseNumericAddress(parts[0]).getAddress(), prefix)) {
                    best = https;
                    bestLength = prefix;
                }
            }
        }
        if (best == null) throw new IOException("No registry listed for this address.");
        return best.endsWith("/") ? best : best + "/";
    }

    private static boolean contains(byte[] address, byte[] network, int prefix) {
        if (address.length != network.length || prefix > address.length * 8) return false;
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (address[i] != network[i]) return false;
        }
        int partial = prefix % 8;
        return partial == 0 || (((address[full] ^ network[full]) & 255) >>> (8 - partial)) == 0;
    }

    private static String describe(JSONObject record, String server) {
        StringBuilder output = new StringBuilder();
        field(output, "Network", record.optString("name", record.optString("handle", "")));
        String registrant = registrant(record.optJSONArray("entities"));
        field(output, "Registrant", registrant);
        String start = record.optString("startAddress");
        String end = record.optString("endAddress");
        if (!start.isEmpty() && !end.isEmpty()) field(output, "Range", start + " – " + end);
        field(output, "Registration country", record.optString("country"));
        field(output, "Registry", server);
        return output.toString();
    }

    private static String registrant(JSONArray entities) {
        if (entities == null) return "";
        for (int i = 0; i < entities.length(); i++) {
            JSONObject entity = entities.optJSONObject(i);
            if (entity == null) continue;
            JSONArray roles = entity.optJSONArray("roles");
            if (roles == null) continue;
            for (int role = 0; role < roles.length(); role++) {
                if (!"registrant".equals(roles.optString(role))) continue;
                JSONArray vcard = entity.optJSONArray("vcardArray");
                JSONArray properties = vcard == null ? null : vcard.optJSONArray(1);
                if (properties == null) continue;
                for (int p = 0; p < properties.length(); p++) {
                    JSONArray property = properties.optJSONArray(p);
                    if (property != null && "fn".equals(property.optString(0))) return property.optString(3);
                }
            }
        }
        return "";
    }

    private static void field(StringBuilder output, String label, String value) {
        if (value.isEmpty()) return;
        if (output.length() > 0) output.append('\n');
        String compact = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        output.append(label).append("  ·  ").append(compact.substring(0, Math.min(180, compact.length())));
    }

    private static String read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (bytes.size() + size > limit) throw new IOException("Lookup response is too large.");
            bytes.write(buffer, 0, size);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
