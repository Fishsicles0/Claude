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
writeFileSync(join(tmp, "index.mjs"), src.replace('from "./cosmetics.js"', 'from "./cosmetics.mjs"'));
writeFileSync(join(tmp, "cosmetics.mjs"), readFileSync(join(here, "..", "cosmetics.js"), "utf8"));
globalThis.WebSocketRequestResponsePair = class { constructor(a, b) { this.a = a; this.b = b; } };
const worker = await import(pathToFileURL(join(tmp, "index.mjs")).href);
const { EvadeSocial, normServer, chatText, worldAddress, discoverList } = worker;

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

await test("chatText: plain text only, 256 characters at most", () => {
  assert.equal(chatText("  hi   there \n you "), "hi there you");
  assert.equal(chatText("§chello‮!"), "chello !");
  assert.equal(chatText("x".repeat(256)).length, 256);
  assert.equal([...chatText("\u{1F600}".repeat(256))].length, 256); // counted in characters, not UTF-16 units
  for (const bad of ["", "   ", "\u0000\u0001", "x".repeat(257), 5, null, undefined, {}, ["a"]]) {
    assert.throws(() => chatText(bad), (e) => e.status === 400, String(bad));
  }
});

await test("messages: friends only, online only, blocked never, nothing stored", async () => {
  const s = makeSocial();
  for (const [p, n] of [[A, "Alice"], [B, "Bob"], [C, "Cara"]]) await ok(s, p, "state", { name: n });
  await friends(s, A, B);
  now += 200_000; // polling sessions lapse: nobody is online
  await s.alarm();
  await fails(s, A, "msg", { uuid: B, text: "hi" }, 409); // offline: not kept for later
  const wsB = await connect(s, B);
  wsB.take();
  const r = await ok(s, A, "msg", { uuid: B, text: "  hello §cBob " });
  assert.equal(r.delivered, true);
  const got = wsB.take("msg");
  assert.equal(got.length, 1);
  assert.deepEqual({ ...got[0], at: 0 }, { type: "msg", from: { uuid: A, name: "Alice" }, text: "hello cBob", at: 0 });
  await fails(s, A, "msg", { uuid: C, text: "hi" }, 404); // not a friend
  await fails(s, A, "msg", { uuid: A, text: "hi" }, 400);
  await fails(s, A, "msg", { uuid: B, text: "x".repeat(257) }, 400);
  await fails(s, A, "msg", { uuid: B, text: "" }, 400);
  await fails(s, A, "msg", { uuid: "nope", text: "hi" }, 400);
  // nothing about messages is in storage
  const tables = s.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table'").toArray().map((t) => t.name);
  for (const t of tables) {
    for (const row of s.sql.exec(`SELECT * FROM ${t}`).toArray()) assert.ok(!JSON.stringify(row).includes("hello"), t + " holds a message");
  }
  // blocked: refused like a stranger, and nothing arrives
  await ok(s, B, "block", { uuid: A });
  now += 1000;
  await fails(s, A, "msg", { uuid: B, text: "hi" }, 404);
  assert.equal(wsB.take("msg").length, 0);
});

await test("messages are rate limited (0.3 s apart, 8 per 10 s)", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice" });
  await ok(s, B, "state", { name: "Bob" });
  await friends(s, A, B);
  const wsB = await connect(s, B);
  await ok(s, A, "msg", { uuid: B, text: "1" });
  const fast = await fails(s, A, "msg", { uuid: B, text: "2" }, 429);
  assert.ok(fast.retryAfter > 0 && fast.retryAfter <= 300);
  for (let i = 0; i < 7; i++) { now += 400; await ok(s, A, "msg", { uuid: B, text: "m" + i }); }
  now += 400;
  await fails(s, A, "msg", { uuid: B, text: "too many" }, 429);
  now += 10_000;
  await ok(s, A, "msg", { uuid: B, text: "again" });
  assert.equal(wsB.take("msg").length, 9);
});

