"""Vendetta: a competitive Minecraft factions team.

Build:  SERVER=vendetta python3 setup_rest.py 1573514900813381702 [--update]

Security model
--------------
* @everyone sees only #rules and #verify. Verifying gives **Verified** (key "member"), which shows the
  public side: about, announcements, recruitment and the lobby. Accounts under 14 days can't self-verify,
  which stops throwaway spy alts.
* Faction channels need **Recruit** or higher. Base coordinates, wall checks and raid alerts need
  **Member** or higher, so trial recruits and allies never see where the base is.
* Only Leader has Administrator. Co-Leader gets specific permissions; Officers moderate.
* Nobody below Co-Leader can create invites.
"""

CLIENT = {
    "name": "Vendetta",
    "short": "Vendetta",
    "site": "",
    "download_url": "",
    "minecraft": "",
    "loader": "",
    "java": "",
    "menu_key": "",
    "version": "",
    "color": 0xB0141E,     # blood red
}
BOT_NAME = "Vendetta"
ICON_PATH = str(__import__("pathlib").Path(__file__).with_name("assets") / "vendetta" / "bot-avatar.png")

SECURITY = {
    "min_account_age_days": 14,
    "verify_fail_cooldown": 60,
    "raid_joins": 8,
    "raid_window": 30,
    "nuke_actions": 3,
    "nuke_window": 60,
    "max_open_tickets": 1,
    "ticket_cooldown": 600,
    "members_can_invite": False,
    "verification_level": "low",    # leadership approves everyone by hand, so no 10-minute wait
    "verify_mode": "approval",      # Verify sends a request to #verify-requests instead of a code check
}

_BASE = [
    "view_channel", "send_messages", "send_messages_in_threads", "create_public_threads",
    "read_message_history", "add_reactions", "embed_links", "attach_files",
    "use_external_emojis", "use_external_stickers", "use_application_commands",
    "connect", "speak", "stream", "use_voice_activation", "use_embedded_activities",
    "change_nickname", "request_to_speak",
]
_OFFICER = [
    "kick_members", "moderate_members", "manage_messages", "manage_threads", "manage_nicknames",
    "mute_members", "deafen_members", "move_members", "view_audit_log",
]
_COLEADER = _OFFICER + [
    "ban_members", "manage_channels", "manage_roles", "manage_guild", "manage_webhooks",
    "manage_expressions", "create_expressions", "manage_events", "create_events",
    "mention_everyone", "priority_speaker", "create_instant_invite",
]

ROLES = [
    {"key": "leader", "name": "Leader", "color": 0xB0141E, "hoist": True, "admin": True},
    {"key": "admin", "name": "Co-Leader", "color": 0xE0242F, "hoist": True, "perms": _COLEADER},
    {"key": "bot", "name": "Bot", "color": 0x95A5A6, "hoist": True,
     "perms": ["view_channel", "send_messages", "embed_links", "attach_files", "read_message_history", "add_reactions"]},
    {"key": "mod", "name": "Officer", "color": 0xFF5A36, "hoist": True, "perms": _OFFICER},
    {"key": "faction", "name": "Member", "color": 0xE8E8E8, "hoist": True},
    {"key": "recruit", "name": "Recruit", "color": 0x9E9E9E, "hoist": True},
    {"key": "ally", "name": "Ally", "color": 0x3BA55D, "hoist": True},
    {"key": "member", "name": "Verified", "color": 0x5C5C5C, "perms": _BASE},
]
EVERYONE_PERMS = ["read_message_history", "use_application_commands"]

ADMINS = ["admin"]               # Leader has Administrator anyway
STAFF = ["mod", *ADMINS]
TICKET_STAFF = ["mod", *ADMINS]


def _see(*roles, send=True):
    return {r: {"view_channel": True, **({} if send else {"send_messages": False})} for r in roles}


