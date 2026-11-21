# Evade Client Discord server

`setup_server.py` builds the whole Evade community server (roles, categories, channels, permissions,
AutoMod and starter posts) in one run. The layout lives in `layout.py`; edit that to change anything.

## Setup (about 5 minutes)

1. **Make an empty server.** In Discord: **+** > *Create My Own* > *For a club or community*.
   Delete the default channels if you like (the script won't touch them).
2. **Make a bot.** Go to the [Developer Portal](https://discord.com/developers/applications) and click *New Application*,
   name it "Evade Setup", then open *Bot* > *Reset Token* and copy the token. Copy the *Application ID* from *General Information*.
3. **Invite it:**
   ```bash
   pip install -r requirements.txt
   python setup_server.py --invite YOUR_APPLICATION_ID
   ```
   Open the link and add the bot to your server. It asks for Administrator, which the setup needs.
4. **Get the server ID:** *User Settings* > *Advanced* > turn on *Developer Mode*, then right-click the server > *Copy Server ID*.
5. **Run it:**
   ```bash
   export DISCORD_TOKEN='paste-token-here'     # Windows PowerShell: $env:DISCORD_TOKEN='...'
   python setup_server.py --dry-run            # see what it will build
   python setup_server.py YOUR_SERVER_ID
   ```
6. **Turn on Community** (*Server Settings* > *Enable Community*), then run it again with `--update`.
   That converts `#announcements`/`#changelog` into announcement channels and makes `#support`,
   `#bug-reports` and `#suggestions` into forums with tags. (Discord only allows those on Community servers.)
   Before re-running, delete the plain-text `#support`, `#bug-reports` and `#suggestions` so they can be recreated as forums.
7. **Kick the setup bot** when you're done, or take away its Administrator role. Give yourself the **Owner** role.

Re-running is safe: everything is matched by name, so it only creates what's missing. `--update` also
re-applies permissions, topics and the starter posts (it edits its own posts in place). Nothing is ever deleted.
The download post reads versions, sizes and the SHA-256 from `site/version.json`, so after a release run
`python setup_server.py SERVER_ID --update` to refresh it.

## What you get

| Category | Channels | Who sees it |
|---|---|---|
| 📌 Start Here | welcome, rules, announcements, changelog, roadmap | everyone, staff-only posting |
| ⬇️ Get Evade | download, install-guide | everyone, staff-only posting |
| 💬 Community | general, hud-setups, clips-and-media, texture-packs, off-topic, bot-commands | everyone |
| 🛠️ Support | support, bug-reports, suggestions (forums with tags) | everyone |
| 🧪 Beta | beta-announcements, beta-builds, beta-feedback | Beta Tester + staff |
| 🔧 Development | dev-chat, github-feed, build-status, website | Developer |
| 🛡️ Staff | staff-chat, mod-log | Developer, Moderator, Support Team |
| 🔊 Voice | Lounge, PvP 1, PvP 2, Duos, Beta Testing, Staff | mixed |

**Roles:** Owner (admin), Developer, Moderator, Support Team, Beta Tester, Content Creator, Member, plus opt-in
ping roles Update Pings and Event Pings.

**Also set:** server icon (the Evade logo from `site/`; skip with `--no-icon`), Medium verification,
explicit-media filter for everyone, @mentions-only notifications by default, and two AutoMod rules
(mention spam, and scam phrases such as "free nitro" or "cracked evade") that alert `#mod-log`.

**Starter posts:** welcome, rules, download links, install guide, roadmap and a beta-tester intro.
The rules make clear Evade is for singleplayer, your own servers, or servers that allow modified clients,
matching the main README.

## Things to finish by hand

- **Ping roles:** *Server Settings* > *Onboarding*. Add a question like "Want notifications?" with
  Update Pings and Event Pings as answers, so members can pick them themselves.
- **GitHub feed:** in `#github-feed`, *Edit Channel* > *Integrations* > *Webhooks* > *New Webhook*, copy the URL,
  then in the GitHub repo go to *Settings* > *Webhooks*, paste it with `/github` on the end, and choose *application/json*.
- **Member role:** to give it automatically, use Onboarding (default role) or a bot such as Carl-bot.
- **Website link:** add an invite (*Invite People* > *Edit invite link* > never expire) to evadeclient.com.
