package io.archud.link;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Self-update, locked to one repository. The app only goes online when the user presses
 * "Check for update", only over HTTPS, and only to this repo's release (plus GitHub's own file
 * servers it redirects to). Android then installs the APK only if it carries the same signature.
 */
final class Updater {
    static final String REPO = "madesh24-svg/arc-hud";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    private static final String TAG_PREFIX = "build-";
    private static final String ASSET = "arc-link.apk";
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"));
    private static final int MAX_API_BYTES = 512 * 1024;
    private static final long MAX_APK_BYTES = 20L * 1024 * 1024;
    private static final long     private static final int MAX_REDIRECTS = 5;

    static final class Release {
        final int versionCode;
        final String name;
        final String url;
        final long size;

        Release(int versionCode, String name, String url, long size) {
            this.versionCode = versionCode;
            this.name = name;
            this.url = url;
            this.size = size;
        }
    }

    private Updater() {
    }

    /** Network call: run off the main thread. */
    static Release latest() throws IOException {
        HttpsURLConnection c = connect(LATEST_API, true);
        try (InputStream in = c.getInputStream()) {
            JSONObject o = new JSONObject(new String(readAll(in, MAX_API_BYTES), StandardCharsets.UTF_8));
            String tag = o.getString("tag_name");
            if (!tag.startsWith(TAG_PREFIX)) {
                throw new IOException("Unexpected release tag " + tag);
            }
            int code = Integer.parseInt(tag.substring(TAG_PREFIX.length()));
            JSONArray assets = o.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (!ASSET.equals(a.getString("name"))) {
                    continue;
                }
                String url = a.getString("browser_download_url");
                if (!url.startsWith(DOWNLOAD_PREFIX)) {
                    throw new IOException("Download is not from " + REPO);
                }
                long size = a.optLong("size", -1);
                if (size > MAX_APK_BYTES) {
                    throw new IOException("Update is larger than allowed");
                }
                return new Release(code, o.optString("name", tag), url, size);
            }
            throw new IOException("No " + ASSET + " in the latest release");
        } catch (JSONException | NumberFormatException e) {
            throw new IOException("Could not read the release information", e);
        } finally {
            c.disconnect();
        }
    }

    /** Network call: streams the APK into an install session; Android asks the user to confirm. */
    static void install(Context ctx, Release r) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int id = pi.createSession(params);
        HttpsURLConnection c = connect(r.url, false);
        try (PackageInstaller.Session session = pi.openSession(id);
             InputStream in = c.getInputStream()) {
            try (OutputStream out = session.openWrite(ASSET, 0, r.size)) {
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_APK_BYTES) {
                        throw new IOException("Update is larger than allowed");
                    }
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            Intent result = new Intent(ctx, InstallReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(id);
            throw e;
        } finally {
            c.disconnect();
        }
    }

    private static HttpsURLConnection connect(String start, boolean api) throws IOException {
        String url = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol()) || !ALLOWED_HOSTS.contains(u.getHost())) {
                throw new IOException("Blocked address: " + u.getHost());
            }
            HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "arc-link-updater");
            if (api) {
                c.setRequestProperty("Accept", "application/vnd.github+json");
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) {
                    throw new IOException("Redirect without a location");
                }
                url = new URL(u, next).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("Server answered " + code);
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > max) {
                throw new IOException("Response too large");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
