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
    "name": "? Client",                   # shown in posts and the server name (--name)
    "short": "Client",                    # one word, used for AutoMod rule names
    "site": "",                           # website, e.g. "https://example.com" ("" = none yet)
    "download_url": "",                   # official download page ("" = none yet)
    "minecraft": "1.21.11",
    "loader": "Fabric",
    "java": "21",
    "menu_key": "Right Shift",
    "version": "1.0.0",                   # bump and run setup_server.py --update after a release
    "color": 0x5865F2,                    # brand colour for embeds
}
ICON_PATH = ""   # path to a PNG/JPG for the server icon, or leave empty

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
        {"key": "cosmetics", "emoji": "👕", "name": "cosmetics", "topic": "Show off your capes and cosmetics."},
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

# ── AutoMod ───────────────────────────────────────────────────────────────────
_n = CLIENT["name"].lower()
SCAM_KEYWORDS = [
    "*free nitro*", "*nitro for free*", "*discord.gift*", "*steamcommunity.ru*", "*grabify*",
    "*iplogger*", f"*cracked {_n}*", f"*{_n} crack*", "*free minecraft account*",
    "*alt generator*", "*free capes*", "*cape generator*", "*token grabber*",
]
INVITE_REGEX = [r"(discord\.gg|discord(app)?\.com/invite|dsc\.gg)/\S+"]

# ── Starter posts ─────────────────────────────────────────────────────────────
# {placeholders} are CLIENT keys. #key becomes a clickable channel link.
POSTS = {
    "rules": [{
        "title": "Server Rules",
        "description": (
            "**1. Be respectful.** No harassment, slurs, hate speech or personal attacks.\n\n"
            "**2. No cheating.** {name} is a legit client. Don't ask for, share or advertise "
            "hacked clients, cheat mods or anti-cheat bypasses, and follow the rules of every server you play on.\n\n"
            "**3. Official downloads only.** Only download {name} from #download. "
            "No re-uploads or \"cracked\" versions.\n\n"
            "**4. Staff will never DM you first** with a download, a link or a request for your "
            "Minecraft, Microsoft or Discord login. Anyone who does is a scammer: report them in a ticket.\n\n"
            "**5. No spam, advertising or invite links.**\n\n"
            "**6. Keep it SFW**, including names, avatars and bios.\n\n"
            "**7. Use the right channel.** Questions in #support or a ticket, bugs in #bug-reports.\n\n"
            "**8. Don't leak beta builds.**\n\n"
            "**9. Follow Discord's Terms of Service and Community Guidelines.**\n\n"
            "Staff have the final say. Punishments range from a warning to a permanent ban.\n\n"
            "When you're ready, head to #verify."
        ),
    }],
    "faq": [{
        "title": "Frequently Asked Questions",
        "description": (
            "**Is {name} allowed on servers?**\nIt's built to be a legit client with no unfair "
            "advantages, but every server sets its own rules about mods. Check them first.\n\n"
            "**Is it free?**\nEdit this answer in `layout.py`.\n\n"
            "**Which versions are supported?**\nMinecraft {minecraft} on {loader}.\n\n"
            "**I need help.**\nCheck #install-guide, ask in #download-help, or open a ticket in #create-ticket."
        ),
    }],
    "download": [{
        "title": "Download {name} {version}",
        "description": (
            "{download_line}\n\n"
            "This channel is the only official source. If anyone sends you a file anywhere else, don't run it."
        ),
        "fields": [("Version", "{version}", True), ("Minecraft", "{minecraft}", True),
                   ("Loader", "{loader}", True)],
    }],
    "install-guide": [{
        "title": "Installing {name}",
        "description": (
            "**Requirements:** Minecraft Java Edition {minecraft}, Java {java}.\n\n"
            "1. Download {name} from #download.\n"
            "2. Run the installer (or put the jar in `.minecraft/mods` with {loader}).\n"
            "3. Open the Minecraft Launcher, pick the **{name}** profile and press Play.\n"
            "4. In game, press **{menu_key}** to open the menu.\n\n"
            "**Crashes on launch?** Check you're on {minecraft} and remove other clients' mods.\n"
            "**Low FPS?** See #fps, and make sure the game uses your dedicated GPU.\n\n"
            "Still stuck? Ask in #download-help or open a ticket in #create-ticket."
        ),
    }],
    "known-issues": [{
        "title": "Known Issues",
        "description": "Nothing reported yet. Staff will list confirmed problems and workarounds here.",
    }],
    "beta-announcements": [{
        "title": "Welcome, Beta Testers",
        "description": (
            "Builds go in #beta-downloads. Report crashes in #beta-bugs and everything else in "
            "#beta-feedback. Back up your settings before testing.\n\n"
            "**Beta builds are confidential.** Leaking one loses you the role."
        ),
    }],
    "staff-handbook": [{
        "title": "Staff Handbook",
        "description": (
            "**Security**\n"
            "• Two-factor authentication is required for all staff.\n"
            "• Never run files, scan QR codes or log in through links from anyone, even other staff.\n"
            "• If your account is compromised, tell an Admin immediately from another device.\n\n"
            "**Moderation**\n"
            "• Warn, then timeout, then kick. Bans are Admin-only: request one in #reports with evidence.\n"
            "• Every action you take is logged in #mod-log.\n"
            "• Take reports and appeals through tickets, not DMs.\n\n"
            "**Tickets**\n"
            "• Press **Claim** before replying so two people don't answer at once.\n"
            "• Close the ticket when it's solved; the transcript is saved to #ticket-transcripts.\n\n"
            "**Commands** (in #staff-commands)\n"
            "`/verify` `/lockdown` `/unlock` `/raidmode` `/purge` `/ticket-add` `/ticket-remove`"
        ),
    }],
}

# Filled into posts as {download_line}.
CLIENT["download_line"] = (
    f"Get the latest version from **[{CLIENT['download_url']}]({CLIENT['download_url']})**."
    if CLIENT["download_url"] else
    f"{CLIENT['name']} isn't released yet. The first download will be posted here, "
    "and announced in #announcements."
)
