// evadeclient.com: serves the static site from ./site, plus a small API for Evade icons in the tab list.
//
// Icons can't be faked: a player proves which Minecraft account they own with Mojang's own "join server"
// handshake (the client asks Mojang to join a one-time id from us, then we ask Mojang who joined it). The
// player's login token only ever goes to Mojang. We then sign a short-lived token for that UUID.
// The id must be one we issued in the last minute and works once, so a game server that sees a player's join
// (or anyone replaying an old one) can't turn it into a token for that player.
//
//   POST /api/auth/challenge            -> { serverId }   (one-time, valid for 60 seconds)
//   POST /api/auth/verify {username, serverId} -> { token, uuid, name, expiresIn }   (checks Mojang hasJoined)
//   (both /api/auth/* calls are rate limited per IP address when the AUTH_RL binding exists; see wrangler.jsonc)
//   POST /api/presence  (Bearer token)  -> marks the player as an active Evade user
//   POST /api/presence/hide (Bearer)   -> stops showing the player's icon right away
//   POST /api/icons {uuids: [...]}      -> { icons: { uuid: "evade" }, ttl }   (only active Evade users)
//   GET  /api/discover                  -> { servers: [{name, address, description, tags, evade}], ttl }  (curated list;
//                                          the client pings them itself and never joins on its own)
//   POST /api/backup/info|get|put|delete (Bearer) -> opt-in cloud backup of Evade profiles, HUD layout and waypoints,
//                                          encrypted on the player's PC before upload (see the EvadeSocial comment)
//
// Friends, parties and party pings (/api/social/*, Bearer token from /api/auth/*) live in the EvadeSocial Durable
// Object below; its header comment lists the endpoints and the WebSocket messages.
//
// Cosmetics and the (not yet open) shop: /api/cosmetics/*, /api/shop/*, /api/partner/*, /api/admin/* in
// worker/cosmetics.js (its header lists the endpoints); their data lives in cos_* tables of the EvadeSocial object.
//
// Everyone gets the same free icon: there are no tiers or paid extras.
import { DurableObject } from "cloudflare:workers";
import { cosmeticsRoute, CosmeticsStore } from "./cosmetics.js";

const HEADERS = {
  "content-type": "application/json",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
};
const json = (body, status = 200, extra) =>
  new Response(JSON.stringify(body), { status, headers: extra ? { ...HEADERS, ...extra } : HEADERS });
const UUID = /^[0-9a-f]{32}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;
const SERVER_ID = /^[0-9a-f]{40}$/; // exactly what /api/auth/challenge hands out
const ACTIVE_MS = 15 * 60 * 1000; // counts as an Evade user for 15 min after the last heartbeat
const TOKEN_MS = 24 * 60 * 60 * 1000;
const CHALLENGE_MS = 60 * 1000; // a sign-in id must be used within a minute
const MAX_CHALLENGES = 10000; // outstanding sign-in ids; above this, new sign-ins wait (keeps the table small)

const hex = (n) => [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, "0")).join("");
const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
/** Mojang's session server refuses requests without a normal User-Agent. */
const MOJANG_HEADERS = { "user-agent": "EvadeClient-Icons/1.0 (+https://evadeclient.com)", accept: "application/json" };
/**
 * Mojang's player-certificate signing keys (api.minecraftservices.com/publickeys, "playerCertificateKeys"). Every
 * Microsoft account gets a chat-signing key pair whose public half Mojang signs with one of these. Mojang blocks
 * requests from Cloudflare, so the keys are built in rather than fetched; update them if Mojang ever rotates them.
 */
const MOJANG_CERT_KEYS = [
  "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAylB4B6m5lz7jwrcFz6Fd/fnfUhcvlxsTSn5kIK/2aGG1C3kMy4VjhwlxF6BFUSnfxhNswPjh3ZitkBxEAFY25uzkJFRwHwVA9mdwjashXILtR6OqdLXXFVyUPIURLOSWqGNBtb08EN5fMnG8iFLgEJIBMxs9BvF3s3/FhuHyPKiVTZmXY0WY4ZyYqvoKR+XjaTRPPvBsDa4WI2u1zxXMeHlodT3lnCzVvyOYBLXL6CJgByuOxccJ8hnXfF9yY4F0aeL080Jz/3+EBNG8RO4ByhtBf4Ny8NQ6stWsjfeUIvH7bU/4zCYcYOq4WrInXHqS8qruDmIl7P5XXGcabuzQstPf/h2CRAUpP/PlHXcMlvewjmGU6MfDK+lifScNYwjPxRo4nKTGFZf/0aqHCh/EAsQyLKrOIYRE0lDG3bzBh8ogIMLAugsAfBb6M3mqCqKaTMAf/VAjh5FFJnjS+7bE+bZEV0qwax1CEoPPJL1fIQjOS8zj086gjpGRCtSy9+bTPTfTR/SJ+VUB5G2IeCItkNHpJX2ygojFZ9n5Fnj7R9ZnOM+L8nyIjPu3aePvtcrXlyLhH/hvOfIOjPxOlqW+O5QwSFP4OEcyLAUgDdUgyW36Z5mB285uKW/ighzZsOTevVUG2QwDItObIV6i8RCxFbN2oDHyPaO5j1tTaBNyVt8CAwEAAQ==",
  "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAt4t9NPuu7cktclnaH7eZj0omkLcJHeLz5MKsyJEntHZ0INtuBjSSul3Pp3pBeJN8k3ADdcdBLUN90bcAi7WsQqTx3Ft363q3W7TbM8j2iTEdp/0uVspoRt/DP1tkaWFs/w2WwUv9jbVoBUzfUc4pSTIxRwdjmqjZQfvjwKNDbOx3IhP2H0WXodbISejPi1wBZqNW4m1rnZAXp/EpUguxA8mobCa4vUCBkyFDyXdl69/wUSJHyCPmgcMJ364OlAhIqtwVPShBZObvrK/f0BYk6ShJD3N7TFDatSYsIIdcTKRknaIm91s+EsMrdB9U4Yw+ZJ/pyCB4S3vk8zfDCnb0DWIxYH3/EMzaxl77djmTmMzi/JDITup5z3jfWtRZmrAhU2/+W5IO5hEpo3/bCS9PXIY5xb41Lmp2ZO8dXKtyD66Chchy0W129n8vPl2GIruOdrxsjZAHnneyAb9jm0uaGaphwnEnuecX/qgHY6ZMtayvLLsPst8PO6R1vufMy8WqjK+j7LnC1krL7CPDg0NEhyQTmw5l+NCNjSlvB1juM9V4PARg0bYCOkGXm7ydRCjSSH8CJXZpwnd5cBB5WKAX3KPzutRgMi/LFwNSMZzFuUyXaYOZPpD259yqph1LmGqegEdDriACVU+dVEONFMm8eIuBofe7ljmsAFKW9BINwK0CAwEAAQ=="
];
const b64bytes = (s) => Uint8Array.from(atob(String(s)), (c) => c.charCodeAt(0));
const normUuid = (u) => (typeof u === "string" ? u.replace(/-/g, "").toLowerCase() : "");

async function body(req) {
  if (req.method !== "POST") throw new HttpError(405, "use POST");
  const text = await req.text();
  if (text.length > 16384) throw new HttpError(413, "too large");
  let v;
  try { v = text ? JSON.parse(text) : {}; } catch { throw new HttpError(400, "bad json"); }
  if (!v || typeof v !== "object") throw new HttpError(400, "bad json");
  return v;
}
class HttpError extends Error { constructor(status, msg) { super(msg); this.status = status; } }

/** The UUID a request's "Authorization: Bearer <token>" was signed for, or null (no token, bad or expired one). */
async function bearerUuid(req, store) {
  const auth = req.headers.get("authorization") || "";
  return auth.startsWith("Bearer ") ? await store.verify(auth.slice(7)) : null;
}

