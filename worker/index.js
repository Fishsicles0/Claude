// evadeclient.com: serves the static site from ./site, plus a small API for Evade icons in the tab list.
//
// Icons can't be faked: a player proves which Minecraft account they own with Mojang's own "join server"
// handshake (the client asks Mojang to join a one-time id from us, then we ask Mojang who joined it). The
// player's login token only ever goes to Mojang. We then sign a short-lived token for that UUID.
//
//   POST /api/auth/challenge            -> { serverId }
//   POST /api/auth/verify {username, serverId} -> { token, uuid, name, expiresIn }   (checks Mojang hasJoined)
//   POST /api/presence  (Bearer token)  -> marks the player as an active Evade user
//   POST /api/presence/hide (Bearer)   -> stops showing the player's icon right away
//   POST /api/icons {uuids: [...]}      -> { icons: { uuid: "evade" }, ttl }   (only active Evade users)
//
// Everyone gets the same free icon: there are no tiers or paid extras.
import { DurableObject } from "cloudflare:workers";

const HEADERS = {
  "content-type": "application/json",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
};
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: HEADERS });
const UUID = /^[0-9a-f]{32}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;
const SERVER_ID = /^[0-9a-f]{8,40}$/;
const ACTIVE_MS = 15 * 60 * 1000; // counts as an Evade user for 15 min after the last heartbeat
const TOKEN_MS = 24 * 60 * 60 * 1000;

const hex = (n) => [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, "0")).join("");
const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const normUuid = (u) => (typeof u === "string" ? u.replace(/-/g, "").toLowerCase() : "");

async function body(req) {
  if (req.method !== "POST") throw new HttpError(405, "use POST");
  const text = await req.text();
  if (text.length > 16384) throw new HttpError(413, "too large");
  try { return text ? JSON.parse(text) : {}; } catch { throw new HttpError(400, "bad json"); }
}
class HttpError extends Error { constructor(status, msg) { super(msg); this.status = status; } }

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (!url.pathname.startsWith("/api/")) return env.ASSETS.fetch(req);
    if (req.method === "OPTIONS") {
      return new Response(null, { headers: { ...HEADERS, "access-control-allow-methods": "GET, POST, OPTIONS",
        "access-control-allow-headers": "content-type, authorization" } });
    }
    const store = env.ICONS.get(env.ICONS.idFromName("global"));
    try {
      switch (url.pathname) {
        case "/api/health":
          return json({ ok: true });

        case "/api/auth/challenge":
          await body(req);
          return json({ serverId: hex(20) });

        case "/api/auth/verify": {
          const { username, serverId } = await body(req);
          if (!NAME.test(username || "") || !SERVER_ID.test(serverId || "")) throw new HttpError(400, "bad request");
          const base = env.MOJANG_SESSION_URL || "https://sessionserver.mojang.com";
          let r;
          try {
            r = await fetch(base + "/session/minecraft/hasJoined?username=" + encodeURIComponent(username)
              + "&serverId=" + encodeURIComponent(serverId));
          } catch {
            throw new HttpError(502, "couldn't reach Mojang; try again");
          }
          if (r.status !== 200) throw new HttpError(401, "Mojang did not confirm this account");
          const profile = await r.json();
          const uuid = normUuid(profile.id);
          if (!UUID.test(uuid)) throw new HttpError(401, "Mojang did not confirm this account");
          return json({ token: await store.sign(uuid), uuid, name: profile.name, expiresIn: TOKEN_MS / 1000 });
        }

        case "/api/presence": {
          await body(req);
          const auth = req.headers.get("authorization") || "";
          const uuid = auth.startsWith("Bearer ") ? await store.verify(auth.slice(7)) : null;
          if (!uuid) throw new HttpError(401, "sign in again");
          await store.seen(uuid);
          return json({ ok: true });
        }

        case "/api/presence/hide": {
          await body(req);
          const auth = req.headers.get("authorization") || "";
          const uuid = auth.startsWith("Bearer ") ? await store.verify(auth.slice(7)) : null;
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
                   CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);`);
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
