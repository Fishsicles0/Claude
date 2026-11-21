# Discord server for your Minecraft client

Two scripts:

- **`setup_server.py`** builds the server once: roles, categories, channels, permissions,
  AutoMod and starter posts.
- **`bot.py`** keeps running and handles verification, tickets, logs, anti-raid, anti-nuke and staff commands.

Everything is configured in `layout.py`. Fill in the `CLIENT` block (name, website, download link,
versions, colour) and look over `SECURITY` before the first run.

## Layout

| Category | Channels | Who sees it |
|---|---|---|
| ✅ Start Here | 📖︱rules, ✅︱verify | Everyone (the only thing unverified users can see) |
| 📌 Info & Announcements | announcements, changelog, giveaways, socials, faq | Members, read-only |
| ⬇️ Download | download, install-guide, known-issues (read-only), download-help | Members |
| 🎫 Support | create-ticket, support, bug-reports, suggestions (forums) | Members |
| 💬 Hangout | general, media, hud-setups, cosmetics, fps-and-performance, looking-for-group, off-topic, bot-commands | Members |
| 🧪 Beta Testing | beta-announcements, beta-downloads, beta-chat, beta-bugs, beta-feedback, voice | Beta Tester + staff |
| 🔊 Voice Channels | Lounge, Room 1–6 (2/3/4/5/6/10 people), AFK | Members |
| 🎫 Open Tickets | created by the bot, one private channel per ticket | ticket owner + that ticket type's staff |
| 🛡️ Staff | staff-announcements, staff-handbook, staff-chat, staff-commands, reports, appeals, voice | Mod, Developer, Admin |
| 🔧 Development | dev-chat, github-feed, build-status, release-planning, voice | Developer, Admin |
| 👑 Management | admin-chat, staff-applications, voice | Admin |
| 📜 Logs | security-alerts, mod-log, message-log, member-log, ticket-transcripts, role-log, server-log | Admin (Mods see the moderation logs) |

**Roles** (highest first): Founder, Owner, Admin, Bot, Developer, Mod, Beta Tester, Member.

## Security

- **Locked by default.** `@everyone` has no server-level permissions, so unverified users see only
  the rules and verify channels. A channel added later stays hidden until you allow it.
- **Verification gate.** Members press Verify, then pick the right code from five buttons. Accounts
  younger than 7 days can't self-verify; staff can `/verify` them. Discord's own verification level is **High**.
- **Administrator only for Founder and Owner.** Admin gets 22 specific permissions instead, so one compromised
  Admin account can't do everything. Mods can kick and timeout. Only Admins can ban, manage roles or channels,
  or create webhooks.
- **Least-privilege bot.** The bot uses Administrator only while setup runs. Then you re-invite it with a
  runtime link that has just the permissions it needs, and put its role below Admin.
- **AutoMod.** Five rules: slurs and sexual content, spam, mention raids (with raid protection), scam and
  token-grabber phrases, and invite links. Hits are blocked, logged to mod-log and, for the worst, timed out.
- **Anti-raid.** 10 joins within 30 seconds turns on raid mode, which pauses verification and pings the admin roles.
- **Anti-nuke.** If one account deletes channels or roles, or bans or kicks people, 3 times within 60 seconds,
  the bot alerts admins, strips that account's roles and times it out, or kicks it if it's a bot. It can only
  do that to accounts below its own role, and says so when it can't.
- **Watchdog.** It alerts on new webhooks, bots being added, roles gaining dangerous permissions, powerful roles
  being handed out, and the verification level or 2FA requirement being lowered.
- **Full logs.** Deleted and edited messages, joins and leaves (with account age), role changes, channel and
  server changes, invites, bans and timeouts, and a transcript of every ticket.
- **Members can't create invites** (change `members_can_invite` if you want them to).

## Tickets

`#create-ticket` has a dropdown: Download/install help, Technical support, Bug report, Account/purchase,
Report a user, Appeal, Other. Picking one opens a short form, then a private channel that only the member
and the right staff can see. Account tickets and appeals are Admin-only, and bug tickets also include Developers.
Staff press **Claim**, and either side presses **Close**. The transcript goes to `#ticket-transcripts` and to the
member's DMs. Each member gets one open ticket at a time and a 5-minute cooldown between tickets.

## Staff commands

