package com.radar.freebie;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 单条福利数据 */
public class RadarItem {
    public String id = "";
    public String source = "";
    public String sourceName = "";
    public String url = "";
    public String title = "";
    public String summary = "";
    public String category = "";
    public String obtain = "";
    public String imageUrl = "";
    public String expiresAt = "";
    public String publishedAt = "";
    public List<String> tags = new ArrayList<>();

    public static RadarItem fromJson(JSONObject o) {
        RadarItem it = new RadarItem();
        it.id = o.optString("id", "");
        it.source = o.optString("source", "");
        it.sourceName = o.optString("sourceName", "");
        it.url = o.optString("url", "");
        it.title = o.optString("title", "");
        it.summary = o.optString("summary", "");
        it.category = o.optString("category", "");
        it.obtain = o.optString("obtain", "");
        it.imageUrl = o.optString("imageUrl", "");
        it.expiresAt = o.optString("expiresAt", "");
        it.publishedAt = o.optString("publishedAt", "");
        JSONArray tg = o.optJSONArray("tags");
        if (tg != null) {
            for (int i = 0; i < tg.length(); i++) it.tags.add(tg.optString(i, ""));
        }
        return it;
    }

    /** 唯一键：source + id */
    public String key() {
        return source + "::" + id;
    }

    /** 分类中文名 */
    public String categoryLabel() {
        switch (category) {
            case "game": return "游戏";
            case "ai": return "AI";
            case "vps": return "VPS";
            case "cloud": return "云服务";
            case "domain": return "域名";
            case "software": return "软件";
            case "edu": return "教育";
            case "other": return "其他";
            default: return category.isEmpty() ? "其他" : category;
        }
    }
}