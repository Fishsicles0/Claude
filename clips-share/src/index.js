// Evade Clips Share — clips.evadeclient.com: the Evade Clips download page, share links and phone sync, on
// Cloudflare Workers + R2.
//
// Storage layout in the R2 bucket:
//   a/<account>.json   account: { keyHash, viewToken, created, used }
//   i/<account>.json   the account's clips (newest first), for the phone gallery and the app
//   n/<account>.json   notices for the owner (e.g. a clip removed by moderation, and why)
//   m/<clip>.json      clip metadata (public fields + visibility, origin, removal)
//   v/<clip>.mp4       the video
//   t/<clip>.jpg       its thumbnail
//   r/<clip>/<time>.json  reports from viewers
//
// Public:  /           landing page + downloads (static files in ./public)
//          /c/<clip>   share page with Discord/Twitter embed tags (private clips need the owner's gallery token)
//          /v/<clip>.mp4, /t/<clip>.jpg   video (byte ranges) and thumbnail
//          /g/<account>?t=<viewToken>     private phone gallery, with upload from the phone
// API:     /api/* with "Authorization: Bearer <account>.<key>" (the app) or "Gallery <account>.<viewToken>"
//          (the phone gallery: list, upload, thumbnail only). Moderation: "Admin <ADMIN_TOKEN>".
// No watermarks, no ads, nothing expires: a clip stays until its owner deletes it (or moderation removes it,
// in which case the owner is told which clip and why).

const ID = /^[A-Za-z0-9_-]{6,40}$/;
const PART_MIN = 5 * 1024 * 1024; // R2 multipart minimum (except the last part)
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
  async fetch(req, env) {
    try {
      return await route(req, env);
    } catch (e) {
      if (e instanceof Response) return e;
      return json({ error: "server error: " + (e && e.message) }, 500);
    }
  },
};

async function route(req, env) {
  const url = new URL(req.url);
  const p = url.pathname;
  const m = req.method;

  if (m === "OPTIONS") return new Response(null, { headers: cors() });
  // Always the secure address (browsers say "Not secure" on http://).
  if (url.protocol === "http:" && !env.DEV) {
    url.protocol = "https:";
    return Response.redirect(url.toString(), 301);
  }
  if (p === "/" && (m === "GET" || m === "HEAD")) return home();
  if (p.startsWith("/assets/")) return siteFile(env, p.slice("/assets/".length));
  if (["/favicon.svg", "/favicon-32.png", "/apple-touch-icon.png"].includes(p)) return siteFile(env, p.slice(1));
  if (p.startsWith("/downloads/")) return download(req, env, p.slice("/downloads/".length));
  if (p === "/api/register" && m === "POST") return register(req, env);
  if (p === "/api/clips" && m === "GET") return listClips(await auth(req, env, true), env);
  if (p === "/api/uploads" && m === "POST") return startUpload(req, await auth(req, env, true), env);
  if (p === "/api/notices" && m === "GET") return notices(await auth(req, env), env, url);
  if (p === "/api/report" && m === "POST") return report(req, env);
  if (p === "/api/admin/remove" && m === "POST") return adminRemove(req, env);
  if (p === "/api/admin/reports" && m === "GET") return adminReports(req, env);
  if (p === "/api/presets" && m === "GET") return listPresets(env, url);
  if (p === "/api/presets" && m === "POST") return publishPreset(req, await auth(req, env), env);
  if (p === "/api/admin/remove-preset" && m === "POST") return adminRemovePreset(req, env);
  if (p === "/presets" && (m === "GET" || m === "HEAD")) return presetsPage(env, url);

  let r;
  if ((r = p.match(/^\/api\/uploads\/([^/]+)\/(\d+)$/)) && m === "PUT") return uploadPart(req, await auth(req, env, true), env, r[1], +r[2], url);
  if ((r = p.match(/^\/api\/uploads\/([^/]+)\/complete$/)) && m === "POST") return completeUpload(req, await auth(req, env, true), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)\/thumb$/)) && m === "PUT") return putThumb(req, await auth(req, env, true), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)$/)) && m === "DELETE") return deleteClip(await auth(req, env), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)$/)) && m === "PATCH") return updateClip(req, await auth(req, env), env, r[1]);

  if ((r = p.match(/^\/api\/presets\/([^/]+)$/)) && m === "GET") return getPreset(env, r[1], false);
  if ((r = p.match(/^\/api\/presets\/([^/]+)$/)) && m === "DELETE") return deletePreset(await auth(req, env), env, r[1]);
  if ((r = p.match(/^\/p\/([^/]+)\.evadepreset$/)) && m === "GET") return getPreset(env, r[1], true);
  if ((r = p.match(/^\/c\/([^/]+)$/)) && (m === "GET" || m === "HEAD")) return sharePage(env, r[1], url);
  if ((r = p.match(/^\/v\/([^/]+)\.mp4$/)) && (m === "GET" || m === "HEAD")) return video(req, env, r[1], url);
  if ((r = p.match(/^\/t\/([^/]+)\.jpg$/)) && m === "GET") return thumb(env, r[1], url);
  if ((r = p.match(/^\/g\/([^/]+)$/)) && m === "GET") return gallery(env, r[1], url);
  return notFound(env);
}

