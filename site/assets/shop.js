// Evade Shop (/shop): catalogue, 3D previews (skinview3d, MIT), account linking, cart and checkout.
// Backend contract: worker/cosmetics.js. Nothing is sold yet: /api/shop/status says {open:false} in production, and
// the page then keeps browsing and previews working but turns checkout off.
(() => {
"use strict";
const one = (s, r = document) => r.querySelector(s);
const all = (s, r = document) => Array.from(r.querySelectorAll(s));
const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const grid = one("#shopGrid");
if (!grid) return;

// skinview3d, pinned to an exact version with Subresource Integrity, loaded only when a preview is needed.
const SV_URL = "https://cdn.jsdelivr.net/npm/skinview3d@3.4.2/bundles/skinview3d.bundle.js";
const SV_SRI = "sha384-IpD3xE/jsAa6T7+6PYhg/3FZ+8dAqrFryxJsRs3pCgIYC9b7rUZZbvSMOI1aoQkb";
const LOCAL = /^(localhost|127\.0\.0\.1|\[::1\])$|\.localhost$|\.test$/.test(location.hostname);
// The catalogue comes from the worker (which reads /cosmetics/catalog.json). A local static server has no worker, so
// local previews fall back to the files scripts/build-pages.py copies from site-src/cosmetics.
const CATALOG_URLS = ["/api/cosmetics/catalog", ...(LOCAL ? ["/cosmetics/catalog.json"] : [])];
const params = new URLSearchParams(location.search);

const CATS = [
  ["capes", "Capes"], ["wings", "Wings"], ["hats", "Hats"], ["masks", "Masks"], ["back", "Back"], ["auras", "Auras"],
  ["trails", "Trails"], ["pets", "Pets"], ["emotes", "Emotes"], ["killfx", "Kill effects"], ["winfx", "Win effects"],
  ["skins", "Item skins"],
  ["partner", "Partner servers"],
];
const CAT_LABEL = Object.fromEntries(CATS);
// Catalogue slot (or category) -> shop category.
const SLOT_CAT = {
  CAPE: "capes", CAPES: "capes", WINGS: "wings", WING: "wings", HAT: "hats", HATS: "hats", HEAD: "hats", MASK: "masks",
  MASKS: "masks", FACE: "masks", BACK: "back", BACKPACK: "back", AURA: "auras", AURAS: "auras", TRAIL: "trails",
  TRAILS: "trails", PET: "pets", PETS: "pets", EMOTE: "emotes", EMOTES: "emotes", KILL_EFFECT: "killfx", KILLFX: "killfx",
  KILL_EFFECTS: "killfx", WIN_EFFECT: "winfx", WIN_EFFECTS: "winfx", WINFX: "winfx", ITEM_SKIN: "skins", ITEM_SKINS: "skins", SKIN: "skins", PARTNER: "partner",
};
// Three tiers: regular (not animated), special (animated; may not be priced yet), partner (partner-server items).
// Older names (common, rare, epic, legendary) read as regular. Colours match --r-* in shop.css and Rarity.java.
const RARITIES = ["regular", "special", "partner"];
const RARITY_LABEL = { regular: "Regular", special: "Special", partner: "Partner" };
const RARITY_COLOR = { regular: "#c3c7d1", special: "#ffc23d", partner: "#2edbc0" };
const BACK_VIEW = new Set(["capes", "wings", "back", "trails"]);
const HEAD_VIEW = new Set(["hats", "masks"]);

// Small line icons, used when an item has no picture and 3D isn't available.
const GLYPH = {
  capes: "M7 3h10l2 18-7-3-7 3z", wings: "M12 7C9 3 4 3 2 5c2 1 3 3 3 5-1 3 1 6 7 9 6-3 8-6 7-9 0-2 1-4 3-5-2-2-7-2-10 2z",
  hats: "M4 17h16M7 17V8a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v9M7 13h10", masks: "M3 8c3-2 15-2 18 0 0 6-3 9-6 9-1.5 0-2-2-3-2s-1.5 2-3 2c-3 0-6-3-6-9zM8 11h2M14 11h2",
  back: "M8 7V5a4 4 0 0 1 8 0v2M6 7h12v13H6zM9 12h6", auras: "M12 2v4M12 18v4M2 12h4M18 12h4M5 5l3 3M16 16l3 3M5 19l3-3M16 8l3-3",
  trails: "M3 20c4-1 6-4 8-8s5-7 10-8M6 14h.01M10 18h.01M14 9h.01", pets: "M7 14c-2 0-3 2-3 4s2 3 4 2l4-1 4 1c2 1 4 0 4-2s-1-4-3-4M8 9a1.5 2 0 1 0 0 .1M16 9a1.5 2 0 1 0 0 .1M12 7a1.5 2 0 1 0 0 .1",
  emotes: "M8 13V5a1.5 1.5 0 0 1 3 0v6m0-1V3.5a1.5 1.5 0 0 1 3 0V11m0-1V5a1.5 1.5 0 0 1 3 0v9a7 7 0 0 1-7 7c-3 0-5-2-7-5l-2-3a1.5 1.5 0 0 1 2.5-1.5L8 14",
  killfx: "M12 2l2 6 6-2-4 5 5 3-6 1 1 6-4-4-4 4 1-6-6-1 5-3-4-5 6 2z", winfx: "M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0zM7 6H4a3 3 0 0 0 3 4M17 6h3a3 3 0 0 1-3 4", skins: "M14 3h7v7L9 22l-3-3-3 3-1-1 3-3-3-3z",
  partner: "M12 2l3 6 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1z",
};
const glyph = cat => `<svg class="glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${GLYPH[cat] || GLYPH.partner}"/></svg>`;

const store = {
  get(k, d) { try { const v = localStorage.getItem("evade-shop-" + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem("evade-shop-" + k, JSON.stringify(v)); } catch (e) { /* private mode: keep it in memory */ } },
};

let busy = false;
const state = {
  items: [], byId: new Map(), currency: "USD", cat: "", q: "", rarity: "", sort: "featured",
  status: { open: false, mode: "off" }, simulated: false,
  cart: store.get("cart", []), link: null, thumbs: new Map(),
};

// ---------------------------------------------------------------- money
const fmt = (cents, cur) => {
  try { return new Intl.NumberFormat(undefined, { style: "currency", currency: cur || state.currency }).format(cents / 100); }
  catch (e) { return (cents / 100).toFixed(2) + " " + (cur || state.currency); }
};
// Same rules as the backend: priced above zero is for sale; priced 0 is free for every Evade player and never sold
// (every cape: never in the cart or a checkout); unpriced special items are "Price coming soon".
const forSale = it => it && Number.isInteger(it.priceCents) && it.priceCents > 0;
const isFree = it => !!it && it.priceCents === 0;
const soon = it => it && it.rarity === "special" && !forSale(it) && !isFree(it) && !it.partner;
const priceText = it => isFree(it) ? "Free" : forSale(it) ? fmt(it.priceCents, it.currency) : it.partner ? "Partner reward" : soon(it) ? "Price coming soon" : "Not for sale";
const priceClass = it => isFree(it) ? " gift" : forSale(it) ? "" : soon(it) ? " soon" : " free";

// ---------------------------------------------------------------- catalogue
function normItem(raw, i) {
  if (!raw || typeof raw !== "object" || !raw.id) return null;
  const id = String(raw.id);
  const slot = String(raw.slot || raw.category || "").toUpperCase().replace(/[\s-]+/g, "_");
  const cat = SLOT_CAT[slot] || "back";
  const priceCents = Number.isSafeInteger(raw.priceCents) && raw.priceCents >= 0 ? raw.priceCents : null;
  const fileMap = (raw.assets && raw.assets.files) || raw.files || {};
  const files = fileMap && typeof fileMap === "object" ? Object.keys(fileMap) : [];
  // Asset files live next to the page at /cosmetics/<id>/ (the API names the production origin; use this one).
  const base = new URL("/cosmetics/" + encodeURIComponent(id) + "/", location.href).href;
  const has = f => files.includes(f) ? base + f : null;
  // the card picture: the 512 px retina thumbnail where there is one (still items), else thumb.png (animated items keep
  // their strip); cache-busted by the file's hash from the catalogue
  const hd = raw.thumbHd && typeof raw.thumbHd === "object" && typeof raw.thumbHd.file === "string" && /^[\w.-]+\.png$/.test(raw.thumbHd.file) ? raw.thumbHd : null;
  const bust = h => typeof h === "string" && /^[0-9a-f]{8,}$/i.test(h) ? "?v=" + h.slice(0, 12) : "";
  const image = hd ? base + hd.file + bust(hd.sha256) : files.includes("thumb.png") ? base + "thumb.png" + bust(fileMap["thumb.png"]) : null;
  const rarity = String(raw.rarity || "").toLowerCase();
  const partner = raw.partner && typeof raw.partner === "object" ? { id: raw.partner.id, name: String(raw.partner.name || raw.partner.id || "Partner") } : null;
  return {
    id, name: String(raw.name || id), cat, slot, rarity: RARITIES.includes(rarity) ? rarity : partner ? "partner" : "regular", priceCents,
    currency: typeof raw.currency === "string" ? raw.currency.toUpperCase() : null, partner,
    defaultColor: typeof raw.defaultColor === "string" ? raw.defaultColor : null,
    desc: String(raw.description || ""), partnerHow: String(raw.partnerHow || ""),
    model: has("model.json"), texture: has("texture.png"), image, details: has("item.json"), glow: has("emissive.png"),
    fx: raw.fx || null, emote: raw.emote || null, order: Number.isFinite(raw.order) ? raw.order : i,
    search: [raw.name, CAT_LABEL[cat], slot.replace(/_/g, " "), RARITIES.includes(rarity) ? rarity : "", partner && partner.name, priceCents === 0 ? "free" : ""].join(" ").toLowerCase(),
  };
}

// item.json: description, particle effect and emote keyframes (not in the catalogue itself).
const detailCache = new Map();
function detailsOf(it) {
  if (!it.details) return Promise.resolve(it);
  if (!detailCache.has(it.id)) {
    detailCache.set(it.id, fetch(it.details).then(r => r.ok ? r.json() : {}).catch(() => ({})).then(d => {
      if (d.description && !it.desc) it.desc = String(d.description);
      if (d.fx && !it.fx) it.fx = d.fx;
      if (d.emote && !it.emote) it.emote = d.emote;
      if (d.animation && typeof d.animation === "object") it.animation = d.animation;
      if (d.physics && typeof d.physics === "object") it.physics = d.physics;
      return it;
    }));
  }
  return detailCache.get(it.id);
}

async function loadCatalog() {
  for (const url of CATALOG_URLS) {
    try {
      const r = await fetch(url, { headers: { accept: "application/json" } });
      if (!r.ok) continue;
      const data = await r.json();
      const items = (Array.isArray(data.items) ? data.items : []).map(normItem).filter(Boolean);
      if (!items.length) continue;
      if (data.currency) state.currency = String(data.currency).toUpperCase();
      else if (items.find(i => i.currency)) state.currency = items.find(i => i.currency).currency;
      return { items };
    } catch (e) { /* try the next */ }
  }
  return { items: [] };
}

// ---------------------------------------------------------------- backend (with a simulated one for local previews)
async function api(path, body) {
  const r = await fetch(path, body === undefined ? { headers: { accept: "application/json" } }
    : { method: "POST", headers: { "content-type": "application/json", accept: "application/json" }, body: JSON.stringify(body) });
  let j = null;
  try { j = await r.json(); } catch (e) { /* not json */ }
  if (!r.ok || !j) { const err = new Error((j && j.error) || "Something went wrong (" + r.status + ")"); err.status = r.status; err.body = j; throw err; }
  return j;
}

// Local only, when there is no backend (a plain static server): a stand-in that behaves like test mode.
const sim = {
  sessions: store.get("sim-sessions", {}),
  save() { store.set("sim-sessions", this.sessions); },
  async call(path, body) {
    await new Promise(r => setTimeout(r, 350));
    const fail = (msg, status = 400) => { const e = new Error(msg); e.status = status; throw e; };
    if (path === "/api/shop/resolve") {
      if (body.linkCode !== undefined) {
        const c = String(body.linkCode).toUpperCase();
        if (!/^[A-Z0-9]{6}$/.test(c)) fail("That code isn't valid or has expired. Get a new one from Link the shop in Evade's Wardrobe.", 404);
        return { uuid: "5e1f" + Array.from(c).map(ch => ch.charCodeAt(0).toString(16)).join("").padEnd(28, "0").slice(0, 28), verified: true };
      }
      fail("Enter a Minecraft username or the code from Link the shop", 400);
    }
    if (path === "/api/shop/checkout") {
      const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, "0")).join("");
      const items = body.items.filter(i => forSale(state.byId.get(i)));
      const buyer = (await this.call("/api/shop/resolve", { linkCode: body.linkCode })).uuid;
      const s = { id, status: "open", items, totalCents: items.reduce((n, i) => n + state.byId.get(i).priceCents, 0), currency: state.currency, recipient: body.giftTo || buyer };
      this.sessions[id] = s; this.save();
      return { sessionId: id, ...s, mode: "test", url: "/shop?testSession=" + id };
    }
    if (path.startsWith("/api/shop/session?id=")) {
      const s = this.sessions[path.split("=")[1]];
      if (!s) fail("No such checkout", 404);
      return s;
    }
    if (path === "/api/shop/test/complete" || path === "/api/shop/test/refund") {
      const s = this.sessions[body.sessionId];
      if (!s) fail("No such checkout", 404);
      if (path.endsWith("complete")) { if (s.status !== "open") fail("That checkout is " + s.status, 409); s.status = "paid"; }
      else s.status = body.chargeback ? "chargeback" : "refunded";
      this.save();
      return { ok: true, session: s };
    }
    fail("not found", 404);
  },
};
const call = (path, body) => state.simulated ? sim.call(path, body) : api(path, body);

async function loadStatus() {
  try {
    const s = await api("/api/shop/status");
    return { open: !!s.open, mode: String(s.mode || (s.open ? "live" : "off")) };
  } catch (e) {
    // No backend: production stays closed; a local static preview simulates test mode.
    if (LOCAL && params.get("shop") !== "closed") { state.simulated = true; return { open: true, mode: "test" }; }
    return { open: false, mode: "off" };
  }
}

// ---------------------------------------------------------------- 3D (skinview3d)
let glOk = null;
function hasWebGL() {
  if (glOk !== null) return glOk;
  try {
    const c = document.createElement("canvas");
    const gl = window.WebGLRenderingContext && (c.getContext("webgl2") || c.getContext("webgl"));
    glOk = !!gl;
    const lose = gl && gl.getExtension("WEBGL_lose_context");
    if (lose) lose.loseContext();
  } catch (e) { glOk = false; }
  return glOk;
}
let svPromise = null;
function loadSV() {
  if (window.skinview3d) return Promise.resolve(window.skinview3d);
  return svPromise || (svPromise = new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = SV_URL; s.integrity = SV_SRI; s.crossOrigin = "anonymous"; s.async = true;
    s.onload = () => window.skinview3d ? resolve(window.skinview3d) : reject(new Error("skinview3d missing"));
    s.onerror = () => { svPromise = null; reject(new Error("skinview3d failed to load")); };
    document.head.appendChild(s);
  }));
}

