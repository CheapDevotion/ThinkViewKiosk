package com.thinkview.kiosk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Dispatcher for kiosk_command events delivered over the HA WebSocket. Each command mutates
 * SharedPreferences and/or starts/stops services to apply the change. Designed to compose with
 * the existing intent-extras pattern so a remote command produces the same end state as
 * `adb shell am start --es ... -n com.thinkview.kiosk/.MainActivity`.
 *
 * Supported commands:
 *   disable_siren     -> alarm-siren-enabled = false; stop AlarmListenerService
 *   enable_siren      -> alarm-siren-enabled = true;  start AlarmListenerService (no-op if up)
 *   set_url           -> dashboard-url = <value>; start MainActivity with VIEW intent so the
 *                        WebView navigates immediately
 *   set_display_name  -> display-name = <value>; bounce SpotifyConnectService so Zeroconf
 *                        re-advertises with the new name; hot-update AlarmListenerService's
 *                        kiosk_command target filter without bouncing the websocket
 *   reload            -> tell MainActivity to reload current URL
 *   restart           -> kill MainActivity (Android relaunches via HOME) -- useful after pushed
 *                        config changes that need a clean boot
 */
class KioskCommandHandler {
    private static final String TAG = "ThinkViewKiosk/Cmd";

    static void handle(Context context, String command, String value) {
        if (command == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("kiosk-prefs", Context.MODE_PRIVATE);

        switch (command) {
            case "disable_siren":
                prefs.edit().putBoolean("alarm-siren-enabled", false).apply();
                Log.i(TAG, "alarm-siren-enabled = false");
                app.stopService(new Intent(app, AlarmListenerService.class));
                break;
            case "enable_siren":
                prefs.edit().putBoolean("alarm-siren-enabled", true).apply();
                Log.i(TAG, "alarm-siren-enabled = true");
                try {
                    app.startForegroundService(new Intent(app, AlarmListenerService.class));
                } catch (Exception ex) {
                    Log.w(TAG, "couldn't start AlarmListenerService: " + ex.getMessage());
                }
                break;
            case "set_url":
                if (value == null || value.isEmpty()) {
                    Log.w(TAG, "set_url with empty value; ignoring");
                    return;
                }
                prefs.edit().putString("dashboard-url", value).apply();
                Log.i(TAG, "dashboard-url = " + value);
                Intent setUrl = new Intent(app, MainActivity.class);
                setUrl.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                setUrl.putExtra("url", value);
                app.startActivity(setUrl);
                break;
            case "set_display_name":
                if (value == null || value.isEmpty()) {
                    Log.w(TAG, "set_display_name with empty value; ignoring");
                    return;
                }
                prefs.edit().putString("display-name", value).apply();
                Log.i(TAG, "display-name = " + value);
                // Bounce Spotify Connect so ZeroconfServer re-advertises under the new name.
                // Zeroconf's device name is set at construction time -- there's no "rename"
                // hook, so we tear down and rebuild.
                app.stopService(new Intent(app, SpotifyConnectService.class));
                try {
                    app.startForegroundService(new Intent(app, SpotifyConnectService.class));
                } catch (Exception ex) {
                    Log.w(TAG, "couldn't restart SpotifyConnectService: " + ex.getMessage());
                }
                // Hot-update the alarm listener's filter so the next kiosk_command targeting
                // the new name reaches us. No websocket bounce -- saves an HA reconnect storm.
                AlarmListenerService alarm = AlarmListenerService.getInstance();
                if (alarm != null) alarm.updateDeviceName(value);
                break;
            case "reload":
                Intent reload = new Intent(app, MainActivity.class);
                reload.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                reload.putExtra("url", prefs.getString("dashboard-url", ""));
                app.startActivity(reload);
                break;
            case "restart":
                // Don't actually kill anything (we're a foreground service; killing self is
                // disruptive). Just relaunch MainActivity on top to pick up any new prefs.
                Intent restart = new Intent(app, MainActivity.class);
                restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                app.startActivity(restart);
                break;
            default:
                Log.w(TAG, "unknown command: " + command);
        }
    }
}