_HIDE = {"@everyone": {"view_channel": False}, "member": {"view_channel": False}}
GATE = {"@everyone": {"view_channel": True, "send_messages": False, "add_reactions": False,
                      "create_public_threads": False, "create_private_threads": False},
        "member": {"view_channel": False}}
GATE_RULES = {**GATE, "member": {"view_channel": True, "send_messages": False}}
# Verified guests (approved, not in the faction) see only #recruitment and #apply-and-contact.
GUEST_RO = {"@everyone": {"view_channel": False},
            "member": {"view_channel": True, "send_messages": False, "create_public_threads": False,
                       "send_messages_in_threads": False},
            **_see("mod", "admin")}
# Everything else needs the Member role (or Officer and up).
FACTION = {**_HIDE, **_see("faction", "mod", "admin")}
FACTION_RO = {**_HIDE, **_see("faction", send=False), **_see("mod", "admin")}
MEMBERS = {**_HIDE, **_see("faction", "mod", "admin")}
ALLIES = {**_HIDE, **_see("ally", "faction", "mod", "admin")}
OFFICERS = {**_HIDE, **_see("mod", "admin")}
LEADERS = {**_HIDE, **_see("admin")}
TICKETS = {**_HIDE, **_see("mod", "admin")}
LOG_LEAD = {**_HIDE, **_see("admin", send=False)}
LOG_OFF = {**_HIDE, **_see("mod", "admin", send=False)}

