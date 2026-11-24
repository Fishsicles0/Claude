// Offline tests for cosmetics and the shop (worker/cosmetics.js, routed from worker/index.js).
// Runs on plain Node 22+ like social.test.mjs:   node worker/test/cosmetics.test.mjs
// Not part of the deployed worker.
import { DatabaseSync } from "node:sqlite";
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import assert from "node:assert/strict";

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, "..", "index.js"), "utf8")
  .replace('import { DurableObject } from "cloudflare:workers";',
    "class DurableObject { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }")
  .replace('from "./cosmetics.js"', 'from "./cosmetics.mjs"');
const tmp = mkdtempSync(join(tmpdir(), "evade-cosmetics-"));
writeFileSync(join(tmp, "index.mjs"), src);
writeFileSync(join(tmp, "cosmetics.mjs"), readFileSync(join(here, "..", "cosmetics.js"), "utf8"));
globalThis.WebSocketRequestResponsePair = class { constructor(a, b) { this.a = a; this.b = b; } };
const worker = await import(pathToFileURL(join(tmp, "index.mjs")).href);
const cos = await import(pathToFileURL(join(tmp, "cosmetics.mjs")).href);
const { EvadeSocial } = worker;

let now = 1_800_000_000_000;
Date.now = () => now;

function fakeSql() {
  const db = new DatabaseSync(":memory:");
  return {
    exec(q, ...binds) {
      const parts = q.split(";").map((s) => s.trim()).filter(Boolean);
      let rows = [];
      if (parts.length > 1) db.exec(q);
      else {
        const st = db.prepare(q);
        if (/^\s*(select|with)\b/i.test(q) || /\breturning\b/i.test(q)) rows = st.all(...binds).map((r) => ({ ...r }));
        else st.run(...binds);
      }
      return { toArray: () => rows, one: () => { assert.equal(rows.length, 1, "one() on " + q); return rows[0]; } };
    },
  };
}

function makeSocial() {
  let alarm = null;
  const ctx = {
    storage: { sql: fakeSql(), getAlarm: async () => alarm, setAlarm: async (t) => { alarm = t; } },
    acceptWebSocket() {}, getWebSockets: () => [], setWebSocketAutoResponse() {}, blockConcurrencyWhile: (fn) => fn(),
  };
  return new EvadeSocial(ctx, { ICONS: { idFromName: () => "g", get: () => ({ verify: async (t) => (t.startsWith("tok-") ? t.slice(4) : null) }) } });
}

const CATALOG = {
  version: "test-1",
  items: [
    { id: "red_cape", name: "Red Cape", slot: "cape", rarity: "rare", priceCents: 499, currency: "usd",
      assets: { files: { "cape.png": "a".repeat(64), "../evil": "b".repeat(64), "bad.png": "nothex" } } },
    { id: "halo", name: "Halo", slot: "HAT", rarity: "epic", priceCents: 299, currency: "USD", colorable: true, assets: { files: {} } },
    { id: "free_hat", name: "Free Hat", slot: "HAT", priceCents: 0 },
    { id: "craft_wings", name: "Craft Wings", slot: "WINGS", priceCents: null, partner: { id: "craftnet", name: "CraftNet" } },
    { id: "other_wings", name: "Other Wings", slot: "WINGS", partner: { id: "othernet", name: "OtherNet" } },
    { id: "Bad Id", name: "broken", slot: "HAT" },
    { id: "noslot", name: "broken" },
  ],
};

const U = (c) => c.repeat(32);
const A = U("a"), B = U("b"), C = U("c");
const ADMIN = "x".repeat(40);

