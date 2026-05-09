package com.thinkview.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

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

    /// Runs `logcat -d` in our own UID and filters to kiosk-relevant tags, capturing up to
    /// MAX_LOG_BYTES of output. We don't use logcat's `-t N` line cap because the unfiltered
    /// stream is dominated by GeckoView's MozAfterPaint chatter -- in the v18 self-test, all
    /// 800 lines were Gecko debug noise and not a single kiosk line made it past the budget.
    /// Better to walk the entire ring and pick out the lines we care about.
    private String captureLogcat() {
        Process proc = null;
        BufferedReader reader = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"logcat", "-d"});
            reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int budget = MAX_LOG_BYTES;
            int kept = 0;
            int dropped = 0;
            while ((line = reader.readLine()) != null) {
                if (!isInteresting(line)) { dropped++; continue; }
                int needed = line.length() + 1;
                if (needed > budget) {
                    sb.append("[truncated -- exceeded ").append(MAX_LOG_BYTES).append(" bytes]\n");
                    break;
                }
                sb.append(line).append('\n');
                budget -= needed;
                kept++;
            }
            sb.append("\n--- captured ").append(kept).append(" interesting lines, skipped ")
              .append(dropped).append(" noisy ones (Gecko etc.) ---\n");
            return sb.toString();
        } catch (Exception ex) {
            return "logcat capture failed: " + ex.getMessage();
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignore) {}
            if (proc != null) proc.destroy();
        }
    }

    /// Filter test for logcat lines worth uploading. The kiosk subprocess is the only owner
    /// of this UID's log stream, so we get our own app's output + GeckoView's child process
    /// (which is verbose at debug level) + AndroidRuntime crash dumps (which always appear in
    /// our process when we crash). The filter keeps:
    ///   - Anything tagged "ThinkViewKiosk*" (our own logs across all sub-tags)
    ///   - Anything containing "librespot" (Spotify Connect internals)
    ///   - AndroidRuntime / FATAL / SIGSEGV / DEBUG (crash dumps)
    ///   - PackageManager / PackageInstaller / ActivityManager when they reference our package
    ///     (update + lifecycle visibility)
    /// Everything else (mostly Gecko per-frame paint events) gets dropped.
    private static boolean isInteresting(String line) {
        if (line.contains("ThinkViewKiosk")) return true;
        if (line.contains("librespot"))      return true;
        if (line.contains("AndroidRuntime")) return true;
        if (line.contains("FATAL EXCEPTION")) return true;
        if (line.contains("DEBUG   :"))      return true; // SIGSEGV stack frames
        if (line.contains("com.thinkview.kiosk")) {
            // PackageManager / ActivityManager / PackageInstaller events about us are useful.
            return line.contains("PackageManager")
                || line.contains("PackageInstaller")
                || line.contains("ActivityManager");
        }
        return false;
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

        // OkHttp + MdnsDns: HA URLs are typically homeassistant.local, and Android 8.1 has no
        // native mDNS. Plain HttpURLConnection bombs with "Unable to resolve host". Reusing
        // the same Dns plumbing the alarm websocket uses keeps log uploads working without a
        // separate resolution path.
        OkHttpClient http = new OkHttpClient.Builder()
                .dns(new MdnsDns(appContext))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        Request req = new Request.Builder()
                .url(haBase + "/api/services/persistent_notification/create")
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (resp.isSuccessful()) {
                Log.i(TAG, "logs uploaded to persistent_notification.kiosk_logs_" + slug);
            } else {
                Log.w(TAG, "logs upload HTTP " + resp.code());
            }
        } catch (Exception ex) {
            Log.w(TAG, "logs upload failed: " + ex.getMessage());
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