CATEGORIES = [
    {"key": "gate", "name": "✅ Start Here", "perms": GATE, "channels": [
        {"key": "welcome", "emoji": "👋", "name": "welcome", "perms": GATE_RULES,
         "topic": "Start here: who we are, how to get in, and where everything is."},
        {"key": "rules", "emoji": "📖", "name": "rules", "perms": GATE_RULES, "topic": "Read the rules, then verify."},
        {"key": "verify", "emoji": "✅", "name": "verify", "topic": "Press Verify to get in."},
    ]},
    {"key": "public", "name": "🩸 Vendetta", "perms": FACTION_RO, "channels": [
        {"key": "about", "emoji": "🩸", "name": "about-vendetta"},
        {"key": "announcements", "emoji": "📢", "name": "announcements", "type": "news"},
        {"key": "wins", "emoji": "🏆", "name": "wins-and-ftop", "topic": "Raids, F-Top placements and season results."},
        {"key": "recruitment", "emoji": "📝", "name": "recruitment", "perms": GUEST_RO, "topic": "Requirements and how to apply."},
        {"key": "create-ticket", "emoji": "🎫", "name": "apply-and-contact", "perms": GUEST_RO,
         "topic": "Apply to join, request an alliance, or contact leadership."},
        {"key": "lobby", "emoji": "💬", "name": "lobby", "perms": FACTION, "slowmode": 3,
         "topic": "Chat for Vendetta members."},
    ]},
    {"key": "faction", "name": "⚔️ Faction", "perms": FACTION, "channels": [
        {"key": "faction-rules", "emoji": "📜", "name": "faction-rules", "perms": FACTION_RO},
        {"key": "roster", "emoji": "👥", "name": "roster", "perms": FACTION_RO},
        {"key": "general", "emoji": "💬", "name": "general"},
        {"key": "raid-planning", "emoji": "🗺️", "name": "raid-planning"},
        {"key": "cannoning", "emoji": "💣", "name": "cannoning"},
        {"key": "grinding", "emoji": "💰", "name": "grinding-and-money"},
        {"key": "loot-log", "emoji": "📦", "name": "loot-log"},
        {"key": "clips", "emoji": "🎬", "name": "clips"},
        {"key": "schematics", "emoji": "📐", "name": "schematics"},
    ]},
    {"key": "base", "name": "🔒 Base", "perms": MEMBERS, "channels": [
        {"key": "base-coords", "emoji": "📍", "name": "base-coords", "topic": "Members only. Never screenshot or share outside."},
        {"key": "wall-checks", "emoji": "🧱", "name": "wall-checks"},
        {"key": "alerts", "emoji": "🚨", "name": "raid-alerts", "topic": "We're being raided / walls breached. Everyone online, get on."},
    ]},
    {"key": "allies", "name": "🤝 Allies", "perms": ALLIES, "channels": [
        {"key": "ally-chat", "emoji": "🤝", "name": "ally-chat"},
        {"key": "diplomacy", "emoji": "🕊️", "name": "diplomacy", "perms": OFFICERS,
         "topic": "Truces, allies, enemies and deals. Leadership only."},
        {"key": "ally-voice", "emoji": "🔊", "name": "Ally Comms", "type": "voice"},
    ]},
    {"key": "voice", "name": "🔊 Voice", "perms": FACTION, "channels": [
        {"key": "lobby-voice", "emoji": "🛋️", "name": "Lobby", "type": "voice"},
        {"key": "raid-1", "emoji": "⚔️", "name": "Raid 1", "type": "voice"},
        {"key": "raid-2", "emoji": "⚔️", "name": "Raid 2", "type": "voice"},
        {"key": "defense", "emoji": "🛡️", "name": "Defense", "type": "voice"},
        {"key": "grind", "emoji": "💰", "name": "Grind", "type": "voice"},
        {"key": "afk", "emoji": "💤", "name": "AFK", "type": "voice",
         "perms": {**FACTION, "faction": {"view_channel": True, "speak": False}}},
    ]},
    {"key": "tickets", "name": "🎫 Open Tickets", "perms": TICKETS, "channels": []},
    {"key": "leadership", "name": "👑 Leadership", "perms": OFFICERS, "channels": [
        {"key": "leaders-chat", "emoji": "👑", "name": "leaders-chat"},
        {"key": "applications-review", "emoji": "📋", "name": "applications-review"},
        {"key": "punishments", "emoji": "⚖️", "name": "punishments"},
        {"key": "co-leaders", "emoji": "🗝️", "name": "co-leaders", "perms": LEADERS},
        {"key": "verify-requests", "emoji": "🛂", "name": "verify-requests", "perms": LEADERS,
         "topic": "Approve or deny people who pressed Verify. Co-Leaders, the Leader and admins only."},
        {"key": "leaders-voice", "emoji": "🔊", "name": "Leadership", "type": "voice"},
    ]},
    {"key": "logs", "name": "📜 Logs", "perms": LOG_LEAD, "channels": [
        {"key": "security-alerts", "emoji": "🚨", "name": "security-alerts"},
        {"key": "mod-log", "emoji": "🔨", "name": "mod-log", "perms": LOG_OFF},
        {"key": "member-log", "emoji": "👥", "name": "member-log", "perms": LOG_OFF},
        {"key": "ticket-transcripts", "emoji": "🗂️", "name": "ticket-transcripts", "perms": LOG_OFF},
        {"key": "message-log", "emoji": "✏️", "name": "message-log", "perms": LOG_OFF},
        {"key": "role-log", "emoji": "🏷️", "name": "role-log"},
        {"key": "server-log", "emoji": "⚙️", "name": "server-log"},
    ]},
]

SYSTEM_CHANNEL = "member-log"
RULES_CHANNEL = "rules"
UPDATES_CHANNEL = "security-alerts"
AFK_CHANNEL = "afk"
AUTOMOD_ALERT_CHANNEL = "mod-log"
LOCKDOWN_CATEGORIES = ["public", "faction"]