/** A worker environment. host: the site's host name (evadeclient.com = production). */
function makeEnv(vars = {}, social = makeSocial()) {
  cos.resetCatalogCache();
  const env = {
    ICONS: { idFromName: () => "g", get: () => ({ verify: async (t) => (t.startsWith("tok-") ? t.slice(4) : null) }) },
    SOCIAL: { idFromName: () => "g", get: () => social },
    ASSETS: {
      fetch: async (r) => new URL(r.url).pathname === "/cosmetics/catalog.json"
        ? new Response(JSON.stringify(CATALOG), { headers: { "content-type": "application/json" } })
        : new Response("site"),
    },
    ...vars,
  };
  env._social = social;
  return env;
}
function caller(env, host = "https://evadeclient.com") {
  return async (path, { method = "GET", body, headers = {}, uuid, ip = "1.2.3.4" } = {}) => {
    const h = { "cf-connecting-ip": ip, ...headers };
    if (uuid) h.authorization = "Bearer tok-" + uuid;
    const r = await worker.default.fetch(new Request(host + path, {
      method, headers: h, body: body === undefined ? undefined : (typeof body === "string" ? body : JSON.stringify(body)) }), env);
    let data = null;
    try { data = await r.json(); } catch { /* not json */ }
    return { status: r.status, body: data, headers: r.headers };
  };
}
const post = (body, extra = {}) => ({ method: "POST", body, ...extra });

let passed = 0;
async function test(name, fn) {
  try { await fn(); passed++; console.log("PASS", name); }
  catch (e) { console.error("FAIL", name); console.error(e); process.exitCode = 1; }
}

async function genericSig(secret, raw, t = Math.floor(now / 1000)) {
  return "t=" + t + ",v1=" + await cos.hmacHex(secret, t + "." + raw);
}
const SECRET = "whsec_" + "k".repeat(32);
async function sendWebhook(call, ev, secret = SECRET, t) {
  const raw = JSON.stringify(ev);
  return call("/api/shop/webhook/generic", post(raw, { headers: { "evade-signature": await genericSig(secret, raw, t) } }));
}
const paid = (orderId, uuid, items, extra = {}) => ({ id: "ev_" + orderId, type: "order.paid",
  order: { id: orderId, uuid, items, amountCents: 499, currency: "USD", email: "someone@example.com", name: "Some Body", ...extra } });

// ---------------------------------------------------------------- tests

await test("catalog: normalized items, asset base URLs, bad entries dropped", async () => {
  const call = caller(makeEnv());
  const r = await call("/api/cosmetics/catalog");
  assert.equal(r.status, 200);
  assert.equal(r.body.version, "test-1");
  assert.deepEqual(r.body.items.map((i) => i.id), ["red_cape", "halo", "free_hat", "craft_wings", "other_wings"]);
  const cape = r.body.items[0];
  assert.equal(cape.slot, "CAPE");
  assert.equal(cape.currency, "USD");
  assert.equal(cape.partner, null);
  assert.equal(cape.assets.base, "https://evadeclient.com/cosmetics/red_cape/");
  assert.deepEqual(cape.assets.files, { "cape.png": "a".repeat(64) });
  assert.deepEqual(r.body.items[3].partner, { id: "craftnet", name: "CraftNet" });
  assert.equal(r.body.items[3].priceCents, null);
  assert.equal(r.body.items[1].colorable, true); // extra fields pass through
  // no catalog file at all: an empty catalog, not an error
  const env = makeEnv();
  env.ASSETS = { fetch: async () => new Response("<html>404</html>", { status: 404, headers: { "content-type": "text/html" } }) };
  const e = await caller(env)("/api/cosmetics/catalog");
  assert.equal(e.status, 200);
  assert.deepEqual(e.body.items, []);
});

