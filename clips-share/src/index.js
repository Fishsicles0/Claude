// Evade Clips Share — clips.evadeclient.com: the Evade Clips download page, share links and phone sync, on
// Cloudflare Workers + R2.
//
// Storage layout in the R2 bucket:
//   a/<account>.json   account: { keyHash, viewToken, created, used, pending: { <clip>: { size, at } } }
//                      ("pending" reserves space for uploads in progress, so parallel uploads can't overrun the quota)
//   i/<account>.json   the account's clips (newest first), for the phone gallery and the app
//   n/<account>.json   notices for the owner (e.g. a clip removed by moderation, and why)
//   m/<clip>.json      clip metadata (public fields + visibility, private-link key, origin); after a takedown only a
//                      tombstone { id, removed: { reason, at } } is kept
//   v/<clip>.mp4       the video
//   t/<clip>.jpg       its thumbnail
//   u/<clip>           marker for an upload in progress (account and uploadId in its custom metadata)
//   r/<clip>/<viewer>.json   reports from viewers, one per viewer and clip (viewer = salted hash of the IP address)
//   p/<id>.json        a community preset; presets.json is the public index
//   pd/<id>/<day>/<viewer>   preset downloads waiting to be counted (folded into presets.json every hour)
//   rp/<id>/<viewer>.json    reports about a preset
//   stats.json, sweep.json, fold.json, cron.json   server-wide storage use and housekeeping state
//
// Public:  /           landing page + downloads (static files in ./public)
//          /c/<clip>   share page with Discord/Twitter embed tags (private clips need their own ?k= key)
//          /v/<clip>.mp4, /t/<clip>.jpg   video (byte ranges) and thumbnail
//          /g/<account>?t=<viewToken>     private phone gallery, with upload from the phone
// API:     /api/* with "Authorization: Bearer <account>.<key>" (the app) or "Gallery <account>.<viewToken>"
//          (the phone gallery: list, upload, thumbnail only). Moderation: "Admin <ADMIN_TOKEN>".
// No watermarks, no ads, nothing expires: a clip stays until its owner deletes it (or moderation removes it,
// in which case the owner is told which clip and why). Unfinished uploads are cleaned up after a day.

const ID = /^[A-Za-z0-9_-]{6,40}$/;
const REPORT_KEY = /^rp?\/[A-Za-z0-9_-]{6,40}\/[A-Za-z0-9_.-]{1,80}\.json$/;
const PART_MIN = 5 * 1024 * 1024; // R2 multipart minimum (except the last part)
const PART_SIZE = 32 * 1024 * 1024; // part size the clients are told to use, and the largest part accepted
const PART_COUNT_MAX = 10000; // R2's limit
const THUMB_MAX = 2_000_000;
const JSON_MAX = 64 * 1024;
const PENDING_TTL = 24 * 3600 * 1000; // unfinished uploads are given up after a day
const PRESETS_MAX = 2000; // entries in the community index
const PRESETS_PER_ACCOUNT = 50;
const PRESET_MAX = 16 * 1024; // A preset is a small JSON of settings (a look, and what to do at each marker): no times, no files, no code.
const DAILY_CRON = "17 3 * * *"; // keep in sync with wrangler.jsonc
const REASONS = {
  copyright: "a copyright claim",
  harassment: "harassment or hate",
  sexual: "sexual content",
  violence: "graphic violence",
  spam: "spam or scams",
  privacy: "sharing someone's private information",
  other: "breaking the rules",
};

import { LANDING, TOP, BOTTOM } from "./shell.js";

export default {
  async fetch(req, env, ctx) {
    try {
      return await route(req, env, ctx);
    } catch (e) {
      if (e instanceof Response) return e;
      console.error(e);
      return json({ error: "server error" }, 500);
    }
  },

  /** Daily: clean up abandoned uploads, recount storage. Hourly: count preset downloads. */
  async scheduled(event, env) {
    await housekeeping(env, event.cron === DAILY_CRON ? "daily" : "fold");
  },
};

async function route(req, env, ctx) {
  const url = new URL(req.url);
  const p = url.pathname;
  const m = req.method;

  if (m === "OPTIONS") return new Response(null, { headers: cors() });
  // Always the secure address (browsers say "Not secure" on http://). 308 keeps the method and body of API calls.
  if (url.protocol === "http:" && !env.DEV) {
    url.protocol = "https:";
    return Response.redirect(url.toString(), m === "GET" || m === "HEAD" ? 301 : 308);
  }
  if (p === "/" && (m === "GET" || m === "HEAD")) return home();
  if (p.startsWith("/assets/")) return siteFile(env, p.slice("/assets/".length));
  if (["/favicon.svg", "/favicon-32.png", "/apple-touch-icon.png"].includes(p)) return siteFile(env, p.slice(1));
  if (p.startsWith("/downloads/")) return download(req, env, p.slice("/downloads/".length));
  if (p === "/api/register" && m === "POST") return register(req, env, ctx);
  if (p === "/api/clips" && m === "GET") return listClips(await auth(req, env, true), env);
  if (p === "/api/uploads" && m === "POST") return startUpload(req, await auth(req, env, true), env, ctx);
  if (p === "/api/notices" && m === "GET") return notices(await auth(req, env), env, url);
  if (p === "/api/account/rotate-gallery" && m === "POST") return rotateGallery(await auth(req, env), env);
  if (p === "/api/account" && m === "DELETE") return deleteAccount(await auth(req, env), env, ctx);
  if (p === "/api/report" && m === "POST") return report(req, env);
  if (p === "/api/admin/remove" && m === "POST") return adminRemove(req, env, ctx);
  if (p === "/api/admin/reports" && m === "GET") return adminReports(req, env, url);
  if (p === "/api/admin/dismiss" && m === "POST") return adminDismiss(req, env);
  if (p === "/api/presets" && m === "GET") return listPresets(env, url);
  if (p === "/api/presets" && m === "POST") return publishPreset(req, await auth(req, env), env, ctx);
  if (p === "/api/admin/remove-preset" && m === "POST") return adminRemovePreset(req, env, ctx);
  if (p === "/presets" && (m === "GET" || m === "HEAD")) return presetsPage(env, url);

  let r;
  if ((r = p.match(/^\/api\/uploads\/([^/]+)\/(\d+)$/)) && m === "PUT") return uploadPart(req, await auth(req, env, true), env, r[1], +r[2], url);
  if ((r = p.match(/^\/api\/uploads\/([^/]+)\/complete$/)) && m === "POST") return completeUpload(req, await auth(req, env, true), env, ctx, r[1]);
  if ((r = p.match(/^\/api\/uploads\/([^/]+)\/abort$/)) && m === "POST") return abortUpload(req, await auth(req, env, true), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)\/thumb$/)) && m === "PUT") return putThumb(req, await auth(req, env, true), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)$/)) && m === "DELETE") return deleteClip(await auth(req, env), env, ctx, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)$/)) && m === "PATCH") return updateClip(req, await auth(req, env), env, r[1]);

  if ((r = p.match(/^\/api\/presets\/([^/]+)\/report$/)) && m === "POST") return reportPreset(req, env, r[1]);
  if ((r = p.match(/^\/api\/presets\/([^/]+)$/)) && m === "GET") return getPreset(req, env, ctx, r[1], false);
  if ((r = p.match(/^\/api\/presets\/([^/]+)$/)) && m === "DELETE") return deletePreset(await auth(req, env), env, ctx, r[1]);
  if ((r = p.match(/^\/p\/([^/]+)\.evadepreset$/)) && m === "GET") return getPreset(req, env, ctx, r[1], true);
  if ((r = p.match(/^\/c\/([^/]+)$/)) && (m === "GET" || m === "HEAD")) return sharePage(req, env, r[1], url);
  if ((r = p.match(/^\/v\/([^/]+)\.mp4$/)) && (m === "GET" || m === "HEAD")) return video(req, env, r[1], url);
  if ((r = p.match(/^\/t\/([^/]+)\.jpg$/)) && (m === "GET" || m === "HEAD")) return thumb(req, env, r[1], url);
  if ((r = p.match(/^\/g\/([^/]+)$/)) && m === "GET") return gallery(env, r[1], url);
  return notFound(env);
}

// ------------------------------------------------------------------ accounts

async function register(req, env, ctx) {
  if (await limited(env.REG_RL, req, "register")) return tooMany("Too many new accounts from this network. Try again in a minute.");
  if (env.REGISTER_SECRET && !(await same(req.headers.get("X-Register-Secret"), env.REGISTER_SECRET)))
    return json({ error: "registration is closed on this server" }, 403);
  const account = rid(12), key = rid(32), viewToken = rid(24);
  const acc = { keyHash: await sha(key), viewToken, created: Date.now(), used: 0, pending: {} };
  await env.CLIPS.put(`a/${account}.json`, JSON.stringify(acc));
  await env.CLIPS.put(`i/${account}.json`, "[]");
  ctx.waitUntil(maybeHousekeep(env));
  return json({ account, key, viewToken, quota: quota(env) });
}

/** The app authenticates with its key; the phone gallery (when {@code galleryOk}) with the gallery token. */
async function auth(req, env, galleryOk = false) {
  const m = (req.headers.get("Authorization") || "").match(/^(Bearer|Gallery) ([A-Za-z0-9_-]{6,40})\.(.+)$/);
  if (!m) throw json({ error: "unauthorized" }, 401);
  const [, scheme, account, secret] = m;
  const acc = await getJson(env, `a/${account}.json`);
  if (!acc) throw json({ error: "unauthorized" }, 401);
  if (scheme === "Bearer" && (await same(await sha(secret), acc.keyHash))) return { account, acc, via: "app" };
  if (scheme === "Gallery" && galleryOk && (await same(secret, acc.viewToken))) return { account, acc, via: "phone" };
  throw json({ error: "unauthorized" }, 401);
}

