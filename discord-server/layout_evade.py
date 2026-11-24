"""Discord server layout for a Minecraft PvP / performance client.

Fill in CLIENT and SECURITY, then run setup_server.py (builds the server) and bot.py
(verification, tickets, logs, anti-raid). Everything below is matched by its "key", so
you can rename channels or change emojis and re-run with --update.

Security model
--------------
* @everyone can see nothing except #rules and #verify. Pressing Verify gives the Member
  role, which unlocks the member channels. New channels are hidden by default.
* Only Founder and Owner have Administrator. Admin gets the specific permissions it needs,
  so a compromised Admin account can't take over the server.
* Bans, role management, webhooks and invites are restricted to Admin and above.
* Every restricted area also lists Admin explicitly, since Admin has no Administrator.
"""

# ── Your client ───────────────────────────────────────────────────────────────
CLIENT = {
    "name": "Evade Client",               # shown in posts and the server name (--name)
    "short": "Client",                    # one word, used for AutoMod rule names
    "site": "https://evadeclient.com",    # website (no trailing slash)
    "download_url": "https://evadeclient.com/download",
    "minecraft": "1.21.11",
    "loader": "Fabric",
    "java": "21",
    "menu_key": "Right Shift",
    "version": "2.1.0",                   # bump and run setup_rest.py --update after a release
    "color": 0x5865F2,                    # brand colour for embeds
}
BOT_NAME = "Evade Client"   # the bot/app name, used on its Terms and Privacy pages
ICON_PATH = str(__import__("pathlib").Path(__file__).with_name("assets") / "bot-avatar.png")   # path to a PNG/JPG for the server icon, or leave empty

# ── Security settings (used by setup_server.py and bot.py) ───────────────────
SECURITY = {
    "min_account_age_days": 7,        # younger Discord accounts can't self-verify; staff can /verify them
    "verify_fail_cooldown": 60,       # seconds to wait after a wrong verification answer
    "raid_joins": 10,                 # this many joins ...
    "raid_window": 30,                # ... within this many seconds turns on raid mode
    "nuke_actions": 3,                # one person deleting/banning this many things ...
    "nuke_window": 60,                # ... within this many seconds triggers an anti-nuke alert
    "max_open_tickets": 1,            # per member
    "ticket_cooldown": 300,           # seconds between tickets from the same member
    "members_can_invite": False,      # let Members create invite links
    "verification_level": "high",     # Discord's own gate: low | medium | high | highest
}

# ── Roles ─────────────────────────────────────────────────────────────────────
# Highest first. "admin" grants Administrator (keep this to Founder and Owner).
_MEMBER_PERMS = [
    "view_channel", "send_messages", "send_messages_in_threads", "create_public_threads",
    "read_message_history", "add_reactions", "embed_links", "attach_files",
    "use_external_emojis", "use_external_stickers", "use_application_commands",
    "connect", "speak", "stream", "use_voice_activation", "use_embedded_activities",
    "change_nickname", "request_to_speak",
]
_MOD_PERMS = [
    "kick_members", "moderate_members", "manage_messages", "manage_threads", "manage_nicknames",
    "mute_members", "deafen_members", "move_members", "view_audit_log", "create_instant_invite",
]
_ADMIN_PERMS = _MOD_PERMS + [
    "ban_members", "manage_channels", "manage_roles", "manage_guild", "manage_webhooks",
    "manage_expressions", "create_expressions", "manage_events", "create_events",
    "mention_everyone", "priority_speaker", "view_guild_insights",
]

ROLES = [
    {"key": "founder", "name": "Founder", "color": 0xF1C40F, "hoist": True, "admin": True},
    {"key": "owner", "name": "Owner", "color": 0xE74C3C, "hoist": True, "admin": True},
    {"key": "admin", "name": "Admin", "color": 0xE67E22, "hoist": True, "perms": _ADMIN_PERMS},
    {"key": "bot", "name": "Bot", "color": 0x95A5A6, "hoist": True,
     "perms": ["view_channel", "send_messages", "embed_links", "attach_files", "read_message_history",
               "add_reactions", "use_external_emojis"]},
    {"key": "developer", "name": "Developer", "color": 0x9B59B6, "hoist": True,
     "perms": ["create_instant_invite", "priority_speaker"]},
    {"key": "mod", "name": "Mod", "color": 0x3498DB, "hoist": True, "perms": _MOD_PERMS},
    {"key": "beta", "name": "Beta Tester", "color": 0x2ECC71, "hoist": True},
    {"key": "member", "name": "Member", "color": 0x99AAB5,
     "perms": _MEMBER_PERMS + (["create_instant_invite"] if SECURITY["members_can_invite"] else [])},
]
# @everyone (unverified) gets nothing at server level; the gate channels allow what they need.
EVERYONE_PERMS = ["read_message_history", "use_application_commands"]

# Groups used below and by bot.py (role keys).
ADMINS = ["admin"]                       # Founder/Owner have Administrator and see everything
STAFF = ["mod", "developer", *ADMINS]
TICKET_STAFF = ["mod", *ADMINS]

