# Evade Clips Share

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

## Deploy

```bash
cd clips-share
npx wrangler r2 bucket create evade-clips
npx wrangler deploy
# optional: make it invite-only
npx wrangler secret put REGISTER_SECRET
```

Put the worker's URL (or a custom domain you route to it) into Evade Clips → Settings → Sharing → *Share
server*.

## Test locally

```bash
npx wrangler dev --local --port 8787 --var QUOTA_GB:0.03
CLIPS_SHARE_URL=http://127.0.0.1:8787 ./gradlew :clips:selfTest
```

The self-test checks link creation, embed tags, byte-range serving, thumbnails, the phone gallery (and that a
wrong gallery link shows nothing), reusing links, wrong-key and quota refusals, a multi-part upload that comes
back byte-for-byte identical, and that *Stop sharing* removes everything.

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
