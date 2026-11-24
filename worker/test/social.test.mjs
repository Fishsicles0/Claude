// Offline tests for the EvadeSocial Durable Object and the /api/social/* routing in worker/index.js.
// Runs on plain Node 22+ (node:sqlite stands in for the Durable Object's SQLite storage; sockets are fakes):
//   node worker/test/social.test.mjs
// Not part of the deployed worker (wrangler only bundles worker/index.js and what it imports).
import { DatabaseSync } from "node:sqlite";
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import assert from "node:assert/strict";

// Load worker/index.js with the "cloudflare:workers" import swapped for a stand-in base class.
const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, "..", "index.js"), "utf8")
  .replace('import { DurableObject } from "cloudflare:workers";',
    "class DurableObject { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }");
const tmp = mkdtempSync(join(tmpdir(), "evade-social-"));
writeFileSync(join(tmp, "index.mjs"), src);
globalThis.WebSocketRequestResponsePair = class { constructor(a, b) { this.a = a; this.b = b; } };
const worker = await import(pathToFileURL(join(tmp, "index.mjs")).href);
const { EvadeSocial, normServer } = worker;

// ---------------------------------------------------------------- fakes
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

class FakeWS {
  constructor(ctx, name) { this.ctx = ctx; this.name = name; this.events = []; this.closed = null; this.att = null; this.tags = []; }
  send(text) { if (this.closed) throw new Error("closed"); this.events.push(JSON.parse(text)); }
  close(code, reason) { if (!this.closed) this.closed = { code, reason }; }
  serializeAttachment(a) { this.att = structuredClone(a); }
  deserializeAttachment() { return this.att; }
  take(type) { const out = this.events.filter((e) => !type || e.type === type); this.events = this.events.filter((e) => type && e.type !== type); return out; }
}

function makeSocial() {
  const sockets = [];
  let alarm = null;
  const ctx = {
    storage: { sql: fakeSql(), getAlarm: async () => alarm, setAlarm: async (t) => { alarm = t; } },
    acceptWebSocket(ws, tags) { ws.tags = tags; sockets.push(ws); },
    getWebSockets(tag) { return sockets.filter((w) => !w.closed && (tag === undefined || w.tags.includes(tag))); },
    setWebSocketAutoResponse() {},
    blockConcurrencyWhile(fn) { return fn(); },
  };
  const env = { ICONS: { idFromName: () => "g", get: () => ({ verify: async (t) => (t.startsWith("tok-") ? t.slice(4) : null) }) } };
  const s = new EvadeSocial(ctx, env);
  s._ctx = ctx;
  s._alarm = () => alarm;
  return s;
}

const U = (c) => c.repeat(32); // a fake 32-hex uuid
const A = U("a"), B = U("b"), C = U("c"), D = U("d");
let passed = 0;
async function test(name, fn) {
  try { await fn(); passed++; console.log("PASS", name); }
  catch (e) { console.error("FAIL", name); console.error(e); process.exitCode = 1; }
}
async function ok(s, uuid, op, args = {}) {
  const r = await s.http(uuid, op, args);
  assert.equal(r.status, 200, op + " -> " + JSON.stringify(r.body));
  return r.body;
}
async function fails(s, uuid, op, args, status) {
  const r = await s.http(uuid, op, args);
  assert.equal(r.status, status, op + " expected " + status + " got " + r.status + " " + JSON.stringify(r.body));
  return r.body;
}
async function connect(s, uuid) {
  const ws = new FakeWS(s._ctx, uuid);
  await s.accept(ws, uuid);
  return ws;
}

// ---------------------------------------------------------------- tests
await test("normServer", () => {
  assert.equal(normServer("Play.Example.NET:25565"), "play.example.net");
  assert.equal(normServer(" play.example.net. "), "play.example.net");
  assert.equal(normServer("play.example.net:25566"), "play.example.net:25566");
  assert.equal(normServer("[2001:db8::1]:25565"), "[2001:db8::1]");
  assert.equal(normServer("8.8.8.8"), "8.8.8.8");
  for (const bad of ["localhost", "127.0.0.1", "192.168.1.5:25565", "10.0.0.2", "172.20.1.1", "my-pc", "server.local",
    "[::1]", "", null, "a b", "x:99999", "a..b"]) assert.equal(normServer(bad), null, String(bad));
});