# ── Permission presets ────────────────────────────────────────────────────────
def _see(*roles, send=True):
    return {r: {"view_channel": True, **({} if send else {"send_messages": False})} for r in roles}

_HIDE = {"@everyone": {"view_channel": False}, "member": {"view_channel": False}}

GATE = {"@everyone": {"view_channel": True, "send_messages": False, "add_reactions": False,
                      "create_public_threads": False, "create_private_threads": False},
        "member": {"view_channel": False}}
GATE_RULES = {**GATE, "member": {"view_channel": True, "send_messages": False}}
MEMBER = {"@everyone": {"view_channel": False}, "member": {"view_channel": True}}
MEMBER_RO = {"@everyone": {"view_channel": False},
             "member": {"view_channel": True, "send_messages": False, "create_public_threads": False,
                        "send_messages_in_threads": False},
             "developer": {"send_messages": True}, "admin": {"send_messages": True}}
BETA = {**_HIDE, **_see("beta", *STAFF)}
BETA_RO = {**_HIDE, **_see("beta", "mod", send=False), **_see("developer", "admin")}
STAFF_ONLY = {**_HIDE, **_see(*STAFF)}
STAFF_RO = {**_HIDE, **_see("mod", "developer", send=False), **_see("admin")}
DEV_ONLY = {**_HIDE, **_see("developer", *ADMINS)}
ADMIN_ONLY = {**_HIDE, **_see(*ADMINS)}
TICKETS = {**_HIDE, **_see(*TICKET_STAFF)}
LOG_ADMIN = {**_HIDE, **_see(*ADMINS, send=False)}
LOG_STAFF = {**_HIDE, **_see("mod", *ADMINS, send=False)}

