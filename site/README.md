# evadeclient.com

Multi-page static site for Evade Client: `/` (home), `/features`, `/showcase`, `/download` and `/faq`.

- Page content lives in `site-src/pages/*.html` (a small header block, then the page body).
- `scripts/build-pages.py` wraps each page in the shared layout (nav, footer, meta tags) and writes `site/<page>.html`.
  Edit the nav/footer there, once, for every page.
- Shared styles and script: `site/assets/site.css` and `site/assets/site.js`.
- `scripts/build-site.sh` builds the mod + installer, refreshes `downloads/` and `version.json`, then rebuilds the pages.

## Releasing a new version

1. Bump `mod_version` in `gradle.properties`.
2. Run `scripts/build-site.sh`. It builds the mod, copies the jar to `site/downloads/` (as
   `evade-client-latest.jar` and a versioned copy) and updates `site/version.json`, which the page
   reads for its version, file name and size.
3. If you only changed page text, run `python3 scripts/build-pages.py` instead.
4. Commit and push. If the site is connected to Cloudflare Pages, it redeploys automatically.

## Preview locally

```bash
cd site && python3 -m http.server 8000   # open http://localhost:8000
```

## Hosting (live)

The site is live at **https://evadeclient.com** (and **www.evadeclient.com**), served by the Cloudflare
Worker `evade-client` using static assets:

- `wrangler.jsonc` (repo root) points Wrangler at `./site`. `site/.assetsignore` keeps this README private,
  and `site/404.html` is the not-found page.
- Cloudflare **Workers Builds** is connected to GitHub (`fishsicles0/claude`, branch
  `claude/evade-client-minecraft-yt571w`) and runs `npx wrangler deploy` on every push, so pushing is deploying.
- Domains: `evadeclient.com` is a Worker custom domain; `www.evadeclient.com` is a Worker route
  (`www.evadeclient.com/*`) with a proxied `AAAA 100::` DNS record.
