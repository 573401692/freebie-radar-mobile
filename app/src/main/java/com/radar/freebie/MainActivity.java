package com.radar.freebie;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 主界面：纯代码构建 UI，不依赖布局资源 */
public class MainActivity extends Activity {

    private LinearLayout listContainer;
    private TextView statusText;
    private ProgressBar progress;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<RadarItem> allItems = new ArrayList<>();
    private String currentFilter = "all";
    private LinearLayout filterBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        ScheduleHelper.schedule(this);
        requestNotifyPermission();
        doSync(true);
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));

        // 顶部栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.parseColor("#2563EB"));
        header.setPadding(dp(18), dp(22), dp(18), dp(14));

        TextView title = new TextView(this);
        title.setText("资源雷达");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("白嫖福利 · 限免 · 免费额度");
        subtitle.setTextColor(Color.parseColor("#BBD3FF"));
        subtitle.setTextSize(11);
        subtitle.setPadding(0, dp(3), 0, 0);
        header.addView(subtitle);

        statusText = new TextView(this);
        statusText.setText("正在加载…");
        statusText.setTextColor(Color.parseColor("#BBD3FF"));
        statusText.setTextSize(10);
        statusText.setPadding(0, dp(6), 0, 0);
        header.addView(statusText);

        root.addView(header);

        // 进度条
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // 筛选栏
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setBackgroundColor(Color.WHITE);

        filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setPadding(dp(12), dp(10), dp(12), dp(10));

        addChip("all", "全部");
        addChip("game", "游戏");
        addChip("ai", "AI");
        addChip("vps", "VPS");
        addChip("cloud", "云服务");
        addChip("software", "软件");

        hsv.addView(filterBar);
        root.addView(hsv);

        // 列表
        ScrollView sv = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(10), dp(10), dp(10), dp(30));
        sv.addView(listContainer);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部刷新按钮
        TextView refresh = new TextView(this);
        refresh.setText("刷新数据");
        refresh.setTextColor(Color.WHITE);
        refresh.setTextSize(15);
        refresh.setGravity(Gravity.CENTER);
        refresh.setTypeface(Typeface.DEFAULT_BOLD);
        refresh.setBackgroundColor(Color.parseColor("#2563EB"));
        refresh.setPadding(0, dp(14), 0, dp(14));
        refresh.setOnClickListener(v -> doSync(true));
        root.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /** 添加筛选按钮 */
    private void addChip(String key, String label) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        chip.setTag(key);
        chip.setOnClickListener(v -> {
            currentFilter = key;
            renderList();
            updateChipStyle();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        filterBar.addView(chip, lp);
        updateChipStyle();
    }

    private void updateChipStyle() {
        if (filterBar == null) return;
        for (int i = 0; i < filterBar.getChildCount(); i++) {
            TextView c = (TextView) filterBar.getChildAt(i);
            boolean active = currentFilter.equals(c.getTag());
            if (active) {
                c.setTextColor(Color.WHITE);
                c.setBackgroundColor(Color.parseColor("#2563EB"));
            } else {
                c.setTextColor(Color.parseColor("#4B5563"));
                c.setBackgroundColor(Color.parseColor("#F0F2F5"));
            }
        }
    }

    /** 执行同步 */
    private void doSync(boolean notify) {
        progress.setVisibility(View.VISIBLE);
        statusText.setText("正在拉取数据…");
        pool.execute(() -> {
            final RadarSync.Result r = RadarSync.sync(this, notify);
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                if (r.ok) {
                    allItems = r.items;
                    String time = r.generatedAt;
                    try {
                        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
                        Date d = in.parse(time.substring(0, 19));
                        SimpleDateFormat out = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                        time = out.format(d);
                    } catch (Exception ignored) {
                    }
                    statusText.setText("共 " + r.total + " 条 · 更新于 " + time
                            + (r.newCount > 0 ? " · 新增 " + r.newCount : ""));
                    renderList();
                } else {
                    statusText.setText("拉取失败：" + r.error);
                    Toast.makeText(this, "拉取失败：" + r.error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** 渲染列表 */
    private void renderList() {
        listContainer.removeAllViews();
        int count = 0;
        for (RadarItem it : allItems) {
            if (!"all".equals(currentFilter) && !currentFilter.equals(it.category)) continue;
            listContainer.addView(buildCard(it));
            count++;
        }
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无数据，下拉或点刷新重试");
            empty.setTextColor(Color.parseColor("#9CA3AF"));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, 0);
            listContainer.addView(empty);
        }
    }

    /** 单条卡片 */
    private View buildCard(RadarItem it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        // 来源 + 分类
        TextView meta = new TextView(this);
        meta.setText(it.sourceName + "  ·  " + it.categoryLabel());
        meta.setTextColor(Color.parseColor("#2563EB"));
        meta.setTextSize(10);
        card.addView(meta);

        // 标题
        TextView title = new TextView(this);
        title.setText(it.title);
        title.setTextColor(Color.parseColor("#111827"));
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(5), 0, 0);
        card.addView(title);

        // 摘要
        if (!TextUtils.isEmpty(it.summary)) {
            TextView sum = new TextView(this);
            sum.setText(it.summary);
            sum.setTextColor(Color.parseColor("#6B7280"));
            sum.setTextSize(11);
            sum.setMaxLines(3);
            sum.setPadding(0, dp(4), 0, 0);
            card.addView(sum);
        }

        // 点击打开链接
        card.setOnClickListener(v -> {
            if (TextUtils.isEmpty(it.url)) return;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(it.url));
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
        });

        return card;
    }

    /** 安卓 13+ 请求通知权限 */
    private void requestNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}