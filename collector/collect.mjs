#!/usr/bin/env node
/**
 * 资源雷达 · 移动端采集器
 * ------------------------------------------------------------
 * 在 GitHub Actions 上定时运行，抓取各福利源，输出 data.json。
 * 手机端 App 拉取 data.json，对比 diff 后发本地通知。
 *
 * 设计原则：
 *  1. 不依赖 SQLite / Hono / React，纯 Node 原生 + fast-xml-parser
 *  2. 单个源失败不影响整体，记录 failed 列表
 *  3. 输出结构与上游 RawItem 对齐，方便后续扩展
 *  4. 保留 history.json 做增量 diff（手机端靠它判断"新增"）
 * ------------------------------------------------------------
 */

import { writeFileSync, readFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = process.env.OUT_DIR || join(__dirname, "..", "docs", "data");

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const TIMEOUT_MS = 20000;
const MAX_ITEMS_PER_SOURCE = 60;

/* ============================ HTTP ============================ */

async function fetchText(url, extraHeaders = {}) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url, {
      signal: ac.signal,
      redirect: "follow",
      headers: {
        "user-agent": UA,
        "accept-language": "zh-CN,zh;q=0.9,en;q=0.8",
        accept:
          "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8",
        ...extraHeaders,
      },
    });
    const body = await res.text();
    return { status: res.status, body, url: res.url };
  } finally {
    clearTimeout(timer);
  }
}

async function fetchJson(url, extraHeaders = {}) {
  const r = await fetchText(url, {
    accept: "application/json",
    ...extraHeaders,
  });
  let data = null;
  try {
    data = JSON.parse(r.body);
  } catch {
    /* ignore */
  }
  return { ...r, data };
}

/* ============================ 工具 ============================ */

function hashId(str) {
  // 简易稳定哈希，用于 externalId 兜底
  let h = 0;
  for (let i = 0; i < str.length; i++) {
    h = (h << 5) - h + str.charCodeAt(i);
    h |= 0;
  }
  return "h" + (h >>> 0).toString(36);
}