// An original default skin for the preview player (drawn here, 64x64 classic layout).
let skinCanvas = null;
function defaultSkin() {
  if (skinCanvas) return skinCanvas;
  const c = document.createElement("canvas"); c.width = c.height = 64;
  const g = c.getContext("2d");
  const box = (u, v, w, h, d, col) => { g.fillStyle = col; g.fillRect(u + d, v, w * 2, d); g.fillRect(u, v + d, (w + d) * 2, h); };
  box(0, 0, 8, 8, 8, "#c99a76");                 // head
  g.fillStyle = "#2a1c16"; g.fillRect(8, 0, 16, 8); g.fillRect(0, 8, 32, 2); g.fillRect(24, 8, 8, 8); g.fillRect(0, 8, 2, 6); g.fillRect(16, 8, 2, 5); // hair
  g.fillStyle = "#fff"; g.fillRect(9, 12, 2, 1); g.fillRect(13, 12, 2, 1);   // eyes
  g.fillStyle = "#1d3b6e"; g.fillRect(10, 12, 1, 1); g.fillRect(13, 12, 1, 1);
  g.fillStyle = "#a8775a"; g.fillRect(11, 14, 2, 1);                         // mouth shadow
  box(16, 16, 8, 12, 4, "#17171c");              // body: dark hoodie
  g.fillStyle = "#e11d2e"; g.fillRect(20, 20, 8, 1); g.fillRect(23, 21, 2, 4); // red trim + zip
  g.fillStyle = "#24242b"; g.fillRect(20, 28, 8, 4);
  box(40, 16, 4, 12, 4, "#17171c"); box(32, 48, 4, 12, 4, "#17171c");          // arms
  g.fillStyle = "#c99a76"; g.fillRect(40, 28, 16, 4); g.fillRect(32, 60, 16, 4); g.fillRect(44, 16, 4, 4); g.fillRect(36, 48, 4, 4);
  g.fillStyle = "#e11d2e"; g.fillRect(40, 26, 16, 1); g.fillRect(32, 58, 16, 1);
  box(0, 16, 4, 12, 4, "#2c2f3a"); box(16, 48, 4, 12, 4, "#2c2f3a");          // legs
  g.fillStyle = "#0d0d10"; g.fillRect(0, 29, 16, 3); g.fillRect(16, 61, 16, 3); g.fillRect(8, 16, 4, 4); g.fillRect(24, 48, 4, 4); // shoes
  return (skinCanvas = c);
}

function stageBackground(color, w = 256, h = 256) {
  const c = document.createElement("canvas"); c.width = w; c.height = h;
  const g = c.getContext("2d");
  g.fillStyle = "#09090b"; g.fillRect(0, 0, w, h);
  const r = g.createRadialGradient(w / 2, h * .42, 0, w / 2, h * .42, Math.max(w, h) * .62);
  r.addColorStop(0, color + "48"); r.addColorStop(1, color + "00");
  g.fillStyle = r; g.fillRect(0, 0, w, h);
  const f = g.createRadialGradient(w / 2, h * 1.05, 0, w / 2, h * 1.05, w * .5);
  f.addColorStop(0, "rgba(255,255,255,.07)"); f.addColorStop(1, "rgba(255,255,255,0)");
  g.fillStyle = f; g.fillRect(0, 0, w, h);
  return c;
}

// three.js classes, taken from skinview3d's own objects (the bundle doesn't export three itself).
function threeOf(viewer) {
  const m = viewer.playerObject.skin.head.innerLayer;
  return { Mesh: m.constructor, Box: m.geometry.constructor, Mat: m.material.constructor, Tex: m.material.map.constructor,
           Group: Object.getPrototypeOf(viewer.playerObject.skin.head.constructor), map: m.material.map };
}

const loadImage = src => new Promise((res, rej) => { const i = new Image(); i.crossOrigin = "anonymous"; i.onload = () => res(i); i.onerror = rej; i.src = src; });
const jsonCache = new Map();
const getJson = url => {
  if (!jsonCache.has(url)) jsonCache.set(url, fetch(url).then(r => r.ok ? r.json() : null).catch(() => null));
  return jsonCache.get(url);
};

// ---- models: format "evade-cosmetic-model/1" (see site-src/cosmetics/README.md and scripts/build-cosmetics.py)
// Entity space is Minecraft's model space: y down, front at -z, each bone attached to a body part and given in that
// part's coordinates. skinview3d is y up with the front at +z, a half turn about x: each part gets an adapter group
// turned by PI about x, placed at the part's Minecraft pivot, and the model's own numbers are used unchanged inside it.
const ATTACH = { // skinview3d part, and where the Minecraft pivot sits inside that part's group
  head: ["head", [0, 0, 0]], body: ["body", [0, 6, 0]], right_arm: ["rightArm", [0, 0, 0]], left_arm: ["leftArm", [0, 0, 0]],
  rightArm: ["rightArm", [0, 0, 0]], leftArm: ["leftArm", [0, 0, 0]], root: [null, [0, 8, 0]],
};

// One cuboid with Minecraft's box UVs (the same quads and corner UVs as ModelPart.Cuboid).
function cuboid(T, c, tw, th, mat) {
  let [x, y, z] = c.origin, [sx, sy, sz] = c.size, inf = c.inflate || 0;
  let f = x + sx, g = y + sy, h = z + sz;
  x -= inf; y -= inf; z -= inf; f += inf; g += inf; h += inf;
  if (c.mirror) [x, f] = [f, x];
  const v1 = [x, y, z], v2 = [f, y, z], v3 = [f, g, z], v4 = [x, g, z], v5 = [x, y, h], v6 = [f, y, h], v7 = [f, g, h], v8 = [x, g, h];
  const [u, v] = c.uv || [0, 0];
  const j = u, k = u + sz, l = u + sz + sx, m = u + sz + sx + sx, n = u + sz + sx + sz, o = u + sz + sx + sz + sx;
  const p = v, q = v + sz, r = v + sz + sy;
  const faces = [[[v6, v5, v1, v2], [k, p, l, q]], [[v3, v4, v8, v7], [l, q, m, p]], [[v1, v5, v8, v4], [j, q, k, r]],
                 [[v2, v1, v4, v3], [k, q, l, r]], [[v6, v2, v3, v7], [l, q, n, r]], [[v5, v6, v7, v8], [n, q, o, r]]];
  // Per-face UVs (format v2): only the listed faces are drawn, each with its own rectangle, laid like the box-UV face.
  const FACE = ["up", "down", "west", "north", "east", "south"];
  const per = c.faces && typeof c.faces === "object" ? c.faces : null;
  const geo = new T.Box(1, 1, 1); // 24 corners in 6 groups of 4: [top-left, top-right, bottom-left, bottom-right]
  const pos = geo.attributes.position, uvA = geo.attributes.uv, nor = geo.attributes.normal;
  faces.forEach(([q4, rect], fi) => {
    const f = per ? per[FACE[fi]] : null;
    if (per && !(f && Array.isArray(f.uv) && f.uv.length === 4)) { // not listed: collapse the face away
      for (let ci = 0; ci < 4; ci++) pos.setXYZ(fi * 4 + ci, 0, 0, 0);
      return;
    }
    const [u1, va, u2, vb] = per ? f.uv : rect;
    let uvs = [[u2, va], [u1, va], [u1, vb], [u2, vb]];
    const rot = per ? ((Math.round((f.rotation || 0) / 90) % 4) + 4) % 4 : 0;
    if (rot) uvs = uvs.map((_, i) => uvs[(i + rot) % 4]);
    const slot = [1, 0, 2, 3]; // corner order of the quad -> BoxGeometry's corner order
    const e1 = q4[2].map((a, i) => a - q4[1][i]), e2 = q4[0].map((a, i) => a - q4[1][i]);
    let nx = e1[1] * e2[2] - e1[2] * e2[1], ny = e1[2] * e2[0] - e1[0] * e2[2], nz = e1[0] * e2[1] - e1[1] * e2[0];
    const len = Math.hypot(nx, ny, nz) || 1;
    for (let ci = 0; ci < 4; ci++) {
      const at = fi * 4 + slot[ci];
      pos.setXYZ(at, ...q4[ci]);
      uvA.setXY(at, uvs[ci][0] / tw, 1 - uvs[ci][1] / th);
      nor.setXYZ(at, nx / len, ny / len, nz / len);
    }
  });
  pos.needsUpdate = uvA.needsUpdate = nor.needsUpdate = true;
  geo.computeBoundingSphere();
  return new T.Mesh(geo, mat);
}

