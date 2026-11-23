# Evade Clips Share — clips.evadeclient.com

Share links and phone sync for [Evade Clips](../clips/README.md). It's a Cloudflare Worker that stores
clips in R2, and one deployment can serve a whole community.

- **Links that play in Discord.** Each clip gets a page with Open Graph video tags, so pasting the link into
  Discord (or X, iMessage, WhatsApp…) shows the video inline. Videos are served with byte ranges, so
  seeking works everywhere. When you change a clip's cover, new posts of the link show the new one.
- **Phone sync.** Every shared clip also appears in a private gallery. In Evade Clips, open *Settings → Sharing &
  phone → Show phone QR code* and scan it. Clips can be watched and downloaded from the phone. Set *Send to phone*
  to *Every new clip* to sync automatically. If the gallery link gets out, *Reset phone link* makes a new one and
  the old one stops working.
- **No watermarks, no ads, no expiry.** Clips are uploaded exactly as rendered and stay until their owner
  deletes them (*Stop sharing* in the app).
- **Plays everywhere.** The app uploads the edited version as H.264 with the audio tracks mixed into one.
  Unedited H.264 clips are uploaded as they are. Large files go up in parts.
- **Per-account storage quota** (`QUOTA_GB`, default 25). Space is reserved when an upload starts, so uploads
  running side by side can't go past it, and the finished size is checked against what was announced. A
  server-wide limit (`TOTAL_GB`) stops new uploads when the bucket is full. Optional private mode: set
  `REGISTER_SECRET` and give people that code (Settings → Sharing & phone → *Server invite code*).
- **Storage cost:** R2 has free egress, so watching and downloading clips costs nothing; you pay only for
  storage beyond R2's free tier.

- **Landing page with downloads.** `/` describes Evade Clips and serves its downloads from `public/downloads/`
  (copied there by `scripts/build-site.sh`, with `clips.json` for versions and checksums).
- **Privacy.** Each clip is *link* (anyone with the link) or *private* (only people given that clip's private
  link, `/c/<clip>?k=<key>`). A private link opens that one clip, never the gallery or other clips, and making a
  clip private again gives it a new link. Uploads that never finish are deleted after a day. Deleting the account
  (`DELETE /api/account`) removes everything it stored: clips, thumbnails, metadata, reports about its clips,
  presets and notices.
- **Moderation.** Viewers report clips and presets (reason + details), once per viewer. Moderators page through
  reports with `GET /api/admin/reports` (`?type=presets` for presets, `?cursor=` for the next page), dismiss them
  with `POST /api/admin/dismiss {key}`, and remove a clip with `POST /api/admin/remove {clip, reason, note}` or a
  preset with `POST /api/admin/remove-preset {id, note}` (all with `Authorization: Admin <ADMIN_TOKEN>`, which also
  opens private clips). A removed clip's video, thumbnail and reports go, the link says which rule it broke, and
  the owner's app shows a notice naming the clip and the reason; the server keeps only which rule and when.
- **Abuse limits.** New accounts are limited to 3 per minute per IP address, reports and preset publishing to 5
  per minute (Workers rate limiting, needs Wrangler 4.36 or later). A preset's downloads count one per viewer per
  day, and when the community index is full the least-downloaded presets of accounts that publish the most go first.
- **Phone uploads.** The gallery page uploads videos (e.g. console captures) straight from the phone; Evade Clips
  pulls them into the library. The gallery link can list and upload, never delete, and can only change clips that
  were uploaded from the phone.

## Deploy

```bash
cd clips-share
npx wrangler r2 bucket create evade-clips
# give up unfinished uploads after a day (R2's default is 7)
npx wrangler r2 bucket lifecycle add evade-clips abort-uploads v/ --abort-multipart-days 1 --force
npx wrangler secret put ADMIN_TOKEN      # for moderation
npx wrangler secret put IP_SALT          # optional: salt for the hashed IP addresses behind "one per viewer"
npx wrangler deploy                      # serves clips.evadeclient.com (custom domain in wrangler.jsonc)
# optional: make it invite-only
npx wrangler secret put REGISTER_SECRET
```

