// Cosmetics and the shop: /api/cosmetics/*, /api/shop/*, /api/partner/*, /api/admin/* (imported by worker/index.js).
//
// NOTHING IS SOLD YET. The shop has three modes, from the SHOP_MODE variable:
//   absent / "off" (production)  checkout answers 503 {"error":"The shop isn't open yet"}
//   "test"                       fake checkout sessions the test harness completes; only honoured on a local or
//                                ".test" host name, so even a stray SHOP_MODE=test on evadeclient.com stays closed
// A player only ever gets an item from:
//   (a) a payment provider's webhook whose signature checks out with that provider's secret (no secret, no grants),
//   (b) a redeem code made through /api/admin/codes (needs the ADMIN_TOKEN secret; without it /api/admin/* is off),
//   (c) a partner server granting its own partner items with a signed request (per-partner key, made by an admin),
//   (d) test mode (local only, see above).
//
//   GET  /api/cosmetics/catalog                    -> {version, items:[{id, name, slot, rarity (regular|special|partner),
//                                                     priceCents (null: not priced yet, not for sale; 0: free for
//                                                     every Evade player, never sold: every cape), currency,
//                                                     partner: null|{id, name}, assets: {base, files: {name: sha256}}}]}
//   GET  /api/cosmetics/players?uuids=a,b          -> {players: {uuid: {equipped: {SLOT: {id, color?}}}}} (64 at most)
//   GET  /api/cosmetics/me            (Bearer)     -> {uuid, owned: [id], equipped: {SLOT: {id, color?}}, shop: {open, mode}}
//   POST /api/cosmetics/equip {slot, id|null, color?: "#rrggbb"} (Bearer) -> {ok, equipped}  (free items: no
//                                                     ownership needed; others see them like owned ones)
//   POST /api/cosmetics/redeem {code}  (Bearer)   -> {ok, granted: [id], owned: [id]}
//   GET  /api/cosmetics/link-code      (Bearer)   -> {code, expiresIn}  (6 characters, 10 minutes: links the website)
//   GET  /api/shop/status                          -> {open, mode}
//   POST /api/shop/resolve {linkCode | username}   -> {uuid, verified}  (usernames: Mojang blocks Cloudflare, so
//                                                     this usually answers 502 {useLinkCode: true})
//   POST /api/shop/checkout {linkCode|uuid, items, giftTo?} -> test: {sessionId, url, ...}; otherwise 503
//   GET  /api/shop/session?id=                     -> {id, status, items, totalCents, currency, recipient}
//   POST /api/shop/test/complete|refund {sessionId, chargeback?}  (test mode only) -> {ok, session}
//   POST /api/shop/webhook/<generic|tebex|paddle|stripe>   provider webhooks (signature checked) -> grants / revokes
//   POST /api/partner/grant {uuid, item, nonce}    headers X-Evade-Partner, X-Evade-Timestamp (ms), X-Evade-Signature
//                                                  = hex HMAC-SHA256(partnerKey, partner + "\n" + timestamp + "\n" + body)
//   POST /api/admin/codes|grant|revoke|partner|partner/delete|orders   (Authorization: Bearer <ADMIN_TOKEN>)
//
// The catalog is site-src/cosmetics/catalog.json, copied to /cosmetics/catalog.json by scripts/build-pages.py and read
// here from the site's own assets (so it always matches the deployed asset files). Storage: cos_* tables in the
// EvadeSocial Durable Object (CosmeticsStore below). Only UUIDs, owned item ids, equipped state and purchase records
// (provider, order id, amount, currency, status, times) are stored; never names or e-mail addresses. Rate limits key
// on a salted hash of the caller's IP address (ipKey), never the address itself.

const HEADERS = { "content-type": "application/json", "cache-control": "no-store", "access-control-allow-origin": "*" };
const json = (body, status = 200, extra) =>
  new Response(JSON.stringify(body), { status, headers: extra ? { ...HEADERS, ...extra } : HEADERS });
class CosError extends Error { constructor(status, msg, extra) { super(msg); this.status = status; this.extra = extra; } }

export const COS = {
  SITE: "https://evadeclient.com",
  CATALOG_TTL_MS: 60 * 1000,
  MAX_PLAYERS: 64,
  LINK_MS: 10 * 60 * 1000,
  SESSION_MS: 30 * 60 * 1000,
  WEBHOOK_SKEW_S: 300,
  PARTNER_SKEW_MS: 5 * 60 * 1000,
  MAX_CART: 20,
  MAX_CODES: 500,
  CLOSED: "The shop isn't open yet",
};
const UUID = /^[0-9a-f]{32}$/;
const ITEM_ID = /^[a-z0-9][a-z0-9_-]{0,47}$/;
const SLOT = /^[A-Z][A-Z0-9_]{0,23}$/;
const PARTNER_ID = /^[a-z0-9][a-z0-9-]{1,31}$/;
const COLOR = /^#[0-9a-f]{6}$/;
const ORDER_ID = /^[\x21-\x7e]{1,128}$/;
const NONCE = /^[A-Za-z0-9_-]{8,64}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const FILE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;
/** Link and redeem codes: no 0/O, 1/I/L, so they're easy to read out and type. */
const CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

const enc = new TextEncoder();
const normUuid = (u) => (typeof u === "string" ? u.replace(/-/g, "").toLowerCase() : "");
const normCode = (c) => (typeof c === "string" ? c.toUpperCase().replace(/[\s-]/g, "") : "");
const toHex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
const hex = (n) => toHex(crypto.getRandomValues(new Uint8Array(n)));
function randomCode(n) {
  // Rejection sampling keeps every character equally likely.
  let out = "";
  while (out.length < n) {
    for (const b of crypto.getRandomValues(new Uint8Array(n * 2))) {
      if (b < 248 && out.length < n) out += CODE_CHARS[b % CODE_CHARS.length];
    }
  }
  return out;
}
const sha256Hex = async (s) => toHex(await crypto.subtle.digest("SHA-256", enc.encode(s)));
export async function hmacHex(secret, msg) {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return toHex(await crypto.subtle.sign("HMAC", key, enc.encode(msg)));
}
/** Constant-time check of a hex HMAC-SHA256 (WebCrypto's verify), false for anything malformed. */
async function hmacOk(secret, msg, sigHex) {
  if (typeof secret !== "string" || !secret || typeof sigHex !== "string" || !SHA256.test(sigHex.toLowerCase())) return false;
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const sig = Uint8Array.from(sigHex.toLowerCase().match(/../g), (h) => parseInt(h, 16));
  return crypto.subtle.verify("HMAC", key, sig, enc.encode(msg));
}
/** Constant-time string comparison (for the admin token). */
async function sameSecret(a, b) {
  const [x, y] = await Promise.all([sha256Hex(String(a)), sha256Hex(String(b))]);
  let d = 0;
  for (let i = 0; i < x.length; i++) d |= x.charCodeAt(i) ^ y.charCodeAt(i);
  return d === 0;
}

