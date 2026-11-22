"""Bundle Plumb & Square into one Cloudflare Pages worker (dist/_worker.js).

The worker serves every file from memory, plus what the public website needs to install
as an app on Windows (Edge/Chrome), Mac, Android and iPhone: a web app manifest, icons and
a service worker that keeps the app working offline.

Usage:  python3 build-site.py
Deploy: npx wrangler pages deploy dist --project-name plumb-and-square --branch main
   or:  curl -X POST https://api.cloudflare.com/client/v4/accounts/<ACCOUNT_ID>/pages/projects/plumb-and-square/deployments \
          -H "Authorization: Bearer <API_TOKEN>" -F 'manifest={}' -F 'branch=main' \
          -F '_worker.js=@dist/_worker.js;type=application/javascript'
"""
import base64, hashlib, json, os

here = os.path.dirname(os.path.abspath(__file__))
read = lambda p: open(os.path.join(here, p), encoding='utf-8').read()
types = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
         '.png': 'image/png', '.webmanifest': 'application/manifest+json'}
js_files = ['js/' + f for f in sorted(os.listdir(os.path.join(here, 'js'))) if f.endswith('.js')]
icons = ['icons/' + f for f in sorted(os.listdir(os.path.join(here, 'icons')))]

# version = hash of all sources, so installed copies pick up new releases
h = hashlib.sha1()
for p in ['index.html'] + js_files + icons:
    h.update(open(os.path.join(here, p), 'rb').read())
version = h.hexdigest()[:10]

head = ('<!doctype html>\n<html lang="en">\n')
pwa = ('<link rel="manifest" href="/manifest.webmanifest">\n'
       '<meta name="theme-color" content="#1b262d">\n'
       '<link rel="apple-touch-icon" href="/icons/icon-192.png">\n'
       '<meta name="apple-mobile-web-app-capable" content="yes">\n'
       '<style>body{margin:0}</style>\n')
index = read('index.html')
index = index.replace('<meta name="claude:viewport-fit-sides" content="cover">\n', pwa, 1)
index = index.replace('<script src="js/boot.js"></script>',
    '<script src="js/boot.js"></script>\n<script>if("serviceWorker" in navigator)addEventListener("load",()=>navigator.serviceWorker.register("/sw.js").catch(()=>{}))</script>', 1)
index = head + index

manifest = {
  "name": "Plumb & Square", "short_name": "Plumb & Square",
  "description": "Kitchen and bath design: measure, place cabinets, 3D, problems, price and quote.",
  "start_url": "/", "scope": "/", "display": "standalone", "display_override": ["window-controls-overlay", "standalone"],
  "background_color": "#e9ece8", "theme_color": "#1b262d",
  "icons": [
    {"src": "/icons/icon-192.png", "sizes": "192x192", "type": "image/png"},
    {"src": "/icons/icon-512.png", "sizes": "512x512", "type": "image/png"},
    {"src": "/icons/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"}
  ]
}

shell = ['/', '/index.html', '/manifest.webmanifest'] + ['/' + p for p in js_files] + ['/icons/icon-192.png',
         'https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js']
sw = """const V='ps-%s';const SHELL=%s;
self.addEventListener('install',e=>{e.waitUntil(caches.open(V).then(c=>Promise.all(SHELL.map(u=>c.add(u).catch(()=>{})))).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==V).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const r=e.request;if(r.method!=='GET')return;const u=new URL(r.url);
  const same=u.origin===self.location.origin, cdn=/cdnjs\\.cloudflare\\.com|fonts\\.(googleapis|gstatic)\\.com|cdn\\.jsdelivr\\.net/.test(u.host);
  if(!same&&!cdn)return;
  if(same){ // newest version when online, saved copy when offline
    e.respondWith(fetch(r).then(res=>{if(res.ok){const c=res.clone();caches.open(V).then(x=>x.put(r,c))}return res}).catch(()=>caches.match(r).then(m=>m||caches.match('/index.html'))));
  } else { // libraries and fonts never change at a pinned URL
    e.respondWith(caches.match(r).then(m=>m||fetch(r).then(res=>{const c=res.clone();caches.open(V).then(x=>x.put(r,c));return res})));
  }
});
""" % (version, json.dumps(shell))

files = {'/index.html': {'t': types['.html'], 'b': index},
         '/manifest.webmanifest': {'t': types['.webmanifest'], 'b': json.dumps(manifest)},
         '/sw.js': {'t': types['.js'], 'b': sw}}
for p in js_files:
    files['/' + p] = {'t': types['.js'], 'b': read(p)}
for p in icons:
    files['/' + p] = {'t': 'image/png', 'b': base64.b64encode(open(os.path.join(here, p), 'rb').read()).decode(), 'bin': 1}
files['/favicon.ico'] = files['/icons/favicon-48.png']

worker = 'const FILES=' + json.dumps(files) + """;
const bin=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
export default {
  async fetch(request){
    const url=new URL(request.url);let p=url.pathname;
    if(p===''||p==='/'||p.endsWith('/'))p+='index.html';
    const f=FILES[p];
    if(!f)return new Response('Not found',{status:404,headers:{'content-type':'text/plain'}});
    const cache=p==='/sw.js'||p==='/index.html'?'no-cache':'public, max-age=300';
    return new Response(f.bin?bin(f.b):f.b,{headers:{'content-type':f.t,'cache-control':cache}});
  }
};
"""
os.makedirs(os.path.join(here, 'dist'), exist_ok=True)
open(os.path.join(here, 'dist', '_worker.js'), 'w', encoding='utf-8').write(worker)
print('wrote dist/_worker.js', len(worker), 'bytes,', len(files), 'files, version', version)