// ------------------------------------------------------------------ accounts

async function register(req, env) {
  if (env.REGISTER_SECRET && req.headers.get("X-Register-Secret") !== env.REGISTER_SECRET)
    return json({ error: "registration is closed on this server" }, 403);
  const account = rid(12), key = rid(32), viewToken = rid(24);
  const acc = { keyHash: await sha(key), viewToken, created: Date.now(), used: 0 };
  await env.CLIPS.put(`a/${account}.json`, JSON.stringify(acc));
  await env.CLIPS.put(`i/${account}.json`, "[]");
  return json({ account, key, viewToken, quota: quota(env) });
}

/** The app authenticates with its key; the phone gallery (when {@code galleryOk}) with the gallery token. */
async function auth(req, env, galleryOk = false) {
  const h = req.headers.get("Authorization") || "";
  const sp = h.indexOf(" ");
  const scheme = h.slice(0, sp), tok = h.slice(sp + 1);
  const dot = tok.indexOf(".");
  const account = tok.slice(0, dot), secret = tok.slice(dot + 1);
  if (dot < 1 || !ID.test(account)) throw json({ error: "unauthorized" }, 401);
  const acc = await getJson(env, `a/${account}.json`);
  if (!acc) throw json({ error: "unauthorized" }, 401);
  if (scheme === "Bearer" && acc.keyHash === (await sha(secret))) return { account, acc, via: "app" };
  if (scheme === "Gallery" && galleryOk && secret === acc.viewToken) return { account, acc, via: "phone" };
  throw json({ error: "unauthorized" }, 401);
}

const quota = (env) => Math.round(parseFloat(env.QUOTA_GB || "25") * 1e9);

async function listClips(a, env) {
  const index = (await getJson(env, `i/${a.account}.json`)) || [];
  return json({ clips: index, used: a.acc.used, quota: quota(env), gallery: `/g/${a.account}?t=${a.acc.viewToken}` });
}

// ------------------------------------------------------------------ uploads (multipart, any size)

async function startUpload(req, a, env) {
  const b = await req.json();
  const size = Math.max(0, +b.size || 0);
  if (a.acc.used + size > quota(env)) return json({ error: "storage full", used: a.acc.used, quota: quota(env) }, 413);
  const clip = rid(14);
  const meta = {
    id: clip, account: a.account, title: str(b.title, 120) || "Clip", game: str(b.game, 60),
    duration: +b.duration || 0, width: +b.width || 0, height: +b.height || 0, size, created: Date.now(), done: false,
    visibility: b.visibility === "private" ? "private" : "link", origin: a.via === "phone" ? "phone" : "app",
  };
  await env.CLIPS.put(`m/${clip}.json`, JSON.stringify(meta));
  const mp = await env.CLIPS.createMultipartUpload(`v/${clip}.mp4`, { httpMetadata: { contentType: "video/mp4" } });
  return json({ clip, uploadId: mp.uploadId, partSize: Math.max(PART_MIN, 32 * 1024 * 1024) });
}