async function readJson(req, max = 16384) {
  if (req.method !== "POST") throw new CosError(405, "use POST");
  const text = await req.text();
  if (text.length > max) throw new CosError(413, "too large");
  let v;
  try { v = text ? JSON.parse(text) : {}; } catch { throw new CosError(400, "bad json"); }
  if (!v || typeof v !== "object" || Array.isArray(v)) throw new CosError(400, "bad json");
  return v;
}
/**
 * A short, salted hash of the caller's IP address, for rate limits only: enough to count one caller's tries, not to
 * find them. The address itself is never passed to storage.
 */
async function ipKey(req, env) {
  const addr = req.headers.get("cf-connecting-ip") || "unknown";
  return (await sha256Hex(`${(env && (env.IP_SALT || env.ADMIN_TOKEN)) || "evade-shop"}|${addr}`)).slice(0, 16);
}

// ====================================================================== shop mode

/** Hosts where SHOP_MODE=test is honoured: the local dev server and the test harness, never a public host. */
function isTestHost(host) {
  host = String(host || "").toLowerCase();
  return host === "localhost" || host === "127.0.0.1" || host === "[::1]" || host.endsWith(".localhost") || host.endsWith(".test");
}

/** "test" only when SHOP_MODE=test on a local/test host; everything else (production included) is "off". */
export function shopMode(env, url) {
  const m = String((env && env.SHOP_MODE) || "off").trim().toLowerCase();
  if (m === "test" && isTestHost(url.hostname)) return "test";
  return "off"; // a "live" mode needs a provider's checkout wired in first (see PROVIDERS)
}

// ====================================================================== catalog

let catalogCache = null;
/** Forgets the cached catalog (tests). */
export function resetCatalogCache() { catalogCache = null; }

/**
 * The item's tier: "regular" (not animated), "special" (animated; may have no price yet) or "partner" (a partner
 * server's item). The old names (common, rare, epic, legendary) and anything unknown read as regular (partner when
 * the item names a partner), so an older catalog still loads.
 */
const RARITY = new Set(["regular", "special", "partner"]);
function normRarity(raw, partner) {
  const r = String(raw || "").trim().toLowerCase();
  if (RARITY.has(r)) return r;
  return partner ? "partner" : "regular";
}

/** Validates one catalog item; null when it can't be used. Extra fields the client wants are passed through. */
function normItem(raw) {
  if (!raw || typeof raw !== "object") return null;
  const id = String(raw.id || "");
  const slot = String(raw.slot || "").toUpperCase();
  if (!ITEM_ID.test(id) || !SLOT.test(slot)) return null;
  const priceCents = Number.isSafeInteger(raw.priceCents) && raw.priceCents >= 0 ? raw.priceCents : null;
  const currency = typeof raw.currency === "string" && /^[A-Za-z]{3}$/.test(raw.currency) ? raw.currency.toUpperCase() : "USD";
  let partner = null;
  if (raw.partner && typeof raw.partner === "object" && PARTNER_ID.test(String(raw.partner.id || ""))) {
    partner = { id: String(raw.partner.id), name: String(raw.partner.name || raw.partner.id).slice(0, 48) };
  }
  const base = COS.SITE + "/cosmetics/" + id + "/";
  const files = {};
  const rawFiles = raw.assets && typeof raw.assets === "object" ? raw.assets.files : null;
  if (rawFiles && typeof rawFiles === "object") {
    for (const [name, sha] of Object.entries(rawFiles)) {
      if (FILE_NAME.test(name) && typeof sha === "string" && SHA256.test(sha.toLowerCase())) files[name] = sha.toLowerCase();
    }
  }
  return {
    ...raw,
    id,
    name: String(raw.name || id).slice(0, 64),
    slot,
    rarity: normRarity(raw.rarity, partner),
    priceCents,
    currency,
    partner,
    assets: { base, files },
  };
}

/** The catalog as published, from the raw catalog.json text. */
export async function parseCatalog(text) {
  let raw;
  try { raw = JSON.parse(text); } catch { raw = null; }
  const list = raw && Array.isArray(raw.items) ? raw.items : [];
  const items = [];
  const byId = new Map();
  for (const r of list) {
    const it = normItem(r);
    if (it && !byId.has(it.id)) { items.push(it); byId.set(it.id, it); }
  }
  const version = raw && (typeof raw.version === "string" || typeof raw.version === "number")
    ? String(raw.version) : (await sha256Hex(String(text))).slice(0, 12);
  return { version, items, byId };
}

/** The catalog from the site's assets (/cosmetics/catalog.json), cached for a minute. Empty when there is none. */
export async function loadCatalog(env) {
  const now = Date.now();
  if (catalogCache && catalogCache.at > now - COS.CATALOG_TTL_MS) return catalogCache.cat;
  let text = "";
  try {
    const r = env.ASSETS ? await env.ASSETS.fetch(new Request(COS.SITE + "/cosmetics/catalog.json")) : null;
    if (r && r.ok && (r.headers.get("content-type") || "").includes("json")) text = await r.text();
    else if (r && r.ok) { const t = await r.text(); if (t.trim().startsWith("{")) text = t; }
  } catch (e) {
    console.error("cosmetics catalog", e);
  }
  const cat = await parseCatalog(text || "{}");
  catalogCache = { at: now, cat };
  return cat;
}

const publicItem = (it) => it; // everything in catalog.json is public already

/** Items a player can buy: priced above zero. Partner items are only sold when the catalog gives them a price. */
const forSale = (it) => it && Number.isSafeInteger(it.priceCents) && it.priceCents > 0;
/** Free for every Evade player (every cape): never sold, worn without owning, seen by others like owned items. */
export const isFree = (it) => !!it && it.priceCents === 0;
const freeIds = (cat) => cat.items.filter(isFree).map((i) => i.id);

/** Only equipped items that are still in the catalog, in the slot the catalog says. */
function filterEquipped(equipped, cat) {
  const out = {};
  for (const [slot, e] of Object.entries(equipped || {})) {
    const it = cat.byId.get(e.id);
    if (it && it.slot === slot) out[slot] = e;
  }
  return out;
}

// ====================================================================== payment providers
//
// Each adapter: secret (the env secret's name), verify(req, raw, secret) -> boolean, parse(raw) -> an event or
// {ignore: true} or {reply: body}. An event is {type: "paid"|"refunded"|"chargeback", orderId, uuid, buyer?, items,
// amountCents, currency}. Anything else the provider sends (e-mail, name, address...) is dropped here and never stored.
// Only "generic" is wired end to end. Tebex, Paddle and Stripe check their documented signature schemes but don't map
// orders yet (they acknowledge and ignore every event) until the owner picks a provider and sets up its products.