`wrangler.jsonc` also sets up the scheduled housekeeping (daily: unfinished uploads, storage recount; hourly:
preset download counts) and the rate limits. If the cron triggers aren't deployed, the same housekeeping runs
from regular requests once it's overdue.

Evade Clips uses https://clips.evadeclient.com by default. If you host your own, put its URL in Evade Clips →
Settings → Sharing & phone → *Share server*.

Live at **https://clips.evadeclient.com** (Worker `evade-clips`, bucket `evade-clips`, `ADMIN_TOKEN` set).
It's a standalone Evade Clips site in the main site's design system: `scripts/build-clips-share.py` builds its
home page (`site/landing.html`) and page frame (own header, menu and footer) into `src/shell.js`, and the shared
stylesheet, scripts, logo and screenshots are served from the bucket under `site/`; the downloads under `dl/`. After `scripts/build-site.sh`:

```bash
cd clips-share
./sync-assets.sh                                        # shell.js + site/ and dl/ files into the bucket
npx wrangler deploy --config wrangler.r2-downloads.jsonc
```

`wrangler.r2-downloads.jsonc` must carry the same `triggers`, `ratelimits` and `vars` as `wrangler.jsonc`.

Plain-http visits are redirected to https (301 for page loads, 308 for API calls so they keep their method and
body), except when the `DEV` variable is set, as in the local test below.

## Test locally

```bash
npx wrangler dev --local --port 8787 --var QUOTA_GB:0.05 --var ADMIN_TOKEN:test-admin --var DEV:1
CLIPS_SHARE_URL=http://127.0.0.1:8787 ./gradlew :clips:selfTest
```

Add `--test-scheduled` to run the housekeeping by hand: `curl "http://127.0.0.1:8787/__scheduled?cron=17+3+*+*+*"`.

The self-test checks link creation, embed tags, byte-range serving, thumbnails, the phone gallery (and that a
wrong gallery link shows nothing), reusing links, wrong-key and quota refusals, a multi-part upload that comes
back byte-for-byte identical, private clips, report → takedown → owner notice, phone uploads pulled into the
library, the landing page's downloads, and that *Stop sharing* removes everything.

## API

| | |
|---|---|
| `POST /api/register` | new account → `{account, key, viewToken}` |
| `POST /api/uploads` | start an upload `{title, game, duration, width, height, size, visibility}` (size required) → `{clip, uploadId, partSize, url, key?}` |
| `PUT /api/uploads/<clip>/<n>?uploadId=` | upload part *n* (every part but the last the same size, at least 5 MiB, at most `partSize`) |
| `POST /api/uploads/<clip>/complete` | `{uploadId, parts}` → `{clip, url, video, size, key?}` |
| `POST /api/uploads/<clip>/abort` | `{uploadId}`: cancel an upload and free its space |
| `PUT /api/clips/<clip>/thumb` | JPEG thumbnail (the cover) |
| `PATCH /api/clips/<clip>` | `{title, visibility}` → `{visibility, url, key?}` |
| `DELETE /api/clips/<clip>` | delete |
| `GET /api/clips` | your clips (private ones with their `key`), storage used and reserved, gallery link |
| `GET /api/notices?unread=1` | notices from moderation |
| `POST /api/account/rotate-gallery` | new gallery link → `{viewToken, gallery, clips: [{id, key, url}]}` (the private clips' links) |
| `DELETE /api/account` | delete the account and everything it stored → `{done}`; repeat while `done` is false (very large accounts) |
| `POST /api/report` | `{clip, reason, details}` (no account needed) |
| `GET /api/presets?sort=popular` · `POST /api/presets` · `GET`/`DELETE /api/presets/<id>` · `POST /api/presets/<id>/report` | community presets |
| `GET /c/<clip>[?k=<key>]` · `/v/<clip>.mp4` · `/t/<clip>.jpg` · `/g/<account>?t=<viewToken>` · `/presets` · `/p/<id>.evadepreset` | public page, video, thumbnail, private gallery, presets |

API calls send `Authorization: Bearer <account>.<key>`; the phone gallery sends `Gallery <account>.<viewToken>` for
listing and uploading. Only a hash of the key is stored. Private clips shared before clips had their own keys
still open with the gallery token (`?t=`) until the gallery link is reset.