const quota = (env) => Math.round(parseFloat(env.QUOTA_GB || "25") * 1e9);
/** Storage for the whole server (TOTAL_GB); 0 or unset means no server-wide limit. */
const totalLimit = (env) => Math.max(0, Math.round(parseFloat(env.TOTAL_GB || "0") * 1e9) || 0);

async function listClips(a, env) {
  const index = (await getJson(env, `i/${a.account}.json`)) || [];
  const reserved = reservedOf(prunePending(a.acc.pending, Date.now()));
  return json({ clips: index, used: a.acc.used || 0, reserved, quota: quota(env), gallery: `/g/${a.account}?t=${a.acc.viewToken}` });
}

/**
 * A new gallery link (the old one, and every link built from it, stops working). Private clips shared before
 * clips had their own keys get one now; the reply lists every private clip's key so the app can update its links.
 */
async function rotateGallery(a, env) {
  const viewToken = rid(24);
  await mutate(env, `a/${a.account}.json`, (acc) => {
    if (!acc) throw json({ error: "unauthorized" }, 401);
    return { ...acc, viewToken };
  });
  const index = (await getJson(env, `i/${a.account}.json`)) || [];
  const keys = {};
  await pool(index.filter((c) => c.visibility === "private" && !c.key && ID.test(c.id)), 6, async (c) => {
    const m = await mutate(env, `m/${c.id}.json`, (m) =>
      m && !m.removed && m.account === a.account && m.visibility === "private" && !m.key ? { ...m, key: rid(16) } : undefined);
    if (m && m.key && m.visibility === "private") keys[c.id] = m.key;
  });
  let list = index;
  if (Object.keys(keys).length) list = await reindex(env, a.account, (l) => l.map((c) => (keys[c.id] ? { ...c, key: keys[c.id] } : c)));
  const clips = (list || []).filter((c) => c.visibility === "private" && c.key).map((c) => ({ id: c.id, key: c.key, url: `/c/${c.id}?k=${c.key}` }));
  return json({ viewToken, gallery: `/g/${a.account}?t=${viewToken}`, clips });
}

/**
 * Deletes everything this account has on the server: its clips (videos, thumbnails, metadata and the reports
 * about them), unfinished uploads, community presets, notices and the account itself. A very large account is
 * deleted in steps: the reply says {done: false} until the last call.
 */
async function deleteAccount(a, env, ctx) {
  const index = (await getJson(env, `i/${a.account}.json`)) || [];
  const STEP = 300;
  const valid = index.filter((c) => c && ID.test(c.id));
  const batch = valid.slice(0, STEP);
  await deleteKeys(env, batch.flatMap((c) => [`v/${c.id}.mp4`, `t/${c.id}.jpg`, `m/${c.id}.json`, `u/${c.id}`]));
  await pool(batch, 6, (c) => deletePrefix(env, `r/${c.id}/`));
  const freed = batch.reduce((s, c) => s + (c.size || 0), 0);
  if (valid.length > batch.length) {
    const gone = new Set(batch.map((c) => c.id));
    const left = await reindex(env, a.account, (l) => l.filter((c) => c && ID.test(c.id) && !gone.has(c.id)));
    await accountUsed(env, a.account, -freed);
    bumpStats(env, ctx, -freed);
    return json({ ok: true, done: false, remaining: (left || []).length });
  }
  // Uploads that never finished.
  await pool(Object.keys(a.acc.pending || {}).filter((c) => ID.test(c)), 6, async (clip) => {
    const meta = await getJson(env, `m/${clip}.json`);
    if (meta && meta.account === a.account && !meta.done && !meta.removed) await dropUpload(env, clip, null, meta.uploadId);
  });
  // Community presets.
  const mine = ((await getJson(env, "presets.json")) || []).filter((p) => p.account === a.account).map((p) => p.id);
  if (mine.length) {
    await mutate(env, "presets.json", (l) => (l ? l.filter((p) => p.account !== a.account) : undefined));
    await purgePresets(env, mine);
  }
  await env.CLIPS.delete([`n/${a.account}.json`, `i/${a.account}.json`, `a/${a.account}.json`]);
  bumpStats(env, ctx, -(a.acc.used || 0));
  return json({ ok: true, done: true, clips: valid.length, presets: mine.length });
}

// ------------------------------------------------------------------ uploads (multipart, any size)

async function startUpload(req, a, env, ctx) {
  ctx.waitUntil(maybeHousekeep(env));
  const b = await body(req);
  const size = Math.floor(+b.size);
  if (!Number.isFinite(size) || size <= 0) return json({ error: "the upload's size is missing" }, 400);
  const q = quota(env);
  if (size > q) return json({ error: "storage full", used: a.acc.used || 0, quota: q }, 413);
  const total = totalLimit(env);
  if (total) {
    const st = await getJson(env, "stats.json");
    if (st && (st.used || 0) + size > total) return json({ error: "This share server is full right now. Try again later." }, 507);
  }
  const clip = rid(14), now = Date.now();
  let full = null;
  await mutate(env, `a/${a.account}.json`, (acc) => {
    if (!acc) throw json({ error: "unauthorized" }, 401);
    const pending = prunePending(acc.pending, now);
    const reserved = reservedOf(pending);
    full = (acc.used || 0) + reserved + size > q ? { used: acc.used || 0, reserved } : null;
    if (full) return undefined;
    return { ...acc, pending: { ...pending, [clip]: { size, at: now } } };
  });
  if (full) return json({ error: "storage full", used: full.used, reserved: full.reserved, quota: q }, 413);
  let mp;
  try {
    mp = await env.CLIPS.createMultipartUpload(`v/${clip}.mp4`, { httpMetadata: { contentType: "video/mp4" } });
  } catch (e) {
    await release(env, a.account, clip);
    throw e;
  }
  const meta = {
    id: clip, account: a.account, title: str(b.title, 120) || "Clip", game: str(b.game, 60),
    duration: num(b.duration, 0, 86400), width: Math.round(num(b.width, 0, 16384)), height: Math.round(num(b.height, 0, 16384)),
    size, created: now, done: false, visibility: b.visibility === "private" ? "private" : "link",
    origin: a.via === "phone" ? "phone" : "app", uploadId: mp.uploadId,
  };
  if (meta.visibility === "private") meta.key = rid(16);
  await env.CLIPS.put(`m/${clip}.json`, JSON.stringify(meta));
  await env.CLIPS.put(`u/${clip}`, "", { customMetadata: { account: a.account, uploadId: mp.uploadId } });
  return json({ clip, uploadId: mp.uploadId, partSize: PART_SIZE, url: `/c/${clip}${clipQuery(meta)}`, ...(meta.key ? { key: meta.key } : {}) });
}

/** The clip's metadata if this account owns it. The phone gallery may only touch clips uploaded from the phone. */
async function ownedMeta(env, a, clip, phoneOk = false) {
  if (!ID.test(clip)) throw json({ error: "bad id" }, 400);
  const meta = await getJson(env, `m/${clip}.json`);
  if (!meta || meta.account !== a.account || meta.removed) throw json({ error: "not found" }, 404);
  if (a.via === "phone" && !(phoneOk && meta.origin === "phone")) throw json({ error: "the phone gallery can only change clips uploaded from the phone" }, 403);
  return meta;
}

async function uploadPart(req, a, env, clip, n, url) {
  const meta = await ownedMeta(env, a, clip, true);
  if (meta.done) return json({ error: "this upload is already finished" }, 409);
  const uploadId = url.searchParams.get("uploadId") || "";
  if (!uploadId || (meta.uploadId && uploadId !== meta.uploadId)) return json({ error: "wrong upload" }, 400);
  // The announced size bounds the upload: every part but the last is the same size and at least 5 MiB.
  const limit = meta.size > 0 ? meta.size : quota(env);
  const maxPart = Math.min(PART_SIZE, limit);
  if (!(n >= 1 && n <= Math.min(PART_COUNT_MAX, Math.ceil(limit / PART_MIN)))) return json({ error: "more parts than the announced size needs" }, 413);
  const declared = req.headers.get("Content-Length");
  if (declared != null && +declared > maxPart) return json({ error: "part too large" }, 413);
  const data = await req.arrayBuffer();
  const len = data.byteLength;
  if (!len) return json({ error: "empty part" }, 400);
  if (len > maxPart) return json({ error: "part too large" }, 413);
  if ((n - 1) * len >= limit) return json({ error: "more data than the announced size" }, 413);
  let part;
  try {
    part = await env.CLIPS.resumeMultipartUpload(`v/${clip}.mp4`, uploadId).uploadPart(n, data);
  } catch (e) {
    console.error("upload part", clip, n, e);
    return json({ error: "That upload expired or was cancelled. Share the clip again." }, 404);
  }
  return json({ partNumber: part.partNumber, etag: part.etag });
}