await test("party chat reaches online members (not blocked ones); polling members get it with /state", async () => {
  const s = makeSocial();
  for (const [p, n] of [[A, "Alice"], [B, "Bob"], [C, "Cara"], [D, "Dex"]]) await ok(s, p, "state", { name: n });
  await friends(s, A, B); await friends(s, A, C); await friends(s, A, D);
  await fails(s, A, "party.msg", { text: "anyone?" }, 409); // no party
  const party = (await ok(s, A, "party.create")).state.party.id;
  for (const p of [B, C, D]) { await ok(s, A, "party.invite", { uuid: p }); await ok(s, p, "party.accept", { party }); }
  now += 200_000;
  await s.alarm(); // everyone's polling lapses
  const wsB = await connect(s, B), wsC = await connect(s, C);
  await ok(s, D, "state", {}); // D polls
  await ok(s, C, "block", { uuid: A });
  [wsB, wsC].forEach((w) => w.take());
  const r = await ok(s, A, "party.msg", { text: "gg" });
  assert.equal(r.delivered, 2); // B by socket, D by poll; C blocked A
  assert.equal(wsB.take("party.msg")[0].text, "gg");
  assert.equal(wsC.take("party.msg").length, 0);
  const st = await ok(s, D, "state", {});
  assert.equal(st.events.length, 1);
  assert.equal(st.events[0].type, "party.msg");
  assert.equal((await ok(s, D, "state", {})).events.length, 0); // only once
  // a member who isn't the leader can't summon; the leader can, to a public server only
  await fails(s, B, "party.summon", { server: "play.example.net" }, 403);
  await fails(s, A, "party.summon", { server: "192.168.1.4" }, 400);
  await fails(s, A, "party.summon", {}, 400);
  const sum = await ok(s, A, "party.summon", { server: "Play.Example.net:25565" });
  assert.equal(sum.delivered, 2);
  const ev = wsB.take("party.summon");
  assert.equal(ev.length, 1);
  assert.deepEqual({ ...ev[0], at: 0 }, { type: "party.summon", from: { uuid: A, name: "Alice" }, server: "play.example.net", at: 0 });
  await fails(s, A, "party.summon", { server: "play.example.net" }, 429); // once per 15 s
  now += 16_000;
  await ok(s, A, "party.summon", { server: "play.example.net" });
});

await test("older clients: unknown ops are a 404 and old ops answer as before", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice" });
  await fails(s, A, "party.teleport", {}, 404);
  const st = await ok(s, A, "state", {});
  assert.ok(Array.isArray(st.pings) && Array.isArray(st.events) && st.state.me.uuid === A);
  const ws = await connect(s, A);
  await s.webSocketMessage(ws, JSON.stringify({ op: "msg", id: 3, uuid: B, text: "x" }));
  const res = ws.take("result")[0];
  assert.equal(res.ok, false);
  assert.equal(res.id, 3);
});

// ---------------------------------------------------------------- v2.5: Open to Friends, presence, backup, discover

await test("worldAddress: only e4mc tunnel hosts", () => {
  assert.equal(worldAddress("Quiet-Fox.Eu.E4MC.link"), "quiet-fox.eu.e4mc.link");
  assert.equal(worldAddress("a-b.us.e4mc.link:25565"), "a-b.us.e4mc.link");
  assert.equal(worldAddress("a-b.us.e4mc.link:25570"), "a-b.us.e4mc.link:25570");
  for (const bad of ["e4mc.link", "play.example.net", "evil.e4mc.link.example.com", "x.e4mc.links", "-x.e4mc.link",
    "x.e4mc.link:0", "x.e4mc.link:70000", "x..e4mc.link", "", null, 5, "a b.e4mc.link", "x.e4mc.link/path"]) assert.equal(worldAddress(bad), null, String(bad));
});

