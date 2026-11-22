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
  if (p === "/" && m === "GET") return home(req, env);
  if (p.startsWith("/downloads/") && env.ASSETS) return env.ASSETS.fetch(req);
  if (p === "/api/register" && m === "POST") return register(req, env);
  if (p === "/api/clips" && m === "GET") return listClips(await auth(req, env, true), env);
  if (p === "/api/uploads" && m === "POST") return startUpload(req, await auth(req, env, true), env);
  if (p === "/api/notices" && m === "GET") return notices(await auth(req, env), env, url);
  if (p === "/api/report" && m === "POST") return report(req, env);
  if (p === "/api/admin/remove" && m === "POST") return adminRemove(req, env);
  if (p === "/api/admin/reports" && m === "GET") return adminReports(req, env);

  let r;
  if ((r = p.match(/^\/api\/uploads\/([^/]+)\/(\d+)$/)) && m === "PUT") return uploadPart(req, await auth(req, env, true), env, r[1], +r[2], url);
  if ((r = p.match(/^\/api\/uploads\/([^/]+)\/complete$/)) && m === "POST") return completeUpload(req, await auth(req, env, true), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)\/thumb$/)) && m === "PUT") return putThumb(req, await auth(req, env, true), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)$/)) && m === "DELETE") return deleteClip(await auth(req, env), env, r[1]);
  if ((r = p.match(/^\/api\/clips\/([^/]+)$/)) && m === "PATCH") return updateClip(req, await auth(req, env), env, r[1]);

  if ((r = p.match(/^\/c\/([^/]+)$/)) && (m === "GET" || m === "HEAD")) return sharePage(env, r[1], url);
  if ((r = p.match(/^\/v\/([^/]+)\.mp4$/)) && (m === "GET" || m === "HEAD")) return video(req, env, r[1], url);
  if ((r = p.match(/^\/t\/([^/]+)\.jpg$/)) && m === "GET") return thumb(env, r[1], url);
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
    return html(page(env, "Clip removed", `<h1>This clip was removed</h1>
<p class=dim>It was taken down for ${esc(REASONS[meta.removed.reason] || "breaking the rules")}. Its owner has been told.</p>`), 410);
  }
  if (!(await visible(env, meta, url))) return html(page(env, "Clip not found", "<p class=dim>This clip is private, was deleted, or never finished uploading.</p>"), 404);
  const tq = meta.visibility === "private" ? `?t=${encodeURIComponent(url.searchParams.get("t"))}` : "";
  const o = url.origin, v = `${o}/v/${clip}.mp4${tq}`, t = `${o}/t/${clip}.jpg${tq}`, w = meta.width || 1280, hgt = meta.height || 720;
  const title = esc(meta.title) + (meta.game ? " · " + esc(meta.game) : "");
  // Open Graph video tags: Discord, Twitter/X, iMessage and friends play the clip right in the chat.
  const head = meta.visibility === "private" ? '<meta name="robots" content="noindex">' : `
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
  const reasons = Object.entries(REASONS).map(([k, v]) => `<option value="${k}">${esc(v[0].toUpperCase() + v.slice(1))}</option>`).join("");
  const body = `
<video id=v src="${v}" poster="${t}" controls muted playsinline loop style="aspect-ratio:${w}/${hgt}"></video>
<h1>${esc(meta.title)}${meta.visibility === "private" ? ' <span class="tag">Private</span>' : ""}</h1>
<p class=dim>${[esc(meta.game), dur(meta.duration), mb(meta.size), new Date(meta.created).toISOString().slice(0, 10)].filter(Boolean).join(" · ")}</p>
<p class=row><a class=btn href="${v}${tq ? "&" : "?"}dl=1">Download</a>
<button class=btn2 onclick="navigator.clipboard.writeText(location.href);this.textContent='Link copied'">Copy link</button>
<label class=dim><input type=checkbox id=ap> Autoplay</label>
<button class="btn2 small" onclick="document.getElementById('rep').hidden=false">Report</button></p>
<form id=rep hidden onsubmit="return sendReport(event)"><select name=reason>${reasons}</select>
<input name=details maxlength=500 placeholder="Details (optional)"><button class=btn2>Send report</button></form>
<script>
const ap=document.getElementById('ap'),vid=document.getElementById('v');
let on=true;try{on=localStorage.getItem('autoplay')!=='off'}catch(e){}
ap.checked=on;if(on)vid.play().catch(()=>{});
ap.onchange=()=>{try{localStorage.setItem('autoplay',ap.checked?'on':'off')}catch(e){};if(ap.checked)vid.play().catch(()=>{});else vid.pause()};
async function sendReport(e){e.preventDefault();const f=new FormData(e.target);
const r=await fetch('/api/report',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({clip:'${clip}',reason:f.get('reason'),details:f.get('details')})});
e.target.innerHTML=r.ok?'<p class=dim>Thanks, a moderator will look at it.</p>':'<p class=dim>Could not send the report.</p>';return false}
</script>`;
  return html(page(env, meta.title, body, head));
}

async function gallery(env, account, url) {
  const acc = ID.test(account) ? await getJson(env, `a/${account}.json`) : null;
  const t = url.searchParams.get("t");
  if (!acc || t !== acc.viewToken) return html(page(env, "Not found", "<p class=dim>Nothing here.</p>"), 404);
  const index = (await getJson(env, `i/${account}.json`)) || [];
  const q = `?t=${encodeURIComponent(t)}`;
  const cards = index.map((c) => `
