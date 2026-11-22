// Evade Clips Share — share links and phone sync for Evade Clips, on Cloudflare Workers + R2.
//
// Storage layout in the R2 bucket:
//   a/<account>.json   account: { keyHash, viewToken, created, used }
//   i/<account>.json   the account's clips (newest first), for the phone gallery
//   m/<clip>.json      clip metadata (public fields only)
//   v/<clip>.mp4       the video
//   t/<clip>.jpg       its thumbnail
//
// Public:  /c/<clip> (share page with Discord/Twitter embed tags), /v/<clip>.mp4 (range-served video),
//          /t/<clip>.jpg, /g/<account>?t=<viewToken> (private phone gallery: unguessable link).
// API:     everything under /api needs "Authorization: Bearer <account>.<key>" except /api/register.
// No watermarks, no ads, nothing expires: a clip stays until its owner deletes it.

const ID = /^[A-Za-z0-9_-]{6,40}$/;
const PART_MIN = 5 * 1024 * 1024; // R2 multipart minimum (except the last part)

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
  if (p === "/" ) return html(home(env));
  if (p === "/api/register" && m === "POST") return register(req, env);
  if (p === "/api/clips" && m === "GET") return listClips(await auth(req, env), env);
  if (p === "/api/uploads" && m === "POST") return startUpload(req, await auth(req, env), env);

  let r;
  if ((r = p.match(/^\/api\/uploads\/([^/]+)\/(\d+)$/)) && m === "PUT") return uploadPart(req, await auth(req, env), env, r[1], +r[2], url);
  if ((r = p.match(/^\/api\/uploads\/([^/]+)\/complete$/)) && m === "POST") return completeUpload(req, await auth(req, env), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)\/thumb$/)) && m === "PUT") return putThumb(req, await auth(req, env), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)$/)) && m === "DELETE") return deleteClip(await auth(req, env), env, r[1]);

  if ((r = p.match(/^\/c\/([^/]+)$/)) && (m === "GET" || m === "HEAD")) return sharePage(env, r[1], url);
  if ((r = p.match(/^\/v\/([^/]+)\.mp4$/)) && (m === "GET" || m === "HEAD")) return video(req, env, r[1], url);
  if ((r = p.match(/^\/t\/([^/]+)\.jpg$/)) && m === "GET") return thumb(env, r[1]);
  if ((r = p.match(/^\/g\/([^/]+)$/)) && m === "GET") return gallery(env, r[1], url);
  return html(page(env, "Not found", "<p class=dim>Nothing here.</p>"), 404);
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

async function auth(req, env) {
  const h = req.headers.get("Authorization") || "";
  const tok = h.startsWith("Bearer ") ? h.slice(7) : "";
  const dot = tok.indexOf(".");
  const account = tok.slice(0, dot), key = tok.slice(dot + 1);
  if (dot < 1 || !ID.test(account)) throw json({ error: "unauthorized" }, 401);
  const acc = await getJson(env, `a/${account}.json`);
  if (!acc || acc.keyHash !== (await sha(key))) throw json({ error: "unauthorized" }, 401);
  return { account, acc };
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
  };
  await env.CLIPS.put(`m/${clip}.json`, JSON.stringify(meta));
  const mp = await env.CLIPS.createMultipartUpload(`v/${clip}.mp4`, { httpMetadata: { contentType: "video/mp4" } });
  return json({ clip, uploadId: mp.uploadId, partSize: Math.max(PART_MIN, 32 * 1024 * 1024) });
}

