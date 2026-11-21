"""Bundle Plumb & Square into one Cloudflare Pages worker (dist/_worker.js).

Usage:  python3 build-site.py
Deploy: curl -X POST https://api.cloudflare.com/client/v4/accounts/<ACCOUNT_ID>/pages/projects/plumb-and-square/deployments \
          -H "Authorization: Bearer <API_TOKEN>" -F 'manifest={}' -F 'branch=main' \
          -F '_worker.js=@dist/_worker.js;type=application/javascript'
   or:  npx wrangler pages deploy dist --project-name plumb-and-square --branch main
"""
import json, os

here = os.path.dirname(os.path.abspath(__file__))
types = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8'}
files = {}
for p in ['index.html'] + ['js/' + f for f in sorted(os.listdir(os.path.join(here, 'js')))]:
    body = open(os.path.join(here, p), encoding='utf-8').read()
    if p == 'index.html':  # a real website needs the document skeleton Claude adds automatically
        body = '<!doctype html>\n<html lang="en">\n' + body.replace(
            '<meta name="claude:viewport-fit-sides" content="cover">\n', '<style>body{margin:0}</style>\n', 1)
    files['/' + p] = {'t': types[os.path.splitext(p)[1]], 'b': body}

worker = 'const FILES=' + json.dumps(files) + """;
export default {
  async fetch(request){
    const url=new URL(request.url);let p=url.pathname;
    if(p===''||p==='/'||p.endsWith('/'))p+='index.html';
    const f=FILES[p];
    if(!f)return new Response('Not found',{status:404,headers:{'content-type':'text/plain'}});
    return new Response(f.b,{headers:{'content-type':f.t,'cache-control':'public, max-age=300'}});
  }
};
"""
os.makedirs(os.path.join(here, 'dist'), exist_ok=True)
open(os.path.join(here, 'dist', '_worker.js'), 'w', encoding='utf-8').write(worker)
print('wrote dist/_worker.js', len(worker), 'bytes,', len(files), 'files')
