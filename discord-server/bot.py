#!/usr/bin/env python3
"""Security and support bot for the server built by setup_server.py.

    export DISCORD_TOKEN=...
    python bot.py

Features
  Verification   #verify button with a code check, minimum account age, paused in raid mode
  Tickets        dropdown in #create-ticket, private channels, claim/close, transcripts
  Logs           messages, members, roles, channels, invites, webhooks, bans and timeouts
  Anti-raid      join-rate detection turns on raid mode and alerts staff
  Anti-nuke      mass deletes/bans by one account trigger an alert and, if possible, strip that account
  Watchdog       alerts on webhooks, dangerous permission grants and lowered security settings
  Commands       /verify /lockdown /unlock /raidmode /purge /ticket-add /ticket-remove /panels

Needs state.json from setup_server.py, and the Server Members and Message Content
intents switched on in the Developer Portal (Bot > Privileged Gateway Intents).
"""

from __future__ import annotations

import asyncio
import io
import logging
import os
import random
import re
import sys
import time
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone

import discord
from discord import app_commands

import layout
from common import load_state, save_state

log = logging.getLogger("bot")
SEC = layout.SECURITY
COLOR = layout.CLIENT["color"]
RED, ORANGE, GREEN, GREY = 0xE74C3C, 0xE67E22, 0x2ECC71, 0x95A5A6
DANGEROUS = ("administrator", "manage_guild", "manage_roles", "manage_channels", "manage_webhooks",
             "ban_members", "kick_members", "mention_everyone", "moderate_members")
TICKET_TOPIC = re.compile(r"ticket:(\d+):(\w+):(\d+)")
LOCK_PERMS = ("send_messages", "send_messages_in_threads", "add_reactions", "connect")
TICKET_TYPES = {t["key"]: t for t in layout.TICKET_TYPES}


def now() -> datetime:
    return datetime.now(timezone.utc)


def ts(dt: datetime, style: str = "R") -> str:
    return discord.utils.format_dt(dt, style)


def clip(text: str, n: int = 1000) -> str:
    text = text or "*(empty)*"
    return text if len(text) <= n else text[: n - 1] + "…"