# ── Channels ──────────────────────────────────────────────────────────────────
# Text channels are named "<emoji>︱<name>", voice channels "<emoji> <name>".
# type: text | news | forum | voice. news/forum need Community enabled (else plain text).
N = CLIENT["name"]
CATEGORIES = [
    {"key": "gate", "name": "✅ Start Here", "perms": GATE, "channels": [
        {"key": "rules", "emoji": "📖", "name": "rules", "perms": GATE_RULES,
         "topic": "Read the rules, then verify in #verify."},
        {"key": "verify", "emoji": "✅", "name": "verify", "topic": "Press Verify to unlock the server."},
    ]},
    {"key": "info", "name": "📌 Info & Announcements", "perms": MEMBER_RO, "channels": [
        {"key": "welcome", "emoji": "👋", "name": "welcome", "topic": "Say hi to everyone who just joined."},
        {"key": "announcements", "emoji": "📢", "name": "announcements", "type": "news"},
        {"key": "changelog", "emoji": "📝", "name": "changelog", "type": "news",
         "topic": "What changed in each version."},
        {"key": "giveaways", "emoji": "🎁", "name": "giveaways"},
        {"key": "socials", "emoji": "📱", "name": "socials"},
        {"key": "faq", "emoji": "❓", "name": "faq"},
    ]},
    {"key": "download", "name": f"⬇️ Download {N}", "perms": MEMBER_RO, "channels": [
        {"key": "download", "emoji": "⬇️", "name": "download",
         "topic": "Official downloads only."},
        {"key": "install-guide", "emoji": "📘", "name": "install-guide"},
        {"key": "known-issues", "emoji": "⚠️", "name": "known-issues"},
        {"key": "download-help", "emoji": "🙋", "name": "download-help", "perms": MEMBER, "slowmode": 10,
         "topic": "Quick install questions. For anything longer, open a ticket."},
    ]},
    {"key": "support", "name": "🎫 Support", "perms": MEMBER, "channels": [
        {"key": "create-ticket", "emoji": "🎫", "name": "create-ticket", "perms": MEMBER_RO,
         "topic": "Open a private ticket with the team."},
        {"key": "support", "emoji": "🛠️", "name": "support", "type": "forum",
         "topic": f"One post per problem. Include your {N} version, Minecraft version, OS and latest.log.",
         "tags": ["Install", "Crash", "Performance", "Mods", "Cosmetics", "Account", "Solved"]},
        {"key": "bug-reports", "emoji": "🐛", "name": "bug-reports", "type": "forum",
         "topic": "What you did, what you expected, what happened, and logs.",
         "tags": ["HUD", "Mods", "Performance", "Cosmetics", "Launcher", "Confirmed", "Fixed"]},
        {"key": "suggestions", "emoji": "💡", "name": "suggestions", "type": "forum", "slowmode": 60,
         "topic": "One idea per post. Search first.",
         "tags": ["Mod", "HUD", "Performance", "Cosmetic", "QoL", "Planned", "Added", "Declined"]},
    ]},
    {"key": "community", "name": "💬 Hangout", "perms": MEMBER, "channels": [
        {"key": "general", "emoji": "💬", "name": "general", "slowmode": 3},
        {"key": "media", "emoji": "📸", "name": "media", "topic": "Screenshots, clips and montages."},
        {"key": "hud-setups", "emoji": "🖥️", "name": "hud-setups"},
        {"key": "cosmetics", "emoji": "👕", "name": "cosmetics", "topic": "Evade cosmetics are coming soon. Until then: show off your themes, crosshairs and packs."},
        {"key": "fps", "emoji": "⚡", "name": "fps-and-performance"},
        {"key": "lfg", "emoji": "🤝", "name": "looking-for-group"},
        {"key": "off-topic", "emoji": "🎲", "name": "off-topic", "slowmode": 3},
        {"key": "bot-commands", "emoji": "🤖", "name": "bot-commands"},
    ]},
    {"key": "beta", "name": "🧪 Beta Testing", "perms": BETA, "channels": [
        {"key": "beta-announcements", "emoji": "📢", "name": "beta-announcements", "perms": BETA_RO},
        {"key": "beta-downloads", "emoji": "📦", "name": "beta-downloads", "perms": BETA_RO,
         "topic": "Pre-release builds. Never share them outside this channel."},
        {"key": "beta-chat", "emoji": "💬", "name": "beta-chat"},
        {"key": "beta-bugs", "emoji": "🐞", "name": "beta-bugs", "type": "forum",
         "tags": ["Crash", "Visual", "Performance", "Regression", "Fixed"]},
        {"key": "beta-feedback", "emoji": "🗳️", "name": "beta-feedback"},
        {"key": "beta-voice", "emoji": "🔊", "name": "Beta Testing", "type": "voice"},
    ]},
    {"key": "voice", "name": "🔊 Voice Channels", "perms": MEMBER, "channels": [
        {"key": "lounge", "emoji": "🛋️", "name": "Lounge", "type": "voice"},
        {"key": "room-1", "emoji": "🎮", "name": "Room 1", "type": "voice", "user_limit": 2},
        {"key": "room-2", "emoji": "🎮", "name": "Room 2", "type": "voice", "user_limit": 3},
        {"key": "room-3", "emoji": "🎮", "name": "Room 3", "type": "voice", "user_limit": 4},
        {"key": "room-4", "emoji": "🎮", "name": "Room 4", "type": "voice", "user_limit": 5},
        {"key": "room-5", "emoji": "🎮", "name": "Room 5", "type": "voice", "user_limit": 6},
        {"key": "room-6", "emoji": "🎮", "name": "Room 6", "type": "voice", "user_limit": 10},
        {"key": "afk", "emoji": "💤", "name": "AFK", "type": "voice",
         "perms": {**MEMBER, "member": {"view_channel": True, "speak": False}}},
    ]},
    {"key": "tickets", "name": "🎫 Open Tickets", "perms": TICKETS, "channels": []},
    {"key": "staff", "name": "🛡️ Staff", "perms": STAFF_ONLY, "channels": [
        {"key": "staff-announcements", "emoji": "📢", "name": "staff-announcements", "perms": STAFF_RO},
        {"key": "staff-handbook", "emoji": "📕", "name": "staff-handbook", "perms": STAFF_RO},
        {"key": "staff-chat", "emoji": "💬", "name": "staff-chat"},
        {"key": "staff-commands", "emoji": "🤖", "name": "staff-commands",
         "topic": "Run moderation and bot commands here."},
        {"key": "reports", "emoji": "🚩", "name": "reports", "topic": "Discuss user reports and evidence."},
        {"key": "appeals", "emoji": "⚖️", "name": "appeals"},
        {"key": "staff-voice", "emoji": "🔊", "name": "Staff Lounge", "type": "voice"},
    ]},
    {"key": "dev", "name": "🔧 Development", "perms": DEV_ONLY, "channels": [
        {"key": "dev-chat", "emoji": "💻", "name": "dev-chat"},
        {"key": "github-feed", "emoji": "🐙", "name": "github-feed",
         "topic": "Commits and releases (an Admin adds the GitHub webhook)."},
        {"key": "build-status", "emoji": "🏗️", "name": "build-status"},
        {"key": "release-planning", "emoji": "🗓️", "name": "release-planning"},
        {"key": "dev-voice", "emoji": "🔊", "name": "Dev Voice", "type": "voice"},
    ]},
    {"key": "management", "name": "👑 Management", "perms": ADMIN_ONLY, "channels": [
        {"key": "admin-chat", "emoji": "👑", "name": "admin-chat"},
        {"key": "staff-applications", "emoji": "📋", "name": "staff-applications"},
        {"key": "management-voice", "emoji": "🔊", "name": "Management", "type": "voice"},
    ]},
    {"key": "logs", "name": "📜 Logs", "perms": LOG_ADMIN, "channels": [
        {"key": "security-alerts", "emoji": "🚨", "name": "security-alerts",
         "topic": "Raids, nukes, webhook and permission changes. Admins are pinged here."},
        {"key": "mod-log", "emoji": "🔨", "name": "mod-log", "perms": LOG_STAFF,
         "topic": "Bans, kicks, timeouts and AutoMod hits."},
        {"key": "message-log", "emoji": "✏️", "name": "message-log", "perms": LOG_STAFF},
        {"key": "member-log", "emoji": "👥", "name": "member-log", "perms": LOG_STAFF},
        {"key": "ticket-transcripts", "emoji": "🗂️", "name": "ticket-transcripts", "perms": LOG_STAFF},
        {"key": "role-log", "emoji": "🏷️", "name": "role-log"},
        {"key": "server-log", "emoji": "⚙️", "name": "server-log"},
    ]},
]

