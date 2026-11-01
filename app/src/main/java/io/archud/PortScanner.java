package io.archud;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * On-demand TCP port scan of the devices on the local network. It runs only after the user presses Start on
 * the Devices screen, and stops on Stop or when that screen closes. Pacing adapts: concurrency rises while
 * probes answer quickly and falls when they time out, slow down, or the TV is short of memory.
 */
final class PortScanner {
    interface Listener {
        void onScanUpdate();
    }

    /** One open port on one host, with the service name, a banner if one was read, and a risk note if any. */
    static final class Open {
        final int port;
        final String service;
        final String banner;
        final String risk;
        /** 0 = info, 1 = check, 2 = act. */
        final int level;

        Open(int port, String service, String banner, String risk, int level) {
            this.port = port;
            this.service = service;
            this.banner = banner;
            this.risk = risk;
            this.level = level;
        }
    }

    static final class Host {
        final String ip;
        final InetAddress addr;
        final List<Open> open = Collections.synchronizedList(new ArrayList<Open>());

        Host(String ip, InetAddress addr) {
            this.ip = ip;
            this.addr = addr;
        }
    }

    private static final class Probe {
        final Host host;
        final int port;

        Probe(Host host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** Common TCP ports: remote access, file sharing, printers, web, mail, IoT and media. */
    static final int[] PORTS = {21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 515, 548, 554, 587, 631, 993,
            995, 1443, 1883, 3389, 5000, 5001, 5900, 8000, 8008, 8080, 8081, 8443, 8888, 9000, 9100, 32400, 49152};
    private static final int CONNECT_MS = 500;
    private static final int BANNER_MS = 600;
    private static final int MIN_WINDOW = 1;
    private static final int MAX_WINDOW = 24;
    private static final int START_WINDOW = 4;
    /** Pacing decision is made after this many probes. */
    private static final int BATCH = 20;
    /** Below this much MemAvailable (kB) the scan drops to one probe at a time and pauses. */
    private static final long MEM_FLOOR_KB = 40_000;
    private static final long PAUSE_MS = 1_000;
    /** Repaint the Devices screen after this many probes, not after each one. */
    private static final int UI_EVERY = 20;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private static PortScanner instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object gate = new Object();
    private final Map<String, Host> hosts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Probe> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger liveWorkers = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private volatile Listener listener;
    private volatile boolean running;
    private volatile boolean stopped;
    private volatile int total;
    private volatile int window = START_WINDOW;
    private volatile long pauseUntil;

    // Guarded by gate.
    private int inFlight;
    private int samples;
    private int batchTimeouts;
    private final List<Long> batchRtt = new ArrayList<>();
    private long baseline = -1;

    private PortScanner() {
    }

    static synchronized PortScanner get() {
        if (instance == null) {
            instance = new PortScanner();
        }
        return instance;
    }

    boolean isRunning() {
        return running;
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Starts a scan of the given addresses. Anything that is not a local (LAN) address is skipped. */
    void start(List<String> ips) {
        if (running) {
            return;
        }
        synchronized (gate) {
            stopped = false;
            inFlight = 0;
            samples = 0;
            batchTimeouts = 0;
            batchRtt.clear();
            baseline = -1;
            window = START_WINDOW;
            pauseUntil = 0;
            hosts.clear();
            tasks.clear();
            for (String ip : ips) {
                if (!IPV4.matcher(ip).matches()) {
                    continue;
                }
                InetAddress a = literal(ip);
                if (a == null || !NetPolicy.isLocal(a)) {
                    continue;
                }
                Host h = new Host(ip, a);
                hosts.put(ip, h);
                for (int port : PORTS) {
                    tasks.add(new Probe(h, port));
                }
            }
            total = tasks.size();
            done.set(0);
        }
        if (tasks.isEmpty()) {
            main.post(this::notifyListener);
            return;
        }
        running = true;
        int workers = Math.min(MAX_WINDOW, tasks.size());
        liveWorkers.set(workers);
        for (int i = 0; i < workers; i++) {
            new Thread(this::work, "port-scan-" + i).start();
        }
        main.post(this::notifyListener);
    }

    /** Stops the scan. Probes already in flight finish within the connect timeout. */
    void stop() {
        synchronized (gate) {
            stopped = true;
            tasks.clear();
            gate.notifyAll();
        }
    }

    /** One line for the Devices screen: progress while running, the final count afterwards. */
    String status() {
        if (running) {
            return "PORT SCAN " + done.get() + "/" + total + " probes  ·  " + window + " in parallel, paced to this TV";
        }
        if (total == 0) {
            return "";
        }
        return "PORT SCAN " + (stopped ? "stopped at " : "finished ") + done.get() + "/" + total + " probes";
    }

    /** Open ports and risk notes for one device, or an empty string if nothing is known yet. */
    String summaryFor(String ip) {
        Host h = hosts.get(ip);
        if (h == null) {
            return "";
        }
        List<Open> copy;
        synchronized (h.open) {
            copy = new ArrayList<>(h.open);
        }
        if (copy.isEmpty()) {
            return running ? "" : "PORTS none open (of " + PORTS.length + " checked)";
        }
        Collections.sort(copy, (x, y) -> Integer.compare(x.port, y.port));
        StringBuilder b = new StringBuilder("PORTS ");
        for (Open o : copy) {
            b.append(o.port).append(' ').append(o.service);
            if (!o.banner.isEmpty()) {
                b.append(" [").append(o.banner).append(']');
            }
            b.append("   ");
        }
        for (Open o : copy) {
            if (!o.risk.isEmpty()) {
                b.append('\n').append(o.level == 2 ? "ACT: " : "CHECK: ").append(o.risk)
                        .append(" (").append(o.port).append(')');
            }
        }
        return b.toString().trim();
    }

    private void work() {
        try {
            while (!stopped) {
                Probe p = tasks.poll();
                if (p == null || !enter()) {
                    break;
                }
                record(probe(p));
            }
        } finally {
            if (liveWorkers.decrementAndGet() == 0) {
                running = false;
                main.post(this::notifyListener);
            }
        }
    }

    /** Waits for a free slot under the current window. Returns false once the scan is stopped. */
    private boolean enter() {
        synchronized (gate) {
            while (!stopped && (inFlight >= window || System.currentTimeMillis() < pauseUntil)) {
                try {
                    gate.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (stopped) {
                return false;
            }
            inFlight++;
            return true;
        }
    }

    /** Records one finished probe. rttMs is -1 for a timeout. */
    private void record(int rttMs) {
        synchronized (gate) {
            inFlight--;
            boolean timeout = rttMs < 0;
            batchRtt.add(timeout ? (long) CONNECT_MS : (long) rttMs);
            if (timeout) {
                batchTimeouts++;
            }
            samples++;
            if (samples % BATCH == 0) {
                adapt();
            }
            gate.notifyAll();
        }
        int n = done.incrementAndGet();
        if (n % UI_EVERY == 0 || n == total) {
            main.post(this::notifyListener);
        }
    }

    /** Pacing decision, called with gate held. Additive increase, multiplicative decrease. */
    private void adapt() {
        List<Long> sorted = new ArrayList<>(batchRtt);
        Collections.sort(sorted);
        long median = sorted.get(sorted.size() / 2);
        double timeoutShare = (double) batchTimeouts / sorted.size();
        if (baseline < 0 || median < baseline) {
            baseline = Math.max(median, 1);
        }
        long avail = memAvailableKb();
        if (avail >= 0 && avail < MEM_FLOOR_KB) {
            window = MIN_WINDOW;
            pauseUntil = System.currentTimeMillis() + PAUSE_MS;
        } else if (timeoutShare > 0.2 || median > Math.max(baseline * 2, 40)) {
            window = Math.max(MIN_WINDOW, window / 2);
        } else {
            window = Math.min(MAX_WINDOW, window + 1);
        }
        batchRtt.clear();
        batchTimeouts = 0;
    }

    /** Returns the connect time in ms, or -1 on timeout. Refused or unreachable counts as a fast answer. */
    private int probe(Probe p) {
        long t0 = System.nanoTime();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(p.host.addr, p.port), CONNECT_MS);
        } catch (SocketTimeoutException e) {
            closeQuietly(s);
            return -1;
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            return elapsed(t0);
        }
        int rtt = elapsed(t0);
        closeQuietly(s);
        addOpen(p);
        return rtt;
    }

    /** Called only for open ports. Connects again briefly to read a banner, so the first socket is closed by probe(). */
    private void addOpen(Probe p) {
        String banner = readBanner(p);
        String risk = "";
        int level = 0;
        switch (p.port) {
            case 21:
                risk = "FTP: passwords cross the network in clear";
                level = 2;
                break;
            case 23:
                risk = "Telnet: remote shell in clear text";
                level = 2;
                break;
            case 139:
            case 445:
                risk = "SMB file sharing reachable";
                level = 1;
                break;
            case 3389:
                risk = "RDP remote desktop reachable";
                level = 1;
                break;
            case 5900:
                risk = "VNC remote desktop reachable";
                level = 1;
                break;
            case 1883:
                risk = "MQTT broker: check it requires a login";
                level = 1;
                break;
            case 80:
            case 5000:
            case 8000:
            case 8008:
            case 8080:
            case 8081:
            case 8888:
            case 9000:
                risk = "Web page without TLS";
                break;
            default:
                break;
        }
        p.host.open.add(new Open(p.port, serviceOf(p.port), banner, risk, level));
    }

    private static String readBanner(Probe p) {
        boolean http = isHttp(p.port);
        boolean line = isLineBanner(p.port);
        if (!http && !line) {
            return "";
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(p.host.addr, p.port), CONNECT_MS);
            s.setSoTimeout(BANNER_MS);
            if (http) {
                OutputStream out = s.getOutputStream();
                out.write(("HEAD / HTTP/1.0\r\nHost: " + p.host.ip + "\r\n\r\n").getBytes("US-ASCII"));
                out.flush();
            }
            InputStream in = s.getInputStream();
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) {
                return "";
            }
            String text = new String(buf, 0, n, "US-ASCII");
            if (http) {
                for (String l : text.split("\r\n")) {
                    if (l.regionMatches(true, 0, "Server:", 0, 7)) {
                        return clean(l.substring(7));
                    }
                }
                return "";
            }
            return clean(text.split("\n")[0]);
        } catch (IOException | RuntimeException e) {
            return "";
        } finally {
            closeQuietly(s);
        }
    }