await test("production: the shop is closed and test checkout is impossible", async () => {
  for (const vars of [{}, { SHOP_MODE: "off" }, { SHOP_MODE: "test" }, { SHOP_MODE: "TEST" }, { SHOP_MODE: "live" }]) {
    const env = makeEnv(vars);
    const call = caller(env); // evadeclient.com
    const st = await call("/api/shop/status");
    assert.deepEqual(st.body, { open: false, mode: "off" }, JSON.stringify(vars));
    const link = await call("/api/cosmetics/link-code", { uuid: A });
    const co = await call("/api/shop/checkout", post({ linkCode: link.body.code, items: ["red_cape"] }));
    assert.equal(co.status, 503);
    assert.deepEqual(co.body, { error: "The shop isn't open yet" });
    const co2 = await call("/api/shop/checkout", post({ uuid: A, items: ["red_cape"] }));
    assert.equal(co2.status, 503);
    for (const p of ["/api/shop/test/complete", "/api/shop/test/refund"]) {
      const r = await call(p, post({ sessionId: "0".repeat(32) }));
      assert.equal(r.status, 503);
      assert.deepEqual(r.body, { error: "The shop isn't open yet" });
    }
    // nothing granted, and the store refuses a test session even if asked directly
    assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body.owned, []);
    const direct = await env._social.cosmetics("session.create", { mode: "off", buyer: A, recipient: A, items: ["red_cape"], totalCents: 1, currency: "USD", ip: "x" });
    assert.equal(direct.status, 503);
  }
  // also refused on a workers.dev preview or any other public host
  const env = makeEnv({ SHOP_MODE: "test" });
  for (const host of ["https://evade-client.someone.workers.dev", "https://www.evadeclient.com", "https://evil.example"]) {
    const r = await caller(env, host)("/api/shop/checkout", post({ uuid: A, items: ["red_cape"] }));
    assert.equal(r.status, 503, host);
  }
});

await test("test mode: link code -> checkout -> complete -> owned -> equip -> players -> refund revokes", async () => {
  const env = makeEnv({ SHOP_MODE: "test" });
  const call = caller(env, "https://shop.test");
  assert.deepEqual((await call("/api/shop/status")).body, { open: true, mode: "test" });
  const link = await call("/api/cosmetics/link-code", { uuid: A });
  assert.equal(link.status, 200);
  assert.match(link.body.code, /^[A-HJKMNP-Z2-9]{6}$/);
  assert.equal(link.body.expiresIn, 600);
  const res = await call("/api/shop/resolve", post({ linkCode: link.body.code.toLowerCase() }));
  assert.deepEqual(res.body, { uuid: A, verified: true });
  // checks on the cart
  assert.equal((await call("/api/shop/checkout", post({ linkCode: link.body.code, items: ["nope"] }))).status, 400);
  assert.equal((await call("/api/shop/checkout", post({ linkCode: link.body.code, items: ["craft_wings"] }))).status, 400); // not for sale
  assert.equal((await call("/api/shop/checkout", post({ linkCode: link.body.code, items: ["free_hat"] }))).status, 400);
  assert.equal((await call("/api/shop/checkout", post({ linkCode: "ZZZZZZ", items: ["red_cape"] }))).status, 404);
  assert.equal((await call("/api/shop/checkout", post({ items: ["red_cape"] }))).status, 400);
  const co = await call("/api/shop/checkout", post({ linkCode: link.body.code, items: ["red_cape", "halo", "red_cape"] }));
  assert.equal(co.status, 200, JSON.stringify(co.body));
  assert.equal(co.body.mode, "test");
  assert.equal(co.body.totalCents, 798);
  assert.equal(co.body.currency, "USD");
  assert.equal(co.body.recipient, A);
  assert.match(co.body.sessionId, /^[0-9a-f]{32}$/);
  // not owned until completed
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body.owned, []);
  assert.equal((await call("/api/cosmetics/equip", post({ slot: "CAPE", id: "red_cape" }, { uuid: A }))).status, 403);
  const done = await call("/api/shop/test/complete", post({ sessionId: co.body.sessionId }));
  assert.equal(done.status, 200);
  assert.equal(done.body.session.status, "paid");
  assert.equal((await call("/api/shop/test/complete", post({ sessionId: co.body.sessionId }))).status, 409);
  const me = await call("/api/cosmetics/me", { uuid: A });
  assert.deepEqual(me.body.owned, ["halo", "red_cape"]);
  assert.deepEqual(me.body.shop, { open: true, mode: "test" });
  // equip
  assert.equal((await call("/api/cosmetics/equip", post({ slot: "HAT", id: "red_cape" }, { uuid: A }))).status, 400); // wrong slot
  assert.equal((await call("/api/cosmetics/equip", post({ slot: "HAT", id: "halo", color: "red" }, { uuid: A }))).status, 400);
  assert.equal((await call("/api/cosmetics/equip", post({ slot: "CAPE", id: "red_cape" }))).status, 401);
  let eq = await call("/api/cosmetics/equip", post({ slot: "cape", id: "red_cape" }, { uuid: A }));
  assert.equal(eq.status, 200);
  eq = await call("/api/cosmetics/equip", post({ slot: "HAT", id: "halo", color: "#FF8800" }, { uuid: A }));
  assert.deepEqual(eq.body.equipped, { CAPE: { id: "red_cape" }, HAT: { id: "halo", color: "#ff8800" } });
  // B can't equip what A owns
  assert.equal((await call("/api/cosmetics/equip", post({ slot: "CAPE", id: "red_cape" }, { uuid: B }))).status, 403);
  const dashed = A.slice(0, 8) + "-" + A.slice(8, 12) + "-" + A.slice(12, 16) + "-" + A.slice(16, 20) + "-" + A.slice(20);
  let pl = await call("/api/cosmetics/players?uuids=" + dashed + "," + B + ",notauuid");
  assert.equal(pl.status, 200);
  assert.deepEqual(pl.body, { players: { [A]: { equipped: { CAPE: { id: "red_cape" }, HAT: { id: "halo", color: "#ff8800" } } } } });
  // unequip
  await call("/api/cosmetics/equip", post({ slot: "HAT", id: null }, { uuid: A }));
  pl = await call("/api/cosmetics/players?uuids=" + A);
  assert.deepEqual(pl.body.players[A].equipped, { CAPE: { id: "red_cape" } });
  // refund: items gone, unequipped, players endpoint empty
  assert.equal((await call("/api/shop/test/refund", post({ sessionId: co.body.sessionId }))).status, 200);
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body, { uuid: A, owned: [], equipped: {}, shop: { open: true, mode: "test" } });
  assert.deepEqual((await call("/api/cosmetics/players?uuids=" + A)).body, { players: {} });
  assert.equal((await call("/api/shop/session?id=" + co.body.sessionId)).body.status, "refunded");
});