/** True when this caller is over the sign-in rate limit (no binding configured, or the limiter failing, means no limit). */
async function limited(limiter, req) {
  if (!limiter) return false;
  try {
    const { success } = await limiter.limit({ key: "auth:" + (req.headers.get("cf-connecting-ip") || "unknown") });
    return !success;
  } catch (e) {
    console.error("rate limit", e);
    return false;
  }
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (!url.pathname.startsWith("/api/")) return env.ASSETS.fetch(req);
    if (req.method === "OPTIONS") {
      return new Response(null, { headers: { ...HEADERS, "access-control-allow-methods": "GET, POST, OPTIONS",
        "access-control-allow-headers": "content-type, authorization" } });
    }
    if (url.pathname.startsWith("/api/auth/") && await limited(env.AUTH_RL, req)) {
      return json({ error: "too many sign-in attempts; try again in a minute" }, 429, { "retry-after": "60" });
    }
    const store = env.ICONS.get(env.ICONS.idFromName("global"));
    try {
      if (url.pathname.startsWith("/api/social/")) return await socialRoute(req, env, url, store);
      if (/^\/api\/(cosmetics|shop|partner|admin)\//.test(url.pathname)) return await cosmeticsRoute(req, env, url, store);
      if (url.pathname.startsWith("/api/backup/")) return await backupRoute(req, env, url, store);
      if (url.pathname === "/api/discover") {
        if (req.method !== "GET") throw new HttpError(405, "use GET");
        return json({ servers: DISCOVER, ttl: 3600 }, 200, { "cache-control": "public, max-age=600" });
      }
      switch (url.pathname) {
        case "/api/health": {
          if (url.searchParams.get("mojang") !== "1") return json({ ok: true });
          // Can this worker reach Mojang's session server? An unknown sign-in id should get 204 (no content).
          const base = env.MOJANG_SESSION_URL || "https://sessionserver.mojang.com";
          try {
            const r = await fetch(base + "/session/minecraft/hasJoined?username=Evade&serverId=" + hex(20), { headers: MOJANG_HEADERS });
            let keys = "unreachable";
            try { keys = (await fetch("https://api.minecraftservices.com/publickeys", { headers: MOJANG_HEADERS })).status; } catch {}
            return json({ ok: r.status === 204 || r.status === 200, mojang: r.status, publickeys: keys });
          } catch (e) {
            return json({ ok: false, mojang: "unreachable" });
          }
        }

        case "/api/auth/challenge": {
          await body(req);
          const serverId = await store.issue();
          if (!serverId) throw new HttpError(503, "too many sign-ins right now; try again shortly");
          return json({ serverId });
        }

        case "/api/auth/verify": {
          const { username, serverId } = await body(req);
          if (typeof username !== "string" || typeof serverId !== "string"
            || !NAME.test(username) || !SERVER_ID.test(serverId)) throw new HttpError(400, "bad request");
          // Use the id up before asking Mojang, so each one can be checked once, however that check turns out.
          if (!(await store.consume(serverId))) throw new HttpError(401, "challenge expired; try again");
          // No &ip= check: a player can reach us over IPv6 and Mojang over IPv4, which would fail real sign-ins.
          const base = env.MOJANG_SESSION_URL || "https://sessionserver.mojang.com";
          let r;
          try {
            r = await fetch(base + "/session/minecraft/hasJoined?username=" + encodeURIComponent(username)
              + "&serverId=" + encodeURIComponent(serverId), { headers: MOJANG_HEADERS });
          } catch {
            throw new HttpError(502, "couldn't reach Mojang; try again");
          }
          if (r.status === 204) throw new HttpError(401, "Mojang did not confirm this account");
          if (r.status !== 200) throw new HttpError(502, "Mojang's session server answered " + r.status + "; try again");
          const profile = await r.json();
          const uuid = normUuid(profile.id);
          if (!UUID.test(uuid)) throw new HttpError(401, "Mojang did not confirm this account");
          return json({ token: await store.sign(uuid), uuid, name: profile.name, expiresIn: TOKEN_MS / 1000 });
        }

        case "/api/auth/key": {
          // Proof without asking Mojang: the client's Mojang-signed chat key, plus our one-time id signed with it.
          const { uuid: rawUuid, expiresAt, publicKey, keySignature, serverId, signature } = await body(req);
          const uuid = normUuid(rawUuid);
          if (!UUID.test(uuid) || !SERVER_ID.test(String(serverId)) || typeof publicKey !== "string"
            || typeof keySignature !== "string" || typeof signature !== "string"
            || !Number.isSafeInteger(expiresAt) || publicKey.length > 2048 || keySignature.length > 2048 || signature.length > 2048) {
            throw new HttpError(400, "bad request");
          }
          if (expiresAt <= Date.now()) throw new HttpError(401, "your Minecraft chat key has expired; restart the game");
          if (!(await store.consume(serverId))) throw new HttpError(401, "challenge expired; try again");
          let keyBytes, keySig, sig;
          try { keyBytes = b64bytes(publicKey); keySig = b64bytes(keySignature); sig = b64bytes(signature); }
          catch { throw new HttpError(400, "bad request"); }
          // What Mojang signed: UUID (16 bytes), expiry in ms (8 bytes, big-endian), then the key itself.
          const signed = new Uint8Array(24 + keyBytes.length);
          const dv = new DataView(signed.buffer);
          dv.setBigUint64(0, BigInt("0x" + uuid.slice(0, 16)));
          dv.setBigUint64(8, BigInt("0x" + uuid.slice(16)));
          dv.setBigInt64(16, BigInt(expiresAt));
          signed.set(keyBytes, 24);
          let mojangOk = false;
          for (const k of MOJANG_CERT_KEYS) {
            try {
              const mk = await crypto.subtle.importKey("spki", b64bytes(k), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"]);
              if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", mk, keySig, signed)) { mojangOk = true; break; }
            } catch { /* try the next key */ }
          }
          if (!mojangOk) throw new HttpError(401, "Mojang did not sign this key");
          let playerKey;
          try { playerKey = await crypto.subtle.importKey("spki", keyBytes, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]); }
          catch { throw new HttpError(400, "bad key"); }
          const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", playerKey, sig, new TextEncoder().encode("evade-icons:" + serverId));
          if (!ok) throw new HttpError(401, "signature doesn't match");
          return json({ token: await store.sign(uuid), uuid, expiresIn: TOKEN_MS / 1000 });
        }

        case "/api/presence": {
          await body(req);
          const uuid = await bearerUuid(req, store);
          if (!uuid) throw new HttpError(401, "sign in again");
          await store.seen(uuid);
          return json({ ok: true });
        }

        case "/api/presence/hide": {
          await body(req);
          const uuid = await bearerUuid(req, store);
          if (!uuid) throw new HttpError(401, "sign in again");
          await store.hide(uuid);
          return json({ ok: true });
        }

        case "/api/icons": {
          const { uuids } = await body(req);
          if (!Array.isArray(uuids)) throw new HttpError(400, "uuids must be a list");
          const list = [...new Set(uuids.slice(0, 200).map(normUuid).filter((u) => UUID.test(u)))];
          return json({ icons: await store.lookup(list), ttl: 300 });
        }
      }
      return json({ error: "not found" }, 404);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status);
      return json({ error: "server error" }, 500);
    }
  },
};