async function ownedMeta(env, a, clip) {
  if (!ID.test(clip)) throw json({ error: "bad id" }, 400);
  const meta = await getJson(env, `m/${clip}.json`);
  if (!meta || meta.account !== a.account || meta.removed) throw json({ error: "not found" }, 404);
  return meta;
}

async function uploadPart(req, a, env, clip, n, url) {
  await ownedMeta(env, a, clip);
  const uploadId = url.searchParams.get("uploadId");
  const mp = env.CLIPS.resumeMultipartUpload(`v/${clip}.mp4`, uploadId);
  const part = await mp.uploadPart(n, await req.arrayBuffer());
  return json({ partNumber: part.partNumber, etag: part.etag });
}

async function completeUpload(req, a, env, clip) {
  const meta = await ownedMeta(env, a, clip);
  const b = await req.json();
  const mp = env.CLIPS.resumeMultipartUpload(`v/${clip}.mp4`, b.uploadId);
  const obj = await mp.complete(b.parts);
  meta.size = obj.size;
  meta.done = true;
  await env.CLIPS.put(`m/${clip}.json`, JSON.stringify(meta));
  a.acc.used += obj.size;
  await env.CLIPS.put(`a/${a.account}.json`, JSON.stringify(a.acc));
  const index = (await getJson(env, `i/${a.account}.json`)) || [];
  index.unshift(publicMeta(meta));
  await env.CLIPS.put(`i/${a.account}.json`, JSON.stringify(index));
  return json({ clip, url: `/c/${clip}`, video: `/v/${clip}.mp4`, size: obj.size });
}

async function putThumb(req, a, env, clip) {
  await ownedMeta(env, a, clip);
  const body = await req.arrayBuffer();
  if (body.byteLength > 2_000_000) return json({ error: "thumbnail too large" }, 413);
  await env.CLIPS.put(`t/${clip}.jpg`, body, { httpMetadata: { contentType: "image/jpeg" } });
  return json({ ok: true });
}

/** Change a clip's title or who can see it ("link": anyone with the link, "private": only you). */
async function updateClip(req, a, env, clip) {
  const meta = await ownedMeta(env, a, clip);
  const b = await req.json();
  if (b.visibility === "private" || b.visibility === "link") meta.visibility = b.visibility;
  if (typeof b.title === "string" && b.title.trim()) meta.title = str(b.title, 120);
  await env.CLIPS.put(`m/${clip}.json`, JSON.stringify(meta));
  await reindex(env, a.account, (list) => list.map((c) => (c.id === clip ? publicMeta(meta) : c)));
  return json({ ok: true, visibility: meta.visibility });
}

async function deleteClip(a, env, clip) {
  const meta = await ownedMeta(env, a, clip);
  await env.CLIPS.delete([`v/${clip}.mp4`, `t/${clip}.jpg`, `m/${clip}.json`]);
  if (meta.done) a.acc.used = Math.max(0, a.acc.used - meta.size);
  await env.CLIPS.put(`a/${a.account}.json`, JSON.stringify(a.acc));
  await reindex(env, a.account, (list) => list.filter((c) => c.id !== clip));
  return json({ ok: true });
}

async function reindex(env, account, fn) {
  const index = (await getJson(env, `i/${account}.json`)) || [];
  await env.CLIPS.put(`i/${account}.json`, JSON.stringify(fn(index)));
}

const publicMeta = (m) => ({
  id: m.id, title: m.title, game: m.game, duration: m.duration, width: m.width, height: m.height, size: m.size,
  created: m.created, visibility: m.visibility || "link", origin: m.origin || "app",
});

// ------------------------------------------------------------------ moderation