TICKET_TYPES = [
    {"key": "apply", "label": "Apply to join Vendetta", "emoji": "📝", "staff": TICKET_STAFF, "questions": [
        {"id": "ign", "label": "Minecraft IGN", "max": 16},
        {"id": "experience", "label": "Factions experience (servers, F-Top, seasons)", "long": True, "max": 600},
        {"id": "skills", "label": "What do you bring? (PvP, cannoning, grinding…)", "long": True, "max": 400},
        {"id": "time", "label": "Timezone and hours you can play", "max": 100},
        {"id": "vouch", "label": "Who referred you / past factions", "required": False, "max": 200},
    ]},
    {"key": "ally", "label": "Alliance or truce request", "emoji": "🤝", "staff": ADMINS, "questions": [
        {"id": "faction", "label": "Your faction name", "max": 50},
        {"id": "leader", "label": "Your leader's IGN", "max": 16},
        {"id": "offer", "label": "What are you proposing?", "long": True, "max": 600},
    ]},
    {"key": "report", "label": "Report someone", "emoji": "🚩", "staff": TICKET_STAFF},
    {"key": "appeal", "label": "Appeal a punishment", "emoji": "⚖️", "staff": ADMINS},
    {"key": "other", "label": "Contact leadership", "emoji": "💬", "staff": TICKET_STAFF},
]

# Welcome cards for newly approved members go in the public lobby; #welcome stays a clean landing page.
WELCOME_CHANNEL = "lobby"
VERIFY_APPROVERS = ["admin"]   # Co-Leader; the Leader, owner and Administrator roles can always approve
BANNER = "https://cdn.discordapp.com/banners/1574756781174038608/edd24cdc063a4709910a8541fc14952b.png?size=1024"

WELCOME_LINES = [
    "📝 Want in? Read #recruitment and apply in #create-ticket",
    "Accepted applicants get **Member**, which unlocks the rest of the server.",
]
HELP_LINES = ["Rules: #rules · Recruitment: #recruitment · Apply: #create-ticket"]
LEGAL_PAGES = False
LEGAL_NOTE = ""

SCAM_KEYWORDS = [
    "*free nitro*", "*nitro for free*", "*discord.gift*", "*steamcommunity.ru*", "*grabify*",
    "*iplogger*", "*free minecraft account*", "*alt generator*", "*token grabber*",
]
PROFILE_KEYWORDS = ["*vendetta leader*", "*vendetta staff*", "*discord staff*", "*free nitro*"]
INVITE_REGEX = [r"(discord\.gg|discord(app)?\.com/invite|dsc\.gg)/\S+"]


def _p(title, description, fields=()):
    return {"title": title, "description": description, "fields": list(fields)}