<a class=card href="/c/${c.id}${c.visibility === "private" ? q : ""}"><img loading=lazy src="/t/${c.id}.jpg${c.visibility === "private" ? q : ""}" alt="">
<b>${esc(c.title)}${c.visibility === "private" ? ' <span class="tag">Private</span>' : ""}${c.origin === "phone" ? ' <span class="tag">Phone</span>' : ""}</b>
<span class=dim>${[esc(c.game), dur(c.duration), mb(c.size)].filter(Boolean).join(" · ")}</span></a>`).join("");
  const body = `<h1>Your clips</h1><p class=dim>${index.length} clip${index.length === 1 ? "" : "s"} · ${mb(acc.used)} of ${mb(quota(env))}.
Tap a clip to watch, download or share it. Keep this page private: anyone with the link can see your clips.</p>
<form id=up class=row onsubmit="return upload(event)">
<input type=file name=f accept="video/*" required><input name=title placeholder="Title" maxlength=120><input name=game placeholder="Game" maxlength=60>
<button class=btn>Upload</button></form>
<p class=dim id=st>Upload from your phone (console captures too): it shows up here and in Evade Clips on your PC.</p>
<div class=grid>${cards || "<p class=dim>Nothing synced yet.</p>"}</div>
<script>
const AUTH='Gallery ${account}.'+new URLSearchParams(location.search).get('t');
async function api(path,opt){opt=opt||{};opt.headers=Object.assign({Authorization:AUTH},opt.headers||{});const r=await fetch(path,opt);if(!r.ok)throw new Error((await r.json().catch(()=>({}))).error||r.status);return r.json()}
function frame(file){return new Promise(res=>{const v=document.createElement('video');v.muted=true;v.playsInline=true;v.src=URL.createObjectURL(file);
v.onloadeddata=()=>{v.currentTime=Math.min(1,v.duration/2)};v.onseeked=()=>{const c=document.createElement('canvas');c.width=480;c.height=Math.round(480*v.videoHeight/v.videoWidth)||270;
c.getContext('2d').drawImage(v,0,0,c.width,c.height);c.toBlob(b=>res({thumb:b,duration:v.duration,width:v.videoWidth,height:v.videoHeight}),'image/jpeg',0.8)};v.onerror=()=>res({})})}
async function upload(e){e.preventDefault();const f=new FormData(e.target),file=f.get('f'),st=document.getElementById('st');
try{st.textContent='Preparing…';const info=await frame(file);
const s=await api('/api/uploads',{method:'POST',body:JSON.stringify({title:f.get('title')||file.name.replace(/\\.[^.]+$/,''),game:f.get('game'),size:file.size,duration:info.duration||0,width:info.width||0,height:info.height||0})});
const ps=10*1024*1024,parts=[];for(let i=0,n=1;i<file.size;i+=ps,n++){st.textContent='Uploading '+Math.round(i/file.size*100)+'%';
parts.push(await api('/api/uploads/'+s.clip+'/'+n+'?uploadId='+encodeURIComponent(s.uploadId),{method:'PUT',body:file.slice(i,i+ps)}))}
await api('/api/uploads/'+s.clip+'/complete',{method:'POST',body:JSON.stringify({uploadId:s.uploadId,parts})});
if(info.thumb)await api('/api/clips/'+s.clip+'/thumb',{method:'PUT',body:info.thumb});st.textContent='Uploaded.';location.reload()}catch(err){st.textContent='Upload failed: '+err.message}return false}
</script>`;
  return html(page(env, "Your clips", body, '<meta name="robots" content="noindex">'));
}

/** clips.evadeclient.com: what Evade Clips is, and its downloads (from ./public/downloads/clips.json). */
async function home(req, env) {
  let d = null;
  if (env.ASSETS) {
    const r = await env.ASSETS.fetch(new Request(new URL("/downloads/clips.json", req.url)));
    if (r.ok) d = await r.json().catch(() => null);
  }
  const dl = d ? `
