package io.archud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Locale;

/**
 * ARC ANNOUNCE: while ARC LINK is on, this TV tells the home network who it is every few seconds, so phones find it
 * again by its permanent ID however often the router changes its address. One small UDP broadcast on port 8766:
 * ARCLINK1|id|name|ip|port|mac|screen (screen = on or standby). It never carries the PIN or anything else private, and goes to the local network only.
 */
final class Announcer {
    static final int PORT = 8766;
    private static final long EVERY_MS = 3000;
    private static final String ID_KEY = "tv_id";
    private static final String NAME_KEY = "tv_name";

    private static Thread thread;
    private static volatile boolean stopped;

    private Announcer() {
    }

    /** Starts the announcing thread if it is not already running. */
    static synchronized void start(Context c) {
        if (thread != null && thread.isAlive()) {
            return;
        }
        final Context app = c.getApplicationContext();
        stopped = false;
        thread = new Thread(() -> loop(app), "arclink-announce");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        stopped = true;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /** This TV's permanent ID: 16 random hex characters made on first use and kept with the other settings. */
    static String id(Context c) {
        SharedPreferences p = Prefs.get(c);
        String id = p.getString(ID_KEY, "");
        if (id.length() != 16) {
            byte[] b = new byte[8];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format(Locale.US, "%02x", x & 0xff));
            }
            id = sb.toString();
            p.edit().putString(ID_KEY, id).apply();
        }
        return id;
    }

    /** The name phones show for this TV: the one set in settings, otherwise the model name. */
    static String name(Context c) {
        String n = Prefs.get(c).getString(NAME_KEY, "");
        if (n.isEmpty()) {
            n = Build.MODEL == null ? "TV" : Build.MODEL;
        }
        return clean(n);
    }

    /** "on" while the screen is on, "standby" while it is off but the TV is still reachable. */
    static String screen(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && !pm.isInteractive() ? "standby" : "on";
    }

    /** The Wi-Fi (or wired) hardware address, lower case with colons, or an empty string if Android hides it. */
    static String mac() {
        String found = "";
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.getHardwareAddress() == null) {
                    continue;
                }
                String m = hex(ni.getHardwareAddress());
                if ("wlan0".equals(ni.getName())) {
                    return m;
                }
                if (found.isEmpty()) {
                    found = m;
                }
            }
        } catch (IOException ignored) {
            // no interface information
        }
        return found;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format(Locale.US, "%02x", b[i] & 0xff));
        }
        return sb.toString();
    }

    /** Keeps the separator and control characters out of a name. */
    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 32; i++) {
            char ch = s.charAt(i);
            if (ch >= 32 && ch != '|' && ch != 127) {
                sb.append(ch);
            }
        }
        return sb.length() == 0 ? "TV" : sb.toString();
    }

    private static void loop(Context app) {
        while (!stopped) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                while (!stopped) {
                    sendAll(app, socket);
                    Thread.sleep(EVERY_MS);
                }
            } catch (IOException | RuntimeException e) {
                // network down or changing: wait and open a fresh socket
                if (!pause(5000)) {
                    return;
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return !stopped;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static void sendAll(Context app, DatagramSocket socket) throws IOException {
        String id = id(app);
        String name = name(app);
        String mac = mac();
        String screen = screen(app);
        Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
        while (ifs != null && ifs.hasMoreElements()) {
            NetworkInterface ni = ifs.nextElement();
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                InetAddress a = ia.getAddress();
                if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) {
                    continue;
                }
                String msg = "ARCLINK1|" + id + "|" + name + "|" + a.getHostAddress() + "|" + LinkServer.PORT
                        + "|" + mac + "|" + screen;
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                InetAddress directed = ia.getBroadcast();
                try {
                    if (directed != null) {
                        socket.send(new DatagramPacket(data, data.length, directed, PORT));
                    }
                    socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), PORT));
                } catch (IOException ignored) {
                    // this interface is not ready; the next round tries again
                }
            }
        }
    }
}