await test("world.invite: friends and party only, online only, blocked never, nothing stored, rate limited", async () => {
  const s = makeSocial();
  for (const [u, n] of [[A, "Alice"], [B, "Bob"], [C, "Cara"], [D, "Dan"]]) await ok(s, u, "state", { name: n });
  await friends(s, A, B);
  await friends(s, A, C);
  const wsB = await connect(s, B), wsC = await connect(s, C), wsD = await connect(s, D);
  await ok(s, C, "block", { uuid: A });
  // D is not a friend: never reached. C blocked A: never reached.
  const r = await ok(s, A, "world.invite", { uuids: [B, C, D, A], address: "Quiet-Fox.eu.e4mc.link", world: "My §cWorld\n" });
  assert.equal(r.delivered, 1);
  const ev = wsB.take("world.invite");
  assert.equal(ev.length, 1);
  assert.equal(ev[0].address, "quiet-fox.eu.e4mc.link");
  assert.equal(ev[0].world, "My cWorld");
  assert.deepEqual(ev[0].from, { uuid: A, name: "Alice" });
  assert.equal(wsC.take("world.invite").length, 0);
  assert.equal(wsD.take("world.invite").length, 0);
  // only e4mc addresses, a list of people, rate limited
  now += 10_000;
  await fails(s, A, "world.invite", { uuids: [B], address: "play.example.net" }, 400);
  await fails(s, A, "world.invite", { uuids: [], address: "x.e4mc.link" }, 400);
  await fails(s, A, "world.invite", { uuids: Array(17).fill(B), address: "x.e4mc.link" }, 400);
  await ok(s, A, "world.invite", { uuids: [B], address: "x.e4mc.link" });
  await fails(s, A, "world.invite", { uuids: [B], address: "x.e4mc.link" }, 429);
  // a party member who isn't a friend is reached; a polling one gets it with /state
  now += 10_000;
  wsD.close(1000, "bye");
  s.socketGone(wsD);
  await ok(s, A, "party.invite", { uuid: B });
  await ok(s, B, "party.accept", { party: (await ok(s, B, "state", {})).state.invites[0].party });
  const sp = s.sql;
  assert.ok(sp);
  await ok(s, D, "state", { name: "Dan" }); // D polls now
  const st = await ok(s, A, "state", {});
  const party = st.state.party.id;
  // D joins A's party through an invite from B? only friends can be invited: make A and D friends, invite, accept, then unfriend
  await friends(s, A, D);
  await ok(s, A, "party.invite", { uuid: D });
  await ok(s, D, "party.accept", { party });
  await ok(s, A, "friend.remove", { uuid: D });
  now += 10_000;
  const r2 = await ok(s, A, "world.invite", { uuids: [D], address: "x.e4mc.link" });
  assert.equal(r2.delivered, 1);
  const evs = (await ok(s, D, "state", {})).events.filter((e) => e.type === "world.invite");
  assert.equal(evs.length, 1);
  assert.equal(evs[0].address, "x.e4mc.link");
  // nothing about invites is written down
  const tables = s.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table'").toArray().map((t) => t.name);
  for (const t of tables) for (const row of s.sql.exec(`SELECT * FROM ${t}`).toArray()) assert.ok(!JSON.stringify(row).includes("e4mc"), t);
});

