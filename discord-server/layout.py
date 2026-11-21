"""The Evade Client Discord server layout: roles, categories, channels and starter posts.

Edit this file to change the server, then run setup_server.py again. The setup script
matches everything by name, so re-running it only creates what is missing (and, with
--update, brings permissions and posts up to date). It never deletes anything.
"""

BRAND_RED = 0xE5383B
SITE = "https://evadeclient.com"

# ── Roles ─────────────────────────────────────────────────────────────────────
# Listed highest first. "perms" names discord.Permissions flags; "admin" grants everything.
ROLES = [
    {"name": "Owner", "color": BRAND_RED, "hoist": True, "admin": True},
    {"name": "Developer", "color": 0xFF6B6B, "hoist": True,
     "perms": ["manage_messages", "manage_threads", "mention_everyone", "moderate_members",
               "manage_events", "view_audit_log"]},
    {"name": "Moderator", "color": 0x4DABF7, "hoist": True,
     "perms": ["manage_messages", "manage_threads", "moderate_members", "kick_members",
               "ban_members", "manage_nicknames", "view_audit_log", "mute_members",
               "move_members", "deafen_members"]},
    {"name": "Support Team", "color": 0x51CF66, "hoist": True,
     "perms": ["manage_threads", "manage_messages"]},
    {"name": "Beta Tester", "color": 0xFCC419, "hoist": True},
    {"name": "Content Creator", "color": 0xCC5DE8, "hoist": True},
    {"name": "Member", "color": 0x868E96},
    # Ping roles: people opt in via Server Settings > Onboarding (see README).
    {"name": "Update Pings", "mentionable": True},
    {"name": "Event Pings", "mentionable": True},
]

STAFF = ["Developer", "Moderator"]
SUPPORT = STAFF + ["Support Team"]

# ── Permission presets ────────────────────────────────────────────────────────
# Each maps a role name ("@everyone" for everyone) to permission overrides.
READ_ONLY = {
    "@everyone": {"view_channel": True, "send_messages": False, "add_reactions": True,
                  "create_public_threads": False, "create_private_threads": False},
    **{r: {"send_messages": True} for r in STAFF},
}
PUBLIC = {"@everyone": {"view_channel": True}}
BETA_ONLY = {
    "@everyone": {"view_channel": False},
    **{r: {"view_channel": True} for r in ["Beta Tester", *STAFF]},
}
DEV_ONLY = {"@everyone": {"view_channel": False}, "Developer": {"view_channel": True}}
STAFF_ONLY = {
    "@everyone": {"view_channel": False},
    **{r: {"view_channel": True} for r in SUPPORT},
}
MOD_LOG = {
    "@everyone": {"view_channel": False},
    **{r: {"view_channel": True, "send_messages": False} for r in STAFF},
}