async function ownedMeta(env, a, clip) {
  if (!ID.test(clip)) throw json({ error: "bad id" }, 400);
  const meta = await getJson(env, `m/${clip}.json`);
  if (!meta || meta.account !== a.account) throw json({ error: "not found" }, 404);
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

async function deleteClip(a, env, clip) {
  const meta = await ownedMeta(env, a, clip);
  await env.CLIPS.delete([`v/${clip}.mp4`, `t/${clip}.jpg`, `m/${clip}.json`]);
  if (meta.done) a.acc.used = Math.max(0, a.acc.used - meta.size);
  await env.CLIPS.put(`a/${a.account}.json`, JSON.stringify(a.acc));
  const index = ((await getJson(env, `i/${a.account}.json`)) || []).filter((c) => c.id !== clip);
  await env.CLIPS.put(`i/${a.account}.json`, JSON.stringify(index));
  return json({ ok: true });
}

const publicMeta = (m) => ({ id: m.id, title: m.title, game: m.game, duration: m.duration, width: m.width, height: m.height, size: m.size, created: m.created });

// ------------------------------------------------------------------ public pages

async function video(req, env, clip, url) {
  if (!ID.test(clip)) return new Response("not found", { status: 404 });
  const range = parseRange(req.headers.get("Range"));
  const head = req.method === "HEAD";
  const obj = head ? await env.CLIPS.head(`v/${clip}.mp4`) : await env.CLIPS.get(`v/${clip}.mp4`, range ? { range } : {});
  if (!obj) return new Response("not found", { status: 404 });
  const size = obj.size;
  const h = new Headers({
    "Content-Type": "video/mp4", "Accept-Ranges": "bytes", "Cache-Control": "public, max-age=31536000, immutable",
    "Access-Control-Allow-Origin": "*",
  });
  if (url.searchParams.has("dl")) {
    const meta = await getJson(env, `m/${clip}.json`);
    h.set("Content-Disposition", `attachment; filename="${safeName(meta && meta.title)}.mp4"`);
  }
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

async function thumb(env, clip) {
  if (!ID.test(clip)) return new Response("not found", { status: 404 });
  const obj = await env.CLIPS.get(`t/${clip}.jpg`);
  if (!obj) return new Response("not found", { status: 404 });
  return new Response(obj.body, { headers: { "Content-Type": "image/jpeg", "Cache-Control": "public, max-age=86400" } });
}

async function sharePage(env, clip, url) {
  const meta = ID.test(clip) ? await getJson(env, `m/${clip}.json`) : null;
  if (!meta || !meta.done) return html(page(env, "Clip not found", "<p class=dim>This clip was deleted or never finished uploading.</p>"), 404);
  const o = url.origin, v = `${o}/v/${clip}.mp4`, t = `${o}/t/${clip}.jpg`, w = meta.width || 1280, hgt = meta.height || 720;
  const title = esc(meta.title) + (meta.game ? " · " + esc(meta.game) : "");
  // Open Graph video tags: Discord, Twitter/X, iMessage and friends play the clip right in the chat.
  const head = `
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
<meta name="twitter:player:height" content="${hgt}">
<meta name="theme-color" content="#E11D2E">`;
  const body = `
<video src="${v}" poster="${t}" controls autoplay muted playsinline loop style="aspect-ratio:${w}/${hgt}"></video>
<h1>${esc(meta.title)}</h1>
<p class=dim>${[esc(meta.game), dur(meta.duration), mb(meta.size), new Date(meta.created).toISOString().slice(0, 10)].filter(Boolean).join(" · ")}</p>
<p><a class=btn href="${v}?dl=1">Download</a> <button class=btn2 onclick="navigator.clipboard.writeText(location.href);this.textContent='Link copied'">Copy link</button></p>`;
  return html(page(env, meta.title, body, head));
}

async function gallery(env, account, url) {
  const acc = ID.test(account) ? await getJson(env, `a/${account}.json`) : null;
  if (!acc || url.searchParams.get("t") !== acc.viewToken) return html(page(env, "Not found", "<p class=dim>Nothing here.</p>"), 404);
  const index = (await getJson(env, `i/${account}.json`)) || [];
  const cards = index.map((c) => `
<a class=card href="/c/${c.id}"><img loading=lazy src="/t/${c.id}.jpg" alt=""><b>${esc(c.title)}</b>
<span class=dim>${[esc(c.game), dur(c.duration), mb(c.size)].filter(Boolean).join(" · ")}</span></a>`).join("");
  const body = `<h1>Your clips</h1><p class=dim>${index.length} clip${index.length === 1 ? "" : "s"} · ${mb(acc.used)} of ${mb(quota(env))}.
Tap a clip to watch, download or share it. Keep this page private: anyone with the link can see your clips.</p>
<div class=grid>${cards || "<p class=dim>Nothing synced yet.</p>"}</div>`;
  return html(page(env, "Your clips", body, '<meta name="robots" content="noindex">'));
}

function home(env) {
  return page(env, env.SITE_NAME || "Evade Clips", `<h1>${esc(env.SITE_NAME || "Evade Clips")} share</h1>
<p class=dim>Share links and phone sync for Evade Clips. No watermarks, no ads, links don't expire.</p>`);
}

function page(env, title, body, head = "") {
  return `<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>${head}
<style>
:root{color-scheme:dark;--bg:#0A0A0C;--card:#15151A;--text:#F4F4F6;--dim:#9B9BA6;--red:#E11D2E}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
main{max-width:1100px;margin:0 auto;padding:20px 16px 48px}
video{width:100%;max-height:78vh;background:#000;border-radius:14px;display:block}
h1{font-size:22px;margin:16px 0 4px}.dim{color:var(--dim)}a{color:var(--text)}
.btn,.btn2{display:inline-block;padding:9px 16px;border-radius:10px;border:0;font:600 14px system-ui,sans-serif;cursor:pointer;text-decoration:none}
.btn{background:var(--red);color:#fff}.btn2{background:var(--card);color:var(--text)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px;margin-top:16px}
.card{display:flex;flex-direction:column;gap:2px;background:var(--card);border-radius:14px;overflow:hidden;text-decoration:none;padding-bottom:12px}
.card img{width:100%;aspect-ratio:16/9;object-fit:cover;background:#000;margin-bottom:8px}.card b,.card span{padding:0 12px}
header{display:flex;align-items:center;gap:8px;font:italic 800 18px system-ui,sans-serif;letter-spacing:.5px}
header i{color:var(--red);font-style:italic}
</style></head><body><main><header>EVADE <i>CLIPS</i></header>${body}</main></body></html>`;
}

// ------------------------------------------------------------------ helpers

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json", ...cors() } });
}
function html(s, status = 200) {
  return new Response(s, { status, headers: { "Content-Type": "text/html; charset=utf-8", "X-Content-Type-Options": "nosniff" } });
}
function cors() {
  return { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "Authorization, Content-Type", "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE" };
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