| Command | Who | What |
|---|---|---|
| `/verify @member` | Mod+ | Verify someone manually (skips the account-age check) |
| `/lockdown [channel]` | Mod+ | Stop members talking in one channel, or in all member channels |
| `/unlock [channel]` | Mod+ | Undo it, restoring the exact previous permissions |
| `/raidmode on/off` | Mod+ | Pause or resume verification |
| `/purge count [@user]` | Mod+ | Delete up to 100 recent messages |
| `/ticket-add` / `/ticket-remove` | ticket staff | Add or remove someone in the current ticket |
| `/panels` | Admin+ | Re-post the verify and ticket panels |
| `/warn` `/warnings` | Mod+ | Warn a member (DM + log), list warnings; Admins can clear them |
| `/timeout` `/kick` | Mod+ | Time out (1 min to 1 week) or kick, with a DM and a log entry |
| `/ban` `/unban` | Admin+ | Ban (optionally deleting recent messages) or unban by ID |
| `/userinfo` `/slowmode` | Mod+ | Account age, join date, roles, warnings; set slowmode |
| `/announce` | Developer, Admin+ | Styled announcement with optional ping and image |
| `/help` `/ticket` | everyone | What the bot does; open a ticket from anywhere |
| Report message / Report user | everyone | Apps menu on a message or user; opens a report ticket with the evidence |

Mods can't act on staff; only Admins can. Nobody can act on themselves or on bots.

Members never see these commands.

## Setup (about 15 minutes)

1. **Create an empty server** in Discord (**+** > *Create My Own*).
2. **Create the bot** in the [Developer Portal](https://discord.com/developers/applications): *New Application*,
   then *Bot*:
   - *Reset Token* and copy it. Treat it like a password and never commit it.
   - Turn **off** *Public Bot*, so nobody else can add it to their servers.
   - Turn **on** *Server Members Intent* and *Message Content Intent*.
3. **Install and invite:**
   ```bash
   cd discord-server
   pip install -r requirements.txt
   python setup_server.py --invite YOUR_APPLICATION_ID   # prints a setup link and a runtime link
   ```
   Open the **setup link** and add the bot to your server.
4. **Build the server:**
   ```bash
   export DISCORD_TOKEN='paste-token-here'     # PowerShell: $env:DISCORD_TOKEN='...'
   python setup_server.py --dry-run            # preview
   python setup_server.py YOUR_SERVER_ID --name
   ```
   (Server ID: *User Settings* > *Advanced* > *Developer Mode*, then right-click the server > *Copy Server ID*.)
5. **Enable Community** (*Server Settings* > *Enable Community*). Delete the plain-text `support`, `bug-reports`,
   `suggestions` and `beta-bugs`, then run `python setup_server.py YOUR_SERVER_ID --update` to recreate them as
   forums and turn on the announcement channels.
6. **Lock it down by hand** (Discord only lets the server owner do these):
   - *Server Settings* > *Safety Setup*: turn on **Require 2FA for moderator actions**, and enable the
     DM spam and raid filters there.
   - *Server Settings* > *Roles*: drag the bot's own role to sit **just below Admin**.
   - Open the **runtime link** from step 3. This replaces the bot's Administrator with just what it needs.
   - Give yourself **Founder**, and give staff their roles (they get Member automatically).
7. **Start the bot** and keep it running:
   ```bash
   python bot.py
   ```
   It posts the verify and ticket panels on first start. For 24/7 hosting, run it on a VPS or a small cloud host
   with `systemd`, `pm2` or Docker. The bot needs `state.json`, which setup wrote, next to it.

`setup_server.py` is safe to re-run. It matches everything by the IDs in `state.json`, and `--update`
re-applies permissions, topics and posts. It never deletes anything.

## Optional extras

- **GitHub feed:** an Admin creates a webhook in `#github-feed`, then adds its URL with `/github` on the end
  in your GitHub repo under *Settings* > *Webhooks* (content type `application/json`).
- **Onboarding:** *Server Settings* > *Onboarding* can ask new members questions once they've verified.
- **Beta Testers:** Admins give the Beta Tester role by hand. Keep the list short; leaks start there.

## Serverless bot (Cloudflare Pages)

`worker/bot.js` runs verification, tickets and the staff commands on Cloudflare Pages, so nothing has to
stay on. Discord sends each button press, form and slash command to `https://<project>.pages.dev/interactions`.

```bash
python deploy_worker.py build              # bakes the IDs from state.json into worker/dist/_worker.js
# deploy worker/dist to a Pages project (advanced mode _worker.js), bind a KV namespace as KV,
# and add DISCORD_TOKEN as an encrypted variable in the project's settings
python deploy_worker.py endpoint https://<project>.pages.dev
python deploy_worker.py commands
python deploy_worker.py panels
```

It can't see live events (messages being deleted, members joining), so message logs, join-raid detection and
anti-nuke need `bot.py` running somewhere. Discord's own AutoMod alerts, join messages (in member-log) and the
audit log still cover a lot of that.