function decodeEntities(s) {
  if (!s) return "";
  return s
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/"/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#x27;/g, "'")
    .replace(/&nbsp;/g, " ")
    .replace(/&#(\d+);/g, (_, d) => String.fromCharCode(Number(d)));
}

function stripTags(html) {
  if (!html) return "";
  return decodeEntities(
    html
      .replace(/<script[\s\S]*?<\/script>/gi, " ")
      .replace(/<style[\s\S]*?<\/style>/gi, " ")
      .replace(/<[^>]+>/g, " ")
      .replace(/\s+/g, " ")
  ).trim();
}

function truncate(s, n = 300) {
  if (!s) return "";
  return s.length > n ? s.slice(0, n) + "…" : s;
}

/* ============================ 采集器 ============================ */

/** Epic Games 喜加一 */
async function collectEpic(log) {
  const url =
    "https://store-site-backend-static-ipv4.ak.epicgames.com/freeGamesPromotions?locale=zh-CN&country=CN&allowCountries=CN";
  const { status, data } = await fetchJson(url);
  if (status !== 200 || !data) throw new Error("Epic HTTP " + status);
  const elements = data?.data?.Catalog?.searchStore?.elements ?? [];
  const items = [];
  for (const e of elements) {
    const flatten = (groups) =>
      (groups ?? []).flatMap((g) => g.promotionalOffers ?? []);
    const current = flatten(e.promotions?.promotionalOffers).filter(
      (o) => Number(o.discountSetting?.discountPercentage ?? -1) === 0
    );
    const upcoming = flatten(e.promotions?.upcomingPromotionalOffers).filter(
      (o) => Number(o.discountSetting?.discountPercentage ?? -1) === 0
    );
    if (!current.length && !upcoming.length) continue;
    const origNum = e.price?.totalPrice?.originalPrice;
    if (typeof origNum === "number" && origNum === 0) continue; // 跳过永久免费
    const slug = (
      e.productSlug ||
      e.urlSlug ||
      e.offerMappings?.[0]?.pageSlug ||
      ""
    ).replace(/\/home$/, "");
    const isNow = current.length > 0;
    const w = (isNow ? current[0] : upcoming[0]) ?? {};
    const original = e.price?.totalPrice?.fmtPrice?.originalPrice ?? "";
    const imgs = e.keyImages ?? [];
    const img =
      imgs.find((k) => k.type === "OfferImageWide")?.url ||
      imgs.find((k) => k.type === "DieselStoreFrontWide")?.url ||
      imgs.find((k) => k.type === "Thumbnail")?.url ||
      null;
    items.push({
      id: e.id,
      source: "epic",
      sourceName: "Epic Games",
      url: "https://store.epicgames.com/zh-CN/p/" + (slug || e.id),
      title: (isNow ? "【本周免费】" : "【即将免费】") + (e.title ?? ""),
      summary: [e.description, original ? "原价 " + original : ""]
        .filter(Boolean)
        .join(" · "),
      kind: "freebie",
      category: "game",
      obtain: "free",
      imageUrl: img,
      expiresAt: w.endDate ?? null,
      publishedAt: w.startDate ?? null,
      tags: ["epic", isNow ? "当前免费" : "即将免费"],
    });
  }
  log(`epic: ${items.length} 条`);
  return items;
}

/** Steam 限时免费 */
async function collectSteam(log) {
  const url =
    "https://store.steampowered.com/search/results/?query&start=0&count=50&maxprice=free&specials=1&infinite=1&cc=us&l=schinese";
  const { status, data } = await fetchJson(url);
  if (status !== 200 || !data) throw new Error("Steam HTTP " + status);
  const html = data?.results_html ?? "";
  const re =
    /<a href="(https:\/\/store\.steampowered\.com\/app\/[^"]+)"[\s\S]{0,3000}?<span class="title">([^<]+)<\/span>/g;
  const out = [];
  const seen = new Set();
  let m;
  while ((m = re.exec(html))) {
    const link = (m[1] || "").split("?")[0];
    const title = decodeEntities((m[2] || "").trim());
    const appId = /\/app\/(\d+)/.exec(link)?.[1] || link;
    if (!link || !title || seen.has(appId)) continue;
    seen.add(appId);
    out.push({
      id: appId,
      source: "steam",
      sourceName: "Steam",
      url: link,
      title,
      summary: "Steam 限时免费（100% off）",
      kind: "freebie",
      category: "game",
      obtain: "free",
      imageUrl: /^\d+$/.test(appId)
        ? `https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${appId}/header.jpg`
        : null,
      publishedAt: null,
      expiresAt: null,
      tags: ["steam", "限时免费"],
    });
  }
  log(`steam: ${out.length} 条`);
  return out;
}

/** GamerPower 免费游戏 */
async function collectGamerPower(log) {
  // 注意：平台/类型参数组合不对会 404，这里用最简组合 + 客户端过滤
  const { status, data } = await fetchJson(
    "https://www.gamerpower.com/api/giveaways?platform=pc"
  );
  if (status !== 200 || !Array.isArray(data)) throw new Error("GamerPower HTTP " + status);
  const items = data.slice(0, MAX_ITEMS_PER_SOURCE).map((g) => ({
    id: String(g.id),
    source: "gamerpower",
    sourceName: "GamerPower",
    url: g.open_giveaway_url || g.giveaway_url || "",
    title: g.title || "",
    summary: truncate(stripTags(g.description), 200),
    kind: "freebie",
    category: "game",
    obtain: "free",
    imageUrl: g.image || g.thumbnail || null,
    publishedAt: g.published_date || null,
    expiresAt: g.end_date && g.end_date !== "N/A" ? g.end_date : null,
    tags: ["gamerpower", g.platforms || ""],
  }));
  log(`gamerpower: ${items.length} 条`);
  return items;
}