/** Anyone can report a clip; reports wait for a moderator (GET /api/admin/reports). */
async function report(req, env) {
  const b = await req.json().catch(() => ({}));
  const clip = String(b.clip || "");
  if (!ID.test(clip) || !REASONS[b.reason]) return json({ error: "bad report" }, 400);
  const meta = await getJson(env, `m/${clip}.json`);
  if (!meta || meta.removed) return json({ error: "not found" }, 404);
  await env.CLIPS.put(`r/${clip}/${Date.now()}-${rid(4)}.json`, JSON.stringify({ clip, reason: b.reason, details: str(b.details, 500), at: Date.now() }));
  return json({ ok: true });
}

function admin(req, env) {
  if (!env.ADMIN_TOKEN || req.headers.get("Authorization") !== `Admin ${env.ADMIN_TOKEN}`) throw json({ error: "unauthorized" }, 401);
}

async function adminReports(req, env) {
  admin(req, env);
  const list = await env.CLIPS.list({ prefix: "r/", limit: 500 });
  const out = [];
  for (const o of list.objects) out.push(await getJson(env, o.key));
  return json({ reports: out });
}

/**
 * Takes a clip down for a reason. The video goes, the link explains why, and the owner gets a notice in the
 * app naming the clip and the reason.
 */
async function adminRemove(req, env) {
  admin(req, env);
  const b = await req.json();
  const clip = String(b.clip || "");
  const reason = REASONS[b.reason] ? b.reason : "other";
  const meta = ID.test(clip) ? await getJson(env, `m/${clip}.json`) : null;
  if (!meta || meta.removed) return json({ error: "not found" }, 404);
  await env.CLIPS.delete([`v/${clip}.mp4`, `t/${clip}.jpg`]);
  meta.removed = { reason, note: str(b.note, 500), at: Date.now() };
  await env.CLIPS.put(`m/${clip}.json`, JSON.stringify(meta));
  const acc = await getJson(env, `a/${meta.account}.json`);
  if (acc && meta.done) { acc.used = Math.max(0, acc.used - meta.size); await env.CLIPS.put(`a/${meta.account}.json`, JSON.stringify(acc)); }
  await reindex(env, meta.account, (list) => list.filter((c) => c.id !== clip));
  const n = (await getJson(env, `n/${meta.account}.json`)) || [];
  n.unshift({ id: rid(8), clip, title: meta.title, reason, reasonText: REASONS[reason], note: meta.removed.note, at: meta.removed.at, read: false });
  await env.CLIPS.put(`n/${meta.account}.json`, JSON.stringify(n.slice(0, 200)));
  return json({ ok: true });
}

// ------------------------------------------------------------------ community presets

// A preset is a small JSON of settings (a look, and what to do at each marker): no times, no files, no code.
const PRESET_MAX = 16 * 1024;

async function listPresets(env, url) {
  const index = (await getJson(env, "presets.json")) || [];
  const sort = url.searchParams.get("sort");
  const list = [...index];
  if (sort === "popular") list.sort((a, b) => b.downloads - a.downloads || b.created - a.created);
  return json({ presets: list.slice(0, 200) });
}

async function publishPreset(req, a, env) {
  const body = await req.text();
  if (body.length > PRESET_MAX) return json({ error: "preset too large" }, 413);
  let b;
  try { b = JSON.parse(body); } catch { return json({ error: "bad preset" }, 400); }
  const name = str(b.name, 60).trim(), preset = b.preset;
  if (!name || !preset || typeof preset !== "object" || Array.isArray(preset)) return json({ error: "a preset needs a name" }, 400);
  const index = (await getJson(env, "presets.json")) || [];
  if (index.filter((x) => x.account === a.account).length >= 50) return json({ error: "you've shared the most presets one PC can (50)" }, 429);
  const id = rid(10);
  const entry = { id, name, description: str(b.description, 300), author: str(b.author, 40), created: Date.now(), downloads: 0, account: a.account };
  await env.CLIPS.put(`p/${id}.json`, JSON.stringify({ ...entry, preset }));
  index.unshift(entry);
  await env.CLIPS.put("presets.json", JSON.stringify(index.slice(0, 2000)));
  return json({ id, url: `/presets#${id}` });
}