async function completeUpload(req, a, env, ctx, clip) {
  const meta = await ownedMeta(env, a, clip, true);
  if (meta.done) {
    // A retried request: it already went through. Make sure the clip made it into the list (the earlier try may
    // have stopped just before that step).
    await reindex(env, a.account, (list) => (list.some((c) => c && c.id === clip) ? undefined : [entry(meta), ...list]), true);
    return json(finished(meta));
  }
  const b = await body(req);
  const uploadId = meta.uploadId || str(b.uploadId, 2048);
  if (!uploadId || (meta.uploadId && b.uploadId != null && b.uploadId !== meta.uploadId)) return json({ error: "wrong upload" }, 400);
  const raw = Array.isArray(b.parts) ? b.parts : [];
  const parts = raw.filter((p) => p && Number.isInteger(+p.partNumber) && typeof p.etag === "string").map((p) => ({ partNumber: +p.partNumber, etag: p.etag }));
  if (!parts.length || parts.length !== raw.length || parts.length > PART_COUNT_MAX) return json({ error: "bad parts" }, 400);
  let obj, recovered = false;
  try {
    obj = await env.CLIPS.resumeMultipartUpload(`v/${clip}.mp4`, uploadId).complete(parts);
  } catch (e) {
    // An earlier try may have put the video together and then failed before recording it (or its reply was
    // lost). Only finishing this upload writes v/<clip>.mp4, so if it's there, carry on from where that try stopped.
    obj = meta.uploadId ? await env.CLIPS.head(`v/${clip}.mp4`) : null;
    if (!obj) {
      console.error("complete upload", clip, e);
      return json({ error: "The upload couldn't be finished (a part is missing or the upload expired). Share the clip again." }, 400);
    }
    recovered = true;
  }
  const q = quota(env);
  const bigger = meta.size > 0 && obj.size > meta.size;
  let fits = !bigger;
  if (fits) {
    // Count the clip and drop its reservation in one step, so parallel uploads can't overrun the quota.
    await mutate(env, `a/${a.account}.json`, (acc) => {
      if (!acc) { fits = false; return undefined; }
      // The earlier try already counted it (that's when the reservation went).
      if (recovered && !(acc.pending && acc.pending[clip])) { fits = true; return undefined; }
      const pending = prunePending(acc.pending, Date.now());
      delete pending[clip];
      fits = (acc.used || 0) + reservedOf(pending) + obj.size <= q;
      return fits ? { ...acc, used: (acc.used || 0) + obj.size, pending } : { ...acc, pending };
    });
  }
  if (!fits) {
    await env.CLIPS.delete([`v/${clip}.mp4`, `m/${clip}.json`, `t/${clip}.jpg`, `u/${clip}`]);
    if (bigger) await release(env, a.account, clip);
    return json(bigger ? { error: "The upload is bigger than announced." } : { error: "storage full", quota: q }, 413);
  }
  const done = await mutate(env, `m/${clip}.json`, (m) => {
    if (!m || m.removed || m.done) return undefined;
    const { uploadId: _u, ...rest } = m;
    return { ...rest, size: obj.size, done: true };
  });
  if (!done || done.removed || !done.done) {
    // Taken down (or deleted) while it was uploading.
    await env.CLIPS.delete([`v/${clip}.mp4`, `u/${clip}`]);
    await accountUsed(env, a.account, -obj.size);
    return json({ error: "not found" }, 404);
  }
  await env.CLIPS.delete(`u/${clip}`);
  await reindex(env, a.account, (list) => [entry(done), ...list.filter((c) => c.id !== clip)], true);
  bumpStats(env, ctx, obj.size);
  return json(finished(done));
}

const finished = (m) => ({
  clip: m.id, url: `/c/${m.id}${clipQuery(m)}`, video: `/v/${m.id}.mp4${clipQuery(m)}`, size: m.size, ...(m.key ? { key: m.key } : {}),
});

/** Cancels an upload: its parts are thrown away and the space it reserved is freed. */
async function abortUpload(req, a, env, clip) {
  const meta = await ownedMeta(env, a, clip, true);
  if (meta.done) return json({ error: "this upload is already finished; delete the clip instead" }, 409);
  const b = await body(req, true);
  if (meta.uploadId && b.uploadId != null && b.uploadId !== meta.uploadId) return json({ error: "wrong upload" }, 400);
  await dropUpload(env, clip, a.account, meta.uploadId || str(b.uploadId, 2048));
  return json({ ok: true });
}

/** Gives up an unfinished upload: aborts its parts, drops its records and frees the space it reserved. */
async function dropUpload(env, clip, account, uploadId) {
  if (uploadId) {
    try { await env.CLIPS.resumeMultipartUpload(`v/${clip}.mp4`, uploadId).abort(); } catch (e) { /* already gone */ }
  }
  await env.CLIPS.delete([`m/${clip}.json`, `t/${clip}.jpg`, `u/${clip}`]);
  if (account) await release(env, account, clip);
}

/** Frees the space an upload reserved. */
async function release(env, account, clip) {
  await mutate(env, `a/${account}.json`, (acc) => {
    if (!acc || !acc.pending || !acc.pending[clip]) return undefined;
    const pending = { ...acc.pending };
    delete pending[clip];
    return { ...acc, pending };
  });
}

/** Adds {@code delta} bytes to an account's storage use. */
async function accountUsed(env, account, delta) {
  if (!delta) return;
  await mutate(env, `a/${account}.json`, (acc) => (acc ? { ...acc, used: Math.max(0, (acc.used || 0) + delta) } : undefined));
}

function prunePending(pending, now) {
  const out = {};
  for (const [k, v] of Object.entries(pending || {})) if (v && now - v.at < PENDING_TTL) out[k] = v;
  return out;
}
const reservedOf = (pending) => Object.values(pending).reduce((s, v) => s + (v.size || 0), 0);

async function putThumb(req, a, env, clip) {
  await ownedMeta(env, a, clip, true);
  if (+req.headers.get("Content-Length") > THUMB_MAX) return json({ error: "thumbnail too large" }, 413);
  const data = await req.arrayBuffer();
  if (data.byteLength > THUMB_MAX) return json({ error: "thumbnail too large" }, 413);
  const head = new Uint8Array(data, 0, Math.min(3, data.byteLength));
  if (head.length < 3 || head[0] !== 0xff || head[1] !== 0xd8 || head[2] !== 0xff) return json({ error: "thumbnails must be JPEG images" }, 415);
  await env.CLIPS.put(`t/${clip}.jpg`, data, { httpMetadata: { contentType: "image/jpeg" } });
  // Remember when the cover changed, so link previews (Discord caches them) and the gallery pick up the new one.
  const thumbAt = Date.now();
  const meta = await mutate(env, `m/${clip}.json`, (m) => (m && !m.removed && m.account === a.account ? { ...m, thumbAt } : undefined));
  if (meta && meta.done && meta.thumbAt === thumbAt) await reindex(env, a.account, (list) => list.map((c) => (c.id === clip ? { ...c, thumbAt } : c)));
  return json({ ok: true, thumbAt });
}

/**
 * Change a clip's title or who can see it ("link": anyone with the link, "private": only people given its
 * private link). Making a clip private gives it a new key, so links shared while it was private before stop working.
 */
async function updateClip(req, a, env, clip) {
  if (!ID.test(clip)) return json({ error: "bad id" }, 400);
  const b = await body(req);
  const title = typeof b.title === "string" && b.title.trim() ? str(b.title, 120) : null;
  const meta = await mutate(env, `m/${clip}.json`, (m) => {
    if (!m || m.account !== a.account || m.removed) throw json({ error: "not found" }, 404);
    const next = { ...m };
    if (b.visibility === "private") {
      if (next.visibility !== "private" || !next.key) next.key = rid(16);
      next.visibility = "private";
    } else if (b.visibility === "link") {
      next.visibility = "link";
      delete next.key;
    }
    if (title) next.title = title;
    return next;
  });
  if (meta.done) await reindex(env, a.account, (list) => list.map((c) => (c.id === clip ? entry(meta) : c)));
  return json({ ok: true, visibility: meta.visibility, url: `/c/${clip}${clipQuery(meta)}`, ...(meta.key ? { key: meta.key } : {}) });
}

async function deleteClip(a, env, ctx, clip) {
  const meta = await ownedMeta(env, a, clip);
  if (!meta.done) await dropUpload(env, clip, a.account, meta.uploadId);
  await env.CLIPS.delete([`v/${clip}.mp4`, `t/${clip}.jpg`, `m/${clip}.json`, `u/${clip}`]);
  if (meta.done) {
    await accountUsed(env, a.account, -(meta.size || 0));
    bumpStats(env, ctx, -(meta.size || 0));
  }
  await reindex(env, a.account, (list) => list.filter((c) => c.id !== clip));
  await deletePrefix(env, `r/${clip}/`);
  return json({ ok: true });
}

/** Read-modify-write of the account's clip list (see mutate); {@code create} makes the list if it's missing. */
const reindex = (env, account, fn, create = false) =>
  mutate(env, `i/${account}.json`, (list) => (list || create ? fn(list || []) : undefined));

/** The clip's entry in its owner's list (the app and the phone gallery read these). */
const entry = (m) => ({
  id: m.id, title: m.title, game: m.game, duration: m.duration, width: m.width, height: m.height, size: m.size,
  created: m.created, visibility: m.visibility || "link", origin: m.origin || "app",
  ...(m.visibility === "private" && m.key ? { key: m.key } : {}), ...(m.thumbAt ? { thumbAt: m.thumbAt } : {}),
});

// ------------------------------------------------------------------ moderation

/** Anyone can report a clip, once; reports wait for a moderator (GET /api/admin/reports). */
async function report(req, env) {
  if (await limited(env.ACT_RL, req, "report")) return tooMany("Too many reports from this network. Try again in a minute.");
  const b = await body(req, true);
  const clip = String(b.clip || "");
  if (!ID.test(clip) || !REASONS[b.reason]) return json({ error: "bad report" }, 400);
  const meta = await getJson(env, `m/${clip}.json`);
  if (!meta || meta.removed || !meta.done) return json({ error: "not found" }, 404);
  const key = `r/${clip}/${await viewer(req, env)}.json`;
  const rec = {
    clip, title: meta.title, visibility: meta.visibility || "link", link: `/c/${clip}${clipQuery(meta)}`,
    reason: b.reason, reasonText: REASONS[b.reason], details: str(b.details, 500), at: Date.now(),
  };
  await putReport(env, key, rec);
  return json({ ok: true });
}