class Bot(discord.Client):
    def __init__(self):
        intents = discord.Intents.default()
        intents.members = True
        intents.message_content = True
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.tree = app_commands.CommandTree(self)
        self.state = load_state()
        self.gid = self.state.get("guild_id")
        if not self.gid or not self.state.get("channels"):
            sys.exit("state.json is missing or empty. Run setup_server.py first.")
        self.joins: deque[float] = deque()
        self.actions: dict[int, deque[float]] = defaultdict(deque)
        self.ticket_cooldown: dict[int, float] = {}
        self.verify_cooldown: dict[int, float] = {}
        self.ticket_lock = asyncio.Lock()
        self.ready_once = False

    # ── Lookups ───────────────────────────────────────────────────────────────
    @property
    def guild(self) -> discord.Guild | None:
        return self.get_guild(self.gid)

    def ch(self, key: str):
        return self.guild.get_channel(self.state["channels"].get(key, 0))

    def role(self, key: str) -> discord.Role | None:
        return self.guild.get_role(self.state["roles"].get(key, 0))

    def has(self, member: discord.Member, keys) -> bool:
        if member.guild_permissions.administrator:
            return True
        ids = {self.state["roles"].get(k) for k in keys}
        return any(r.id in ids for r in member.roles)

    def is_staff(self, m: discord.Member) -> bool:
        return self.has(m, layout.STAFF)

    def save(self) -> None:
        save_state(self.state)

    async def send_log(self, key: str, embed: discord.Embed, **kw) -> None:
        ch = self.ch(key)
        if ch is None:
            return
        embed.timestamp = embed.timestamp or now()
        try:
            await ch.send(embed=embed, **kw)
        except discord.HTTPException as e:
            log.warning("could not log to %s: %s", key, e)

    async def alert(self, title: str, description: str, color: int = RED) -> None:
        """Post to #security-alerts and ping the admin roles."""
        roles = [r for r in (self.role("founder"), self.role("owner"), self.role("admin")) if r]
        await self.send_log("security-alerts", discord.Embed(title=f"🚨 {title}", description=description, colour=color),
                            content=" ".join(r.mention for r in roles),
                            allowed_mentions=discord.AllowedMentions(roles=roles))

    async def audit_actor(self, action: discord.AuditLogAction, target_id: int | None = None):
        """Who did it, from the audit log (best effort)."""
        try:
            async for entry in self.guild.audit_logs(limit=6, action=action):
                if (now() - entry.created_at).total_seconds() > 20:
                    break
                if target_id is None or getattr(entry.target, "id", None) == target_id:
                    return entry.user, entry.reason
        except discord.HTTPException:
            pass
        return None, None

    # ── Startup ───────────────────────────────────────────────────────────────
    async def setup_hook(self) -> None:
        self.add_view(VerifyView(self))
        self.add_view(TicketPanel(self))
        self.add_view(TicketControls(self))
        register_commands(self)
        guild = discord.Object(self.gid)
        self.tree.copy_global_to(guild=guild)
        await self.tree.sync(guild=guild)

    async def on_ready(self) -> None:
        if self.ready_once:
            return
        self.ready_once = True
        g = self.guild
        if g is None:
            sys.exit(f"The bot isn't in server {self.gid}.")
        log.info("Logged in as %s in %s", self.user, g.name)
        if g.me.guild_permissions.administrator:
            log.warning("The bot still has Administrator. Open the runtime invite link from "
                        "'setup_server.py --invite' to remove it.")
        admin = self.role("admin")
        if admin and g.me.top_role > admin:
            log.warning("The bot's role is above Admin. Move it just below Admin in Server Settings > Roles.")
        member = self.role("member")
        if member and g.me.top_role <= member:
            log.error("The bot's role is below Member, so it can't verify anyone. Move it up.")
        await self.ensure_panels()

    async def ensure_panels(self, force: bool = False) -> None:
        panels = self.state.setdefault("panels", {})
        for key, chan, make in (("verify", "verify", self.verify_panel), ("ticket", "create-ticket", self.ticket_panel)):
            ch = self.ch(chan)
            if ch is None:
                continue
            msg = None
            if panels.get(key) and not force:
                try:
                    msg = await ch.fetch_message(panels[key])
                except discord.HTTPException:
                    msg = None
            if msg is None:
                embed, view = make()
                msg = await ch.send(embed=embed, view=view)
                panels[key] = msg.id
                self.save()
                log.info("posted %s panel", key)

    def verify_panel(self):
        e = discord.Embed(
            title=f"Welcome to {layout.CLIENT['name']}",
            description=("Read the rules, then press **Verify** to unlock the server.\n\n"
                         "You'll be asked to pick a code to prove you're human. "
                         f"Accounts younger than {SEC['min_account_age_days']} days can't verify "
                         "automatically. If that's you, wait a little or ask staff."),
            colour=COLOR)
        return e, VerifyView(self)

    def ticket_panel(self):
        lines = "\n".join(f"{t['emoji']} **{t['label']}**" for t in layout.TICKET_TYPES)
        e = discord.Embed(
            title="Open a Ticket",
            description=("Need private help from the team? Pick a category below and a private "
                         "channel will open that only you and staff can see.\n\n" + lines +
                         "\n\nStaff will **never** ask for your password or send you files to run."),
            colour=COLOR)
        return e, TicketPanel(self)

    # ── Anti-nuke ─────────────────────────────────────────────────────────────
    async def count_action(self, actor, what: str) -> None:
        if actor is None or actor.id in (self.user.id, self.guild.owner_id):
            return
        q = self.actions[actor.id]
        t = time.monotonic()
        q.append(t)
        while q and t - q[0] > SEC["nuke_window"]:
            q.popleft()
        if len(q) < SEC["nuke_actions"]:
            return
        q.clear()
        member = self.guild.get_member(actor.id)
        result = "I couldn't act on them: their role is above mine. **Remove their roles now.**"
        if member and member.top_role < self.guild.me.top_role:
            try:
                keep = [r for r in member.roles if r.managed or r.is_default()]
                await member.edit(roles=keep, reason="Anti-nuke: mass destructive actions")
                if member.bot:
                    await member.kick(reason="Anti-nuke: mass destructive actions")
                    result = "I removed their roles and kicked the bot."
                else:
                    await member.timeout(timedelta(days=1), reason="Anti-nuke: mass destructive actions")
                    result = "I removed their roles and timed them out for 24 hours."
            except discord.HTTPException as e:
                result = f"I tried to stop them but Discord refused: {e}"
        await self.alert("Possible nuke",
                         f"{actor.mention} (`{actor.id}`) did {SEC['nuke_actions']}+ {what} within "
                         f"{SEC['nuke_window']}s.\n\n{result}\n\nCheck the audit log and their account.")

    # ── Members ───────────────────────────────────────────────────────────────
    async def on_member_join(self, m: discord.Member) -> None:
        if m.guild.id != self.gid:
            return
        age = now() - m.created_at
        young = age < timedelta(days=SEC["min_account_age_days"])
        e = discord.Embed(title="Member joined", colour=ORANGE if young else GREEN,
                          description=f"{m.mention} `{m}` (`{m.id}`)")
        e.add_field(name="Account created", value=f"{ts(m.created_at, 'f')} ({ts(m.created_at)})")
        if young:
            e.add_field(name="⚠️", value="New account: can't self-verify", inline=False)
        if m.bot:
            actor, _ = await self.audit_actor(discord.AuditLogAction.bot_add, m.id)
            await self.alert("Bot added", f"{m.mention} was added by {actor.mention if actor else 'someone'}. "
                             "Give it the Bot role and only the permissions it needs.", ORANGE)
        e.set_thumbnail(url=m.display_avatar.url)
        e.set_footer(text=f"Member #{m.guild.member_count}")
        await self.send_log("member-log", e)

        # Raid detection
        t = time.monotonic()
        self.joins.append(t)
        while self.joins and t - self.joins[0] > SEC["raid_window"]:
            self.joins.popleft()
        if len(self.joins) >= SEC["raid_joins"] and not self.state.get("raid_mode"):
            self.state["raid_mode"] = True
            self.save()
            await self.alert("Raid detected",
                             f"{len(self.joins)} joins in {SEC['raid_window']}s. **Raid mode is on**: "
                             "verification is paused. Check #member-log, then run `/raidmode off`.\n"
                             "If it's bad, also pause invites in Server Settings > Invites.")

    async def on_member_remove(self, m: discord.Member) -> None:
        if m.guild.id != self.gid:
            return
        roles = ", ".join(r.mention for r in m.roles if not r.is_default()) or "none"
        e = discord.Embed(title="Member left", colour=GREY, description=f"{m.mention} `{m}` (`{m.id}`)")
        e.add_field(name="Roles", value=clip(roles))
        if m.joined_at:
            e.add_field(name="Joined", value=ts(m.joined_at))
        await self.send_log("member-log", e)
        actor, reason = await self.audit_actor(discord.AuditLogAction.kick, m.id)
        if actor:
            await self.send_log("mod-log", discord.Embed(
                title="Kicked", colour=ORANGE,
                description=f"{m.mention} `{m}` by {actor.mention}\nReason: {reason or 'none'}"))
            await self.count_action(actor, "kicks")

    async def on_member_ban(self, guild: discord.Guild, user: discord.User) -> None:
        if guild.id != self.gid:
            return
        actor, reason = await self.audit_actor(discord.AuditLogAction.ban, user.id)
        await self.send_log("mod-log", discord.Embed(
            title="Banned", colour=RED,
            description=f"{user.mention} `{user}` (`{user.id}`) by {actor.mention if actor else 'unknown'}\n"
                        f"Reason: {reason or 'none'}"))
        await self.count_action(actor, "bans")

    async def on_member_unban(self, guild: discord.Guild, user: discord.User) -> None:
        if guild.id != self.gid:
            return
        actor, _ = await self.audit_actor(discord.AuditLogAction.unban, user.id)
        await self.send_log("mod-log", discord.Embed(
            title="Unbanned", colour=GREEN,
            description=f"{user.mention} `{user}` by {actor.mention if actor else 'unknown'}"))

    async def on_member_update(self, before: discord.Member, after: discord.Member) -> None:
        if after.guild.id != self.gid:
            return
        added = [r for r in after.roles if r not in before.roles]
        removed = [r for r in before.roles if r not in after.roles]
        if added or removed:
            actor, _ = await self.audit_actor(discord.AuditLogAction.member_role_update, after.id)
            e = discord.Embed(title="Roles changed", colour=COLOR,
                              description=f"{after.mention} `{after}` by {actor.mention if actor else 'unknown'}")
            if added:
                e.add_field(name="Added", value=" ".join(r.mention for r in added))
            if removed:
                e.add_field(name="Removed", value=" ".join(r.mention for r in removed))
            await self.send_log("role-log", e)
            risky = [r for r in added if any(getattr(r.permissions, p) for p in DANGEROUS)]
            if risky and actor and actor.id != self.user.id:
                await self.alert("Powerful role given",
                                 f"{actor.mention} gave {after.mention} {' '.join(r.mention for r in risky)}. "
                                 "Make sure this was intended.", ORANGE)
            # Staff also need Member to see the member channels.
            member = self.role("member")
            if member and member not in after.roles and self.has(after, layout.STAFF + ["beta"]):
                try:
                    await after.add_roles(member, reason="Staff and testers also get Member")
                except discord.HTTPException:
                    pass

        if before.timed_out_until != after.timed_out_until:
            actor, reason = await self.audit_actor(discord.AuditLogAction.member_update, after.id)
            if after.timed_out_until and after.timed_out_until > now():
                desc = f"{after.mention} until {ts(after.timed_out_until, 'f')}"
                title, colour = "Timed out", ORANGE
            else:
                desc, title, colour = after.mention, "Timeout removed", GREEN
            await self.send_log("mod-log", discord.Embed(
                title=title, colour=colour,
                description=f"{desc}\nBy {actor.mention if actor else 'unknown'} · Reason: {reason or 'none'}"))

    # ── Messages ──────────────────────────────────────────────────────────────
    def loggable(self, msg: discord.Message) -> bool:
        if msg.guild is None or msg.guild.id != self.gid or msg.author.bot:
            return False
        cat = getattr(msg.channel, "category_id", None)
        return cat != self.state["categories"].get("logs")

    async def on_message_delete(self, msg: discord.Message) -> None:
        if not self.loggable(msg):
            return
        e = discord.Embed(title="Message deleted", colour=RED,
                          description=f"{msg.author.mention} in {msg.channel.mention}\n\n{clip(msg.content, 3500)}")
        if msg.attachments:
            e.add_field(name="Attachments", value=clip("\n".join(a.filename for a in msg.attachments)))
        e.set_footer(text=f"User {msg.author.id} · Message {msg.id}")
        await self.send_log("message-log", e)

    async def on_bulk_message_delete(self, msgs: list[discord.Message]) -> None:
        msgs = [m for m in msgs if self.loggable(m)]
        if not msgs:
            return
        text = "\n".join(f"[{m.created_at:%H:%M}] {m.author} ({m.author.id}): {m.content}" for m in msgs)
        await self.send_log("message-log",
                            discord.Embed(title=f"{len(msgs)} messages purged", colour=RED,
                                          description=f"In {msgs[0].channel.mention}"),
                            file=discord.File(io.BytesIO(text.encode()), "purged.txt"))

    async def on_message_edit(self, before: discord.Message, after: discord.Message) -> None:
        if not self.loggable(after) or before.content == after.content:
            return
        e = discord.Embed(title="Message edited", colour=ORANGE, url=after.jump_url,
                          description=f"{after.author.mention} in {after.channel.mention}")
        e.add_field(name="Before", value=clip(before.content), inline=False)
        e.add_field(name="After", value=clip(after.content), inline=False)
        e.set_footer(text=f"User {after.author.id}")
        await self.send_log("message-log", e)

    # ── Server changes ────────────────────────────────────────────────────────
    async def on_guild_channel_create(self, ch) -> None:
        if ch.guild.id != self.gid or TICKET_TOPIC.match(getattr(ch, "topic", "") or ""):
            return
        actor, _ = await self.audit_actor(discord.AuditLogAction.channel_create, ch.id)
        if actor and actor.id == self.user.id:
            return
        await self.send_log("server-log", discord.Embed(
            title="Channel created", colour=GREEN,
            description=f"{ch.mention} by {actor.mention if actor else 'unknown'}"))

    async def on_guild_channel_delete(self, ch) -> None:
        if ch.guild.id != self.gid:
            return
        actor, _ = await self.audit_actor(discord.AuditLogAction.channel_delete, ch.id)
        if actor and actor.id == self.user.id:
            return
        await self.send_log("server-log", discord.Embed(
            title="Channel deleted", colour=RED,
            description=f"`{ch.name}` by {actor.mention if actor else 'unknown'}"))
        await self.count_action(actor, "channel deletions")

    async def on_guild_channel_update(self, before, after) -> None:
        if after.guild.id != self.gid:
            return
        changes = []
        if before.name != after.name:
            changes.append(f"Name: `{before.name}` → `{after.name}`")
        if before.overwrites != after.overwrites:
            changes.append("Permissions changed")
        if not changes:
            return
        actor, _ = await self.audit_actor(discord.AuditLogAction.channel_update, after.id)
        if actor is None:
            actor, _ = await self.audit_actor(discord.AuditLogAction.overwrite_update, after.id)
        if actor and actor.id == self.user.id:
            return
        await self.send_log("server-log", discord.Embed(
            title="Channel updated", colour=ORANGE,
            description=f"{after.mention} by {actor.mention if actor else 'unknown'}\n" + "\n".join(changes)))

    async def on_guild_role_create(self, role: discord.Role) -> None:
        if role.guild.id != self.gid:
            return
        actor, _ = await self.audit_actor(discord.AuditLogAction.role_create, role.id)
        await self.send_log("role-log", discord.Embed(
            title="Role created", colour=GREEN,
            description=f"{role.mention} by {actor.mention if actor else 'unknown'}"))

    async def on_guild_role_delete(self, role: discord.Role) -> None:
        if role.guild.id != self.gid:
            return
        actor, _ = await self.audit_actor(discord.AuditLogAction.role_delete, role.id)
        await self.send_log("role-log", discord.Embed(
            title="Role deleted", colour=RED, description=f"`{role.name}` by {actor.mention if actor else 'unknown'}"))
        await self.count_action(actor, "role deletions")

    async def on_guild_role_update(self, before: discord.Role, after: discord.Role) -> None:
        if after.guild.id != self.gid:
            return
        gained = [p for p in DANGEROUS if getattr(after.permissions, p) and not getattr(before.permissions, p)]
        changes = []
        if before.name != after.name:
            changes.append(f"Name: `{before.name}` → `{after.name}`")
        if before.permissions != after.permissions:
            changes.append("Permissions changed" + (f" (gained **{', '.join(gained)}**)" if gained else ""))
        if not changes:  # position-only shuffles fire for every role; skip them
            return
        actor, _ = await self.audit_actor(discord.AuditLogAction.role_update, after.id)
        await self.send_log("role-log", discord.Embed(
            title="Role updated", colour=ORANGE,
            description=f"{after.mention} by {actor.mention if actor else 'unknown'}\n" + "\n".join(changes)))
        if gained and not (actor and actor.id == self.user.id):
            await self.alert("Role gained dangerous permissions",
                             f"{after.mention} gained **{', '.join(gained)}** "
                             f"(by {actor.mention if actor else 'unknown'}). "
                             f"It has {len(after.members)} members.", ORANGE)

    async def on_webhooks_update(self, ch) -> None:
        if ch.guild.id != self.gid:
            return
        actor, _ = await self.audit_actor(discord.AuditLogAction.webhook_create)
        if actor is None:
            return
        await self.alert("Webhook created",
                         f"{actor.mention} created a webhook in {ch.mention}. Webhooks can post as anyone; "
                         "delete it in Server Settings > Integrations if it wasn't expected.", ORANGE)

    async def on_guild_update(self, before: discord.Guild, after: discord.Guild) -> None:
        if after.id != self.gid:
            return
        if after.verification_level < before.verification_level:
            await self.alert("Verification level lowered",
                             f"{before.verification_level.name} → {after.verification_level.name}")
        if after.mfa_level < before.mfa_level:
            await self.alert("2FA requirement turned off", "Moderators no longer need 2FA.")
        if before.name != after.name or before.vanity_url_code != after.vanity_url_code:
            await self.send_log("server-log", discord.Embed(
                title="Server updated", colour=ORANGE,
                description=f"Name: `{before.name}` → `{after.name}`\n"
                            f"Vanity: `{before.vanity_url_code}` → `{after.vanity_url_code}`"))

    async def on_invite_create(self, inv: discord.Invite) -> None:
        if inv.guild is None or inv.guild.id != self.gid:
            return
        uses = inv.max_uses or "∞"
        expiry = ts(inv.expires_at) if inv.expires_at else "never"
        await self.send_log("server-log", discord.Embed(
            title="Invite created", colour=GREY,
            description=f"`{inv.code}` by {inv.inviter.mention if inv.inviter else 'unknown'} "
                        f"for {inv.channel.mention}, uses {uses}, expires {expiry}"))

    # ── Tickets ───────────────────────────────────────────────────────────────
    def ticket_info(self, channel) -> tuple[int, str, int] | None:
        m = TICKET_TOPIC.match(getattr(channel, "topic", "") or "")
        return (int(m[1]), m[2], int(m[3])) if m else None

    def open_tickets(self, user_id: int) -> list:
        cat = self.guild.get_channel(self.state["categories"].get("tickets", 0))
        if cat is None:
            return []
        return [c for c in cat.text_channels if (i := self.ticket_info(c)) and i[0] == user_id]

    async def create_ticket(self, inter: discord.Interaction, kind: str, subject: str, details: str) -> None:
        g, user = self.guild, inter.user
        spec = TICKET_TYPES[kind]
        cat = g.get_channel(self.state["categories"].get("tickets", 0))
        if cat is None:
            return await inter.response.send_message("Tickets aren't set up. Ask an Admin.", ephemeral=True)
        async with self.ticket_lock:
            if len(self.open_tickets(user.id)) >= SEC["max_open_tickets"]:
                return await inter.response.send_message("You already have an open ticket.", ephemeral=True)
            self.state["ticket_counter"] = self.state.get("ticket_counter", 0) + 1
            num = self.state["ticket_counter"]
            self.save()
        self.ticket_cooldown[user.id] = time.monotonic()

        talk = dict(view_channel=True, send_messages=True, attach_files=True, embed_links=True,
                    read_message_history=True)
        ow = {g.default_role: discord.PermissionOverwrite(view_channel=False),
              g.me: discord.PermissionOverwrite(**talk, manage_messages=True, manage_channels=True),
              user: discord.PermissionOverwrite(**talk)}
        if (member := self.role("member")):
            ow[member] = discord.PermissionOverwrite(view_channel=False)
        staff_roles = [r for k in spec["staff"] if (r := self.role(k))]
        for r in staff_roles:
            ow[r] = discord.PermissionOverwrite(**talk, manage_messages=True)
        safe = re.sub(r"[^a-z0-9]", "", user.name.lower())[:20] or "user"
        channel = await g.create_text_channel(
            f"{spec['emoji']}︱{num:04d}-{safe}", category=cat, overwrites=ow,
            topic=f"ticket:{user.id}:{kind}:{num}", reason=f"Ticket #{num} for {user}")

        e = discord.Embed(title=f"{spec['emoji']} Ticket #{num:04d}: {clip(subject, 200)}", colour=COLOR,
                          description=clip(details, 3500))
        e.add_field(name="Opened by", value=user.mention)
        e.add_field(name="Type", value=spec["label"])
        e.add_field(name="Status", value="Waiting for staff", inline=False)
        e.set_footer(text="Staff never ask for passwords or send files to run.")
        await channel.send(content=f"{user.mention} the team will be with you soon.", embed=e,
                           view=TicketControls(self), allowed_mentions=discord.AllowedMentions(users=[user]))
        await inter.response.send_message(f"Your ticket is open: {channel.mention}", ephemeral=True)
        await self.send_log("ticket-transcripts", discord.Embed(
            title=f"Ticket #{num:04d} opened", colour=GREEN,
            description=f"{user.mention} · {spec['label']} · {channel.mention}"))

    def can_manage_ticket(self, member: discord.Member, kind: str) -> bool:
        return self.has(member, TICKET_TYPES.get(kind, {}).get("staff", layout.TICKET_STAFF))

    async def close_ticket(self, inter: discord.Interaction, channel: discord.TextChannel) -> None:
        info = self.ticket_info(channel)
        if info is None:
            return
        owner_id, kind, num = info
        await inter.response.send_message("Closing: saving the transcript…")
        lines, count, claimed = [], 0, None
        async for m in channel.history(limit=None, oldest_first=True):
            count += 1
            body = m.content
            for em in m.embeds:
                body += f" [embed: {em.title or ''} {em.description or ''}]"
                for f in em.fields:
                    if f.name == "Status" and f.value.startswith("Claimed by"):
                        claimed = f.value
            body += "".join(f" [file: {a.url}]" for a in m.attachments)
            lines.append(f"[{m.created_at:%Y-%m-%d %H:%M:%S}] {m.author} ({m.author.id}): {body}")
        transcript = (f"Ticket #{num:04d} · {TICKET_TYPES.get(kind, {}).get('label', kind)}\n"
                      f"Opened by {owner_id} · Closed by {inter.user} ({inter.user.id}) at {now():%Y-%m-%d %H:%M} UTC\n\n"
                      + "\n".join(lines))
        name = f"ticket-{num:04d}.txt"
        e = discord.Embed(title=f"Ticket #{num:04d} closed", colour=GREY,
                          description=f"Opened by <@{owner_id}> · Closed by {inter.user.mention}\n"
                                      f"{claimed or 'Never claimed'} · {count} messages")
        await self.send_log("ticket-transcripts", e, file=discord.File(io.BytesIO(transcript.encode()), name))
        owner = self.guild.get_member(owner_id)
        if owner:
            try:
                await owner.send(f"Your ticket #{num:04d} in **{self.guild.name}** was closed. Here's a copy.",
                                 file=discord.File(io.BytesIO(transcript.encode()), name))
            except discord.HTTPException:
                pass  # DMs closed
        await asyncio.sleep(3)
        await channel.delete(reason=f"Ticket closed by {inter.user}")