await test("test mode: gifts go to giftTo; chargeback revokes too", async () => {
  const call = caller(makeEnv({ SHOP_MODE: "test" }), "http://localhost:8787");
  const link = await call("/api/cosmetics/link-code", { uuid: A });
  const co = await call("/api/shop/checkout", post({ linkCode: link.body.code, items: ["halo"], giftTo: B }));
  assert.equal(co.body.recipient, B);
  await call("/api/shop/test/complete", post({ sessionId: co.body.sessionId }));
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body.owned, []);
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: B })).body.owned, ["halo"]);
  await call("/api/shop/test/refund", post({ sessionId: co.body.sessionId, chargeback: true }));
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: B })).body.owned, []);
  assert.equal((await call("/api/shop/session?id=" + co.body.sessionId)).body.status, "chargeback");
  // a session expires after 30 minutes
  const co2 = await call("/api/shop/checkout", post({ uuid: C, items: ["halo"] }));
  now += 31 * 60 * 1000;
  assert.equal((await call("/api/shop/test/complete", post({ sessionId: co2.body.sessionId }))).status, 410);
});

await test("webhooks: unconfigured and bad signatures are rejected; verified ones grant, refunds revoke", async () => {
  // no secrets configured: everything is refused
  let call = caller(makeEnv());
  for (const p of ["generic", "tebex", "paddle", "stripe"]) {
    const r = await call("/api/shop/webhook/" + p, post(paid("o1", A, ["red_cape"])));
    assert.equal(r.status, 503, p);
  }
  assert.equal((await call("/api/shop/webhook/nope", post({}))).status, 404);
  assert.equal((await call("/api/shop/webhook/__proto__", post({}))).status, 404);
  const env = makeEnv({ SHOP_WEBHOOK_SECRET: SECRET, TEBEX_WEBHOOK_SECRET: SECRET, PADDLE_WEBHOOK_SECRET: SECRET, STRIPE_WEBHOOK_SECRET: SECRET });
  call = caller(env);
  // bad signatures
  assert.equal((await call("/api/shop/webhook/generic", post(paid("o1", A, ["red_cape"])))).status, 401);
  assert.equal((await sendWebhook(call, paid("o1", A, ["red_cape"]), "wrong-secret-wrong-secret")).status, 401);
  assert.equal((await sendWebhook(call, paid("o1", A, ["red_cape"]), SECRET, Math.floor(now / 1000) - 3600)).status, 401); // stale
  const raw = JSON.stringify(paid("o1", A, ["red_cape"]));
  const sig = await genericSig(SECRET, raw);
  assert.equal((await call("/api/shop/webhook/generic", post(raw.replace("red_cape", "halo"), { headers: { "evade-signature": sig } }))).status, 401); // tampered
  for (const [p, h] of [["tebex", "x-signature"], ["paddle", "paddle-signature"], ["stripe", "stripe-signature"]]) {
    assert.equal((await call("/api/shop/webhook/" + p, post(raw, { headers: { [h]: "t=1,v1=" + "0".repeat(64) } }))).status, 401, p);
  }
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body.owned, []);
  // the stub adapters verify, then acknowledge without granting
  const t = Math.floor(now / 1000);
  const st = await call("/api/shop/webhook/stripe", post(raw, { headers: { "stripe-signature": "t=" + t + ",v1=" + await cos.hmacHex(SECRET, t + "." + raw) } }));
  assert.deepEqual(st.body, { ok: true, ignored: true });
  const pd = await call("/api/shop/webhook/paddle", post(raw, { headers: { "paddle-signature": "ts=" + t + ";h1=" + await cos.hmacHex(SECRET, t + ":" + raw) } }));
  assert.deepEqual(pd.body, { ok: true, ignored: true });
  const tb = await call("/api/shop/webhook/tebex", post(raw, { headers: { "x-signature": await cos.hmacHex(SECRET, await sha256(raw)) } }));
  assert.deepEqual(tb.body, { ok: true, ignored: true });
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body.owned, []);
  // a verified payment grants (unknown items reported, not granted); a replay changes nothing
  let r = await sendWebhook(call, paid("o1", A, ["red_cape", "ghost_item"]));
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.granted, ["red_cape"]);
  assert.deepEqual(r.body.unknown, ["ghost_item"]);
  r = await sendWebhook(call, paid("o1", A, ["red_cape", "halo"]));
  assert.deepEqual(r.body.granted, []);
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body.owned, ["red_cape"]);
  await call("/api/cosmetics/equip", post({ slot: "CAPE", id: "red_cape" }, { uuid: A }));
  // refund revokes and unequips
  r = await sendWebhook(call, { id: "ev_r1", type: "order.refunded", order: { id: "o1" } });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.revoked, ["red_cape"]);
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body, { uuid: A, owned: [], equipped: {}, shop: { open: false, mode: "off" } });
  // a refund that arrives first means the late payment grants nothing
  await sendWebhook(call, { id: "ev_r2", type: "order.chargeback", order: { id: "o2" } });
  r = await sendWebhook(call, paid("o2", B, ["halo"]));
  assert.deepEqual(r.body.granted, []);
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: B })).body.owned, []);
  // no e-mail or name is stored anywhere
  const dump = JSON.stringify(env._social.sql.exec("SELECT * FROM cos_orders").toArray());
  assert.ok(!dump.includes("example.com") && !dump.includes("Some Body"), dump);
  // unknown event types are acknowledged and ignored
  assert.deepEqual((await sendWebhook(call, { id: "x", type: "customer.created", email: "a@b.c" })).body, { ok: true, ignored: true });
});