/** Stores a report unless this viewer already reported the same thing (the first report stands). */
async function putReport(env, key, rec) {
  const s = JSON.stringify(rec);
  // A copy in the custom metadata lets the moderator listing read a page of reports in one call.
  const meta = new TextEncoder().encode(s).length <= 6000 ? { customMetadata: { j: s } } : {};
  await env.CLIPS.put(key, s, { ...meta, onlyIf: new Headers({ "If-None-Match": "*" }) });
}

async function admin(req, env) {
  if (!(await isAdmin(req, env))) throw json({ error: "unauthorized" }, 401);
}
async function isAdmin(req, env) {
  return !!env.ADMIN_TOKEN && (await same(req.headers.get("Authorization"), `Admin ${env.ADMIN_TOKEN}`));
}

/** One page of reports (?type=presets for preset reports); pass the returned cursor as ?cursor= for the next. */
async function adminReports(req, env, url) {
  await admin(req, env);
  const presets = url.searchParams.get("type") === "presets";
  const limit = Math.max(1, Math.min(1000, Math.floor(+url.searchParams.get("limit")) || 500));
  let list;
  try {
    list = await env.CLIPS.list({ prefix: presets ? "rp/" : "r/", limit, cursor: url.searchParams.get("cursor") || undefined, include: ["customMetadata"] });
  } catch (e) {
    return json({ error: "bad cursor" }, 400);
  }
  const reports = await pool(list.objects, 6, async (o) => {
    let r = null;
    try { r = o.customMetadata && o.customMetadata.j ? JSON.parse(o.customMetadata.j) : null; } catch (e) { r = null; }
    if (!r) r = await getJson(env, o.key);
    return r && { ...r, key: o.key };
  });
  return json({ reports: reports.filter(Boolean), cursor: list.truncated ? list.cursor : null });
}

/** Deletes reports a moderator has dealt with: {key} or {keys: [...]}, report keys only. */
async function adminDismiss(req, env) {
  await admin(req, env);
  const b = await body(req);
  const keys = (Array.isArray(b.keys) ? b.keys : [b.key]).map((k) => String(k || ""));
  if (!keys.length || keys.length > 1000 || !keys.every((k) => REPORT_KEY.test(k))) return json({ error: "only report keys (r/… or rp/…) can be dismissed" }, 400);
  await env.CLIPS.delete(keys);
  return json({ ok: true, dismissed: keys.length });
}

/**
 * Takes a clip down for a reason. The video goes, the link explains why, and the owner gets a notice in the
 * app naming the clip and the reason. Only a tombstone (which rule, when) stays on the server.
 */
async function adminRemove(req, env, ctx) {
  await admin(req, env);
  const b = await body(req);
  const clip = String(b.clip || "");
  const reason = REASONS[b.reason] ? b.reason : "other";
  const meta = ID.test(clip) ? await getJson(env, `m/${clip}.json`) : null;
  if (!meta || meta.removed) return json({ error: "not found" }, 404);
  if (!meta.done) await dropUpload(env, clip, meta.account, meta.uploadId);
  await env.CLIPS.delete([`v/${clip}.mp4`, `t/${clip}.jpg`, `u/${clip}`]);
  const at = Date.now(), note = str(b.note, 500);
  await env.CLIPS.put(`m/${clip}.json`, JSON.stringify({ id: clip, removed: { reason, at } }));
  if (meta.done) {
    await accountUsed(env, meta.account, -(meta.size || 0));
    bumpStats(env, ctx, -(meta.size || 0));
  }
  await reindex(env, meta.account, (list) => list.filter((c) => c.id !== clip));
  if (await env.CLIPS.head(`a/${meta.account}.json`)) {
    await mutate(env, `n/${meta.account}.json`, (n) =>
      [{ id: rid(8), clip, title: meta.title, reason, reasonText: REASONS[reason], note, at, read: false }, ...(n || [])].slice(0, 200));
  }
  await deletePrefix(env, `r/${clip}/`);
  return json({ ok: true });
}

// ------------------------------------------------------------------ community presets

/** What the public sees of a preset (who published it stays on the server). */
const presetInfo = (p) => ({ id: p.id, name: p.name, description: p.description || "", author: p.author || "", created: p.created, downloads: p.downloads || 0 });

function sortedPresets(index, popular) {
  const list = [...index];
  if (popular) list.sort((a, b) => (b.downloads || 0) - (a.downloads || 0) || b.created - a.created);
  return list;
}

async function listPresets(env, url) {
  const index = (await getJson(env, "presets.json")) || [];
  return json({ presets: sortedPresets(index, url.searchParams.get("sort") === "popular").slice(0, 200).map(presetInfo) });
}

async function publishPreset(req, a, env, ctx) {
  if (await limited(env.ACT_RL, req, "publish")) return tooMany("You're sharing presets too quickly. Try again in a minute.");
  if (+req.headers.get("Content-Length") > PRESET_MAX) return json({ error: "preset too large" }, 413);
  const text = await req.text();
  if (text.length > PRESET_MAX) return json({ error: "preset too large" }, 413);
  let b;
  try { b = JSON.parse(text); } catch (e) { return json({ error: "bad preset" }, 400); }
  const name = str(b && b.name, 60).trim(), preset = b && b.preset;
  if (!name || !preset || typeof preset !== "object" || Array.isArray(preset)) return json({ error: "a preset needs a name" }, 400);
  const tooManyMine = (index) => index.filter((x) => x.account === a.account).length >= PRESETS_PER_ACCOUNT;
  const capped = () => json({ error: `you've shared the most presets one PC can (${PRESETS_PER_ACCOUNT})` }, 429);
  if (tooManyMine((await getJson(env, "presets.json")) || [])) return capped();
  const id = rid(10);
  const entry = { id, name, description: str(b.description, 300), author: str(b.author, 40).trim(), created: Date.now(), downloads: 0, account: a.account };
  await env.CLIPS.put(`p/${id}.json`, JSON.stringify({ ...entry, preset }));
  let evicted = [];
  try {
    await mutate(env, "presets.json", (index) => {
      index = index || [];
      if (tooManyMine(index)) throw capped();
      const list = [entry, ...index];
      evicted = evictPresets(list, PRESETS_MAX);
      const gone = new Set(evicted);
      return list.filter((e) => !gone.has(e.id));
    });
  } catch (e) {
    await env.CLIPS.delete(`p/${id}.json`);
    throw e;
  }
  if (evicted.length) ctx.waitUntil(purgePresets(env, evicted).catch((e) => console.error("evict presets", e)));
  return json({ id, url: `/presets#${id}` });
}

/**
 * Which presets leave the index when it's over {@code max}: the least downloaded first, counting each account's
 * downloads against how many presets it has published (so mass-publishing loses), oldest first on a tie.
 * Presets from the last three days get a chance to be found before they can go.
 */
function evictPresets(list, max) {
  if (list.length <= max) return [];
  const per = {};
  for (const e of list) per[e.account] = (per[e.account] || 0) + 1;
  const fresh = Date.now() - 3 * 86400e3;
  const score = (e) => ((e.downloads || 0) + 1) / (per[e.account] || 1);
  const order = [...list].sort((x, y) => (x.created > fresh) - (y.created > fresh) || score(x) - score(y) || x.created - y.created);
  return order.slice(0, list.length - max).map((e) => e.id);
}

/** Deletes presets for good: the preset, its uncounted downloads and its reports. */
async function purgePresets(env, ids) {
  ids = ids.filter((id) => ID.test(id));
  await deleteKeys(env, ids.map((id) => `p/${id}.json`));
  await pool(ids, 6, async (id) => {
    await deletePrefix(env, `pd/${id}/`);
    await deletePrefix(env, `rp/${id}/`);
  });
}

async function getPreset(req, env, ctx, id, asFile) {
  if (!ID.test(id)) return json({ error: "not found" }, 404);
  const p = await getJson(env, `p/${id}.json`);
  if (!p || p.removed) return asFile ? notFound(env, "That preset was removed or never existed.") : json({ error: "not found" }, 404);
  // One download per viewer per day, counted later (the index isn't rewritten on every download).
  ctx.waitUntil(countDownload(req, env, id).catch((e) => console.error("count download", e)));
  const out = { format: "evade-clips-preset", version: 1, ...p.preset, name: p.name, description: p.description, author: p.author };
  if (!asFile) return json(out);
  return new Response(JSON.stringify(out, null, 2), { headers: { "Content-Type": "application/json", "Content-Disposition": `attachment; filename="${safeName(p.name)}.evadepreset"`, ...cors() } });
}

async function countDownload(req, env, id) {
  await env.CLIPS.put(`pd/${id}/${day(Date.now())}/${await viewer(req, env)}`, "", { onlyIf: new Headers({ "If-None-Match": "*" }) });
}

async function deletePreset(a, env, ctx, id) {
  const p = ID.test(id) ? await getJson(env, `p/${id}.json`) : null;
  if (!p || p.removed || p.account !== a.account) return json({ error: "not found" }, 404);
  await mutate(env, "presets.json", (index) => (index ? index.filter((x) => x.id !== id) : undefined));
  await purgePresets(env, [id]);
  return json({ ok: true });
}

/** Anyone can report a preset, once; moderators see them with GET /api/admin/reports?type=presets. */
async function reportPreset(req, env, id) {
  if (await limited(env.ACT_RL, req, "report")) return tooMany("Too many reports from this network. Try again in a minute.");
  const b = await body(req, true);
  if (!ID.test(id) || !REASONS[b.reason]) return json({ error: "bad report" }, 400);
  const p = await getJson(env, `p/${id}.json`);
  if (!p || p.removed) return json({ error: "not found" }, 404);
  await putReport(env, `rp/${id}/${await viewer(req, env)}.json`, {
    preset: id, name: p.name, author: p.author || "", description: p.description || "",
    reason: b.reason, reasonText: REASONS[b.reason], details: str(b.details, 500), at: Date.now(),
  });
  return json({ ok: true });
}