await test("presence: activity and since reach friends, cleared offline, old clients unaffected", async () => {
  const s = makeSocial();
  await ok(s, A, "state", { name: "Alice" });
  await ok(s, B, "state", { name: "Bob" });
  await friends(s, A, B);
  const wsB = await connect(s, B);
  wsB.take();
  const wsA = await connect(s, A);
  const since = now - 600_000;
  await s.webSocketMessage(wsA, JSON.stringify({ op: "presence", id: 1, server: "play.example.net", activity: "server", since }));
  let st = wsB.take("friend.status");
  assert.equal(st.at(-1).activity, "server");
  assert.equal(st.at(-1).activeSince, since);
  let f = (await ok(s, B, "state", {})).state.friends[0];
  assert.equal(f.activity, "server");
  assert.equal(f.activeSince, since);
  // AFK, then a bad value is ignored (not refused), a far-future "since" becomes now
  await s.webSocketMessage(wsA, JSON.stringify({ op: "presence", id: 2, activity: "afk", since: now + 1e9 }));
  assert.equal(wsA.take("result").at(-1).ok, true);
  f = (await ok(s, B, "state", {})).state.friends[0];
  assert.equal(f.activity, "afk");
  assert.equal(f.activeSince, now);
  await s.webSocketMessage(wsA, JSON.stringify({ op: "presence", id: 3, activity: "flying" }));
  assert.equal(wsA.take("result").at(-1).ok, true);
  assert.equal((await ok(s, B, "state", {})).state.friends[0].activity, "afk");
  // an older client's presence (no activity field) leaves it alone; null clears it
  await s.webSocketMessage(wsA, JSON.stringify({ op: "presence", id: 4, server: null }));
  assert.equal((await ok(s, B, "state", {})).state.friends[0].activity, "afk");
  await s.webSocketMessage(wsA, JSON.stringify({ op: "presence", id: 5, activity: null }));
  assert.equal((await ok(s, B, "state", {})).state.friends[0].activity, undefined);
  // going offline forgets it
  await s.webSocketMessage(wsA, JSON.stringify({ op: "presence", id: 6, activity: "menus" }));
  wsA.close(1000, "bye");
  await s.webSocketClose(wsA, 1000, "bye");
  now += 100_000; // A's earlier /state (polling) lapses too
  await s.alarm();
  f = (await ok(s, B, "state", {})).state.friends[0];
  assert.equal(f.online, false);
  assert.equal(f.activity, undefined);
  assert.equal(s.sql.exec("SELECT COUNT(*) AS n FROM activity").one().n, 0);
});

const blob = (n) => Buffer.alloc(n, 7).toString("base64");
const SALT = Buffer.alloc(16, 1).toString("base64");

await test("backup: put, info, get, delete; caps and validation; one per account", async () => {
  const s = makeSocial();
  const bk = async (u, op, a, status = 200) => { const r = await s.backup(u, op, a); assert.equal(r.status, status, op + " " + JSON.stringify(r.body)); return r.body; };
  assert.equal((await bk(A, "info", {})).backup.exists, false);
  await bk(A, "get", {}, 404);
  const put = await bk(A, "put", { blob: blob(5000), salt: SALT, iters: 200000, format: 1, label: "Laptop\u0000" });
  assert.equal(put.backup.exists, true);
  assert.equal(put.backup.label, "Laptop");
  const info = (await bk(A, "info", {})).backup;
  assert.equal(info.size, blob(5000).length);
  assert.equal(info.salt, SALT);
  assert.equal(info.expires, now + 90 * 24 * 3600 * 1000);
  const got = await bk(A, "get", {});
  assert.equal(got.blob, blob(5000));
  assert.equal((await bk(B, "info", {})).backup.exists, false); // someone else's is invisible
  // replacing keeps one row
  await bk(A, "put", { blob: blob(100), salt: SALT, iters: 200000 });
  assert.equal(s.sql.exec("SELECT COUNT(*) AS n FROM backups").one().n, 1);
  // validation
  await bk(A, "put", { blob: "not base64!!", salt: SALT, iters: 200000 }, 400);
  await bk(A, "put", { blob: blob(10), salt: SALT, iters: 200000 }, 400);
  await bk(A, "put", { blob: blob(300 * 1024), salt: SALT, iters: 200000 }, 413);
  await bk(A, "put", { blob: blob(100), salt: "x", iters: 200000 }, 400);
  await bk(A, "put", { blob: blob(100), salt: SALT, iters: 10 }, 400);
  await bk(A, "nope", {}, 404);
  assert.equal((await bk(A, "delete", {})).deleted, true);
  assert.equal((await bk(A, "info", {})).backup.exists, false);
});