/** GitHub free-for-dev 清单 */
async function collectGithubFree(log) {
  const repo = "ripienaar/free-for-dev";
  const headers = { accept: "application/vnd.github.raw" };
  if (process.env.GH_TOKEN) headers.authorization = "Bearer " + process.env.GH_TOKEN;
  const { status, body } = await fetchText(
    `https://api.github.com/repos/${repo}/contents/README.md`,
    headers
  );
  if (status !== 200) throw new Error("GitHub HTTP " + status);

  const SECTION_RULES = [
    [/generative ai|apis, data, and ml|ai|llm|chatbot/i, "ai"],
    [/domain|dns|web hosting/i, "domain"],
    [/education/i, "edu"],
    [/cloud|paas|baas|iaas|cdn|storage/i, "cloud"],
  ];
  const mapSection = (s) => {
    for (const [re, cat] of SECTION_RULES) if (re.test(s)) return cat;
    return "software";
  };

  const items = [];
  const seen = new Set();
  let section = "";
  for (const line of body.split("\n")) {
    const h = /^#{2,3}\s+(.+)$/.exec(line);
    if (h) {
      section = (h[1] || "").trim();
      continue;
    }
    if (items.length >= MAX_ITEMS_PER_SOURCE) break;
    const bullet = /^\s*[-*+]\s+\[(.+?)\]\((https?:\/\/[^)\s]+)\)\s*[-–—:]?\s*(.*)$/.exec(line);
    const table = !bullet
      ? /^\|\s*\[(.+?)\]\((https?:\/\/[^)\s]+)\)\s*\|\s*([^|]*)/.exec(line)
      : null;
    const m = bullet || table;
    if (!m) continue;
    const title = (m[1] || "").replace(/\*\*/g, "").trim();
    const url = (m[2] || "").trim();
    if (!title || seen.has(url) || /shields\.io|badge/i.test(url)) continue;
    seen.add(url);
    const desc = (m[3] || "").replace(/\[([^\]]+)\]\([^)]*\)/g, "$1").trim();
    items.push({
      id: hashId(url),
      source: "github-free-for-dev",
      sourceName: "GitHub 免费清单",
      url,
      title: title + (desc ? " — " + desc.slice(0, 80) : ""),
      summary: truncate(desc, 200),
      kind: "freebie",
      category: mapSection(section),
      obtain: "free",
      imageUrl: null,
      publishedAt: null,
      expiresAt: null,
      tags: ["github", section],
    });
  }
  log(`github-free-for-dev: ${items.length} 条`);
  return items;
}

/** V2EX 免费赠送 */
async function collectV2EX(log) {
  const { status, data } = await fetchJson(
    "https://www.v2ex.com/api/topics/show.json?node_name=free"
  );
  if (status !== 200 || !Array.isArray(data)) throw new Error("V2EX HTTP " + status);
  const items = data.slice(0, MAX_ITEMS_PER_SOURCE).map((t) => ({
    id: String(t.id),
    source: "v2ex-free",
    sourceName: "V2EX · 免费赠送",
    url: t.url || `https://www.v2ex.com/t/${t.id}`,
    title: t.title || "",
    summary: truncate(stripTags(t.content), 200),
    kind: "freebie",
    category: "other",
    obtain: "free",
    imageUrl: null,
    publishedAt: t.created
      ? new Date(t.created * 1000).toISOString()
      : null,
    expiresAt: null,
    tags: ["v2ex", t.node?.name || ""],
  }));
  log(`v2ex: ${items.length} 条`);
  return items;
}

/** OpenRouter 免费模型 */
async function collectOpenRouter(log) {
  const { status, data } = await fetchJson("https://openrouter.ai/api/v1/models");
  if (status !== 200 || !data?.data) throw new Error("OpenRouter HTTP " + status);
  const items = data.data
    .filter((m) => {
      const p = m.pricing || {};
      return Number(p.prompt) === 0 && Number(p.completion) === 0;
    })
    .slice(0, MAX_ITEMS_PER_SOURCE)
    .map((m) => ({
      id: m.id,
      source: "openrouter-free",
      sourceName: "OpenRouter",
      url: "https://openrouter.ai/models/" + m.id,
      title: "免费模型：" + (m.name || m.id),
      summary: truncate(m.description, 200),
      kind: "freebie",
      category: "ai",
      obtain: "free",
      imageUrl: null,
      publishedAt: null,
      expiresAt: null,
      tags: ["ai", "免费模型", String(m.context_length || "")],
    }));
  log(`openrouter: ${items.length} 条`);
  return items;
}