# ── Views ─────────────────────────────────────────────────────────────────────

class VerifyView(discord.ui.View):
    def __init__(self, bot: Bot):
        super().__init__(timeout=None)
        self.bot = bot

    @discord.ui.button(label="Verify", emoji="✅", style=discord.ButtonStyle.success, custom_id="verify:start")
    async def verify(self, inter: discord.Interaction, _):
        b, m = self.bot, inter.user
        member_role = b.role("member")
        if member_role is None:
            return await inter.response.send_message("Verification isn't set up yet.", ephemeral=True)
        if member_role in m.roles:
            return await inter.response.send_message("You're already verified.", ephemeral=True)
        if b.state.get("raid_mode"):
            return await inter.response.send_message(
                "Verification is paused for a moment while staff deal with a raid. Please try again later.",
                ephemeral=True)
        if now() - m.created_at < timedelta(days=SEC["min_account_age_days"]):
            await b.send_log("member-log", discord.Embed(
                title="Verification blocked: new account", colour=ORANGE,
                description=f"{m.mention} (created {ts(m.created_at)}). Staff can `/verify` them."))
            return await inter.response.send_message(
                f"Your Discord account is newer than {SEC['min_account_age_days']} days, so you can't verify "
                "automatically. Staff have been notified and can verify you manually.", ephemeral=True)
        wait = b.verify_cooldown.get(m.id, 0) + SEC["verify_fail_cooldown"] - time.monotonic()
        if wait > 0:
            return await inter.response.send_message(f"Try again in {int(wait) + 1}s.", ephemeral=True)
        code = f"{random.randint(0, 9999):04d}"
        prompt = discord.Embed(colour=COLOR, title="Quick check",
                               description="Press the button that shows this code:\n\n" + " ".join(f"`{c}`" for c in code))
        await inter.response.send_message(embed=prompt, view=CaptchaView(b, m.id, code), ephemeral=True)


