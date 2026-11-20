package com.radar.freebie;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后，恢复定时任务 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ScheduleHelper.schedule(context.getApplicationContext());
        }
    }
}