await test("friend request by name, accept, list with online state", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice" });
  await ok(s, B, "state", { name: "Bob" });
  const wsB = await connect(s, B);
  assert.equal(wsB.take("hello").length, 1);
  await ok(s, A, "friend.request", { name: "bob" });
  const req = wsB.take("friend.request");
  assert.equal(req.length, 1);
  assert.deepEqual(req[0].from, { uuid: A, name: "Alice" });
  await fails(s, A, "friend.request", { name: "Bob" }, 409); // already sent
  const st = (await ok(s, B, "friend.accept", { uuid: A })).state;
  assert.equal(st.friends.length, 1);
  assert.equal(st.friends[0].name, "Alice");
  const stA = (await ok(s, A, "state", {})).state;
  assert.equal(stA.friends[0].uuid, B);
  assert.equal(stA.friends[0].online, true); // B has a socket open
  await fails(s, A, "friend.request", { uuid: B }, 409); // already friends
});

await test("requests: refused the same way for unknown, nobody and blocked; crossing requests become friends", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice" });
  await ok(s, B, "state", { name: "Bob" });
  await ok(s, C, "state", { name: "Cara" });
  const unknown = await fails(s, A, "friend.request", { name: "Nobody" }, 404);
  await ok(s, B, "settings", { allowRequests: "nobody" });
  const nobody = await fails(s, A, "friend.request", { name: "Bob" }, 404);
  assert.equal(unknown.error.replace("Nobody", "X"), nobody.error.replace("Bob", "X"));
  await ok(s, C, "block", { uuid: A });
  await fails(s, A, "friend.request", { uuid: C }, 404);
  await ok(s, B, "settings", { allowRequests: "everyone" });
  await ok(s, B, "friend.request", { uuid: A });
  const r = await ok(s, A, "friend.request", { uuid: B });
  assert.equal(r.accepted, true);
  assert.equal(r.state.friends.length, 1);
  await fails(s, A, "friend.request", { uuid: A }, 400);
});

await test("a name two accounts claim is never guessed between", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice" });
  await ok(s, B, "state", { name: "Dex" });
  await ok(s, C, "state", { name: "Dex" });
  await fails(s, A, "friend.request", { name: "Dex" }, 404);
  await ok(s, A, "friend.request", { uuid: B }); // by UUID (what the client sends after asking Mojang) still works
});

await test("decline, cancel, remove, limits on pending requests", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice" });
  await ok(s, B, "state", { name: "Bob" });
  await ok(s, A, "friend.request", { uuid: B });
  let st = (await ok(s, B, "friend.decline", { uuid: A })).state;
  assert.equal(st.incoming.length, 0);
  await ok(s, A, "friend.request", { uuid: B });
  st = (await ok(s, A, "friend.cancel", { uuid: B })).state;
  assert.equal(st.outgoing.length, 0);
  await ok(s, A, "friend.request", { uuid: B });
  await ok(s, B, "friend.accept", { uuid: A });
  st = (await ok(s, A, "friend.remove", { uuid: B })).state;
  assert.equal(st.friends.length, 0);
  assert.equal((await ok(s, B, "state")).state.friends.length, 0);
  // 30 pending outgoing at most (the hourly request limit is 20, so spread over two hours).
  const s2 = makeSocial();
  await ok(s2, A, "state", { name: "Alice" });
  for (let i = 0; i < 31; i++) await ok(s2, i.toString(16).padStart(32, "e"), "state", { name: "P" + i });
  for (let i = 0; i < 20; i++) await ok(s2, A, "friend.request", { uuid: i.toString(16).padStart(32, "e") });
  await fails(s2, A, "friend.request", { uuid: (20).toString(16).padStart(32, "e") }, 429);
  now += 3600 * 1000 + 1;
  for (let i = 20; i < 30; i++) await ok(s2, A, "friend.request", { uuid: i.toString(16).padStart(32, "e") });
  await fails(s2, A, "friend.request", { uuid: (30).toString(16).padStart(32, "e") }, 409);
});