class CaptchaView(discord.ui.View):
    def __init__(self, bot: Bot, user_id: int, code: str):
        super().__init__(timeout=60)
        self.bot, self.user_id, self.code, self.done = bot, user_id, code, False
        options = {code}
        while len(options) < 5:
            options.add(f"{random.randint(0, 9999):04d}")
        for opt in random.sample(sorted(options), 5):
            btn = discord.ui.Button(label=opt, style=discord.ButtonStyle.secondary)
            btn.callback = self.make_callback(opt)
            self.add_item(btn)

    def make_callback(self, opt: str):
        async def callback(inter: discord.Interaction):
            if inter.user.id != self.user_id or self.done:
                return await inter.response.defer()
            self.done = True
            self.stop()
            b, m = self.bot, inter.user
            if opt != self.code:
                b.verify_cooldown[m.id] = time.monotonic()
                return await inter.response.edit_message(
                    content=f"That wasn't it. Try again in {SEC['verify_fail_cooldown']}s.", embed=None, view=None)
            try:
                await m.add_roles(b.role("member"), reason="Passed verification")
            except discord.HTTPException:
                return await inter.response.edit_message(
                    content="I couldn't give you the Member role. Please tell staff.", embed=None, view=None)
            await inter.response.edit_message(content="✅ You're verified. Welcome!", embed=None, view=None)
            await b.send_log("member-log", discord.Embed(title="Verified", colour=GREEN, description=m.mention))
        return callback