# ── Channels ──────────────────────────────────────────────────────────────────
# type: text | news | forum | voice. "news" and "forum" need the server's Community
# feature; without it they are created as plain text channels.
CATEGORIES = [
    {"name": "📌 Start Here", "perms": READ_ONLY, "channels": [
        {"name": "welcome", "topic": "What Evade is and where to find everything."},
        {"name": "rules", "topic": "Read these before posting."},
        {"name": "announcements", "type": "news", "topic": "Releases and important news. Ping: @Update Pings"},
        {"name": "changelog", "type": "news", "topic": "What changed in each version."},
        {"name": "roadmap", "topic": "What's planned next."},
    ]},
    {"name": "⬇️ Get Evade", "perms": READ_ONLY, "channels": [
        {"name": "download", "topic": f"Official downloads only: {SITE}/download"},
        {"name": "install-guide", "topic": "Step-by-step install, requirements and common fixes."},
    ]},
    {"name": "💬 Community", "perms": PUBLIC, "channels": [
        {"name": "general", "topic": "Talk about anything Evade or Minecraft.", "slowmode": 3},
        {"name": "hud-setups", "topic": "Show off your HUD layout and GUI style."},
        {"name": "clips-and-media", "topic": "Screenshots, clips and montages."},
        {"name": "texture-packs", "topic": "Packs that go well with Evade, including the official one."},
        {"name": "off-topic", "topic": "Anything that isn't Minecraft.", "slowmode": 3},
        {"name": "bot-commands", "topic": "Use bots here."},
    ]},
    {"name": "🛠️ Support", "perms": PUBLIC, "channels": [
        {"name": "support", "type": "forum",
         "topic": "One post per problem. Include your Evade version, Fabric Loader version and "
                  "your latest.log (or crash report).",
         "tags": ["Install", "Crash", "Module", "HUD / GUI", "Config", "Solved"]},
        {"name": "bug-reports", "type": "forum",
         "topic": "Found a bug? Say what you did, what you expected and what happened, and attach logs.",
         "tags": ["Combat", "Render", "HUD", "Mods", "Installer", "Website", "Confirmed", "Fixed"]},
        {"name": "suggestions", "type": "forum",
         "topic": "Ideas for new modules or features. One idea per post, and search first.",
         "tags": ["Module", "HUD", "GUI", "QoL", "Planned", "Added", "Declined"],
         "slowmode": 30},
    ]},
    {"name": "🧪 Beta", "perms": BETA_ONLY, "channels": [
        {"name": "beta-announcements", "perms": {**BETA_ONLY, "Beta Tester": {"view_channel": True, "send_messages": False}},
         "topic": "New beta builds and what to test."},
        {"name": "beta-builds", "perms": {**BETA_ONLY, "Beta Tester": {"view_channel": True, "send_messages": False}},
         "topic": "Pre-release jars. Don't share them outside this channel."},
        {"name": "beta-feedback", "topic": "Report what you found in beta builds."},
    ]},
    {"name": "🔧 Development", "perms": DEV_ONLY, "channels": [
        {"name": "dev-chat"},
        {"name": "github-feed", "topic": "Commits and releases (add a GitHub webhook here)."},
        {"name": "build-status", "topic": "CI and game-test results."},
        {"name": "website", "topic": "evadeclient.com and the installer."},
    ]},
    {"name": "🛡️ Staff", "perms": STAFF_ONLY, "channels": [
        {"name": "staff-chat"},
        {"name": "mod-log", "perms": MOD_LOG, "topic": "Discord AutoMod alerts land here."},
    ]},
    {"name": "🔊 Voice", "perms": PUBLIC, "channels": [
        {"name": "Lounge", "type": "voice"},
        {"name": "PvP 1", "type": "voice", "user_limit": 4},
        {"name": "PvP 2", "type": "voice", "user_limit": 4},
        {"name": "Duos", "type": "voice", "user_limit": 2},
        {"name": "Beta Testing", "type": "voice", "perms": BETA_ONLY},
        {"name": "Staff", "type": "voice", "perms": STAFF_ONLY},
    ]},
]

# ── Server settings ───────────────────────────────────────────────────────────
SYSTEM_CHANNEL = "welcome"          # where Discord posts join and boost messages
RULES_CHANNEL = "rules"             # Community servers only
UPDATES_CHANNEL = "mod-log"         # Community servers only: Discord's notices to staff
AUTOMOD_ALERT_CHANNEL = "mod-log"

# Phrases common in Discord scam and malware spam. AutoMod blocks messages containing them.
SCAM_KEYWORDS = [
    "*free nitro*", "*nitro for free*", "*discord.gift*", "*steamcommunity.ru*",
    "*cracked evade*", "*evade crack*", "*free minecraft account*", "*alt generator*",
]