async function getPreset(env, id, asFile) {
  if (!ID.test(id)) return json({ error: "not found" }, 404);
  const p = await getJson(env, `p/${id}.json`);
  if (!p || p.removed) return asFile ? notFound(env, "That preset was removed or never existed.") : json({ error: "not found" }, 404);
  // count downloads (best effort)
  const index = (await getJson(env, "presets.json")) || [];
  const e = index.find((x) => x.id === id);
  if (e) { e.downloads = (e.downloads || 0) + 1; await env.CLIPS.put("presets.json", JSON.stringify(index)); }
  const out = { format: "evade-clips-preset", version: 1, ...p.preset, name: p.name, description: p.description, author: p.author };
  if (!asFile) return json(out);
  return new Response(JSON.stringify(out, null, 2), { headers: { "Content-Type": "application/json", "Content-Disposition": `attachment; filename="${safeName(p.name)}.evadepreset"`, ...cors() } });
}

async function deletePreset(a, env, id) {
  const p = ID.test(id) ? await getJson(env, `p/${id}.json`) : null;
  if (!p || p.account !== a.account) return json({ error: "not found" }, 404);
  await env.CLIPS.delete(`p/${id}.json`);
  const index = ((await getJson(env, "presets.json")) || []).filter((x) => x.id !== id);
  await env.CLIPS.put("presets.json", JSON.stringify(index));
  return json({ ok: true });
}

async function adminRemovePreset(req, env) {
  admin(req, env);
  const b = await req.json();
  const id = String(b.id || "");
  const p = ID.test(id) ? await getJson(env, `p/${id}.json`) : null;
  if (!p) return json({ error: "not found" }, 404);
  p.removed = { at: Date.now(), note: str(b.note, 500) };
  await env.CLIPS.put(`p/${id}.json`, JSON.stringify(p));
  const index = ((await getJson(env, "presets.json")) || []).filter((x) => x.id !== id);
  await env.CLIPS.put("presets.json", JSON.stringify(index));
  return json({ ok: true });
}

/** Browse community presets; each downloads as a .evadepreset file to import in Evade Clips. */
async function presetsPage(env, url) {
  const index = (await getJson(env, "presets.json")) || [];
  const popular = url.searchParams.get("sort") === "popular";
  const list = [...index];
  if (popular) list.sort((a, b) => b.downloads - a.downloads || b.created - a.created);
  const cards = list.slice(0, 120).map((p) => `<div class="card preset-card" id="${esc(p.id)}">
<h3>${esc(p.name)}</h3><p>${esc(p.description || "")}</p>
<div class="preset-meta"><span>${p.author ? "by " + esc(p.author) + " &middot; " : ""}${p.downloads || 0} download${p.downloads === 1 ? "" : "s"}</span>
<a class="btn btn-ghost btn-sm" href="/p/${esc(p.id)}.evadepreset">Download</a></div></div>`).join("");
  const body = `
<div class="sec-head"><div class="eyebrow">Community</div><h1>Edit presets.</h1>
<p>Looks and per-kill effects made by other players. Download one, then in Evade Clips open a clip and, in the <b>Edit</b> tab under <b>Presets</b>, pick <b>Import file</b>. Or click <b>Community</b> there to browse and share right inside the app.</p>
<div class="preset-sort"><a class="btn btn-sm ${popular ? "btn-ghost" : "btn-red"}" href="/presets">Newest</a><a class="btn btn-sm ${popular ? "btn-red" : "btn-ghost"}" href="/presets?sort=popular">Most downloaded</a></div></div>
${cards ? `<div class="preset-grid">${cards}</div>` : `<div class="card preset-card"><p>No community presets yet. Be the first: share one from Evade Clips.</p></div>`}`;
  const css = `<style>.preset-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px;margin-top:22px}
.preset-card{padding:18px 20px}.preset-card h3{margin:0 0 6px;font-family:var(--display);font-size:18px}.preset-card p{margin:0 0 14px;color:var(--muted);font-size:14px}
.preset-meta{display:flex;justify-content:space-between;align-items:center;gap:10px;color:var(--faint);font-size:13px}.preset-sort{display:flex;gap:8px;margin-top:16px}</style>`;
  return html(page(env, "Edit presets", body, css));
}