class TicketPanel(discord.ui.View):
    def __init__(self, bot: Bot):
        super().__init__(timeout=None)
        self.bot = bot
        select = discord.ui.Select(
            custom_id="ticket:open", placeholder="Choose a ticket type…",
            options=[discord.SelectOption(label=t["label"], value=t["key"], emoji=t["emoji"]) for t in layout.TICKET_TYPES])
        select.callback = self.pick
        self.add_item(select)

    async def pick(self, inter: discord.Interaction):
        b, user = self.bot, inter.user
        kind = inter.data["values"][0]
        if len(b.open_tickets(user.id)) >= SEC["max_open_tickets"]:
            mine = b.open_tickets(user.id)[0]
            return await inter.response.send_message(f"You already have an open ticket: {mine.mention}", ephemeral=True)
        wait = b.ticket_cooldown.get(user.id, 0) + SEC["ticket_cooldown"] - time.monotonic()
        if wait > 0:
            return await inter.response.send_message(f"Please wait {int(wait) + 1}s before opening another ticket.",
                                                     ephemeral=True)
        await inter.response.send_modal(TicketModal(b, kind))
        # Reset the dropdown so the same option can be picked again.
        try:
            await inter.message.edit(view=TicketPanel(b))
        except discord.HTTPException:
            pass


