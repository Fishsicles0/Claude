# Evade Clips Share — clips.evadeclient.com

Share links and phone sync for [Evade Clips](../clips/README.md). It's a Cloudflare Worker that stores
clips in R2, and one deployment can serve a whole community.

- **Links that play in Discord.** Each clip gets a page with Open Graph video tags, so pasting the link into
  Discord (or X, iMessage, WhatsApp…) shows the video inline. Videos are served with byte ranges, so
  seeking works everywhere.
- **Phone sync.** Every shared clip also appears in a private gallery. In Evade Clips, open *Settings → Sharing →
  Show phone QR code* and scan it. Clips can be watched and downloaded from the phone. Set *Send to phone* to
  *Every new clip* to sync automatically.
- **No watermarks, no ads, no expiry.** Clips are uploaded exactly as rendered and stay until their owner
  deletes them (*Stop sharing* in the app).
- **Plays everywhere.** The app uploads the edited version as H.264 with the audio tracks mixed into one.
  Unedited H.264 clips are uploaded as they are. Large files go up in parts.
- **Per-account storage quota** (`QUOTA_GB`, default 25). Optional private mode: set `REGISTER_SECRET` and
  give people that code (Settings → Sharing → *Server invite code*).
- **Storage cost:** R2 has free egress, so watching and downloading clips costs nothing; you pay only for
  storage beyond R2's free tier.

- **Landing page with downloads.** `/` describes Evade Clips and serves its downloads from `public/downloads/`
  (copied there by `scripts/build-site.sh`, with `clips.json` for versions and checksums).
- **Privacy.** Each clip is *link* (anyone with the link) or *private* (only the owner, through their gallery link).
- **Moderation.** Viewers report clips (reason + details). A moderator removes one with
  `POST /api/admin/remove {clip, reason, note}` (`Authorization: Admin <ADMIN_TOKEN>`): the video goes, the link
  says why, and the owner's app shows a notice naming the clip and the reason.
- **Phone uploads.** The gallery page uploads videos (e.g. console captures) straight from the phone; Evade Clips
  pulls them into the library. The gallery link can list and upload, never delete.

## Deploy

```bash
cd clips-share
npx wrangler r2 bucket create evade-clips
npx wrangler secret put ADMIN_TOKEN      # for moderation
npx wrangler deploy                      # serves clips.evadeclient.com (custom domain in wrangler.jsonc)
# optional: make it invite-only
npx wrangler secret put REGISTER_SECRET
```

Evade Clips uses https://clips.evadeclient.com by default. If you host your own, put its URL in Evade Clips →
Settings → Sharing → *Share server*.

Live at **https://clips.evadeclient.com** (Worker `evade-clips`, bucket `evade-clips`, `ADMIN_TOKEN` set).
It uses the main site's design: `scripts/build-clips-share.py` turns the built `site/clips.html` into the
Worker's home page and page frame (`src/shell.js`), and the main site's stylesheet, scripts, logo and screenshots
are served from the bucket under `site/`; the downloads under `dl/`. After `scripts/build-site.sh`:

```bash
cd clips-share
./sync-assets.sh                                        # shell.js + site/ and dl/ files into the bucket
npx wrangler deploy --config wrangler.r2-downloads.jsonc
```

Plain-http visits are redirected to https.

## Test locally

```bash
npx wrangler dev --local --port 8787 --var QUOTA_GB:0.05 --var ADMIN_TOKEN:test-admin
CLIPS_SHARE_URL=http://127.0.0.1:8787 ./gradlew :clips:selfTest
```

The self-test checks link creation, embed tags, byte-range serving, thumbnails, the phone gallery (and that a
wrong gallery link shows nothing), reusing links, wrong-key and quota refusals, a multi-part upload that comes
back byte-for-byte identical, private clips, report → takedown → owner notice, phone uploads pulled into the
library, the landing page's downloads, and that *Stop sharing* removes everything.

## API

| | |
|---|---|
| `POST /api/register` | new account → `{account, key, viewToken}` |
| `POST /api/uploads` | start an upload `{title, game, duration, width, height, size}` → `{clip, uploadId, partSize}` |
| `PUT /api/uploads/<clip>/<n>?uploadId=` | upload part *n* |
| `POST /api/uploads/<clip>/complete` | `{uploadId, parts}` → `{url, video}` |
| `PUT /api/clips/<clip>/thumb` | JPEG thumbnail |
| `DELETE /api/clips/<clip>` | delete |
| `GET /api/clips` | your clips, storage used, gallery link |
| `GET /c/<clip>` · `/v/<clip>.mp4` · `/t/<clip>.jpg` · `/g/<account>?t=<viewToken>` | public page, video, thumbnail, private gallery |

API calls send `Authorization: Bearer <account>.<key>`. Only a hash of the key is stored.