async function sha256(s) {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

await test("admin: off without ADMIN_TOKEN; redeem codes are single-use and rate limited", async () => {
  let call = caller(makeEnv());
  assert.equal((await call("/api/admin/codes", post({ items: ["halo"] }, { headers: { authorization: "Bearer anything" } }))).status, 404);
  call = caller(makeEnv({ ADMIN_TOKEN: "short" }));
  assert.equal((await call("/api/admin/codes", post({ items: ["halo"] }, { headers: { authorization: "Bearer short" } }))).status, 404);
  const env = makeEnv({ ADMIN_TOKEN: ADMIN });
  call = caller(env);
  const admin = { authorization: "Bearer " + ADMIN };
  assert.equal((await call("/api/admin/codes", post({ items: ["halo"] }, { headers: { authorization: "Bearer " + "y".repeat(40) } }))).status, 401);
  assert.equal((await call("/api/admin/codes", post({ items: ["halo"] }))).status, 401);
  assert.equal((await call("/api/admin/codes", post({ items: ["nope"] }, { headers: admin }))).status, 400);
  const made = await call("/api/admin/codes", post({ items: ["halo", "red_cape"], count: 3, note: "giveaway" }, { headers: admin }));
  assert.equal(made.status, 200);
  assert.equal(made.body.codes.length, 3);
  assert.equal(new Set(made.body.codes).size, 3);
  for (const c of made.body.codes) assert.match(c, /^[A-HJKMNP-Z2-9]{4}(-[A-HJKMNP-Z2-9]{4}){3}$/);
  // codes are stored hashed, never in plain text
  const stored = JSON.stringify(env._social.sql.exec("SELECT * FROM cos_codes").toArray());
  for (const c of made.body.codes) assert.ok(!stored.includes(c.replace(/-/g, "")));
  const [c1, c2] = made.body.codes;
  let r = await call("/api/cosmetics/redeem", post({ code: c1.toLowerCase().replace(/-/g, " ") }, { uuid: A }));
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.deepEqual(r.body.granted.sort(), ["halo", "red_cape"]);
  assert.equal((await call("/api/cosmetics/redeem", post({ code: c1 }, { uuid: A }))).status, 409);
  assert.equal((await call("/api/cosmetics/redeem", post({ code: c1 }, { uuid: B }))).status, 409);
  assert.equal((await call("/api/cosmetics/redeem", post({ code: c1 }))).status, 401);
  // A already owns both: a second code grants nothing new but is used up
  r = await call("/api/cosmetics/redeem", post({ code: c2 }, { uuid: A }));
  assert.deepEqual(r.body.granted, []);
  // guessing is limited: 10 tries an hour per account
  let last;
  for (let i = 0; i < 11; i++) last = await call("/api/cosmetics/redeem", post({ code: "AAAA-BBBB-CCCC-" + String(1000 + i) }, { uuid: C }));
  assert.equal(last.status, 429);
  assert.ok(Number(last.headers.get("retry-after")) > 0);
  // expiring codes
  const exp = await call("/api/admin/codes", post({ items: ["halo"], expiresDays: 1 }, { headers: admin }));
  now += 2 * 24 * 3600 * 1000;
  assert.equal((await call("/api/cosmetics/redeem", post({ code: exp.body.codes[0] }, { uuid: B }))).status, 404);
  // admin grant / revoke / orders
  await call("/api/admin/grant", post({ uuid: B, items: ["halo"] }, { headers: admin }));
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: B })).body.owned, ["halo"]);
  await call("/api/admin/revoke", post({ uuid: B, items: ["halo"] }, { headers: admin }));
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: B })).body.owned, []);
  assert.equal((await call("/api/admin/orders", post({ uuid: A }, { headers: admin }))).status, 200);
});

