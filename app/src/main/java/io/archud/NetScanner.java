package io.archud;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
        volatile String custom = "";
        volatile long firstSeen;
        volatile long lastSeen;
        volatile long lastActive;
        volatile boolean isNew;
        volatile boolean self;
        volatile boolean gateway;
        /** Known from an earlier scan but not seen now (built from the on-device memory). */
        volatile boolean offline;

        Device(String ip) {
            this.ip = ip;
        }

        /** ACTIVE answers pings / UPnP; STANDBY only answers the network (ARP) check, e.g. a TV on standby. */
        boolean active(long now) {
            return self || now - lastActive <= ACTIVE_WINDOW_MS;
        }

        String display() {
            if (!custom.isEmpty()) {
                return custom;
            }
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
    private static final long ACTIVE_WINDOW_MS = 2 * PERIOD_MS + 30_000;
    private static final long NEW_FOR_MS = 24L * 3600 * 1000;
    private static final int MAX_HOSTS = 1022;
    /** Per-device memory on this TV: first-seen time and user-given name, keyed by MAC. */
    private static final String DEVICES = "devices";
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

        Device me = mark(ipString(selfIp), now, true);
        me.self = true;
        me.name = "THIS TV";
        String myMac = Prefs.get(ctx).getString(Prefs.MAC, "");
        if (!myMac.isEmpty()) {
            me.mac = myMac.toUpperCase(Locale.US);
        }
        if (gatewayIp != null) {
            mark(gatewayIp, now, false).gateway = true;
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
                        mark(a.getHostAddress(), System.currentTimeMillis(), true);
                    }
                } catch (Exception ignored) {
                    // unreachable host
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // 2. nudge every address (this forces an ARP lookup even for devices that ignore pings), twice, so
        //    a device that is slow to answer (Wi-Fi power save) is still caught, then read the ARP table
        prime(base, hosts, selfIp);
        Thread.sleep(1500);
        readArp(System.currentTimeMillis());
        prime(base, hosts, selfIp);
        Thread.sleep(2000);
        readArp(System.currentTimeMillis());

        // 3. UPnP / SSDP: friendly names, models, and the router's status
        ssdp(wm, System.currentTimeMillis());

        // 3b. mDNS: the device's own name (Windows, Macs, phones, Chromecast-style boxes ...)
        mdnsNames();

        // 4. names from the router's DNS
        reverseDns();

        // 5. manufacturer from the MAC prefix
        vendors();

        // 6. new-device badge
        markNew();
    }

    private Device mark(String ip, long now, boolean active) {
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
        if (active) {
            d.lastActive = now;
        }
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
                mark(p[0], now, false).mac = p[3].toUpperCase(Locale.US);
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
                mark(from, now, true);
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

    /**
     * NEW = first seen within the last 24 hours and not yet named by the user. Devices present on the
     * very first scan form the baseline and are never NEW. Naming a device marks it as known.
     */
    private void markNew() {
        SharedPreferences dp = ctx.getSharedPreferences(DEVICES, Context.MODE_PRIVATE);
        boolean baseline = !dp.getBoolean("baseline", false);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = dp.edit();
        for (Device d : byIp.values()) {
            if (d.mac.isEmpty()) {
                continue;
            }
            String k = key(d.mac);
            long first = dp.getLong("first_" + k, 0);
            if (first == 0) {
                first = baseline ? 1 : now;
                e.putLong("first_" + k, first);
            }
            d.custom = dp.getString("name_" + k, "");
            d.isNew = first > 1 && now - first < NEW_FOR_MS && d.custom.isEmpty();
            // remember the device, so it can still be listed (as offline) after it leaves the network
            e.putLong("last_" + k, d.lastSeen);
            e.putString("ip_" + k, d.ip);
            if (!d.vendor.isEmpty()) {
                e.putString("vendor_" + k, d.vendor);
            }
            String label = !d.name.isEmpty() ? d.name : d.model;
            if (!label.isEmpty() && !d.self) {
                e.putString("label_" + k, label);
            }
        }
        if (baseline) {
            e.putBoolean("baseline", true);
        }
        e.apply();
    }

    /** Stores (or clears, when empty) the user's name for a device; applies to the current list at once. */
    void setCustomName(Device d, String name) {
        if (d.mac.isEmpty()) {
            return;
        }
        String clean = name == null ? "" : name.trim();
        SharedPreferences.Editor e = ctx.getSharedPreferences(DEVICES, Context.MODE_PRIVATE).edit();
        if (clean.isEmpty()) {
            e.remove("name_" + key(d.mac));
        } else {
            e.putString("name_" + key(d.mac), clean);
        }
        e.apply();
        d.custom = clean;
        if (!clean.isEmpty()) {
            d.isNew = false;
        }
    }

    private static String key(String mac) {
        return mac.replace(":", "").replace("-", "").toUpperCase(Locale.US);
    }

    // ---------------------------------------------------------------- discovery helpers

    /** Sends one tiny UDP packet (discard port) to every address; the OS asks "who has this address?" for each. */
    private void prime(int base, int hosts, int selfIp) {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] nudge = new byte[] {0};
            for (int h = 1; h < hosts; h++) {
                int addr = base | h;
                if (addr == selfIp) {
                    continue;
                }
                try {
                    InetAddress a = InetAddress.getByAddress(bytes(addr));
                    if (NetPolicy.isLocal(a)) {
                        s.send(new DatagramPacket(nudge, nudge.length, a, 9));
                    }
                } catch (Exception ignored) {
                    // address not usable right now
                }
            }
        } catch (Exception ignored) {
            // no socket: the sweep results remain
        }
    }

    /** Asks each known device without a name for its mDNS host name; an answer also proves it is awake. */
    private void mdnsNames() {
        List<Device> targets = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Device d : byIp.values()) {
            if (!d.self && d.name.isEmpty() && d.custom.isEmpty() && now - d.lastSeen <= ONLINE_WINDOW_MS) {
                targets.add(d);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(300);
            Map<Integer, Device> waiting = new HashMap<>();
            int id = 1;
            for (Device d : targets) {
                InetAddress a = InetAddress.getByName(d.ip);
                if (!NetPolicy.isLocal(a)) {
                    continue;
                }
                byte[] q = dnsQuery(id, reverseName(d.ip));
                waiting.put(id, d);
                id++;
                s.send(new DatagramPacket(q, q.length, a, 5353));
            }
            long end = SystemClock.elapsedRealtime() + 1500;
            byte[] buf = new byte[1500];
            while (SystemClock.elapsedRealtime() < end && !waiting.isEmpty()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (p.getLength() < 12) {
                    continue;
                }
                int replyId = ((buf[0] & 0xff) << 8) | (buf[1] & 0xff);
                Device d = waiting.get(replyId);
                if (d == null) {
                    continue;
                }
                waiting.remove(replyId);
                long t = System.currentTimeMillis();
                d.lastSeen = t;
                d.lastActive = t;
                String n = ptrAnswer(Arrays.copyOf(buf, p.getLength()));
                if (n != null) {
                    if (n.endsWith(".local")) {
                        n = n.substring(0, n.length() - 6);
                    }
                    if (!n.isEmpty() && d.name.isEmpty()) {
                        d.name = n;
                    }
                }
            }
        } catch (Exception ignored) {
            // mDNS is best effort
        }
    }

    private static String reverseName(String ip) {
        String[] o = ip.split("\\.");
        return o[3] + "." + o[2] + "." + o[1] + "." + o[0] + ".in-addr.arpa";
    }

    private static byte[] dnsQuery(int id, String name) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id >> 8);
        o.write(id);
        o.write(0);
        o.write(0);
        o.write(0);
        o.write(1);
        for (int i = 0; i < 6; i++) {
            o.write(0);
        }
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
        o.write(0);
        o.write(12);
        o.write(0);
        o.write(1);
        return o.toByteArray();
    }

    private static int skipName(byte[] m, int off) {
        while (off < m.length) {
            int len = m[off] & 0xff;
            if (len == 0) {
                return off + 1;
            }
            if ((len & 0xC0) == 0xC0) {
                return off + 2;
            }
            off += len + 1;
        }
        return m.length;
    }

    private static String readName(byte[] m, int start) {
        StringBuilder sb = new StringBuilder();
        int off = start;
        int guard = 0;
        while (off < m.length && guard++ < 40) {
            int len = m[off] & 0xff;
            if (len == 0) {
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                if (off + 1 >= m.length) {
                    break;
                }
                off = ((len & 0x3F) << 8) | (m[off + 1] & 0xff);
                continue;
            }
            if (off + 1 + len > m.length) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(new String(m, off + 1, len, StandardCharsets.UTF_8));
            off += len + 1;
        }
        return sb.toString();
    }

    /** The first PTR answer in a DNS reply, or null. */
    private static String ptrAnswer(byte[] m) {
        int questions = ((m[4] & 0xff) << 8) | (m[5] & 0xff);
        int answers = ((m[6] & 0xff) << 8) | (m[7] & 0xff);
        int off = 12;
        for (int i = 0; i < questions; i++) {
            off = skipName(m, off) + 4;
        }
        for (int i = 0; i < answers && off + 10 <= m.length; i++) {
            off = skipName(m, off);
            if (off + 10 > m.length) {
                break;
            }
            int type = ((m[off] & 0xff) << 8) | (m[off + 1] & 0xff);
            int rdlen = ((m[off + 8] & 0xff) << 8) | (m[off + 9] & 0xff);
            off += 10;
            if (type == 12) {
                String n = readName(m, off);
                if (!n.isEmpty()) {
                    return n;
                }
            }
            off += rdlen;
        }
        return null;
    }

    // ---------------------------------------------------------------- remembered (offline) devices

    /** Devices this TV has seen in the last 30 days that are not on the network now, most recent first. */
    List<Device> offlineKnown() {
        SharedPreferences dp = ctx.getSharedPreferences(DEVICES, Context.MODE_PRIVATE);
        Set<String> online = new HashSet<>();
        for (Device d : snapshot()) {
            if (!d.mac.isEmpty()) {
                online.add(key(d.mac));
            }
        }
        long now = System.currentTimeMillis();
        List<Device> out = new ArrayList<>();
        for (String entry : dp.getAll().keySet()) {
            if (!entry.startsWith("last_")) {
                continue;
            }
            String k = entry.substring(5);
            long last = dp.getLong(entry, 0);
            if (online.contains(k) || last <= 0 || now - last > 30L * 24 * 3600 * 1000) {
                continue;
            }
            Device d = new Device(dp.getString("ip_" + k, ""));
            d.mac = formatMac(k);
            d.custom = dp.getString("name_" + k, "");
            d.name = dp.getString("label_" + k, "");
            d.vendor = dp.getString("vendor_" + k, "");
            d.lastSeen = last;
            d.firstSeen = dp.getLong("first_" + k, 0);
            d.offline = true;
            out.add(d);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return out;
    }

    private static String formatMac(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < key.length(); i += 2) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(key, i, i + 2);
        }
        return sb.toString();
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
