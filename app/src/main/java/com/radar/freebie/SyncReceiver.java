package com.radar.freebie;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 定时同步接收器
 * 触发一次后台拉取，并发通知；随后重新排下一次，形成循环。
 */
public class SyncReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();

        // 用线程执行网络请求（BroadcastReceiver 不能做耗时操作）
        new Thread(() -> {
            try {
                RadarSync.sync(app, true);
            } catch (Throwable ignored) {
            } finally {
                // 排下一次，维持循环
                ScheduleHelper.schedule(app);
            }
        }, "radar-sync").start();
    }
}