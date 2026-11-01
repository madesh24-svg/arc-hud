package io.archud;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTPS GET for the search screen, restricted to a fixed list of hosts: Wikipedia's open search API and
 * YouTube's image servers (thumbnails). Redirects are not followed, so a response can never move the
 * request to another host. The updater has its own, stricter allowlist (this app's GitHub releases).
 */
final class SafeHttp {
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "en.wikipedia.org", "ta.wikipedia.org", "i.ytimg.com"));
    private static final int MAX_TEXT = 256 * 1024;

    private SafeHttp() {
    }

    static byte[] get(String url, int maxBytes) throws IOException {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol()) || !HOSTS.contains(u.getHost())) {
            throw new IOException("Blocked address: " + u.getHost());
        }
        HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "arc-hud-search");
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("Server answered " + code);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > maxBytes) {
                        throw new IOException("Response too large");
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static String getText(String url) throws IOException {
        return new String(get(url, MAX_TEXT), StandardCharsets.UTF_8);
    }
}