# Channels the bot uses (channel keys).
SYSTEM_CHANNEL = "member-log"       # Discord's own join/boost messages (kept out of public chat)
RULES_CHANNEL = "rules"             # Community servers only
UPDATES_CHANNEL = "security-alerts" # Community servers only: Discord's notices to staff
AFK_CHANNEL = "afk"
AUTOMOD_ALERT_CHANNEL = "mod-log"
LOCKDOWN_CATEGORIES = ["community", "support", "download", "voice"]  # muted by /lockdown

# ── Tickets ───────────────────────────────────────────────────────────────────
# Who can see each ticket type besides the opener (role keys).
TICKET_TYPES = [
    {"key": "install", "label": "Download / install help", "emoji": "⬇️", "staff": TICKET_STAFF},
    {"key": "support", "label": "Technical support", "emoji": "🛠️", "staff": TICKET_STAFF},
    {"key": "bug", "label": "Bug report", "emoji": "🐛", "staff": [*TICKET_STAFF, "developer"]},
    {"key": "account", "label": "Account / purchase", "emoji": "💳", "staff": ADMINS},
    {"key": "report", "label": "Report a user", "emoji": "🚩", "staff": TICKET_STAFF},
    {"key": "appeal", "label": "Appeal a punishment", "emoji": "⚖️", "staff": ADMINS},
    {"key": "other", "label": "Something else", "emoji": "💬", "staff": TICKET_STAFF},
]

# ── Bot text ──────────────────────────────────────────────────────────────────
# Lines in the welcome card and in /help (#key becomes a channel link).
WELCOME_LINES = [
    "⬇️ Grab the client in #download",
    "💬 Say hi in #general",
    "❓ Questions? #download-help or #create-ticket",
]
HELP_LINES = ["Download: #download · Install help: #install-guide · Rules: #rules"]
LEGAL_PAGES = True   # serve /terms and /privacy from worker/legal/
LEGAL_NOTE = "Terms & privacy: fish-claude-bot.pages.dev/terms"

# ── AutoMod ───────────────────────────────────────────────────────────────────
_n = CLIENT["name"].lower()
SCAM_KEYWORDS = [
    "*free nitro*", "*nitro for free*", "*discord.gift*", "*steamcommunity.ru*", "*grabify*",
    "*iplogger*", f"*cracked {_n}*", f"*{_n} crack*", "*free minecraft account*",
    "*alt generator*", "*free capes*", "*cape generator*", "*token grabber*",
]
# Blocks people whose username, nickname or bio pretends to be staff or advertises scams.
PROFILE_KEYWORDS = [
    "*evade staff*", "*evade support*", "*evade admin*", "*evade mod*", "*discord staff*",
    "*discord support*", "*free nitro*", "*nitro giveaway*", "*steam gift*", "*dm for nitro*",
]
INVITE_REGEX = [r"(discord\.gg|discord(app)?\.com/invite|dsc\.gg)/\S+"]

# ── Starter posts ─────────────────────────────────────────────────────────────
# One or more embeds per channel, posted once, pinned, and kept up to date with --update.
# {placeholders} are CLIENT keys. #key becomes a clickable link to that channel.
# Facts here come from evadeclient.com (download page, FAQ, changelog); update them on each release.

SITE = CLIENT["site"]
DL = SITE + "/downloads"


def _p(title, description, fields=()):
    return {"title": title, "description": description, "fields": list(fields)}