await test("backup: rate limited, deleted with social data and after 90 days unused", async () => {
  const s = makeSocial();
  const bk = (u, op, a) => s.backup(u, op, a);
  for (let i = 0; i < 30; i++) { now += 1000; assert.equal((await bk(A, "put", { blob: blob(100), salt: SALT, iters: 200000 })).status, 200); }
  assert.equal((await bk(A, "put", { blob: blob(100), salt: SALT, iters: 200000 })).status, 429);
  // Delete my social data deletes the backup too
  await ok(s, A, "state", { name: "Alice" });
  await ok(s, A, "delete", {});
  assert.equal((await bk(A, "info", {})).body.backup.exists, false);
  // retention: 90 days without a backup or restore
  await bk(B, "put", { blob: blob(100), salt: SALT, iters: 200000 });
  now += 89 * 24 * 3600 * 1000;
  assert.equal((await bk(B, "get", {})).status, 200); // a restore counts as use
  now += 89 * 24 * 3600 * 1000;
  s.sweep(now);
  assert.equal((await bk(B, "info", {})).body.backup.exists, true);
  now += 2 * 24 * 3600 * 1000;
  s.sweep(now);
  assert.equal((await bk(B, "info", {})).body.backup.exists, false);
});

await test("worker routing: /api/backup needs a token and caps the body; /api/discover is a GET list", async () => {
  const social = makeSocial();
  const icons = { verify: async (t) => (t.startsWith("tok-") ? t.slice(4) : null) };
  const env = {
    ICONS: { idFromName: () => "g", get: () => icons },
    SOCIAL: { idFromName: () => "g", get: () => ({ http: (u, op, a) => social.http(u, op, a), backup: (u, op, a) => social.backup(u, op, a) }) },
    ASSETS: { fetch: async () => new Response("site") },
  };
  const call = (path, init) => worker.default.fetch(new Request("https://evadeclient.com" + path, init), env);
  const auth = { authorization: "Bearer tok-" + A };
  let r = await call("/api/backup/info", { method: "POST", body: "{}" });
  assert.equal(r.status, 401);
  r = await call("/api/backup/info", { method: "GET", headers: auth });
  assert.equal(r.status, 405);
  r = await call("/api/backup/put", { method: "POST", headers: auth, body: JSON.stringify({ blob: blob(1000), salt: SALT, iters: 200000 }) });
  assert.equal(r.status, 200);
  r = await call("/api/backup/get", { method: "POST", headers: auth, body: "{}" });
  assert.equal((await r.json()).blob, blob(1000));
  r = await call("/api/backup/put", { method: "POST", headers: auth, body: JSON.stringify({ blob: blob(400 * 1024), salt: SALT, iters: 200000 }) });
  assert.equal(r.status, 413);
  r = await call("/api/backup/put", { method: "POST", headers: auth, body: "[1]" });
  assert.equal(r.status, 400);
  r = await call("/api/discover", { method: "GET" });
  assert.equal(r.status, 200);
  const d = await r.json();
  assert.ok(Array.isArray(d.servers) && d.servers.length === discoverList().length);
  for (const sv of d.servers) {
    assert.ok(typeof sv.name === "string" && sv.name.length <= 32);
    assert.equal(normServer(sv.address), sv.address, "public address " + sv.address);
    assert.equal(typeof sv.evade, "boolean");
  }
  r = await call("/api/discover", { method: "POST", body: "{}" });
  assert.equal(r.status, 405);
  // the old endpoints still answer as before
  r = await call("/api/social/state", { method: "POST", body: "{}", headers: auth });
  assert.equal(r.status, 200);
});

console.log(`\n${passed} passed` + (process.exitCode ? ", some FAILED" : ""));