function sigParts(header, sep) {
  const out = {};
  for (const part of String(header || "").split(sep)) {
    const i = part.indexOf("=");
    if (i > 0) (out[part.slice(0, i).trim()] ||= []).push(part.slice(i + 1).trim());
  }
  return out;
}
async function timestamped(secret, header, sep, tKey, vKey, joiner, raw) {
  const p = sigParts(header, sep);
  const t = Number((p[tKey] || [])[0]);
  if (!Number.isSafeInteger(t) || Math.abs(Date.now() / 1000 - t) > COS.WEBHOOK_SKEW_S) return false;
  for (const v of p[vKey] || []) if (await hmacOk(secret, t + joiner + raw, v)) return true;
  return false;
}
const EVENT_TYPES = { "order.paid": "paid", "order.refunded": "refunded", "order.chargeback": "chargeback" };

export const PROVIDERS = {
  /**
   * Generic HMAC-SHA256 webhook, for a provider (or a small relay) that can sign its own requests:
   *   Evade-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(SHOP_WEBHOOK_SECRET, t + "." + body)>
   *   body: {id, type: "order.paid"|"order.refunded"|"order.chargeback",
   *          order: {id, uuid, buyerUuid?, items: [itemId], amountCents, currency}}
   */
  generic: {
    secret: "SHOP_WEBHOOK_SECRET",
    verify: (req, raw, secret) => timestamped(secret, req.headers.get("evade-signature"), ",", "t", "v1", ".", raw),
    parse(raw) {
      const b = JSON.parse(raw);
      const type = EVENT_TYPES[b && b.type];
      const o = b && b.order;
      if (!type) return { ignore: true };
      if (!o || typeof o !== "object") throw new CosError(400, "missing order");
      return { type, orderId: o.id, uuid: o.uuid, buyer: o.buyerUuid, items: o.items, amountCents: o.amountCents, currency: o.currency };
    },
  },
  /** Tebex: X-Signature = hex HMAC-SHA256(secret, hex SHA-256(body)). Order mapping: not wired yet. */
  tebex: {
    secret: "TEBEX_WEBHOOK_SECRET",
    async verify(req, raw, secret) { return hmacOk(secret, await sha256Hex(raw), req.headers.get("x-signature") || ""); },
    parse(raw) {
      const b = JSON.parse(raw);
      if (b && b.type === "validation.webhook" && typeof b.id === "string") return { reply: { id: b.id } };
      return { ignore: true };
    },
  },
  /** Paddle Billing: Paddle-Signature: ts=<s>;h1=<hex HMAC-SHA256(secret, ts + ":" + body)>. Not wired yet. */
  paddle: {
    secret: "PADDLE_WEBHOOK_SECRET",
    verify: (req, raw, secret) => timestamped(secret, req.headers.get("paddle-signature"), ";", "ts", "h1", ":", raw),
    parse: () => ({ ignore: true }),
  },
  /** Stripe: Stripe-Signature: t=<s>,v1=<hex HMAC-SHA256(secret, t + "." + body)>. Not wired yet. */
  stripe: {
    secret: "STRIPE_WEBHOOK_SECRET",
    verify: (req, raw, secret) => timestamped(secret, req.headers.get("stripe-signature"), ",", "t", "v1", ".", raw),
    parse: () => ({ ignore: true }),
  },
};

// ====================================================================== worker routes

async function bearerUuid(req, store) {
  const auth = req.headers.get("authorization") || "";
  return auth.startsWith("Bearer ") ? await store.verify(auth.slice(7)) : null;
}

function cosObject(env) {
  if (!env.SOCIAL) throw new CosError(503, "cosmetics aren't available right now");
  return env.SOCIAL.get(env.SOCIAL.idFromName("global"));
}

/** Calls the store; its {status, body} becomes a response (or an error). */
async function call(env, op, args) {
  const r = await cosObject(env).cosmetics(op, args);
  if (r.status !== 200) throw new CosError(r.status, r.body.error || "error", r.body.retryAfter ? { retryAfter: r.body.retryAfter } : undefined);
  return r.body;
}

/** Items from a request: a list of distinct catalog ids, 1 to {@code max}. */
function itemList(v, cat, max = COS.MAX_CART) {
  if (!Array.isArray(v) || v.length === 0 || v.length > max) throw new CosError(400, "items must be a list of 1 to " + max + " item ids");
  const ids = [...new Set(v.map(String))];
  for (const id of ids) if (!cat.byId.has(id)) throw new CosError(400, "unknown item " + id.slice(0, 48));
  return ids;
}

/** Everything under /api/cosmetics/, /api/shop/, /api/partner/ and /api/admin/. */
export async function cosmeticsRoute(req, env, url, store) {
  try {
    const p = url.pathname;
    if (p.startsWith("/api/cosmetics/")) return await cosmeticsApi(req, env, url, store, p.slice(15));
    if (p.startsWith("/api/shop/")) return await shopApi(req, env, url, p.slice(10));
    if (p === "/api/partner/grant") return await partnerGrant(req, env);
    if (p.startsWith("/api/admin/")) return await adminApi(req, env, p.slice(11));
    return json({ error: "not found" }, 404);
  } catch (e) {
    if (e instanceof CosError) {
      const extra = e.extra && e.extra.retryAfter ? { "retry-after": String(Math.ceil(e.extra.retryAfter / 1000)) } : undefined;
      const { retryAfter, ...rest } = e.extra || {};
      return json({ error: e.message, ...rest }, e.status, extra);
    }
    console.error("cosmetics", e);
    return json({ error: "server error" }, 500);
  }
}