await test("partner grants: signed with the partner's key, own items only, no replays", async () => {
  const env = makeEnv({ ADMIN_TOKEN: ADMIN });
  const call = caller(env);
  const admin = { authorization: "Bearer " + ADMIN };
  const key = (await call("/api/admin/partner", post({ id: "craftnet", name: "CraftNet" }, { headers: admin }))).body.key;
  assert.match(key, /^evp_[0-9a-f]{48}$/);
  const other = (await call("/api/admin/partner", post({ id: "othernet" }, { headers: admin }))).body.key;
  const grant = async (partner, k, body, { ts = now, sig } = {}) => {
    const raw = JSON.stringify(body);
    return call("/api/partner/grant", post(raw, { headers: {
      "x-evade-partner": partner, "x-evade-timestamp": String(ts),
      "x-evade-signature": sig || await cos.hmacHex(k, partner + "\n" + ts + "\n" + raw) } }));
  };
  let r = await grant("craftnet", key, { uuid: A, item: "craft_wings", nonce: "nonce-0001" });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.granted, true);
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body.owned, ["craft_wings"]);
  assert.equal((await call("/api/cosmetics/equip", post({ slot: "WINGS", id: "craft_wings" }, { uuid: A }))).status, 200);
  // replayed nonce
  assert.equal((await grant("craftnet", key, { uuid: A, item: "craft_wings", nonce: "nonce-0001" })).status, 409);
  // wrong key, unknown partner, tampered signature, stale timestamp
  assert.equal((await grant("craftnet", other, { uuid: B, item: "craft_wings", nonce: "nonce-0002" })).status, 401);
  assert.equal((await grant("ghostnet", key, { uuid: B, item: "craft_wings", nonce: "nonce-0003" })).status, 401);
  assert.equal((await grant("craftnet", key, { uuid: B, item: "craft_wings", nonce: "nonce-0004" }, { sig: "0".repeat(64) })).status, 401);
  assert.equal((await grant("craftnet", key, { uuid: B, item: "craft_wings", nonce: "nonce-0005" }, { ts: now - 10 * 60 * 1000 })).status, 401);
  // another partner's item, or a shop item
  assert.equal((await grant("craftnet", key, { uuid: B, item: "other_wings", nonce: "nonce-0006" })).status, 403);
  assert.equal((await grant("craftnet", key, { uuid: B, item: "red_cape", nonce: "nonce-0007" })).status, 403);
  assert.equal((await grant("othernet", other, { uuid: B, item: "other_wings", nonce: "nonce-0008" })).body.granted, true);
  assert.deepEqual((await call("/api/cosmetics/me", { uuid: B })).body.owned, ["other_wings"]);
  // granting again: fine, nothing new
  assert.equal((await grant("craftnet", key, { uuid: A, item: "craft_wings", nonce: "nonce-0009" })).body.granted, false);
  // a rotated key replaces the old one; a deleted partner can't grant
  const key2 = (await call("/api/admin/partner", post({ id: "craftnet", name: "CraftNet" }, { headers: admin }))).body.key;
  assert.equal((await grant("craftnet", key, { uuid: C, item: "craft_wings", nonce: "nonce-0010" })).status, 401);
  assert.equal((await grant("craftnet", key2, { uuid: C, item: "craft_wings", nonce: "nonce-0011" })).status, 200);
  await call("/api/admin/partner/delete", post({ id: "craftnet" }, { headers: admin }));
  assert.equal((await grant("craftnet", key2, { uuid: C, item: "craft_wings", nonce: "nonce-0012" })).status, 401);
});