// Builds every bone of a model onto the viewer's player; returns the added groups and a per-frame animator.
// Animated textures (item.json "animation"): the PNG is a vertical strip of frames; the GPU texture is a frame-sized
// canvas redrawn only when the frame (or, with interpolate, the blend step) changes. Same timing as the client.
function frameClock(an) {
  const frames = Math.max(1, Math.min(64, an.frames | 0)), ft = Math.max(.5, +an.frametime || 1);
  let order = Array.isArray(an.order) && an.order.length ? an.order.map(o => typeof o === "object" ? { i: o.index | 0, t: Math.max(.5, +o.time || ft) } : { i: o | 0, t: ft }) : null;
  if (!order) order = Array.from({ length: frames }, (_, i) => ({ i, t: ft }));
  order = order.filter(o => o.i >= 0 && o.i < frames);
  const cycle = order.reduce((a, o) => a + o.t, 0) || 1;
  return seconds => {
    let t = (seconds * 20) % cycle, k = 0;
    while (k < order.length - 1 && t >= order[k].t) { t -= order[k].t; k++; }
    const cur = order[k], next = order[(k + 1) % order.length];
    if (!an.interpolate || cur.i === next.i) return [cur.i, cur.i, 0];
    const steps = Math.max(2, Math.min(16, Math.round(cur.t * 3)));
    return [cur.i, next.i, Math.min(steps - 1, Math.floor(t / cur.t * steps)) / steps];
  };
}
// Pixel art stays sharp: nearest filtering both ways and no mipmaps (as in the client), never smoothed or smeared.
const NEAREST = 1003; // THREE.NearestFilter
const pixelated = t => { t.magFilter = NEAREST; t.minFilter = NEAREST; t.generateMipmaps = false; return t; };
// The item's texture and glow layer as drawn by the thumbnails (scripts/build-cosmetics.py): where the glow layer has
// alpha it replaces the lit colour (so the diffuse is darkened there, not added to and blown out to white), and the
// glow layer is flattened onto black so its see-through texels never glow with whatever colour the PNG kept in them.
const canvasOf = (img, w = img.naturalWidth || img.width, h = img.naturalHeight || img.height) => {
  const c = document.createElement("canvas"); c.width = w; c.height = h; return c;
};
async function layerImages(texSrc, glowSrc) {
  const [tex, glow] = await Promise.all([texSrc ? loadImage(texSrc).catch(() => null) : null, glowSrc ? loadImage(glowSrc).catch(() => null) : null]);
  if (!glow) return { tex, glow: null };
  const w = glow.naturalWidth, h = glow.naturalHeight;
  const gc = canvasOf(glow), g = gc.getContext("2d");
  g.fillStyle = "#000"; g.fillRect(0, 0, w, h); g.drawImage(glow, 0, 0);
  let dc = tex;
  if (tex && tex.naturalWidth === w && tex.naturalHeight === h) {
    const mask = canvasOf(glow), m = mask.getContext("2d");
    m.drawImage(glow, 0, 0); m.globalCompositeOperation = "source-in"; m.fillStyle = "#000"; m.fillRect(0, 0, w, h);
    dc = canvasOf(tex); const d = dc.getContext("2d");
    d.drawImage(tex, 0, 0); d.globalCompositeOperation = "source-atop"; d.drawImage(mask, 0, 0);
  }
  // an emissive bone shows all of it at full strength: the darkened texture with the glow layer over it
  const full = () => {
    if (dc === tex) return tex;
    const fc = canvasOf(dc), f = fc.getContext("2d");
    f.drawImage(dc, 0, 0); f.globalCompositeOperation = "lighter"; f.drawImage(gc, 0, 0);
    return fc;
  };
  return { tex: dc, glow: gc, full };
}
async function stripTexture(T, img, an, aspect) {
  if (typeof img === "string") img = await loadImage(img);
  const frames = an && an.frames > 1 ? an.frames | 0 : 1;
  if (frames <= 1) { const t = pixelated(new T.Tex(img)); t.needsUpdate = true; return { tex: t, tick: null }; }
  const w = img.naturalWidth || img.width, fh = Math.round((img.naturalHeight || img.height) / frames) || Math.round(w * aspect);
  const c = document.createElement("canvas"); c.width = w; c.height = fh;
  const g = c.getContext("2d"); g.imageSmoothingEnabled = false;
  const tex = pixelated(new T.Tex(c));
  const at = frameClock(an);
  let shown = "";
  const tick = sec => {
    const [a, b, f] = at(sec), key = a + ":" + b + ":" + f;
    if (key === shown) return;
    shown = key;
    g.clearRect(0, 0, w, fh); g.globalAlpha = 1;
    g.drawImage(img, 0, a * fh, w, fh, 0, 0, w, fh);
    if (f > 0) { g.globalAlpha = f; g.drawImage(img, 0, b * fh, w, fh, 0, 0, w, fh); g.globalAlpha = 1; }
    tex.needsUpdate = true;
  };
  tick(performance.now() / 1000);
  return { tex, tick };
}

async function buildModel(viewer, it, model) {
  const T = threeOf(viewer);
  const [tw, th] = Array.isArray(model.texture) ? model.texture : [64, 32];
  const made = [], anims = [];
  let tex = null, glow = null;
  const layers = await layerImages(it.texture, it.glow);
  if (layers.tex) {
    try { const s = await stripTexture(T, layers.tex, it.animation, th / tw); tex = s.tex; if (s.tick) anims.push(() => s.tick(performance.now() / 1000)); }
    catch (e) { tex = null; }
  }
  if (layers.glow) {
    try { const s = await stripTexture(T, layers.glow, it.animation, th / tw); glow = s.tex; if (s.tick) anims.push(() => s.tick(performance.now() / 1000)); }
    catch (e) { glow = null; }
  }
  let full = tex;
  if (layers.glow && layers.full && (model.bones || []).some(b => b.emissive)) {
    try { const s = await stripTexture(T, layers.full(), it.animation, th / tw); full = s.tex; if (s.tick) anims.push(() => s.tick(performance.now() / 1000)); }
    catch (e) { full = tex; }
  }
  const texs = viewer._texs || (viewer._texs = []);
  texs.push(...[tex, glow, full !== tex ? full : null].filter(Boolean));
  const phys = Object.assign({ stiffness: .5, lift: 1, sway: 1 }, it.physics || {});
  const motion = viewer._motion || (viewer._motion = { moving: 0 });
  const adapters = {};
  const adapter = attach => {
    if (adapters[attach]) return adapters[attach];
    const g = new T.Group();
    if (attach === "item") {
      // Item space (y up, a 16-unit block), held in the right hand the way Minecraft draws a held item: the arm's own
      // space, vanilla's hand offset (turn -90 about x and 180 about y, then 1, 2, -10 pixels), then the model's
      // thirdperson_righthand display transform and the -8 centring. Follows the arm in every animation.
      const R = d => d * Math.PI / 180;
      const arm = new T.Group(); arm.rotation.x = Math.PI;
      viewer.playerObject.skin.rightArm.add(arm);
      const hand = new T.Group(); hand.rotation.set(-Math.PI / 2, Math.PI, 0, "XYZ"); arm.add(hand);
      const d = (model.display && model.display.thirdperson_righthand) || {};
      const [rx, ry, rz] = d.rotation || [0, -90, 55], [tx, ty, tz] = d.translation || [0, 4, .5], sc = d.scale || [.85, .85, .85];
      const disp = new T.Group();
      disp.position.set(1 + tx, 2 + ty, -10 + tz); disp.rotation.set(R(rx), R(ry), R(rz), "XYZ"); disp.scale.set(...sc);
      hand.add(disp);
      g.position.set(-8, -8, -8); disp.add(g);
      made.push(arm);
      return (adapters[attach] = g);
    } else {
      const [part, at] = ATTACH[attach] || ATTACH.body;
      g.position.set(...at); g.rotation.x = Math.PI;
      (part ? viewer.playerObject.skin[part] : viewer.playerObject).add(g);
    }
    made.push(g);
    return (adapters[attach] = g);
  };
  const tint = it.defaultColor || null;
  const byName = {};
  for (const b of model.bones || []) {
    const outer = new T.Group(), inner = new T.Group();
    const piv = b.pivot || [0, 0, 0], rot = (b.rotation || [0, 0, 0]).map(a => a * Math.PI / 180);
    outer.position.set(...piv); outer.rotation.order = "ZYX"; outer.rotation.set(...rot);
    inner.position.set(-piv[0], -piv[1], -piv[2]);
    outer.add(inner);
    const see = b.alpha < 1 || b.glass || b.emissive;
    // an emissive bone is unlit (its texture at full strength, as in the thumbnails); a glow layer adds to the lit colour
    const opts = { map: tex, transparent: see, alphaTest: see ? 1e-3 : .1, side: 2, opacity: b.alpha == null ? 1 : b.alpha, depthWrite: !see };
    const mat = new T.Mat({ ...opts, color: b.emissive ? "#000000" : b.tint && tint ? tint : "#ffffff",
      emissive: b.emissive || glow ? "#ffffff" : "#000000", emissiveMap: b.emissive ? full : glow, emissiveIntensity: b.emissive || glow ? 1 : 0 });
    // what the bloom pass sees of this bone: its glowing texels only (see glowPass)
    const glowMat = b.emissive || glow ? new T.Mat({ ...opts, color: "#000000", emissive: "#ffffff", emissiveMap: b.emissive ? full : glow }) : null;
    if (b.tint && tint && b.emissive) glowMat.emissive.set(tint), mat.emissive.set(tint);
    for (const c of b.cubes || []) { const m = cuboid(T, c, tw, th, mat); if (glowMat) m.userData.glow = glowMat; inner.add(m); }
    const parent = b.parent && byName[b.parent];
    if (!parent && b.anim && b.anim.type === "pet") {
      // like the client: the pet turns about the player's vertical axis with the head, so it stays beside it
      const orbit = new T.Group(), head = viewer.playerObject.skin.head;
      adapter(b.attach).add(orbit); orbit.add(outer);
      anims.push(() => { orbit.rotation.y = -head.rotation.y; });
    } else (parent ? parent : adapter(b.attach)).add(outer);
    if (b.name) byName[b.name] = inner;
    const a = b.anim || {}, base = rot.slice();
    const R = d => d * Math.PI / 180, TAU = Math.PI * 2, ph = +a.phase || 0;
    const smooth = x => { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); };
    const wave = (c, flap) => { const f = c - Math.floor(c); if (!flap) return Math.sin(f * TAU); return f < .38 ? 1 - 2 * smooth(f / .38) : -1 + 2 * smooth((f - .38) / .62); };
    if (a.type === "cape") {
      // Hangs from the shoulders like the vanilla cape: turned half round about y, then tipped back about x (x first in
      // the order, as Minecraft does it) so the hem swings out behind the body, never into it. A spring (item.json
      // "physics") lifts it when the preview walks or runs and lets it settle with a soft overshoot when still.
      outer.rotation.order = "XYZ";
      outer.rotation.set(R(9), Math.PI, 0);
      const k = 60 + 200 * phys.stiffness, c = 2 * Math.sqrt(k) * .55;
      let x = 0, v = 0, last = 0;
      anims.push(t => {
        const dt = Math.min(.1, Math.max(0, t - last)); last = t;
        const target = motion.moving * 22 * phys.lift + (motion.moving ? Math.sin(t * 11) * motion.moving * 2.5 * (1.2 - phys.stiffness) : 0);
        for (let left = dt; left > 1e-5;) { const h = Math.min(left, 1 / 120); left -= h; v += (k * (target - x) - c * v) * h; x += v * h; }
        outer.rotation.x = R(9 + x + 1.5 * Math.sin(t * 1.6) * phys.sway);
      });
    } else if (a.type === "wing") {
      let clock = 0, rate = 1, last = 0;
      anims.push(t => {
        const dt = Math.min(.1, Math.max(0, t - last)); last = t;
        rate += ((motion.moving > 1 ? 1.8 : 1) - rate) * (1 - Math.exp(-dt * 5)); clock += dt * rate;
        const w = wave(clock * (a.speed || 1) + ph, a.ease === "flap");
        outer.rotation.y = base[1] + R((a.side || 1) * ((a.base || 0) + (a.amp || 0) * (motion.moving > 1 ? 1.3 : 1) * w));
        outer.rotation.x = base[0] + R((a.lift || 0) * Math.max(0, w));
      });
    } else if (a.type === "pet") anims.push(t => { // bobs up from its seat, hops now and then, sways a little
      let dy = -(.5 + .5 * Math.sin((t * (a.speed || 1) * .5 + ph) * TAU)) * (a.amp || .5) * 1.4;
      const cyc = (t + ph * 7) % 7;
      if (cyc < .5) dy -= Math.sin(cyc / .5 * Math.PI) * 2.5;
      outer.position.y = piv[1] + dy;
      outer.rotation.y = base[1] + R(Math.sin(t * .37 * TAU) * 5);
      outer.rotation.z = base[2] + R(Math.sin(t * .61 * TAU) * 1.5 + (cyc > 3.5 && cyc < 4.6 ? Math.sin((cyc - 3.5) / 1.1 * Math.PI) * 3 : 0));
    });
    else if (a.type === "pet_head") anims.push(t => { // glances about, tilts its head now and then
      const s = Math.sin((t * .19 + ph) * TAU), cyc = (t + 2.3 + ph * 9) % 9;
      outer.rotation.y = base[1] + R(Math.sign(s) * Math.pow(Math.abs(s), .35) * (a.amp || 14));
      outer.rotation.x = base[0] + R(Math.sin(t * .43 * TAU) * 5);
      outer.rotation.z = base[2] + R(cyc < 1.2 ? Math.sin(cyc / 1.2 * Math.PI) * 7 : 0);
    });
    else if (a.type === "blink") anims.push(t => { // eyelids, flat (open) but for a quick close, sometimes twice
      const blink = f => f < 0 || f > .07 ? 0 : Math.sin(f / .07 * Math.PI);
      const f = t * (a.speed || 1) + ph, k = Math.floor(f);
      let c = blink(f - k);
      if (k % 3 === 1) c = Math.max(c, blink(f - k - .1));
      outer.scale.y = Math.max(.02, c);
    });
    else if (a.type === "bob") anims.push(t => { outer.position.y = piv[1] + Math.sin((t * a.speed + ph) * TAU) * (a.amp || .5); });
    else if (a.type === "sway") anims.push(t => { outer.rotation.z = base[2] + R(a.amp * Math.sin((t * a.speed + ph) * TAU)); });
    else if (a.type === "spin") anims.push(t => { outer.rotation.y = base[1] + R(a.speed * t + ph * 360); });
  }
  return { made, anims };
}

