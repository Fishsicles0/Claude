#!/usr/bin/env python3
"""Build your Minecraft client's Discord server from layout.py.

    export DISCORD_TOKEN=...            # your bot's token (never commit it)
    python setup_server.py GUILD_ID     # create whatever is missing
    python setup_server.py GUILD_ID --update     # also sync permissions, topics and posts
    python setup_server.py --dry-run    # print the plan without connecting
    python setup_server.py --invite APPLICATION_ID   # print the bot's invite link

Everything is matched by name, so it is safe to run again. Nothing is ever deleted.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

import discord

import layout

HERE = Path(__file__).resolve().parent
REASON = f"{layout.CLIENT['name']} server setup"
MARKER = "server-setup:"  # embed footer that marks posts this script made


# ── Helpers ───────────────────────────────────────────────────────────────────

def placeholders() -> dict:
    """Values for {placeholders} in the starter posts."""
    return dict(layout.CLIENT)


def text_name(name: str) -> str:
    """How Discord stores a text channel name."""
    return re.sub(r"\s+", "-", name.strip().lower())


def all_channels():
    for cat in layout.CATEGORIES:
        for ch in cat["channels"]:
            yield cat, ch


def check_layout() -> list[str]:
    """Catch typos in layout.py before touching the server."""
    roles = {r["name"] for r in layout.ROLES} | {"@everyone"}
    errors, seen = [], set()
    def valid(p: str) -> bool:
        try:
            discord.PermissionOverwrite(**{p: True})
            return True
        except (ValueError, TypeError):
            return False
    for r in layout.ROLES:
        for p in r.get("perms", []):
            if not valid(p):
                errors.append(f"role {r['name']}: unknown permission {p!r}")
    for cat, ch in all_channels():
        key = ch["name"] if ch.get("type") == "voice" else text_name(ch["name"])
        if key in seen:
            errors.append(f"duplicate channel name {key!r}")
        seen.add(key)
        for owner in (cat, ch):
            for role, perms in owner.get("perms", {}).items():
                if role not in roles:
                    errors.append(f"{owner['name']}: unknown role {role!r}")
                for p in perms:
                    if not valid(p):
                        errors.append(f"{owner['name']}: unknown permission {p!r}")
    for key in layout.POSTS:
        if key not in seen:
            errors.append(f"POSTS has {key!r} but there is no such channel")
    return errors


def dry_run() -> None:
    print("Roles (highest first):")
    for r in layout.ROLES:
        print(f"  @{r['name']}" + ("  [admin]" if r.get("admin") else ""))
    for cat in layout.CATEGORIES:
        print(f"\n{cat['name']}")
        for ch in cat["channels"]:
            kind = ch.get("type", "text")
            name = ch["name"] if kind == "voice" else "#" + text_name(ch["name"])
            extra = " (private)" if ch.get("perms", cat.get("perms", {})).get("@everyone", {}).get("view_channel") is False else ""
            post = " + starter post" if ch["name"] in layout.POSTS else ""
            print(f"  {kind:6} {name}{extra}{post}")
    print("\nAutoMod: mention spam (5+), scam keywords; alerts to #" + layout.AUTOMOD_ALERT_CHANNEL)


# ── Setup ─────────────────────────────────────────────────────────────────────

class Setup:
    def __init__(self, guild: discord.Guild, update: bool, icon: Path | None, rename: bool = False):
        self.g = guild
        self.update = update
        self.icon = icon
        self.rename = rename
        self.community = "COMMUNITY" in guild.features
        self.roles: dict[str, discord.Role] = {}
        self.channels: dict[str, discord.abc.GuildChannel] = {}
        self.made = 0

    def log(self, msg: str) -> None:
        print(msg, flush=True)

    async def run(self) -> None:
        me = self.g.me
        if not me.guild_permissions.administrator:
            sys.exit("The bot needs the Administrator permission for setup. Re-invite it with --invite.")
        self.log(f"Setting up {self.g.name} ({'Community' if self.community else 'not Community'} server)")
        await self.make_roles()
        await self.make_channels()
        await self.server_settings()
        await self.automod()
        await self.posts()
        self.log(f"\nDone. {self.made} things created or updated.")
        if not self.community:
            self.log("Tip: enable Community (Server Settings > Enable Community), then run again with "
                     "--update to turn #announcements/#changelog into announcement channels and "
                     "#support/#bug-reports/#suggestions into forums.")

    # Roles
    async def make_roles(self) -> None:
        existing = {r.name: r for r in self.g.roles}
        for spec in layout.ROLES:
            perms = discord.Permissions.none()
            if spec.get("admin"):
                perms = discord.Permissions(administrator=True)
            for p in spec.get("perms", []):
                setattr(perms, p, True)
            kwargs = dict(permissions=perms, colour=discord.Colour(spec.get("color", 0)),
                          hoist=spec.get("hoist", False), mentionable=spec.get("mentionable", False))
            role = existing.get(spec["name"])
            if role is None:
                role = await self.g.create_role(name=spec["name"], reason=REASON, **kwargs)
                self.log(f"+ role @{role.name}")
                self.made += 1
            elif self.update and not role.managed and role < self.g.me.top_role:
                await role.edit(reason=REASON, **kwargs)
                self.log(f"~ role @{role.name}")
                self.made += 1
            self.roles[spec["name"]] = role
        self.roles["@everyone"] = self.g.default_role

        # Order them under the bot's own role, highest first.
        top = self.g.me.top_role.position
        wanted = [self.roles[s["name"]] for s in layout.ROLES]
        movable = [r for r in wanted if r < self.g.me.top_role]
        if len(movable) < len(wanted):
            self.log("! some roles sit above the bot's role; drag the bot's role to the top to fix order")
        positions = {r: top - 1 - i for i, r in enumerate(movable) if top - 1 - i > 0}
        if any(r.position != p for r, p in positions.items()):
            await self.g.edit_role_positions(positions, reason=REASON)
            self.log("~ role order")

    def overwrites(self, spec: dict) -> dict:
        out = {}
        for role, perms in spec.items():
            out[self.roles[role]] = discord.PermissionOverwrite(**perms)
        out[self.g.me] = discord.PermissionOverwrite(view_channel=True, send_messages=True,
                                                     embed_links=True, manage_channels=True)
        return out

    # Channels
    async def make_channels(self) -> None:
        for pos, cat_spec in enumerate(layout.CATEGORIES):
            cat_ow = self.overwrites(cat_spec.get("perms", {}))
            cat = discord.utils.get(self.g.categories, name=cat_spec["name"])
            if cat is None:
                cat = await self.g.create_category(cat_spec["name"], overwrites=cat_ow,
                                                   position=pos, reason=REASON)
                self.log(f"+ category {cat.name}")
                self.made += 1
            elif self.update:
                await cat.edit(overwrites=cat_ow, position=pos, reason=REASON)

            for ch_pos, spec in enumerate(cat_spec["channels"]):
                await self.make_channel(cat, cat_spec, spec, ch_pos)

    async def make_channel(self, cat, cat_spec, spec, position) -> None:
        kind = spec.get("type", "text")
        if kind in ("news", "forum") and not self.community:
            kind = "text"
        perms = spec.get("perms", cat_spec.get("perms", {}))
        ow = self.overwrites(perms)
        name = spec["name"] if kind == "voice" else text_name(spec["name"])
        topic = spec.get("topic", "")

        voice = kind == "voice"
        ch = next((c for c in self.g.channels if c.name == name
                   and isinstance(c, discord.VoiceChannel) == voice
                   and not isinstance(c, discord.CategoryChannel)), None)
        # Upgrade a plain text channel to news/forum once the server has Community.
        wants_forum = kind == "forum" and ch is not None and not isinstance(ch, discord.ForumChannel)
        if ch is not None and kind == "news" and isinstance(ch, discord.TextChannel) and not ch.is_news():
            if self.update:
                await ch.edit(type=discord.ChannelType.news)
                self.log(f"~ #{name} is now an announcement channel")
        if wants_forum:
            self.log(f"! #{name} exists as a text channel; rename or delete it and run again to get a forum")

        common = dict(category=cat, overwrites=ow, position=position, reason=REASON)
        if ch is None:
            if kind == "voice":
                ch = await self.g.create_voice_channel(name, user_limit=spec.get("user_limit", 0), **common)
            elif kind == "forum":
                tags = [discord.ForumTag(name=t) for t in spec.get("tags", [])]
                ch = await self.g.create_forum(name, topic=topic, available_tags=tags,
                                               slowmode_delay=spec.get("slowmode", 0), **common)
            elif kind == "news":
                ch = await self.g.create_text_channel(name, topic=topic, news=True, **common)
            else:
                ch = await self.g.create_text_channel(name, topic=topic,
                                                      slowmode_delay=spec.get("slowmode", 0), **common)
            self.log(f"+ {kind} {name}")
            self.made += 1
        elif self.update:
            edit = dict(category=cat, overwrites=ow, position=position, reason=REASON)
            if isinstance(ch, discord.VoiceChannel):
                edit["user_limit"] = spec.get("user_limit", 0)
            elif isinstance(ch, discord.ForumChannel):
                have = {t.name for t in ch.available_tags}
                tags = list(ch.available_tags) + [discord.ForumTag(name=t) for t in spec.get("tags", []) if t not in have]
                edit.update(topic=topic, available_tags=tags[:20], slowmode_delay=spec.get("slowmode", 0))
            else:
                edit.update(topic=topic, slowmode_delay=spec.get("slowmode", 0))
            await ch.edit(**edit)
            self.log(f"~ {name}")
            self.made += 1
        self.channels[spec["name"]] = ch

    # Server-wide settings
    async def server_settings(self) -> None:
        edit = dict(
            verification_level=discord.VerificationLevel.medium,
            explicit_content_filter=discord.ContentFilter.all_members,
            default_notifications=discord.NotificationLevel.only_mentions,
            system_channel=self.channels.get(layout.SYSTEM_CHANNEL),
        )
        if self.community:
            edit["rules_channel"] = self.channels.get(layout.RULES_CHANNEL)
            edit["public_updates_channel"] = self.channels.get(layout.UPDATES_CHANNEL)
        if self.rename:
            edit["name"] = layout.CLIENT["name"]
        if self.icon:
            edit["icon"] = self.icon.read_bytes()
        await self.g.edit(reason=REASON, **{k: v for k, v in edit.items() if v is not None})
        self.log("~ server settings (verification, content filter, notifications"
                 + (", icon" if "icon" in edit else "") + ")")

    async def automod(self) -> None:
        alert = self.channels.get(layout.AUTOMOD_ALERT_CHANNEL)
        exempt = [self.roles[r] for r in layout.STAFF]
        rules = [
            (f"{layout.CLIENT['short']}: mention spam", discord.AutoModTrigger(
                type=discord.AutoModRuleTriggerType.mention_spam, mention_limit=5)),
            (f"{layout.CLIENT['short']}: scam links", discord.AutoModTrigger(
                type=discord.AutoModRuleTriggerType.keyword, keyword_filter=layout.SCAM_KEYWORDS)),
        ]
        try:
            existing = {r.name: r for r in await self.g.fetch_automod_rules()}
        except discord.HTTPException as e:
            self.log(f"! skipped AutoMod: {e}")
            return
        for name, trigger in rules:
            actions = [discord.AutoModRuleAction(custom_message="Blocked by AutoMod. Ask a moderator if this was a mistake.")]
            if alert:
                actions.append(discord.AutoModRuleAction(channel_id=alert.id))
            try:
                if name not in existing:
                    await self.g.create_automod_rule(
                        name=name, event_type=discord.AutoModRuleEventType.message_send,
                        trigger=trigger, actions=actions, enabled=True, exempt_roles=exempt,
                        reason=REASON)
                    self.log(f"+ automod {name}")
                    self.made += 1
                elif self.update:
                    await existing[name].edit(trigger=trigger, actions=actions, exempt_roles=exempt,
                                              reason=REASON)
                    self.log(f"~ automod {name}")
            except discord.HTTPException as e:
                self.log(f"! AutoMod rule {name!r} failed: {e}")

    # Starter posts
    def mentions(self, text: str) -> str:
        """Turn #channel-name into a clickable channel mention."""
        def sub(m):
            ch = self.channels.get(m.group(1))
            return ch.mention if ch else m.group(0)
        return re.sub(r"#([a-z0-9-]+)", sub, text)

    def embed(self, key: str, i: int, spec: dict, v: dict) -> discord.Embed:
        fmt = lambda s: self.mentions(s.format(**v))
        e = discord.Embed(title=fmt(spec["title"]), description=fmt(spec["description"]),
                          colour=layout.BRAND)
        for name, value, inline in spec.get("fields", []):
            e.add_field(name=fmt(name), value=fmt(value), inline=inline)
        e.set_footer(text=f"{layout.CLIENT['name']} · {MARKER}{key}:{i}")
        return e

    async def posts(self) -> None:
        v = placeholders()
        for key, specs in layout.POSTS.items():
            ch = self.channels.get(key)
            if not isinstance(ch, discord.TextChannel):
                continue
            mine = {}
            async for msg in ch.history(limit=50):
                if msg.author == self.g.me and msg.embeds and msg.embeds[0].footer.text:
                    foot = msg.embeds[0].footer.text
                    if MARKER in foot:
                        mine[foot.split(MARKER, 1)[1]] = msg
            for i, spec in enumerate(specs):
                e = self.embed(key, i, spec, v)
                old = mine.get(f"{key}:{i}")
                if old is None:
                    await ch.send(embed=e)
                    self.log(f"+ post in #{key}")
                    self.made += 1
                elif self.update:
                    await old.edit(embed=e)
                    self.log(f"~ post in #{key}")
                    self.made += 1


# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("guild_id", nargs="?", type=int, help="your server's ID (right-click the server > Copy Server ID)")
    ap.add_argument("--update", action="store_true", help="also sync existing roles, channels and posts")
    ap.add_argument("--icon", metavar="PNG", default=layout.ICON_PATH or None,
                    help="image for the server icon (default: ICON_PATH in layout.py)")
    ap.add_argument("--name", action="store_true", help="also rename the server to the client's name")
    ap.add_argument("--dry-run", action="store_true", help="print the plan and exit")
    ap.add_argument("--invite", metavar="APPLICATION_ID", help="print the bot invite link and exit")
    args = ap.parse_args()

    errors = check_layout()
    if errors:
        sys.exit("layout.py has problems:\n  " + "\n  ".join(errors))
    if args.invite:
        print(f"https://discord.com/oauth2/authorize?client_id={args.invite}&scope=bot&permissions=8")
        return
    if args.dry_run:
        dry_run()
        return
    if not args.guild_id:
        ap.error("give your server ID, or use --dry-run")
    icon = Path(args.icon).expanduser() if args.icon else None
    if icon and not icon.is_file():
        sys.exit(f"Icon not found: {icon}")
    token = os.environ.get("DISCORD_TOKEN")
    if not token:
        sys.exit("Set DISCORD_TOKEN to your bot's token first (Developer Portal > Bot > Reset Token).")

    client = discord.Client(intents=discord.Intents(guilds=True))

    @client.event
    async def on_ready():
        try:
            guild = client.get_guild(args.guild_id)
            if guild is None:
                print(f"The bot isn't in server {args.guild_id}. Invite it with --invite first.")
                return
            await Setup(guild, update=args.update, icon=icon, rename=args.name).run()
        except discord.Forbidden as e:
            print(f"Discord refused a change: {e}. Make sure the bot's role is at the top of the role list.")
        finally:
            await client.close()

    try:
        client.run(token, log_handler=None)
    except discord.LoginFailure:
        sys.exit("That token was rejected. Reset it in the Developer Portal and try again.")


if __name__ == "__main__":
    main()
