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
// Same rule as the backend: priced above zero. Unpriced special items are "Price coming soon".
const forSale = it => it && Number.isInteger(it.priceCents) && it.priceCents > 0;
const soon = it => it && it.rarity === "special" && !forSale(it) && !it.partner;
const priceText = it => forSale(it) ? fmt(it.priceCents, it.currency) : it.partner ? "Partner reward" : soon(it) ? "Price coming soon" : "Not for sale";

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
  const rarity = String(raw.rarity || "").toLowerCase();
  const partner = raw.partner && typeof raw.partner === "object" ? { id: raw.partner.id, name: String(raw.partner.name || raw.partner.id || "Partner") } : null;
  return {
    id, name: String(raw.name || id), cat, slot, rarity: RARITIES.includes(rarity) ? rarity : partner ? "partner" : "regular", priceCents,
    currency: typeof raw.currency === "string" ? raw.currency.toUpperCase() : null, partner,
    defaultColor: typeof raw.defaultColor === "string" ? raw.defaultColor : null,
    desc: String(raw.description || ""), partnerHow: String(raw.partnerHow || ""),
    model: has("model.json"), texture: has("texture.png"), image: has("thumb.png"), details: has("item.json"), glow: has("emissive.png"),
    fx: raw.fx || null, emote: raw.emote || null, order: Number.isFinite(raw.order) ? raw.order : i,
    search: [raw.name, CAT_LABEL[cat], slot.replace(/_/g, " "), RARITIES.includes(rarity) ? rarity : "", partner && partner.name].join(" ").toLowerCase(),
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
async function stripTexture(T, src, an, aspect) {
  const img = await loadImage(src);
  const frames = an && an.frames > 1 ? an.frames | 0 : 1;
  if (frames <= 1) { const t = pixelated(new T.Tex(img)); t.needsUpdate = true; return { tex: t, tick: null }; }
  const w = img.naturalWidth, fh = Math.round(img.naturalHeight / frames) || Math.round(w * aspect);
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
  if (it.texture) {
    try { const s = await stripTexture(T, it.texture, it.animation, th / tw); tex = s.tex; if (s.tick) anims.push(() => s.tick(performance.now() / 1000)); }
    catch (e) { tex = null; }
  }
  if (it.glow) {
    try { const s = await stripTexture(T, it.glow, it.animation, th / tw); glow = s.tex; if (s.tick) anims.push(() => s.tick(performance.now() / 1000)); }
    catch (e) { glow = null; }
  }
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
    const mat = new T.Mat({ map: tex, transparent: see, alphaTest: see ? 1e-3 : .1, side: 2, opacity: b.alpha == null ? 1 : b.alpha,
      color: b.tint && tint ? tint : "#ffffff", emissive: b.emissive || glow ? "#ffffff" : "#000000", emissiveMap: b.emissive ? tex : glow,
      emissiveIntensity: b.emissive ? .85 : glow ? 1 : 0, depthWrite: !see });
    for (const c of b.cubes || []) inner.add(cuboid(T, c, tw, th, mat));
    const parent = b.parent && byName[b.parent];
    (parent ? parent : adapter(b.attach)).add(outer);
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
    } else if (a.type === "pet") anims.push(t => {
      let dy = Math.sin((t * (a.speed || 1) * .5 + ph) * TAU) * (a.amp || .5) * 1.4;
      const cyc = (t + ph * 7) % 7;
      if (cyc < .5) dy -= Math.sin(cyc / .5 * Math.PI) * 2.5;
      outer.position.y = piv[1] + dy;
      outer.rotation.y = base[1] + R(Math.sin(t * .37 * TAU) * 18);
      outer.rotation.z = base[2] + R(Math.sin(t * .61 * TAU) * 4 + (cyc > 3.5 && cyc < 4.6 ? Math.sin((cyc - 3.5) / 1.1 * Math.PI) * 14 : 0));
    });
    else if (a.type === "pet_head") anims.push(t => { outer.rotation.x = base[0] + R(Math.sin(t * .5 * TAU) * 6); });
    else if (a.type === "bob") anims.push(t => { outer.position.y = piv[1] + Math.sin((t * a.speed + ph) * TAU) * (a.amp || .5); });
    else if (a.type === "sway") anims.push(t => { outer.rotation.z = base[2] + R(a.amp * Math.sin((t * a.speed + ph) * TAU)); });
    else if (a.type === "spin") anims.push(t => { outer.rotation.y = base[1] + R(a.speed * t + ph * 360); });
  }
  return { made, anims };
}

