package com.thinkview.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fires after our own APK is replaced by the auto-updater.
 *
 * Without this, PackageInstaller's silent upgrade kills the running process and Android does
 * not bring it back -- the kiosk sits dark until BOOT_COMPLETED or someone manually launches
 * MainActivity. For a fleet of headless wall-mounted devices, that is effectively a brick on
 * update day. We saw exactly this happen during the v10 -> v11 push: the install committed,
 * the process died, nothing started it again until the next reboot.
 *
 * MY_PACKAGE_REPLACED is delivered to the *new* install of our own package only (no need for
 * a data filter). This receiver therefore comes from v(N+1) when handling the v(N) -> v(N+1)
 * jump, which is what we want -- the new code restarts itself.
 */
public class PackageReplacedReceiver extends BroadcastReceiver {
    private static final String TAG = "ThinkViewKiosk/PackageReplaced";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "package replaced; relaunching MainActivity");
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(launch);
        } catch (Exception ex) {
            Log.w(TAG, "couldn't relaunch MainActivity: " + ex.getMessage());
        }
    }
}