/** All icon state in one SQLite-backed Durable Object (created automatically on deploy). */
export class EvadeIcons extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS presence (uuid TEXT PRIMARY KEY, last_seen INTEGER NOT NULL);
                   CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
                   CREATE TABLE IF NOT EXISTS challenges (id TEXT PRIMARY KEY, expires INTEGER NOT NULL);
                   CREATE INDEX IF NOT EXISTS challenges_by_expiry ON challenges (expires);`);
  }

  /**
   * A new one-time sign-in id, good for CHALLENGE_MS, or null while MAX_CHALLENGES are outstanding.
   * Synchronous on purpose: nothing else runs in this object between the cleanup, the count and the insert.
   */
  issue() {
    const now = Date.now();
    // The index keeps this to the expired rows; the count is kept here so a busy table isn't rescanned every time.
    const gone = this.sql.exec("DELETE FROM challenges WHERE expires <= ? RETURNING id", now).toArray().length;
    this._challenges = this._challenges === undefined
      ? this.sql.exec("SELECT COUNT(*) AS n FROM challenges").one().n
      : Math.max(0, this._challenges - gone);
    if (this._challenges >= MAX_CHALLENGES) return null;
    const id = hex(20);
    this.sql.exec("INSERT INTO challenges (id, expires) VALUES (?, ?)", id, now + CHALLENGE_MS);
    this._challenges++;
    return id;
  }

  /** Uses up a sign-in id: true only for an id we issued, the first time, before it expires. */
  consume(id) {
    if (typeof id !== "string") return false;
    const used = this.sql.exec("DELETE FROM challenges WHERE id = ? AND expires > ? RETURNING id", id, Date.now()).toArray().length > 0;
    if (used && this._challenges > 0) this._challenges--;
    return used;
  }

  /** The token-signing key: generated once and kept in this object's storage (never leaves it). */
  async hmacKey() {
    if (!this._key) {
      let row = this.sql.exec("SELECT v FROM meta WHERE k = 'hmac'").toArray()[0];
      if (!row) { row = { v: hex(32) }; this.sql.exec("INSERT INTO meta (k, v) VALUES ('hmac', ?)", row.v); }
      this._key = await crypto.subtle.importKey("raw", new TextEncoder().encode(row.v), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    }
    return this._key;
  }

  async sign(uuid) {
    const payload = uuid + "." + (Date.now() + TOKEN_MS);
    return payload + "." + await this.sign0(payload);
  }

  async verify(token) {
    const parts = String(token).split(".");
    if (parts.length !== 3 || !UUID.test(parts[0]) || !(Number(parts[1]) > Date.now())) return null;
    const expected = await this.sign0(parts[0] + "." + parts[1]);
    return expected === parts[2] ? parts[0] : null;
  }

  async sign0(payload) {
    return b64url(await crypto.subtle.sign("HMAC", await this.hmacKey(), new TextEncoder().encode(payload)));
  }

  seen(uuid) {
    const now = Date.now();
    this.sql.exec("INSERT INTO presence (uuid, last_seen) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET last_seen = excluded.last_seen", uuid, now);
    // Forget players who haven't used Evade for 7 days (checked at most once an hour). See /privacy.
    if (!(this._cleaned > now - 3600 * 1000)) {
      this._cleaned = now;
      this.sql.exec("DELETE FROM presence WHERE last_seen < ?", now - 7 * 24 * 3600 * 1000);
    }
  }

  lookup(uuids) {
    const since = Date.now() - ACTIVE_MS, out = {};
    for (const u of uuids) {
      const p = this.sql.exec("SELECT last_seen FROM presence WHERE uuid = ?", u).toArray()[0];
      if (p && p.last_seen >= since) out[u] = "evade";
    }
    return out;
  }

  hide(uuid) {
    this.sql.exec("DELETE FROM presence WHERE uuid = ?", uuid);
  }
}

// ====================================================================== friends, parties and party pings
//
// Everything below is EvadeSocial: one SQLite-backed Durable Object ("global") holding social profiles, friends,
// friend requests, blocks, parties and party invites, plus the open WebSockets. Accounts are the UUIDs that
// /api/auth/* proves (the same Bearer token as /api/presence). Names are what the client reports and are only shown,
// never trusted: the client looks up a name's UUID with Mojang itself before adding someone (see /privacy).
//
// HTTP (all POST with "Authorization: Bearer <token>", JSON body; answers JSON, errors as { error } with a status):
//   /api/social/state     {name?, server?}          -> state (also marks a polling client online for 90 s)
//   /api/social/presence  {name?, server?}          -> { ok }   server: address you're playing on, or null
//   /api/social/settings  {allowRequests?: "everyone"|"nobody", shareServer?: bool} -> { ok, state }
//   /api/social/friend/request {uuid? | name?}      -> { ok, state, accepted? }  (accepts their request to you)
//   /api/social/friend/accept|decline|cancel|remove {uuid}            -> { ok, state }
//   /api/social/block {uuid? | name?} , /api/social/unblock {uuid}    -> { ok, state }
//   /api/social/report {uuid, reason}                                 -> { ok }
//   /api/social/party/create {}                                       -> { ok, state }
//   /api/social/party/invite {uuid}  (a friend; creates your party if you have none)
//   /api/social/party/accept|decline {party}, /party/leave {}, /party/kick|transfer {uuid} -> { ok, state }
//   /api/social/ping {x, y, z, dimension, server, kind?: "location"|"danger"|"loot"} -> { ok }
//   /api/social/msg {uuid, text}   (a friend who is online; plain text, 256 characters at most) -> { ok, delivered }
//   /api/social/party/msg {text}   (party chat)                                     -> { ok, delivered }
//   /api/social/party/summon {server}  (leader: "Bring party here", members choose to join) -> { ok, delivered }
//   /api/social/world/invite {uuids: [...], address, world?}  (Open to Friends: an e4mc address to chosen friends or
//                                party members who are online; never stored)                  -> { ok, delivered }
//   /api/social/delete {}                                             -> { ok, deleted }  (all your social data, and the cloud backup)
//   state/presence also take {activity?: "menus"|"singleplayer"|"server"|"afk"|null, since?: ms}: friends and party then
//   see it (as activity and activeSince) while you're online; it's forgotten when you go offline. Older clients send neither.
// WebSocket: GET /api/social/ws (wss) with the same Authorization header, or ?token=, or send
//   {"op":"auth","token":"..."} as the first message (within 30 s). The text "ping" is answered "pong" without waking
//   the object. Requests are {"op":"friend.request","id":7,...same fields as HTTP} -> {"type":"result","id":7,"ok":true,...}
//   or {"type":"result","id":7,"ok":false,"error":"...","status":429}. Pushed events:
//   hello {state} | state {state} | friend.request {from} | friend.accepted {friend} | friend.removed {uuid}
//   friend.status {uuid, name, online, server} | party.invite {party, from, size} | party.joined {member}
//   party.left {member, kicked} | party.kicked {by} | party.leader {leader} | ping {from, x, y, z, dimension, kind, at}
//   msg {from, text, at} | party.msg {from, text, at} | party.summon {from, server, at} | world.invite {from, address, world, at}
//   (friend.status and the people in state carry activity and activeSince too, when the player's client sends them)
//   where from/friend/member = {uuid, name}. A polling client gets pings in /state "pings" and the msg, party.msg,
//   party.summon and world.invite events in /state "events" (both only for its next /state, 15-30 s at most). Clients ignore types
//   they don't know, so older clients are unaffected by newer events.
//
// Pings are relayed straight to the sender's party members who are online on the same server address (normalized:
// lower case, default port 25565 dropped; private/LAN addresses never match) and are never stored. Messages, party
// chat and "Bring party here" are relayed the same way: only to friends / party members who are online right now, only
// between players who haven't blocked each other, and never written down (a polling recipient's copy waits in memory
// for its next /state, 30 s at most). Social data of an account unused for 90 days is deleted; /api/social/delete
// deletes it at once.

const SOCIAL = {
  MAX_FRIENDS: 300, MAX_PENDING: 30, MAX_BLOCKS: 1000, MAX_PARTY: 8, MAX_SOCKETS: 3,
  INVITE_MS: 10 * 60 * 1000,             // a party invite lapses after 10 minutes
  POLL_ONLINE_MS: 90 * 1000,             // a polling client counts as online this long after its last /state
  INACTIVE_MS: 90 * 24 * 3600 * 1000,    // social data is deleted after 90 days without use
  PARTY_IDLE_MS: 12 * 3600 * 1000,       // a party with nobody online for 12 hours is disbanded
  REPORT_KEEP_MS: 90 * 24 * 3600 * 1000,
  PING_GAP_MS: 1500, PING_WINDOW_MS: 10 * 1000, PING_MAX: 5,
  AUTH_GRACE_MS: 30 * 1000,              // a socket that hasn't sent its token by then is closed
  MAX_MESSAGE: 4096,
  CHAT_MAX: 256,                         // characters in one friend or party message
  CHAT_GAP_MS: 300, CHAT_WINDOW_MS: 10 * 1000, CHAT_BURST: 8, CHAT_HOUR: 600,
  SUMMON_GAP_MS: 15 * 1000, SUMMON_HOUR: 20,
  POLL_EVENT_MS: 30 * 1000,              // how long a polling client's relayed message waits for its next /state
  MAX_WORLD_INVITE: 16, WORLD_INVITE_GAP_MS: 5 * 1000, WORLD_INVITE_HOUR: 30,
};
/** Richer presence: what a player is doing (only shown to friends and party while they're online). */
const ACTIVITIES = new Set(["menus", "singleplayer", "server", "afk"]);
/** Cloud backup limits: one encrypted blob per account. */
const BACKUP = {
  MAX_BLOB: 256 * 1024,                         // bytes of encrypted backup
  MAX_BLOB_B64: Math.ceil(256 * 1024 / 3) * 4,   // the same, as base64
  MAX_BODY: 360 * 1024,                         // the whole request body
  MAX_TOTAL: 2 * 1024 * 1024 * 1024,            // every backup together (keeps the object well inside its storage)
  UNUSED_MS: 90 * 24 * 3600 * 1000,             // deleted after 90 days without a backup or restore
  PUTS_PER_DAY: 30, GETS_PER_DAY: 30,
};
const B64 = /^[A-Za-z0-9+/]*={0,2}$/;

/**
 * An Open to Friends address: an e4mc tunnel host ("name.region.e4mc.link"), lower case, optional port. Null for
 * anything else, so the invite relay can't be used to send players to an arbitrary server.
 */
export function worldAddress(s) {
  if (typeof s !== "string") return null;
  s = s.trim().toLowerCase().replace(/\.$/, "");
  if (s.length > 200) return null;
  const m = s.match(/^((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.){1,4}e4mc\.link)(?::(\d{1,5}))?$/);
  if (!m) return null;
  if (m[2] !== undefined && !(Number(m[2]) >= 1 && Number(m[2]) <= 65535)) return null;
  return m[2] === undefined || m[2] === "25565" ? m[1] : m[1] + ":" + m[2];
}
const PING_KINDS = new Set(["location", "danger", "loot"]);
const DIMENSION = /^[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}$/;
const PARTY_ID = /^[0-9a-f]{16}$/;

/**
 * A friend or party message as it is relayed: plain text with control, bidi-override and formatting (§) characters
 * removed and runs of white space made one space. Throws a 400 for anything that isn't a non-empty string of at most
 * {@code SOCIAL.CHAT_MAX} characters.
 */
export function chatText(t) {
  if (typeof t !== "string" || t.length > SOCIAL.CHAT_MAX * 4) throw new SocialError(400, "Messages are " + SOCIAL.CHAT_MAX + " characters at most");
  const s = t.replace(/[\u0000-\u001f\u007f-\u009f\u00a7\u200b-\u200f\u2028-\u202e\u2060-\u206f\ufeff\ufff9-\ufffb]/g, " ")
    .replace(/\s+/g, " ").trim();
  if (!s) throw new SocialError(400, "Type a message");
  if ([...s].length > SOCIAL.CHAT_MAX) throw new SocialError(400, "Messages are " + SOCIAL.CHAT_MAX + " characters at most");
  return s;
}

/**
 * A server address as the key pings are routed by: lower case, no trailing dot, no ":25565". Null for anything that
 * isn't a public host name or address (singleplayer, LAN and loopback addresses, which aren't the same server for
 * two different players).
 */
export function normServer(s) {
  if (typeof s !== "string") return null;
  s = s.trim().toLowerCase();
  if (!s || s.length > 260) return null;
  let host, port, m;
  if ((m = s.match(/^\[([0-9a-f:.]+)\](?::(\d{1,5}))?$/))) { host = m[1]; port = m[2]; }
  else if ((m = s.match(/^([a-z0-9._-]+?)\.?(?::(\d{1,5}))?$/))) { host = m[1]; port = m[2]; }
  else if (/^[0-9a-f:]+$/.test(s) && s.includes("::")) { host = s; }
  else return null;
  if (!host || host.length > 253 || host.startsWith(".") || host.includes("..")) return null;
  if (isPrivateHost(host)) return null;
  const p = port === undefined ? 25565 : Number(port);
  if (!(p >= 1 && p <= 65535)) return null;
  const h = host.includes(":") ? "[" + host + "]" : host;
  return p === 25565 ? h : h + ":" + p;
}

function isPrivateHost(h) {
  if (h === "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal")
    || h.endsWith(".home.arpa")) return true;
  if (!h.includes(".") && !h.includes(":")) return true; // a bare machine name
  const v4 = h.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
      || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224;
  }
  if (h.includes(":")) return h === "::1" || h === "::" || /^(fe8|fe9|fea|feb|fc|fd)/.test(h) || h.startsWith("::ffff:");
  return false;
}

class SocialError extends Error { constructor(status, msg, extra) { super(msg); this.status = status; this.extra = extra; } }

/** /api/social/*: checks the token here (the EvadeIcons object holds the signing key), then hands over to EvadeSocial. */
async function socialRoute(req, env, url, store) {
  if (!env.SOCIAL) throw new HttpError(503, "friends aren't available right now");
  const social = env.SOCIAL.get(env.SOCIAL.idFromName("global"));
  if (url.pathname === "/api/social/ws") {
    if ((req.headers.get("upgrade") || "").toLowerCase() !== "websocket") throw new HttpError(426, "expected a WebSocket");
    const token = (req.headers.get("authorization") || "").replace(/^Bearer /, "") || url.searchParams.get("token") || "";
    let uuid = null;
    if (token) {
      uuid = await store.verify(token);
      if (!uuid) throw new HttpError(401, "sign in again");
    }
    // Only this worker sets x-evade-uuid: whatever a client sent under that name is dropped first.
    const headers = new Headers(req.headers);
    headers.delete("x-evade-uuid");
    headers.delete("authorization");
    if (uuid) headers.set("x-evade-uuid", uuid);
    return social.fetch(new Request("https://social.internal/ws", { method: "GET", headers }));
  }
  const args = await body(req);
  const uuid = await bearerUuid(req, store);
  if (!uuid) throw new HttpError(401, "sign in again");
  const op = url.pathname.slice("/api/social/".length).replace(/\//g, ".");
  const r = await social.http(uuid, op, args);
  return json(r.body, r.status, r.status === 429 && r.body.retryAfter ? { "retry-after": String(Math.ceil(r.body.retryAfter / 1000)) } : undefined);
}

/**
 * /api/backup/*: opt-in cloud backup. The body can be larger than other API calls (an encrypted backup), so it has its
 * own size cap; the token is checked here and the EvadeSocial object stores the blob it can't read.
 */
async function backupRoute(req, env, url, store) {
  if (!env.SOCIAL) throw new HttpError(503, "cloud backup isn't available right now");
  if (req.method !== "POST") throw new HttpError(405, "use POST");
  const len = Number(req.headers.get("content-length") || 0);
  if (len > BACKUP.MAX_BODY) throw new HttpError(413, "That backup is too big (" + Math.round(BACKUP.MAX_BLOB / 1024) + " KB at most)");
  const text = await req.text();
  if (text.length > BACKUP.MAX_BODY) throw new HttpError(413, "That backup is too big (" + Math.round(BACKUP.MAX_BLOB / 1024) + " KB at most)");
  let args;
  try { args = text ? JSON.parse(text) : {}; } catch { throw new HttpError(400, "bad json"); }
  if (!args || typeof args !== "object" || Array.isArray(args)) throw new HttpError(400, "bad json");
  const uuid = await bearerUuid(req, store);
  if (!uuid) throw new HttpError(401, "sign in again");
  const op = url.pathname.slice("/api/backup/".length);
  const social = env.SOCIAL.get(env.SOCIAL.idFromName("global"));
  const r = await social.backup(uuid, op, args);
  return json(r.body, r.status, r.status === 429 && r.body.retryAfter ? { "retry-after": String(Math.ceil(r.body.retryAfter / 1000)) } : undefined);
}

/**
 * Discover: servers shown on the title screen's Discover strip. Curated by hand (edit this list and push); "evade"
 * marks servers that run the Evade Server API plugin. The client pings each one itself and only ever joins when the
 * player clicks Join and confirms.
 */
const DISCOVER = []; // no featured servers
/** The Discover list (a function, not the array: the Workers runtime only expects classes and functions as exports). */
export function discoverList() { return DISCOVER; }

export class EvadeSocial extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS profiles (uuid TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '',
                     name_lc TEXT NOT NULL DEFAULT '', allow_requests INTEGER NOT NULL DEFAULT 1,
                     share_server INTEGER NOT NULL DEFAULT 1, server TEXT, poll_at INTEGER NOT NULL DEFAULT 0,
                     last_seen INTEGER NOT NULL, created INTEGER NOT NULL);
                   CREATE INDEX IF NOT EXISTS profiles_by_name ON profiles (name_lc);
                   CREATE INDEX IF NOT EXISTS profiles_by_seen ON profiles (last_seen);
                   CREATE TABLE IF NOT EXISTS friends (a TEXT NOT NULL, b TEXT NOT NULL, since INTEGER NOT NULL, PRIMARY KEY (a, b));
                   CREATE TABLE IF NOT EXISTS requests (src TEXT NOT NULL, dst TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY (src, dst));
                   CREATE INDEX IF NOT EXISTS requests_by_dst ON requests (dst);
                   CREATE TABLE IF NOT EXISTS blocks (src TEXT NOT NULL, dst TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY (src, dst));
                   CREATE INDEX IF NOT EXISTS blocks_by_dst ON blocks (dst);
                   CREATE TABLE IF NOT EXISTS parties (id TEXT PRIMARY KEY, leader TEXT NOT NULL, created INTEGER NOT NULL);
                   CREATE TABLE IF NOT EXISTS members (uuid TEXT PRIMARY KEY, party TEXT NOT NULL, joined INTEGER NOT NULL);
                   CREATE INDEX IF NOT EXISTS members_by_party ON members (party);
                   CREATE TABLE IF NOT EXISTS invites (party TEXT NOT NULL, dst TEXT NOT NULL, src TEXT NOT NULL,
                     created INTEGER NOT NULL, PRIMARY KEY (party, dst));
                   CREATE INDEX IF NOT EXISTS invites_by_dst ON invites (dst);
                   CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY AUTOINCREMENT, src TEXT NOT NULL, dst TEXT NOT NULL,
                     reason TEXT NOT NULL, created INTEGER NOT NULL);
                   CREATE TABLE IF NOT EXISTS rate (k TEXT PRIMARY KEY, start INTEGER NOT NULL, n INTEGER NOT NULL);
                   CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
                   CREATE TABLE IF NOT EXISTS activity (uuid TEXT PRIMARY KEY, kind TEXT NOT NULL, since INTEGER NOT NULL);
                   CREATE TABLE IF NOT EXISTS backups (uuid TEXT PRIMARY KEY, blob TEXT NOT NULL, salt TEXT NOT NULL,
                     iters INTEGER NOT NULL, format INTEGER NOT NULL, size INTEGER NOT NULL, label TEXT NOT NULL DEFAULT '',
                     updated INTEGER NOT NULL, used INTEGER NOT NULL);
                   CREATE INDEX IF NOT EXISTS backups_by_used ON backups (used);`);
    /** Cosmetics: owned items, equipped state, orders, codes, partners (worker/cosmetics.js; cos_* tables). */
    this.cos = new CosmeticsStore(this.sql);
    /** Ping times per sender (memory only: pings are never written down). */
    this.pingTimes = new Map();
    /** Pings waiting for a polling client's next /state, by recipient (memory only, kept 15 s). */
    this.pollPings = new Map();
    /** Request counts per account for the short flood limit (memory only). */
    this.bursts = new Map();
    /** Message send times per sender (memory only: messages are never written down). */
    this.chatTimes = new Map();
    /** Messages and summons waiting for a polling client's next /state, by recipient (memory only, 30 s). */
    this.pollEvents = new Map();
    /** When each leader last brought their party somewhere (memory only). */
    this.summonTimes = new Map();
    /** When each player last sent Open to Friends invites (memory only). */
    this.worldInviteTimes = new Map();
    try { ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong")); } catch { /* older runtime */ }
    ctx.blockConcurrencyWhile(async () => {
      if (!(await ctx.storage.getAlarm())) await ctx.storage.setAlarm(Date.now() + 60 * 1000);
    });
  }

  // ------------------------------------------------------------------ entry points

  /** WebSocket upgrades forwarded by the worker (x-evade-uuid is set only by the worker, after checking the token). */
  async fetch(req) {
    if ((req.headers.get("upgrade") || "").toLowerCase() !== "websocket") return new Response("expected a WebSocket", { status: 426 });
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const uuid = req.headers.get("x-evade-uuid");
    await this.accept(server, UUID.test(uuid || "") ? uuid : null);
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Takes a server-side socket into the hibernation API, signed in already ({@code uuid}) or waiting for its token. */
  async accept(ws, uuid) {
    const now = Date.now();
    if (uuid) {
      this.trimSockets(uuid, SOCIAL.MAX_SOCKETS - 1);
      this.ctx.acceptWebSocket(ws, [uuid]);
      ws.serializeAttachment({ uuid, at: now });
      this.online(uuid, ws);
    } else {
      this.ctx.acceptWebSocket(ws, ["pending"]);
      ws.serializeAttachment({ uuid: null, at: now });
      await this.alarmBy(now + SOCIAL.AUTH_GRACE_MS + 1000);
    }
  }

  /** One HTTP request (see the header comment). Returns { status, body }. */
  async http(uuid, op, args) {
    try {
      this.flood(uuid);
      const body = await this.run(uuid, op, args || {}, false);
      if (op === "state") {
        const waiting = this.pollPings.get(uuid);
        this.pollPings.delete(uuid);
        const now = Date.now();
        body.pings = (waiting || []).filter((p) => p.at > now - 15000);
        const events = this.pollEvents.get(uuid);
        this.pollEvents.delete(uuid);
        body.events = (events || []).filter((e) => e.at > now - SOCIAL.POLL_EVENT_MS);
      }
      return { status: 200, body };
    } catch (e) {
      if (e instanceof SocialError) return { status: e.status, body: { error: e.message, ...(e.extra || {}) } };
      console.error("social", op, e);
      return { status: 500, body: { error: "server error" } };
    }
  }

  /** One cosmetics/shop request from worker/cosmetics.js (which checked tokens and signatures). Returns { status, body }. */
  async cosmetics(op, args) {
    return this.cos.run(op, args);
  }

  /** One /api/backup/* request (the worker checked the token). Returns { status, body }. */
  async backup(uuid, op, args) {
    try {
      this.flood(uuid);
      return { status: 200, body: this.runBackup(uuid, op, args || {}) };
    } catch (e) {
      if (e instanceof SocialError) return { status: e.status, body: { error: e.message, ...(e.extra || {}) } };
      console.error("backup", op, e);
      return { status: 500, body: { error: "server error" } };
    }
  }

  async webSocketMessage(ws, message) {
    const att = ws.deserializeAttachment() || {};
    if (typeof message !== "string" || message.length > SOCIAL.MAX_MESSAGE) { this.closeSocket(ws, 1009, "message too large"); return; }
    let m;
    try { m = JSON.parse(message); } catch { m = null; }
    if (!m || typeof m !== "object" || typeof m.op !== "string") { this.reply(ws, m, { ok: false, status: 400, error: "bad message" }); return; }
    if (!att.uuid) {
      // Signing in over the socket: the token goes to the EvadeIcons object, which holds the signing key.
      if (m.op !== "auth" || typeof m.token !== "string" || !this.env.ICONS) { this.closeSocket(ws, 4001, "sign in first"); return; }
      const uuid = await this.env.ICONS.get(this.env.ICONS.idFromName("global")).verify(m.token);
      if (!uuid) { this.closeSocket(ws, 4001, "sign in again"); return; }
      this.trimSockets(uuid, SOCIAL.MAX_SOCKETS - 1, ws);
      ws.serializeAttachment({ uuid, at: Date.now() });
      this.reply(ws, m, { ok: true });
      this.online(uuid, ws);
      return;
    }
    try {
      this.flood(att.uuid);
      const body = await this.run(att.uuid, m.op, m, true);
      this.reply(ws, m, { ok: true, ...body });
    } catch (e) {
      if (e instanceof SocialError) this.reply(ws, m, { ok: false, status: e.status, error: e.message, ...(e.extra || {}) });
      else { console.error("social ws", m.op, e); this.reply(ws, m, { ok: false, status: 500, error: "server error" }); }
    }
  }

  async webSocketClose(ws, code, reason) {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "bye"); } catch { /* already closed */ }
    this.socketGone(ws);
  }

  async webSocketError(ws) {
    this.socketGone(ws);
  }

  socketGone(ws) {
    const att = ws.deserializeAttachment() || {};
    if (att.uuid && !this.isOnline(att.uuid, ws)) this.offline(att.uuid);
  }

  async alarm() {
    const now = Date.now();
    // Sockets that never signed in.
    for (const ws of this.ctx.getWebSockets("pending")) {
      const att = ws.deserializeAttachment() || {};
      if (!att.uuid && att.at < now - SOCIAL.AUTH_GRACE_MS) this.closeSocket(ws, 4001, "sign in first");
    }
    // Polling clients that stopped polling go offline.
    for (const r of this.sql.exec("SELECT uuid, poll_at FROM profiles WHERE poll_at > 0 AND poll_at <= ?", now - SOCIAL.POLL_ONLINE_MS).toArray()) {
      this.sql.exec("UPDATE profiles SET poll_at = 0 WHERE uuid = ?", r.uuid);
      if (!this.isOnline(r.uuid)) this.offline(r.uuid, r.poll_at);
    }
    const nextSweep = Number(this.meta("sweep_at") || 0);
    if (now >= nextSweep) {
      this.sweep(now);
      this.setMeta("sweep_at", String(now + 6 * 3600 * 1000));
    }
    // Next: when the next polling client would time out, when a pending socket's grace ends, or the next sweep.
    let next = Number(this.meta("sweep_at"));
    const poll = this.sql.exec("SELECT MIN(poll_at) AS t FROM profiles WHERE poll_at > 0").one().t;
    if (poll) next = Math.min(next, poll + SOCIAL.POLL_ONLINE_MS + 1000);
    if (this.ctx.getWebSockets("pending").some((ws) => !(ws.deserializeAttachment() || {}).uuid)) next = Math.min(next, now + 10 * 1000);
    await this.ctx.storage.setAlarm(Math.max(next, now + 5000));
  }

  // ------------------------------------------------------------------ operations

  async run(me, op, a, viaSocket) {
    const now = Date.now();
    switch (op) {
      case "state": {
        this.touch(me, a, !viaSocket);
        return { state: this.state(me) };
      }
      case "presence": {
        this.touch(me, a, !viaSocket);
        return {};
      }
      case "settings": {
        this.profile(me);
        const sets = [];
        if (a.allowRequests !== undefined) {
          if (a.allowRequests !== "everyone" && a.allowRequests !== "nobody") throw new SocialError(400, "allowRequests is everyone or nobody");
          this.sql.exec("UPDATE profiles SET allow_requests = ? WHERE uuid = ?", a.allowRequests === "everyone" ? 1 : 0, me);
        }
        if (a.shareServer !== undefined) {
          if (typeof a.shareServer !== "boolean") throw new SocialError(400, "shareServer is true or false");
          this.sql.exec("UPDATE profiles SET share_server = ? WHERE uuid = ?", a.shareServer ? 1 : 0, me);
          sets.push("share");
        }
        if (sets.length) this.broadcastStatus(me);
        return { state: this.state(me) };
      }
      case "friend.request": {
        this.profile(me);
        this.limit(me + ":req", 20, 3600 * 1000, "too many friend requests; try again later");
        const them = this.target(a, me);
        const refuse = () => new SocialError(404, "No Evade player called " + (them.name || "that") + " is taking friend requests");
        if (!them.profile) throw refuse();
        const u = them.uuid;
        if (this.areFriends(me, u)) throw new SocialError(409, "You're already friends with " + them.profile.name);
        if (this.blockedEither(me, u)) throw refuse();
        if (this.has("requests", "src", u, "dst", me)) {
          this.makeFriends(me, u, now);
          return { state: this.state(me), accepted: true };
        }
        if (!them.profile.allow_requests) throw refuse();
        if (this.has("requests", "src", me, "dst", u)) throw new SocialError(409, "You've already sent " + them.profile.name + " a request");
        if (this.count("SELECT COUNT(*) AS n FROM friends WHERE a = ?", me) >= SOCIAL.MAX_FRIENDS) throw new SocialError(409, "You have the most friends Evade allows (" + SOCIAL.MAX_FRIENDS + ")");
        if (this.count("SELECT COUNT(*) AS n FROM friends WHERE a = ?", u) >= SOCIAL.MAX_FRIENDS) throw refuse();
        if (this.count("SELECT COUNT(*) AS n FROM requests WHERE src = ?", me) >= SOCIAL.MAX_PENDING) throw new SocialError(409, "Too many requests waiting; cancel some first");
        if (this.count("SELECT COUNT(*) AS n FROM requests WHERE dst = ?", u) >= SOCIAL.MAX_PENDING) throw refuse();
        this.sql.exec("INSERT INTO requests (src, dst, created) VALUES (?, ?, ?)", me, u, now);
        this.send(u, { type: "friend.request", from: this.who(me) });
        this.pushState(u);
        return { state: this.state(me) };
      }
      case "friend.accept": {
        const u = this.uuidArg(a);
        if (!this.has("requests", "src", u, "dst", me)) throw new SocialError(404, "That request is gone");
        if (this.count("SELECT COUNT(*) AS n FROM friends WHERE a = ?", me) >= SOCIAL.MAX_FRIENDS) throw new SocialError(409, "You have the most friends Evade allows (" + SOCIAL.MAX_FRIENDS + ")");
        if (this.count("SELECT COUNT(*) AS n FROM friends WHERE a = ?", u) >= SOCIAL.MAX_FRIENDS) throw new SocialError(409, "They have the most friends Evade allows");
        this.makeFriends(me, u, now);
        return { state: this.state(me) };
      }
      case "friend.decline": {
        const u = this.uuidArg(a);
        this.sql.exec("DELETE FROM requests WHERE src = ? AND dst = ?", u, me);
        this.pushState(u);
        return { state: this.state(me) };
      }
      case "friend.cancel": {
        const u = this.uuidArg(a);
        this.sql.exec("DELETE FROM requests WHERE src = ? AND dst = ?", me, u);
        this.pushState(u);
        return { state: this.state(me) };
      }
      case "friend.remove": {
        const u = this.uuidArg(a);
        if (!this.areFriends(me, u)) throw new SocialError(404, "You aren't friends");
        this.sql.exec("DELETE FROM friends WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)", me, u, u, me);
        this.send(u, { type: "friend.removed", uuid: me });
        this.pushState(u);
        return { state: this.state(me) };
      }
      case "block": {
        this.profile(me);
        const them = this.target(a, me);
        if (!them.uuid) throw new SocialError(404, "No Evade player called " + (them.name || "that"));
        const u = them.uuid;
        if (this.count("SELECT COUNT(*) AS n FROM blocks WHERE src = ?", me) >= SOCIAL.MAX_BLOCKS) throw new SocialError(409, "You've blocked too many players");
        this.sql.exec("INSERT OR IGNORE INTO blocks (src, dst, created) VALUES (?, ?, ?)", me, u, now);
        const wereFriends = this.areFriends(me, u);
        this.sql.exec("DELETE FROM friends WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)", me, u, u, me);
        this.sql.exec("DELETE FROM requests WHERE (src = ? AND dst = ?) OR (src = ? AND dst = ?)", me, u, u, me);
        this.sql.exec("DELETE FROM invites WHERE (src = ? AND dst = ?) OR (src = ? AND dst = ?)", me, u, u, me);
        if (wereFriends) this.send(u, { type: "friend.removed", uuid: me });
        this.pushState(u);
        return { state: this.state(me) };
      }
      case "unblock": {
        const u = this.uuidArg(a);
        this.sql.exec("DELETE FROM blocks WHERE src = ? AND dst = ?", me, u);
        return { state: this.state(me) };
      }
      case "report": {
        const u = this.uuidArg(a);
        const reason = typeof a.reason === "string" ? a.reason.replace(/[\u0000-\u001f]/g, " ").trim().slice(0, 500) : "";
        if (!reason) throw new SocialError(400, "Say what happened");
        this.limit(me + ":report", 5, 24 * 3600 * 1000, "too many reports today");
        this.sql.exec("INSERT INTO reports (src, dst, reason, created) VALUES (?, ?, ?, ?)", me, u, reason, now);
        console.log("social report", JSON.stringify({ from: me, about: u, reason }));
        return {};
      }
      case "party.create": {
        this.profile(me);
        if (this.partyOf(me)) throw new SocialError(409, "You're already in a party");
        this.createParty(me, now);
        return { state: this.state(me) };
      }
      case "party.invite": {
        this.profile(me);
        const them = this.target(a, me);
        const u = them.uuid;
        if (!u || !this.areFriends(me, u)) throw new SocialError(404, "You can only invite friends");
        if (this.blockedEither(me, u)) throw new SocialError(404, "You can only invite friends");
        this.limit(me + ":invite", 30, 3600 * 1000, "too many invites; try again later");
        let party = this.partyOf(me) || this.createParty(me, now);
        if (this.partyOf(u) === party) throw new SocialError(409, (them.profile?.name || "They") + " is already in your party");
        this.sql.exec("DELETE FROM invites WHERE created <= ?", now - SOCIAL.INVITE_MS);
        const size = this.count("SELECT COUNT(*) AS n FROM members WHERE party = ?", party)
          + this.count("SELECT COUNT(*) AS n FROM invites WHERE party = ? AND dst != ?", party, u);
        if (size >= SOCIAL.MAX_PARTY) throw new SocialError(409, "A party holds " + SOCIAL.MAX_PARTY + " players (invites count)");
        this.sql.exec("INSERT OR REPLACE INTO invites (party, dst, src, created) VALUES (?, ?, ?, ?)", party, u, me, now);
        this.send(u, { type: "party.invite", party, from: this.who(me), size: this.members(party).length });
        this.pushState(u);
        this.pushParty(party, me);
        return { state: this.state(me) };
      }
      case "party.accept": {
        const party = this.partyArg(a);
        const inv = this.sql.exec("SELECT src FROM invites WHERE party = ? AND dst = ? AND created > ?", party, me, now - SOCIAL.INVITE_MS).toArray()[0];
        if (!inv || !this.sql.exec("SELECT id FROM parties WHERE id = ?", party).toArray().length) {
          this.sql.exec("DELETE FROM invites WHERE party = ? AND dst = ?", party, me);
          throw new SocialError(404, "That invite has expired");
        }
        if (this.partyOf(me) === party) {
          this.sql.exec("DELETE FROM invites WHERE party = ? AND dst = ?", party, me);
          throw new SocialError(409, "You're already in that party");
        }
        if (this.count("SELECT COUNT(*) AS n FROM members WHERE party = ?", party) >= SOCIAL.MAX_PARTY) throw new SocialError(409, "That party is full");
        if (this.partyOf(me)) this.leaveParty(me, false);
        this.sql.exec("DELETE FROM invites WHERE party = ? AND dst = ?", party, me);
        this.sql.exec("INSERT INTO members (uuid, party, joined) VALUES (?, ?, ?)", me, party, now);
        for (const m of this.members(party)) if (m !== me) this.send(m, { type: "party.joined", member: this.who(me) });
        this.pushParty(party, me);
        return { state: this.state(me) };
      }
      case "party.decline": {
        const party = this.partyArg(a);
        const inv = this.sql.exec("SELECT src FROM invites WHERE party = ? AND dst = ?", party, me).toArray()[0];
        this.sql.exec("DELETE FROM invites WHERE party = ? AND dst = ?", party, me);
        if (inv) this.pushParty(party, null);
        return { state: this.state(me) };
      }
      case "party.leave": {
        if (!this.partyOf(me)) throw new SocialError(404, "You aren't in a party");
        this.leaveParty(me, false);
        return { state: this.state(me) };
      }
      case "party.kick": {
        const u = this.uuidArg(a);
        const party = this.leaderParty(me);
        if (u === me || this.partyOf(u) !== party) throw new SocialError(404, "They aren't in your party");
        this.send(u, { type: "party.kicked", by: this.who(me) });
        this.leaveParty(u, true);
        this.pushState(u);
        return { state: this.state(me) };
      }
      case "party.transfer": {
        const u = this.uuidArg(a);
        const party = this.leaderParty(me);
        if (u === me || this.partyOf(u) !== party) throw new SocialError(404, "They aren't in your party");
        this.sql.exec("UPDATE parties SET leader = ? WHERE id = ?", u, party);
        for (const m of this.members(party)) this.send(m, { type: "party.leader", leader: this.who(u) });
        this.pushParty(party, me);
        return { state: this.state(me) };
      }
      case "ping": {
        this.ping(me, a, now);
        return {};
      }
      case "msg": {
        const u = this.uuidArg(a);
        if (u === me) throw new SocialError(400, "That's you");
        const text = chatText(a.text);
        if (!this.areFriends(me, u) || this.blockedEither(me, u)) throw new SocialError(404, "You can only message friends");
        const name = this.who(u).name || "They";
        if (!this.isOnline(u)) throw new SocialError(409, name + " is offline (messages aren't kept for later)");
        this.chatLimit(me, now);
        const delivered = this.relay(u, { type: "msg", from: this.who(me), text, at: now }, now);
        return { delivered };
      }
      case "party.msg": {
        const text = chatText(a.text);
        const party = this.partyOf(me);
        if (!party) throw new SocialError(409, "Join a party to use party chat");
        this.chatLimit(me, now);
        const msg = { type: "party.msg", from: this.who(me), text, at: now };
        let delivered = 0;
        for (const m of this.members(party)) if (m !== me && !this.blockedEither(me, m) && this.relay(m, msg, now)) delivered++;
        return { delivered };
      }
      case "party.summon": {
        const party = this.leaderParty(me);
        const server = normServer(a.server);
        if (!server) throw new SocialError(400, "Bring your party to a multiplayer server (not singleplayer or LAN)");
        const last = this.summonTimes.get(me) || 0;
        if (now - last < SOCIAL.SUMMON_GAP_MS) throw new SocialError(429, "You just did that", { retryAfter: last + SOCIAL.SUMMON_GAP_MS - now });
        this.limit(me + ":summon", SOCIAL.SUMMON_HOUR, 3600 * 1000, "too many party summons; try again later");
        this.summonTimes.set(me, now);
        if (this.summonTimes.size > 5000) for (const [k, v] of this.summonTimes) if (v <= now - SOCIAL.SUMMON_GAP_MS) this.summonTimes.delete(k);
        const msg = { type: "party.summon", from: this.who(me), server, at: now };
        let delivered = 0;
        for (const m of this.members(party)) if (m !== me && !this.blockedEither(me, m) && this.relay(m, msg, now)) delivered++;
        return { delivered };
      }
      case "world.invite": {
        // Open to Friends: the address of your singleplayer world (an e4mc tunnel) to chosen friends / party members
        // who are online. Never stored; the recipient joins only by clicking Join and confirming.
        const address = worldAddress(a.address);
        if (!address) throw new SocialError(400, "Only an Open to Friends address can be shared");
        if (!Array.isArray(a.uuids) || !a.uuids.length) throw new SocialError(400, "Pick who to invite");
        if (a.uuids.length > SOCIAL.MAX_WORLD_INVITE) throw new SocialError(400, "Invite " + SOCIAL.MAX_WORLD_INVITE + " players at most at once");
        const world = typeof a.world === "string" ? a.world.replace(/[\u0000-\u001f\u007f-\u009f\u00a7\u200b-\u200f\u2028-\u202e\u2060-\u206f\ufeff]/g, " ")
          .replace(/\s+/g, " ").trim().slice(0, 48) : "";
        const last = this.worldInviteTimes.get(me) || 0;
        if (now - last < SOCIAL.WORLD_INVITE_GAP_MS) throw new SocialError(429, "You just sent invites", { retryAfter: last + SOCIAL.WORLD_INVITE_GAP_MS - now });
        this.limit(me + ":world", SOCIAL.WORLD_INVITE_HOUR, 3600 * 1000, "too many world invites; try again later");
        this.worldInviteTimes.set(me, now);
        if (this.worldInviteTimes.size > 5000) for (const [k, v] of this.worldInviteTimes) if (v <= now - SOCIAL.WORLD_INVITE_GAP_MS) this.worldInviteTimes.delete(k);
        const party = this.partyOf(me);
        const msg = { type: "world.invite", from: this.who(me), address, world, at: now };
        let delivered = 0;
        for (const u of new Set(a.uuids.map(normUuid))) {
          if (!UUID.test(u) || u === me) continue;
          const allowed = this.areFriends(me, u) || (party && this.partyOf(u) === party);
          if (!allowed || this.blockedEither(me, u)) continue;
          if (this.relay(u, msg, now)) delivered++;
        }
        return { delivered };
      }
      case "delete": {
        this.purge(me, true);
        this.sql.exec("DELETE FROM backups WHERE uuid = ?", me); // asked for: the cloud backup goes too
        for (const ws of this.socketsOf(me)) this.closeSocket(ws, 4002, "social data deleted");
        return { deleted: true };
      }
      case "auth":
        throw new SocialError(400, "already signed in");
    }
    throw new SocialError(404, "unknown request");
  }

  /** Relays a ping to the sender's party members on the same server. Nothing about it is written down. */
  ping(me, a, now) {
    const x = Number(a.x), y = Number(a.y), z = Number(a.z);
    if (![x, y, z].every(Number.isFinite) || Math.abs(x) > 30000000 || Math.abs(z) > 30000000 || Math.abs(y) > 20000) throw new SocialError(400, "bad position");
    const dimension = typeof a.dimension === "string" ? a.dimension.toLowerCase() : "";
    if (!DIMENSION.test(dimension)) throw new SocialError(400, "bad dimension");
    const kind = a.kind === undefined || a.kind === null ? "location" : a.kind;
    if (!PING_KINDS.has(kind)) throw new SocialError(400, "kind is location, danger or loot");
    const server = normServer(a.server);
    if (!server) throw new SocialError(400, "pings only work on a multiplayer server");
    // 1 ping per 1.5 s, at most 5 per 10 s.
    const times = (this.pingTimes.get(me) || []).filter((t) => t > now - SOCIAL.PING_WINDOW_MS);
    const wait = times.length >= SOCIAL.PING_MAX ? times[times.length - SOCIAL.PING_MAX] + SOCIAL.PING_WINDOW_MS - now
      : times.length ? times[times.length - 1] + SOCIAL.PING_GAP_MS - now : 0;
    if (wait > 0) { this.pingTimes.set(me, times); throw new SocialError(429, "Pinging too fast", { retryAfter: wait }); }
    times.push(now);
    this.pingTimes.set(me, times);
    if (this.pingTimes.size > 5000) for (const [k, v] of this.pingTimes) if (!v.length || v[v.length - 1] < now - SOCIAL.PING_WINDOW_MS) this.pingTimes.delete(k);

    const party = this.partyOf(me);
    if (!party) throw new SocialError(409, "Join a party to ping");
    const p = this.profile(me);
    // The ping says where the sender is playing now: keep their presence in step with it.
    if (p.server !== server) this.touch(me, { server }, false);
    const msg = { type: "ping", from: this.who(me), x: Math.round(x * 10) / 10, y: Math.round(y * 10) / 10, z: Math.round(z * 10) / 10, dimension, kind, at: now };
    for (const m of this.members(party)) {
      if (m === me || this.blockedEither(me, m)) continue;
      const mp = this.sql.exec("SELECT server, poll_at FROM profiles WHERE uuid = ?", m).toArray()[0];
      if (!mp || mp.server !== server) continue;
      const sockets = this.socketsOf(m);
      if (sockets.length) this.send(m, msg);
      else if (mp.poll_at > now - SOCIAL.POLL_ONLINE_MS) {
        const q = (this.pollPings.get(m) || []).filter((e) => e.at > now - 15000).slice(-9);
        q.push(msg);
        this.pollPings.set(m, q);
      }
    }
  }

  /** Message rate: one per 0.3 s, 8 per 10 s (memory), 600 an hour (a counter only, never the text). */
  chatLimit(me, now) {
    const times = (this.chatTimes.get(me) || []).filter((t) => t > now - SOCIAL.CHAT_WINDOW_MS);
    const wait = times.length >= SOCIAL.CHAT_BURST ? times[times.length - SOCIAL.CHAT_BURST] + SOCIAL.CHAT_WINDOW_MS - now
      : times.length ? times[times.length - 1] + SOCIAL.CHAT_GAP_MS - now : 0;
    if (wait > 0) { this.chatTimes.set(me, times); throw new SocialError(429, "Sending too fast", { retryAfter: wait }); }
    this.limit(me + ":chat", SOCIAL.CHAT_HOUR, 3600 * 1000, "too many messages this hour; try again later");
    times.push(now);
    this.chatTimes.set(me, times);
    if (this.chatTimes.size > 5000) for (const [k, v] of this.chatTimes) if (!v.length || v[v.length - 1] < now - SOCIAL.CHAT_WINDOW_MS) this.chatTimes.delete(k);
  }

  /**
   * Hands {@code msg} to {@code uuid} if it is online: to its sockets, or (polling) into memory for its next /state.
   * Returns whether it was delivered. Nothing is stored.
   */
  relay(uuid, msg, now) {
    if (this.socketsOf(uuid).length) { this.send(uuid, msg); return true; }
    const p = this.sql.exec("SELECT poll_at FROM profiles WHERE uuid = ?", uuid).toArray()[0];
    if (!p || p.poll_at <= now - SOCIAL.POLL_ONLINE_MS) return false;
    const q = (this.pollEvents.get(uuid) || []).filter((e) => e.at > now - SOCIAL.POLL_EVENT_MS).slice(-19);
    q.push(msg);
    this.pollEvents.set(uuid, q);
    if (this.pollEvents.size > 5000) for (const [k, v] of this.pollEvents) if (!v.length || v[v.length - 1].at <= now - SOCIAL.POLL_EVENT_MS) this.pollEvents.delete(k);
    return true;
  }

  // ------------------------------------------------------------------ helpers: accounts

  /** My profile row, created on first use. */
  profile(uuid) {
    let p = this.sql.exec("SELECT * FROM profiles WHERE uuid = ?", uuid).toArray()[0];
    if (!p) {
      const now = Date.now();
      this.sql.exec("INSERT INTO profiles (uuid, last_seen, created) VALUES (?, ?, ?)", uuid, now, now);
      p = this.sql.exec("SELECT * FROM profiles WHERE uuid = ?", uuid).toArray()[0];
    }
    return p;
  }

  /** Records a sign of life: name, current server ({@code a.server}, null for none) and, for HTTP clients, polling. */
  touch(me, a, polling) {
    const p = this.profile(me);
    const now = Date.now();
    const name = typeof a.name === "string" && NAME.test(a.name) ? a.name : p.name;
    const server = a.server === undefined ? p.server : normServer(a.server);
    const wasOnline = polling ? this.isOnline(me) : true;
    this.sql.exec("UPDATE profiles SET name = ?, name_lc = ?, server = ?, last_seen = ?, poll_at = ? WHERE uuid = ?",
      name, name.toLowerCase(), server, now, polling ? now : p.poll_at, me);
    if (polling && !p.poll_at) this.alarmBy(now + SOCIAL.POLL_ONLINE_MS + 1000).catch((e) => console.error("social alarm", e));
    const activityChanged = this.setActivity(me, a, now);
    if (!wasOnline || server !== p.server || name !== p.name || activityChanged) this.broadcastStatus(me);
  }

  /**
   * Richer presence: what the player is doing ({@code a.activity}: menus, singleplayer, server or afk; null clears it)
   * and since when ({@code a.since}, ms). Older clients send neither, which leaves it as it was. Returns whether it
   * changed. Unknown values are ignored rather than refused, so a newer client never breaks.
   */
  setActivity(me, a, now) {
    if (a.activity === undefined) return false;
    const old = this.sql.exec("SELECT kind, since FROM activity WHERE uuid = ?", me).toArray()[0];
    if (a.activity === null) {
      if (!old) return false;
      this.sql.exec("DELETE FROM activity WHERE uuid = ?", me);
      return true;
    }
    if (!ACTIVITIES.has(a.activity)) return false;
    let since = Number(a.since);
    if (!Number.isSafeInteger(since) || since > now || since < now - 7 * 24 * 3600 * 1000) since = now;
    if (old && old.kind === a.activity && Math.abs(old.since - since) < 60 * 1000) return false;
    this.sql.exec("INSERT OR REPLACE INTO activity (uuid, kind, since) VALUES (?, ?, ?)", me, a.activity, since);
    return true;
  }

  /** {activity, activeSince} to show for an online account, or {} (offline, or an older client that never sent one). */
  shownActivity(uuid, online) {
    if (!online) return {};
    const r = this.sql.exec("SELECT kind, since FROM activity WHERE uuid = ?", uuid).toArray()[0];
    return r ? { activity: r.kind, activeSince: r.since } : {};
  }

  /** {uuid, name} for events and lists. */
  who(uuid) {
    const p = this.sql.exec("SELECT name FROM profiles WHERE uuid = ?", uuid).toArray()[0];
    return { uuid, name: p ? p.name : "" };
  }

  /** The account a request names: {uuid} (preferred: the client looked it up with Mojang) or {name}. */
  target(a, me) {
    if (typeof a.uuid === "string") {
      const u = normUuid(a.uuid);
      if (!UUID.test(u)) throw new SocialError(400, "bad uuid");
      if (u === me) throw new SocialError(400, "That's you");
      const profile = this.sql.exec("SELECT * FROM profiles WHERE uuid = ?", u).toArray()[0];
      return { uuid: u, profile, name: profile ? profile.name : (typeof a.name === "string" && NAME.test(a.name) ? a.name : "") };
    }
    if (typeof a.name !== "string" || !NAME.test(a.name)) throw new SocialError(400, "Type a Minecraft name");
    // Names are only what clients report, so a name two accounts claim is never guessed between.
    const rows = this.sql.exec("SELECT * FROM profiles WHERE name_lc = ? ORDER BY last_seen DESC LIMIT 2", a.name.toLowerCase()).toArray();
    if (rows.length !== 1) return { uuid: null, profile: null, name: a.name };
    if (rows[0].uuid === me) throw new SocialError(400, "That's you");
    return { uuid: rows[0].uuid, profile: rows[0], name: a.name };
  }

  uuidArg(a) {
    const u = normUuid(a.uuid);
    if (!UUID.test(u)) throw new SocialError(400, "bad uuid");
    return u;
  }

  partyArg(a) {
    if (typeof a.party !== "string" || !PARTY_ID.test(a.party)) throw new SocialError(400, "bad party");
    return a.party;
  }

  areFriends(a, b) { return this.has("friends", "a", a, "b", b); }

  blockedEither(a, b) {
    return this.sql.exec("SELECT 1 FROM blocks WHERE (src = ? AND dst = ?) OR (src = ? AND dst = ?) LIMIT 1", a, b, b, a).toArray().length > 0;
  }

  has(table, c1, v1, c2, v2) {
    return this.sql.exec(`SELECT 1 FROM ${table} WHERE ${c1} = ? AND ${c2} = ? LIMIT 1`, v1, v2).toArray().length > 0;
  }

  count(q, ...binds) { return this.sql.exec(q, ...binds).one().n; }

  makeFriends(me, u, now) {
    this.sql.exec("INSERT OR IGNORE INTO friends (a, b, since) VALUES (?, ?, ?)", me, u, now);
    this.sql.exec("INSERT OR IGNORE INTO friends (a, b, since) VALUES (?, ?, ?)", u, me, now);
    this.sql.exec("DELETE FROM requests WHERE (src = ? AND dst = ?) OR (src = ? AND dst = ?)", me, u, u, me);
    this.send(u, { type: "friend.accepted", friend: this.who(me) });
    this.pushState(u);
  }

  /** A counter kept in storage: at most {@code limit} per {@code windowMs}, else a 429. */
  limit(key, limit, windowMs, msg) {
    const now = Date.now();
    const row = this.sql.exec("SELECT start, n FROM rate WHERE k = ?", key).toArray()[0];
    if (!row || row.start <= now - windowMs) {
      this.sql.exec("INSERT OR REPLACE INTO rate (k, start, n) VALUES (?, ?, 1)", key, now);
      return;
    }
    if (row.n >= limit) throw new SocialError(429, msg, { retryAfter: row.start + windowMs - now });
    this.sql.exec("UPDATE rate SET n = n + 1 WHERE k = ?", key);
  }

  /** A short flood limit on everything an account sends: 40 requests per 10 seconds (memory only). */
  flood(uuid) {
    const now = Date.now();
    let b = this.bursts.get(uuid);
    if (!b || b.start <= now - 10000) { b = { start: now, n: 0 }; this.bursts.set(uuid, b); }
    if (++b.n > 40) throw new SocialError(429, "Slow down", { retryAfter: b.start + 10000 - now });
    if (this.bursts.size > 5000) for (const [k, v] of this.bursts) if (v.start <= now - 10000) this.bursts.delete(k);
  }

  meta(k) { const r = this.sql.exec("SELECT v FROM meta WHERE k = ?", k).toArray()[0]; return r ? r.v : null; }
  setMeta(k, v) { this.sql.exec("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", k, v); }

  async alarmBy(at) {
    const cur = await this.ctx.storage.getAlarm();
    if (!cur || cur > at) await this.ctx.storage.setAlarm(at);
  }

  // ------------------------------------------------------------------ helpers: sockets and presence

  /** Open sockets signed in as {@code uuid} (tagged at accept, or signed in later over the socket), minus {@code except}. */
  socketsOf(uuid, except) {
    const out = this.ctx.getWebSockets(uuid).filter((ws) => ws !== except);
    for (const ws of this.ctx.getWebSockets("pending")) {
      if (ws !== except && (ws.deserializeAttachment() || {}).uuid === uuid) out.push(ws);
    }
    return out;
  }

  /** Closes the oldest of {@code uuid}'s sockets until at most {@code keep} are left (a game opened a 4th time). */
  trimSockets(uuid, keep, except) {
    const list = this.socketsOf(uuid, except).sort((a, b) => (a.deserializeAttachment()?.at || 0) - (b.deserializeAttachment()?.at || 0));
    while (list.length > keep) this.closeSocket(list.shift(), 4000, "signed in somewhere else");
  }

  closeSocket(ws, code, reason) {
    try { ws.close(code, reason); } catch { /* gone */ }
  }

  isOnline(uuid, except) {
    if (this.socketsOf(uuid, except).length) return true;
    const p = this.sql.exec("SELECT poll_at FROM profiles WHERE uuid = ?", uuid).toArray()[0];
    return !!p && p.poll_at > Date.now() - SOCIAL.POLL_ONLINE_MS;
  }

  /** A socket just signed in: say hello with the full state, and tell friends if this account just came online. */
  online(uuid, ws) {
    const wasOnline = this.isOnline(uuid, ws);
    const p = this.profile(uuid);
    this.sql.exec("UPDATE profiles SET last_seen = ? WHERE uuid = ?", Date.now(), uuid);
    try { ws.send(JSON.stringify({ type: "hello", state: this.state(uuid) })); } catch { /* gone */ }
    if (!wasOnline && p) this.broadcastStatus(uuid);
  }

  /**
   * Nobody is signed in as {@code uuid} any more (since {@code lastActive}, now by default): forget the server address
   * and tell friends and the party.
   */
  offline(uuid, lastActive = Date.now()) {
    this.sql.exec("UPDATE profiles SET server = NULL, poll_at = 0, last_seen = MAX(last_seen, ?) WHERE uuid = ?", lastActive, uuid);
    this.sql.exec("DELETE FROM activity WHERE uuid = ?", uuid);
    this.broadcastStatus(uuid);
  }

  /** The server {@code p} shows friends and party members: only with Share My Server on, and only while online. */
  shownServer(p, online) { return online && p && p.share_server ? p.server : null; }

  /** Tells {@code uuid}'s online friends and party members its online state and (if shared) its server. */
  broadcastStatus(uuid) {
    const p = this.sql.exec("SELECT * FROM profiles WHERE uuid = ?", uuid).toArray()[0];
    if (!p) return;
    const online = this.isOnline(uuid);
    const msg = JSON.stringify({ type: "friend.status", uuid, name: p.name, online, server: this.shownServer(p, online), ...this.shownActivity(uuid, online) });
    for (const r of this.sql.exec("SELECT b FROM friends WHERE a = ?", uuid).toArray()) this.sendRaw(r.b, msg);
    const party = this.partyOf(uuid);
    if (party) this.pushParty(party, uuid);
  }

  send(uuid, obj) { this.sendRaw(uuid, JSON.stringify(obj)); }

  sendRaw(uuid, text) {
    for (const ws of this.socketsOf(uuid)) { try { ws.send(text); } catch { /* closing */ } }
  }

  reply(ws, m, body) {
    try { ws.send(JSON.stringify({ type: "result", id: m && (typeof m.id === "number" || typeof m.id === "string") ? m.id : null, ...body })); } catch { /* gone */ }
  }

  /** Sends {@code uuid} its full state, if it has a socket open. */
  pushState(uuid) {
    const sockets = this.socketsOf(uuid);
    if (!sockets.length) return;
    const text = JSON.stringify({ type: "state", state: this.state(uuid) });
    for (const ws of sockets) { try { ws.send(text); } catch { /* closing */ } }
  }

  /** Sends every member of {@code party} (but {@code except}) its fresh state. */
  pushParty(party, except) {
    for (const m of this.members(party)) if (m !== except) this.pushState(m);
  }

  // ------------------------------------------------------------------ helpers: parties

  partyOf(uuid) {
    const r = this.sql.exec("SELECT party FROM members WHERE uuid = ?", uuid).toArray()[0];
    return r ? r.party : null;
  }

  members(party) {
    return this.sql.exec("SELECT uuid FROM members WHERE party = ? ORDER BY joined", party).toArray().map((r) => r.uuid);
  }

  leaderParty(me) {
    const party = this.partyOf(me);
    if (!party) throw new SocialError(404, "You aren't in a party");
    const p = this.sql.exec("SELECT leader FROM parties WHERE id = ?", party).toArray()[0];
    if (!p || p.leader !== me) throw new SocialError(403, "Only the party leader can do that");
    return party;
  }

  createParty(me, now) {
    const id = hex(8);
    this.sql.exec("INSERT INTO parties (id, leader, created) VALUES (?, ?, ?)", id, me, now);
    this.sql.exec("INSERT OR REPLACE INTO members (uuid, party, joined) VALUES (?, ?, ?)", me, id, now);
    return id;
  }

  /** Takes {@code uuid} out of its party: the next member leads if it was the leader; the last one out ends the party. */
  leaveParty(uuid, kicked) {
    const party = this.partyOf(uuid);
    if (!party) return;
    this.sql.exec("DELETE FROM members WHERE uuid = ?", uuid);
    const rest = this.members(party);
    if (!rest.length) {
      this.sql.exec("DELETE FROM parties WHERE id = ?", party);
      this.sql.exec("DELETE FROM invites WHERE party = ?", party);
      return;
    }
    const p = this.sql.exec("SELECT leader FROM parties WHERE id = ?", party).toArray()[0];
    if (p && p.leader === uuid) {
      this.sql.exec("UPDATE parties SET leader = ? WHERE id = ?", rest[0], party);
      for (const m of rest) this.send(m, { type: "party.leader", leader: this.who(rest[0]) });
    }
    // Invites the leaver sent stay valid: the party still wants them.
    for (const m of rest) this.send(m, { type: "party.left", member: this.who(uuid), kicked: !!kicked });
    this.pushParty(party, null);
  }

  // ------------------------------------------------------------------ state

  /** Everything the client shows: settings, friends (online first), requests, blocks, party and invites. */
  state(me) {
    const now = Date.now();
    const p = this.profile(me);
    const person = (uuid) => {
      const q = this.sql.exec("SELECT * FROM profiles WHERE uuid = ?", uuid).toArray()[0];
      const online = !!q && this.isOnline(uuid);
      return { uuid, name: q ? q.name : "", online, server: this.shownServer(q, online), lastSeen: q ? q.last_seen : 0, ...this.shownActivity(uuid, online) };
    };
    const friends = this.sql.exec("SELECT b, since FROM friends WHERE a = ?", me).toArray()
      .map((r) => ({ ...person(r.b), since: r.since }))
      .sort((x, y) => (y.online - x.online) || x.name.localeCompare(y.name));
    const incoming = this.sql.exec("SELECT src, created FROM requests WHERE dst = ? ORDER BY created DESC", me).toArray()
      .map((r) => ({ ...this.who(r.src), at: r.created }));
    const outgoing = this.sql.exec("SELECT dst, created FROM requests WHERE src = ? ORDER BY created DESC", me).toArray()
      .map((r) => ({ ...this.who(r.dst), at: r.created }));
    const blocked = this.sql.exec("SELECT dst FROM blocks WHERE src = ? ORDER BY created DESC", me).toArray().map((r) => this.who(r.dst));
    let party = null;
    const pid = this.partyOf(me);
    if (pid) {
      const pr = this.sql.exec("SELECT leader FROM parties WHERE id = ?", pid).toArray()[0];
      party = {
        id: pid,
        leader: pr ? pr.leader : null,
        members: this.members(pid).map((u) => ({ ...person(u), leader: !!pr && pr.leader === u })),
        invited: this.sql.exec("SELECT dst FROM invites WHERE party = ? AND created > ?", pid, now - SOCIAL.INVITE_MS).toArray().map((r) => this.who(r.dst)),
      };
    }
    const invites = this.sql.exec("SELECT party, src, created FROM invites WHERE dst = ? AND created > ? ORDER BY created DESC", me, now - SOCIAL.INVITE_MS).toArray()
      .map((r) => ({ party: r.party, from: this.who(r.src), size: this.count("SELECT COUNT(*) AS n FROM members WHERE party = ?", r.party), at: r.created }));
    return {
      me: { uuid: me, name: p.name, allowRequests: p.allow_requests ? "everyone" : "nobody", shareServer: !!p.share_server },
      friends, incoming, outgoing, blocked, party, invites,
      limits: { friends: SOCIAL.MAX_FRIENDS, pending: SOCIAL.MAX_PENDING, party: SOCIAL.MAX_PARTY },
    };
  }

  // ------------------------------------------------------------------ retention

  /**
   * Deletes everything social about {@code uuid}: profile, friendships, requests both ways, the blocks it made,
   * invites, party membership and reports it sent. Blocks other players made against it are theirs and stay.
   */
  purge(uuid, notify) {
    const friends = this.sql.exec("SELECT b FROM friends WHERE a = ?", uuid).toArray().map((r) => r.b);
    const asked = this.sql.exec("SELECT dst FROM requests WHERE src = ? UNION SELECT src FROM requests WHERE dst = ?", uuid, uuid).toArray().map((r) => r.dst);
    this.leaveParty(uuid, false);
    this.sql.exec("DELETE FROM friends WHERE a = ? OR b = ?", uuid, uuid);
    this.sql.exec("DELETE FROM requests WHERE src = ? OR dst = ?", uuid, uuid);
    this.sql.exec("DELETE FROM blocks WHERE src = ?", uuid);
    this.sql.exec("DELETE FROM invites WHERE src = ? OR dst = ?", uuid, uuid);
    this.sql.exec("DELETE FROM reports WHERE src = ?", uuid);
    this.sql.exec("DELETE FROM rate WHERE k >= ? AND k < ?", uuid + ":", uuid + ";");
    this.sql.exec("DELETE FROM profiles WHERE uuid = ?", uuid);
    this.sql.exec("DELETE FROM activity WHERE uuid = ?", uuid);
    this.pingTimes.delete(uuid);
    this.worldInviteTimes.delete(uuid);
    this.pollPings.delete(uuid);
    this.chatTimes.delete(uuid);
    this.pollEvents.delete(uuid);
    this.summonTimes.delete(uuid);
    if (notify) {
      for (const f of friends) { this.send(f, { type: "friend.removed", uuid }); this.pushState(f); }
      for (const u of asked) this.pushState(u);
    }
  }

  /** Every few hours: inactive accounts, idle parties, lapsed invites, old reports and rate counters. */
  sweep(now) {
    for (const r of this.sql.exec("SELECT uuid FROM profiles WHERE last_seen < ? LIMIT 500", now - SOCIAL.INACTIVE_MS).toArray()) {
      if (!this.socketsOf(r.uuid).length) this.purge(r.uuid, true);
    }
    for (const r of this.sql.exec("SELECT id FROM parties").toArray()) {
      const ms = this.members(r.id);
      const active = ms.some((m) => {
        if (this.isOnline(m)) return true;
        const q = this.sql.exec("SELECT last_seen FROM profiles WHERE uuid = ?", m).toArray()[0];
        return q && q.last_seen > now - SOCIAL.PARTY_IDLE_MS;
      });
      if (!active) {
        this.sql.exec("DELETE FROM members WHERE party = ?", r.id);
        this.sql.exec("DELETE FROM invites WHERE party = ?", r.id);
        this.sql.exec("DELETE FROM parties WHERE id = ?", r.id);
      }
    }
    this.sql.exec("DELETE FROM invites WHERE created <= ?", now - SOCIAL.INVITE_MS);
    this.sql.exec("DELETE FROM reports WHERE created <= ?", now - SOCIAL.REPORT_KEEP_MS);
    this.sql.exec("DELETE FROM rate WHERE start <= ?", now - 24 * 3600 * 1000);
    this.sql.exec("DELETE FROM backups WHERE used <= ?", now - BACKUP.UNUSED_MS);
    this.sql.exec("DELETE FROM activity WHERE uuid NOT IN (SELECT uuid FROM profiles)");
    this.cos.sweep(now);
  }

  // ------------------------------------------------------------------ cloud backup

  /**
   * Opt-in cloud backup: one encrypted blob per account. The client encrypts it (AES-GCM, key from the player's own
   * passphrase) before upload, so this object stores bytes it can't read. Deleted after 90 days without a backup or
   * restore, or at once with delete (or Delete my social data, which deletes the backup too).
   */
  runBackup(me, op, a) {
    const now = Date.now();
    const info = (r) => r ? { exists: true, size: r.size, updated: r.updated, label: r.label, salt: r.salt, iters: r.iters, format: r.format,
      expires: r.used + BACKUP.UNUSED_MS } : { exists: false };
    switch (op) {
      case "info": {
        const r = this.sql.exec("SELECT size, updated, label, salt, iters, format, used FROM backups WHERE uuid = ?", me).toArray()[0];
        return { backup: info(r), limits: { maxBytes: BACKUP.MAX_BLOB } };
      }
      case "get": {
        this.limit(me + ":backup.get", BACKUP.GETS_PER_DAY, 24 * 3600 * 1000, "too many restores today; try again tomorrow");
        const r = this.sql.exec("SELECT * FROM backups WHERE uuid = ?", me).toArray()[0];
        if (!r) throw new SocialError(404, "No cloud backup for this account");
        this.sql.exec("UPDATE backups SET used = ? WHERE uuid = ?", now, me);
        return { backup: info({ ...r, used: now }), blob: r.blob };
      }
      case "put": {
        const blob = a.blob, salt = a.salt;
        if (typeof blob !== "string" || !B64.test(blob) || blob.length % 4 !== 0) throw new SocialError(400, "bad backup");
        if (blob.length > BACKUP.MAX_BLOB_B64) throw new SocialError(413, "That backup is too big (" + Math.round(BACKUP.MAX_BLOB / 1024) + " KB at most)");
        if (blob.length < 40) throw new SocialError(400, "bad backup");
        if (typeof salt !== "string" || !/^[A-Za-z0-9+/=]{16,64}$/.test(salt)) throw new SocialError(400, "bad salt");
        const iters = Number(a.iters);
        if (!Number.isSafeInteger(iters) || iters < 100000 || iters > 5000000) throw new SocialError(400, "bad iterations");
        const format = Number(a.format ?? 1);
        if (!Number.isSafeInteger(format) || format < 1 || format > 100) throw new SocialError(400, "bad format");
        const label = typeof a.label === "string" ? a.label.replace(/[\u0000-\u001f\u007f-\u009f\u00a7]/g, " ").trim().slice(0, 64) : "";
        this.limit(me + ":backup.put", BACKUP.PUTS_PER_DAY, 24 * 3600 * 1000, "too many backups today; try again tomorrow");
        const old = this.sql.exec("SELECT size FROM backups WHERE uuid = ?", me).toArray()[0];
        const total = this.sql.exec("SELECT COALESCE(SUM(size), 0) AS n FROM backups").one().n - (old ? old.size : 0);
        if (total + blob.length > BACKUP.MAX_TOTAL) throw new SocialError(507, "Cloud backup is full right now; try again later");
        this.sql.exec("INSERT OR REPLACE INTO backups (uuid, blob, salt, iters, format, size, label, updated, used) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          me, blob, salt, iters, format, blob.length, label, now, now);
        return { backup: info({ size: blob.length, updated: now, label, salt, iters, format, used: now }) };
      }
      case "delete": {
        const n = this.sql.exec("DELETE FROM backups WHERE uuid = ? RETURNING uuid", me).toArray().length;
        return { deleted: n > 0 };
      }
    }
    throw new SocialError(404, "unknown request");
  }
}
