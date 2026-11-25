package local.cc3.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.InetAddresses;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TrafficLog {
    private static final int MAX_ROWS = 240;
    private static final String IP_KEY = "firewall_ips";
    private static final String APP_KEY = "firewall_apps";
    private static final long[][] LOCAL_IPV4 = {
        {0x00000000L, 0x01000000L}, {0x0a000000L, 0x0b000000L},
        {0x64400000L, 0x64800000L}, {0x7f000000L, 0x80000000L},
        {0xa9fe0000L, 0xa9ff0000L}, {0xac100000L, 0xac200000L},
        {0xc0a80000L, 0xc0a90000L}, {0xe0000000L, 0x100000000L}
    };
    private final LinkedHashMap<Flow, Row> rows = new LinkedHashMap<>(16, 0.75f, true);
    private final HashMap<Integer, App> owners = new HashMap<>();
    private final PackageManager packages;
    private final SharedPreferences prefs;
    private volatile Rules rules;
    private volatile boolean blockUnknown;
    private long version;

    TrafficLog(Context context) {
        packages = context.getPackageManager();
        prefs = context.getSharedPreferences(Screen.STORAGE_NAME, Context.MODE_PRIVATE);
        rules = loadRules();
        blockUnknown=prefs.getBoolean("firewall_block_unknown",false);
    }

    static final class App {
        final String key;
        final String label;

        App(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    static final class Flow {
        final String ip;
        final int port;
        final int protocol;
        final int uid;
        final App app;
        private final int hash;

        Flow(String ip, int port, int protocol, int uid, App app) {
            this.ip = ip;
            this.port = port;
            this.protocol = protocol;
            this.uid = uid;
            this.app = app;
            hash = ((ip.hashCode() * 31 + port) * 31 + protocol) * 31 + uid;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Flow)) return false;
            Flow value = (Flow) other;
            return uid == value.uid && port == value.port && protocol == value.protocol
                    && ip.equals(value.ip);
        }

        @Override public int hashCode() {
            return hash;
        }

        String protocolLabel() {
            switch (protocol) {
                case 6: return "TCP " + port;
                case 17: return "UDP " + port;
                case 1: return "ICMP";
                default: return "Protocol " + protocol;
            }
        }
    }

    static final class Row {
        final Flow flow;
        long sent;
        long received;
        long denied;

        Row(Flow flow) {
            this.flow = flow;
        }

        Row(Row other) {
            this(other.flow);
            sent = other.sent;
            received = other.received;
            denied = other.denied;
        }
    }

    private static final class Rules {
        final Set<String> ips;
        final Set<String> apps;
        final int revision;

        Rules(Set<String> ips, Set<String> apps, int revision) {
            this.ips = ips;
            this.apps = apps;
            this.revision = revision;
        }
    }

    private Rules loadRules() {
        Set<String> ips = new LinkedHashSet<>(prefs.getStringSet(IP_KEY, Collections.emptySet()));
        if (!prefs.contains(IP_KEY)) {
            for (String old : prefs.getString("ip_rules", "").split("\\s+")) {
                try {
                    ips.add(publicIp(old));
                } catch (IllegalArgumentException ignored) {
                    // The previous screen may have saved an address outside the monitored routes.
                }
            }
        }
        return new Rules(ips, new LinkedHashSet<>(prefs.getStringSet(APP_KEY, Collections.emptySet())), 0);
    }

    boolean unknownBlocked(){return blockUnknown;}
    synchronized void setUnknownBlocked(boolean block){
        blockUnknown=block;prefs.edit().putBoolean("firewall_block_unknown",block).apply();
        rules=new Rules(rules.ips,rules.apps,rules.revision+1);version++;
    }

    int revision() {
        return rules.revision;
    }

    boolean ipBlocked(String ip) {
        return rules.ips.contains(ip);
    }

    boolean appBlocked(String pkg) {
        return rules.apps.contains(pkg);
    }

    boolean blocked(Flow flow) {
        Rules snapshot = rules;
        return snapshot.ips.contains(flow.ip) || snapshot.apps.contains(flow.app.key)
                || blockUnknown && flow.app.key==null;
    }

    synchronized void setIp(String ip, boolean block) {
        Set<String> ips = new LinkedHashSet<>(rules.ips);
        if (block && ips.size() >= 512 && !ips.contains(ip)) {
            throw new IllegalStateException("Remove an IP rule before adding another.");
        }
        if (block) ips.add(ip);
        else ips.remove(ip);
        save(ips, rules.apps);
    }

    synchronized void setApp(String pkg, boolean block) {
        Set<String> apps = new LinkedHashSet<>(rules.apps);
        if (block) apps.add(pkg);
        else apps.remove(pkg);
        save(rules.ips, apps);
    }

    private void save(Set<String> ips, Set<String> apps) {
        prefs.edit().putStringSet(IP_KEY, ips).putStringSet(APP_KEY, apps).apply();
        rules = new Rules(ips, apps, rules.revision + 1);
        version++;
    }

    List<String> ipRules() {
        return new ArrayList<>(rules.ips);
    }

    List<String> appRules() {
        return new ArrayList<>(rules.apps);
    }

    String appLabel(String pkg) {
        try {
            ApplicationInfo info = packages.getApplicationInfo(pkg, 0);
            return info.loadLabel(packages).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return pkg;
        }
    }

    synchronized App owner(int uid) {
        App cached = owners.get(uid);
        if (cached != null) return cached;
        String[] names = uid < 0 ? null : packages.getPackagesForUid(uid);
        App result;
        if (uid == 1000) result = new App(null, "Android & TEYES services");
        else if (names == null || names.length == 0) {
            result = new App(null, uid < 0 ? "Unknown app" : "Android UID " + uid);
        } else if (names.length > 1) {
            result = new App(null, "Shared Android UID " + uid);
        } else {
            result = new App(names[0], appLabel(names[0]));
        }
        owners.put(uid, result);
        return result;
    }

    synchronized void record(Flow flow, int size, boolean outgoing, boolean denied) {
        Row row = rows.computeIfAbsent(flow, Row::new);
        if (rows.size() > MAX_ROWS) rows.remove(rows.keySet().iterator().next());
        if (denied) row.denied++;
        else if (outgoing) row.sent += size;
        else row.received += size;
        version++;
    }

    synchronized List<Row> snapshot() {
        List<Row> copy = new ArrayList<>(rows.size());
        for (Row row : rows.values()) copy.add(new Row(row));
        Collections.reverse(copy);
        return copy;
    }

    synchronized void reset() {
        rows.clear();
        owners.clear();
        version++;
    }

    synchronized long version() {
        return version;
    }

    static String publicIp(String text) {
        if (!InetAddresses.isNumericAddress(text) || text.contains("%")) {
            throw new IllegalArgumentException("Enter a public IPv4 or IPv6 address.");
        }
        InetAddress address = InetAddresses.parseNumericAddress(text);
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            long number = ((bytes[0] & 255L) << 24) | ((bytes[1] & 255L) << 16)
                    | ((bytes[2] & 255L) << 8) | (bytes[3] & 255L);
            for (long[] range : LOCAL_IPV4) {
                if (number >= range[0] && number < range[1]) {
                    throw new IllegalArgumentException("Local addresses bypass the firewall.");
                }
            }
        } else if ((bytes[0] & 0xe0) != 0x20) {
            byte[] nat64 = {0, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};
            for (int i = 0; i < nat64.length; i++) {
                if (bytes[i] != nat64[i]) throw new IllegalArgumentException("Enter a public IPv6 address.");
            }
        }
        return address.getHostAddress();
    }

    interface Ipv4Route {
        void add(String address, int prefix);
    }

    static void addIpv4Routes(Ipv4Route routes) {
        long next = 0;
        for (long[] range : LOCAL_IPV4) {
            while (next < range[0]) {
                long block = Math.min(Long.lowestOneBit(next), Long.highestOneBit(range[0] - next));
                String address = (next >>> 24) + "." + ((next >>> 16) & 255) + "."
                        + ((next >>> 8) & 255) + "." + (next & 255);
                routes.add(address, 32 - Long.numberOfTrailingZeros(block));
                next += block;
            }
            next = range[1];
        }
    }
}
