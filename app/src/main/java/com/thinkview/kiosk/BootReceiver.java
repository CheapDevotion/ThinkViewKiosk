package com.thinkview.kiosk;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Launches MainActivity on device boot so the kiosk is up without the user touching anything.
 * Even when this app is the system home launcher, booting straight into HOME isn't always
 * reliable on Android 8.1 (the launcher dispatch can race with package state) -- explicitly
 * starting MainActivity from BOOT_COMPLETED makes the foreground state deterministic.
 *
 * Also schedules the periodic UpdateAlarmReceiver. Interval is set short (DEV) while we're
 * iterating; bump it back up once the kiosk is stable.
 */
public class BootReceiver extends BroadcastReceiver {

    // DEV: 5 minutes for fast iteration. Production should be 12h or so.
    static final long UPDATE_INTERVAL_MS = 5L * 60L * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;
        if (!action.equals(Intent.ACTION_BOOT_COMPLETED)
                && !action.equals("android.intent.action.QUICKBOOT_POWERON")) {
            return;
        }
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);

        scheduleUpdateAlarm(context);
    }

    static void scheduleUpdateAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, UpdateAlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // setRepeating is reliable on API 27 (no idle deferral, no inexact bucketing) and
        // honors short intervals like 5 minutes. Newer Android tightens this; our target is 27.
        am.setRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + UPDATE_INTERVAL_MS,
                UPDATE_INTERVAL_MS,
                pi);
    }
}
