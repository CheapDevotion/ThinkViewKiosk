package com.thinkview.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Background task that calls Home Assistant's alarm_control_panel.alarm_disarm service. Used
 * by the SILENCE button on AlarmActivity to actually disarm the alarm system in HA rather
 * than just stop the local siren.
 *
 * Once HA disarms, the alarm entity transitions out of 'triggered' state. That state change
 * propagates back over the WebSocket to *every* connected kiosk in the house, each of which
 * fires its onAlarmCleared path and dismisses any open AlarmActivity overlay. So one tap on
 * a designated panel (e.g. master bedroom) silences and dismisses the alarm everywhere
 * automatically.
 *
 * Top-level Runnable for d8 sanity (anonymous-inner-class issues have bitten us enough times
 * in this codebase to make a habit of avoiding them).
 *
 * Optional disarm code: HA's Alarmo integration can be configured to require a PIN. If the
 * pref alarm-disarm-code is set (provisioned via intent extra alarm_disarm_code or pushed
 * via remote command), it's included in the service call. If empty, no code is sent --
 * works for Alarmo configurations where disarm doesn't require a code from trusted devices.
 */
class AlarmDisarmTask implements Runnable {
    private static final String TAG = "ThinkViewKiosk/Alarm";

    private final Context appContext;

    AlarmDisarmTask(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    @Override
    public void run() {
        SharedPreferences prefs = appContext.getSharedPreferences("kiosk-prefs", Context.MODE_PRIVATE);
        String haUrl       = prefs.getString("dashboard-url", null);
        String alarmEntity = prefs.getString("alarm-entity", BuildConfig.HA_ALARM_ENTITY);
        String disarmCode  = prefs.getString("alarm-disarm-code", "");
        String token       = BuildConfig.HA_TOKEN;

        if (haUrl == null || haUrl.isEmpty() || token == null || token.isEmpty()
                || alarmEntity == null || alarmEntity.isEmpty()) {
            Log.w(TAG, "missing HA URL/token/alarm entity; can't fire disarm");
            return;
        }
        String haBase = stripPath(haUrl);

        // POST shape: { "entity_id": "alarm_control_panel.alarmo", "code": "1234" }
        // Alarmo accepts entity_id as either a string or an array; we send a string.
        JsonObject body = new JsonObject();
        body.addProperty("entity_id", alarmEntity);
        if (disarmCode != null && !disarmCode.isEmpty()) {
            body.addProperty("code", disarmCode);
        }

        OkHttpClient http = new OkHttpClient.Builder()
                .dns(new MdnsDns(appContext))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        Request req = new Request.Builder()
                .url(haBase + "/api/services/alarm_control_panel/alarm_disarm")
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (resp.isSuccessful()) {
                Log.i(TAG, "alarm disarm fired against " + alarmEntity
                        + " (code " + (disarmCode == null || disarmCode.isEmpty() ? "no" : "yes") + ")");
            } else {
                Log.w(TAG, "alarm disarm HTTP " + resp.code());
            }
        } catch (Exception ex) {
            Log.w(TAG, "alarm disarm failed: " + ex.getMessage());
        }
    }

    /// Strips path component from an HA dashboard URL, leaving scheme://host:port. Mirrors
    /// the same helper in LogCaptureTask. Two helpers because the alternative is a small
    /// util class with one method, and that's not pulling its weight yet.
    private static String stripPath(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return "http://" + url;
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }

    // Intentionally unused -- kept as a hint that the schema accepts arrays too, in case
    // we ever need to disarm multiple entities atomically.
    @SuppressWarnings("unused")
    private static JsonArray asArray(String entityId) {
        JsonArray arr = new JsonArray();
        arr.add(entityId);
        return arr;
    }
}