/** The owner's notices; ?unread=1 for new ones only, which are then marked read. */
async function notices(a, env, url) {
  const n = (await getJson(env, `n/${a.account}.json`)) || [];
  const unreadOnly = url.searchParams.get("unread") === "1";
  const out = unreadOnly ? n.filter((x) => !x.read) : n;
  if (out.some((x) => !x.read)) {
    for (const x of n) x.read = true;
    await env.CLIPS.put(`n/${a.account}.json`, JSON.stringify(n));
  }
  return json({ notices: out });
}

// ------------------------------------------------------------------ public pages

/** Private clips are only visible with the owner's gallery token (t=...). */
async function visible(env, meta, url) {
  if (!meta || !meta.done || meta.removed) return false;
  if (meta.visibility !== "private") return true;
  const acc = await getJson(env, `a/${meta.account}.json`);
  return !!acc && url.searchParams.get("t") === acc.viewToken;
}

async function video(req, env, clip, url) {
  if (!ID.test(clip)) return new Response("not found", { status: 404 });
  const meta = await getJson(env, `m/${clip}.json`);
  if (!(await visible(env, meta, url))) return new Response("not found", { status: 404 });
  const range = parseRange(req.headers.get("Range"));
  const head = req.method === "HEAD";
  const obj = head ? await env.CLIPS.head(`v/${clip}.mp4`) : await env.CLIPS.get(`v/${clip}.mp4`, range ? { range } : {});
  if (!obj) return new Response("not found", { status: 404 });
  const size = obj.size;
  const h = new Headers({
    "Content-Type": "video/mp4", "Accept-Ranges": "bytes",
    "Cache-Control": meta.visibility === "private" ? "private, max-age=3600" : "public, max-age=31536000, immutable",
    "Access-Control-Allow-Origin": "*",
  });
  if (url.searchParams.has("dl")) h.set("Content-Disposition", `attachment; filename="${safeName(meta.title)}.mp4"`);
  if (head) { h.set("Content-Length", String(size)); return new Response(null, { headers: h }); }
  if (range && obj.range) {
    const start = range.suffix != null ? Math.max(0, size - range.suffix) : range.offset;
    const len = range.suffix != null ? size - start : range.length ?? size - start;
    h.set("Content-Range", `bytes ${start}-${start + len - 1}/${size}`);
    h.set("Content-Length", String(len));
    return new Response(obj.body, { status: 206, headers: h });
  }
  h.set("Content-Length", String(size));
  return new Response(obj.body, { headers: h });
}

function parseRange(v) {
  const m = v && v.match(/^bytes=(\d*)-(\d*)$/);
  if (!m) return null;
  if (m[1] === "" && m[2] !== "") return { suffix: +m[2] };
  if (m[1] === "") return null;
  return m[2] === "" ? { offset: +m[1] } : { offset: +m[1], length: +m[2] - +m[1] + 1 };
}

async function thumb(env, clip, url) {
  if (!ID.test(clip)) return new Response("not found", { status: 404 });
  const meta = await getJson(env, `m/${clip}.json`);
  if (!(await visible(env, meta, url))) return new Response("not found", { status: 404 });
  const obj = await env.CLIPS.get(`t/${clip}.jpg`);
  if (!obj) return new Response("not found", { status: 404 });
  return new Response(obj.body, { headers: { "Content-Type": "image/jpeg", "Cache-Control": "public, max-age=86400" } });
}