await test("players lookup and link codes are rate limited; at most 64 uuids", async () => {
  const env = makeEnv({ SHOP_MODE: "test" });
  const call = caller(env, "https://shop.test");
  const many = Array.from({ length: 65 }, (_, i) => i.toString(16).padStart(32, "0")).join(",");
  assert.equal((await call("/api/cosmetics/players?uuids=" + many)).status, 400);
  assert.equal((await call("/api/cosmetics/players", { method: "POST", body: "{}" })).status, 405);
  let last;
  for (let i = 0; i < 121; i++) last = await call("/api/cosmetics/players?uuids=" + A, { ip: "9.9.9.9" });
  assert.equal(last.status, 429);
  assert.equal((await call("/api/cosmetics/players?uuids=" + A, { ip: "8.8.8.8" })).status, 200); // other address
  now += 61 * 1000;
  assert.equal((await call("/api/cosmetics/players?uuids=" + A, { ip: "9.9.9.9" })).status, 200);
  // guessing link codes: 30 tries per 10 minutes per address
  for (let i = 0; i < 31; i++) last = await call("/api/shop/resolve", post({ linkCode: "QQQQ" + String(10 + i).replace(/[01]/g, "Q") }, { ip: "7.7.7.7" }));
  assert.equal(last.status, 429);
  // link codes lapse after 10 minutes; a new one replaces the old one
  const l1 = (await call("/api/cosmetics/link-code", { uuid: A })).body.code;
  const l2 = (await call("/api/cosmetics/link-code", { uuid: A })).body.code;
  assert.equal((await call("/api/shop/resolve", post({ linkCode: l1 }))).status, l1 === l2 ? 200 : 404);
  assert.equal((await call("/api/shop/resolve", post({ linkCode: l2 }))).status, 200);
  now += 11 * 60 * 1000;
  assert.equal((await call("/api/shop/resolve", post({ linkCode: l2 }))).status, 404);
  assert.equal((await call("/api/cosmetics/link-code")).status, 401);
  for (let i = 0; i < 20; i++) last = await call("/api/cosmetics/link-code", { uuid: B });
  assert.equal(last.status, 200);
  assert.equal((await call("/api/cosmetics/link-code", { uuid: B })).status, 429);
});