# ── Starter posts ─────────────────────────────────────────────────────────────
# Posted once to each channel as embeds. {placeholders} come from site/version.json.
POSTS = {
    "welcome": [{
        "title": "Welcome to Evade Client",
        "description": (
            "Evade is a Fabric PvP client for **Minecraft {minecraft}** (Java 21): combat helpers, "
            "through-wall render tools, a draggable HUD and quality-of-life mods, all in one menu "
            "you open with **Right Shift**.\n\n"
            "**Start here**\n"
            "📜 Read #rules\n"
            "⬇️ Get the client in #download, then follow #install-guide\n"
            "🛠️ Stuck? Open a post in #support\n"
            "🐛 Found a bug? Post it in #bug-reports\n"
            "💡 Got an idea? #suggestions\n\n"
            "Current version: **{version}** ({date})"
        ),
        "fields": [
            ("Website", f"[evadeclient.com]({SITE})", True),
            ("Features", f"[Feature list]({SITE}/features)", True),
            ("FAQ", f"[Read the FAQ]({SITE}/faq)", True),
        ],
    }],
    "rules": [{
        "title": "Server Rules",
        "description": (
            "**1. Be respectful.** No harassment, slurs, hate speech or personal attacks.\n\n"
            "**2. Use Evade only where it's allowed.** Evade is for singleplayer, your own servers, "
            "or servers whose rules allow modified clients. Most multiplayer servers ban clients "
            "like this. Don't ask for help bypassing anti-cheat or getting around bans on servers "
            "that forbid it, and don't brag about it here.\n\n"
            "**3. Official downloads only.** Only download Evade from " + SITE + " or this "
            "server's #download channel. Don't post re-uploads, \"cracked\" versions or other "
            "jars. Staff will **never** DM you a download or ask for your Minecraft or Discord login.\n\n"
            "**4. No spam or advertising.** No unsolicited DMs, invite links or self-promotion "
            "outside the channels meant for it.\n\n"
            "**5. Keep it SFW.** No NSFW, gore or shock content anywhere, including names and avatars.\n\n"
            "**6. Use the right channel.** Support questions go in #support, bugs in #bug-reports. "
            "Include your Evade version and logs so people can help.\n\n"
            "**7. Don't leak beta builds.** Beta jars stay in the Beta channels.\n\n"
            "**8. Follow Discord's Terms of Service and Community Guidelines.**\n\n"
            "Staff have the final say. Breaking the rules gets a warning, timeout, kick or ban "
            "depending on how serious it is."
        ),
    }],
    "download": [{
        "title": "Download Evade {version}",
        "description": (
            "**Windows:** [Evade-Installer.exe]({site}/{installer_exe}) ({installer_exe_size})\n"
            "**macOS / Linux:** [EvadeInstaller.jar]({site}/{installer_jar}) ({installer_jar_size})\n"
            "**Mod jar only:** [evade-client-latest.jar]({site}/{jar}) ({jar_size})\n"
            "**Texture pack:** [Evade-Texture-Pack.zip]({site}/{pack}) ({pack_size})\n\n"
            "The installer sets up Fabric Loader, Fabric API and Evade, and adds an "
            "**Evade Client** profile to the official Minecraft Launcher.\n\n"
            "Always download from [evadeclient.com/download]({site}/download). "
            "If anyone sends you a file somewhere else, don't run it."
        ),
        "fields": [
            ("Minecraft", "{minecraft}", True),
            ("Loader", "{loader}", True),
            ("Released", "{date}", True),
            ("Mod jar SHA-256", "`{jar_sha}`", False),
        ],
    }],
    "install-guide": [{
        "title": "Installing Evade",
        "description": (
            "**With the installer (recommended)**\n"
            "1. Install [Java 21](https://adoptium.net/temurin/releases/) if you don't have it "
            "(the .exe doesn't need it on Windows).\n"
            "2. Run the installer from #download and click **Install**.\n"
            "3. Open the Minecraft Launcher, pick the **Evade Client** profile and press Play.\n"
            "4. In game, press **Right Shift** to open the menu.\n\n"
            "**Manual install**\n"
            "1. Install [Fabric Loader](https://fabricmc.net/use/) 0.18+ for {minecraft}.\n"
            "2. Put the Evade jar and [Fabric API](https://modrinth.com/mod/fabric-api) in "
            "`.minecraft/mods`.\n"
            "3. Launch the Fabric profile.\n\n"
            "**Common fixes**\n"
            "• *Game crashes on launch:* make sure you're on {minecraft} with Fabric API installed, "
            "and remove other clients' mods.\n"
            "• *Menu won't open:* the key may have been rebound. Type `.help` in chat.\n"
            "• *Settings lost:* your config is in `config/evade/config.json`. `.save` and `.load` "
            "back it up and restore it.\n\n"
            "Still stuck? Open a post in #support with your `logs/latest.log`."
        ),
    }],
    "roadmap": [{
        "title": "Roadmap",
        "description": (
            "The roadmap lives in the repo (`docs/ROADMAP.md`) and on the website. "
            "Staff will post milestones here as they land.\n\n"
            "Want something added? Post it in #suggestions."
        ),
    }],
    "beta-announcements": [{
        "title": "Welcome, Beta Testers",
        "description": (
            "New builds go in #beta-builds. Report what you find in #beta-feedback with your "
            "build number, what you did and your `latest.log`.\n\n"
            "Beta builds can be unstable: back up `config/evade/config.json` before you test, "
            "and please don't share the jars outside these channels."
        ),
    }],
}