    private static boolean isHttp(int port) {
        switch (port) {
            case 80:
            case 5000:
            case 8000:
            case 8008:
            case 8080:
            case 8081:
            case 8888:
            case 9000:
                return true;
            default:
                return false;
        }
    }

    private static boolean isLineBanner(int port) {
        switch (port) {
            case 21:
            case 22:
            case 23:
            case 25:
            case 110:
            case 143:
            case 587:
                return true;
            default:
                return false;
        }
    }

    private static String serviceOf(int port) {
        switch (port) {
            case 21: return "ftp";
            case 22: return "ssh";
            case 23: return "telnet";
            case 25:
            case 587: return "smtp";
            case 53: return "dns";
            case 80:
            case 8000:
            case 8008:
            case 8080:
            case 8081:
            case 8888:
            case 9000: return "http";
            case 110: return "pop3";
            case 139:
            case 445: return "smb";
            case 143:
            case 993: return "imap";
            case 443:
            case 1443:
            case 5001:
            case 8443: return "https";
            case 515:
            case 9100: return "print";
            case 548: return "afp";
            case 554: return "rtsp";
            case 631: return "ipp";
            case 995: return "pop3s";
            case 1883: return "mqtt";
            case 3389: return "rdp";
            case 5000: return "web";
            case 5900: return "vnc";
            case 32400: return "plex";
            case 49152: return "upnp";
            default: return "tcp";
        }
    }

    /** Keeps printable ASCII only, so a banner cannot inject control characters into the screen. */
    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 80; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7f) {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    private static long memAvailableKb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
        return -1;
    }

    private static InetAddress literal(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static int elapsed(long t0) {
        return (int) ((System.nanoTime() - t0) / 1_000_000L);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do: the socket is finished either way
        }
    }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) {
            l.onScanUpdate();
        }
    }
}