class TicketModal(discord.ui.Modal):
    subject = discord.ui.TextInput(label="Subject", max_length=100)
    details = discord.ui.TextInput(label="Details", style=discord.TextStyle.paragraph, max_length=1500,
                                   placeholder="What happened? Include versions, logs or screenshots after opening.")

    def __init__(self, bot: Bot, kind: str):
        super().__init__(title=TICKET_TYPES[kind]["label"][:45])
        self.bot, self.kind = bot, kind

    async def on_submit(self, inter: discord.Interaction):
        await self.bot.create_ticket(inter, self.kind, str(self.subject), str(self.details))


class TicketControls(discord.ui.View):
    def __init__(self, bot: Bot):
        super().__init__(timeout=None)
        self.bot = bot

    @discord.ui.button(label="Claim", emoji="🙋", style=discord.ButtonStyle.primary, custom_id="ticket:claim")
    async def claim(self, inter: discord.Interaction, _):
        info = self.bot.ticket_info(inter.channel)
        if info is None or not self.bot.can_manage_ticket(inter.user, info[1]):
            return await inter.response.send_message("Only staff can claim tickets.", ephemeral=True)
        e = inter.message.embeds[0]
        for i, f in enumerate(e.fields):
            if f.name == "Status":
                if f.value.startswith("Claimed"):
                    return await inter.response.send_message(f"Already {f.value.lower()}.", ephemeral=True)
                e.set_field_at(i, name="Status", value=f"Claimed by {inter.user.mention}", inline=False)
        await inter.response.edit_message(embed=e)
        await inter.channel.send(f"{inter.user.mention} is handling this ticket.")

    @discord.ui.button(label="Close", emoji="🔒", style=discord.ButtonStyle.danger, custom_id="ticket:close")
    async def close(self, inter: discord.Interaction, _):
        info = self.bot.ticket_info(inter.channel)
        if info is None:
            return await inter.response.defer()
        if inter.user.id != info[0] and not self.bot.can_manage_ticket(inter.user, info[1]):
            return await inter.response.send_message("You can't close this ticket.", ephemeral=True)
        await inter.response.send_message("Close this ticket? A transcript will be saved.",
                                          view=ConfirmClose(self.bot), ephemeral=True)


