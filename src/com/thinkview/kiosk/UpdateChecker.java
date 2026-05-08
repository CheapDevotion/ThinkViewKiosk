package com.thinkview.kiosk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Pulls a release manifest from GitHub Releases and silently installs a newer APK if available.
 *
 * Manifest source: https://github.com/{owner}/{repo}/releases/latest/download/manifest.json
 * The "latest/download/<asset>" pattern resolves to the literal asset on whichever release is
 * marked "latest" -- no API calls, no rate-limit concerns.
 *
 * Manifest format:
 *   {"versionCode": 5, "apkUrl": "https://github.com/.../thinkview-kiosk.apk"}
 *
 * If versionCode > our installed BuildConfig.VERSION_CODE, we download the APK to the app's
 * cache dir and hand it to PackageInstaller. With device-owner privileges (set during
 * provisioning), the install is silent -- no user tap.
 */
public class UpdateChecker {
    private static final String TAG = "ThinkViewKiosk/UpdateChecker";

    public static void checkAsync(final Context context, final String repoOwner, final String repoName) {
        if (repoOwner == null || repoOwner.isEmpty() || repoName == null || repoName.isEmpty()) {
            Log.i(TAG, "no GitHub repo configured; skipping update check");
            return;
        }
        final Context appContext = context.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runCheck(appContext, repoOwner, repoName);
                } catch (Exception ex) {
                    Log.w(TAG, "update check failed: " + ex.getMessage());
                }
            }
        }, "thinkview-update-checker");
        t.setDaemon(true);
        t.start();
    }

    private static void runCheck(Context appContext, String owner, String repo) throws Exception {
        String manifestUrl = "https://github.com/" + owner + "/" + repo
                + "/releases/latest/download/manifest.json";
        JSONObject manifest = fetchJson(manifestUrl);
        int latestVersion = manifest.getInt("versionCode");
        String apkUrl = manifest.getString("apkUrl");

        int currentVersion = appContext.getPackageManager()
                .getPackageInfo(appContext.getPackageName(), 0).versionCode;

        Log.i(TAG, "current=" + currentVersion + " latest=" + latestVersion);
        if (latestVersion <= currentVersion) return;

        File apkFile = downloadApk(appContext, apkUrl);
        installApk(appContext, apkFile);
    }

    private static JSONObject fetchJson(String urlStr) throws IOException, org.json.JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("manifest HTTP " + code);
            InputStream in = new BufferedInputStream(conn.getInputStream());
            byte[] buf = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            conn.disconnect();
        }
    }

    private static File downloadApk(Context appContext, String urlStr) throws IOException {
        File outFile = new File(appContext.getCacheDir(), "kiosk-update.apk");
        if (outFile.exists()) outFile.delete();

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("apk HTTP " + code);
            InputStream in = new BufferedInputStream(conn.getInputStream());
            FileOutputStream out = new FileOutputStream(outFile);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
            Log.i(TAG, "downloaded " + outFile.length() + " bytes to " + outFile);
            return outFile;
        } finally {
            conn.disconnect();
        }
    }

    private static void installApk(Context appContext, File apkFile) throws IOException {
        PackageInstaller installer = appContext.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(appContext.getPackageName());

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            OutputStream sessionOut = session.openWrite("kiosk-update.apk", 0, apkFile.length());
            FileInputStream fis = new FileInputStream(apkFile);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = fis.read(buf)) > 0) sessionOut.write(buf, 0, n);
                session.fsync(sessionOut);
            } finally {
                fis.close();
                sessionOut.close();
            }

            // FLAG_MUTABLE is API 31+; on API 27 intents are mutable by default, which is what
            // PackageInstaller wants (it fills in EXTRA_STATUS).
            Intent intent = new Intent(appContext, InstallResultReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(appContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pi.getIntentSender());
            Log.i(TAG, "PackageInstaller session committed (id=" + sessionId + ")");
        } finally {
            session.close();
        }
    }

    public static class InstallResultReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.i(TAG, "install result status=" + status + " msg=" + msg);
        }
    }
}