async function sharePage(env, clip, url) {
  const meta = ID.test(clip) ? await getJson(env, `m/${clip}.json`) : null;
  if (meta && meta.removed) {
    return html(page(env, "Clip removed", `<div class="clips-msg card"><h1>This clip was removed.</h1>
<p>It was taken down for ${esc(REASONS[meta.removed.reason] || "breaking the rules")}. Its owner has been told which clip and why.</p>
<a class="btn btn-ghost" href="/">About Evade Clips</a></div>`), 410);
  }
  if (!(await visible(env, meta, url))) return notFound(env, "This clip is private, was deleted, or never finished uploading.");
  const tq = meta.visibility === "private" ? `?t=${encodeURIComponent(url.searchParams.get("t"))}` : "";
  const o = url.origin, v = `${o}/v/${clip}.mp4${tq}`, t = `${o}/t/${clip}.jpg${tq}`, w = meta.width || 1280, hgt = meta.height || 720;
  const title = esc(meta.title) + (meta.game ? " · " + esc(meta.game) : "");
  // Open Graph video tags: Discord, Twitter/X, iMessage and friends play the clip right in the chat.
  const head = `<meta name="description" content="${title} — shared with Evade Clips.">` + (meta.visibility === "private" ? '<meta name="robots" content="noindex">' : `
<meta property="og:type" content="video.other">
<meta property="og:site_name" content="${esc(env.SITE_NAME || "Evade Clips")}">
<meta property="og:title" content="${title}">
<meta property="og:url" content="${o}/c/${clip}">
<meta property="og:image" content="${t}">
<meta property="og:video" content="${v}">
<meta property="og:video:url" content="${v}">
<meta property="og:video:secure_url" content="${v}">
<meta property="og:video:type" content="video/mp4">
<meta property="og:video:width" content="${w}">
<meta property="og:video:height" content="${hgt}">
<meta name="twitter:card" content="player">
<meta name="twitter:title" content="${title}">
<meta name="twitter:image" content="${t}">
<meta name="twitter:player" content="${o}/c/${clip}">
<meta name="twitter:player:stream" content="${v}">
<meta name="twitter:player:stream:content_type" content="video/mp4">
<meta name="twitter:player:width" content="${w}">
<meta name="twitter:player:height" content="${hgt}">`);
  const reasons = Object.entries(REASONS).map(([k, r]) => `<option value="${k}">${esc(r[0].toUpperCase() + r.slice(1))}</option>`).join("");
  const facts = [esc(meta.game), dur(meta.duration), mb(meta.size), new Date(meta.created).toISOString().slice(0, 10)].filter(Boolean);
  const body = `
<div class="clip-player"><video id=v src="${v}" poster="${t}" controls muted playsinline loop style="aspect-ratio:${w}/${hgt}"></video></div>
<div class="clip-bar">
  <div class="clip-info">
    <div class="eyebrow">${meta.game ? esc(meta.game) : "Evade Clips"}${meta.visibility === "private" ? " &middot; Private" : ""}</div>
    <h1>${esc(meta.title)}</h1>
    <p class="clip-facts">${facts.join(" &middot; ")}</p>
  </div>
  <div class="cta">
    <a class="btn btn-red" href="${v}${tq ? "&" : "?"}dl=1">Download</a>
    <button class="btn btn-ghost" onclick="navigator.clipboard.writeText(location.href);this.textContent='Link copied'">Copy link</button>
  </div>
</div>
<div class="clip-tools">
  <label class="switch"><input type=checkbox id=ap><span></span> Autoplay</label>
  <button class="link-inline clip-report" onclick="document.getElementById('rep').hidden=!document.getElementById('rep').hidden">Report this clip</button>
</div>
<form id=rep class="card clip-form" hidden onsubmit="return sendReport(event)">
  <h3>Report this clip</h3>
  <select name=reason>${reasons}</select>
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
async function sendReport(e){e.preventDefault();const f=new FormData(e.target);
const r=await fetch('/api/report',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({clip:'${clip}',reason:f.get('reason'),details:f.get('details')})});
e.target.innerHTML=r.ok?'<p>Thanks. A moderator will look at it.</p>':'<p>Couldn\'t send the report. Try again later.</p>';return false}
</script>`;
  return html(page(env, meta.title, body, head));
}

