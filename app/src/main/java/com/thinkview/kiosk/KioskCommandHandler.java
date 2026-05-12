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
 *   disable_siren     -> alarm-siren-enabled = false. The websocket service stays up
 *                        (kiosk_command remains reachable); only the alarm overlay is
 *                        suppressed at trigger time.
 *   enable_siren      -> alarm-siren-enabled = true. Same service stays up; flag flipped
 *                        so the next trigger fires the overlay.
 *   set_can_disarm    -> alarm-can-disarm = (value parsed as bool). Controls whether the
 *                        AlarmActivity overlay shows the SILENCE button (which, on v24+,
 *                        actually fires HA's alarm_disarm service rather than just stopping
 *                        the local siren). When false, the overlay shows a "disarm from the
 *                        Alarmo panel" hint instead. Useful for shared / kid rooms where
 *                        you want the siren to keep wailing until disarmed at a designated
 *                        location (e.g. master bedroom).
 *   set_disarm_code   -> alarm-disarm-code = <value>. Optional PIN passed to HA's
 *                        alarm_disarm service. Empty means "no code" (works for Alarmo
 *                        configurations that don't require a code from trusted devices).
 *   set_brightness    -> screen-brightness = <value>. Accepts 0.0-1.0 fraction or 0-100
 *                        percent (anything > 1.5 is interpreted as a percentage). Applied
 *                        live to the running MainActivity's window and persisted to
 *                        SharedPreferences. Useful for "alarm armed -> dim bedroom panel"
 *                        style HA automations.
 *   set_autoplay      -> spotify-autoplay = (value parsed as bool). Default false. When
 *                        true, librespot's autoplay loads a radio station based on the
 *                        current artist when the queue exhausts. When false (recommended
 *                        for foreground kiosks), playback stops at end of queue. Takes
 *                        effect on the next Spotify Connect session -- reconnect from
 *                        phone after toggling.
 *   play_media        -> Plays the audio URL in `value` via Android's MediaPlayer on
 *                        STREAM_ALARM (bypasses silent mode, ducks current music). Use
 *                        case: HA-driven alarm clocks, doorbell sounds, TTS announcements.
 *                        Replaces any in-progress play_media. Releases automatically on
 *                        completion / error.
 *   stop_media        -> Stops the current play_media playback, if any. No-op otherwise.
 *   set_volume        -> Sets the play_media playback volume as a per-sample software
 *                        gain inside MediaPlayer (0.0-1.0 fraction or 0-100 percent).
 *                        v33+: stores the value in pref `media-volume` and applies via
 *                        MediaPlayer.setVolume(), not via STREAM_ALARM index. The Lenovo
 *                        CD-18781Y's STREAM_ALARM curve is so steep that mid-range index
 *                        values are effectively inaudible -- software gain gives linear,
 *                        predictable attenuation across the full 0-100% range. STREAM_ALARM
 *                        itself stays pegged to hardware max while play_media is active so
 *                        we have full dynamic range to attenuate from. Does NOT affect the
 *                        security siren (independent peg/restore in SirenPlayer) or the
 *                        Spotify Connect volume (separate librespot protocol path).
 *   set_url           -> dashboard-url = <value>; start MainActivity with VIEW intent so the
 *                        WebView navigates immediately
 *   set_display_name  -> display-name = <value>; trigger SpotifyConnectService to rebuild its
 *                        Zeroconf advertisement in place under the new name (multicast lock
 *                        held continuously, foreground service stays up); hot-update
 *                        AlarmListenerService's kiosk_command target filter without bouncing
 *                        the HA websocket
 *   restart_spotify   -> trigger SpotifyConnectService to rebuild Zeroconf without changing
 *                        the name. Forcing function for "phone Spotify lost track of me"
 *   ping              -> log a line. Lets you confirm a device is alive and reachable on the
 *                        HA event bus before issuing real commands.
 *   get_logs          -> capture recent logcat and POST to HA's persistent_notification
 *                        service so a maintainer can read remote diagnostics via
 *                        GET /api/states/persistent_notification.kiosk_logs_<device-slug>
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
                // Don't stop the service -- it owns the kiosk_command websocket. Just flip
                // the pref; AlarmListenerService.onAlarmTriggered checks it before firing
                // the AlarmActivity overlay. Earlier versions stopped the service here,
                // which orphaned the device from remote control (you couldn't even
                // re-enable the siren without ADB or a re-provision).
                prefs.edit().putBoolean("alarm-siren-enabled", false).apply();
                Log.i(TAG, "alarm-siren-enabled = false (websocket stays up; only the overlay is gated)");
                break;
            case "enable_siren":
                prefs.edit().putBoolean("alarm-siren-enabled", true).apply();
                Log.i(TAG, "alarm-siren-enabled = true");
                // Service is already running (started by MainActivity unconditionally on
                // v22+). Belt-and-braces start in case we got here on a quirky launch path
                // where MainActivity hasn't reached its onCreate yet.
                try {
                    app.startForegroundService(new Intent(app, AlarmListenerService.class));
                } catch (Exception ex) {
                    Log.w(TAG, "couldn't start AlarmListenerService: " + ex.getMessage());
                }
                break;
            case "set_can_disarm":
                // Accept "true"/"false"/"1"/"0"/"yes"/"no". Anything else falls through to
                // false to err on the conservative side -- if you typo'd the value, better
                // to lock the silence button than accidentally unlock it.
                boolean canDisarm = "true".equalsIgnoreCase(value)
                        || "1".equals(value)
                        || "yes".equalsIgnoreCase(value);
                prefs.edit().putBoolean("alarm-can-disarm", canDisarm).apply();
                Log.i(TAG, "alarm-can-disarm = " + canDisarm);
                break;
            case "set_disarm_code":
                // Empty string means "no code" -- explicitly clearing a previously-set code.
                // Accept null too just in case the event has a missing value field.
                String newCode = value == null ? "" : value;
                prefs.edit().putString("alarm-disarm-code", newCode).apply();
                Log.i(TAG, "alarm-disarm-code " + (newCode.isEmpty() ? "cleared" : "set"));
                break;
            case "set_autoplay":
                boolean autoplayOn = "true".equalsIgnoreCase(value)
                        || "1".equals(value)
                        || "yes".equalsIgnoreCase(value);
                prefs.edit().putBoolean("spotify-autoplay", autoplayOn).apply();
                Log.i(TAG, "spotify-autoplay = " + autoplayOn
                        + " (takes effect on next Connect session)");
                break;
            case "play_media":
                if (value == null || value.isEmpty()) {
                    Log.w(TAG, "play_media with empty value; ignoring");
                    return;
                }
                Log.i(TAG, "play_media: " + value);
                MediaPlaybackHelper.play(app, value);
                break;
            case "stop_media":
                Log.i(TAG, "stop_media");
                MediaPlaybackHelper.stop(app);
                break;
            case "set_volume":
                if (value == null || value.isEmpty()) {
                    Log.w(TAG, "set_volume with empty value; ignoring");
                    return;
                }
                float vol;
                try {
                    vol = Float.parseFloat(value);
                } catch (NumberFormatException nfe) {
                    Log.w(TAG, "set_volume with non-numeric value '" + value + "'; ignoring");
                    return;
                }
                // Accept fraction (0.0-1.0) or percentage (0-100). Mirrors set_brightness.
                if (vol > 1.5f) vol = vol / 100f;
                if (vol < 0f) vol = 0f;
                if (vol > 1f) vol = 1f;

                // v33+: software gain on MediaPlayer, not STREAM_ALARM index. The Lenovo
                // CD-18781Y's STREAM_ALARM volume curve is so steep that 6/15 (40%) is
                // effectively inaudible while 15/15 (100%) is full output. Apply volume
                // as a per-sample software attenuation inside MediaPlayer instead, where
                // the curve is linear and predictable. play_media keeps STREAM_ALARM
                // pegged to max during playback so our software gain has the full range
                // to work with.
                prefs.edit().putFloat("media-volume", vol).apply();
                Log.i(TAG, "media-volume = " + vol);
                MediaPlaybackHelper.applyVolumeToCurrent(vol);
                break;
            case "set_brightness":
                if (value == null || value.isEmpty()) {
                    Log.w(TAG, "set_brightness with empty value; ignoring");
                    return;
                }
                float brightness;
                try {
                    brightness = Float.parseFloat(value);
                } catch (NumberFormatException nfe) {
                    Log.w(TAG, "set_brightness with non-numeric value '" + value + "'; ignoring");
                    return;
                }
                // Accept either fractional (0.0-1.0) or percentage (0-100) input. Anything
                // above 1.5 is unambiguously a percentage; convert.
                if (brightness > 1.5f) brightness = brightness / 100f;
                // Clamp to [0.05, 1.0]. Below 5% the screen is functionally unreadable; we
                // don't want a remote command (or a typo) to lock the user out of touch
                // interaction.
                if (brightness < 0.05f) brightness = 0.05f;
                if (brightness > 1.0f)  brightness = 1.0f;
                prefs.edit().putFloat("screen-brightness", brightness).apply();
                Log.i(TAG, "screen-brightness = " + brightness);
                // Apply to the live activity if it's running. If it isn't, the next launch
                // will pick up the pref via MainActivity.applyBrightness in onCreate.
                MainActivity main = MainActivity.getInstance();
                if (main != null) {
                    main.runOnUiThread(new BrightnessApplyTask(main));
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
                // Rebuild Zeroconf in place via an intent extra delivered to the running
                // SpotifyConnectService. Compared to stopService + startForegroundService:
                //   - multicast lock stays held the whole time (no Android-side mDNS gap)
                //   - foreground service notification stays up
                //   - eliminates the lifecycle race where startForegroundService coalesces
                //     with a pending stop and onStartCommand goes to the OLD instance
                Intent rebuild = new Intent(app, SpotifyConnectService.class);
                rebuild.putExtra("rebuild", true);
                try {
                    app.startForegroundService(rebuild);
                } catch (Exception ex) {
                    Log.w(TAG, "couldn't trigger Spotify rebuild: " + ex.getMessage());
                }
                // Hot-update the alarm listener's filter so the next kiosk_command targeting
                // the new name reaches us. No websocket bounce -- saves an HA reconnect storm.
                AlarmListenerService alarm = AlarmListenerService.getInstance();
                if (alarm != null) alarm.updateDeviceName(value);
                break;
            case "restart_spotify":
                // Forcing function for "the phone's Spotify lost track of me" or
                // "the device went into a weird mDNS state". Re-fires the announcement under
                // the existing display name without changing anything else.
                Log.i(TAG, "restart_spotify -- rebuilding Zeroconf without rename");
                Intent restartSpotify = new Intent(app, SpotifyConnectService.class);
                restartSpotify.putExtra("rebuild", true);
                try {
                    app.startForegroundService(restartSpotify);
                } catch (Exception ex) {
                    Log.w(TAG, "couldn't trigger Spotify rebuild: " + ex.getMessage());
                }
                break;
            case "ping":
                // Liveness probe. If you fire `ping` and see this log line within a few
                // seconds, the device's HA WebSocket is reachable and kiosk_command routing
                // is working. Useful when half the fleet is silent and you need to know which
                // ones are bricked vs which ones are just not in your Spotify cache.
                Log.i(TAG, "ping received (value='" + value + "')");
                break;
            case "get_logs":
                // Fleet diagnostic. Captures recent logcat and uploads it to HA as a
                // persistent notification, where a remote maintainer can read it via
                // GET /api/states/persistent_notification.kiosk_logs_<slug>. Off the websocket
                // dispatch thread because logcat capture + HTTPS upload can take a couple
                // seconds, and we don't want to block other kiosk_command events behind it.
                Log.i(TAG, "get_logs requested -- capturing logcat");
                String dn = prefs.getString("display-name", "ThinkView Kiosk");
                Thread t = new Thread(new LogCaptureTask(app, dn), "kiosk-log-capture");
                t.setDaemon(true);
                t.start();
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