async function cosmeticsApi(req, env, url, store, op) {
  if (op === "catalog") {
    if (req.method !== "GET") throw new CosError(405, "use GET");
    const cat = await loadCatalog(env);
    return json({ version: cat.version, items: cat.items.map(publicItem) }, 200, { "cache-control": "public, max-age=300" });
  }
  if (op === "players") {
    if (req.method !== "GET") throw new CosError(405, "use GET");
    const raw = (url.searchParams.get("uuids") || "").split(",").map((s) => s.trim()).filter(Boolean);
    if (raw.length > COS.MAX_PLAYERS) throw new CosError(400, "at most " + COS.MAX_PLAYERS + " uuids");
    const uuids = [...new Set(raw.map(normUuid).filter((u) => UUID.test(u)))].sort();
    if (!uuids.length) return json({ players: {} }, 200, { "cache-control": "public, max-age=30" });
    // Same list (in any order) within 30 s: answered from Cloudflare's cache, without counting against the limit.
    const cache = typeof caches !== "undefined" && caches.default ? caches.default : null;
    const key = new Request(COS.SITE + "/api/cosmetics/players?uuids=" + uuids.join(","));
    if (cache) {
      try { const hit = await cache.match(key); if (hit) return hit; } catch { /* no cache here */ }
    }
    const cat = await loadCatalog(env);
    const got = await call(env, "players", { uuids, ip: await ipKey(req, env), free: freeIds(cat) });
    const players = {};
    for (const [u, eq] of Object.entries(got.players)) {
      const equipped = filterEquipped(eq, cat);
      if (Object.keys(equipped).length) players[u] = { equipped };
    }
    const res = json({ players }, 200, { "cache-control": "public, max-age=30" });
    if (cache) { try { await cache.put(key, res.clone()); } catch { /* fine */ } }
    return res;
  }
  // The rest need the player's sign-in token (the same one as /api/social/*).
  if (!["me", "equip", "redeem", "link-code"].includes(op)) return json({ error: "not found" }, 404);
  const uuid = await bearerUuid(req, store);
  if (!uuid) throw new CosError(401, "sign in again");
  const cat = await loadCatalog(env);
  switch (op) {
    case "me": {
      if (req.method !== "GET") throw new CosError(405, "use GET");
      return json(await me(env, url, uuid, cat));
    }
    case "equip": {
      const a = await readJson(req);
      const slot = String(a.slot || "").toUpperCase();
      if (!SLOT.test(slot)) throw new CosError(400, "bad slot");
      let id = a.id === undefined ? null : a.id;
      let color = null;
      if (id !== null) {
        const it = cat.byId.get(String(id));
        if (!it) throw new CosError(404, "No such item");
        if (it.slot !== slot) throw new CosError(400, "That item doesn't go in " + slot);
        id = it.id;
        if (a.color !== undefined && a.color !== null) {
          color = typeof a.color === "number" && Number.isInteger(a.color) && a.color >= 0 && a.color <= 0xffffff
            ? "#" + a.color.toString(16).padStart(6, "0") : String(a.color).toLowerCase();
          if (!COLOR.test(color)) throw new CosError(400, "color must be \"#rrggbb\"");
        }
      }
      const r = await call(env, "equip", { uuid, slot, id, color, free: id !== null && isFree(cat.byId.get(id)) });
      return json({ ok: true, equipped: filterEquipped(r.equipped, cat) });
    }
    case "redeem": {
      const a = await readJson(req);
      const code = normCode(a.code);
      if (!/^[A-Z0-9]{4,32}$/.test(code)) throw new CosError(400, "That code isn't valid");
      const r = await call(env, "redeem", { uuid, code });
      return json({ ok: true, granted: r.granted.filter((i) => cat.byId.has(i)), owned: r.owned.filter((i) => cat.byId.has(i)) });
    }
    case "link-code": {
      if (req.method !== "GET") throw new CosError(405, "use GET");
      return json(await call(env, "link.create", { uuid }));
    }
  }
}

async function me(env, url, uuid, cat) {
  const r = await call(env, "me", { uuid });
  const mode = shopMode(env, url);
  return { uuid, owned: r.owned.filter((i) => cat.byId.has(i)), equipped: filterEquipped(r.equipped, cat), shop: { open: mode !== "off", mode } };
}

async function shopApi(req, env, url, op) {
  const mode = shopMode(env, url);
  if (op === "status") {
    if (req.method !== "GET") throw new CosError(405, "use GET");
    return json({ open: mode !== "off", mode });
  }
  if (op.startsWith("webhook/")) return webhook(req, env, op.slice(8));
  if (op === "session") {
    if (req.method !== "GET") throw new CosError(405, "use GET");
    const id = url.searchParams.get("id") || "";
    if (!/^[0-9a-f]{32}$/.test(id)) throw new CosError(400, "bad session id");
    return json(await call(env, "session.get", { id }));
  }
  if (op === "resolve") {
    const a = await readJson(req);
    if (a.linkCode !== undefined) {
      const r = await call(env, "link.resolve", { code: normCode(a.linkCode), ip: await ipKey(req, env) });
      return json({ uuid: r.uuid, verified: true });
    }
    const name = String(a.username || "");
    if (!NAME.test(name)) throw new CosError(400, "Enter a Minecraft username or the code from Link the shop");
    await call(env, "rate.ip", { ip: await ipKey(req, env), what: "resolve", limit: 30, windowMs: 10 * 60 * 1000 });
    const uuid = await mojangUuid(env, name);
    return json({ uuid, verified: false });
  }
  if (op === "checkout") {
    // Production: always closed. Nothing below this line runs unless the shop is in test mode.
    if (mode !== "test") return json({ error: COS.CLOSED }, 503);
    const a = await readJson(req);
    const cat = await loadCatalog(env);
    const items = itemList(a.items, cat);
    for (const id of items) {
      const it = cat.byId.get(id);
      if (isFree(it)) throw new CosError(400, it.name + " is free for everyone: equip it in game, no purchase needed", { free: true });
      if (!forSale(it)) throw new CosError(400, it.name + " isn't for sale");
    }
    const currencies = new Set(items.map((id) => cat.byId.get(id).currency));
    if (currencies.size !== 1) throw new CosError(400, "Items in one checkout must share a currency");
    let buyer = null;
    if (a.linkCode !== undefined) buyer = (await call(env, "link.resolve", { code: normCode(a.linkCode), ip: await ipKey(req, env) })).uuid;
    else if (UUID.test(normUuid(a.uuid))) buyer = normUuid(a.uuid);
    else throw new CosError(400, "Link your account with the code from Link the shop");
    let recipient = buyer;
    if (a.giftTo !== undefined && a.giftTo !== null) {
      recipient = normUuid(a.giftTo);
      if (!UUID.test(recipient)) throw new CosError(400, "giftTo must be a player UUID");
    }
    const totalCents = items.reduce((s, id) => s + cat.byId.get(id).priceCents, 0);
    const r = await call(env, "session.create", { buyer, recipient, items, totalCents, currency: [...currencies][0], ip: await ipKey(req, env), mode });
    return json({ ...r, mode, url: "/shop?testSession=" + r.sessionId });
  }
  if (op === "test/complete" || op === "test/refund") {
    // Outside test mode (production) these routes don't exist at all.
    if (mode !== "test") return json({ error: "not found" }, 404);
    const a = await readJson(req);
    const id = String(a.sessionId || "");
    if (!/^[0-9a-f]{32}$/.test(id)) throw new CosError(400, "bad session id");
    const cat = await loadCatalog(env);
    const r = await call(env, op === "test/complete" ? "session.complete" : "session.refund",
      { id, chargeback: a.chargeback === true, known: cat.items.map((i) => i.id), free: freeIds(cat) });
    return json({ ok: true, session: r.session });
  }
  return json({ error: "not found" }, 404);
}

