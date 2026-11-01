package io.archud;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/** Live device telemetry, read locally on the device. Nothing leaves the device. */
final class Stats {
    private static final float FULL_SCALE_KBPS = 4096f;

    private final Context ctx;

    final String host;
    final String chipset;
    final String os;
    final boolean remoteApp;
    final boolean castApp;
    final boolean mirrorApp;

    String ip = "--";
    String mac = "--";
    String ssid = "--";
    String band = "";
    String dns = "--";
    String uptime = "--";
    String thermal;
    int signal = -1;
    int linkMbps = -1;
    boolean wifiUp;
    boolean healthy = true;

    float ram;
    float storage;
    float cpu = -1f;
    float rxFrac;
    float txFrac;
    String ramText = "--";
    String storageText = "--";
    String cpuText = "--";
    String rxText = "--";
    String txText = "--";

    private long lastRx = -1;
    private long lastTx = -1;
    private long lastSample;
    private long lastIdle = -1;
    private long lastTotal = -1;

    Stats(Context context) {
        ctx = context;
        host = (Build.MANUFACTURER + " " + Build.MODEL).toUpperCase(Locale.US);
        chipset = (Build.HARDWARE + " x" + Runtime.getRuntime().availableProcessors()).toUpperCase(Locale.US);
        os = "ANDROID " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT;
        PackageManager pm = context.getPackageManager();
        remoteApp = installed(pm, "com.vs.phoneremote.google");
        castApp = installed(pm, "org.smarttube.stable") || installed(pm, "com.google.android.youtube.tv");
        mirrorApp = installed(pm, "com.vstv.mcast");
    }

    void refresh() {
        long now = SystemClock.elapsedRealtime();
        uptime = duration(now);
        readWifi();
        mac = readMac();
        readNetwork();
        readMemory();
        readDisk();
        readTraffic(now);
        readCpu();
        thermal = readThermal();
        healthy = wifiUp && ram < 0.9f && storage < 0.92f;
    }

    private void readWifi() {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            wifiUp = wi != null && wi.getNetworkId() != -1;
            if (wi == null) {
                return;
            }
            int a = wi.getIpAddress();
            ip = a == 0 ? "--" : String.format(Locale.US, "%d.%d.%d.%d",
                    a & 0xff, (a >> 8) & 0xff, (a >> 16) & 0xff, (a >>> 24) & 0xff);
            String s = wi.getSSID();
            ssid = (s == null || s.contains("<unknown")) ? "HIDDEN" : s.replace("\"", "");
            signal = wifiUp ? WifiManager.calculateSignalLevel(wi.getRssi(), 101) : -1;
            linkMbps = wi.getLinkSpeed();
            int f = wi.getFrequency();
            band = f > 4900 ? "5G" : (f > 2000 ? "2.4G" : "");
        } catch (RuntimeException e) {
            wifiUp = false;
        }
    }

    private void readNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp == null) {
                dns = "OFFLINE";
            } else if (Build.VERSION.SDK_INT >= 28 && lp.isPrivateDnsActive()) {
                String name = lp.getPrivateDnsServerName();
                dns = name != null ? name.toUpperCase(Locale.US) : "PRIVATE";
            } else {
                dns = "STANDARD";
            }
        } catch (RuntimeException e) {
            dns = "--";
        }
    }

    private void readMemory() {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        ram = mi.totalMem > 0 ? used / (float) mi.totalMem : 0f;
        ramText = (used >> 20) + "/" + (mi.totalMem >> 20) + " MB";
    }

    private void readDisk() {
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            long total = sf.getTotalBytes();
            long free = sf.getAvailableBytes();
            storage = total > 0 ? (total - free) / (float) total : 0f;
            storageText = String.format(Locale.US, "%.1f GB FREE", free / 1073741824f);
        } catch (RuntimeException e) {
            storageText = "--";
        }
    }

    private void readTraffic(long now) {
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            rxText = txText = "N/A";
            return;
        }
        if (lastRx >= 0 && now > lastSample) {
            float dt = (now - lastSample) / 1000f;
            float rxRate = Math.max(0, rx - lastRx) / dt;
            float txRate = Math.max(0, tx - lastTx) / dt;
            rxText = rate(rxRate);
            txText = rate(txRate);
            rxFrac = scale(rxRate);
            txFrac = scale(txRate);
        }
        lastRx = rx;
        lastTx = tx;
        lastSample = now;
    }

    private void readCpu() {
        String line = readLine("/proc/stat");
        if (line == null || !line.startsWith("cpu")) {
            cpu = -1f;
            cpuText = "SANDBOXED";
            return;
        }
        String[] p = line.trim().split("\\s+");
        long total = 0;
        for (int i = 1; i < p.length; i++) {
            total += Long.parseLong(p[i]);
        }
        long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
        if (lastTotal >= 0 && total > lastTotal) {
            cpu = 1f - (idle - lastIdle) / (float) (total - lastTotal);
            cpuText = Math.round(cpu * 100) + " %";
        }
        lastIdle = idle;
        lastTotal = total;
    }

    private static String readThermal() {
        String t = readLine("/sys/class/thermal/thermal_zone0/temp");
        if (t == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(t.trim());
            return (v > 1000 ? v / 1000 : v) + " C";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readMac() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!"wlan0".equals(ni.getName())) {
                    continue;
                }
                byte[] hw = ni.getHardwareAddress();
                if (hw == null || hw.length == 0) {
                    break;
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : hw) {
                    if (sb.length() > 0) {
                        sb.append(':');
                    }
                    sb.append(String.format(Locale.US, "%02X", b));
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // fall through to sysfs
        }
        String sys = readLine("/sys/class/net/wlan0/address");
        return sys != null ? sys.trim().toUpperCase(Locale.US) : "RESTRICTED";
    }

    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static float scale(float bytesPerSec) {
        float kb = bytesPerSec / 1024f;
        return (float) Math.min(1.0, Math.log10(1 + kb) / Math.log10(1 + FULL_SCALE_KBPS));
    }

    private static String rate(float b) {
        if (b < 1024) {
            return Math.round(b) + " B/s";
        }
        if (b < 1048576) {
            return Math.round(b / 1024) + " KB/s";
        }
        return String.format(Locale.US, "%.1f MB/s", b / 1048576f);
    }

    static String duration(long ms) {
        long m = ms / 60000;
        long h = m / 60;
        long d = h / 24;
        if (d > 0) {
            return d + "D " + (h % 24) + "H " + (m % 60) + "M";
        }
        return h + "H " + (m % 60) + "M";
    }
}