POSTS = {
    # ── Start Here ──
    "rules": [_p("Server Rules", (
        "**1. Be respectful.** No harassment, slurs, hate speech or personal attacks.\n\n"
        "**2. No cheating.** Evade is a quality-of-life client with no combat or movement cheats. Don't ask for, "
        "share or advertise hacked clients, cheat mods or anti-cheat bypasses, and follow the rules of every server you play on.\n\n"
        "**3. Official downloads only.** Only download Evade from **evadeclient.com** or #download. "
        "No re-uploads or \"cracked\" versions.\n\n"
        "**4. Staff will never DM you first** with a download, a link or a request for your Minecraft, Microsoft "
        "or Discord login. Anyone who does is a scammer: report them with a ticket.\n\n"
        "**5. No spam, advertising or invite links.**\n\n"
        "**6. Keep it SFW**, including names, avatars and bios.\n\n"
        "**7. Use the right channel.** Install questions in #download-help, problems in #support or a ticket, "
        "bugs in #bug-reports.\n\n"
        "**8. Don't leak beta builds.**\n\n"
        "**9. Follow Discord's Terms of Service and Community Guidelines.**\n\n"
        "Staff have the final say. Punishments range from a warning to a permanent ban.\n\n"
        "When you're ready, head to #verify."))],

    # ── Info & Announcements ──
    "welcome": [_p("👋 Welcome", (
        "Everyone who joins and verifies gets a welcome here.\n\n"
        "**New here?** Grab Evade in #download, follow #install-guide, and say hi in #general. "
        "Need help? #download-help or #create-ticket."))],
    "announcements": [
        _p("📢 Announcements", "New releases, big news and events land here. Only staff can post.\n\n"
           "What changed in each version goes in #changelog.")],
    "changelog": [
        _p("📝 Changelog", f"Every release, newest at the bottom. The full history is at {SITE}/changelog."),
        _p("v2.0.0 · 23 Nov 2026", (
            "• New look: rounded shapes, soft shadows, sharp Inter text and animations throughout the menu.\n"
            "• **FPS Mode:** Max FPS, Balanced or Quality, plus Unlock FPS and Render Distance Cap.\n"
            "• `.bench` benchmark, minimap (terrain only), about 1,050 settings, Evade icons in the tab list.\n"
            "• Servers can switch off any Evade mod for their players.")),
        _p("v2.0.1 · 23 Nov 2026", (
            "• The installer updates itself.\n"
            "• Evade Clips updates even while it's open.\n"
            "• Removed Nametags (not allowed on many servers).\n"
            "• Fullbright tells you when shaders are on.")),
        _p("v2.1.0 · 24 Nov 2026", (
            "• **Evade Launcher:** the installer is now a full launcher with Evade Clips built in: home screen, "
            "instances, mod browser, and Clips' library, recorder and editor. It updates itself.\n"
            "• **Mod Browser** in game and in the launcher: install Fabric mods from Modrinth in one click.\n"
            "• **Hypixel:** Quick Play menu, BedWars and SkyWars stats in the tab list, BedWars helper.\n"
            "• **New mods:** Snaplook, Text Hotkeys, Totem Counter, Day Counter, Menu Blur, Evade title screen, pack favourites, Discord status.\n"
            "• **Evade Server API:** servers can show waypoints, cooldowns, titles and more to Evade players, and switch mods off."))],
    "giveaways": [_p("🎁 Giveaways", (
        "Giveaways and events are posted here. Only staff can post.\n\n"
        "**How it works:** react or press the button on a giveaway post to enter. Winners are picked at random and "
        "announced here. Staff will contact winners in a ticket, never by DMing you first.\n\n"
        "Anyone DMing you about a prize you didn't enter is a scammer."))],
    "socials": [_p("📱 Evade Online", (
        f"**Website:** {SITE}\n"
        f"**Download:** {SITE}/download\n"
        f"**Docs:** {SITE}/docs\n"
        f"**Changelog:** {SITE}/changelog\n"
        f"**Evade Clips:** {SITE}/clips\n"
        f"**For server owners:** {SITE}/servers\n"
        "**Email:** privacy@evadeclient.com\n\n"
        "These are the only official links. More socials will be added here as they launch."))],
    "faq": [_p("Frequently Asked Questions", (
        "**Is it free?**\nYes, completely. No account or licence key.\n\n"
        "**Is it allowed on servers?**\nOn the great majority. Evade is quality of life only: HUD, zoom, freelook, "
        "crosshair, waypoints and visual tweaks. No combat or movement cheats, ESP or x-ray. Some servers (including "
        "Hypixel) forbid freelook, so Evade switches it off there automatically. Always check a server's own rules.\n\n"
        "**Which versions?**\nMinecraft Java {minecraft} with {loader}. The launcher sets up the right versions for you.\n\n"
        "**Bedrock or console?**\nNo, and it isn't possible: Bedrock is a closed platform.\n\n"
        "**Does it work with other mods?**\nUsually. Put extra Fabric mods in `.minecraft/evade/mods`, or use the Mod Browser.\n\n"
        "**Voice chat?**\nYes, through Simple Voice Chat, added by the launcher. Press **V**.\n\n"
        "**What data does Evade collect?**\nAlmost none: settings and clips stay on your PC. "
        f"Details: {SITE}/privacy\n\n"
        f"More answers at {SITE}/faq, or ask in #download-help."))],

    # ── Download ──
    "download": [_p("⬇️ Download Evade v{version}", (
        "**Evade Launcher (recommended)**\n"
        f"🪟 **Windows 10/11:** [Evade-Installer.exe]({DL}/Evade-Installer.exe) · 4.6 MB\n"
        f"🍎 **macOS** / 🐧 **Linux:** [EvadeInstaller.jar]({DL}/EvadeInstaller.jar) · 4.5 MB · needs Java 17+\n\n"
        "The launcher installs Evade, keeps it up to date and starts it through the official Minecraft Launcher, "
        "which signs you in. It never sees your password. Evade Clips is built in.\n\n"
        "**Just the mod (advanced)**\n"
        f"[evade-client-latest.jar]({DL}/evade-client-latest.jar) · 3.4 MB: put it in your mods folder with "
        "Fabric API for {minecraft}.\n\n"
        f"Download page: {SITE}/download · Install steps: #install-guide\n\n"
        "⚠️ Only download from evadeclient.com or this channel. If anyone sends you a file anywhere else, don't run it."),
        fields=[("Minecraft", "Java {minecraft}", True), ("Loader", "{loader}", True), ("Released", "24 Nov 2026", True),
                ("SHA-256 checksums", "Installer .exe `fe9450616e73…ba779`\nInstaller .jar `0a92b57eeae7…0f5`\n"
                 "Mod jar `a93040edef71…1b6`\nFull values on the download page.", False)])],
    "install-guide": [
        _p("📘 Installing Evade (2 minutes)", (
            "**1. Download the launcher** from #download: the **.exe** on Windows, the **.jar** on macOS and Linux "
            "(needs Java 17+).\n\n"
            "**2. Open it and click Install.** The first time, it sets up Fabric, Fabric API, Evade and, if you "
            "leave them on, the FPS mods and voice chat, all in their own folder. Your normal Minecraft isn't touched.\n\n"
            "**3. Press Play.** The Minecraft Launcher opens with **Evade Client** selected. Press its Play button "
            "(sign in once if it asks).\n\n"
            "**4. Join a world and press Right Shift** to open the Evade menu."),
            fields=[("Keys", "**Right Shift** menu · **C** zoom · **Left Alt** freelook · **V** voice chat · `.help` chat commands", False)]),
        _p("Updating, settings and uninstalling", (
            "**Update:** open the Evade Launcher; it updates itself and Evade. Your settings are kept.\n\n"
            "**Settings file:** `.minecraft/evade/config/evade/config.json`. Back it up to keep your layout and keybinds.\n\n"
            "**Extra mods:** `.minecraft/evade/mods`, or use the Mod Browser.\n\n"
            "**Uninstall:** delete the `evade` folder and `versions/evade-client-{minecraft}` inside `.minecraft`, "
            "then remove the Evade Client installation in the Minecraft Launcher.\n\n"
            "Stuck? See #known-issues, ask in #download-help, or open a ticket in #create-ticket."))],
    "known-issues": [_p("⚠️ Common Problems & Fixes", (
        "**\"Windows protected your PC\"**\nThe launcher isn't code-signed yet. Click **More info**, then **Run anyway**. "
        "It only writes to your `.minecraft` folder.\n\n"
        "**The Mac/Linux .jar won't open**\nIt needs Java 17+. Install it from adoptium.net, then double-click the jar "
        "or run `java -jar EvadeInstaller.jar`.\n\n"
        "**\"I have Java 17 but it still won't open\"**\nAn older Java (often Java 8) is set to open .jar files. Install "
        "the latest Java from adoptium.net, or right-click the jar → Open with your newer Java.\n\n"
        "**Evade Client isn't in the Minecraft Launcher**\nClose and reopen the Minecraft Launcher. If you picked a "
        "different Minecraft folder in the installer, make sure it's the one your launcher uses.\n\n"
        "**Freelook does nothing on Hypixel**\nThat's on purpose: Hypixel forbids freelook, so Evade switches it off there.\n\n"
        "**Low FPS on a laptop**\nPlug it in and set Windows power mode to Best performance. More tips in #fps.\n\n"
        "**Still broken?** Reinstalling with the latest launcher fixes most problems. Otherwise open a ticket in #create-ticket."))],
    "download-help": [_p("🙋 Download Help", (
        "Quick questions about downloading, installing or updating Evade go here.\n\n"
        "**Help us help you. Include:**\n"
        "• Windows, macOS or Linux\n"
        "• Which step you're stuck on (download, Install, Play, in game)\n"
        "• A screenshot of any error\n\n"
        "Check #install-guide and #known-issues first. For anything long or private, open a ticket in #create-ticket.\n\n"
        "Never share your Microsoft password or login codes, here or in DMs."))],

    # ── Support ──
    "support": [_p("🛠️ Support", (
        "Something not working in Evade? Post here and the community or staff will help.\n\n"
        "**One post per problem. Include:**\n"
        "• Your Evade version and OS\n"
        "• What you were doing and what went wrong\n"
        "• Your log: `.minecraft/evade/logs/latest.log` (or the crash report)\n\n"
        "Private problems (account, payments, reports) go in a ticket: #create-ticket."))],
    "bug-reports": [_p("🐛 Bug Reports", (
        "Found a bug? Report it here. One bug per post, and search first in case it's known.\n\n"
        "**Copy this:**\n"
        "```\nWhat happened:\nWhat I expected:\nSteps to reproduce:\nEvade version / OS:\nMods installed (besides Evade):\n```\n"
        "Attach your `latest.log` or crash report and a screenshot or clip if you can.\n\n"
        "Confirmed bugs go to the developers and are listed in #known-issues until fixed."))],
    "suggestions": [_p("💡 Suggestions", (
        "Ideas for new mods, HUD elements or features go here. One idea per post, and search first.\n\n"
        "**Make it a good one:**\n"
        "• What it does, in one sentence\n"
        "• Why it would help (when would you use it?)\n"
        "• It must be fair to use on servers: no combat or movement advantages\n\n"
        "React 👍 on ideas you like. Popular ones get looked at first."))],

    # ── Hangout ──
    "general": [_p("💬 General", (
        "The main chat. Talk about Evade, Minecraft, PvP, whatever.\n\n"
        "Keep it friendly and follow #rules. Help questions go in #download-help or #support so they don't get lost."))],
    "media": [_p("📸 Media", (
        "Show off screenshots, clips and montages.\n\n"
        "**Tip:** Evade Clips (built into the Evade Launcher) clips kills, totem pops and wins automatically, and its "
        "share links play right inside Discord. Press your clip key in game or type `.clips`.\n\n"
        "No NSFW, gore or reposting other people's work as your own."))],
    "hud-setups": [_p("🖥️ HUD Setups", (
        "Share your HUD layout, menu style and theme.\n\n"
        "**Share your setup as a code:** open the menu, go to Profiles and share your profile. Paste the code here with a screenshot so others "
        "can import it in one click.\n\n"
        "Edit your own HUD with **Edit HUD Layout** in the menu: drag to move, scroll to resize."))],
    "cosmetics": [_p("👕 Cosmetics: Coming Soon", (
        "**Evade cosmetics are on the way.** They're not out yet; keep an eye on #announcements for news.\n\n"
        "**Until then**, show off how your game looks: themes, accent colours, crosshairs, block outlines and resource packs.\n\n"
        "Every Evade player already gets the Evade icon in the tab list. Turn it off under **Evade Icons** if you'd rather not show it.\n\n"
        "⚠️ Anyone selling or giving away \"Evade cosmetics\" right now is a scammer. Report them in #create-ticket."))],
    "fps": [_p("⚡ FPS & Performance", (
        "Tips, benchmarks and help getting more frames.\n\n"
        "**Quick wins**\n"
        "• Keep the launcher's **FPS mods** on (Sodium, Lithium, FerriteCore and more).\n"
        "• Menu → Performance → **FPS Mode**: Max FPS, Balanced (default) or Quality. **Unlock FPS** removes the frame cap.\n"
        "• Type `.bench` to measure your average FPS and 1% low, then compare settings.\n"
        "• Shaders cost a lot of FPS; leave them off on weaker PCs.\n\n"
        "**Laptops**\n"
        "• Plug in, and set Windows → System → Power → **Best performance**.\n"
        "• Update your graphics driver. With built-in graphics, use Balanced or Max FPS.\n\n"
        "Post your `.bench` results with your specs so others can compare."))],
    "lfg": [_p("🤝 Looking for Group", (
        "Find people to play with.\n\n"
        "**Post like this:**\n"
        "```\nServer / mode:\nRegion:\nLooking for: (duo, team, practice partner)\nWhen:\n```\n"
        "Then jump into a voice Room together. Don't post server invite links outside this channel."))],
    "off-topic": [_p("🎲 Off-Topic", "Anything that isn't Minecraft. Keep it SFW and follow #rules.")],
    "bot-commands": [_p("🤖 Bot Commands", (
        "Use bots here so other channels stay clean.\n\n"
        "**Evade bot:** `/help` lists everything, `/ticket` opens a ticket. To report a message or person, long-press "
        "(or right-click) it → **Apps** → **Report**."))],

    # ── Beta ──
    "beta-announcements": [_p("Welcome, Beta Testers", (
        "New test builds are posted in #beta-downloads. Report crashes in #beta-bugs and everything else in #beta-feedback. "
        "Back up `.minecraft/evade/config` before testing.\n\n"
        "**Beta builds are confidential.** Leaking one loses you the role."))],
    "beta-downloads": [_p("📦 Beta Downloads", (
        "Pre-release builds are posted here by the developers. Each post says what to test.\n\n"
        "**Installing a beta:** replace the Evade jar in `.minecraft/evade/mods` with the beta jar. To go back, reinstall "
        "with the Evade Launcher.\n\n"
        "Don't share these files outside this channel."))],
    "beta-chat": [_p("💬 Beta Chat", "Talk about the current beta with other testers and the devs. Spoilers stay in the Beta channels.")],
    "beta-bugs": [_p("🐞 Beta Bugs", (
        "Crashes and bugs in beta builds. One bug per post.\n\n"
        "```\nBuild:\nWhat happened:\nSteps to reproduce:\nOS / GPU:\n```\n"
        "Attach `latest.log` or the crash report. Say if it also happens on the release version."))],
    "beta-feedback": [_p("🗳️ Beta Feedback", (
        "Not a bug, but not right? Tell us here: confusing settings, things that feel slow, wording, ideas for the build "
        "you're testing. Be specific, screenshots help."))],

    # ── Staff ──
    "staff-announcements": [_p("📢 Staff Announcements", "Notices for the whole team from Admins. Read everything posted here.")],
    "staff-handbook": [_p("Staff Handbook", (
        "**Security**\n"
        "• Two-factor authentication is required for all staff.\n"
        "• Never run files, scan QR codes or log in through links from anyone, even other staff.\n"
        "• If your account is compromised, tell an Admin immediately from another device.\n\n"
        "**Moderation**\n"
        "• Warn, then timeout, then kick. Bans are Admin-only: bring evidence to #reports.\n"
        "• Every action is logged in #mod-log, and the member gets a DM with your reason.\n"
        "• Handle reports and appeals through tickets, never DMs.\n\n"
        "**Tickets**\n"
        "• Press **Claim** before replying so two people don't answer at once.\n"
        "• Close it when solved; the transcript is saved to #ticket-transcripts.\n\n"
        "**Commands** (`/help` any time)\n"
        "Mods: `/warn` `/warnings` `/timeout` `/kick` `/userinfo` `/slowmode` `/purge` `/lockdown` `/unlock` "
        "`/raidmode` `/verify` `/ticket-add` `/ticket-remove`\n"
        "Admins: `/ban` `/unban` `/announce` `/panels`, and clearing warnings"))],
    "staff-chat": [_p("💬 Staff Chat", "Day-to-day talk for the team. Keep member details and evidence in #reports, not here.")],
    "staff-commands": [_p("🤖 Staff Commands", (
        "Run moderation commands here so they don't clutter public channels. Replies are private to you.\n\n"
        "`/userinfo` before acting · `/warnings` to see history · `/lockdown` + `/raidmode on` if a raid starts, then "
        "check #member-log and undo with `/unlock` and `/raidmode off`."))],
    "reports": [_p("🚩 Reports", (
        "Discuss member reports and evidence here.\n\n"
        "**For each case:** who, what happened, links to messages or screenshots, and what you recommend. "
        "An Admin decides on bans. Report tickets from members arrive in Open Tickets; summarise them here."))],
    "appeals": [_p("⚖️ Appeals", (
        "Discuss punishment appeals. Members appeal through an **Appeal** ticket.\n\n"
        "The staff member who gave the punishment shouldn't decide the appeal alone. Post the outcome and reason so the "
        "team stays consistent."))],

    # ── Development ──
    "dev-chat": [_p("💻 Dev Chat", "Development talk: features, code, design decisions. Releases are planned in #release-planning.")],
    "github-feed": [_p("🐙 GitHub Feed", (
        "Commits, releases and pull requests from the Evade repo.\n\n"
        "**Set up (Admin):** this channel → Edit Channel → Integrations → Webhooks → New Webhook → copy URL. In the "
        "GitHub repo: Settings → Webhooks → add the URL with `/github` on the end, content type `application/json`."))],
    "build-status": [_p("🏗️ Build Status", "Build and in-game test results. Post CI or `runClientGameTest` results here before a release.")],
    "release-planning": [_p("🗓️ Release Checklist", (
        "1. Bump `mod_version` in `gradle.properties`.\n"
        "2. Run the game tests (`xvfb-run ./gradlew runClientGameTest`).\n"
        "3. Run `scripts/build-site.sh` to build the launcher and jar and update `version.json`.\n"
        "4. Update the changelog on the site and in #changelog.\n"
        "5. Deploy the site, then post in #announcements with `/announce`.\n"
        "6. Update #download (run the Discord setup with `--update`)."))],

    # ── Management ──
    "admin-chat": [_p("👑 Admin Chat", "Private channel for Admins, Owner and Founder: staffing, bans, server settings and anything sensitive.")],
    "staff-applications": [_p("📋 Staff Applications", (
        "Review staff applications here.\n\n"
        "**Look for:** time in the server, a clean warning history (`/warnings`), helpfulness in #download-help and "
        "#support, and 2FA on their account. Note your decision and who approved it."))],

    # ── Logs (the bot and Discord post here) ──
    "security-alerts": [_p("🚨 Security Alerts", "Raid mode, lockdowns and anything urgent. Admins are pinged here. Check it whenever you're pinged.")],
    "mod-log": [_p("🔨 Mod Log", "Every warning, timeout, kick, ban, purge and AutoMod hit, with who did it and why.")],
    "message-log": [_p("✏️ Message Log", "Deleted and edited messages, when message logging is running.")],
    "member-log": [_p("👥 Member Log", "Joins, leaves and verifications, including new accounts that need a manual `/verify`.")],
    "ticket-transcripts": [_p("🗂️ Ticket Transcripts", "Every ticket's opening and its full transcript when closed.")],
    "role-log": [_p("🏷️ Role Log", "Role changes: who gave or removed which role.")],
    "server-log": [_p("⚙️ Server Log", "Channel, invite and server setting changes.")],
}
