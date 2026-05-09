package com.thinkview.kiosk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that holds an authenticated WebSocket connection to Home Assistant.
 * Fires {@link AlarmActivity} the instant the configured alarm_control_panel transitions to
 * 'triggered' state -- but ONLY if alarm_siren_enabled is true at the moment of trigger.
 *
 * The service runs on every device regardless of alarm-siren-enabled, because this is also
 * the bus that delivers kiosk_command events from HA. Earlier versions stopped the service
 * when siren was disabled; that left the device deaf to all future remote commands (including
 * the one to re-enable the siren) until the next reboot, which itself wouldn't help because
 * MainActivity used to gate the start on the same pref. v22 decouples: websocket always
 * connected, alarm overlay gated at trigger time.
 */
public class AlarmListenerService extends Service implements HaWebSocketClient.Listener {
    private static final String TAG = "ThinkViewKiosk/Alarm";
    private static final String CHANNEL_ID = "kiosk-alarm";
    private static final int NOTIFICATION_ID = 9002;

    private static volatile AlarmListenerService instance;
    private HaWebSocketClient client;

    /// Live reference to the running service (or null) so KioskCommandHandler can hot-update
    /// the device name on the existing websocket without bouncing the connection.
    static AlarmListenerService getInstance() { return instance; }

    void updateDeviceName(String newName) {
        if (client != null) client.setDeviceName(newName);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForeground(NOTIFICATION_ID, buildNotification("Listening for alarm…"));

        SharedPreferences prefs = getSharedPreferences("kiosk-prefs", MODE_PRIVATE);
        String haUrl       = prefs.getString("dashboard-url", null);
        String token       = prefs.getString("ha-token", null);
        String alarmEntity = prefs.getString("alarm-entity", null);
        String deviceName  = prefs.getString("display-name", "ThinkView Kiosk");

        // Fall back to baked-in values from BuildConfig (compile-time constants populated from
        // gitignored secrets.properties). Lets adult-area devices Just Work without per-device
        // intent extras, while letting kid-room devices override via SharedPreferences.
        if (token == null || token.isEmpty())             token = BuildConfig.HA_TOKEN;
        if (alarmEntity == null || alarmEntity.isEmpty()) alarmEntity = BuildConfig.HA_ALARM_ENTITY;

        if (haUrl == null || haUrl.isEmpty() || token == null || token.isEmpty()) {
            Log.w(TAG, "missing HA URL or token; alarm listener cannot start");
            updateNotification("Misconfigured -- no HA URL or token");
            return;
        }
        Log.i(TAG, "starting alarm listener for " + alarmEntity + " on " + haUrl + " (device='" + deviceName + "')");
        client = new HaWebSocketClient(this, haUrl, token, alarmEntity, deviceName, this);
        client.connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (client != null) client.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // HaWebSocketClient.Listener

    @Override
    public void onAlarmTriggered(String triggerInfo) {
        // Gate at trigger time: kid-room devices have alarm-siren-enabled=false and silently
        // drop the event. We still log it so logcat tells you whether the trigger reached the
        // device -- useful for confirming that "no siren on this device" was the pref talking,
        // not the websocket dropping the event.
        SharedPreferences prefs = getSharedPreferences("kiosk-prefs", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("alarm-siren-enabled", true);
        if (!enabled) {
            Log.i(TAG, "alarm triggered but siren is disabled on this device; skipping overlay");
            return;
        }
        Intent intent = new Intent(this, AlarmActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("trigger", triggerInfo);
        startActivity(intent);
    }

    @Override
    public void onAlarmCleared() {
        // Auto-dismiss the overlay if it's up. Idempotent -- if no AlarmActivity is showing
        // (e.g. siren is disabled on this device), this no-ops.
        AlarmActivity.dismissIfShown();
    }

    @Override
    public void onConnectionState(boolean connected) {
        updateNotification(connected ? "Listening for alarm" : "Reconnecting to HA…");
    }

    @Override
    public void onCommandReceived(String command, String value) {
        KioskCommandHandler.handle(this, command, value);
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Alarm Listener", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("ThinkView Kiosk")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