await test("username lookup falls back to the link code when Mojang refuses", async () => {
  const realFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response("forbidden", { status: 403 });
    const call = caller(makeEnv());
    let r = await call("/api/shop/resolve", post({ username: "Notch" }));
    assert.equal(r.status, 502);
    assert.equal(r.body.useLinkCode, true);
    assert.equal((await call("/api/shop/resolve", post({ username: "bad name!" }))).status, 400);
    globalThis.fetch = async () => new Response(JSON.stringify({ id: A, name: "Alice" }), { status: 200 });
    r = await call("/api/shop/resolve", post({ username: "Alice" }));
    assert.deepEqual(r.body, { uuid: A, verified: false });
    globalThis.fetch = async () => new Response(null, { status: 404 });
    assert.equal((await call("/api/shop/resolve", post({ username: "Nobody" }))).status, 404);
  } finally {
    globalThis.fetch = realFetch;
  }
});

await test("owned cosmetics survive Delete my social data and the inactivity sweep", async () => {
  const env = makeEnv({ ADMIN_TOKEN: ADMIN });
  const call = caller(env);
  await call("/api/admin/grant", post({ uuid: A, items: ["halo"] }, { headers: { authorization: "Bearer " + ADMIN } }));
  await call("/api/cosmetics/equip", post({ slot: "HAT", id: "halo" }, { uuid: A }));
  const s = env._social;
  assert.equal((await s.http(A, "state", { name: "Alice" })).status, 200);
  assert.equal((await s.http(A, "delete", {})).status, 200);
  now += 100 * 24 * 3600 * 1000;
  await s.alarm();
  const me = await call("/api/cosmetics/me", { uuid: A });
  assert.deepEqual(me.body.owned, ["halo"]);
  assert.deepEqual(me.body.equipped, { HAT: { id: "halo" } });
  // and the social API still answers as before
  const r = await caller(env)("/api/social/state", post({ name: "Alice" }, { uuid: A }));
  assert.equal(r.status, 200);
});

await test("items dropped from the catalog disappear from me and players", async () => {
  const env = makeEnv({ ADMIN_TOKEN: ADMIN });
  const call = caller(env);
  await call("/api/admin/grant", post({ uuid: A, items: ["halo"] }, { headers: { authorization: "Bearer " + ADMIN } }));
  await call("/api/cosmetics/equip", post({ slot: "HAT", id: "halo" }, { uuid: A }));
  const saved = CATALOG.items;
  CATALOG.items = saved.filter((i) => i.id !== "halo");
  cos.resetCatalogCache();
  try {
    assert.deepEqual((await call("/api/cosmetics/me", { uuid: A })).body.owned, []);
    assert.deepEqual((await call("/api/cosmetics/players?uuids=" + A)).body, { players: {} });
  } finally {
    CATALOG.items = saved;
    cos.resetCatalogCache();
  }
});

console.log(`\n${passed} passed` + (process.exitCode ? ", some FAILED" : ""));