/** Minecraft username -> UUID through Mojang. Mojang refuses Cloudflare's addresses (403), so expect a 502 here. */
async function mojangUuid(env, name) {
  const urls = env.MOJANG_PROFILE_URL ? [env.MOJANG_PROFILE_URL + "/" + name]
    : ["https://api.mojang.com/users/profiles/minecraft/" + name, "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name];
  for (const u of urls) {
    try {
      const r = await fetch(u, { headers: { "user-agent": "EvadeClient-Shop/1.0 (+https://evadeclient.com)", accept: "application/json" } });
      if (r.status === 404 || r.status === 204) throw new CosError(404, "No Minecraft account is called " + name);
      if (r.ok) {
        const id = normUuid((await r.json()).id);
        if (UUID.test(id)) return id;
      }
    } catch (e) {
      if (e instanceof CosError) throw e;
    }
  }
  throw new CosError(502, "Couldn't look that name up. Use the code from Link the shop in Evade's Wardrobe instead.", { useLinkCode: true });
}

async function webhook(req, env, provider) {
  const ad = Object.hasOwn(PROVIDERS, provider) ? PROVIDERS[provider] : null;
  if (!ad) return json({ error: "not found" }, 404);
  if (req.method !== "POST") throw new CosError(405, "use POST");
  const secret = env[ad.secret];
  if (typeof secret !== "string" || secret.length < 16) return json({ error: "webhook not configured" }, 503);
  const raw = await req.text();
  if (raw.length > 65536) throw new CosError(413, "too large");
  if (!(await ad.verify(req, raw, secret))) return json({ error: "bad signature" }, 401);
  let ev;
  try { ev = ad.parse(raw); } catch (e) { if (e instanceof CosError) throw e; throw new CosError(400, "bad payload"); }
  if (ev.reply) return json(ev.reply);
  if (ev.ignore) return json({ ok: true, ignored: true });
  const cat = await loadCatalog(env);
  const uuid = normUuid(ev.uuid);
  const buyer = UUID.test(normUuid(ev.buyer)) ? normUuid(ev.buyer) : null;
  const orderId = String(ev.orderId || "");
  if (!ORDER_ID.test(orderId)) throw new CosError(400, "bad order id");
  if (ev.type === "paid" && !UUID.test(uuid)) throw new CosError(400, "bad uuid");
  const items = Array.isArray(ev.items) ? [...new Set(ev.items.map(String))].slice(0, 50) : [];
  const known = items.filter((i) => cat.byId.has(i));
  if (ev.type === "paid") {
    // A signed payment must still match the catalog: only items that are for sale, paid in full in their currency.
    const notSold = known.filter((i) => !forSale(cat.byId.get(i)) && !isFree(cat.byId.get(i)));
    if (notSold.length) throw new CosError(400, "not for sale: " + notSold.join(", "));
    const sold = known.filter((i) => forSale(cat.byId.get(i)));
    const total = sold.reduce((n, i) => n + cat.byId.get(i).priceCents, 0);
    const currencies = new Set(sold.map((i) => cat.byId.get(i).currency));
    const cur = typeof ev.currency === "string" ? ev.currency.toUpperCase() : null;
    if (total > 0 && (!Number.isSafeInteger(ev.amountCents) || ev.amountCents < total || currencies.size !== 1 || !currencies.has(cur))) {
      throw new CosError(400, "amount or currency doesn't match the items");
    }
  }
  const r = await call(env, "order.event", {
    provider, type: ev.type, orderId, uuid: UUID.test(uuid) ? uuid : null, buyer, items: known,
    amountCents: Number.isSafeInteger(ev.amountCents) ? ev.amountCents : null,
    currency: typeof ev.currency === "string" && /^[A-Za-z]{3}$/.test(ev.currency) ? ev.currency.toUpperCase() : null,
    free: freeIds(cat),
  });
  return json({ ok: true, ...r, unknown: items.filter((i) => !cat.byId.has(i)) });
}

async function partnerGrant(req, env) {
  if (req.method !== "POST") throw new CosError(405, "use POST");
  const partner = String(req.headers.get("x-evade-partner") || "");
  const ts = Number(req.headers.get("x-evade-timestamp"));
  const sig = String(req.headers.get("x-evade-signature") || "");
  const raw = await req.text();
  if (raw.length > 4096) throw new CosError(413, "too large");
  if (!PARTNER_ID.test(partner) || !Number.isSafeInteger(ts) || !SHA256.test(sig.toLowerCase())) throw new CosError(401, "bad signature");
  let a;
  try { a = JSON.parse(raw); } catch { a = null; }
  if (!a || typeof a !== "object") throw new CosError(400, "bad json");
  const uuid = normUuid(a.uuid);
  const item = String(a.item || "");
  if (!UUID.test(uuid) || !ITEM_ID.test(item) || !NONCE.test(String(a.nonce || ""))) throw new CosError(400, "bad request");
  const cat = await loadCatalog(env);
  const it = cat.byId.get(item);
  const r = await call(env, "partner.grant", { partner, ts, sig: sig.toLowerCase(), raw, uuid, item, nonce: a.nonce, itemOk: !!(it && it.partner && it.partner.id === partner) });
  return json({ ok: true, granted: r.granted });
}

async function adminApi(req, env, op) {
  const token = env.ADMIN_TOKEN;
  // No ADMIN_TOKEN secret (or a short one): the admin API doesn't exist.
  if (typeof token !== "string" || token.length < 32) return json({ error: "not found" }, 404);
  const auth = req.headers.get("authorization") || "";
  if (!auth.startsWith("Bearer ") || !(await sameSecret(auth.slice(7), token))) throw new CosError(401, "bad admin token");
  const a = await readJson(req, 65536);
  const cat = await loadCatalog(env);
  switch (op) {
    case "codes": {
      const items = itemList(a.items, cat, 50);
      const count = a.count === undefined ? 1 : a.count;
      if (!Number.isSafeInteger(count) || count < 1 || count > COS.MAX_CODES) throw new CosError(400, "count is 1 to " + COS.MAX_CODES);
      const days = a.expiresDays === undefined ? null : a.expiresDays;
      if (days !== null && !(Number.isSafeInteger(days) && days >= 1 && days <= 3650)) throw new CosError(400, "expiresDays is 1 to 3650");
      return json(await call(env, "admin.codes", { items, count, days, note: String(a.note || "").slice(0, 200) }));
    }
    case "grant":
    case "revoke": {
      const uuid = normUuid(a.uuid);
      if (!UUID.test(uuid)) throw new CosError(400, "bad uuid");
      const items = op === "grant" ? itemList(a.items, cat, 100) : (Array.isArray(a.items) ? a.items.map(String).slice(0, 100) : []);
      if (!items.length) throw new CosError(400, "items must be a list");
      return json(await call(env, "admin." + op, { uuid, items, free: freeIds(cat) }));
    }
    case "partner": {
      const id = String(a.id || "");
      if (!PARTNER_ID.test(id)) throw new CosError(400, "partner id: 2-32 of a-z, 0-9 and -");
      return json(await call(env, "admin.partner", { id, name: String(a.name || id).slice(0, 48) }));
    }
    case "partner/delete": {
      return json(await call(env, "admin.partner.delete", { id: String(a.id || "") }));
    }
    case "orders": {
      const uuid = normUuid(a.uuid);
      if (!UUID.test(uuid)) throw new CosError(400, "bad uuid");
      return json(await call(env, "admin.orders", { uuid }));
    }
  }
  return json({ error: "not found" }, 404);
}

// ====================================================================== storage (inside the EvadeSocial object)

/**
 * The cos_* tables in the EvadeSocial Durable Object's SQLite storage. Social clean-up (90 days unused, Delete my social
 * data) never touches them: what a player owns stays theirs.
 */
export class CosmeticsStore {
  constructor(sql) {
    this.sql = sql;
    sql.exec(`CREATE TABLE IF NOT EXISTS cos_owned (uuid TEXT NOT NULL, item TEXT NOT NULL, ref TEXT NOT NULL,
                granted INTEGER NOT NULL, PRIMARY KEY (uuid, item, ref));
              CREATE INDEX IF NOT EXISTS cos_owned_by_ref ON cos_owned (ref);
              CREATE TABLE IF NOT EXISTS cos_equipped (uuid TEXT NOT NULL, slot TEXT NOT NULL, item TEXT NOT NULL,
                color TEXT, updated INTEGER NOT NULL, PRIMARY KEY (uuid, slot));
              CREATE TABLE IF NOT EXISTS cos_orders (provider TEXT NOT NULL, order_id TEXT NOT NULL, uuid TEXT, buyer TEXT,
                items TEXT NOT NULL, amount_cents INTEGER, currency TEXT, status TEXT NOT NULL, created INTEGER NOT NULL,
                updated INTEGER NOT NULL, PRIMARY KEY (provider, order_id));
              CREATE INDEX IF NOT EXISTS cos_orders_by_uuid ON cos_orders (uuid);
              CREATE TABLE IF NOT EXISTS cos_sessions (id TEXT PRIMARY KEY, buyer TEXT NOT NULL, recipient TEXT NOT NULL,
                items TEXT NOT NULL, total_cents INTEGER NOT NULL, currency TEXT NOT NULL, status TEXT NOT NULL,
                created INTEGER NOT NULL);
              CREATE TABLE IF NOT EXISTS cos_codes (hash TEXT PRIMARY KEY, items TEXT NOT NULL, note TEXT NOT NULL DEFAULT '',
                created INTEGER NOT NULL, expires INTEGER, used_by TEXT, used_at INTEGER);
              CREATE TABLE IF NOT EXISTS cos_links (code TEXT PRIMARY KEY, uuid TEXT NOT NULL, expires INTEGER NOT NULL);
              CREATE INDEX IF NOT EXISTS cos_links_by_uuid ON cos_links (uuid);
              CREATE TABLE IF NOT EXISTS cos_partners (id TEXT PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL,
                created INTEGER NOT NULL);
              CREATE TABLE IF NOT EXISTS cos_nonces (partner TEXT NOT NULL, nonce TEXT NOT NULL, at INTEGER NOT NULL,
                PRIMARY KEY (partner, nonce));
              CREATE TABLE IF NOT EXISTS cos_rate (k TEXT PRIMARY KEY, start INTEGER NOT NULL, n INTEGER NOT NULL);`);
    /** Per-address request counts for the players lookup (memory only). */
    this.lookups = new Map();
  }

  /** One request from the worker. Returns {status, body}. */
  async run(op, a) {
    try {
      return { status: 200, body: await this.op(op, a || {}) };
    } catch (e) {
      if (e instanceof CosError) return { status: e.status, body: { error: e.message, ...(e.extra || {}) } };
      console.error("cosmetics store", op, e);
      return { status: 500, body: { error: "server error" } };
    }
  }

  async op(op, a) {
    const now = Date.now();
    switch (op) {
      case "me": return { owned: this.owned(a.uuid), equipped: this.equipped(a.uuid) };

      case "equip": {
        this.limit(a.uuid + ":equip", 60, 60 * 1000, "Slow down");
        if (a.id === null) this.sql.exec("DELETE FROM cos_equipped WHERE uuid = ? AND slot = ?", a.uuid, a.slot);
        else {
          // free items (every cape): any signed-in player may wear them; the worker checked the catalog
          if (!a.free && !this.owns(a.uuid, a.id)) throw new CosError(403, "You don't own that item");
          this.sql.exec("INSERT OR REPLACE INTO cos_equipped (uuid, slot, item, color, updated) VALUES (?, ?, ?, ?, ?)",
            a.uuid, a.slot, a.id, a.color, now);
        }
        return { equipped: this.equipped(a.uuid) };
      }

      case "players": {
        this.memLimit("ip:" + a.ip, 120, 60 * 1000);
        const uuids = (a.uuids || []).filter((u) => UUID.test(u)).slice(0, COS.MAX_PLAYERS);
        const players = {};
        const free = (Array.isArray(a.free) ? a.free : []).filter((i) => ITEM_ID.test(i)).slice(0, 500);
        if (uuids.length) {
          // owned items, and free ones (every cape) for anyone
          const freeSql = free.length ? `e.item IN (${free.map(() => "?").join(",")}) OR ` : "";
          const rows = this.sql.exec(`SELECT e.uuid, e.slot, e.item, e.color FROM cos_equipped e WHERE e.uuid IN (${uuids.map(() => "?").join(",")})
              AND (${freeSql}EXISTS (SELECT 1 FROM cos_owned o WHERE o.uuid = e.uuid AND o.item = e.item))`, ...uuids, ...free).toArray();
          for (const r of rows) (players[r.uuid] ||= {})[r.slot] = r.color ? { id: r.item, color: r.color } : { id: r.item };
        }
        return { players };
      }

      case "redeem": {
        // Counts every attempt, so codes can't be guessed: 10 an hour per account.
        this.limit(a.uuid + ":redeem", 10, 3600 * 1000, "Too many codes tried; try again later");
        const hash = await sha256Hex("evade-code:" + a.code);
        const row = this.sql.exec("SELECT items, expires, used_by FROM cos_codes WHERE hash = ?", hash).toArray()[0];
        if (!row || (row.expires && row.expires <= now)) throw new CosError(404, "That code isn't valid");
        if (row.used_by) throw new CosError(409, "That code has already been used");
        const took = this.sql.exec("UPDATE cos_codes SET used_by = ?, used_at = ? WHERE hash = ? AND used_by IS NULL RETURNING hash",
          a.uuid, now, hash).toArray().length;
        if (!took) throw new CosError(409, "That code has already been used");
        const items = JSON.parse(row.items);
        const granted = this.grant(a.uuid, items, "code:" + hash.slice(0, 16), now);
        return { granted, owned: this.owned(a.uuid) };
      }

      case "link.create": {
        this.limit(a.uuid + ":link", 20, 3600 * 1000, "Too many link codes; try again later");
        this.sql.exec("DELETE FROM cos_links WHERE uuid = ? OR expires <= ?", a.uuid, now);
        let code;
        for (let i = 0; i < 5; i++) {
          code = randomCode(6);
          if (!this.sql.exec("SELECT 1 AS x FROM cos_links WHERE code = ?", code).toArray().length) break;
        }
        this.sql.exec("INSERT OR REPLACE INTO cos_links (code, uuid, expires) VALUES (?, ?, ?)", code, a.uuid, now + COS.LINK_MS);
        return { code, expiresIn: COS.LINK_MS / 1000 };
      }

      case "link.resolve": {
        this.limit("ip:" + a.ip + ":link", 30, 10 * 60 * 1000, "Too many tries; wait a few minutes");
        const row = /^[A-Z0-9]{6}$/.test(a.code || "")
          ? this.sql.exec("SELECT uuid FROM cos_links WHERE code = ? AND expires > ?", a.code, now).toArray()[0] : null;
        if (!row) throw new CosError(404, "That code isn't valid or has expired. Get a new one from Link the shop in Evade's Wardrobe.");
        return { uuid: row.uuid };
      }

      case "rate.ip": {
        this.limit("ip:" + a.ip + ":" + a.what, a.limit, a.windowMs, "Too many tries; wait a few minutes");
        return {};
      }

      case "session.create": {
        if (a.mode !== "test") throw new CosError(503, COS.CLOSED); // belt and braces: the worker checked already
        this.limit("ip:" + a.ip + ":checkout", 20, 10 * 60 * 1000, "Too many checkouts; wait a few minutes");
        const id = hex(16);
        this.sql.exec("INSERT INTO cos_sessions (id, buyer, recipient, items, total_cents, currency, status, created) VALUES (?, ?, ?, ?, ?, ?, 'open', ?)",
          id, a.buyer, a.recipient, JSON.stringify(a.items), a.totalCents, a.currency, now);
        return { sessionId: id, recipient: a.recipient, items: a.items, totalCents: a.totalCents, currency: a.currency, expiresIn: COS.SESSION_MS / 1000 };
      }

      case "session.get": return this.session(a.id);

      case "session.complete":
      case "session.refund": {
        const s = this.sql.exec("SELECT * FROM cos_sessions WHERE id = ?", a.id).toArray()[0];
        if (!s) throw new CosError(404, "No such checkout");
        const known = new Set(a.known || []);
        const items = JSON.parse(s.items).filter((i) => known.has(i));
        if (op === "session.complete") {
          if (s.status !== "open") throw new CosError(409, "That checkout is " + s.status);
          if (s.created <= now - COS.SESSION_MS) throw new CosError(410, "That checkout has expired");
          this.sql.exec("UPDATE cos_sessions SET status = 'paid' WHERE id = ?", a.id);
          this.orderEvent({ provider: "test", type: "paid", orderId: a.id, uuid: s.recipient, buyer: s.buyer, items,
            amountCents: s.total_cents, currency: s.currency, free: a.free }, now);
        } else {
          if (s.status !== "paid") throw new CosError(409, "That checkout is " + s.status);
          const type = a.chargeback ? "chargeback" : "refunded";
          this.sql.exec("UPDATE cos_sessions SET status = ? WHERE id = ?", type, a.id);
          this.orderEvent({ provider: "test", type, orderId: a.id, uuid: s.recipient, items, free: a.free }, now);
        }
        return { session: this.session(a.id) };
      }

      case "order.event": return this.orderEvent(a, now);

      case "partner.grant": {
        const p = this.sql.exec("SELECT key FROM cos_partners WHERE id = ?", a.partner).toArray()[0];
        // Unknown partner and bad signature answer the same.
        if (!p || !(await hmacOk(p.key, a.partner + "\n" + a.ts + "\n" + a.raw, a.sig))) throw new CosError(401, "bad signature");
        if (Math.abs(now - a.ts) > COS.PARTNER_SKEW_MS) throw new CosError(401, "request too old; check the server clock");
        if (!a.itemOk) throw new CosError(403, "That item isn't one of your partner items");
        this.limit("partner:" + a.partner, 3000, 3600 * 1000, "Too many grants; slow down");
        const fresh = this.sql.exec("INSERT OR IGNORE INTO cos_nonces (partner, nonce, at) VALUES (?, ?, ?) RETURNING nonce", a.partner, a.nonce, now).toArray().length;
        if (!fresh) throw new CosError(409, "request already used");
        return { granted: this.grant(a.uuid, [a.item], "partner:" + a.partner, now).length > 0 };
      }

      case "admin.codes": {
        const codes = [];
        const expires = a.days ? now + a.days * 24 * 3600 * 1000 : null;
        for (let i = 0; i < a.count; i++) {
          const raw = randomCode(16);
          this.sql.exec("INSERT INTO cos_codes (hash, items, note, created, expires) VALUES (?, ?, ?, ?, ?)",
            await sha256Hex("evade-code:" + raw), JSON.stringify(a.items), a.note || "", now, expires);
          codes.push(raw.match(/.{4}/g).join("-"));
        }
        return { codes, items: a.items, expires };
      }
      case "admin.grant": return { granted: this.grant(a.uuid, a.items, "admin", now), owned: this.owned(a.uuid) };
      case "admin.revoke": {
        for (const item of a.items) this.sql.exec("DELETE FROM cos_owned WHERE uuid = ? AND item = ?", a.uuid, item);
        this.unequipUnowned(a.uuid, a.free);
        return { owned: this.owned(a.uuid) };
      }
      case "admin.partner": {
        const key = "evp_" + hex(24);
        this.sql.exec("INSERT OR REPLACE INTO cos_partners (id, name, key, created) VALUES (?, ?, ?, ?)", a.id, a.name, key, now);
        return { id: a.id, name: a.name, key };
      }
      case "admin.partner.delete": {
        const n = this.sql.exec("DELETE FROM cos_partners WHERE id = ? RETURNING id", a.id).toArray().length;
        return { deleted: n > 0 };
      }
      case "admin.orders": {
        const orders = this.sql.exec("SELECT provider, order_id, uuid, buyer, items, amount_cents, currency, status, created, updated FROM cos_orders WHERE uuid = ? OR buyer = ? ORDER BY created DESC LIMIT 200", a.uuid, a.uuid)
          .toArray().map((o) => ({ ...o, items: JSON.parse(o.items) }));
        return { orders, owned: this.owned(a.uuid) };
      }
    }
    throw new CosError(404, "not found");
  }

  /**
   * A payment, refund or chargeback for one order. Paid grants the items (once); refunded and chargeback take back what
   * that order granted. A refund that arrives before the payment is remembered, so the late payment grants nothing.
   */
  orderEvent(e, now) {
    const ref = "order:" + e.provider + ":" + e.orderId;
    const o = this.sql.exec("SELECT status, uuid FROM cos_orders WHERE provider = ? AND order_id = ?", e.provider, e.orderId).toArray()[0];
    if (e.type === "paid") {
      if (o) return { status: o.status, granted: [] }; // already handled (or refunded first)
      this.sql.exec("INSERT INTO cos_orders (provider, order_id, uuid, buyer, items, amount_cents, currency, status, created, updated) VALUES (?, ?, ?, ?, ?, ?, ?, 'paid', ?, ?)",
        e.provider, e.orderId, e.uuid, e.buyer || null, JSON.stringify(e.items || []), e.amountCents ?? null, e.currency || null, now, now);
      return { status: "paid", granted: this.grant(e.uuid, e.items || [], ref, now) };
    }
    if (o) this.sql.exec("UPDATE cos_orders SET status = ?, updated = ? WHERE provider = ? AND order_id = ?", e.type, now, e.provider, e.orderId);
    else this.sql.exec("INSERT INTO cos_orders (provider, order_id, uuid, buyer, items, amount_cents, currency, status, created, updated) VALUES (?, ?, ?, NULL, ?, NULL, NULL, ?, ?, ?)",
      e.provider, e.orderId, e.uuid || null, JSON.stringify(e.items || []), e.type, now, now);
    const gone = this.sql.exec("DELETE FROM cos_owned WHERE ref = ? RETURNING uuid, item", ref).toArray();
    for (const u of new Set(gone.map((g) => g.uuid))) this.unequipUnowned(u, e.free);
    return { status: e.type, revoked: gone.map((g) => g.item) };
  }

  /** Grants items under {@code ref}; returns the ones the player didn't own before. */
  grant(uuid, items, ref, now) {
    const fresh = [];
    for (const item of items) {
      if (!this.owns(uuid, item)) fresh.push(item);
      this.sql.exec("INSERT OR IGNORE INTO cos_owned (uuid, item, ref, granted) VALUES (?, ?, ?, ?)", uuid, item, ref, now);
    }
    return fresh;
  }

  owns(uuid, item) {
    return this.sql.exec("SELECT 1 AS x FROM cos_owned WHERE uuid = ? AND item = ? LIMIT 1", uuid, item).toArray().length > 0;
  }

  owned(uuid) {
    return this.sql.exec("SELECT DISTINCT item FROM cos_owned WHERE uuid = ? ORDER BY item", uuid).toArray().map((r) => r.item);
  }

  equipped(uuid) {
    const out = {};
    for (const r of this.sql.exec("SELECT slot, item, color FROM cos_equipped WHERE uuid = ?", uuid).toArray()) {
      out[r.slot] = r.color ? { id: r.item, color: r.color } : { id: r.item };
    }
    return out;
  }

  /** Takes off what {@code uuid} no longer owns; free items ({@code free}: their ids) stay on. */
  unequipUnowned(uuid, free) {
    const keep = (Array.isArray(free) ? free : []).filter((i) => ITEM_ID.test(i)).slice(0, 500);
    const keepSql = keep.length ? ` AND item NOT IN (${keep.map(() => "?").join(",")})` : "";
    this.sql.exec("DELETE FROM cos_equipped WHERE uuid = ? AND item NOT IN (SELECT item FROM cos_owned WHERE uuid = ?)" + keepSql, uuid, uuid, ...keep);
  }

  session(id) {
    const s = this.sql.exec("SELECT * FROM cos_sessions WHERE id = ?", id).toArray()[0];
    if (!s) throw new CosError(404, "No such checkout");
    return { id: s.id, status: s.status, items: JSON.parse(s.items), totalCents: s.total_cents, currency: s.currency, recipient: s.recipient };
  }

  /** At most {@code limit} per {@code windowMs} for {@code key}, kept in storage; else a 429. */
  limit(key, limit, windowMs, msg) {
    const now = Date.now();
    const row = this.sql.exec("SELECT start, n FROM cos_rate WHERE k = ?", key).toArray()[0];
    if (!row || row.start <= now - windowMs) {
      this.sql.exec("INSERT OR REPLACE INTO cos_rate (k, start, n) VALUES (?, ?, 1)", key, now);
      return;
    }
    if (row.n >= limit) throw new CosError(429, msg, { retryAfter: row.start + windowMs - now });
    this.sql.exec("UPDATE cos_rate SET n = n + 1 WHERE k = ?", key);
  }

  /** The same in memory only (for cheap, frequent lookups). */
  memLimit(key, limit, windowMs) {
    const now = Date.now();
    let b = this.lookups.get(key);
    if (!b || b.start <= now - windowMs) { b = { start: now, n: 0 }; this.lookups.set(key, b); }
    if (++b.n > limit) throw new CosError(429, "Slow down", { retryAfter: b.start + windowMs - now });
    if (this.lookups.size > 10000) for (const [k, v] of this.lookups) if (v.start <= now - windowMs) this.lookups.delete(k);
  }

  /** Called from EvadeSocial's sweep: lapsed link codes, test checkouts, partner nonces and rate counters. */
  sweep(now) {
    this.sql.exec("DELETE FROM cos_links WHERE expires <= ?", now);
    this.sql.exec("DELETE FROM cos_sessions WHERE status = 'open' AND created <= ?", now - COS.SESSION_MS);
    this.sql.exec("DELETE FROM cos_nonces WHERE at <= ?", now - 2 * COS.PARTNER_SKEW_MS);
    this.sql.exec("DELETE FROM cos_rate WHERE start <= ?", now - 24 * 3600 * 1000);
  }
}