async function adminRemovePreset(req, env, ctx) {
  await admin(req, env);
  const b = await body(req);
  const id = String(b.id || "");
  const p = ID.test(id) ? await getJson(env, `p/${id}.json`) : null;
  if (!p || p.removed) return json({ error: "not found" }, 404);
  await mutate(env, "presets.json", (index) => (index ? index.filter((x) => x.id !== id) : undefined));
  await purgePresets(env, [id]);
  // Only a tombstone stays, so the download link says it was removed.
  await env.CLIPS.put(`p/${id}.json`, JSON.stringify({ id, removed: { at: Date.now(), note: str(b.note, 500) } }));
  return json({ ok: true });
}

/** Browse community presets; each downloads as a .evadepreset file to import in Evade Clips. */
async function presetsPage(env, url) {
  const index = (await getJson(env, "presets.json")) || [];
  const popular = url.searchParams.get("sort") === "popular";
  const list = sortedPresets(index, popular).map(presetInfo);
  const cards = list.slice(0, 120).map((p) => `<div class="card preset-card" id="${esc(p.id)}">
<h3>${esc(p.name)}</h3><p>${esc(p.description)}</p>
<div class="preset-meta"><span>${p.author ? "by " + esc(p.author) + " &middot; " : ""}${p.downloads} download${p.downloads === 1 ? "" : "s"}</span>
<span class="preset-actions"><button type=button class="clip-report" data-report="${esc(p.id)}">Report</button><a class="btn btn-ghost btn-sm" href="/p/${esc(p.id)}.evadepreset">Download</a></span></div></div>`).join("");
  const body = `
<div class="sec-head"><div class="eyebrow">Community</div><h1>Edit presets.</h1>
<p>Looks and per-kill effects made by other players. Download one, then in Evade Clips open a clip and, in the <b>Edit</b> tab under <b>Presets</b>, pick <b>Import file</b>. Or click <b>Community</b> there to browse and share right inside the app.</p>
<div class="preset-sort"><a class="btn btn-sm ${popular ? "btn-ghost" : "btn-red"}" href="/presets">Newest</a><a class="btn btn-sm ${popular ? "btn-red" : "btn-ghost"}" href="/presets?sort=popular">Most downloaded</a></div></div>
${cards ? `<div class="preset-grid">${cards}</div>` : `<div class="card preset-card"><p>No community presets yet. Be the first: share one from Evade Clips.</p></div>`}
<form id=prep class="clip-form preset-report" hidden onsubmit="return sendPresetReport(event)">
  <h3>Report this preset</h3>
  <select name=reason>${reasonOptions()}</select>
  <input name=details maxlength=500 placeholder="Details (optional)">
  <button class="btn btn-ghost btn-sm">Send report</button>
  <p class="clip-status" id=prst></p>
</form>
<script>
const prep=document.getElementById('prep'),prst=document.getElementById('prst');let prid=null;
document.querySelectorAll('[data-report]').forEach(b=>b.onclick=()=>{const c=b.closest('.preset-card');if(prid===b.dataset.report&&!prep.hidden){prep.hidden=true;return}
prid=b.dataset.report;prep.reset();prst.textContent='';prep.querySelector('button').disabled=false;c.appendChild(prep);prep.hidden=false});
async function sendPresetReport(e){e.preventDefault();const f=new FormData(prep),btn=prep.querySelector('button');btn.disabled=true;
try{const r=await fetch('/api/presets/'+encodeURIComponent(prid)+'/report',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({reason:f.get('reason'),details:f.get('details')})});
prst.textContent=r.ok?'Thanks. A moderator will look at it.':r.status===429?'Too many reports right now. Try again in a minute.':'Couldn\\'t send the report. Try again later.'}
catch(err){prst.textContent='Couldn\\'t send the report. Try again later.'}if(!prst.textContent.startsWith('Thanks'))btn.disabled=false;return false}
</script>`;
  const css = `<style>.preset-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px;margin-top:22px}
.preset-card{padding:18px 20px}.preset-card h3{margin:0 0 6px;font-family:var(--display);font-size:18px}.preset-card p{margin:0 0 14px;color:var(--muted);font-size:14px}
.preset-meta{display:flex;justify-content:space-between;align-items:center;gap:10px;color:var(--faint);font-size:13px}.preset-sort{display:flex;gap:8px;margin-top:16px}
.preset-actions{display:flex;align-items:center;gap:14px}.preset-actions .clip-report{font-size:13px}
.preset-card .preset-report{padding:14px 0 0;margin:14px 0 0;border-top:1px solid var(--stroke);max-width:none}.preset-card .preset-report p{margin:0}</style>`;
  return html(page(env, "Edit presets", body, css));
}

/** The owner's notices; ?unread=1 for new ones only, which are then marked read. */
async function notices(a, env, url) {
  const n = (await getJson(env, `n/${a.account}.json`)) || [];
  const out = url.searchParams.get("unread") === "1" ? n.filter((x) => !x.read) : n;
  const fresh = new Set(out.filter((x) => !x.read).map((x) => x.id));
  if (fresh.size) await mutate(env, `n/${a.account}.json`, (cur) => (cur ? cur.map((x) => (fresh.has(x.id) ? { ...x, read: true } : x)) : undefined));
  return json({ notices: out });
}

// ------------------------------------------------------------------ public pages

/**
 * Private clips open only with their own key (?k=). The owner's gallery token (?t=) still opens clips made
 * private before clips had keys, and any private clip when the request comes from the owner's gallery page.
 * Moderators ("Authorization: Admin …") see every clip.
 */
async function visible(req, env, meta, url) {
  if (!meta || !meta.done || meta.removed) return false;
  if (meta.visibility !== "private") return true;
  const k = url.searchParams.get("k");
  if (k && meta.key && (await same(k, meta.key))) return true;
  if (await isAdmin(req, env)) return true;
  const t = url.searchParams.get("t");
  if (t && (!meta.key || fromGallery(req, url, meta.account))) {
    const acc = await getJson(env, `a/${meta.account}.json`);
    return !!acc && (await same(t, acc.viewToken));
  }
  return false;
}

function fromGallery(req, url, account) {
  try {
    const r = new URL(req.headers.get("Referer") || "");
    return r.origin === url.origin && r.pathname === `/g/${account}`;
  } catch (e) {
    return false;
  }
}

/** "?k=…" for a private clip (or the older "?t=…" when it has no key yet), plus {@code extra} params; "" if none. */
function clipQuery(meta, t = null, extra = {}) {
  const q = new URLSearchParams();
  if (meta.visibility === "private") {
    if (meta.key) q.set("k", meta.key);
    else if (t) q.set("t", t);
  }
  for (const [k, v] of Object.entries(extra)) if (v != null && v !== "") q.set(k, String(v));
  const s = q.toString();
  return s ? "?" + s : "";
}

async function video(req, env, clip, url) {
  if (!ID.test(clip)) return new Response("not found", { status: 404 });
  const meta = await getJson(env, `m/${clip}.json`);
  if (!(await visible(req, env, meta, url))) return new Response("not found", { status: 404 });
  const key = `v/${clip}.mp4`;
  const h = new Headers({
    "Content-Type": "video/mp4", "Accept-Ranges": "bytes",
    "Cache-Control": meta.visibility === "private" ? "private, max-age=3600" : "public, max-age=31536000, immutable",
    "Access-Control-Allow-Origin": "*",
  });
  if (url.searchParams.has("dl")) h.set("Content-Disposition", `attachment; filename="${safeName(meta.title)}.mp4"`);
  const range = req.method === "GET" ? parseRange(req.headers.get("Range")) : null;
  if (req.method === "HEAD" || range) {
    const info = await env.CLIPS.head(key);
    if (!info) return new Response("not found", { status: 404 });
    const size = info.size;
    h.set("ETag", info.httpEtag);
    if (!range) {
      h.set("Content-Length", String(size));
      return new Response(null, { headers: h });
    }
    let start, end;
    if (range.bad) start = end = -1;
    else if (range.suffix != null) { start = Math.max(0, size - range.suffix); end = range.suffix > 0 ? size - 1 : -1; }
    else { start = range.offset; end = Math.min(range.end != null ? range.end : size - 1, size - 1); }
    if (size === 0 || start < 0 || start >= size || end < start) {
      h.set("Content-Range", `bytes */${size}`);
      return new Response(null, { status: 416, headers: h });
    }
    const obj = await env.CLIPS.get(key, { range: { offset: start, length: end - start + 1 } });
    if (!obj) return new Response("not found", { status: 404 });
    h.set("Content-Range", `bytes ${start}-${end}/${size}`);
    h.set("Content-Length", String(end - start + 1));
    return new Response(obj.body, { status: 206, headers: h });
  }
  const obj = await env.CLIPS.get(key);
  if (!obj) return new Response("not found", { status: 404 });
  h.set("ETag", obj.httpEtag);
  h.set("Content-Length", String(obj.size));
  return new Response(obj.body, { headers: h });
}

/** A single "bytes=a-b", "bytes=a-" or "bytes=-n" range; {bad: true} when it can't be satisfied as written; null to ignore. */
function parseRange(v) {
  const m = v && v.trim().match(/^bytes=(\d*)-(\d*)$/);
  if (!m || (m[1] === "" && m[2] === "")) return null;
  if (m[1] === "") return { suffix: +m[2] };
  const offset = +m[1];
  if (m[2] === "") return { offset };
  const end = +m[2];
  return end < offset ? { bad: true } : { offset, end };
}

