package com.radar.freebie;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 定时任务调度
 * 用 AlarmManager 每 2 小时唤醒一次做增量拉取。
 * 不用 WorkManager 是为了零第三方依赖，减小包体。
 */
public class ScheduleHelper {

    /** 拉取间隔：2 小时（毫秒） */
    private static final long INTERVAL = 2L * 60 * 60 * 1000;

    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(ctx, SyncReceiver.class);
        i.setAction("com.radar.freebie.SYNC");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1001, i, flags);

        long triggerAt = System.currentTimeMillis() + INTERVAL;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 非精确闹钟，省电且不需特殊权限
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception ignored) {
        }

        // 同时排一个循环：每次触发后再排下一个
        // （在 SyncReceiver 里重新调用 schedule 即可维持循环）
    }

    /** 立即触发一次 */
    public static void triggerNow(Context ctx) {
        Intent i = new Intent(ctx, SyncReceiver.class);
        i.setAction("com.radar.freebie.SYNC");
        ctx.sendBroadcast(i);
    }
}