// Particle effects (item.json "fx": aura, trail, burst, spiral), played as the client spawns them: each particle lives
// "life" seconds, rises or falls with "gravity", shrinks to "sizeEnd" and fades in and out ("fade"). Glowing kinds
// (soft, flame, enchant, spark) are camera-facing additive glow sprites, coloured over their life by "colors"; "dust"
// is small solid flakes in the palette's colours. Counts are steady-state (rate x life) with a floor, so a preview that
// stands still shows as much as a player who keeps moving. Units: px, the player's space (feet at y -16, front +z).
let softSprite = null;
function softGlow() {
  if (softSprite) return softSprite;
  const c = document.createElement("canvas"); c.width = c.height = 64;
  const g = c.getContext("2d"), r = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  r.addColorStop(0, "rgba(255,255,255,1)"); r.addColorStop(.22, "rgba(255,255,255,.7)"); r.addColorStop(.55, "rgba(255,255,255,.16)"); r.addColorStop(1, "rgba(255,255,255,0)");
  g.fillStyle = r; g.fillRect(0, 0, 64, 64);
  return (softSprite = c);
}
const hexRgb = h => { const m = /^#?([0-9a-f]{6})$/i.exec(String(h)); const n = m ? parseInt(m[1], 16) : 0xffffff; return [n >> 16 & 255, n >> 8 & 255, n & 255]; };
const hash = (a, b) => { const x = Math.sin(a * 127.1 + b * 311.7) * 43758.5453; return x - Math.floor(x); };
function buildEffect(viewer, fx) {
  const T = threeOf(viewer);
  const colors = Array.isArray(fx.colors) && fx.colors.length ? fx.colors : ["#ff3b4c"];
  const type = String(fx.type || "aura"), kind = String(fx.particle || "soft"), glowy = kind !== "dust";
  const num = (v, d) => Number.isFinite(+v) && v !== null && v !== undefined ? +v : d;
  const life = Math.max(.3, num(fx.life, type === "burst" ? 1.2 : 1.3)), size = num(fx.size, .8), sizeEnd = num(fx.sizeEnd, glowy ? .4 : .7);
  const grav = num(fx.gravity, type === "burst" ? .4 : 0), fade = Math.max(.02, Math.min(.5, num(fx.fade, type === "burst" ? .1 : .25)));
  const radius = num(fx.radius, type === "spiral" ? .55 : .62) * 16, height = num(fx.height, 2.2) * 16, from = num(fx.from, 0);
  const n = type === "burst" || type === "spiral" ? Math.round(Math.min(90, Math.max(36, num(fx.count, 48))))
    : Math.round(Math.min(72, Math.max(28, num(fx.rate, 8) * life * 3)));
  const period = type === "burst" ? life + .3 : life; // a burst goes off, clears, and goes off again
  const group = new T.Group(), parts = [], rgb = colors.map(hexRgb);
  group.userData.fx = true;
  const geo = new T.Box(1, 1, glowy ? .001 : 1);
  let glowTex = null;
  if (glowy) { glowTex = pixelated(new T.Tex(softGlow())); glowTex.magFilter = glowTex.minFilter = 1006; glowTex.needsUpdate = true; } // linear: a smooth glow
  for (let i = 0; i < n; i++) {
    const col = colors[i % colors.length];
    const mat = glowy
      ? new T.Mat({ map: glowTex, color: "#000000", emissive: "#ffffff", emissiveMap: glowTex, emissiveIntensity: 1.25, transparent: true, depthWrite: false, blending: 2 })
      : new T.Mat({ color: fx.spark ? "#000000" : col, emissive: col, emissiveIntensity: fx.spark ? 1 : .35, transparent: true, depthWrite: false });
    const m = new T.Mesh(geo, mat);
    m.userData.glow = mat; // particles glow in the bloom pass too
    group.add(m); parts.push({ m, seed: i / n, col: rgb[i % rgb.length] });
  }
  viewer.playerObject.add(group);
  const s0 = glowy ? size * 3.8 : size * 1.6, TAU = Math.PI * 2;
  const place = (p, i, t) => {
    // this particle's own clock: spread over the period, with fresh randoms every time it respawns
    const off = type === "burst" ? hash(i, 9) * .12 : p.seed * period;
    const ct = t + off, cyc = Math.floor(ct / period), age = ct - cyc * period, k = age / life;
    const r1 = hash(i, cyc), r2 = hash(i + 31, cyc), r3 = hash(i + 77, cyc);
    if (k >= 1) { p.m.visible = false; return; }
    p.m.visible = true;
    let x = 0, y = 0, z = 0;
    if (type === "trail") { // left behind at the feet as the player walks on (+z), drifting with gravity
      x = (r1 - .5) * 7; y = -15.2 + r2 * 2.5 - grav * 26 * age + Math.sin(age * 5 + i) * .6; z = 1 - age * 16 - r3 * 3;
    } else if (type === "burst") { // out from the body's centre, slowing, then falling with gravity
      const th = r1 * TAU, ph = Math.acos(2 * r2 - 1), sp = 20 * (.55 + .7 * r3), d = 4 + sp * life * (1 - Math.pow(1 - k, 2.2)) / 2.2 * 2; // from the body's surface out
      x = Math.sin(ph) * Math.cos(th) * d; y = 2 + Math.cos(ph) * d * .8 - grav * 30 * age * age; z = Math.sin(ph) * Math.sin(th) * d;
    } else if (type === "spiral" && fx.shape === "orbit") { // a ring circling the body at one height
      const th = p.seed * TAU + t * 2.2; x = Math.cos(th) * radius * (1 + (r1 - .5) * .2); z = Math.sin(th) * radius;
      y = -16 + (from || .5) * 32 + Math.sin(t * 3 + p.seed * 9) * 1.5 + (r2 - .5) * 3;
    } else if (type === "spiral") { // two arms winding up around the player
      const u = k, th = u * TAU * 1.6 + (i % 2) * Math.PI + t * .6;
      x = Math.cos(th) * radius * (1 - u * .25); z = Math.sin(th) * radius * (1 - u * .25); y = -16 + u * height + (r1 - .5) * 2;
    } else { // aura: rising around the player (a flat ring at one height for "ring")
      const th = r1 * TAU + age * .9, rr = (fx.shape === "ring" ? radius : radius * (.75 + .5 * r2));
      x = Math.cos(th) * rr; z = Math.sin(th) * rr;
      y = (fx.shape === "ring" ? -16 + from * 32 : -16 + r3 * 10) + (4 - grav * 26) * age;
    }
    p.m.position.set(x, y, z);
    // fade in and out, shrink, and (glow) colour over life: additive, so a darker colour is a fainter glow
    let v = Math.max(0, Math.min(1, k / fade, (1 - k) / fade)); v = v * v * (3 - 2 * v);
    p.m.scale.setScalar(s0 * (1 + (sizeEnd - 1) * k) * (.75 + .5 * r3));
    if (glowy) {
      const pos = k * (rgb.length - 1), a = Math.min(rgb.length - 1, Math.floor(pos)), b = Math.min(rgb.length - 1, a + 1), f = pos - a;
      p.m.material.emissive.setRGB(...rgb[a].map((c, ci) => (c + (rgb[b][ci] - c) * f) / 255 * v));
    } else { p.m.material.opacity = v; p.m.rotation.set(t * 2 + r1 * 6, t * 1.4 + r2 * 6, 0); }
  };
  const q = viewer.camera.quaternion.clone();
  const update = t => {
    if (glowy) { group.updateWorldMatrix(true, false); group.getWorldQuaternion(q).invert().multiply(viewer.camera.quaternion); }
    parts.forEach((p, i) => { place(p, i, t); if (glowy) p.m.quaternion.copy(q); });
  };
  update(1.3);
  // where the effect can reach (player space), so the preview frames all of it
  const R = radius + 4, top = Math.max(-16 + height, -16 + 10 + (4 - grav * 26) * life);
  const bounds = type === "trail" ? [[-6, -17, -2 - 15 * life], [6, Math.max(-9, -13 - grav * 26 * life), 3]]
    : type === "burst" ? [[-20, -14, -20], [20, 20, 20]]
    : type === "spiral" && fx.shape === "orbit" ? [[-R, -16 + (from || .5) * 32 - 4, -R], [R, -16 + (from || .5) * 32 + 4, R]]
    : type === "spiral" ? [[-R, -17, -R], [R, -16 + height + 2, R]]
    : [[-R, -17, -R], [R, fx.shape === "ring" ? -16 + from * 32 + 4 + 4 * life : Math.min(24, top), R]];
  return { group, update, bounds, dispose() { geo.dispose(); if (glowTex) glowTex.dispose(); } };
}

// Emotes: keyframes [time, pitch, yaw, roll] (degrees, Minecraft's axes) per body part, played on a loop.
function emoteAnimation(sv, emote) {
  const len = emote.length || 1;
  return new sv.FunctionAnimation(player => emotePose(player, emote, performance.now() / 1000 % len));
}
function emotePose(player, emote, t) { // t: seconds into the emote, 0 to its length
  const parts = emote.parts || {};
  const R = d => d * Math.PI / 180;
  {
    for (const [part, keys] of Object.entries(parts)) {
      const o = player.skin[part];
      if (!o || !keys.length) continue;
      let i = keys.findIndex(k => k[0] > t);
      if (i <= 0) i = i === 0 ? 1 : keys.length - 1;
      const a = keys[i - 1] || keys[0], b = keys[i] || a, f = b[0] > a[0] ? Math.min(1, Math.max(0, (t - a[0]) / (b[0] - a[0]))) : 0;
      const lerp = n => a[n] + (b[n] - a[n]) * f;
      o.rotation.set(R(lerp(1)), -R(lerp(2)), -R(lerp(3))); // y and z turn the other way in skinview3d's space
    }
    // Whole-body keyframes: "root" turns the model about the hips (12 px above the feet), "offset" moves it (px).
    const at = keys => {
      if (!keys || !keys.length) return [0, 0, 0];
      let i = keys.findIndex(k => k[0] > t);
      if (i <= 0) i = i === 0 ? 1 : keys.length - 1;
      const a = keys[i - 1], b = keys[i] || a, f = b[0] > a[0] ? Math.min(1, Math.max(0, (t - a[0]) / (b[0] - a[0]))) : 0;
      return [1, 2, 3].map(n => a[n] + (b[n] - a[n]) * f);
    };
    if (parts.root || parts.offset) {
      const r = at(parts.root), off = at(parts.offset);
      player.rotation.set(R(r[0]), -R(r[1]), -R(r[2]));
      const pivot = player.position.clone().set(0, HIP_Y, 0);
      const turned = pivot.clone().applyEuler(player.rotation);
      player.position.set(pivot.x - turned.x + off[0], pivot.y - turned.y - off[1], pivot.z - turned.z + off[2]);
    }
  }
}
// skinview3d's player: feet at y = -16 in the player's own space, so the hips (top of the legs) are 12 px above that.
const HIP_Y = -4;
function resetBody(viewer) { const p = viewer.playerObject; if (p) { p.rotation.set(0, 0, 0); p.position.set(0, 0, 0); } }

const ANIMS = { wave: "WaveAnimation", crouch: "CrouchAnimation", run: "RunningAnimation", fly: "FlyingAnimation", walk: "WalkingAnimation", idle: "IdleAnimation" };

// Put one item on a viewer (removing the previous one). Returns a per-frame animator, or null.
async function dress(viewer, it) {
  // free the previous item's GPU memory: geometry, materials (with the bloom pass's copies) and every texture it made
  const skinMap = viewer._evade && viewer._evade.length ? threeOf(viewer).map : null;
  for (const o of viewer._evade || []) {
    o.parent && o.parent.remove(o);
    o.traverse(x => {
      if (x.geometry) x.geometry.dispose();
      for (const m of [x.material, x.userData.glow]) {
        if (!m) continue;
        if (m.map && m.map !== skinMap) m.map.dispose();
        if (m.userData.black) m.userData.black.dispose();
        m.dispose();
      }
    });
  }
  for (const t of viewer._texs || []) t.dispose();
  viewer._evade = []; viewer._texs = []; viewer._tick = null;
  viewer.loadCape(null);
  await detailsOf(it);
  viewer._item = it;
  const ticks = [];
  const model = it.model ? await getJson(it.model) : null;
  if (model && model.bones) { const m = await buildModel(viewer, it, model); viewer._evade.push(...m.made); ticks.push(...m.anims); }
  viewer._fxBounds = null;
  if (it.fx) { const fx = buildEffect(viewer, it.fx); viewer._evade.push(fx.group); ticks.push(fx.update); viewer._fxBounds = fx.bounds; }
  viewer._tick = ticks.length ? t => ticks.forEach(f => f(t)) : null;
  if (viewer._tick) viewer._tick(1.3);
}

// How each category is shown: turned to the side that shows the item (back or front, a little round so it reads as 3D),
// and framed on the item together with the body parts that give it scale, with room for its motion. The camera is
// fitted to the actual shapes (and to where the item's particles go, and an emote's whole move), so every item fills the
// space above the pose buttons, on a phone or a desktop, whatever its size.
const ALL_PARTS = ["head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg"];
const VIEWS = {
  capes: { turn: .34, parts: ["head", "body"], margin: 1.2 }, wings: { turn: .4, parts: ["head", "body"], margin: 1.28 },
  back: { turn: .6, parts: ["head", "body"], margin: 1.16 }, trails: { turn: 1.25, parts: ALL_PARTS, margin: 1.06 },
  hats: { turn: .5, parts: ["head"], margin: 1.26 }, masks: { turn: .5, parts: ["head"], margin: 1.22 },
  auras: { turn: .5, tilt: .62, parts: ["body", "rightLeg", "leftLeg"], margin: 1.08 }, pets: { turn: -.7, parts: [], margin: 1.75 },
  emotes: { turn: .45, parts: ALL_PARTS, margin: 1.06 }, killfx: { turn: .5, parts: ALL_PARTS, margin: 1.02 },
  winfx: { turn: .5, parts: ALL_PARTS, margin: 1.04 }, skins: { turn: .75, parts: ["head", "body", "rightArm"], margin: 1.12 },
};
function pose(viewer, cat, back) {
  const wrap = viewer.playerWrapper, cam = viewer.camera, cfg = VIEWS[cat] || { turn: .5, parts: ALL_PARTS, margin: 1.1 };
  wrap.rotation.set(cfg.tilt || 0, back ? Math.PI - cfg.turn : cfg.turn, 0);
  wrap.position.set(0, 0, 0);
  wrap.updateMatrixWorld(true);
  const V = cam.position.constructor, v = new V(), pts = [];
  const lo = new V(Infinity, Infinity, Infinity), hi = new V(-Infinity, -Infinity, -Infinity);
  const add = () => { lo.min(v); hi.max(v); pts.push(v.x, v.y, v.z); };
  const items = new Set(viewer._evade || []);
  const walk = (o, skipItems) => {
    if (!o || !o.visible || o.userData.fx || (skipItems && items.has(o))) return;
    const pos = o.isMesh && o.geometry && o.geometry.attributes.position;
    if (pos) for (let i = 0; i < pos.count; i++) {
      v.fromBufferAttribute(pos, i);
      if (v.x === 0 && v.y === 0 && v.z === 0) continue; // a face a per-face-UV cube leaves out (collapsed)
      v.applyMatrix4(o.matrixWorld); add();
    }
    o.children.forEach(c => walk(c, skipItems));
  };
  const player = viewer.playerObject;
  const hasModel = [...items].some(o => !o.userData.fx);
  const collect = () => {
    player.updateMatrixWorld(true);
    for (const o of items) walk(o, false);
    for (const part of cfg.parts) walk(player.skin[part], true);
    // where the particles go, when they are the item (an aura's own rune circle is framed instead of its sparks, an
    // emote's move instead of its sparkles)
    const b = viewer._fxBounds && cat !== "emotes" && (cat !== "auras" || !hasModel) ? viewer._fxBounds : null;
    if (b) for (let i = 0; i < 8; i++) { v.set(b[i & 1][0], b[i >> 1 & 1][1], b[i >> 2][2]).applyMatrix4(player.matrixWorld); add(); }
  };
  const emote = viewer._item && viewer._item.emote;
  if (emote && emote.parts) { // the whole move: sampled over its length, then back to standing
    for (let k = 0; k < 24; k++) { emotePose(player, emote, (emote.length || 1) * k / 24); collect(); }
    try { player.resetJoints(); } catch (e) { /* older skinview3d */ }
    resetBody(viewer); player.updateMatrixWorld(true);
  } else collect();
  if (!(hi.x > lo.x)) { viewer.zoom = .72 * (viewer._fit || 1); return; }
  const c = lo.clone().add(hi).multiplyScalar(.5);
  wrap.position.set(-c.x, -c.y, -c.z);
  // the nearest distance at which every point is inside the frame, in perspective (near parts look bigger)
  const tan = Math.tan(cam.fov * Math.PI / 360), fit = viewer._fit || 1, m = cfg.margin;
  const ky = m / (tan * fit), kx = m / (tan * cam.aspect);
  let d = 10.5;
  for (let i = 0; i < pts.length; i += 3) {
    const z = pts[i + 2] - c.z;
    d = Math.max(d, z + Math.abs(pts[i + 1] - c.y) * ky, z + Math.abs(pts[i] - c.x) * kx);
  }
  viewer.zoom = 16.5 / tan / (d - 4.5);
}

// ---- the preview's look: the same light and glow as the generator's thumbnails (scripts/build-cosmetics.py
// render_scene), sharp on every screen. skinview3d's own setup is flat light (ambient only) and FXAA, which smears
// texels; here: a warm key light from the upper left and a cool fill from the right (both fixed to the camera, like
// the thumbnails), multisampled edges instead of FXAA (texels stay crisp), and a bloom of only the glowing parts.
const PR_MAX = 3, PX_BUDGET = 5.5e6; // device pixels: the full density of today's phones, capped for huge screens
const pixelRatioFor = (w, h) => Math.max(1, Math.min(window.devicePixelRatio || 1, PR_MAX, Math.sqrt(PX_BUDGET / Math.max(1, w * h))));
const FS_VERT = "varying vec2 vUv; void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }";
const BLUR = { uniforms: { tDiffuse: { value: null }, dir: { value: [0, 0] }, sigma: { value: 3 } }, vertexShader: FS_VERT,
  fragmentShader: `uniform sampler2D tDiffuse; uniform vec2 dir; uniform float sigma; varying vec2 vUv;
  void main() { vec4 s = vec4(0.0); float n = 0.0;
    for (int i = -12; i <= 12; i++) { float x = float(i), w = exp(-0.5 * x * x / (sigma * sigma)); s += texture2D(tDiffuse, vUv + dir * x) * w; n += w; }
    gl_FragColor = s / n; }` };
const COMPOSE = { uniforms: { tDiffuse: { value: null }, tA: { value: null }, tB: { value: null }, k: { value: 0 } }, vertexShader: FS_VERT,
  fragmentShader: `uniform sampler2D tDiffuse, tA, tB; uniform float k; varying vec2 vUv;
  void main() { vec4 c = texture2D(tDiffuse, vUv); vec3 g = (texture2D(tA, vUv).rgb + texture2D(tB, vUv).rgb) * k; gl_FragColor = vec4(c.rgb + g, c.a); }` };
function enhanceViewer(viewer) {
  const T = threeOf(viewer), PI = Math.PI, cam = viewer.camera;
  // light: 0.42 ambient + 0.62 key + 0.22 fill in the thumbnails' units (three's lights here are physical: x PI)
  viewer.globalLight.intensity = .44 * PI;
  viewer.cameraLight.intensity = 0;
  const Light = viewer.cameraLight.constructor; // a point light far away with no falloff: a directional light
  for (const [rgb, k, dir] of [[[1.04, 1, .95], .62, [-.45, .7, .55]], [[.55, .62, .78], .22, [.6, -.1, .4]]]) {
    const l = new Light(0xffffff, k * PI, 0, 0), n = Math.hypot(...dir);
    l.color.setRGB(...rgb); l.position.set(...dir.map(v => v / n * 4000)); cam.add(l);
  }
  // edges: 4x MSAA on the scene (WebGL2), no FXAA
  const comp = viewer.composer, r = viewer.renderer, SP = viewer.fxaaPass.constructor, RT = comp.renderTarget1.constructor;
  if (r.capabilities.isWebGL2) for (const rt of [comp.renderTarget1, comp.renderTarget2]) { rt.samples = 4; rt.dispose(); }
  comp.removePass(viewer.fxaaPass);
  // bloom: the glowing parts alone (everything else black, so it still hides what is behind it), blurred twice (a tight
  // and a wide halo, as in the thumbnails) at half and quarter size, then added to the picture
  const rt = () => new RT(1, 1), A = rt(), B = rt(), C = rt(), D = rt();
  const blur = new SP(BLUR), compose = new SP(COMPOSE);
  compose.uniforms.tA.value = A.texture; compose.uniforms.tB.value = D.texture;
  let H = 1, W = 1;
  const black = m => m.userData.black || (m.userData.black = new T.Mat({ color: "#000000", map: m.map, alphaTest: Math.max(.1, m.alphaTest || 0), side: m.side }));
  const pass = (src, dst, dx, dy, px) => { // a Gaussian of px pixels (of dst) along one axis
    const step = Math.max(1, px / 4);
    blur.uniforms.dir.value = [dx * step / dst.width, dy * step / dst.height]; blur.uniforms.sigma.value = px / step;
    blur.render(r, dst, src);
  };
  const glowPass = {
    enabled: true, needsSwap: false, clear: false, renderToScreen: false,
    setSize(w, h) { W = w; H = h; A.setSize(w >> 1, h >> 1); B.setSize(w >> 1, h >> 1); C.setSize(w >> 2, h >> 2); D.setSize(w >> 2, h >> 2); },
    render(renderer) {
      const swapped = [];
      viewer.scene.traverse(o => {
        if (!o.isMesh || !o.material) return;
        if (o.userData.glow) { swapped.push([o, o.material]); o.material = o.userData.glow; }
        else if (o.material.transparent) { swapped.push([o, o.material, o.visible]); o.visible = false; }
        else { swapped.push([o, o.material]); o.material = black(o.material); }
      });
      const lit = swapped.some(([o]) => o.userData.glow && o.visible);
      compose.uniforms.k.value = lit ? .9 * .55 : 0;
      if (lit) {
        const bg = viewer.scene.background; viewer.scene.background = null;
        renderer.setRenderTarget(A); renderer.setClearColor(0, 0); renderer.clear(); renderer.render(viewer.scene, cam);
        viewer.scene.background = bg;
      }
      for (const [o, m, vis] of swapped) { o.material = m; if (vis !== undefined) o.visible = vis; }
      if (!lit) return;
      const s = H * .8 / 128; // the thumbnails' blur radii (2.2 and 7 px of a 128 px picture), at this picture's size
      pass(A, B, 1, 0, 2.2 * s / 2); pass(B, A, 0, 1, 2.2 * s / 2);
      pass(A, C, 1, 0, 7 * s / 4); pass(C, D, 0, 1, 7 * s / 4);
    },
    dispose() { [A, B, C, D].forEach(t => t.dispose()); },
  };
  comp.addPass(glowPass);
  comp.addPass(compose);
  viewer._post = glowPass;
}

// ---- thumbnails: the catalogue's thumb.png; without one, a hidden viewer renders a picture, one item at a time
let thumbViewer = null, thumbQueue = Promise.resolve();
function thumbFor(item) {
  if (item.image) return Promise.resolve(item.image);
  if (state.thumbs.has(item.id)) return state.thumbs.get(item.id);
  const p = thumbQueue = thumbQueue.then(async () => {
    if (!hasWebGL()) return null;
    const sv = await loadSV();
    if (!thumbViewer) {
      const canvas = document.createElement("canvas");
      thumbViewer = new sv.SkinViewer({ canvas, width: 300, height: 300, pixelRatio: 2, skin: defaultSkin(), model: "default",
        preserveDrawingBuffer: true, renderPaused: true, enableControls: false });
    }
    thumbViewer.loadBackground(stageBackground(RARITY_COLOR[item.rarity] || "#e11d2e"));
    await dress(thumbViewer, item);
    pose(thumbViewer, item.cat, BACK_VIEW.has(item.cat));
    thumbViewer.render();
    return thumbViewer.canvas.toDataURL("image/webp", .9);
  }).catch(() => null);
  state.thumbs.set(item.id, p);
  return p;
}
// Without WebGL the detail view shows the thumbnail picture instead.
const stillFor = item => Promise.resolve(item.image || null);

// ---------------------------------------------------------------- catalogue view
const cats = one("#shopCats"), count = one("#shopCount"), empty = one("#shopEmpty");
function inCat(it, cat) { return !cat || (cat === "partner" ? !!it.partner : it.cat === cat); }
function renderCats() {
  const n = c => state.items.filter(i => inCat(i, c)).length;
  cats.innerHTML = [["", "All"], ...CATS].map(([k, label]) => {
    const c = n(k);
    return `<button class="tab${state.cat === k ? " on" : ""}" role="tab" aria-selected="${state.cat === k}" data-cat="${k}">${esc(label)}${k && !c ? '<span class="soon">Soon</span>' : `<small>${c}</small>`}</button>`;
  }).join("");
}
const owned = () => new Set(state.link && state.link.owned || []);
function card(it) {
  const own = owned().has(it.id), inCart = state.cart.includes(it.id);
  // "Price coming soon" shortens to "Soon" on phones (shop.css), so it never wraps
  const label = soon(it) ? '<span class="pl">Price coming soon</span><span class="ps" aria-hidden="true">Soon</span>' : esc(priceText(it));
  const price = `<span class="item-price-sm${priceClass(it)}"${soon(it) ? ' title="Animated item: its price is set when the shop opens"' : ""}>${label}</span>`;
  const tag = isFree(it) ? '<span class="free-tag">Free for everyone</span>' : own ? '<span class="owned-tag">Owned</span>' : inCart ? '<span class="incart-tag">In cart</span>' : "";
  return `<button class="card item-card" type="button" data-id="${esc(it.id)}" data-r="${it.rarity}" aria-label="${esc(it.name)}, ${esc(RARITY_LABEL[it.rarity])} ${esc(CAT_LABEL[it.cat])}, ${esc(isFree(it) ? "free for everyone" : priceText(it))}">
    <span class="item-thumb">${glyph(it.cat)}${tag}</span>
    <span class="item-body"><span class="rarity" data-r="${it.rarity}">${esc(RARITY_LABEL[it.rarity])}</span><h3>${esc(it.name)}</h3>
    <span class="item-row"><span class="item-cat">${esc(it.partner ? it.partner.name : CAT_LABEL[it.cat])}</span>${price}</span></span></button>`;
}
// Thumbnails are smooth renders (antialiased, 128-256 px a frame). Blown up 2x or more on the screen's own pixels (any
// phone, a retina desktop) they are drawn pixel for pixel (nearest), which keeps the texels sharp instead of blurring
// them; at smaller steps (a 1x desktop) smooth scaling looks better than uneven blocks.
function crisp(img) {
  const box = img.getBoundingClientRect().width;
  if (!box || !img.naturalWidth) return;
  img.classList.toggle("px", box * (window.devicePixelRatio || 1) / img.naturalWidth >= 1.9);
}
let crispT = 0;
addEventListener("resize", () => { clearTimeout(crispT); crispT = setTimeout(() => all(".item-thumb img.ok, #itemStill").forEach(crisp), 150); });
let io = null;
function renderGrid() {
  const q = state.q.trim().toLowerCase();
  let list = state.items.filter(i => inCat(i, state.cat) && (!state.rarity || i.rarity === state.rarity) && (!q || q.split(/\s+/).every(w => i.search.includes(w))));
  const price = i => forSale(i) || isFree(i) ? i.priceCents : Infinity;
  if (state.sort === "price-asc") list.sort((a, b) => price(a) - price(b));
  else if (state.sort === "price-desc") list.sort((a, b) => (forSale(b) || isFree(b) ? b.priceCents : -1) - (forSale(a) || isFree(a) ? a.priceCents : -1));
  else if (state.sort === "name") list.sort((a, b) => a.name.localeCompare(b.name));
  else list.sort((a, b) => a.order - b.order);
  grid.innerHTML = list.map(card).join("");
  count.textContent = state.items.length ? `${list.length} item${list.length === 1 ? "" : "s"}${state.cat ? " in " + CAT_LABEL[state.cat] : ""}` : "";
  // the Capes category says up front that every cape is free
  one("#shopNote").hidden = state.cat !== "capes" || !state.items.some(isFree);
  empty.hidden = list.length > 0;
  if (!list.length) {
    one("#shopEmptyText").textContent = !state.items.length ? "The catalogue couldn't be loaded. Please try again later."
      : state.cat && !state.items.some(i => inCat(i, state.cat)) ? `${CAT_LABEL[state.cat]} are on the way. Check back soon.`
      : "No cosmetics match. Try another category or clear the search.";
  }
  // Thumbnails, rendered as the cards scroll into view.
  if (io) io.disconnect();
  const fill = el => {
    const it = state.byId.get(el.dataset.id);
    const show = src => {
      if (!src) return;
      const t = el.querySelector(".item-thumb"), g = t.querySelector(".glyph"); const img = new Image(); img.decoding = "async";
      img.alt = it.name + " " + (CAT_LABEL[it.cat] || "cosmetic").replace(/s$/, "").toLowerCase() + " preview";
      // an animated item's thumb.png is a strip of square frames, one per texture frame: play it (stepped, at the
      // texture's frametime from item.json). The picture stays hidden until it is laid out, so a strip never flashes
      // squashed, and it fades in without moving anything (the box has its size already).
      img.onload = () => {
        const n = Math.round(img.naturalHeight / img.naturalWidth);
        if (n >= 2 && img.naturalHeight === n * img.naturalWidth) {
          t.classList.add("strip"); img.style.setProperty("--n", n);
          const dur = () => { const an = it.animation || {}, ft = Math.max(.5, +an.frametime || 1); img.style.setProperty("--dur", (n * ft * .05).toFixed(2) + "s"); };
          dur(); detailsOf(it).then(dur);
        }
        crisp(img); img.classList.add("ok");
      };
      img.src = src; g ? g.replaceWith(img) : t.prepend(img);
    };
    (hasWebGL() ? thumbFor(it) : stillFor(it)).then(show);
  };
  if ("IntersectionObserver" in window) {
    io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { io.unobserve(e.target); fill(e.target); } }), { rootMargin: "200px" });
    all(".item-card", grid).forEach(el => io.observe(el));
  } else all(".item-card", grid).forEach(fill);
}
cats.addEventListener("click", e => {
  const b = e.target.closest("[data-cat]");
  if (!b) return;
  state.cat = b.dataset.cat; renderCats(); renderGrid();
});
one("#shopSearch").addEventListener("input", e => { state.q = e.target.value; renderGrid(); });
one("#shopRarity").addEventListener("change", e => { state.rarity = e.target.value; renderGrid(); });
one("#shopSort").addEventListener("change", e => { state.sort = e.target.value; renderGrid(); });
all("[data-cat-jump]").forEach(b => b.addEventListener("click", () => {
  state.cat = b.dataset.catJump; renderCats(); renderGrid();
  one("#shop").scrollIntoView({ behavior: "smooth", block: "start" });
}));

