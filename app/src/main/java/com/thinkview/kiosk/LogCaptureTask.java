package com.thinkview.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Captures the kiosk's recent logcat and posts it to Home Assistant as a persistent
 * notification, so a maintainer can read the logs from a different machine without ever
 * touching the device. Top-level Runnable to avoid d8 anonymous-inner-class issues.
 *
 * Why persistent_notification.create rather than firing an HA event:
 *   - HA events are transient. Once fired, you have to be subscribed at that exact moment to
 *     see them. Persistent notifications survive in the HA state machine until dismissed,
 *     which means a maintainer can read them via GET /api/states/persistent_notification.<id>
 *     hours later.
 *   - No HA automation setup required to capture / archive the logs. The notification IS the
 *     archive.
 *   - Built-in HA UI shows them in the notifications panel, so the user can see what the
 *     fleet is reporting without any extra config.
 *
 * Logcat permissions: regular Android apps can only read their own UID's logcat output since
 * Jelly Bean. That's exactly what we want -- only kiosk-related lines, no leaking other
 * apps' internals.
 *
 * The notification ID is derived from the device name so the same device overwrites its own
 * notification on each get_logs call rather than piling up dozens of historical dumps.
 */
class LogCaptureTask implements Runnable {
    private static final String TAG = "ThinkViewKiosk/Logs";
    /// 32 KB cap. HA's REST API accepts much larger payloads, but persistent notifications
    /// rendered via markdown choke past ~64 KB. 32 KB is enough for ~500 logcat lines.
    private static final int MAX_LOG_BYTES = 32 * 1024;

    private final Context appContext;
    private final String deviceName;

    LogCaptureTask(Context ctx, String deviceName) {
        this.appContext = ctx.getApplicationContext();
        this.deviceName = deviceName == null ? "ThinkView Kiosk" : deviceName;
    }

    @Override
    public void run() {
        String logs = captureLogcat();
        upload(logs);
    }

    /// Runs `logcat -d -t 800` in our own UID, returning the most recent 800 lines (or up to
    /// 32 KB, whichever is smaller). 800 covers the typical span between an update commit and
    /// a service crash with comfortable headroom for Spotify init / Wi-Fi flap noise.
    private String captureLogcat() {
        Process proc = null;
        BufferedReader reader = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "800"});
            reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int budget = MAX_LOG_BYTES;
            while ((line = reader.readLine()) != null) {
                int needed = line.length() + 1;
                if (needed > budget) {
                    sb.append("[truncated -- exceeded ").append(MAX_LOG_BYTES).append(" bytes]\n");
                    break;
                }
                sb.append(line).append('\n');
                budget -= needed;
            }
            return sb.toString();
        } catch (Exception ex) {
            return "logcat capture failed: " + ex.getMessage();
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignore) {}
            if (proc != null) proc.destroy();
        }
    }

    private void upload(String logs) {
        SharedPreferences prefs = appContext.getSharedPreferences("kiosk-prefs", Context.MODE_PRIVATE);
        String haUrl = prefs.getString("dashboard-url", null);
        String token = BuildConfig.HA_TOKEN;
        if (haUrl == null || haUrl.isEmpty() || token == null || token.isEmpty()) {
            Log.w(TAG, "no HA URL or token; can't upload logs");
            return;
        }
        String haBase = stripPath(haUrl);

        String slug = slugify(deviceName);
        String now  = isoNow();
        String header = "device: " + deviceName + "\n"
                      + "version: " + BuildConfig.VERSION_NAME + " (code " + BuildConfig.VERSION_CODE + ")\n"
                      + "captured: " + now + "\n"
                      + "------------------------------------------------------------\n";
        // Wrap in fenced code block so HA's markdown renderer leaves the formatting intact in
        // the notifications panel, and so my reader (curl + jq) gets a predictable boundary.
        String message = header + "```\n" + logs + "\n```";

        JsonObject body = new JsonObject();
        body.addProperty("notification_id", "kiosk_logs_" + slug);
        body.addProperty("title", "Kiosk logs: " + deviceName);
        body.add("message", new JsonPrimitive(message));

        HttpURLConnection conn = null;
        try {
            URL url = new URL(haBase + "/api/services/persistent_notification/create");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");

            byte[] bytes = body.toString().getBytes("UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                Log.i(TAG, "logs uploaded to persistent_notification.kiosk_logs_" + slug
                        + " (" + bytes.length + " bytes)");
            } else {
                Log.w(TAG, "logs upload HTTP " + code);
            }
        } catch (Exception ex) {
            Log.w(TAG, "logs upload failed: " + ex.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /// Strips path component from an HA dashboard URL, leaving scheme://host:port. Tolerates
    /// the URL having no path at all. Mirrors the logic in HaWebSocketClient.toWebSocketUrl
    /// but emits http(s) rather than ws(s).
    private static String stripPath(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return "http://" + url;
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }

    private static String slugify(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static String isoNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }
}
