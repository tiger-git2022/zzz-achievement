// ==UserScript==
// @name         ZZZ Stardb RNG Achievement Badges
// @namespace    local.zzz.stardb
// @version      0.2.1
// @description  Add zzz.rng.moe gold/silver/bronze achievement badges to stardb.gg achievement tracker.
// @match        https://stardb.gg/*/zzz/achievement-tracker*
// @grant        GM_xmlhttpRequest
// @connect      zzz.rng.moe
// @run-at       document-idle
// ==/UserScript==

(function () {
  "use strict";

  const RNG_METADATA_URL = "https://zzz.rng.moe/_out/immutable/chunks/Dc9aTjwP.js";
  const DATA_PATH = `/${currentLocale()}/zzz/achievement-tracker/__data.json?x-sveltekit-invalidated=01`;
  const BADGE_CLASS = "zzz-rng-achievement-badge";
  const BADGE_ATTR = "data-zzz-rng-badge";
  const HOST_ATTR = "data-zzz-rng-badge-host";
  const TARGET_ATTR = "data-zzz-rng-badge-target";
  const MENU_GROUPED_ATTR = "data-zzz-rng-menu-grouped";
  const MENU_HEADER_CLASS = "zzz-rng-category-header";
  const SCAN_LIMIT = 400;

  const TIER_META = {
    1: { label: "铜", en: "Bronze", color: "#b7773e", bg: "linear-gradient(135deg,#ffd3a6,#b9783f 55%,#71401f)" },
    2: { label: "银", en: "Silver", color: "#aeb8c2", bg: "linear-gradient(135deg,#f4f7fa,#aab4bf 55%,#697480)" },
    3: { label: "金", en: "Gold", color: "#d7a637", bg: "linear-gradient(135deg,#fff2b8,#d6a12f 55%,#8c5d13)" },
  };

  const CATEGORY_GROUPS = [
    {
      name: "故事",
      categories: ["代理人信赖", "法厄同纪事", "代理人秘闻", "独家视界", "际会之时", "绳网热议"],
    },
    {
      name: "城市",
      categories: ["绳匠业务", "雅努斯区", "布亚斯特", "卫非地", "外环地带", "索恩区"],
    },
    {
      name: "战术",
      categories: ["枯萎之都", "作战技巧", "敌对目标", "战斗成就", "迷失之地"],
    },
    {
      name: "探索",
      categories: ["空洞探索指南", "零号密钥", "勘域探境"],
    },
  ];

  const CATEGORY_TO_GROUP = new Map(CATEGORY_GROUPS.flatMap((group) => group.categories.map((category) => [category, group.name])));
  const KNOWN_CATEGORIES = new Set(["全部", ...CATEGORY_TO_GROUP.keys()]);

  const state = {
    achievements: [],
    iconTiers: new Map(),
    byName: new Map(),
    observer: null,
    scanQueued: false,
  };

  injectStyle();
  boot();

  async function boot() {
    try {
      const [achievements, iconTiers] = await Promise.all([
        fetchStardbAchievements(),
        fetchIconTiers(),
      ]);

      state.achievements = achievements;
      state.iconTiers = iconTiers;
      state.byName = buildNameMap(achievements, iconTiers);

      removeExistingBadges();
      scanAndApplyBadges();
      applyCategoryMenuGrouping();
      watchDom();
      console.info(`[ZZZ badges] Loaded ${achievements.length} achievements and ${iconTiers.size} icon tiers.`);
    } catch (error) {
      console.error("[ZZZ badges] Failed to initialize.", error);
    }
  }

  async function fetchStardbAchievements() {
    const response = await fetch(DATA_PATH, {
      credentials: "include",
      headers: { accept: "*/*" },
    });
    if (!response.ok) throw new Error(`stardb __data.json HTTP ${response.status}`);
    return parseAchievementTrackerData(await response.json());
  }

  function fetchIconTiers() {
    return new Promise((resolve) => {
      GM_xmlhttpRequest({
        method: "GET",
        url: RNG_METADATA_URL,
        headers: { accept: "*/*" },
        onload: (response) => resolve(parseIconTiers(response.responseText || "")),
        onerror: () => resolve(new Map()),
        ontimeout: () => resolve(new Map()),
      });
    });
  }

  function parseIconTiers(source) {
    const result = new Map();
    const objectRegex = /\{[^{}]*\}/g;
    const idRegex = /\bid\s*:\s*(\d+(?:e\d+)?)/i;
    const iconRegex = /\bicon\s*:\s*([123])/;
    const blocks = source.match(objectRegex) || [];

    for (const block of blocks) {
      const idMatch = idRegex.exec(block);
      const iconMatch = iconRegex.exec(block);
      if (!idMatch || !iconMatch) continue;
      const id = normalizeJsNumberId(idMatch[1]);
      const icon = Number(iconMatch[1]);
      if (id && icon >= 1 && icon <= 3) result.set(id, icon);
    }

    return result;
  }

  function normalizeJsNumberId(value) {
    const normalized = String(value).toLowerCase();
    if (!normalized.includes("e")) return normalized;
    const [base, exponentText] = normalized.split("e");
    const exponent = Number(exponentText);
    if (!/^\d+$/.test(base) || !Number.isInteger(exponent) || exponent < 0) return null;
    return base + "0".repeat(exponent);
  }

  function parseAchievementTrackerData(root) {
    const nodes = Array.isArray(root?.nodes) ? root.nodes : [];
    for (const node of nodes) {
      if (node?.type !== "data" || !Array.isArray(node.data)) continue;
      const resolver = createSvelteKitResolver(node.data);
      const rootData = resolver.resolveAt(0);
      const load = rootData?.load;
      const parsed = toAchievements(load);
      if (parsed.length) return parsed;
    }
    return [];
  }

  function createSvelteKitResolver(data) {
    const cache = new Map();

    function resolveAt(index) {
      if (index < 0 || index >= data.length) return null;
      if (cache.has(index)) return cache.get(index);
      const resolved = resolveValue(data[index]);
      cache.set(index, resolved);
      return resolved;
    }

    function resolveValue(value) {
      if (value === null || value === undefined) return null;
      if (Array.isArray(value)) return value.map(resolveReferenceOrValue);
      if (typeof value === "object") {
        return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, resolveReferenceOrValue(child)]));
      }
      return value;
    }

    function resolveReferenceOrValue(value) {
      if (Number.isInteger(value) && value >= 0 && value < data.length) return resolveAt(value);
      return resolveValue(value);
    }

    return { resolveAt };
  }

  function toAchievements(load) {
    const series = Array.isArray(load?.series) ? load.series : [];
    const result = [];
    const seen = new Set();
    let order = 0;

    for (const seriesItem of series) {
      const primaryCategory = displayName(seriesItem);
      const groups = Array.isArray(seriesItem?.achievement_groups) ? seriesItem.achievement_groups : [];
      for (const group of groups) {
        const completeId = group?.complete == null ? "" : String(group.complete);
        const groupName = displayName(group);
        const achievements = Array.isArray(group?.achievements) ? group.achievements : [];
        for (const achievement of achievements) {
          const id = achievement?.id == null ? "" : String(achievement.id);
          if (!id || seen.has(id)) continue;
          seen.add(id);
          result.push({
            id,
            name: String(achievement?.name || ""),
            description: String(achievement?.description || ""),
            primaryCategory,
            category: String(achievement?.series_name || groupName || ""),
            reward: achievement?.currency == null ? "" : String(achievement.currency),
            version: String(achievement?.version || ""),
            displayOrder: order++,
            completed: completeId === id,
          });
        }
      }
    }

    return result;
  }

  function displayName(value) {
    for (const key of ["name", "title", "label", "series_name"]) {
      const text = value?.[key] == null ? "" : String(value[key]);
      if (text.trim()) return text;
    }
    return "";
  }

  function buildNameMap(achievements, iconTiers) {
    const result = new Map();
    for (const achievement of achievements) {
      const tier = iconTiers.get(achievement.id);
      if (!tier || !achievement.name) continue;
      result.set(normalizeText(achievement.name), { ...achievement, tier });
    }
    return result;
  }

  function watchDom() {
    state.observer?.disconnect();
    state.observer = new MutationObserver(queueScan);
    state.observer.observe(document.body, { childList: true, subtree: true });
  }

  function queueScan() {
    if (state.scanQueued) return;
    state.scanQueued = true;
    window.requestAnimationFrame(() => {
      state.scanQueued = false;
      scanAndApplyBadges();
      applyCategoryMenuGrouping();
    });
  }

  function scanAndApplyBadges() {
    if (!state.byName.size) return;

    const textNodes = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        const text = normalizeText(node.nodeValue);
        if (!text || text.length > 80) return NodeFilter.FILTER_REJECT;
        if (!state.byName.has(text)) return NodeFilter.FILTER_REJECT;
        if (node.parentElement?.closest(`.${BADGE_CLASS}`)) return NodeFilter.FILTER_REJECT;
        if (node.parentElement?.closest(`[${HOST_ATTR}], [${TARGET_ATTR}]`)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      },
    });

    while (textNodes.length < SCAN_LIMIT) {
      const node = walker.nextNode();
      if (!node) break;
      textNodes.push(node);
    }

    const wasWatching = Boolean(state.observer);
    if (wasWatching) state.observer.disconnect();

    for (const textNode of textNodes) {
      const achievement = state.byName.get(normalizeText(textNode.nodeValue));
      if (!achievement) continue;
      const target = findBadgeTarget(textNode.parentElement);
      const host = findBadgeHost(textNode);
      if (!target || !host || hasBadge(target, host, achievement.id)) continue;
      host.after(createBadge(achievement));
      host.setAttribute(HOST_ATTR, achievement.id);
      target.setAttribute(TARGET_ATTR, achievement.id);
      target.classList.add("zzz-rng-achievement-target");
    }

    if (wasWatching) watchDom();
  }

  function hasBadge(target, host, id) {
    return target.getAttribute(TARGET_ATTR) === id ||
      host.getAttribute(HOST_ATTR) === id ||
      target.querySelector(`[${BADGE_ATTR}="${id}"]`) ||
      host.nextElementSibling?.getAttribute(BADGE_ATTR) === id;
  }

  function removeExistingBadges() {
    document.querySelectorAll(`.${BADGE_CLASS}`).forEach((element) => element.remove());
    document.querySelectorAll(`[${HOST_ATTR}]`).forEach((element) => element.removeAttribute(HOST_ATTR));
    document.querySelectorAll(`[${TARGET_ATTR}]`).forEach((element) => element.removeAttribute(TARGET_ATTR));
  }

  function applyCategoryMenuGrouping() {
    const candidates = findCategoryMenuCandidates();
    for (const { container, items } of candidates) {
      if (container.getAttribute(MENU_GROUPED_ATTR) === "true") continue;
      groupCategoryMenu(container, items);
    }
  }

  function findCategoryMenuCandidates() {
    const result = [];
    const seen = new Set();

    for (const node of document.querySelectorAll("body *")) {
      const category = categoryFromElement(node);
      if (!category) continue;

      const item = findCategoryItem(node, category);
      if (!item || !item.parentElement) continue;

      const container = item.parentElement;
      if (seen.has(container)) continue;

      const items = Array.from(container.children)
        .map((element) => ({ element, category: categoryFromElement(element) }))
        .filter((entry) => entry.category && isSeriesCategory(entry.category));

      const realCategoryCount = items.filter((entry) => entry.category !== "全部").length;
      if (realCategoryCount < 5) continue;

      seen.add(container);
      result.push({ container, items });
    }

    return result;
  }

  function findCategoryItem(element, category) {
    let current = element;
    for (let depth = 0; current && depth < 6; depth += 1) {
      const currentCategory = categoryFromElement(current);
      const parent = current.parentElement;
      if (currentCategory === category && parent && countCategoryChildren(parent) >= 5) return current;
      current = current.parentElement;
    }
    return null;
  }

  function countCategoryChildren(container) {
    return Array.from(container.children).filter((child) => {
      const category = categoryFromElement(child);
      return category && isSeriesCategory(category);
    }).length;
  }

  function groupCategoryMenu(container, items) {
    const itemByCategory = new Map();
    const usedElements = new Set();

    for (const item of items) {
      if (!item.category || usedElements.has(item.element)) continue;
      if (!itemByCategory.has(item.category)) itemByCategory.set(item.category, item.element);
      usedElements.add(item.element);
    }

    const fragment = document.createDocumentFragment();
    const allItem = itemByCategory.get("全部");
    if (allItem) fragment.appendChild(allItem);

    for (const group of CATEGORY_GROUPS) {
      const groupItems = group.categories
        .map((category) => itemByCategory.get(category))
        .filter(Boolean);
      if (!groupItems.length) continue;

      fragment.appendChild(createCategoryHeader(group.name));
      for (const item of groupItems) fragment.appendChild(item);
    }

    const otherItems = Array.from(itemByCategory.entries())
      .filter(([category]) => category !== "全部" && !CATEGORY_TO_GROUP.has(category))
      .map(([, element]) => element);
    if (otherItems.length) {
      fragment.appendChild(createCategoryHeader("其他"));
      for (const item of otherItems) fragment.appendChild(item);
    }

    const wasWatching = Boolean(state.observer);
    if (wasWatching) state.observer.disconnect();
    container.querySelectorAll(`.${MENU_HEADER_CLASS}`).forEach((element) => element.remove());
    container.appendChild(fragment);
    container.setAttribute(MENU_GROUPED_ATTR, "true");
    if (wasWatching) watchDom();
  }

  function createCategoryHeader(text) {
    const header = document.createElement("div");
    header.className = MENU_HEADER_CLASS;
    header.textContent = text;
    header.setAttribute("role", "presentation");
    return header;
  }

  function categoryFromElement(element) {
    if (!element || element.classList?.contains(MENU_HEADER_CLASS)) return "";
    const text = normalizeOptionText(element.textContent);
    if (!text) return "";
    if (text === "全部" || text.startsWith("全部 ")) return "全部";
    if (text.startsWith("[Arcade]")) return text;
    for (const category of KNOWN_CATEGORIES) {
      if (category !== "全部" && (text === category || text.startsWith(`${category} `))) return category;
    }
    return "";
  }

  function isSeriesCategory(category) {
    return KNOWN_CATEGORIES.has(category) || category.startsWith("[Arcade]");
  }

  function findBadgeTarget(element) {
    let current = element;
    for (let depth = 0; current && depth < 6; depth += 1) {
      if (current.matches("article, li, button, a, [role='button'], [class*='card'], [class*='Card']")) return current;
      current = current.parentElement;
    }
    return element;
  }

  function findBadgeHost(textNode) {
    const element = textNode.parentElement;
    if (!element) return null;

    let current = element;
    for (let depth = 0; current && depth < 3; depth += 1) {
      if (current.matches("h1, h2, h3, h4, h5, h6, p, span, div, a, button")) return current;
      current = current.parentElement;
    }

    return element;
  }

  function createBadge(achievement) {
    const meta = TIER_META[achievement.tier];
    const badge = document.createElement("span");
    badge.className = `${BADGE_CLASS} zzz-rng-tier-${achievement.tier}`;
    badge.setAttribute(BADGE_ATTR, achievement.id);
    badge.title = `${meta.en} / ${meta.label}${achievement.version ? ` · V${achievement.version}` : ""}`;
    badge.innerHTML = `
      <span class="zzz-rng-medal" aria-hidden="true"></span>
      ${achievement.version ? `<span class="zzz-rng-version">V${escapeHtml(achievement.version)}</span>` : ""}
    `;
    badge.style.setProperty("--zzz-rng-badge-bg", meta.bg);
    badge.style.setProperty("--zzz-rng-badge-color", meta.color);
    return badge;
  }

  function injectStyle() {
    const style = document.createElement("style");
    style.textContent = `
      .zzz-rng-achievement-target {
        position: relative;
      }

      .${BADGE_CLASS} {
        display: inline-flex;
        align-items: center;
        gap: 3px;
        width: max-content;
        max-width: 100%;
        margin: 0 0 0 8px;
        padding: 2px 5px 2px 3px;
        border: 1px solid color-mix(in srgb, var(--zzz-rng-badge-color), #ffffff 34%);
        border-radius: 999px;
        background: color-mix(in srgb, var(--zzz-rng-badge-color), transparent 88%);
        color: currentColor;
        font: 600 10px/1.1 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        vertical-align: middle;
        white-space: nowrap;
      }

      .zzz-rng-medal {
        width: 18px;
        height: 18px;
        border-radius: 50%;
        background: var(--zzz-rng-badge-bg);
        box-shadow: inset 0 1px 1px rgba(255,255,255,.8), inset 0 -2px 3px rgba(0,0,0,.22), 0 1px 2px rgba(0,0,0,.18);
        flex: 0 0 auto;
      }

      .zzz-rng-version {
        opacity: .68;
        font-weight: 500;
      }

      .${MENU_HEADER_CLASS} {
        padding: 12px 24px 8px;
        color: #8ea3d7;
        font: 700 15px/1.2 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        letter-spacing: 0;
        cursor: default;
        user-select: none;
        border-top: 1px solid rgba(148, 163, 184, .12);
      }

      .${MENU_HEADER_CLASS}:first-child {
        border-top: 0;
      }
    `;
    document.head.appendChild(style);
  }

  function normalizeText(value) {
    return String(value || "").replace(/\s+/g, " ").trim();
  }

  function normalizeOptionText(value) {
    return normalizeText(value)
      .replace(/\d+\s*\/\s*\d+/g, " ")
      .replace(/\d+\s*%/g, " ")
      .replace(/\s+/g, " ")
      .trim();
  }

  function currentLocale() {
    return location.pathname.split("/").filter(Boolean)[0] || "zh-cn";
  }

  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    }[char]));
  }
})();