<p class=row><a class=btn href="/downloads/${esc(d.windows.file)}">Download for Windows</a>
<a class=btn2 href="/downloads/${esc(d.jar.file)}">macOS / Linux (.jar)</a></p>
<p class=dim>Evade Clips ${esc(d.version)} · Windows ${esc(d.windows.size)} · jar ${esc(d.jar.size)} · needs Java 17+ (the Minecraft Launcher's works) and ffmpeg.
Playing Minecraft? The <a href="https://evadeclient.com/download.html">Evade Client installer</a> sets up both, ffmpeg included.</p>
<details><summary class=dim>Checksums</summary><p class="dim mono">${esc(d.windows.file)} ${esc(d.windows.sha256)}<br>${esc(d.jar.file)} ${esc(d.jar.sha256)}</p></details>`
    : `<p class=dim>Downloads are on <a href="https://evadeclient.com/download.html">evadeclient.com</a>.</p>`;
  return html(page(env, "Evade Clips", `
<h1 class=hero>Clip it. Edit it. Share it.</h1>
<p class=lead><a href="https://evadeclient.com/clips">evadeclient.com/clips</a> · part of Evade Client.</p>
<p class=lead>Evade Clips records your gameplay with near-zero FPS cost and keeps the last moments ready to save at a key press, a voice command, or automatically when something happens in game. Then edit and share without leaving the app.</p>
${dl}
<div class=features>
<div><b>Instant replay</b><span>15 s to 10 min always ready. Saving is instant: nothing is re-encoded.</span></div>
<div><b>Auto-clips</b><span>Kills, aces, wins, deaths and full rounds in CS2, League and Minecraft, plus loud moments in any game.</span></div>
<div><b>Voice commands</b><span>Say "clip that". Recognised offline on your PC.</span></div>
<div><b>Editor</b><span>Trim, slow-mo, captions, meme text, video overlays, one-click vertical, montages.</span></div>
<div><b>Separate tracks</b><span>Game, mic and voice chat on their own tracks, balanced after the fact.</span></div>
<div><b>Share links</b><span>Links that play inside Discord. No watermark, no ads, no expiry. Private clips too.</span></div>
<div><b>Your phone</b><span>Every shared clip in a private gallery; upload console captures from your phone.</span></div>
<div><b>Small files</b><span>Hard bitrate ceilings and HEVC: a busy minute of 1080p60 stays under 70 MB.</span></div>
</div>`));
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
h1{font-size:22px;margin:16px 0 4px}.hero{font-size:clamp(30px,6vw,52px);line-height:1.1;margin:36px 0 12px}
.lead{font-size:17px;color:var(--dim);max-width:720px}.dim{color:var(--dim)}a{color:var(--text)}.mono{font:12px ui-monospace,monospace;word-break:break-all}
.row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.btn,.btn2{display:inline-block;padding:9px 16px;border-radius:10px;border:0;font:600 14px system-ui,sans-serif;cursor:pointer;text-decoration:none}
.btn{background:var(--red);color:#fff}.btn2{background:var(--card);color:var(--text)}.small{padding:6px 10px;font-size:12px}
input,select{background:var(--card);color:var(--text);border:1px solid #26262E;border-radius:8px;padding:8px;font:14px system-ui,sans-serif;max-width:100%}
.tag{font-size:11px;background:var(--card);border:1px solid #26262E;border-radius:6px;padding:1px 6px;vertical-align:middle;color:var(--dim)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px;margin-top:16px}
.card{display:flex;flex-direction:column;gap:2px;background:var(--card);border-radius:14px;overflow:hidden;text-decoration:none;padding-bottom:12px}
.card img{width:100%;aspect-ratio:16/9;object-fit:cover;background:#000;margin-bottom:8px}.card b,.card span{padding:0 12px}
.features{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px;margin-top:28px}
.features div{background:var(--card);border-radius:14px;padding:14px 16px;display:flex;flex-direction:column;gap:4px}.features span{color:var(--dim);font-size:14px}
header{display:flex;align-items:center;gap:8px;font:italic 800 18px system-ui,sans-serif;letter-spacing:.5px}
header a{text-decoration:none}header i{color:var(--red);font-style:italic}
</style></head><body><main><header><a href="/">EVADE <i>CLIPS</i></a></header>${body}</main></body></html>`;
}

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