/** RSS 通用采集（NodeSeek 等） */
async function collectRss(log, id, name, url, category = "other") {
  const { status, body } = await fetchText(url);
  if (status !== 200) throw new Error(`${name} HTTP ${status}`);

  // 轻量 XML 解析：不引第三方库，正则提取 item/entry
  const items = [];
  const blocks = body.match(/<item[\s\S]*?<\/item>|<entry[\s\S]*?<\/entry>/g) || [];
  for (const b of blocks.slice(0, MAX_ITEMS_PER_SOURCE)) {
    const pick = (tag) => {
      const m =
        new RegExp(`<${tag}[^>]*>([\\s\\S]*?)</${tag}>`, "i").exec(b) ||
        new RegExp(`<${tag}[^>]*/>`, "i").exec(b);
      if (!m) return "";
      let v = m[1] || "";
      const cdata = /<!\[CDATA\[([\s\S]*?)\]\]>/.exec(v);
      if (cdata) v = cdata[1];
      return stripTags(v).trim();
    };
    const linkM =
      /<link[^>]*href="([^"]+)"/i.exec(b) || /<link>([\s\S]*?)<\/link>/i.exec(b);
    const link = linkM ? (linkM[1] || "").trim() : "";
    const title = pick("title");
    if (!title || !link) continue;
    items.push({
      id: hashId(link),
      source: id,
      sourceName: name,
      url: link,
      title,
      summary: truncate(pick("description") || pick("summary") || pick("content"), 200),
      kind: "freebie",
      category,
      obtain: "conditional",
      imageUrl: null,
      publishedAt: pick("pubDate") || pick("published") || pick("updated") || null,
      expiresAt: null,
      tags: [id],
    });
  }
  log(`${id}: ${items.length} 条`);
  return items;
}

/* ============================ 主流程 ============================ */

const SOURCES = [
  { id: "epic", run: collectEpic },
  { id: "steam", run: collectSteam },
  { id: "gamerpower", run: collectGamerPower },
  { id: "github-free-for-dev", run: collectGithubFree },
  { id: "v2ex-free", run: collectV2EX },
  { id: "openrouter-free", run: collectOpenRouter },
  {
    id: "nodeseek",
    run: (log) =>
      collectRss(log, "nodeseek", "NodeSeek · 福利", "https://rss.nodeseek.com/", "vps"),
  },
];

async function main() {
  const startedAt = new Date().toISOString();
  console.log(`[radar] 启动采集 @ ${startedAt}`);

  const allItems = [];
  const failed = [];
  const stats = {};

  for (const src of SOURCES) {
    const log = (m) => console.log("  " + m);
    console.log(`[radar] → ${src.id}`);
    try {
      const items = await src.run(log);
      allItems.push(...items);
      stats[src.id] = items.length;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      console.error(`  ✗ ${src.id} 失败: ${msg}`);
      failed.push({ source: src.id, error: msg });
      stats[src.id] = 0;
    }
  }

  // 去重（同 source + id）
  const uniq = new Map();
  for (const it of allItems) {
    uniq.set(it.source + "::" + it.id, it);
  }
  const items = [...uniq.values()];

  /* ---- 增量 diff：与上次 history 比对 ---- */
  if (!existsSync(OUT_DIR)) mkdirSync(OUT_DIR, { recursive: true });
  const historyPath = join(OUT_DIR, "history.json");
  let seenIds = new Set();
  if (existsSync(historyPath)) {
    try {
      const h = JSON.parse(readFileSync(historyPath, "utf8"));
      seenIds = new Set(h.seenIds || []);
    } catch {
      /* ignore */
    }
  }
  const newItems = items.filter((it) => !seenIds.has(it.source + "::" + it.id));
  // history 只保留最近 3000 条 id，避免无限增长
  const mergedIds = [
    ...new Set([...items.map((i) => i.source + "::" + i.id), ...seenIds]),
  ].slice(0, 3000);

  const payload = {
    generatedAt: new Date().toISOString(),
    startedAt,
    total: items.length,
    newCount: newItems.length,
    stats,
    failed,
    items,
    newItems,
  };

  writeFileSync(join(OUT_DIR, "data.json"), JSON.stringify(payload, null, 2), "utf8");
  writeFileSync(
    historyPath,
    JSON.stringify({ updatedAt: new Date().toISOString(), seenIds: mergedIds }),
    "utf8"
  );

  console.log(
    `[radar] 完成：共 ${items.length} 条，新增 ${newItems.length} 条，失败 ${failed.length} 个源`
  );
  if (failed.length) {
    for (const f of failed) console.log(`  - ${f.source}: ${f.error}`);
  }
}

main().catch((err) => {
  console.error("[radar] 致命错误:", err);
  process.exit(1);
});