# evadeclient.com

Static download site for Evade Client. No build step: everything is in `index.html`, `assets/`,
`downloads/` and `version.json`.

## Releasing a new version

1. Bump `mod_version` in `gradle.properties`.
2. Run `scripts/build-site.sh`. It builds the mod, copies the jar to `site/downloads/` (as
   `evade-client-latest.jar` and a versioned copy) and updates `site/version.json`, which the page
   reads for its version, file name and size.
3. Commit and push. If the site is connected to Cloudflare Pages, it redeploys automatically.

## Preview locally

```bash
cd site && python3 -m http.server 8000   # open http://localhost:8000
```

## Putting it online

**Quickest (no account setup):** drag the `site` folder onto https://app.netlify.com/drop.
You get a live URL in seconds and can attach a domain later.

**Recommended: Cloudflare Pages** (free, works with private GitHub repos, auto-deploys on push):
1. dash.cloudflare.com → Workers & Pages → Create → Pages → Connect to Git → pick this repo.
2. Framework preset: None. Build command: leave empty. Build output directory: `site`.
3. Choose the branch to deploy, then Save and Deploy.

**Domain:** search evadeclient.com at Cloudflare Registrar (Domain Registration → Register),
Namecheap or Porkbun. Registering it through Cloudflare makes step 4 automatic.
4. In the Pages project → Custom domains → add `evadeclient.com` (and `www.evadeclient.com`).
