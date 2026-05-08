package com.thinkview.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class UpdateAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("kiosk-prefs", Context.MODE_PRIVATE);
        String owner = prefs.getString("repo-owner", null);
        String repo  = prefs.getString("repo-name",  null);
        UpdateChecker.checkAsync(context, owner, repo);
    }
}
