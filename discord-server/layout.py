"""Discord server layout for a Minecraft PvP / performance client.

Fill in CLIENT below, then run setup_server.py. Everything else (roles, channels,
starter posts) is built from it. Re-running only creates what's missing (and, with
--update, brings permissions, topics and posts up to date). Nothing is ever deleted.
"""

# ── Your client: fill these in ────────────────────────────────────────────────
CLIENT = {
    "name": "My Client",                  # shown in posts, the server and AutoMod
    "short": "MyClient",                  # one word, used in role/AutoMod names
    "site": "https://example.com",        # your website (no trailing slash)
    "download_url": "https://example.com/download",
    "minecraft": "1.21.11",               # supported Minecraft version(s)
    "loader": "Fabric",                   # Fabric, Forge, NeoForge, or a custom launcher
    "java": "21",
    "menu_key": "Right Shift",            # key that opens the in-game menu
    "version": "1.0.0",                   # current release; bump and run --update
    "color": 0x5865F2,                    # brand colour for roles and embeds
}
ICON_PATH = ""   # path to a PNG/JPG for the server icon, or leave empty

BRAND = CLIENT["color"]

# ── Roles ─────────────────────────────────────────────────────────────────────
# Listed highest first. "perms" names Discord permissions; "admin" grants everything.
ROLES = [
    {"name": "Owner", "color": BRAND, "hoist": True, "admin": True},
    {"name": "Developer", "color": 0xFF6B6B, "hoist": True,
     "perms": ["manage_messages", "manage_threads", "mention_everyone", "moderate_members",
               "manage_events", "view_audit_log"]},
    {"name": "Moderator", "color": 0x4DABF7, "hoist": True,
     "perms": ["manage_messages", "manage_threads", "moderate_members", "kick_members",
               "ban_members", "manage_nicknames", "view_audit_log", "mute_members",
               "move_members", "deafen_members"]},
    {"name": "Support Team", "color": 0x51CF66, "hoist": True,
     "perms": ["manage_threads", "manage_messages"]},
    {"name": "Partner", "color": 0x20C997, "hoist": True},       # partnered servers / teams
    {"name": "Content Creator", "color": 0xCC5DE8, "hoist": True},
    {"name": "Beta Tester", "color": 0xFCC419, "hoist": True},
    {"name": "Supporter", "color": 0xF783AC, "hoist": True},     # donors / cosmetic buyers
    {"name": "Member", "color": 0x868E96},
    # Ping roles: people opt in via Server Settings > Onboarding (see README).
    {"name": "Update Pings", "mentionable": True},
    {"name": "Event Pings", "mentionable": True},
    {"name": "Giveaway Pings", "mentionable": True},
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
BETA_READ_ONLY = {**BETA_ONLY, "Beta Tester": {"view_channel": True, "send_messages": False}}
CREATOR_ONLY = {
    "@everyone": {"view_channel": False},
    **{r: {"view_channel": True} for r in ["Content Creator", "Partner", *STAFF]},
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
N = CLIENT["name"]
CATEGORIES = [
    {"name": "📌 Start Here", "perms": READ_ONLY, "channels": [
        {"name": "welcome", "topic": f"What {N} is and where to find everything."},
        {"name": "rules", "topic": "Read these before posting."},
        {"name": "announcements", "type": "news", "topic": "Releases and important news."},
        {"name": "changelog", "type": "news", "topic": "What changed in each version."},
        {"name": "roadmap", "topic": "What's planned next."},
        {"name": "giveaways", "topic": "Cosmetics, capes and other giveaways."},
    ]},
    {"name": f"⬇️ Get {N}", "perms": READ_ONLY, "channels": [
        {"name": "download", "topic": f"Official downloads only: {CLIENT['download_url']}"},
        {"name": "install-guide", "topic": "Requirements, install steps and common fixes."},
        {"name": "faq"},
    ]},
    {"name": "💬 Community", "perms": PUBLIC, "channels": [
        {"name": "general", "topic": f"Talk about {N} and Minecraft PvP.", "slowmode": 3},
        {"name": "fps-and-performance", "topic": "Settings, benchmarks and tips for more FPS."},
        {"name": "hud-setups", "topic": "Show off your HUD layout and mod setup."},
        {"name": "cosmetics", "topic": "Capes, wings, emotes and other cosmetics."},
        {"name": "clips-and-media", "topic": "Screenshots, clips and montages."},
        {"name": "texture-packs", "topic": "PvP packs that pair well with the client."},
        {"name": "looking-for-group", "topic": "Find duos, teams and people to queue with."},
        {"name": "off-topic", "topic": "Anything that isn't Minecraft.", "slowmode": 3},
        {"name": "bot-commands", "topic": "Use bots here."},
    ]},
    {"name": "🛠️ Support", "perms": PUBLIC, "channels": [
        {"name": "support", "type": "forum",
         "topic": f"One post per problem. Include your {N} version, Minecraft version, OS and "
                  "your latest.log (or crash report).",
         "tags": ["Install", "Crash", "Performance", "Mods", "Cosmetics", "Account", "Solved"]},
        {"name": "bug-reports", "type": "forum",
         "topic": "Say what you did, what you expected and what happened, and attach logs.",
         "tags": ["HUD", "Mods", "Performance", "Cosmetics", "Launcher", "Website", "Confirmed", "Fixed"]},
        {"name": "suggestions", "type": "forum",
         "topic": "Ideas for new mods or features. One idea per post, and search first.",
         "tags": ["Mod", "HUD", "Performance", "Cosmetic", "QoL", "Planned", "Added", "Declined"],
         "slowmode": 30},
    ]},
    {"name": "🎥 Creators", "perms": CREATOR_ONLY, "channels": [
        {"name": "creator-chat", "topic": "For Content Creators and Partners."},
        {"name": "creator-assets", "perms": {**CREATOR_ONLY, "Content Creator": {"view_channel": True, "send_messages": False},
                                             "Partner": {"view_channel": True, "send_messages": False}},
         "topic": "Logos, banners and press kit."},
    ]},
    {"name": "🧪 Beta", "perms": BETA_ONLY, "channels": [
        {"name": "beta-announcements", "perms": BETA_READ_ONLY, "topic": "New beta builds and what to test."},
        {"name": "beta-builds", "perms": BETA_READ_ONLY,
         "topic": "Pre-release builds. Don't share them outside this channel."},
        {"name": "beta-feedback", "topic": "Report what you found in beta builds."},
    ]},
    {"name": "🔧 Development", "perms": DEV_ONLY, "channels": [
        {"name": "dev-chat"},
        {"name": "github-feed", "topic": "Commits and releases (add a GitHub webhook here)."},
        {"name": "build-status", "topic": "CI results."},
        {"name": "website", "topic": "Website, launcher and installer."},
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
        {"name": "Streaming", "type": "voice"},
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
_n = CLIENT["name"].lower()
SCAM_KEYWORDS = [
    "*free nitro*", "*nitro for free*", "*discord.gift*", "*steamcommunity.ru*",
    f"*cracked {_n}*", f"*{_n} crack*", "*free minecraft account*", "*alt generator*",
    "*free capes*", "*cape generator*",
]

# ── Starter posts ─────────────────────────────────────────────────────────────
# Posted once to each channel as embeds. {placeholders} are the CLIENT keys above.
# #channel-name becomes a clickable link.
POSTS = {
    "welcome": [{
        "title": "Welcome to {name}",
        "description": (
            "{name} is a PvP and performance client for **Minecraft {minecraft}**: "
            "more FPS, a customisable HUD, quality-of-life mods and cosmetics, all in one menu "
            "you open with **{menu_key}**.\n\n"
            "**Start here**\n"
            "📜 Read #rules\n"
            "⬇️ Get the client in #download, then follow #install-guide\n"
            "❓ Check #faq\n"
            "🛠️ Stuck? Open a post in #support\n"
            "🐛 Found a bug? Post it in #bug-reports\n"
            "💡 Got an idea? #suggestions\n\n"
            "Current version: **{version}**"
        ),
        "fields": [
            ("Website", "[{site}]({site})", True),
            ("Download", "[Get {name}]({download_url})", True),
        ],
    }],
    "rules": [{
        "title": "Server Rules",
        "description": (
            "**1. Be respectful.** No harassment, slurs, hate speech or personal attacks.\n\n"
            "**2. No cheating.** {name} is a legit client: it only includes features that are "
            "fair to use in multiplayer. Don't ask for, share or advertise hacked clients, "
            "cheat mods or ways to bypass anti-cheat, and follow the rules of every server you play on.\n\n"
            "**3. Official downloads only.** Only download {name} from {download_url} or #download. "
            "Don't post re-uploads, \"cracked\" versions or other files claiming to be {name}. "
            "Staff will **never** DM you a download or ask for your Minecraft, Microsoft or "
            "Discord login.\n\n"
            "**4. No spam or advertising.** No unsolicited DMs, invite links or self-promotion "
            "outside the channels meant for it.\n\n"
            "**5. Keep it SFW.** No NSFW, gore or shock content anywhere, including names and avatars.\n\n"
            "**6. Use the right channel.** Support questions go in #support, bugs in #bug-reports. "
            "Include your version and logs so people can help.\n\n"
            "**7. Don't leak beta builds.** Beta builds stay in the Beta channels.\n\n"
            "**8. Follow Discord's Terms of Service and Community Guidelines.**\n\n"
            "Staff have the final say. Breaking the rules gets a warning, timeout, kick or ban "
            "depending on how serious it is."
        ),
    }],
    "download": [{
        "title": "Download {name} {version}",
        "description": (
            "Get the latest version from **[{download_url}]({download_url})**.\n\n"
            "Always download from the official site. If anyone sends you a file somewhere "
            "else, don't run it."
        ),
        "fields": [
            ("Version", "{version}", True),
            ("Minecraft", "{minecraft}", True),
            ("Loader", "{loader}", True),
        ],
    }],
    "install-guide": [{
        "title": "Installing {name}",
        "description": (
            "**Requirements**\n"
            "• Minecraft Java Edition {minecraft}\n"
            "• Java {java} (most launchers include it)\n\n"
            "**Install**\n"
            "1. Download {name} from #download.\n"
            "2. Run the installer (or put the jar in `.minecraft/mods` with {loader}).\n"
            "3. Open the Minecraft Launcher, pick the **{name}** profile and press Play.\n"
            "4. In game, press **{menu_key}** to open the menu.\n\n"
            "**Common fixes**\n"
            "• *Crashes on launch:* check you're on {minecraft} and remove other clients' mods.\n"
            "• *Low FPS:* see #fps-and-performance, and make sure the game is using your "
            "dedicated GPU.\n"
            "• *Menu won't open:* the key may have been rebound in your settings.\n\n"
            "Still stuck? Open a post in #support with your `logs/latest.log`."
        ),
    }],
    "faq": [{
        "title": "Frequently Asked Questions",
        "description": (
            "**Is {name} allowed on servers?**\n"
            "It's built to be a legit client with no unfair advantages, but every server sets "
            "its own rules about allowed mods. Check them before you play.\n\n"
            "**Is it free?**\n"
            "Edit this answer in `layout.py` to match your pricing and cosmetics.\n\n"
            "**Which versions are supported?**\n"
            "Minecraft {minecraft} on {loader}.\n\n"
            "**Can I use it with other mods?**\n"
            "Most performance and cosmetic mods are fine; other clients' mods usually conflict.\n\n"
            "**How do I report a bug or suggest a feature?**\n"
            "Use #bug-reports or #suggestions."
        ),
    }],
    "roadmap": [{
        "title": "Roadmap",
        "description": "Staff will post milestones here as they land. Want something added? "
                       "Post it in #suggestions.",
    }],
    "beta-announcements": [{
        "title": "Welcome, Beta Testers",
        "description": (
            "New builds go in #beta-builds. Report what you find in #beta-feedback with the "
            "build number, what you did and your `latest.log`.\n\n"
            "Beta builds can be unstable, so back up your settings before testing, and please "
            "don't share builds outside these channels."
        ),
    }],
}