async function thumb(req, env, clip, url) {
  if (!ID.test(clip)) return new Response("not found", { status: 404 });
  const meta = await getJson(env, `m/${clip}.json`);
  if (!(await visible(req, env, meta, url))) return new Response("not found", { status: 404 });
  const obj = req.method === "HEAD" ? await env.CLIPS.head(`t/${clip}.jpg`) : await env.CLIPS.get(`t/${clip}.jpg`);
  if (!obj) return new Response("not found", { status: 404 });
  return new Response(req.method === "HEAD" ? null : obj.body, {
    headers: {
      "Content-Type": "image/jpeg", "Content-Length": String(obj.size), "X-Content-Type-Options": "nosniff",
      "Cache-Control": meta.visibility === "private" ? "private, max-age=3600" : "public, max-age=86400",
    },
  });
}

async function sharePage(req, env, clip, url) {
  const meta = ID.test(clip) ? await getJson(env, `m/${clip}.json`) : null;
  if (meta && meta.removed) {
    return html(page(env, "Clip removed", `<div class="clips-msg card"><h1>This clip was removed.</h1>
<p>It was taken down for ${esc(REASONS[meta.removed.reason] || "breaking the rules")}. Its owner has been told which clip and why.</p>
<a class="btn btn-ghost" href="/">About Evade Clips</a></div>`), 410);
  }
  if (!(await visible(req, env, meta, url))) return notFound(env, "This clip is private, was deleted, or never finished uploading.");
  const priv = meta.visibility === "private";
  const t = url.searchParams.get("t");
  const tq = clipQuery(meta, t); // carries the clip's key (or the older gallery token) through to the video and thumbnail
  const o = url.origin, v = `${o}/v/${clip}.mp4${tq}`, ve = esc(v), w = meta.width || 1280, hgt = meta.height || 720;
  const th = `${o}/t/${clip}.jpg${clipQuery(meta, t, { v: meta.thumbAt })}`; // ?v= so a changed cover shows up in new posts
  const title = esc(meta.title) + (meta.game ? " · " + esc(meta.game) : "");
  // Open Graph video tags: Discord, Twitter/X, iMessage and friends play the clip right in the chat.
  const head = `<meta name="description" content="${title} — shared with Evade Clips.">` + (priv ? '<meta name="robots" content="noindex">' : `
<meta property="og:type" content="video.other">
<meta property="og:site_name" content="${esc(env.SITE_NAME || "Evade Clips")}">
<meta property="og:title" content="${title}">
<meta property="og:url" content="${o}/c/${clip}">
<meta property="og:image" content="${esc(th)}">
<meta property="og:video" content="${ve}">
<meta property="og:video:url" content="${ve}">
<meta property="og:video:secure_url" content="${ve}">
<meta property="og:video:type" content="video/mp4">
<meta property="og:video:width" content="${w}">
<meta property="og:video:height" content="${hgt}">
<meta name="twitter:card" content="player">
<meta name="twitter:title" content="${title}">
<meta name="twitter:image" content="${esc(th)}">
<meta name="twitter:player" content="${o}/c/${clip}">
<meta name="twitter:player:stream" content="${ve}">
<meta name="twitter:player:stream:content_type" content="video/mp4">
<meta name="twitter:player:width" content="${w}">
<meta name="twitter:player:height" content="${hgt}">`);
  const facts = [esc(meta.game), dur(meta.duration), mb(meta.size), new Date(meta.created).toISOString().slice(0, 10)].filter(Boolean);
  const body = `
<div class="clip-player"><video id=v src="${ve}" poster="${esc(th)}" controls muted playsinline loop style="aspect-ratio:${w}/${hgt}"></video></div>
<div class="clip-bar">
  <div class="clip-info">
    <div class="eyebrow">${meta.game ? esc(meta.game) : "Evade Clips"}${priv ? " &middot; Private" : ""}</div>
    <h1>${esc(meta.title)}</h1>
    <p class="clip-facts">${facts.join(" &middot; ")}</p>
  </div>
  <div class="cta">
    <a class="btn btn-red" href="${esc(v + (tq ? "&" : "?") + "dl=1")}">Download</a>
    <button class="btn btn-ghost" onclick="navigator.clipboard.writeText(location.href);this.textContent='Link copied'">Copy link</button>
  </div>
</div>
<div class="clip-tools">
  <label class="switch"><input type=checkbox id=ap><span></span> Autoplay</label>
  <button class="link-inline clip-report" onclick="document.getElementById('rep').hidden=!document.getElementById('rep').hidden">Report this clip</button>
</div>
<form id=rep class="card clip-form" hidden onsubmit="return sendReport(event)">
  <h3>Report this clip</h3>
  <select name=reason>${reasonOptions()}</select>
  <input name=details maxlength=500 placeholder="Details (optional)">
  <button class="btn btn-ghost btn-sm">Send report</button>
</form>
<div class="card clip-promo">
  <div><h3>Made with Evade Clips</h3><p>Free instant replay, auto-clips and an editor, with share links like this one. No watermark, no ads.</p></div>
  <a class="btn btn-ghost" href="/">Get Evade Clips</a>
</div>
<script>
const ap=document.getElementById('ap'),vid=document.getElementById('v');
let on=true;try{on=localStorage.getItem('autoplay')!=='off'}catch(e){}
ap.checked=on;if(on)vid.play().catch(()=>{});
ap.onchange=()=>{try{localStorage.setItem('autoplay',ap.checked?'on':'off')}catch(e){};if(ap.checked)vid.play().catch(()=>{});else vid.pause()};
async function sendReport(e){e.preventDefault();const f=new FormData(e.target);let r=null;
try{r=await fetch('/api/report',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({clip:${JSON.stringify(clip)},reason:f.get('reason'),details:f.get('details')})})}catch(err){}
e.target.innerHTML=r&&r.ok?'<p>Thanks. A moderator will look at it.</p>':r&&r.status===429?'<p>Too many reports right now. Try again in a minute.</p>':'<p>Couldn\\'t send the report. Try again later.</p>';return false}
</script>`;
  return html(page(env, meta.title, body, head));
}

async function gallery(env, account, url) {
  const acc = ID.test(account) ? await getJson(env, `a/${account}.json`) : null;
  const t = url.searchParams.get("t");
  if (!acc || !(await same(t, acc.viewToken)))
    return notFound(env, "This gallery link isn't valid. Open it again from Evade Clips (Settings → Sharing & phone → Show phone QR code).");
  const index = (await getJson(env, `i/${account}.json`)) || [];
  const cards = index.map((c) => {
    const tags = (c.visibility === "private" ? '<span class="tag">Private</span>' : "") + (c.origin === "phone" ? '<span class="tag">From phone</span>' : "");
    return `<a class="card shot-card clip-card" href="/c/${esc(c.id)}${esc(clipQuery(c, t))}">
<div class="img"><img loading=lazy src="/t/${esc(c.id)}.jpg${esc(clipQuery(c, t, { v: c.thumbAt }))}" alt=""><span class="clip-dur">${dur(c.duration)}</span></div>
<div class="txt">${tags ? `<div class="clip-tags">${tags}</div>` : ""}<h3>${esc(c.title)}</h3><p>${[esc(c.game), mb(c.size)].filter(Boolean).join(" &middot; ")}</p></div></a>`;
  }).join("");
  const usedBytes = acc.used || 0;
  const used = usedBytes > 0 ? Math.max(2, Math.min(100, Math.round((usedBytes / quota(env)) * 100))) : 0;
  const body = `
<div class="sec-head"><div class="eyebrow">Your gallery</div><h1>Your clips.</h1>
<p>${index.length} clip${index.length === 1 ? "" : "s"}. Tap one to watch, download or share it. Keep this link to yourself: anyone who has it can see your clips. If it gets out, reset it in Evade Clips (Settings → Sharing &amp; phone → Reset phone link).</p></div>
<div class="card clip-storage"><div class="clip-storage-row"><span>Storage</span><span>${mb(usedBytes)} of ${mb(quota(env))}</span></div><div class="clip-meter"><i style="width:${used}%"></i></div></div>
<form id=up class="card clip-form clip-upload" onsubmit="return upload(event)">
  <h3>Upload from this phone</h3>
  <p>Console captures and phone recordings land here and in Evade Clips on your PC.</p>
  <input type=file name=f accept="video/*" required>
  <div class="clip-upload-row"><input name=title placeholder="Title" maxlength=120><input name=game placeholder="Game" maxlength=60></div>
  <button class="btn btn-red">Upload</button>
  <p class="clip-status" id=st></p>
</form>
<div class="clip-grid">${cards || '<p class="clip-empty">Nothing here yet. Share a clip from Evade Clips, or upload one above.</p>'}</div>
<script>
const AUTH='Gallery ${account}.'+new URLSearchParams(location.search).get('t');
async function api(path,opt){opt=opt||{};opt.headers=Object.assign({Authorization:AUTH},opt.headers||{});const r=await fetch(path,opt);if(!r.ok)throw new Error((await r.json().catch(()=>({}))).error||r.status);return r.json()}
function frame(file){return new Promise(res=>{const v=document.createElement('video');v.muted=true;v.playsInline=true;v.src=URL.createObjectURL(file);
v.onloadeddata=()=>{v.currentTime=Math.min(1,v.duration/2)};v.onseeked=()=>{const c=document.createElement('canvas');c.width=480;c.height=Math.round(480*v.videoHeight/v.videoWidth)||270;
c.getContext('2d').drawImage(v,0,0,c.width,c.height);c.toBlob(b=>res({thumb:b,duration:v.duration,width:v.videoWidth,height:v.videoHeight}),'image/jpeg',0.8)};v.onerror=()=>res({})})}
async function upload(e){e.preventDefault();const f=new FormData(e.target),file=f.get('f'),st=document.getElementById('st'),btn=e.target.querySelector('button');let s=null;btn.disabled=true;
try{st.textContent='Preparing…';const info=await frame(file);
s=await api('/api/uploads',{method:'POST',body:JSON.stringify({title:f.get('title')||file.name.replace(/\\.[^.]+$/,''),game:f.get('game'),size:file.size,duration:info.duration||0,width:info.width||0,height:info.height||0})});
const ps=10*1024*1024,parts=[];for(let i=0,n=1;i<file.size;i+=ps,n++){st.textContent='Uploading '+Math.round(i/file.size*100)+'%';
parts.push(await api('/api/uploads/'+s.clip+'/'+n+'?uploadId='+encodeURIComponent(s.uploadId),{method:'PUT',body:file.slice(i,i+ps)}))}
await api('/api/uploads/'+s.clip+'/complete',{method:'POST',body:JSON.stringify({uploadId:s.uploadId,parts})});const clip=s.clip;s=null;
if(info.thumb)await api('/api/clips/'+clip+'/thumb',{method:'PUT',body:info.thumb}).catch(()=>{});
st.textContent='Uploaded.';location.reload()}catch(err){if(s)api('/api/uploads/'+s.clip+'/abort',{method:'POST',body:JSON.stringify({uploadId:s.uploadId})}).catch(()=>{});
st.textContent='Upload failed: '+err.message;btn.disabled=false}return false}
</script>`;
  return html(page(env, "Your clips", body, '<meta name="robots" content="noindex">'));
}

