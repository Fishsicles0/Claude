# Discord server for your Minecraft client

`setup_server.py` builds a full community server for a Minecraft PvP / performance client
(roles, categories, channels, permissions, AutoMod and starter posts) in one run. The layout
lives in `layout.py`.

## 1. Fill in your client

Open `layout.py` and edit the `CLIENT` block at the top: name, website, download link,
Minecraft version, loader, menu key, current version and brand colour. Set `ICON_PATH` to
your logo if you have one. Every post, channel topic and AutoMod rule uses these values.

Do this **before the first run**. Channels are matched by name, so changing the client's name
afterwards creates a second "Get …" category and new AutoMod rules instead of renaming the old ones.

Also look at the FAQ post in `POSTS["faq"]`: the "Is it free?" answer is a placeholder.

## 2. Run it (about 5 minutes)

1. **Make an empty server.** In Discord: **+** > *Create My Own* > *For a club or community*.
2. **Make a bot.** In the [Developer Portal](https://discord.com/developers/applications), click *New Application*,
   then open *Bot* > *Reset Token* and copy the token. Copy the *Application ID* from *General Information*.
3. **Invite it:**
   ```bash
   pip install -r requirements.txt
   python setup_server.py --invite YOUR_APPLICATION_ID
   ```
   Open the link and add the bot to your server. It asks for Administrator, which the setup needs.
4. **Get the server ID:** *User Settings* > *Advanced* > turn on *Developer Mode*, then right-click the server > *Copy Server ID*.
5. **Build it:**
   ```bash
   export DISCORD_TOKEN='paste-token-here'     # Windows PowerShell: $env:DISCORD_TOKEN='...'
   python setup_server.py --dry-run            # see what it will build
   python setup_server.py YOUR_SERVER_ID --name   # --name also renames the server to your client
   ```
6. **Turn on Community** (*Server Settings* > *Enable Community*). Discord only allows announcement
   channels and forums on Community servers, so before this they're plain text channels. Delete the text
   `#support`, `#bug-reports` and `#suggestions`, then run again with `--update` to recreate them as forums
   with tags and turn `#announcements`/`#changelog` into announcement channels.
7. **Remove the setup bot** when you're done (it has Administrator). Give yourself the **Owner** role.

Re-running is safe: it only creates what's missing and never deletes anything. `--update` also re-applies
permissions and topics and edits its own starter posts in place, so after a release bump `version` in
`layout.py` and run `python setup_server.py SERVER_ID --update`.

## What you get

| Category | Channels | Who sees it |
|---|---|---|
| 📌 Start Here | welcome, rules, announcements, changelog, roadmap, giveaways | everyone, staff-only posting |
| ⬇️ Get My Client | download, install-guide, faq | everyone, staff-only posting |
| 💬 Community | general, fps-and-performance, hud-setups, cosmetics, clips-and-media, texture-packs, looking-for-group, off-topic, bot-commands | everyone |
| 🛠️ Support | support, bug-reports, suggestions (forums with tags) | everyone |
| 🎥 Creators | creator-chat, creator-assets | Content Creator, Partner, staff |
| 🧪 Beta | beta-announcements, beta-builds, beta-feedback | Beta Tester, staff |
| 🔧 Development | dev-chat, github-feed, build-status, website | Developer |
| 🛡️ Staff | staff-chat, mod-log | Developer, Moderator, Support Team |
| 🔊 Voice | Lounge, PvP 1, PvP 2, Duos, Streaming, Beta Testing, Staff | mixed |

**Roles:** Owner (admin), Developer, Moderator, Support Team, Partner, Content Creator, Beta Tester,
Supporter, Member, plus opt-in Update Pings, Event Pings and Giveaway Pings.

**Also set:** Medium verification, explicit-media filter for everyone, @mentions-only notifications by
default, optional server icon and name, and two AutoMod rules (mention spam, and scam phrases such as
"free nitro", "cracked my client" or "cape generator") that alert `#mod-log`.

**Starter posts:** welcome, rules, download, install guide, FAQ, roadmap and a beta-tester intro. The rules
say it's a legit client, ban hacked clients and anti-cheat bypass talk, and warn that staff never DM downloads.

## Finish by hand

- **Ping roles:** *Server Settings* > *Onboarding*. Add a question like "Want notifications?" with the
  ping roles as answers so members can pick them.
- **Member role:** give it automatically with Onboarding's default roles, or a bot such as Carl-bot.
- **GitHub feed:** in `#github-feed`, *Edit Channel* > *Integrations* > *Webhooks* > *New Webhook*. In your
  GitHub repo, add that URL with `/github` on the end under *Settings* > *Webhooks* (content type *application/json*).
- **Website link:** make a never-expiring invite and put it on your site.