POSTS = {
    "rules": [_p("Vendetta Rules", (
        "**1. Respect the team.** Trash talk enemies all you want in game, not each other.\n\n"
        "**2. What's said in Vendetta stays in Vendetta.** Leaking coords, plans, screenshots or chat to anyone "
        "outside is an instant ban and kick from the faction.\n\n"
        "**3. No alts in this Discord** unless leadership approves them.\n\n"
        "**4. No scamming or stealing** from the faction or allies. Everything taken from the faction's chests is logged.\n\n"
        "**5. Follow the factions server's own rules.** Getting banned there doesn't help anyone.\n\n"
        "**6. Leadership has the final say.** Disagree in #create-ticket, not in public.\n\n"
        "**7. Follow Discord's Terms of Service.**\n\n"
        "Read them? Head to #verify."))],
    "welcome": [
        {**_p("Welcome to Vendetta", (
            "**Vendetta** is a competitive Minecraft factions team. We raid, we defend, and we play for F-Top.\n\n"
            "Whether you're here to join, to talk alliances, or just to hang out, start below.")), "image": BANNER},
        _p("🚪 How to get in", (
            "**1.** Read #rules.\n"
            "**2.** Press **Verify** in #verify.\n"
            "**3.** Leadership reviews your request by hand. You'll get a **DM** the moment you're approved, "
            "and #recruitment and #create-ticket open up for you.\n"
            "**4.** Read #recruitment and apply in #create-ticket. If you're accepted you get the **Member** role, "
            "which unlocks the rest of the server.\n\n"
            "Requests are checked by real people, so give it a little time. Pressing Verify again won't speed it up.")),
        _p("🗺️ Where everything is", (
            "**Everyone**\n"
            "👋 #welcome · 📖 #rules · ✅ #verify\n\n"
            "**Once approved**\n"
            "📝 #recruitment · 🎫 #create-ticket\n\n"
            "**Members**\n"
            "🩸 About, announcements, wins and lobby\n"
            "⚔️ Faction chat, raid planning, cannoning, grinding, loot log, clips and schematics\n"
            "🔒 Base coords, wall checks and raid alerts\n"
            "🔊 All voice channels\n\n"
            "**Allies**\n"
            "🤝 Ally chat and Ally Comms voice")),
        _p("🎖️ Ranks", (
            "**Leader**: runs Vendetta\n"
            "**Co-Leader**: leadership, approves new people and alliances\n"
            "**Officer**: runs raids and defence, reviews applications, moderates\n"
            "**Member**: in the faction; unlocks the whole server\n"
            "**Recruit**: a tag for Members who are still on trial\n"
            "**Ally**: from an allied faction; sees the Allies channels only\n"
            "**Verified**: approved by leadership; can read recruitment and apply")),
    ],
    "about": [_p("🩸 About Vendetta", (
        "**Vendetta** is a competitive factions team. We play to win: raids, F-Top and season placements.\n\n"
        "**What we do**\n"
        "• Raiding and counter-raiding\n"
        "• Cannoning and base design\n"
        "• Grinding for F-Top\n"
        "• PvP and defence\n\n"
        "Want in? Read #recruitment. Leadership can be reached through #create-ticket."))],
    "announcements": [_p("📢 Announcements", "Faction news: seasons, raids, recruitment and results. Leadership only.")],
    "wins": [_p("🏆 Wins & F-Top", "Raids we've won, F-Top placements and season results. Post proof (screenshots or clips).")],
    "recruitment": [_p("📝 Recruitment", (
        "**How to join**\n"
        "1. Make sure you meet the requirements below.\n"
        "2. Go to #create-ticket and pick **Apply to join Vendetta**.\n"
        "3. Answer the questions honestly. Leadership reviews every application.\n"
        "4. If accepted, you get the **Member** role (plus **Recruit** while you're on trial), which unlocks the server.\n\n"
        "**Requirements**\n"
        "• Active: able to play regularly during the season\n"
        "• Has a mic for raids and defence\n"
        "• Useful at something: PvP, cannoning, grinding, building or base design\n"
        "• Loyal: no leaking, no faction hopping mid-season\n\n"
        "Don't DM leaders to ask about your application; your ticket gets the answer."))],
    "create-ticket": [_p("🎫 Apply & Contact", (
        "Use the menu below:\n"
        "📝 **Apply to join Vendetta**: application form\n"
        "🤝 **Alliance or truce request**: for other factions' leaders\n"
        "🚩 **Report someone** · ⚖️ **Appeal** · 💬 **Contact leadership**\n\n"
        "Each opens a private channel that only you and leadership can see."))],
    "lobby": [_p("💬 Lobby", (
        "Public chat for guests, applicants and allies. New people get their welcome here once they're approved.\n\n"
        "No faction business here: no coords, no plans."))],
    "faction-rules": [_p("📜 Faction Rules", (
        "**Activity**\n• Check in daily during the season. Tell an Officer if you'll be away.\n\n"
        "**Base & security**\n• Never share coords, screenshots with F3, or plans outside #base-coords.\n"
        "• Don't bring anyone to base without an Officer's OK.\n\n"
        "**Loot & money**\n• Log what you take or deposit in #loot-log.\n• Payouts are set by leadership.\n\n"
        "**Raids & defence**\n• When #alerts pings, everyone online gets on and joins **Defense**.\n"
        "• Follow the raid lead's calls in voice.\n\n"
        "**Promotion**\n• Recruit → Member after you've proven yourself. Leadership decides."))],
    "roster": [_p("👥 Roster", (
        "Leadership keeps the roster here.\n\n"
        "```\nIGN | Rank | Role (PvP/cannon/grind/build) | Timezone\n```"))],
    "general": [_p("💬 General", "Faction chat. Recruits and up only. Keep coords out of here; use #base-coords.")],
    "raid-planning": [_p("🗺️ Raid Planning", (
        "Plan raids here.\n\n```\nTarget faction:\nTheir base (rough location, no exact coords here):\nWhen:\nCannons / gear needed:\nRaid lead:\n```"))],
    "cannoning": [_p("💣 Cannoning", "Cannon designs, testing results and schematics talk. Upload schematic files in #schematics.")],
    "grinding": [_p("💰 Grinding & Money", "Spawner pushes, money goals, F-Top value and who's grinding what.")],
    "loot-log": [_p("📦 Loot Log", "Log everything taken from or put into faction storage.\n\n```\nIGN:\nTook / deposited:\nItems:\nWhy:\n```")],
    "clips": [_p("🎬 Clips", "Raids, fights and wins. Blur coords and F3 before posting outside Vendetta.")],
    "schematics": [_p("📐 Schematics", "Cannon, base and wall schematics. Don't share these files outside the faction.")],
    "base-coords": [_p("📍 Base Coords", (
        "**Members only.** Base and stash coordinates live here and nowhere else.\n\n"
        "Never screenshot this channel, never share coords in DMs, and never bring anyone to base without an Officer's OK."))],
    "wall-checks": [_p("🧱 Wall Checks", (
        "Check walls regularly during the season and post here.\n\n"
        "**Clear:** `✅ walls clear` · **Problem:** `⚠️ <where> <what>` and ping in #alerts if it's a breach."))],
    "alerts": [_p("🚨 Raid Alerts", (
        "**Only for real emergencies:** we're being raided, walls are breached, or an enemy is at base.\n\n"
        "When this channel pings, everyone online gets on and joins **Defense** voice. False alarms waste everyone's time."))],
    "ally-chat": [_p("🤝 Ally Chat", "Talk with our allies. Don't share our coords or plans here; allies can change sides.")],
    "diplomacy": [_p("🕊️ Diplomacy", "Track allies, truces and enemies.\n\n```\nFaction | Status (ally/truce/enemy) | Contact | Notes\n```")],
    "leaders-chat": [_p("👑 Leaders Chat", "Officers and up. Planning, promotions and problems.")],
    "applications-review": [_p("📋 Applications Review", (
        "Applications arrive as tickets in **Open Tickets**. Discuss them here.\n\n"
        "**Before accepting:** check their IGN's history on the server, ask past factions, and vote. "
        "Accepted players get **Member** (and **Recruit** while on trial)."))],
    "punishments": [_p("⚖️ Punishments", "Record every punishment.\n\n```\nWho:\nWhat they did:\nPunishment:\nBy:\n```")],
    "co-leaders": [_p("🗝️ Co-Leaders", "Co-Leaders and the Leader only.")],
    "security-alerts": [_p("🚨 Security Alerts", "Raid mode, lockdowns and anything urgent.")],
    "mod-log": [_p("🔨 Mod Log", "Every warning, timeout, kick, ban and AutoMod hit.")],
    "member-log": [_p("👥 Member Log", "Joins, leaves and verifications, including new accounts that need a manual `/verify`.")],
    "ticket-transcripts": [_p("🗂️ Ticket Transcripts", "Every ticket and application, with its full transcript when closed.")],
    "message-log": [_p("✏️ Message Log", "Deleted and edited messages, when message logging is running.")],
    "role-log": [_p("🏷️ Role Log", "Who promoted or demoted whom.")],
    "server-log": [_p("⚙️ Server Log", "Channel, invite and server setting changes.")],
}