function notFound(env, why) {
  return html(page(env, "Not found", `<div class="clips-msg card"><h1>Nothing here.</h1><p>${esc(why || "This page doesn't exist.")}</p>
<a class="btn btn-ghost" href="/">About Evade Clips</a></div>`), 404);
}

/**
 * Downloads: from the bundled static files when the Worker was deployed with them, otherwise from the R2
 * bucket under dl/ (upload with `npx wrangler r2 object put evade-clips/dl/<file> --file ...`).
 */
async function download(req, env, name) {
  if (!/^[\w.-]+$/.test(name)) return new Response("not found", { status: 404 });
  if (env.ASSETS) {
    const r = await env.ASSETS.fetch(req);
    if (r.ok) return r;
  }
  const obj = await env.CLIPS.get(`dl/${name}`);
  if (!obj) return new Response("not found", { status: 404 });
  const type = name.endsWith(".json") ? "application/json" : "application/octet-stream";
  const h = { "Content-Type": type, "Content-Length": String(obj.size), "Cache-Control": "public, max-age=300" };
  if (!name.endsWith(".json")) h["Content-Disposition"] = `attachment; filename="${name}"`;
  return new Response(obj.body, { headers: h });
}

/** The main site's stylesheet, scripts, logo and screenshots (synced into R2 under site/ by sync-assets.sh). */
async function siteFile(env, name) {
  if (!/^[\w.-]+$/.test(name)) return new Response("not found", { status: 404 });
  const obj = await env.CLIPS.get(`site/${name}`);
  if (!obj) return new Response("not found", { status: 404 });
  const types = { css: "text/css; charset=utf-8", js: "text/javascript; charset=utf-8", svg: "image/svg+xml", png: "image/png", webp: "image/webp" };
  return new Response(obj.body, { headers: { "Content-Type": types[name.split(".").pop()] || "application/octet-stream", "Cache-Control": "public, max-age=3600" } });
}

/** clips.evadeclient.com: the same page as evadeclient.com/clips, in the main site's design. */
function home() {
  return html(LANDING);
}

/**
 * Any other page: the main site's header and footer around {@code body}, plus the clip-page styles. The
 * replacements are functions so "$&", "$`" and "$'" in titles are inserted as written.
 */
function page(env, title, body, head = "") {
  return TOP.replace("{{TITLE}}", () => `${esc(title)} · Evade Clips`).replace("{{HEAD}}", () => head + CLIP_CSS)
    + `<section class="wrap page-body clips">${body}</section>` + BOTTOM;
}

const reasonOptions = () => Object.entries(REASONS).map(([k, r]) => `<option value="${k}">${esc(r[0].toUpperCase() + r.slice(1))}</option>`).join("");

/** Styles for the clip page and gallery, built on the main site's colours and components. */
const CLIP_CSS = `<style>
.clips{max-width:1100px}
.clips .eyebrow{margin-bottom:10px}
.clip-player{border-radius:var(--r-lg);overflow:hidden;background:#000;border:1px solid var(--stroke);box-shadow:0 30px 80px -30px rgba(225,29,46,.35)}
.clip-player video{display:block;width:100%;max-height:76vh;background:#000}
.clip-bar{display:flex;flex-wrap:wrap;gap:20px;align-items:flex-end;justify-content:space-between;margin-top:28px}
.clip-bar .cta{margin-top:0}
.clip-info h1{font-family:var(--display);font-size:clamp(28px,4vw,44px);font-weight:800;letter-spacing:-.035em;line-height:1.08;margin:0}
.clip-facts{color:var(--muted);margin:10px 0 0}
.clip-tools{display:flex;flex-wrap:wrap;gap:22px;align-items:center;margin-top:22px;color:var(--muted);font-size:14px}
.clip-report{background:none;border:0;color:var(--muted);font:inherit;cursor:pointer;text-decoration:underline;text-underline-offset:3px;padding:0}
.switch{display:inline-flex;align-items:center;gap:10px;cursor:pointer}
.switch input{position:absolute;opacity:0;width:0;height:0}
.switch span{width:38px;height:22px;border-radius:99px;background:#2a2a31;position:relative;transition:background .2s var(--ease)}
.switch span::after{content:"";position:absolute;top:3px;left:3px;width:16px;height:16px;border-radius:50%;background:#fff;transition:transform .2s var(--ease)}
.switch input:checked+span{background:var(--red)}
.switch input:checked+span::after{transform:translateX(16px)}
.clip-form[hidden]{display:none}
.clip-form{padding:22px;margin-top:18px;display:flex;flex-direction:column;gap:12px;max-width:560px}
.clip-form h3{margin:0;font-family:var(--display)}
.clip-form p{margin:0;color:var(--muted);font-size:14px}
.clip-form input,.clip-form select{background:var(--elev-2);color:var(--text);border:1px solid var(--stroke-2);border-radius:var(--r-sm);padding:11px 12px;font:15px var(--body);width:100%}
.clip-form input[type=file]{padding:9px}
.clip-form .btn{align-self:flex-start}
.clip-form .btn:disabled{opacity:.6;cursor:default}
.clip-upload{max-width:none}
.clip-upload-row{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.clip-status{min-height:1em}
.clip-promo{display:flex;flex-wrap:wrap;gap:16px;align-items:center;justify-content:space-between;padding:22px 24px;margin-top:40px}
.clip-promo h3{margin:0 0 4px;font-family:var(--display)}
.clip-promo p{margin:0;color:var(--muted)}
.clip-storage{padding:18px 22px;margin-top:6px}
.clip-storage-row{display:flex;justify-content:space-between;color:var(--muted);font-size:14px;margin-bottom:10px}
.clip-meter{height:6px;border-radius:99px;background:#1c1c22;overflow:hidden}
.clip-meter i{display:block;height:100%;background:linear-gradient(90deg,#9b0d1a,var(--red-2))}
.clip-grid{margin-top:22px;display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:18px}
.clip-grid .clip-card{grid-column:auto}
.clip-card .img{position:relative}
.clip-card img{width:100%;aspect-ratio:16/9;object-fit:cover;display:block;background:#000}
.clip-dur{position:absolute;right:10px;bottom:10px;background:rgba(0,0,0,.75);border-radius:6px;padding:2px 7px;font:600 12px var(--mono)}
.clip-tags{display:flex;gap:6px;margin-bottom:6px}
.clip-tags .tag{margin:0}
.clip-empty{color:var(--muted)}
.clips-msg{padding:40px 28px;text-align:center;max-width:640px;margin:20px auto 0}
.clips-msg h1{font-family:var(--display);font-size:clamp(28px,4vw,40px);letter-spacing:-.03em;margin:0 0 10px}
.clips-msg p{color:var(--muted);margin:0 0 22px}
@media (max-width:640px){.clip-upload-row{grid-template-columns:1fr}.clip-bar .cta .btn{flex:1 1 auto}}
</style>`;

// ------------------------------------------------------------------ housekeeping (cron)

/**
 * "daily": gives up uploads left unfinished for a day, turns old takedown records into tombstones, recounts the
 * server's storage and counts preset downloads. "fold": only counts preset downloads. Each step is idempotent.
 */
async function housekeeping(env, what) {
  const steps = what === "daily" ? [sweepUploads, sweepMetas, recountStorage, foldDownloads] : [foldDownloads];
  for (const step of steps) {
    try { await step(env); } catch (e) { console.error("housekeeping", step.name, e); }
  }
  const t = Date.now();
  await mutate(env, "cron.json", (c) => ({ ...(c || {}), fold: t, ...(what === "daily" ? { daily: t } : {}), lease: 0 }));
}

/**
 * Runs housekeeping from a request when the scheduled runs aren't happening (a config without cron triggers):
 * the daily part after 26 hours, the download count after 2 hours, one request at a time.
 */
async function maybeHousekeep(env) {
  try {
    const o = await env.CLIPS.get("cron.json");
    const c = o ? await o.json() : {};
    const t = Date.now();
    const daily = !c.daily || t - c.daily > 26 * 3600e3, fold = !c.fold || t - c.fold > 2 * 3600e3;
    if ((!daily && !fold) || (c.lease && t - c.lease < 15 * 60e3)) return;
    const claimed = await env.CLIPS.put("cron.json", JSON.stringify({ ...c, lease: t }), { onlyIf: o ? { etagMatches: o.etag } : new Headers({ "If-None-Match": "*" }) });
    if (claimed) await housekeeping(env, daily ? "daily" : "fold");
  } catch (e) {
    console.error("housekeeping", e);
  }
}