class ConfirmClose(discord.ui.View):
    def __init__(self, bot: Bot):
        super().__init__(timeout=30)
        self.bot = bot

    @discord.ui.button(label="Close ticket", style=discord.ButtonStyle.danger)
    async def confirm(self, inter: discord.Interaction, _):
        self.stop()
        await self.bot.close_ticket(inter, inter.channel)


# ── Slash commands ────────────────────────────────────────────────────────────

def register_commands(bot: Bot) -> None:
    tree = bot.tree

    def staff_only(keys):
        def predicate(inter: discord.Interaction) -> bool:
            if not isinstance(inter.user, discord.Member) or not bot.has(inter.user, keys):
                raise app_commands.CheckFailure("You don't have permission to use this.")
            return True
        return app_commands.check(predicate)

    async def mod_log(inter, title, desc, colour=ORANGE):
        await bot.send_log("mod-log", discord.Embed(title=title, colour=colour,
                                                    description=f"{desc}\nBy {inter.user.mention}"))

    @tree.command(description="Verify a member manually (skips the account-age check)")
    @app_commands.default_permissions(moderate_members=True)
    @app_commands.guild_only()
    @staff_only(["mod", *layout.ADMINS])
    async def verify(inter: discord.Interaction, member: discord.Member):
        await member.add_roles(bot.role("member"), reason=f"Manually verified by {inter.user}")
        await inter.response.send_message(f"Verified {member.mention}.", ephemeral=True)
        await mod_log(inter, "Manually verified", member.mention, GREEN)

    async def set_lock(inter: discord.Interaction, locked: bool, channel):
        member = bot.role("member")
        if channel is not None:
            targets = [channel]
        else:
            targets = []
            for key in layout.LOCKDOWN_CATEGORIES:
                cat = bot.guild.get_channel(bot.state["categories"].get(key, 0))
                if cat:
                    targets += cat.channels
        saved = bot.state.setdefault("lockdown", {})
        await inter.response.defer(ephemeral=True, thinking=True)
        done = 0
        for ch in targets:
            ow = ch.overwrites_for(member)
            if locked:
                if str(ch.id) in saved:
                    continue
                saved[str(ch.id)] = {p: getattr(ow, p) for p in LOCK_PERMS}
                for p in LOCK_PERMS:
                    setattr(ow, p, False)
            else:
                prev = saved.pop(str(ch.id), None)
                if prev is None:
                    continue
                for p, v in prev.items():
                    setattr(ow, p, v)
            try:
                await ch.set_permissions(member, overwrite=ow, reason=f"{'Lockdown' if locked else 'Unlock'} by {inter.user}")
                done += 1
            except discord.HTTPException as e:
                log.warning("lock %s failed: %s", ch, e)
        bot.save()
        word = "Locked" if locked else "Unlocked"
        await inter.followup.send(f"{word} {done} channel(s).", ephemeral=True)
        await mod_log(inter, f"{word}", f"{done} channel(s)" + (f": {channel.mention}" if channel else " (server-wide)"),
                      RED if locked else GREEN)
        if locked and channel is None:
            await bot.alert("Server lockdown", f"{inter.user.mention} locked the member channels. "
                            "Run `/unlock` to undo.", ORANGE)

    @tree.command(description="Stop members talking in one channel, or in all member channels")
    @app_commands.default_permissions(moderate_members=True)
    @app_commands.guild_only()
    @staff_only(["mod", *layout.ADMINS])
    async def lockdown(inter: discord.Interaction, channel: discord.TextChannel | discord.VoiceChannel | None = None):
        await set_lock(inter, True, channel)

    @tree.command(description="Undo /lockdown")
    @app_commands.default_permissions(moderate_members=True)
    @app_commands.guild_only()
    @staff_only(["mod", *layout.ADMINS])
    async def unlock(inter: discord.Interaction, channel: discord.TextChannel | discord.VoiceChannel | None = None):
        await set_lock(inter, False, channel)

    @tree.command(description="Pause or resume verification during a raid")
    @app_commands.default_permissions(moderate_members=True)
    @app_commands.guild_only()
    @staff_only(["mod", *layout.ADMINS])
    @app_commands.choices(mode=[app_commands.Choice(name="on", value="on"), app_commands.Choice(name="off", value="off")])
    async def raidmode(inter: discord.Interaction, mode: app_commands.Choice[str]):
        bot.state["raid_mode"] = mode.value == "on"
        bot.save()
        await inter.response.send_message(f"Raid mode {mode.value}.", ephemeral=True)
        await bot.alert(f"Raid mode {mode.value}", f"Set by {inter.user.mention}.",
                        RED if mode.value == "on" else GREEN)

    @tree.command(description="Delete recent messages in this channel")
    @app_commands.default_permissions(manage_messages=True)
    @app_commands.guild_only()
    @staff_only(["mod", *layout.ADMINS])
    async def purge(inter: discord.Interaction, count: app_commands.Range[int, 1, 100], user: discord.Member | None = None):
        await inter.response.defer(ephemeral=True, thinking=True)
        kwargs = {"check": (lambda m: m.author == user)} if user else {}
        deleted = await inter.channel.purge(limit=count, reason=f"/purge by {inter.user}", **kwargs)
        await inter.followup.send(f"Deleted {len(deleted)} message(s).", ephemeral=True)
        await mod_log(inter, "Purge", f"{len(deleted)} message(s) in {inter.channel.mention}"
                      + (f" from {user.mention}" if user else ""))

    async def ticket_member(inter, member, add: bool):
        info = bot.ticket_info(inter.channel)
        if info is None:
            return await inter.response.send_message("Use this inside a ticket.", ephemeral=True)
        if not bot.can_manage_ticket(inter.user, info[1]):
            return await inter.response.send_message("Only staff on this ticket can do that.", ephemeral=True)
        ow = discord.PermissionOverwrite(view_channel=True, send_messages=True, attach_files=True,
                                         read_message_history=True) if add else None
        await inter.channel.set_permissions(member, overwrite=ow, reason=f"Ticket edit by {inter.user}")
        await inter.response.send_message(f"{'Added' if add else 'Removed'} {member.mention}.")

    @tree.command(name="ticket-add", description="Add someone to this ticket")
    @app_commands.guild_only()
    async def ticket_add(inter: discord.Interaction, member: discord.Member):
        await ticket_member(inter, member, True)

    @tree.command(name="ticket-remove", description="Remove someone from this ticket")
    @app_commands.guild_only()
    async def ticket_remove(inter: discord.Interaction, member: discord.Member):
        await ticket_member(inter, member, False)

    @tree.command(description="Re-post the verify and ticket panels")
    @app_commands.default_permissions(manage_guild=True)
    @app_commands.guild_only()
    @staff_only(layout.ADMINS)
    async def panels(inter: discord.Interaction):
        await inter.response.defer(ephemeral=True, thinking=True)
        await bot.ensure_panels(force=True)
        await inter.followup.send("Panels posted.", ephemeral=True)

    @tree.error
    async def on_error(inter: discord.Interaction, error: app_commands.AppCommandError):
        msg = str(error) if isinstance(error, app_commands.CheckFailure) else "Something went wrong. It's been logged."
        if not isinstance(error, app_commands.CheckFailure):
            log.exception("command failed", exc_info=error)
        if inter.response.is_done():
            await inter.followup.send(msg, ephemeral=True)
        else:
            await inter.response.send_message(msg, ephemeral=True)


def main() -> None:
    token = os.environ.get("DISCORD_TOKEN")
    if not token:
        sys.exit("Set DISCORD_TOKEN to your bot's token first.")
    discord.utils.setup_logging(level=logging.INFO)
    try:
        Bot().run(token, log_handler=None)
    except discord.PrivilegedIntentsRequired:
        sys.exit("Turn on 'Server Members Intent' and 'Message Content Intent' in the Developer Portal "
                 "(your app > Bot > Privileged Gateway Intents), then run again.")


if __name__ == "__main__":
    main()