// ---------------------------------------------------------------- item detail
const modal = one("#itemModal"), stage = one("#itemStage"), canvas = one("#itemCanvas"), still = one("#itemStill");
const loading = one("#itemLoading"), controls = one("#itemControls"), hint = one("#itemHint"), addBtn = one("#itemAdd");
let viewer = null, current = null, lastFocus = null, fxRaf = 0, backView = false;
function sizeViewer() {
  if (viewer && stage.clientWidth) {
    const pr = pixelRatioFor(stage.clientWidth, stage.clientHeight);
    if (viewer.pixelRatio !== pr) viewer.pixelRatio = pr;
    viewer.setSize(stage.clientWidth, stage.clientHeight);
  }
  frameStage();
  if (viewer && current && viewer._item === current && !modal.hidden) pose(viewer, current.cat, backView); // refit
}
// The pose buttons and the hint sit over the bottom of the stage: the picture is moved up so the model is framed in the
// space above them (and scaled to fit it, see pose()), and the canvas fades out just above them (shop.css), so nothing
// is ever drawn over the buttons or the hint, on a phone or a desktop.
function stageBand() {
  if (controls.hidden) return 0;
  const s = stage.getBoundingClientRect(), c = controls.getBoundingClientRect();
  return Math.max(0, Math.round(s.bottom - c.top + 10));
}
function frameStage() {
  if (!viewer || !stage.clientWidth) return;
  const band = stageBand(), W = stage.clientWidth, H = stage.clientHeight;
  stage.style.setProperty("--band", band + "px");
  if (band) viewer.camera.setViewOffset(W, H, 0, band / 2, W, H); else viewer.camera.clearViewOffset();
  viewer.camera.updateProjectionMatrix();
  viewer._fit = H > 0 ? Math.max(.5, (H - band) / H) : 1;
}
function fxLoop() {
  cancelAnimationFrame(fxRaf);
  const t0 = performance.now();
  const step = now => { if (viewer && viewer._tick) viewer._tick((now - t0) / 1000 + 1.3); fxRaf = requestAnimationFrame(step); };
  if (viewer && viewer._tick) fxRaf = requestAnimationFrame(step);
}
function setAnim(sv, name) {
  try { viewer.playerObject.resetJoints(); } catch (e) { /* older skinview3d */ }
  resetBody(viewer);
  viewer.animation = name === "emote" && current && current.emote ? emoteAnimation(sv, current.emote) : new sv[ANIMS[name] || ANIMS.idle]();
  if (name === "walk" || name === "run") viewer.animation.speed = .8;
  (viewer._motion || (viewer._motion = { moving: 0 })).moving = name === "run" || name === "fly" ? 2 : name === "walk" ? 1 : 0;
  all("[data-anim]", controls).forEach(b => { b.classList.toggle("on", b.dataset.anim === name); b.setAttribute("aria-pressed", String(b.dataset.anim === name)); });
}
async function openItem(id, push = true) {
  const it = state.byId.get(id);
  if (!it) return;
  current = it;
  lastFocus = document.activeElement;
  modal.hidden = false;
  document.documentElement.classList.add("modal-open");
  modal.style.setProperty("--rc", RARITY_COLOR[it.rarity]);
  stage.style.setProperty("--rc", RARITY_COLOR[it.rarity]);
  const r = one("#itemRarity"); r.textContent = RARITY_LABEL[it.rarity]; r.dataset.r = it.rarity;
  one("#itemCat").textContent = CAT_LABEL[it.cat];
  one("#itemName").textContent = it.name;
  one("#itemDesc").textContent = it.desc;
  detailsOf(it).then(() => { if (current === it) one("#itemDesc").textContent = it.desc; });
  const pp = one("#itemPartner");
  pp.hidden = !it.partner;
  if (it.partner) pp.innerHTML = `<b>Partner item from ${esc(it.partner.name)}.</b> ${esc(it.partnerHow || (forSale(it) ? "Also sold here." : "Earned on the partner server."))}`;
  const pr = one("#itemPrice");
  pr.className = "item-price" + priceClass(it);
  pr.textContent = priceText(it);
  const note = one("#itemNote");
  note.hidden = !isFree(it) && !soon(it);
  note.className = "item-note" + (isFree(it) ? " gift" : " soon");
  note.innerHTML = isFree(it) ? "<b>Free for everyone.</b> Every Evade player can wear it: open the <b>Wardrobe</b> in Evade and pick it. No account link or purchase needed, and it's never sold."
    : soon(it) ? "<b>Animated item.</b> Its price is set when the shop opens. Preview it here any time." : "";
  updateAdd();
  if (push) history.replaceState(null, "", "#item=" + encodeURIComponent(it.id));
  one(".item-close", modal).focus({ preventScroll: true });

  backView = BACK_VIEW.has(it.cat);
  if (!hasWebGL()) return showStill(it);
  canvas.hidden = false; still.hidden = true; loading.hidden = false; loading.textContent = "Loading 3D preview…";
  controls.hidden = false; hint.hidden = false;
  let sv;
  try { sv = await loadSV(); } catch (e) { return showStill(it); }
  if (current !== it || modal.hidden) return;
  if (!viewer) {
    const w = stage.clientWidth || 400, h = stage.clientHeight || 400;
    viewer = new sv.SkinViewer({ canvas, width: w, height: h, pixelRatio: pixelRatioFor(w, h), skin: defaultSkin(), model: "default" });
    try { enhanceViewer(viewer); } catch (e) { /* the plain skinview3d look still works */ }
    viewer.controls.enablePan = false;
    viewer.autoRotateSpeed = .8;
    addEventListener("resize", sizeViewer);
  } else viewer.renderPaused = false;
  sizeViewer();
  viewer.loadBackground(stageBackground(RARITY_COLOR[it.rarity] || "#e11d2e", 512, 512));
  await dress(viewer, it);
  if (current !== it) return;
  viewer.resetCameraPose();
  pose(viewer, it.cat, backView);
  viewer.autoRotate = false;
  // one filled state (the pose); Back/Front is a plain switch and Spin an outlined toggle, so only one looks selected
  all("[data-rotate]", controls).forEach(b => { b.classList.remove("pressed"); b.setAttribute("aria-pressed", "false"); });
  all("[data-view]", controls).forEach(b => { b.textContent = backView ? "Front" : "Back"; });
  one("[data-anim=emote]", controls).hidden = !it.emote;
  setAnim(sv, it.emote ? "emote" : it.cat === "capes" || it.cat === "trails" ? "walk" : "idle");
  fxLoop();
  loading.hidden = true;
}
async function showStill(it) {
  canvas.hidden = true; controls.hidden = true; hint.hidden = true;
  const src = await stillFor(it);
  await detailsOf(it);
  if (current !== it) return;
  if (src) {
    still.classList.remove("strip", "px");
    still.onload = () => {
      const n = Math.round(still.naturalHeight / still.naturalWidth);
      if (n >= 2 && still.naturalHeight === n * still.naturalWidth) {
        const an = it.animation || {}, ft = Math.max(.5, +an.frametime || 1);
        still.classList.add("strip"); still.style.setProperty("--n", n); still.style.setProperty("--dur", (n * ft * .05).toFixed(2) + "s");
      }
      crisp(still);
    };
    still.src = src; still.alt = it.name; still.hidden = false; loading.hidden = true;
  }
  else { still.hidden = true; loading.hidden = false; loading.innerHTML = glyph(it.cat) + "<br>3D preview needs WebGL"; }
}
function closeItem() {
  if (modal.hidden) return;
  modal.hidden = true; current = null;
  document.documentElement.classList.remove("modal-open");
  cancelAnimationFrame(fxRaf);
  if (viewer) viewer.renderPaused = true;
  if (/^#item=/.test(location.hash)) history.replaceState(null, "", location.pathname + location.search);
  if (lastFocus && lastFocus.focus) lastFocus.focus({ preventScroll: true });
}
grid.addEventListener("click", e => { const c = e.target.closest(".item-card"); if (c) openItem(c.dataset.id); });
// the 3D library (about 480 kB) starts loading as soon as a card is pointed at, so the preview opens sooner
let svWarm = false;
grid.addEventListener("pointerover", e => { if (!svWarm && e.target.closest(".item-card") && hasWebGL()) { svWarm = true; loadSV().catch(() => {}); } }, { passive: true });
all("[data-close]", modal).forEach(b => b.addEventListener("click", closeItem));
addEventListener("keydown", e => {
  if (modal.hidden) return;
  if (e.key === "Escape") closeItem();
  if (e.key === "Tab") { // keep focus inside the dialog
    const f = all("button:not([hidden]):not(:disabled), a[href]", modal).filter(x => x.offsetParent);
    if (!f.length) return;
    if (e.shiftKey && document.activeElement === f[0]) { e.preventDefault(); f[f.length - 1].focus(); }
    else if (!e.shiftKey && document.activeElement === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
  }
});
controls.addEventListener("click", async e => {
  const b = e.target.closest("button");
  if (!b || !viewer) return;
  const sv = await loadSV();
  if (b.dataset.anim) setAnim(sv, b.dataset.anim);
  else if (b.hasAttribute("data-view")) {
    backView = !backView; viewer.autoRotate = false;
    all("[data-rotate]", controls).forEach(x => { x.classList.remove("pressed"); x.setAttribute("aria-pressed", "false"); });
    viewer.resetCameraPose(); pose(viewer, current.cat, backView);
    b.textContent = backView ? "Front" : "Back";
  } else if (b.hasAttribute("data-rotate")) { viewer.autoRotate = !viewer.autoRotate; b.classList.toggle("pressed", viewer.autoRotate); b.setAttribute("aria-pressed", String(viewer.autoRotate)); }
});
function updateAdd() {
  const it = current;
  if (!it) return;
  const own = owned().has(it.id), inCart = state.cart.includes(it.id);
  addBtn.classList.toggle("in", inCart || own);
  addBtn.classList.toggle("gift", isFree(it));
  addBtn.disabled = !forSale(it) || own;
  // free items (capes) are never sold: no cart, just where to get them; the price line already says "Price coming soon"
  // in gold for unpriced specials, so their (disabled) button just says "Coming soon"
  addBtn.textContent = isFree(it) ? "Equip in game" : own ? "Owned" : !forSale(it) ? (it.partner ? "Earn on the server" : soon(it) ? "Coming soon" : "Not for sale") : inCart ? "In cart ✓ Remove" : "Add to cart";
}
addBtn.addEventListener("click", () => {
  if (!current || !forSale(current)) return;
  toggleCart(current.id);
  updateAdd();
});

// ---------------------------------------------------------------- cart
const cartList = one("#cartList"), checkoutBtn = one("#checkoutBtn"), checkoutMsg = one("#checkoutMsg");
const side = one("#shopSide"), fab = one("#cartFab");
function toggleCart(id) {
  if (!forSale(state.byId.get(id))) return; // free items (capes) and unpriced ones never enter the cart
  const i = state.cart.indexOf(id);
  if (i >= 0) state.cart.splice(i, 1); else if (state.cart.length < 20) state.cart.push(id);
  store.set("cart", state.cart);
  renderCart(); renderGrid();
}
function renderCart() {
  state.cart = state.cart.filter(id => forSale(state.byId.get(id)) && !owned().has(id));
  const items = state.cart.map(id => state.byId.get(id));
  cartList.innerHTML = items.map(it => `<li data-r="${it.rarity}"><span class="ci-thumb" data-thumb="${esc(it.id)}">${glyph(it.cat)}</span>
    <span><b>${esc(it.name)}</b><small>${esc(CAT_LABEL[it.cat])}</small></span><span class="ci-price">${esc(fmt(it.priceCents, it.currency))}</span>
    <button class="ci-remove" type="button" data-remove="${esc(it.id)}" aria-label="Remove ${esc(it.name)}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg></button></li>`).join("");
  all("[data-thumb]", cartList).forEach(el => {
    const it = state.byId.get(el.dataset.thumb);
    (hasWebGL() ? (state.thumbs.get(it.id) || (it.image ? Promise.resolve(it.image) : Promise.resolve(null))) : stillFor(it))
      .then(src => { if (src) el.innerHTML = `<img src="${esc(src)}" alt="">`; });
  });
  const n = items.length, total = items.reduce((s, it) => s + it.priceCents, 0);
  one("#cartCount").textContent = n + (n === 1 ? " item" : " items");
  one("#cartFabCount").textContent = n;
  one("#cartEmpty").hidden = n > 0;
  one("#cartTotal").textContent = n ? fmt(total) : "—";
  updateCheckout();
}
cartList.addEventListener("click", e => { const b = e.target.closest("[data-remove]"); if (b) { toggleCart(b.dataset.remove); updateAdd(); } });
function updateCheckout() {
  const open = state.status.open;
  checkoutBtn.disabled = !open || !state.cart.length || busy;
  checkoutBtn.textContent = !open ? "Checkout opens soon" : busy ? "Working…" : !state.link ? "Link your account to check out"
    : state.status.mode === "test" ? "Check out (test mode)" : "Check out";
  if (open && !state.link && state.cart.length) checkoutBtn.disabled = false; // clicking explains what's missing
}
const setOpen = open => {
  side.classList.toggle("open", open);
  fab.setAttribute("aria-expanded", open);
};
fab.addEventListener("click", () => setOpen(!side.classList.contains("open")));
document.addEventListener("click", e => {
  if (side.classList.contains("open") && !side.contains(e.target) && !fab.contains(e.target) && !modal.contains(e.target)) setOpen(false);
});
addEventListener("keydown", e => { if (e.key === "Escape" && modal.hidden) setOpen(false); });

// ---------------------------------------------------------------- account link
const linkForm = one("#linkForm"), linkInput = one("#linkCode"), linkMsg = one("#linkMsg");
const msg = (el, text, kind) => { el.textContent = text || ""; el.className = "shop-msg" + (kind ? " " + kind : ""); };
const CODE = /^[A-Z0-9]{6}$/;
const normCode = s => String(s || "").toUpperCase().replace(/[\s-]/g, "");
linkInput.addEventListener("input", () => {
  const v = normCode(linkInput.value).replace(/[^A-Z0-9]/g, "").slice(0, 6);
  if (v !== linkInput.value) linkInput.value = v;
  linkInput.removeAttribute("aria-invalid");
});
function renderLink() {
  const chip = one("#linkState");
  chip.textContent = state.link ? "Linked" : "Not linked";
  chip.classList.toggle("ok", !!state.link);
  one("#linkBtn").textContent = state.link ? "Change" : "Link";
  if (state.link) linkInput.value = state.link.code;
  const ow = one("#owned"), list = state.link && state.link.owned || [];
  ow.hidden = !list.length;
  one("#ownedList").innerHTML = list.map(id => `<span>${esc((state.byId.get(id) || { name: id }).name)}</span>`).join("");
  updateCheckout();
}
linkForm.addEventListener("submit", async e => {
  e.preventDefault();
  const code = normCode(linkInput.value);
  if (!CODE.test(code)) { linkInput.setAttribute("aria-invalid", "true"); msg(linkMsg, "Codes are 6 letters and numbers. Find yours in Evade: Wardrobe → Link the shop.", "err"); return; }
  msg(linkMsg, "Checking the code…");
  try {
    const r = await call("/api/shop/resolve", { linkCode: code });
    state.link = { code, uuid: r.uuid, owned: r.owned || [] };
    try { sessionStorage.setItem("evade-shop-link", JSON.stringify(state.link)); } catch (x) { /* fine */ }
    msg(linkMsg, "Linked. Items will go to the account that showed this code.", "ok");
  } catch (err) {
    state.link = null;
    if (err.status === 503 || err.status === 404 && !(err.body && err.body.error)) {
      // The shop backend isn't answering: keep the code, it's checked again at checkout.
      msg(linkMsg, state.status.open ? "Couldn't check the code right now. Try again in a moment." : "Linking opens with the shop. Your code is checked when checkout opens.", state.status.open ? "err" : "");
    } else { linkInput.setAttribute("aria-invalid", "true"); msg(linkMsg, err.message, "err"); }
  }
  renderLink(); renderCart(); renderGrid();
});

// ---------------------------------------------------------------- gift + checkout
const giftToggle = one("#giftToggle"), giftField = one("#giftField"), giftTo = one("#giftTo");
giftToggle.addEventListener("change", () => { giftField.hidden = !giftToggle.checked; if (giftToggle.checked) giftTo.focus(); });
giftTo.addEventListener("input", () => { const v = normCode(giftTo.value).replace(/[^A-Z0-9]/g, "").slice(0, 6); if (v !== giftTo.value) giftTo.value = v; });
// Gifts go to the account behind a friend's link code (username look-ups aren't available: Mojang blocks the server).
async function giftUuid() {
  const code = normCode(giftTo.value);
  if (!CODE.test(code)) { giftTo.setAttribute("aria-invalid", "true"); throw new Error("Enter your friend's 6-character code from Wardrobe \u2192 Link the shop."); }
  if (state.link && code === state.link.code) { giftTo.setAttribute("aria-invalid", "true"); throw new Error("That's your own code. Use your friend's code to send a gift."); }
  try { return (await call("/api/shop/resolve", { linkCode: code })).uuid; }
  catch (e) { giftTo.setAttribute("aria-invalid", "true"); throw new Error(e.status === 404 ? "Your friend's code isn't valid or has expired. Ask them for a new one." : e.message); }
}
giftTo.addEventListener("input", () => giftTo.removeAttribute("aria-invalid"));
checkoutBtn.addEventListener("click", async () => {
  if (!state.status.open || busy || !state.cart.length) return;
  if (!state.link) { msg(checkoutMsg, "First link your Minecraft account with the code from Wardrobe → Link the shop.", "err"); linkInput.focus(); return; }
  busy = true; updateCheckout(); msg(checkoutMsg, "");
  try {
    const body = { linkCode: state.link.code, items: state.cart.filter(id => forSale(state.byId.get(id))) };
    if (!body.items.length) throw new Error("Your cart is empty.");
    if (giftToggle.checked) body.giftTo = await giftUuid();
    const r = await call("/api/shop/checkout", body);
    if (state.status.mode === "test" || r.mode === "test") showSession(r.sessionId);
    else if (r.url) location.href = r.url; // a payment provider's page, once the shop is live
  } catch (err) { msg(checkoutMsg, err.message, "err"); }
  busy = false; updateCheckout();
});

// Test-mode order panel (also reached from /shop?testSession=...). Never shown in production.
async function showSession(id) {
  if (!state.status.open || state.status.mode !== "test" || !/^[0-9a-f]{32}$/.test(id || "")) return;
  const panel = one("#cartPanel");
  let s;
  try { s = await call("/api/shop/session?id=" + id); } catch (e) { msg(checkoutMsg, e.message, "err"); return; }
  const names = s.items.map(i => esc((state.byId.get(i) || { name: i }).name)).join(", ");
  const done = s.status !== "open";
  panel.innerHTML = `<div class="shop-panel-head"><h3>Test checkout</h3><span class="shop-chip">${esc(s.status)}</span></div>
    <div class="order-done">${done ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><path d="m7.5 12.5 3 3 6-6"/></svg>' : ""}
      <h4>${s.status === "paid" ? "Payment complete (test)" : s.status === "open" ? "Ready to pay (test)" : "Order " + esc(s.status)}</h4>
      <p>${names}<br>${esc(fmt(s.totalCents, s.currency))} &middot; for <code>${esc(String(s.recipient).slice(0, 8))}&hellip;</code></p></div>
    <div class="order-actions">
      ${s.status === "open" ? '<button class="btn btn-red" type="button" data-sim="complete">Complete test payment</button>' : ""}
      ${s.status === "paid" ? '<button class="btn btn-ghost btn-sm" type="button" data-sim="refund">Simulate refund</button><button class="btn btn-ghost btn-sm" type="button" data-sim="chargeback">Simulate chargeback</button>' : ""}
      <button class="btn btn-ghost btn-sm" type="button" data-sim="back">Back to the shop</button></div>
    <p class="shop-msg" id="checkoutMsg2" role="status"></p>
    <p class="shop-fine">Test mode: no money moves and nothing is charged.${state.simulated ? " Local preview: the backend is simulated in this browser." : ""}</p>`;
  if (side.classList.contains("open") === false && matchMedia("(max-width: 860px)").matches) setOpen(true);
  panel.querySelectorAll("[data-sim]").forEach(b => b.addEventListener("click", async () => {
    const what = b.dataset.sim;
    if (what === "back") { history.replaceState(null, "", location.pathname); location.reload(); return; }
    try {
      await call("/api/shop/test/" + (what === "complete" ? "complete" : "refund"), { sessionId: id, chargeback: what === "chargeback" });
      if (what === "complete") { state.cart = state.cart.filter(x => !s.items.includes(x)); store.set("cart", state.cart); if (state.link && s.recipient === state.link.uuid) { state.link.owned = [...new Set([...(state.link.owned || []), ...s.items])]; renderLink(); } }
      else if (state.link && state.link.owned) { state.link.owned = state.link.owned.filter(x => !s.items.includes(x)); renderLink(); }
      renderGrid();
      showSession(id);
    } catch (e) { msg(one("#checkoutMsg2"), e.message, "err"); }
  }));
  history.replaceState(null, "", "/shop?testSession=" + id);
}

// ---------------------------------------------------------------- status banner
function renderStatus() {
  const b = one("#shopBanner"), s = state.status;
  if (!s.open) {
    b.hidden = false; b.dataset.state = "soon";
  } else if (s.mode === "test") {
    b.hidden = false; b.dataset.state = "test";
    one("#shopBannerTitle").textContent = "Test mode";
    one("#shopBannerText").textContent = (state.simulated ? "Local preview with a simulated backend. " : "") + "Nothing is charged; checkouts are fake and can be completed below.";
    one("#shopBannerCta").hidden = true;
  } else b.hidden = true;
  // The nav's "Soon" pill goes once the shop is open.
  if (s.open && s.mode !== "test") all(".nav-soon").forEach(x => x.remove());
}

// ---------------------------------------------------------------- start
(async () => {
  grid.innerHTML = '<div class="skeleton"></div>'.repeat(6);
  const [cat, status] = await Promise.all([loadCatalog(), loadStatus()]);
  state.status = status;
  state.items = cat.items;
  state.byId = new Map(cat.items.map(i => [i.id, i]));
  try { const l = JSON.parse(sessionStorage.getItem("evade-shop-link") || "null"); if (l && CODE.test(l.code)) state.link = l; } catch (e) { /* none */ }
  const want = (location.hash.match(/^#cat=([a-z]+)/) || [])[1];
  if (want && CAT_LABEL[want]) state.cat = want;
  renderStatus(); renderCats(); renderGrid(); renderLink(); renderCart();
  const item = (location.hash.match(/^#item=(.+)$/) || [])[1];
  if (item) openItem(decodeURIComponent(item), false);
  if (params.get("testSession")) showSession(params.get("testSession"));
})();
})();