async function friends(s, x, y) {
  await ok(s, x, "friend.request", { uuid: y });
  await ok(s, y, "friend.accept", { uuid: x });
}

await test("parties: invite friends only, accept, max 8, leave, kick, transfer", async () => {
  const s = makeSocial();
  const people = [A, B, C, D, U("1"), U("2"), U("3"), U("4"), U("5")];
  for (const [i, p] of people.entries()) await ok(s, p, "state", { name: "P" + i });
  for (const p of people.slice(1)) await friends(s, A, p);
  await fails(s, B, "party.invite", { uuid: C }, 404); // B and C aren't friends
  const wsB = await connect(s, B);
  const st = (await ok(s, A, "party.invite", { uuid: B })).state; // creates A's party
  assert.equal(st.party.members.length, 1);
  assert.equal(st.party.invited[0].uuid, B);
  const inv = wsB.take("party.invite");
  assert.equal(inv.length, 1);
  const party = inv[0].party;
  await ok(s, B, "party.accept", { party });
  for (const p of people.slice(2, 8)) {
    await ok(s, A, "party.invite", { uuid: p });
    await ok(s, p, "party.accept", { party });
  }
  assert.equal((await ok(s, A, "state")).state.party.members.length, 8);
  await fails(s, A, "party.invite", { uuid: people[8] }, 409); // full
  await fails(s, B, "party.kick", { uuid: C }, 403); // not the leader
  await ok(s, A, "party.kick", { uuid: C });
  assert.equal((await ok(s, C, "state")).state.party, null);
  await ok(s, A, "party.transfer", { uuid: B });
  assert.equal((await ok(s, A, "state")).state.party.leader, B);
  await ok(s, B, "party.leave");
  assert.equal((await ok(s, A, "state")).state.party.leader, A); // next member in order of joining leads
  // invites lapse after 10 minutes
  await ok(s, A, "party.invite", { uuid: C });
  now += 11 * 60 * 1000;
  await fails(s, C, "party.accept", { party }, 404);
});

await test("pings reach only party members online on the same server, and are rate limited", async () => {
  const s = makeSocial();
  for (const [p, n] of [[A, "Alice"], [B, "Bob"], [C, "Cara"], [D, "Dex"]]) await ok(s, p, "state", { name: n });
  await friends(s, A, B); await friends(s, A, C); await friends(s, A, D);
  const ws = { B: await connect(s, B), C: await connect(s, C), D: await connect(s, D) };
  const party = (await ok(s, A, "party.create")).state.party.id;
  for (const p of [B, C]) { await ok(s, A, "party.invite", { uuid: p }); await ok(s, p, "party.accept", { party }); }
  await ok(s, A, "presence", { server: "Play.Example.net:25565" });
  await ok(s, B, "presence", { server: "play.example.net" });
  await ok(s, C, "presence", { server: "other.example.net" });
  await ok(s, D, "presence", { server: "play.example.net" }); // same server, not in the party
  Object.values(ws).forEach((w) => w.take());
  const ping = { x: 120.4, y: 64, z: -340, dimension: "minecraft:overworld", server: "play.example.net", kind: "danger" };
  await ok(s, A, "ping", ping);
  const got = ws.B.take("ping");
  assert.equal(got.length, 1);
  assert.deepEqual({ ...got[0], at: 0 }, { type: "ping", from: { uuid: A, name: "Alice" }, x: 120.4, y: 64, z: -340, dimension: "minecraft:overworld", kind: "danger", at: 0 });
  assert.equal(ws.C.take("ping").length, 0);
  assert.equal(ws.D.take("ping").length, 0);
  // 1 per 1.5 s
  const fast = await fails(s, A, "ping", ping, 429);
  assert.ok(fast.retryAfter > 0 && fast.retryAfter <= 1500);
  // at most 5 per 10 s
  for (let i = 0; i < 4; i++) { now += 1600; await ok(s, A, "ping", ping); }
  now += 1600;
  await fails(s, A, "ping", ping, 429);
  now += 10_000;
  await ok(s, A, "ping", ping);
  // a blocked member gets nothing
  ws.B.take();
  await ok(s, B, "block", { uuid: A });
  now += 2000;
  await ok(s, A, "ping", ping);
  assert.equal(ws.B.take("ping").length, 0);
  // bad input
  now += 2000;
  await fails(s, A, "ping", { ...ping, server: "localhost" }, 400);
  await fails(s, A, "ping", { ...ping, x: 1e9 }, 400);
  await fails(s, A, "ping", { ...ping, kind: "attack" }, 400);
  await fails(s, A, "ping", { ...ping, dimension: "Overworld!" }, 400);
  // not in a party
  now += 2000;
  await fails(s, D, "ping", ping, 409);
});

