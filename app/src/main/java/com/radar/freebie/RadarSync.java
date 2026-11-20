package com.radar.freebie;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据拉取 + 增量比对 + 本地通知
 * 数据源：GitHub raw 的 docs/data/data.json
 */
public class RadarSync {

    /** 数据地址（可换 CDN 加速） */
    public static final String DATA_URL =
            "https://raw.githubusercontent.com/573401692/freebie-radar-mobile/main/docs/data/data.json";

    /** 备用地址（jsDelivr 加速，国内更快） */
    public static final String DATA_URL_BACKUP =
            "https://cdn.jsdelivr.net/gh/573401692/freebie-radar-mobile@main/docs/data/data.json";

    private static final String PREFS = "radar";
    private static final String KEY_SEEN = "seen_keys";
    private static final String KEY_LAST_FETCH = "last_fetch";
    private static final String CHANNEL_ID = "radar_new";

    public static class Result {
        public boolean ok = false;
        public String error = "";
        public int total = 0;
        public int newCount = 0;
        public List<RadarItem> items = new ArrayList<>();
        public List<RadarItem> newItems = new ArrayList<>();
        public String generatedAt = "";
    }

    /** 执行一次完整同步 */
    public static Result sync(Context ctx, boolean notify) {
        Result r = new Result();
        String body = null;
        String err = "";

        for (String u : new String[]{DATA_URL_BACKUP, DATA_URL}) {
            try {
                body = httpGet(u);
                if (body != null && body.length() > 10) break;
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                body = null;
            }
        }

        if (body == null) {
            r.error = err.isEmpty() ? "网络请求失败" : err;
            return r;
        }

        try {
            JSONObject root = new JSONObject(body);
            r.generatedAt = root.optString("generatedAt", "");
            r.total = root.optInt("total", 0);
            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) r.items.add(RadarItem.fromJson(o));
                }
            }

            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> seen = new HashSet<>(sp.getStringSet(KEY_SEEN, new HashSet<String>()));

            if (seen.isEmpty()) {
                // 首次同步：不发通知，只记录基线
                for (RadarItem it : r.items) seen.add(it.key());
                sp.edit()
                        .putStringSet(KEY_SEEN, seen)
                        .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                        .apply();
                r.newCount = 0;
            } else {
                for (RadarItem it : r.items) {
                    if (!seen.contains(it.key())) {
                        r.newItems.add(it);
                        seen.add(it.key());
                    }
                }
                r.newCount = r.newItems.size();
                sp.edit()
                        .putStringSet(KEY_SEEN, seen)
                        .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                        .apply();

                if (notify && !r.newItems.isEmpty()) {
                    notifyNew(ctx, r.newItems);
                }
            }
            r.ok = true;
        } catch (Exception e) {
            r.error = "解析失败: " + e.getMessage();
        }
        return r;
    }

    public static long lastFetch(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_FETCH, 0L);
    }

    /** 简单 HTTP GET */
    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("User-Agent", "FreebieRadar-Android/1.0");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 发通知：单条汇总或逐条（超过3条则汇总） */
    private static void notifyNew(Context ctx, List<RadarItem> items) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "新福利提醒", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("资源雷达发现新增福利时提醒");
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        if (items.size() == 1) {
            RadarItem it = items.get(0);
            Notification.Builder b = buildBase(ctx, pi)
                    .setContentTitle("🎁 " + it.title)
                    .setContentText(it.sourceName + " · " + it.summary)
                    .setStyle(new Notification.BigTextStyle().bigText(it.summary));
            nm.notify(1001, b.build());
        } else {
            StringBuilder sb = new StringBuilder();
            int show = Math.min(items.size(), 5);
            for (int i = 0; i < show; i++) {
                sb.append("· ").append(items.get(i).title).append("\n");
            }
            if (items.size() > show) sb.append("…还有 ").append(items.size() - show).append(" 条");

            Notification.Builder b = buildBase(ctx, pi)
                    .setContentTitle("资源雷达发现 " + items.size() + " 条新福利")
                    .setContentText(items.get(0).title)
                    .setStyle(new Notification.BigTextStyle().bigText(sb.toString()));
            nm.notify(1001, b.build());
        }
    }

    /** 构造基础 Notification.Builder，兼容安卓 8 以下 */
    private static Notification.Builder buildBase(Context ctx, PendingIntent pi) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pi);
        return b;
    }
}