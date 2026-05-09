package com.thinkview.kiosk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import org.json.JSONArray;
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
 * Uses GitHub's REST API (api.github.com) so the same code path works on both public and
 * private repos -- a Bearer token from BuildConfig.GITHUB_TOKEN authenticates every request.
 * Public-repo flow used to be:
 *   GET https://github.com/.../releases/latest/download/manifest.json  (CDN, no auth)
 * which 404s once the repo flips private. The API flow:
 *   1. GET /repos/{owner}/{repo}/releases/latest (with Bearer token)
 *      -> returns JSON with assets[] each having a 'url' API endpoint and a 'name'
 *   2. Find the asset by name, GET its 'url' with Accept: application/octet-stream
 *      -> returns the raw asset bytes
 *
 * Manifest format (unchanged):
 *   {"versionCode": 5, "apkUrl": "https://..."}
 *
 * If versionCode > our installed BuildConfig.VERSION_CODE, we download the APK to the cache
 * dir and hand it to PackageInstaller. With device-owner privileges, the install is silent.
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
        String token = BuildConfig.GITHUB_TOKEN;
        if (token.isEmpty()) {
            Log.w(TAG, "no GitHub token baked into BuildConfig -- private-repo updates unavailable");
        }

        // 1. List the latest release.
        String releaseUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";
        JSONObject release = fetchJson(releaseUrl, token);

        // 2. Find manifest.json + thinkview-kiosk.apk assets by name.
        JSONArray assets = release.getJSONArray("assets");
        String manifestApiUrl = null;
        String apkApiUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.getString("name");
            String url  = a.getString("url"); // api.github.com/.../assets/{id}
            if (name.equals("manifest.json"))         manifestApiUrl = url;
            else if (name.endsWith(".apk"))           apkApiUrl = url;
        }
        if (manifestApiUrl == null) {
            Log.w(TAG, "no manifest.json asset on latest release");
            return;
        }

        // 3. Fetch manifest as bytes.
        byte[] manifestBytes = fetchAsset(manifestApiUrl, token);
        JSONObject manifest = new JSONObject(new String(manifestBytes, "UTF-8"));
        int latestVersion = manifest.getInt("versionCode");

        int currentVersion = appContext.getPackageManager()
                .getPackageInfo(appContext.getPackageName(), 0).versionCode;
        Log.i(TAG, "current=" + currentVersion + " latest=" + latestVersion);
        if (latestVersion <= currentVersion) return;

        if (apkApiUrl == null) {
            Log.w(TAG, "no .apk asset on latest release");
            return;
        }

        // 4. Download APK + install.
        File apkFile = downloadApkFromApi(appContext, apkApiUrl, token);
        installApk(appContext, apkFile);
    }

    /// GET an api.github.com URL expecting a JSON response.
    private static JSONObject fetchJson(String urlStr, String token) throws IOException, org.json.JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("GitHub API HTTP " + code + " on " + urlStr);
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

    /// GET an asset by its api.github.com URL with Accept: application/octet-stream. Returns
    /// the raw bytes (used for small JSON manifests).
    private static byte[] fetchAsset(String urlStr, String token) throws IOException {
        HttpURLConnection conn = openAsset(urlStr, token);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("asset HTTP " + code + " on " + urlStr);
            InputStream in = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static File downloadApkFromApi(Context appContext, String urlStr, String token) throws IOException {
        File outFile = new File(appContext.getCacheDir(), "kiosk-update.apk");
        if (outFile.exists()) outFile.delete();

        HttpURLConnection conn = openAsset(urlStr, token);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("apk HTTP " + code + " on " + urlStr);
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

    private static HttpURLConnection openAsset(String urlStr, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/octet-stream");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
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