await test("polling clients get pings with their next /state", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice", server: "mc.example.org" });
  await ok(s, B, "state", { name: "Bob", server: "mc.example.org" });
  await friends(s, A, B);
  const party = (await ok(s, A, "party.create")).state.party.id;
  await ok(s, A, "party.invite", { uuid: B });
  await ok(s, B, "party.accept", { party });
  await ok(s, A, "ping", { x: 1, y: 2, z: 3, dimension: "minecraft:the_nether", server: "mc.example.org" });
  const st = await ok(s, B, "state", { server: "mc.example.org" });
  assert.equal(st.pings.length, 1);
  assert.equal(st.pings[0].kind, "location");
  assert.equal((await ok(s, B, "state", {})).pings.length, 0);
});

await test("online/offline and server changes reach friends; hidden servers stay hidden but pings still route", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice" });
  await ok(s, B, "state", { name: "Bob" });
  await friends(s, A, B);
  now += 200_000; // both polling sessions lapse
  await s.alarm();
  const wsB = await connect(s, B);
  const wsA = await connect(s, A);
  let st = wsB.take("friend.status");
  assert.ok(st.some((e) => e.uuid === A && e.online === true));
  await s.webSocketMessage(wsA, JSON.stringify({ op: "presence", id: 1, server: "mc.example.org", name: "Alice" }));
  assert.equal(wsA.take("result")[0].ok, true);
  st = wsB.take("friend.status");
  assert.equal(st.at(-1).server, "mc.example.org");
  await s.webSocketMessage(wsA, JSON.stringify({ op: "settings", id: 2, shareServer: false }));
  st = wsB.take("friend.status");
  assert.equal(st.at(-1).server, null);
  assert.equal((await ok(s, B, "state")).state.friends[0].server, null);
  // still routes pings
  const party = (await ok(s, A, "party.create")).state.party.id;
  await ok(s, A, "party.invite", { uuid: B });
  await ok(s, B, "party.accept", { party });
  await s.webSocketMessage(wsB, JSON.stringify({ op: "presence", id: 3, server: "mc.example.org" }));
  wsB.take();
  await s.webSocketMessage(wsA, JSON.stringify({ op: "ping", id: 4, x: 5, y: 6, z: 7, dimension: "minecraft:overworld", server: "mc.example.org" }));
  assert.equal(wsB.take("ping").length, 1);
  // A closes its socket: B hears A went offline, and A's server is forgotten
  now += 200_000;
  wsA.close(1000, "bye");
  await s.webSocketClose(wsA, 1000, "bye", true);
  st = wsB.take("friend.status");
  assert.equal(st.at(-1).online, false);
  assert.equal(s.sql.exec("SELECT server FROM profiles WHERE uuid = ?", A).one().server, null);
});

await test("WebSocket: sign-in by first message, bad tokens closed, a 4th socket replaces the oldest", async () => {
  const s = makeSocial();
  const ws = new FakeWS(s._ctx, "x");
  await s.accept(ws, null);
  await s.webSocketMessage(ws, JSON.stringify({ op: "auth", token: "tok-" + A }));
  assert.equal(ws.take("result")[0].ok, true);
  assert.equal(ws.take("hello").length, 1);
  await s.webSocketMessage(ws, JSON.stringify({ op: "state", id: 9 }));
  assert.equal(ws.take("result")[0].state.me.uuid, A);
  const bad = new FakeWS(s._ctx, "y");
  await s.accept(bad, null);
  await s.webSocketMessage(bad, JSON.stringify({ op: "auth", token: "nope" }));
  assert.equal(bad.closed.code, 4001);
  const idle = new FakeWS(s._ctx, "z");
  await s.accept(idle, null);
  now += 60_000;
  await s.alarm();
  assert.equal(idle.closed.code, 4001);
  const more = [await connect(s, A), await connect(s, A), await connect(s, A)];
  assert.equal(ws.closed.code, 4000);
  assert.ok(more.every((w) => !w.closed));
  await s.webSocketMessage(more[0], "x".repeat(5000));
  assert.equal(more[0].closed.code, 1009);
});

