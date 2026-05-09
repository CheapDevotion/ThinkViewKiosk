package com.thinkview.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Empty DeviceAdminReceiver subclass. Its only job is to exist so the kiosk app can be
 * registered as device-owner via `adb shell dpm set-device-owner com.thinkview.kiosk/.AdminReceiver`.
 *
 * Once we're device-owner, the UpdateInstaller can install APKs silently (no user tap),
 * which is the whole point — we have 7+ devices to push updates to.
 */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
    }
}