/** Uploads started more than a day ago and never finished: abort them and free what they reserved. */
async function sweepUploads(env) {
  const cutoff = Date.now() - PENDING_TTL;
  for await (const o of listAll(env, { prefix: "u/", include: ["customMetadata"] })) {
    if (o.uploaded.getTime() > cutoff) continue;
    const clip = o.key.slice(2);
    if (!ID.test(clip)) { await env.CLIPS.delete(o.key); continue; }
    const meta = await getJson(env, `m/${clip}.json`);
    if (meta && (meta.done || meta.removed)) { await env.CLIPS.delete(o.key); continue; } // finished; only the marker was left
    const cm = o.customMetadata || {};
    await dropUpload(env, clip, cm.account || (meta && meta.account), cm.uploadId || (meta && meta.uploadId));
  }
}

/**
 * Walks the clip records (a bounded slice per run, continuing where the last run stopped) and, for records
 * without a video: deletes uploads abandoned before upload markers existed and shrinks old takedown records to
 * tombstones. The m/ and v/ listings are merged, so only records without a video are read.
 */
async function sweepMetas(env, budget = 200) {
  const state = (await getJson(env, "sweep.json")) || {};
  const after = typeof state.after === "string" && ID.test(state.after) ? state.after : "";
  const cutoff = Date.now() - PENDING_TTL;
  const vids = listAll(env, { prefix: "v/", ...(after ? { startAfter: `v/${after}.mp4` } : {}) });
  let v = await vids.next();
  let last = "", complete = true;
  for await (const o of listAll(env, { prefix: "m/", ...(after ? { startAfter: `m/${after}.json` } : {}) })) {
    const id = o.key.slice(2, -5);
    while (!v.done && v.value.key.slice(2, -4) < id) v = await vids.next();
    const hasVideo = !v.done && v.value.key.slice(2, -4) === id;
    if (!hasVideo && o.uploaded.getTime() < cutoff && ID.test(id)) {
      if (budget-- <= 0) { complete = false; break; }
      const meta = await getJson(env, o.key);
      if (meta && meta.removed) {
        if (Object.keys(meta).length > 2 || meta.removed.note !== undefined) {
          await env.CLIPS.put(o.key, JSON.stringify({ id: meta.id || id, removed: { reason: meta.removed.reason, at: meta.removed.at } }));
        }
      } else if (meta && !meta.done && (meta.created || 0) < cutoff) {
        await dropUpload(env, id, meta.account, meta.uploadId);
      }
    }
    last = id;
  }
  if (!v.done) await vids.return();
  await env.CLIPS.put("sweep.json", JSON.stringify({ after: complete ? "" : last, at: Date.now() }));
}

/** The server's storage use, from the videos themselves (between runs, uploads and deletes adjust it). */
async function recountStorage(env) {
  let used = 0, clips = 0;
  for await (const o of listAll(env, { prefix: "v/" })) { used += o.size; clips++; }
  const at = Date.now();
  await mutate(env, "stats.json", (s) => ({ ...(s || {}), used, clips, at }));
}

/** Adds {@code delta} bytes to the server's storage use, in the background and best effort. */
function bumpStats(env, ctx, delta) {
  if (!delta) return;
  const p = mutate(env, "stats.json", (s) => ({ ...(s || {}), used: Math.max(0, ((s && s.used) || 0) + delta) })).catch((e) => console.error("stats", e));
  if (ctx) ctx.waitUntil(p);
}

/**
 * Adds the downloads recorded since the last run to presets.json. A download record is one viewer on one day,
 * so it's counted once (by its upload time) and deleted when its day is over.
 */
async function foldDownloads(env) {
  const until = Date.now();
  const since = ((await getJson(env, "fold.json")) || {}).at || 0;
  const today = day(until);
  const counts = {}, old = [];
  for await (const o of listAll(env, { prefix: "pd/" })) {
    const [, id, d] = o.key.split("/");
    const up = o.uploaded.getTime();
    if (up > until) continue; // next run
    if (up > since) counts[id] = (counts[id] || 0) + 1;
    if (d < today) old.push(o.key);
  }
  if (Object.keys(counts).length) {
    await mutate(env, "presets.json", (index) => (index ? index.map((e) => (counts[e.id] ? { ...e, downloads: (e.downloads || 0) + counts[e.id] } : e)) : undefined));
  }
  await env.CLIPS.put("fold.json", JSON.stringify({ at: until }));
  await deleteKeys(env, old);
}

// ------------------------------------------------------------------ helpers

/**
 * Read-modify-write of a JSON object that's safe against concurrent writers: {@code fn} gets the current value
 * (null if there's none) and returns the new one, or undefined to leave it as it is. The write only lands if
 * nobody wrote in between; otherwise it reads again and retries. Returns the value now stored.
 */
async function mutate(env, key, fn) {
  for (let i = 0; i < 6; i++) {
    const o = await env.CLIPS.get(key);
    const cur = o ? await o.json() : null;
    const next = await fn(cur);
    if (next === undefined) return cur;
    let put;
    try {
      put = await env.CLIPS.put(key, JSON.stringify(next), { onlyIf: o ? { etagMatches: o.etag } : new Headers({ "If-None-Match": "*" }) });
    } catch (e) {
      // R2 takes about one write per second to the same key and refuses the rest (nothing is written then), so
      // wait and go again. Other errors may or may not have written, so they aren't retried.
      if (i === 5 || !writeRateLimited(e)) throw e;
      await sleep(1000 + Math.random() * 500);
      continue;
    }
    if (put) return next;
    await sleep(15 + Math.random() * 40 * (i + 1));
  }
  throw json({ error: "The server is busy. Try again." }, 503);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** R2's "too many writes to this key" error (code 10058, HTTP 429). */
const writeRateLimited = (e) => /10058|concurrent request rate|too many requests|\b429\b/i.test(String((e && e.message) || e));

/** Every object under a prefix (follows the list cursor). */
async function* listAll(env, opts) {
  let o = { ...opts };
  for (;;) {
    const l = await env.CLIPS.list(o);
    yield* l.objects;
    if (!l.truncated) return;
    o = { prefix: opts.prefix, cursor: l.cursor, ...(opts.include ? { include: opts.include } : {}) };
  }
}

async function deleteKeys(env, keys) {
  for (let i = 0; i < keys.length; i += 1000) await env.CLIPS.delete(keys.slice(i, i + 1000));
}

async function deletePrefix(env, prefix) {
  const keys = [];
  for await (const o of listAll(env, { prefix })) keys.push(o.key);
  await deleteKeys(env, keys);
}

/** Runs {@code fn} over {@code items}, {@code n} at a time. */
async function pool(items, n, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (next < items.length) { const i = next++; out[i] = await fn(items[i], i); }
  }));
  return out;
}

/** True when this caller is over a rate limit (no binding configured, or the limiter failing, means no limit). */
async function limited(limiter, req, what) {
  if (!limiter) return false;
  try {
    const { success } = await limiter.limit({ key: `${what}:${req.headers.get("CF-Connecting-IP") || "unknown"}` });
    return !success;
  } catch (e) {
    console.error("rate limit", e);
    return false;
  }
}
const tooMany = (error) => new Response(JSON.stringify({ error }), { status: 429, headers: { "Content-Type": "application/json", "Retry-After": "60", ...cors() } });

/** A short, salted hash of the caller's IP address: enough to count one report or download per viewer, not to find them. */
async function viewer(req, env) {
  const ip = req.headers.get("CF-Connecting-IP") || "unknown";
  return (await sha(`${env.IP_SALT || env.ADMIN_TOKEN || "evade-clips"}|${ip}`)).slice(0, 16);
}

/** Constant-time string comparison (neither side may be empty). */
async function same(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || !a || !b) return false;
  const enc = new TextEncoder();
  const [x, y] = await Promise.all([crypto.subtle.digest("SHA-256", enc.encode(a)), crypto.subtle.digest("SHA-256", enc.encode(b))]);
  return crypto.subtle.timingSafeEqual(x, y);
}

/** A JSON object request body (at most 64 KB); {@code optional} accepts an empty body as {}. */
async function body(req, optional = false) {
  if (+req.headers.get("Content-Length") > JSON_MAX) throw json({ error: "request too large" }, 413);
  const text = await req.text();
  if (text.length > JSON_MAX) throw json({ error: "request too large" }, 413);
  if (!text.trim() && optional) return {};
  try {
    const o = JSON.parse(text);
    if (o && typeof o === "object" && !Array.isArray(o)) return o;
  } catch (e) { /* below */ }
  throw json({ error: "bad request" }, 400);
}

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json", ...cors() } });
}
function html(s, status = 200) {
  return new Response(s, { status, headers: { "Content-Type": "text/html; charset=utf-8", "X-Content-Type-Options": "nosniff" } });
}
function cors() {
  return { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "Authorization, Content-Type", "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE" };
}
async function getJson(env, key) {
  const o = await env.CLIPS.get(key);
  return o ? o.json() : null;
}
function rid(n) {
  const abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const b = crypto.getRandomValues(new Uint8Array(n));
  let s = "";
  for (const x of b) s += abc[x % abc.length];
  return s;
}
async function sha(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
const str = (v, n) => (typeof v === "string" ? v.slice(0, n) : "");
const num = (v, lo, hi) => { const x = +v; return Number.isFinite(x) ? Math.min(hi, Math.max(lo, x)) : lo; };
const day = (t) => new Date(t).toISOString().slice(0, 10).replace(/-/g, "");
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const safeName = (s) => String(s || "clip").replace(/[^\w .()-]+/g, "").slice(0, 80) || "clip";
const mb = (b) => (b >= 1e9 ? (b / 1e9).toFixed(2) + " GB" : (b / 1e6).toFixed(1) + " MB");
const dur = (s) => (s ? `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}` : "");