await test("delete my data, and 90-day retention", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice" });
  await ok(s, B, "state", { name: "Bob" });
  await ok(s, C, "state", { name: "Cara" });
  await friends(s, A, B);
  await ok(s, A, "friend.request", { uuid: C });
  await ok(s, A, "party.create");
  await ok(s, A, "report", { uuid: C, reason: "spam" });
  const wsB = await connect(s, B);
  const r = await ok(s, A, "delete");
  assert.equal(r.deleted, true);
  assert.ok(wsB.take("friend.removed").some((e) => e.uuid === A));
  for (const t of ["profiles WHERE uuid", "friends WHERE a", "friends WHERE b", "requests WHERE src", "members WHERE uuid", "reports WHERE src"])
    assert.equal(s.sql.exec(`SELECT COUNT(*) AS n FROM ${t} = ?`, A).one().n, 0, t);
  assert.equal(s.sql.exec("SELECT COUNT(*) AS n FROM parties").one().n, 0);
  // retention: C goes quiet for 91 days, B keeps playing
  now += 91 * 24 * 3600 * 1000;
  await ok(s, B, "state", {});
  await s.alarm();
  assert.equal(s.sql.exec("SELECT COUNT(*) AS n FROM profiles WHERE uuid = ?", C).one().n, 0);
  assert.equal(s.sql.exec("SELECT COUNT(*) AS n FROM profiles WHERE uuid = ?", B).one().n, 1);
});

await test("worker routing: token checked, x-evade-uuid can't be forged, presence still works", async () => {
  const social = makeSocial();
  const forwarded = [];
  const icons = { verify: async (t) => (t.startsWith("tok-") ? t.slice(4) : null), seen: async () => {}, hide: async () => {} };
  const env = {
    ICONS: { idFromName: () => "g", get: () => icons },
    SOCIAL: { idFromName: () => "g", get: () => ({ http: (u, op, a) => social.http(u, op, a), fetch: async (r) => { forwarded.push(r); return new Response("ok"); } }) },
    ASSETS: { fetch: async () => new Response("site") },
  };
  const call = (path, init) => worker.default.fetch(new Request("https://evadeclient.com" + path, init), env);
  let r = await call("/api/social/state", { method: "POST", body: "{}" });
  assert.equal(r.status, 401);
  r = await call("/api/social/state", { method: "POST", body: JSON.stringify({ name: "Alice" }), headers: { authorization: "Bearer tok-" + A } });
  assert.equal(r.status, 200);
  assert.equal((await r.json()).state.me.name, "Alice");
  r = await call("/api/social/friend/request", { method: "POST", body: JSON.stringify({ name: "Nobody" }), headers: { authorization: "Bearer tok-" + A } });
  assert.equal(r.status, 404);
  r = await call("/api/social/ws", { headers: { upgrade: "websocket", "x-evade-uuid": B } });
  assert.equal(r.status, 200);
  assert.equal(forwarded.at(-1).headers.get("x-evade-uuid"), null);
  r = await call("/api/social/ws", { headers: { upgrade: "websocket", authorization: "Bearer tok-" + A } });
  assert.equal(forwarded.at(-1).headers.get("x-evade-uuid"), A);
  assert.equal(forwarded.at(-1).headers.get("authorization"), null);
  r = await call("/api/social/ws", { headers: { upgrade: "websocket", authorization: "Bearer bad" } });
  assert.equal(r.status, 401);
  r = await call("/api/presence", { method: "POST", body: "{}", headers: { authorization: "Bearer tok-" + A } });
  assert.equal(r.status, 200);
  r = await call("/api/presence", { method: "POST", body: "{}" });
  assert.equal(r.status, 401);
});

console.log(`\n${passed} passed` + (process.exitCode ? ", some FAILED" : ""));