async function gallery(env, account, url) {
  const acc = ID.test(account) ? await getJson(env, `a/${account}.json`) : null;
  const t = url.searchParams.get("t");
  if (!acc || t !== acc.viewToken) return notFound(env, "This gallery link isn't valid. Open it again from Evade Clips (Settings → Sharing → Show phone QR code).");
  const index = (await getJson(env, `i/${account}.json`)) || [];
  const q = `?t=${encodeURIComponent(t)}`;
  const cards = index.map((c) => {
    const pq = c.visibility === "private" ? q : "";
    const tags = (c.visibility === "private" ? '<span class="tag">Private</span>' : "") + (c.origin === "phone" ? '<span class="tag">From phone</span>' : "");
    return `<a class="card shot-card clip-card" href="/c/${c.id}${pq}">
<div class="img"><img loading=lazy src="/t/${c.id}.jpg${pq}" alt=""><span class="clip-dur">${dur(c.duration)}</span></div>
<div class="txt">${tags ? `<div class="clip-tags">${tags}</div>` : ""}<h3>${esc(c.title)}</h3><p>${[esc(c.game), mb(c.size)].filter(Boolean).join(" &middot; ")}</p></div></a>`;
  }).join("");
  const used = acc.used > 0 ? Math.max(2, Math.min(100, Math.round((acc.used / quota(env)) * 100))) : 0;
  const body = `
<div class="sec-head"><div class="eyebrow">Your gallery</div><h1>Your clips.</h1>
<p>${index.length} clip${index.length === 1 ? "" : "s"}. Tap one to watch, download or share it. Keep this link to yourself: anyone who has it can see your clips.</p></div>
<div class="card clip-storage"><div class="clip-storage-row"><span>Storage</span><span>${mb(acc.used)} of ${mb(quota(env))}</span></div><div class="clip-meter"><i style="width:${used}%"></i></div></div>
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
async function upload(e){e.preventDefault();const f=new FormData(e.target),file=f.get('f'),st=document.getElementById('st');
try{st.textContent='Preparing…';const info=await frame(file);
const s=await api('/api/uploads',{method:'POST',body:JSON.stringify({title:f.get('title')||file.name.replace(/\.[^.]+$/,''),game:f.get('game'),size:file.size,duration:info.duration||0,width:info.width||0,height:info.height||0})});
const ps=10*1024*1024,parts=[];for(let i=0,n=1;i<file.size;i+=ps,n++){st.textContent='Uploading '+Math.round(i/file.size*100)+'%';
parts.push(await api('/api/uploads/'+s.clip+'/'+n+'?uploadId='+encodeURIComponent(s.uploadId),{method:'PUT',body:file.slice(i,i+ps)}))}
await api('/api/uploads/'+s.clip+'/complete',{method:'POST',body:JSON.stringify({uploadId:s.uploadId,parts})});
if(info.thumb)await api('/api/clips/'+s.clip+'/thumb',{method:'PUT',body:info.thumb});st.textContent='Uploaded.';location.reload()}catch(err){st.textContent='Upload failed: '+err.message}return false}
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

/** Any other page: the main site's header and footer around {@code body}, plus the clip-page styles. */
function page(env, title, body, head = "") {
  return TOP.replace("{{TITLE}}", `${esc(title)} · Evade Clips`).replace("{{HEAD}}", head + CLIP_CSS)
    + `<section class="wrap page-body clips">${body}</section>` + BOTTOM;
}

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

// ------------------------------------------------------------------ helpers

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
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const safeName = (s) => String(s || "clip").replace(/[^\w .()-]+/g, "").slice(0, 80) || "clip";
const mb = (b) => (b >= 1e9 ? (b / 1e9).toFixed(2) + " GB" : (b / 1e6).toFixed(1) + " MB");
const dur = (s) => (s ? `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}` : "");