// Particle effects (aura, trail, burst, spiral): small glowing cubes moved every frame; "particle": "soft" draws the
// client's soft additive glow sprites instead (camera-facing, colour over life, fading in and out, shrinking).
let softSprite = null;
function softGlow() {
  if (softSprite) return softSprite;
  const c = document.createElement("canvas"); c.width = c.height = 64;
  const g = c.getContext("2d"), r = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  r.addColorStop(0, "rgba(255,255,255,1)"); r.addColorStop(.25, "rgba(255,255,255,.55)"); r.addColorStop(.6, "rgba(255,255,255,.12)"); r.addColorStop(1, "rgba(255,255,255,0)");
  g.fillStyle = r; g.fillRect(0, 0, 64, 64);
  return (softSprite = c);
}
const hexRgb = h => { const m = /^#?([0-9a-f]{6})$/i.exec(String(h)); const n = m ? parseInt(m[1], 16) : 0xffffff; return [n >> 16 & 255, n >> 8 & 255, n & 255]; };
function buildEffect(viewer, fx) {
  const T = threeOf(viewer);
  const colors = Array.isArray(fx.colors) && fx.colors.length ? fx.colors : ["#ff3b4c"];
  const type = String(fx.type || "aura");
  const n = type === "trail" ? 24 : type === "burst" ? 30 : type === "spiral" ? 36 : 20;
  const size = (fx.size || 1) * .7;
  const soft = fx.particle === "soft";
  const group = new T.Group(), parts = [];
  let glowTex = null;
  if (soft) { glowTex = new T.Tex(softGlow()); glowTex.needsUpdate = true; }
  const rgb = colors.map(hexRgb), sizeEnd = fx.sizeEnd == null ? .4 : +fx.sizeEnd;
  for (let i = 0; i < n; i++) {
    const col = colors[i % colors.length], s = size * (.6 + (i % 3) * .3);
    const m = soft
      ? new T.Mesh(new T.Box(size * 3.2, size * 3.2, .01), new T.Mat({ map: glowTex, color: "#000000", emissive: col, emissiveMap: glowTex, emissiveIntensity: 1.4, transparent: true, opacity: 1, depthWrite: false, blending: 2 }))
      : new T.Mesh(new T.Box(s, s, s), new T.Mat({ color: col, emissive: col, emissiveIntensity: .9, transparent: true, opacity: .9, depthWrite: false }));
    group.add(m); parts.push({ m, seed: i / n });
  }
  if (soft) {
    // colour over life and the fade: additive, so a darker emissive colour is a fainter glow
    const fade = Math.max(.02, Math.min(.5, fx.fade == null ? .25 : +fx.fade));
    parts.forEach(p => { p.life = k => {
      const pos = k * (rgb.length - 1), i = Math.min(rgb.length - 1, Math.floor(pos)), j = Math.min(rgb.length - 1, i + 1), f = pos - i;
      let v = Math.max(0, Math.min(1, k / fade, (1 - k) / fade)); v = v * v * (3 - 2 * v);
      p.m.material.emissive.setRGB(...rgb[i].map((c, ci) => (c + (rgb[j][ci] - c) * f) / 255 * v));
      p.m.scale.setScalar(1 + (sizeEnd - 1) * k);
      p.m.quaternion.copy(viewer.camera.quaternion);
      if (p.m.parent) { const q = p.m.parent.getWorldQuaternion(p.m.quaternion.clone()).invert(); p.m.quaternion.premultiply(q); }
    }; });
  }
  viewer.playerObject.add(group);
  const update = t => {
    for (const p of parts) {
      const k = (t * .15 + p.seed) % 1, a = p.seed * Math.PI * 6 + t * .9;
      if (type === "trail") { p.m.position.set(Math.sin(p.seed * 40) * 3, -15 + k * 4, -3 - k * 16); p.m.material.opacity = 1 - k; }
      else if (type === "burst") { const kk = (t * .5 + p.seed * .3) % 1, r = 2 + kk * 15; p.m.position.set(Math.cos(p.seed * 41) * r, 2 + Math.sin(p.seed * 17) * r * .7, Math.sin(p.seed * 41) * r); p.m.material.opacity = 1 - kk; }
      else if (type === "spiral") { const kk = (t * .25 + p.seed) % 1, ang = kk * Math.PI * 6; p.m.position.set(Math.cos(ang) * 8, -16 + kk * 38, Math.sin(ang) * 8); p.m.material.opacity = Math.sin(kk * Math.PI); }
      else { const r = 9 + Math.sin(p.seed * 23) * 2; p.m.position.set(Math.cos(a) * r, -16 + k * 30, Math.sin(a) * r); p.m.material.opacity = Math.sin(k * Math.PI); }
      if (p.life) p.life(type === "burst" ? (t * .5 + p.seed * .3) % 1 : type === "trail" ? (t * .15 + p.seed) % 1 : type === "spiral" ? (t * .25 + p.seed) % 1 : (t * .15 + p.seed) % 1);
      else p.m.rotation.set(t + p.seed * 5, t * 1.3, 0);
    }
  };
  update(1.3);
  return { group, update };
}

// Emotes: keyframes [time, pitch, yaw, roll] (degrees, Minecraft's axes) per body part, played on a loop.
function emoteAnimation(sv, emote) {
  const len = emote.length || 1, parts = emote.parts || {};
  const R = d => d * Math.PI / 180;
  return new sv.FunctionAnimation(player => {
    const t = (performance.now() / 1000 % len) / len;
    for (const [part, keys] of Object.entries(parts)) {
      const o = player.skin[part];
      if (!o || !keys.length) continue;
      let i = keys.findIndex(k => k[0] > t);
      if (i <= 0) i = i === 0 ? 1 : keys.length - 1;
      const a = keys[i - 1], b = keys[i], f = b[0] > a[0] ? Math.min(1, Math.max(0, (t - a[0]) / (b[0] - a[0]))) : 0;
      const lerp = n => a[n] + (b[n] - a[n]) * f;
      o.rotation.set(R(lerp(1)), -R(lerp(2)), -R(lerp(3))); // y and z turn the other way in skinview3d's space
    }
  });
}

const ANIMS = { wave: "WaveAnimation", crouch: "CrouchAnimation", run: "RunningAnimation", fly: "FlyingAnimation", walk: "WalkingAnimation", idle: "IdleAnimation" };

// Put one item on a viewer (removing the previous one). Returns a per-frame animator, or null.
async function dress(viewer, it) {
  for (const o of viewer._evade || []) {
    o.parent && o.parent.remove(o);
    o.traverse(x => { if (x.geometry) x.geometry.dispose(); if (x.material) { if (x.material.map && x.material.map !== threeOf(viewer).map) x.material.map.dispose(); x.material.dispose(); } });
  }
  viewer._evade = []; viewer._tick = null;
  viewer.loadCape(null);
  await detailsOf(it);
  const ticks = [];
  const model = it.model ? await getJson(it.model) : null;
  if (model && model.bones) { const m = await buildModel(viewer, it, model); viewer._evade.push(...m.made); ticks.push(...m.anims); }
  if (it.fx) { const fx = buildEffect(viewer, it.fx); viewer._evade.push(fx.group); ticks.push(fx.update); }
  viewer._tick = ticks.length ? t => ticks.forEach(f => f(t)) : null;
  if (viewer._tick) viewer._tick(1.3);
}

function pose(viewer, cat, back) {
  const wrap = viewer.playerWrapper;
  wrap.rotation.y = back ? Math.PI - .55 : .5;
  wrap.position.y = HEAD_VIEW.has(cat) ? -13 : 0;
  viewer.zoom = HEAD_VIEW.has(cat) ? 1.05 : cat === "killfx" || cat === "winfx" ? .62 : .74;
  if (cat === "pets") { // close on the head and the left shoulder, where the pet floats, turned so it isn't behind the head
    wrap.rotation.y = back ? Math.PI + .5 : -.45;
    wrap.position.y = -10;
    viewer.zoom = .95;
  }
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
  const price = `<span class="item-price-sm${forSale(it) ? "" : soon(it) ? " soon" : " free"}">${esc(priceText(it))}</span>`;
  return `<button class="card item-card" type="button" data-id="${esc(it.id)}" data-r="${it.rarity}" aria-label="${esc(it.name)}, ${esc(RARITY_LABEL[it.rarity])} ${esc(CAT_LABEL[it.cat])}, ${esc(priceText(it))}">
    <span class="item-thumb">${glyph(it.cat)}${own ? '<span class="owned-tag">Owned</span>' : inCart ? '<span class="incart-tag">In cart</span>' : ""}</span>
    <span class="item-body"><span class="rarity" data-r="${it.rarity}">${esc(RARITY_LABEL[it.rarity])}</span><h3>${esc(it.name)}</h3>
    <span class="item-row"><span class="item-cat">${esc(it.partner ? it.partner.name : CAT_LABEL[it.cat])}</span>${price}</span></span></button>`;
}
let io = null;
function renderGrid() {
  const q = state.q.trim().toLowerCase();
  let list = state.items.filter(i => inCat(i, state.cat) && (!state.rarity || i.rarity === state.rarity) && (!q || q.split(/\s+/).every(w => i.search.includes(w))));
  const price = i => forSale(i) ? i.priceCents : Infinity;
  if (state.sort === "price-asc") list.sort((a, b) => price(a) - price(b));
  else if (state.sort === "price-desc") list.sort((a, b) => (forSale(b) ? b.priceCents : -1) - (forSale(a) ? a.priceCents : -1));
  else if (state.sort === "name") list.sort((a, b) => a.name.localeCompare(b.name));
  else list.sort((a, b) => a.order - b.order);
  grid.innerHTML = list.map(card).join("");
  count.textContent = state.items.length ? `${list.length} item${list.length === 1 ? "" : "s"}${state.cat ? " in " + CAT_LABEL[state.cat] : ""}` : "";
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
      const t = el.querySelector(".item-thumb"), g = t.querySelector(".glyph"); const img = new Image(); img.alt = ""; img.decoding = "async";
      // an animated item's thumb.png may be a strip of square frames: play it (stepped, frametime from item.json)
      img.onload = () => {
        const n = Math.round(img.naturalHeight / img.naturalWidth);
        if (n < 2 || img.naturalHeight !== n * img.naturalWidth) return;
        detailsOf(it).then(() => {
          const an = it.animation || {}, ft = Math.max(.5, +an.frametime || 1);
          t.classList.add("strip"); img.style.setProperty("--n", n); img.style.setProperty("--dur", (n * ft * .05).toFixed(2) + "s");
        });
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
  if (viewer && stage.clientWidth) viewer.setSize(stage.clientWidth, stage.clientHeight);
}
function fxLoop() {
  cancelAnimationFrame(fxRaf);
  const t0 = performance.now();
  const step = now => { if (viewer && viewer._tick) viewer._tick((now - t0) / 1000 + 1.3); fxRaf = requestAnimationFrame(step); };
  if (viewer && viewer._tick) fxRaf = requestAnimationFrame(step);
}
function setAnim(sv, name) {
  try { viewer.playerObject.resetJoints(); } catch (e) { /* older skinview3d */ }
  viewer.animation = name === "emote" && current && current.emote ? emoteAnimation(sv, current.emote) : new sv[ANIMS[name] || ANIMS.idle]();
  if (name === "walk" || name === "run") viewer.animation.speed = .8;
  (viewer._motion || (viewer._motion = { moving: 0 })).moving = name === "run" || name === "fly" ? 2 : name === "walk" ? 1 : 0;
  all("[data-anim]", controls).forEach(b => b.classList.toggle("on", b.dataset.anim === name));
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
  pr.className = "item-price" + (forSale(it) ? "" : soon(it) ? " soon" : " free");
  pr.textContent = priceText(it);
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
    viewer = new sv.SkinViewer({ canvas, width: stage.clientWidth || 400, height: stage.clientHeight || 400, skin: defaultSkin(), model: "default" });
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
  all("[data-rotate]", controls).forEach(b => b.classList.remove("on"));
  all("[data-view]", controls).forEach(b => { b.classList.toggle("on", backView); b.textContent = backView ? "Front" : "Back"; });
  one("[data-anim=emote]", controls).hidden = !it.emote;
  setAnim(sv, it.emote ? "emote" : it.cat === "capes" || it.cat === "trails" ? "walk" : "idle");
  fxLoop();
  loading.hidden = true;
}
async function showStill(it) {
  canvas.hidden = true; controls.hidden = true; hint.hidden = true;
  const src = await stillFor(it);
  if (src) { still.src = src; still.alt = it.name; still.hidden = false; loading.hidden = true; }
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
    all("[data-rotate]", controls).forEach(x => x.classList.remove("on"));
    viewer.resetCameraPose(); pose(viewer, current.cat, backView);
    b.textContent = backView ? "Front" : "Back"; b.classList.toggle("on", backView);
  } else if (b.hasAttribute("data-rotate")) { viewer.autoRotate = !viewer.autoRotate; b.classList.toggle("on", viewer.autoRotate); }
});
function updateAdd() {
  const it = current;
  if (!it) return;
  const own = owned().has(it.id), inCart = state.cart.includes(it.id);
  addBtn.classList.toggle("in", inCart || own);
  addBtn.disabled = !forSale(it) || own;
  // the price line already says "Price coming soon" in gold: the (disabled) button just says "Coming soon"
  addBtn.textContent = own ? "Owned" : !forSale(it) ? (it.partner ? "Earn on the server" : soon(it) ? "Coming soon" : "Not for sale") : inCart ? "In cart ✓ Remove" : "Add to cart";
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
    const body = { linkCode: state.link.code, items: state.cart.slice() };
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
