package io.archud;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds devices on the local network once a minute while something is on screen to show them.
 * Local addresses only (see NetPolicy). Sources: ping sweep, the ARP table, reverse DNS from the
 * router, UPnP/SSDP descriptions, and the router's own UPnP status (uptime, public IP).
 */
final class NetScanner {
    interface Listener {
        void onDevices(List<Device> devices);
    }

    static final class Device {
        final String ip;
        volatile String mac = "";
        volatile String name = "";
        volatile String vendor = "";
        volatile String kind = "";
        volatile String model = "";
        volatile long firstSeen;
        volatile long lastSeen;
        volatile boolean isNew;
        volatile boolean self;
        volatile boolean gateway;

        Device(String ip) {
            this.ip = ip;
        }

        String display() {
            if (!name.isEmpty()) {
                return name;
            }
            if (!model.isEmpty()) {
                return model;
            }
            return vendor.isEmpty() ? "UNKNOWN DEVICE" : vendor;
        }
    }

    private static final long PERIOD_MS = 60_000;
    private static final long ONLINE_WINDOW_MS = 3 * PERIOD_MS;
    private static final int MAX_HOSTS = 1022;
    private static final String KNOWN = "known_macs";
    private static NetScanner instance;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Device> byIp = new ConcurrentHashMap<>();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Map<String, String> vendorCache = new ConcurrentHashMap<>();
    private volatile boolean scanning;
    volatile String routerUptime;
    volatile String publicIp;
    volatile long lastScan;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (listeners.isEmpty()) {
                return;
            }
            startScan();
            main.postDelayed(this, PERIOD_MS);
        }
    };

    private NetScanner(Context c) {
        ctx = c.getApplicationContext();
    }

    static synchronized NetScanner get(Context c) {
        if (instance == null) {
            instance = new NetScanner(c);
        }
        return instance;
    }

    void subscribe(Listener l) {
        boolean first = listeners.isEmpty();
        listeners.add(l);
        if (first) {
            main.removeCallbacks(tick);
            main.post(tick);
        } else {
            l.onDevices(snapshot());
        }
    }

    void unsubscribe(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) {
            main.removeCallbacks(tick);
        }
    }

    /** Devices seen in the last few minutes, sorted by address. */
    List<Device> snapshot() {
        long now = System.currentTimeMillis();
        List<Device> out = new ArrayList<>();
        for (Device d : byIp.values()) {
            if (now - d.lastSeen <= ONLINE_WINDOW_MS) {
                out.add(d);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(ipValue(a.ip), ipValue(b.ip)));
        return out;
    }

    int onlineCount() {
        return snapshot().size();
    }

    private void startScan() {
        if (scanning) {
            return;
        }
        scanning = true;
        new Thread(() -> {
            try {
                scan();
            } catch (Exception ignored) {
                // a failed pass simply shows the previous result until the next minute
            } finally {
                scanning = false;
                lastScan = System.currentTimeMillis();
                List<Device> snap = snapshot();
                main.post(() -> {
                    for (Listener l : listeners) {
                        l.onDevices(snap);
                    }
                });
            }
        }, "lan-scan").start();
    }

    private void scan() throws Exception {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            return;
        }
        DhcpInfo dh = wm.getDhcpInfo();
        if (dh == null || dh.ipAddress == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        int selfIp = Integer.reverseBytes(dh.ipAddress);
        int mask = dh.netmask != 0 ? Integer.reverseBytes(dh.netmask) : 0xFFFFFF00;
        int base = selfIp & mask;
        int hosts = Math.min(~mask, MAX_HOSTS);
        String gatewayIp = dh.gateway != 0 ? ipString(Integer.reverseBytes(dh.gateway)) : null;

        Device me = mark(ipString(selfIp), now);
        me.self = true;
        me.name = "THIS TV";
        String myMac = Prefs.get(ctx).getString(Prefs.MAC, "");
        if (!myMac.isEmpty()) {
            me.mac = myMac.toUpperCase(Locale.US);
        }
        if (gatewayIp != null) {
            mark(gatewayIp, now).gateway = true;
        }

        // 1. sweep: replies (and the ARP lookups they trigger) reveal who is there
        ExecutorService pool = Executors.newFixedThreadPool(24);
        for (int h = 1; h < hosts; h++) {
            final int addr = base | h;
            if (addr == selfIp) {
                continue;
            }
            pool.submit(() -> {
                try {
                    InetAddress a = InetAddress.getByAddress(bytes(addr));
                    if (NetPolicy.isLocal(a) && a.isReachable(500)) {
                        mark(a.getHostAddress(), System.currentTimeMillis());
                    }
                } catch (Exception ignored) {
                    // unreachable host
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // 2. ARP table: MAC addresses (and devices that ignore pings but talked recently)
        readArp(System.currentTimeMillis());

        // 3. UPnP / SSDP: friendly names, models, and the router's status
        ssdp(wm, System.currentTimeMillis());

        // 4. names from the router's DNS
        reverseDns();

        // 5. manufacturer from the MAC prefix
        vendors();

        // 6. new-device badge
        markNew();
    }

    private Device mark(String ip, long now) {
        Device d = byIp.get(ip);
        if (d == null) {
            d = new Device(ip);
            Device prev = byIp.putIfAbsent(ip, d);
            if (prev != null) {
                d = prev;
            }
        }
        if (d.firstSeen == 0 || now - d.lastSeen > ONLINE_WINDOW_MS) {
            d.firstSeen = now;
        }
        d.lastSeen = now;
        return d;
    }

    private void readArp(long now) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 4 || !"0x2".equals(p[2]) || "00:00:00:00:00:00".equals(p[3])) {
                    continue;
                }
                InetAddress a = InetAddress.getByName(p[0]);
                if (!NetPolicy.isLocal(a)) {
                    continue;
                }
                mark(p[0], now).mac = p[3].toUpperCase(Locale.US);
            }
        } catch (Exception ignored) {
            // some devices hide the ARP table from apps; the sweep results remain
        }
    }

    private void ssdp(WifiManager wm, long now) {
        Map<String, Set<String>> locations = new HashMap<>();
        WifiManager.MulticastLock lock = wm.createMulticastLock("arc-hud-ssdp");
        lock.setReferenceCounted(false);
        lock.acquire();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1500);
            byte[] q = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
                    + "MX: 1\r\nST: ssdp:all\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            DatagramPacket probe = new DatagramPacket(q, q.length, InetAddress.getByName("239.255.255.250"), 1900);
            s.send(probe);
            s.send(probe);
            long end = SystemClock.elapsedRealtime() + 4000;
            byte[] buf = new byte[2048];
            while (SystemClock.elapsedRealtime() < end) {
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(reply);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (!NetPolicy.isLocal(reply.getAddress())) {
                    continue;
                }
                String from = reply.getAddress().getHostAddress();
                mark(from, now);
                String loc = header(new String(buf, 0, reply.getLength(), StandardCharsets.US_ASCII), "LOCATION");
                if (loc != null) {
                    Set<String> set = locations.get(from);
                    if (set == null) {
                        set = new HashSet<>();
                        locations.put(from, set);
                    }
                    if (set.size() < 4) {
                        set.add(loc);
                    }
                }
            }
        } catch (Exception ignored) {
            // no multicast on this network
        } finally {
            lock.release();
        }
        for (Map.Entry<String, Set<String>> e : locations.entrySet()) {
            Device d = byIp.get(e.getKey());
            for (String loc : e.getValue()) {
                try {
                    String xml = NetPolicy.localHttp(loc, null, null);
                    String friendly = NetPolicy.tag(xml, "friendlyName");
                    String maker = NetPolicy.tag(xml, "manufacturer");
                    String model = NetPolicy.tag(xml, "modelName");
                    String type = NetPolicy.tag(xml, "deviceType");
                    if (d != null) {
                        if (d.name.isEmpty() && friendly != null && !d.self) {
                            d.name = friendly;
                        }
                        if (d.model.isEmpty() && model != null) {
                            d.model = (maker != null ? maker + " " : "") + model;
                        }
                        if (d.kind.isEmpty() && type != null) {
                            d.kind = kindOf(type);
                        }
                    }
                    if (xml.contains("WANIPConnection") || xml.contains("WANPPPConnection")) {
                        routerStatus(loc, xml);
                    }
                } catch (Exception ignored) {
                    // device did not answer its own description URL
                }
            }
        }
    }

    /** Router uptime and public IP over UPnP IGD, when the router offers it. */
    private void routerStatus(String location, String xml) {
        String service = null;
        String control = null;
        int from = 0;
        while (true) {
            int a = xml.indexOf("<service>", from);
            if (a < 0) {
                break;
            }
            int b = xml.indexOf("</service>", a);
            if (b < 0) {
                break;
            }
            String block = xml.substring(a, b);
            String type = NetPolicy.tag(block, "serviceType");
            if (type != null && (type.contains("WANIPConnection") || type.contains("WANPPPConnection"))) {
                service = type;
                control = NetPolicy.tag(block, "controlURL");
                break;
            }
            from = b;
        }
        if (service == null || control == null) {
            return;
        }
        try {
            java.net.URL base = new java.net.URL(location);
            String url = new java.net.URL(base, control).toString();
            String status = NetPolicy.localHttp(url, service + "#GetStatusInfo", soap(service, "GetStatusInfo"));
            String up = NetPolicy.tag(status, "NewUptime");
            if (up != null) {
                routerUptime = Stats.duration(Long.parseLong(up.trim()) * 1000);
            }
            String ext = NetPolicy.localHttp(url, service + "#GetExternalIPAddress", soap(service, "GetExternalIPAddress"));
            String ip = NetPolicy.tag(ext, "NewExternalIPAddress");
            if (ip != null && !ip.isEmpty()) {
                publicIp = ip;
            }
        } catch (Exception ignored) {
            // router does not expose its status over UPnP
        }
    }

    private void reverseDns() {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> jobs = new ArrayList<>();
        for (Device d : byIp.values()) {
            if (!d.name.isEmpty() || d.self) {
                continue;
            }
            jobs.add(pool.submit(() -> {
                try {
                    String host = InetAddress.getByName(d.ip).getCanonicalHostName();
                    if (host != null && !host.equals(d.ip)) {
                        d.name = host.endsWith(".lan") || host.endsWith(".local") || host.endsWith(".home")
                                ? host.substring(0, host.lastIndexOf('.')) : host;
                    }
                } catch (Exception ignored) {
                    // no name from the router
                }
            }));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void vendors() {
        Set<String> wanted = new HashSet<>();
        for (Device d : byIp.values()) {
            String oui = oui(d.mac);
            if (oui == null) {
                continue;
            }
            if (randomized(d.mac)) {
                d.vendor = "PRIVATE (RANDOM MAC)";
            } else if (vendorCache.containsKey(oui)) {
                d.vendor = vendorCache.get(oui);
            } else {
                wanted.add(oui);
            }
        }
        if (!wanted.isEmpty()) {
            vendorCache.putAll(VendorDb.lookup(ctx, wanted));
            for (Device d : byIp.values()) {
                String oui = oui(d.mac);
                if (oui != null && d.vendor.isEmpty() && vendorCache.containsKey(oui)) {
                    d.vendor = vendorCache.get(oui);
                }
            }
        }
    }

    private void markNew() {
        SharedPreferences prefs = Prefs.get(ctx);
        Set<String> known = new HashSet<>(prefs.getStringSet(KNOWN, new HashSet<String>()));
        boolean firstEver = known.isEmpty();
        for (Device d : byIp.values()) {
            if (d.mac.isEmpty()) {
                continue;
            }
            if (!known.contains(d.mac)) {
                d.isNew = !firstEver;
                known.add(d.mac);
            }
        }
        prefs.edit().putStringSet(KNOWN, known).apply();
    }

    // ---------------------------------------------------------------- helpers

    private static String kindOf(String deviceType) {
        String t = deviceType.toLowerCase(Locale.US);
        if (t.contains("internetgateway") || t.contains("wanconnection")) {
            return "ROUTER";
        }
        if (t.contains("mediarenderer")) {
            return "MEDIA PLAYER";
        }
        if (t.contains("mediaserver")) {
            return "MEDIA SERVER";
        }
        if (t.contains("printer")) {
            return "PRINTER";
        }
        if (t.contains("dial") || t.contains("tv")) {
            return "TV / CAST";
        }
        return "UPNP DEVICE";
    }

    private static String soap(String service, String action) {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action
                + " xmlns:u=\"" + service + "\"/></s:Body></s:Envelope>";
    }

    private static String header(String msg, String name) {
        for (String line : msg.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    private static String oui(String mac) {
        if (mac == null || mac.length() < 8) {
            return null;
        }
        String hex = mac.replace(":", "").replace("-", "").toUpperCase(Locale.US);
        return hex.length() >= 6 ? hex.substring(0, 6) : null;
    }

    /** Locally administered (bit 1 of the first byte): phones use these for privacy. */
    private static boolean randomized(String mac) {
        try {
            return (Integer.parseInt(mac.substring(0, 2), 16) & 0x02) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] bytes(int ip) {
        return new byte[] {(byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip};
    }

    private static String ipString(int ip) {
        return ((ip >>> 24) & 0xff) + "." + ((ip >>> 16) & 0xff) + "." + ((ip >>> 8) & 0xff) + "." + (ip & 0xff);
    }

    private static long ipValue(String ip) {
        long v = 0;
        for (String part : ip.split("\\.")) {
            try {
                v = v * 256 + Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }
        return v;
    }
}
