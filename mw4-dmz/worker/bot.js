// MW4 DMZ bot for The Hajin Exclusion Zone, as a Cloudflare Worker.
//
// Discord sends every slash command, button press, dropdown and form here as an HTTPS
// request (an "interactions endpoint"), so nothing has to stay online.
//
//   Squad finder   /lfg posts a squad card in #find-a-squad with Join / Leave / Close buttons,
//                  pings everyone and points them at the right voice channel when it fills.
//                  An hourly cron closes cards older than 3 hours.
//   Tickets        a panel with a dropdown, a short form, then a private channel under
//                  TICKETS ONLY for the member and staff. Claim, Close, transcript to #ticket-logs.
//   Mod tools      /warn /warnings /timeout /kick /ban /unban /purge /slowmode /userinfo,
//                  each logged to #server-logs.
//   DMZ guides     /build /blueprint /intel look up guides staff have saved with /guide-add.
//
// Environment: DISCORD_TOKEN (secret), KV (KV namespace binding).
// Optional: PUBLIC_KEY overrides the app's public key (used by the tests).

const CONFIG = {
  name: "The Hajin Exclusion Zone",
  color: 0x7a8b3c,
  app_id: "1574759457294913654",
  public_key: "35d9b2cf73c8ad93460998f28333deb0c238287dd5c6fb883e88d35431e77803",
  guild_id: "1564453181558235267",
  roles: {
    member: "1564453181566615585",
    mod: "1564453181566615588",
    developer: "1564453181566615589",
    admin: "1564453181566615590",
    owner: "1564453181566615591",
  },
  channels: {
    find_a_squad: "1564453636308734103",
    report_issues: "1564453182640357453",
    server_logs: "1564453182850203660",
    ticket_logs: "1564453182850203661",
    tickets_category: "1564453182850203662",
    blueprint_help: "1564453678847631410",
    build_help: "1564453716315078706",
  },
  squad_vc: { 2: "1564453182468259929", 3: "1564453182468259930", 4: "1564453182640357448" },
  lfg_max_age_hours: 3,
  ticket_cooldown_seconds: 300,
};

const API = "https://discord.com/api/v10";
const EPHEMERAL = 64;
const COLOR = CONFIG.color;
const RED = 0xe74c3c, ORANGE = 0xe67e22, GREEN = 0x2ecc71, GREY = 0x95a5a6, BLUE = 0x3498db;

const BIT = {
  ADMINISTRATOR: 1n << 3n, MANAGE_GUILD: 1n << 5n, VIEW: 1n << 10n, SEND: 1n << 11n,
  MANAGE_MESSAGES: 1n << 13n, EMBED: 1n << 14n, ATTACH: 1n << 15n, HISTORY: 1n << 16n,
  ADD_REACTIONS: 1n << 6n, SEND_IN_THREADS: 1n << 38n, MODERATE: 1n << 40n, MANAGE_CHANNELS: 1n << 4n,
  MANAGE_ROLES: 1n << 28n,
};
const TALK = BIT.VIEW | BIT.SEND | BIT.EMBED | BIT.ATTACH | BIT.HISTORY | BIT.ADD_REACTIONS;

const SIZES = { duo: 2, trio: 3, squad: 4 };
const SIZE_NAME = { 2: "Duo", 3: "Trio", 4: "Squad" };

const TICKETS = {
  help: { label: "Help / question", emoji: "❓", desc: "Game, server or bot help", staff: ["mod", "developer", "admin", "owner"] },
  report: { label: "Report a player", emoji: "🚨", desc: "Cheating, toxicity, scams", staff: ["mod", "admin", "owner"] },
  bug: { label: "Server or bot issue", emoji: "🔧", desc: "Something on the server is broken", staff: ["mod", "developer", "admin", "owner"] },
  appeal: { label: "Appeal a punishment", emoji: "⚖️", desc: "Warn or timeout you think was wrong", staff: ["admin", "owner"] },
  other: { label: "Something else", emoji: "💬", desc: "Partnerships, ideas, anything else", staff: ["mod", "admin", "owner"] },
};

const GUIDES = {
  build: { title: "Build", emoji: "🔧", noun: "loadout" },
  blueprint: { title: "Blueprint", emoji: "📘", noun: "blueprint" },
  intel: { title: "Intel", emoji: "🗺️", noun: "tip" },
};

// ── Plumbing ──────────────────────────────────────────────────────────────────

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });
const reply = (content, extra = {}) =>
  json({ type: 4, data: { content, flags: EPHEMERAL, allowed_mentions: { parse: [] }, ...extra } });
const publicReply = (data) => json({ type: 4, data: { allowed_mentions: { parse: [] }, ...data } });
const update = (data) => json({ type: 7, data: { allowed_mentions: { parse: [] }, ...data } });
const deferred = (ephemeral = true) => json({ type: 5, data: ephemeral ? { flags: EPHEMERAL } : {} });
const modal = (custom_id, title, inputs) => json({
  type: 9,
  data: { custom_id, title, components: inputs.map((i) => ({ type: 1, components: [{ type: 4, ...i }] })) },
});
const button = (custom_id, label, style = 2, extra = {}) => ({ type: 2, custom_id, label, style, ...extra });
const row = (...components) => ({ type: 1, components });
const now = () => Math.floor(Date.now() / 1000);
const clip = (s, n) => (s && s.length > n ? s.slice(0, n - 1) + "…" : s || "");
const slug = (s) => s.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);

function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

async function verifySignature(request, body, publicKey) {
  const sig = request.headers.get("X-Signature-Ed25519");
  const ts = request.headers.get("X-Signature-Timestamp");
  if (!sig || !ts || !/^[0-9a-f]{128}$/i.test(sig)) return false;
  const data = new TextEncoder().encode(ts + body);
  for (const alg of [{ name: "Ed25519" }, { name: "NODE-ED25519", namedCurve: "NODE-ED25519" }]) {
    try {
      const key = await crypto.subtle.importKey("raw", hexToBytes(publicKey), alg, false, ["verify"]);
      return await crypto.subtle.verify(alg, key, hexToBytes(sig), data);
    } catch (e) { /* try the other algorithm name */ }
  }
  return false;
}

class DiscordError extends Error {
  constructor(status, body) { super(`Discord ${status}: ${body}`); this.status = status; }
}

async function discord(env, method, path, body, { reason, form } = {}) {
  const headers = { Authorization: `Bot ${env.DISCORD_TOKEN}`, "User-Agent": "DiscordBot (mw4-dmz, 1.0)" };
  if (reason) headers["X-Audit-Log-Reason"] = encodeURIComponent(clip(reason, 400));
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers["Content-Type"] = "application/json"; payload = JSON.stringify(body); }
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await fetch(API + path, { method, headers, body: payload });
    if (res.status === 429) {
      const wait = (await res.json().catch(() => ({}))).retry_after ?? 1;
      await new Promise((r) => setTimeout(r, wait * 1000 + 100));
      continue;
    }
    if (res.status === 204) return null;
    const text = await res.text();
    if (!res.ok) throw new DiscordError(res.status, text.slice(0, 300));
    return text ? JSON.parse(text) : null;
  }
  throw new DiscordError(429, "rate limited");
}

const tryDiscord = (...args) => discord(...args).catch(() => null);

// Edit the deferred "thinking…" reply.
const finish = (env, i, content, extra = {}) =>
  discord(env, "PATCH", `/webhooks/${CONFIG.app_id}/${i.token}/messages/@original`,
    { content, allowed_mentions: { parse: [] }, ...extra });

// Run slow work after answering Discord (it wants a reply within 3 seconds).
function later(ctx, env, i, work) {
  ctx.waitUntil(work().catch((e) => finish(env, i, `⚠️ ${e.status === 403 ? "I'm missing a permission for that." : "Something went wrong."} (${clip(String(e.message), 150)})`).catch(() => {})));
  return deferred();
}

const perms = (i) => BigInt(i.member?.permissions ?? "0");
const has = (i, bit) => (perms(i) & BIT.ADMINISTRATOR) !== 0n || (perms(i) & bit) !== 0n;
const isMod = (i) => has(i, BIT.MODERATE);
const isAdmin = (i) => has(i, BIT.MANAGE_GUILD);
const STAFF_ROLES = ["mod", "developer", "admin", "owner"].map((r) => CONFIG.roles[r]);
const isStaffMember = (m) => (m?.roles || []).some((r) => STAFF_ROLES.includes(r));

function options(i) {
  const out = {};
  const walk = (opts) => (opts || []).forEach((o) => (o.options ? walk(o.options) : (out[o.name] = o)));
  walk(i.data.options);
  return Object.fromEntries(Object.entries(out).map(([k, o]) => [k, o.value]));
}
const sub = (i) => i.data.options?.find((o) => o.type === 1)?.name;
const resolvedMember = (i, id) => i.data.resolved?.members?.[id] ? { ...i.data.resolved.members[id], user: i.data.resolved.users[id] } : null;
const resolvedUser = (i, id) => i.data.resolved?.users?.[id];
const who = (u) => u ? `${u.global_name || u.username} (\`${u.username}\`)` : "unknown";

async function log(env, embed) {
  await tryDiscord(env, "POST", `/channels/${CONFIG.channels.server_logs}/messages`,
    { embeds: [{ timestamp: new Date().toISOString(), ...embed }], allowed_mentions: { parse: [] } });
}

async function dm(env, userId, payload) {
  const ch = await tryDiscord(env, "POST", "/users/@me/channels", { recipient_id: userId });
  if (!ch) return false;
  return !!(await tryDiscord(env, "POST", `/channels/${ch.id}/messages`, payload));
}

// Checks shared by every action a moderator takes on a member.
function targetProblem(i, targetUser, targetMember) {
  if (!targetUser) return "I couldn't find that user.";
  if (targetUser.id === i.member.user.id) return "You can't do that to yourself.";
  if (targetUser.bot) return "You can't do that to a bot.";
  if (targetMember && isStaffMember(targetMember) && !isAdmin(i)) return "Only Admins can act on staff.";
  return null;
}

// ── Squad finder ─────────────────────────────────────────────────────────────

const ACTIVITIES = {
  missions: "📋 Missions / contracts", loot: "💰 Looting / farming", pvp: "🔫 PvP / hunting squads",
  bosses: "💀 Bosses / strongholds", exfil: "🚁 Quick exfil runs", anything: "🎲 Anything goes",
};
const PLATFORMS = { any: "Any", pc: "PC", playstation: "PlayStation", xbox: "Xbox" };

function lfgCard({ host, size, activity, platform, mic, note, players, closed, posted }) {
  const full = players.length >= size;
  const status = closed ? "Closed" : full ? "Full, good luck out there" : `${players.length}/${size}, need ${size - players.length} more`;
  return {
    embeds: [{
      title: `${closed ? "🔒" : full ? "✅" : "🔎"} ${SIZE_NAME[size]} looking for squad`,
      color: closed ? GREY : full ? GREEN : COLOR,
      description: note ? `> ${clip(note, 300)}` : undefined,
      fields: [
        { name: "Doing", value: ACTIVITIES[activity] || activity, inline: true },
        { name: "Platform", value: PLATFORMS[platform] || platform, inline: true },
        { name: "Mic", value: mic ? "🎙️ Required" : "Optional", inline: true },
        { name: `Squad (${status})`, value: players.map((p, n) => `${n === 0 ? "👑" : "•"} <@${p}>`).join("\n") },
        { name: "Voice", value: `<#${CONFIG.squad_vc[size]}>`, inline: true },
        { name: "Posted", value: `<t:${posted}:R>`, inline: true },
      ],
      footer: { text: `lfg|${host}|${size}|${activity}|${platform}|${mic ? 1 : 0}|${posted}` },
    }],
    components: closed ? [] : [row(
      button("lfg:join", "Join", 3, { disabled: full, emoji: { name: "➕" } }),
      button("lfg:leave", "Leave", 2),
      button("lfg:close", "Close", 4),
    )],
    allowed_mentions: { parse: [] },
  };
}

function parseCard(message) {
  const e = message.embeds?.[0];
  const parts = e?.footer?.text?.split("|");
  if (!parts || parts[0] !== "lfg") return null;
  const [, host, size, activity, platform, mic, posted] = parts;
  const squadField = e.fields.find((f) => f.name.startsWith("Squad"));
  const players = [...(squadField?.value || "").matchAll(/<@!?(\d+)>/g)].map((m) => m[1]);
  return {
    host, size: Number(size), activity, platform, mic: mic === "1", posted: Number(posted),
    note: e.description ? e.description.replace(/^> /, "") : "", players, closed: e.title.startsWith("🔒"),
  };
}

async function cmdLfg(i, env, ctx) {
  const o = options(i);
  const size = SIZES[o.size];
  const userId = i.member.user.id;
  const key = `lfgby:${userId}`;
  const existing = await env.KV?.get(key);
  if (existing) {
    const [chan, msg] = existing.split("/");
    const live = await tryDiscord(env, "GET", `/channels/${chan}/messages/${msg}`);
    const card = live && parseCard(live);
    if (card && !card.closed) {
      return reply(`You already have an open squad card: https://discord.com/channels/${CONFIG.guild_id}/${chan}/${msg}\nClose it first, or wait for it to fill.`);
    }
  }
  return later(ctx, env, i, async () => {
    const card = {
      host: userId, size, activity: o.activity || "anything", platform: o.platform || "any",
      mic: !!o.mic, note: o.note, players: [userId], closed: false, posted: now(),
    };
    const msg = await discord(env, "POST", `/channels/${CONFIG.channels.find_a_squad}/messages`, lfgCard(card));
    const ttl = CONFIG.lfg_max_age_hours * 3600 + 7200;
    await env.KV?.put(key, `${msg.channel_id}/${msg.id}`, { expirationTtl: ttl });
    await env.KV?.put(`lfg:${msg.id}`, msg.channel_id, { expirationTtl: ttl, metadata: { posted: card.posted } });
    await finish(env, i, `Your squad card is up: https://discord.com/channels/${CONFIG.guild_id}/${msg.channel_id}/${msg.id}`);
  });
}

async function lfgButton(i, env, ctx, action) {
  const card = parseCard(i.message);
  if (!card) return reply("This card is too old to use.");
  const me = i.member.user.id;
  if (action === "join") {
    if (card.closed) return reply("This squad is closed.");
    if (card.players.includes(me)) return reply("You're already in this squad.");
    if (card.players.length >= card.size) return reply("This squad is full.");
    card.players.push(me);
    const full = card.players.length >= card.size;
    if (full) {
      ctx.waitUntil(Promise.all([
        tryDiscord(env, "POST", `/channels/${i.channel_id}/messages`, {
          content: `🪂 ${card.players.map((p) => `<@${p}>`).join(" ")} your ${SIZE_NAME[card.size].toLowerCase()} is full! Jump in <#${CONFIG.squad_vc[card.size]}> and send invites.`,
          message_reference: { message_id: i.message.id, fail_if_not_exists: false },
          allowed_mentions: { users: card.players },
        }),
        env.KV?.delete(`lfg:${i.message.id}`),
        env.KV?.delete(`lfgby:${card.host}`),
      ]));
    }
    return update(lfgCard(card));
  }
  if (action === "leave") {
    if (!card.players.includes(me)) return reply("You're not in this squad.");
    if (me === card.host) return reply("You're the host. Press **Close** instead.");
    card.players = card.players.filter((p) => p !== me);
    return update(lfgCard(card));
  }
  if (action === "close") {
    if (me !== card.host && !isMod(i)) return reply("Only the host or a mod can close this card.");
    card.closed = true;
    ctx.waitUntil(Promise.all([env.KV?.delete(`lfg:${i.message.id}`), env.KV?.delete(`lfgby:${card.host}`)]));
    return update(lfgCard(card));
  }
  return reply("Unknown button.");
}

async function closeStaleLfg(env) {
  if (!env.KV) return;
  const cutoff = now() - CONFIG.lfg_max_age_hours * 3600;
  let cursor;
  do {
    const page = await env.KV.list({ prefix: "lfg:", cursor });
    for (const k of page.keys) {
      if ((k.metadata?.posted ?? 0) > cutoff) continue;
      const msgId = k.name.slice(4);
      const chan = await env.KV.get(k.name);
      const msg = chan && await tryDiscord(env, "GET", `/channels/${chan}/messages/${msgId}`);
      const card = msg && parseCard(msg);
      if (card && !card.closed) {
        card.closed = true;
        await tryDiscord(env, "PATCH", `/channels/${chan}/messages/${msgId}`, lfgCard(card));
        await env.KV.delete(`lfgby:${card.host}`);
      }
      await env.KV.delete(k.name);
    }
    cursor = page.list_complete ? null : page.cursor;
  } while (cursor);
}

// ── Tickets ──────────────────────────────────────────────────────────────────

const ticketMenu = () => row({
  type: 3, custom_id: "ticket:type", placeholder: "What do you need help with?",
  options: Object.entries(TICKETS).map(([value, t]) => ({ value, label: t.label, description: t.desc, emoji: { name: t.emoji } })),
});

const ticketPanel = () => ({
  embeds: [{
    title: "🎫 Need a hand? Open a ticket",
    color: COLOR,
    description: "Pick an option below and fill in the short form. You'll get a private channel with the staff team.\n\n" +
      Object.values(TICKETS).map((t) => `${t.emoji} **${t.label}**: ${t.desc}`).join("\n") +
      "\n\nOne open ticket at a time, please. Don't open tickets just to test.",
  }],
  components: [ticketMenu()],
});

function ticketForm(type) {
  const t = TICKETS[type];
  const inputs = type === "report"
    ? [
      { custom_id: "player", label: "Who are you reporting? (name or ID)", style: 1, max_length: 100, required: true },
      { custom_id: "details", label: "What happened?", style: 2, max_length: 1500, required: true },
      { custom_id: "evidence", label: "Evidence links (clips, screenshots)", style: 2, max_length: 500, required: false },
    ]
    : type === "appeal"
      ? [
        { custom_id: "punishment", label: "What punishment, and when?", style: 1, max_length: 150, required: true },
        { custom_id: "details", label: "Why should it be lifted?", style: 2, max_length: 1500, required: true },
      ]
      : [
        { custom_id: "subject", label: "Subject", style: 1, max_length: 100, required: true },
        { custom_id: "details", label: "Details", style: 2, max_length: 1500, required: true },
      ];
  return modal(`ticket:form:${type}`, `${t.emoji} ${t.label}`, inputs);
}

const modalValues = (i) => Object.fromEntries(
  i.data.components.flatMap((r) => r.components).map((c) => [c.custom_id, (c.value || "").trim()]));

async function openTicket(i, env, ctx, type) {
  const t = TICKETS[type];
  if (!t) return reply("Unknown ticket type.");
  const user = i.member.user;
  const openKey = `ticket:open:${user.id}`;
  const open = await env.KV?.get(openKey);
  if (open && await tryDiscord(env, "GET", `/channels/${open}`)) return reply(`You already have an open ticket: <#${open}>`);
  const cooldown = await env.KV?.get(`ticket:cool:${user.id}`);
  if (cooldown && !isMod(i)) return reply(`Please wait a few minutes before opening another ticket.`);
  const values = modalValues(i);
  return later(ctx, env, i, async () => {
    const n = Number((await env.KV?.get("ticket:count")) || 0) + 1;
    await env.KV?.put("ticket:count", String(n));
    const number = String(n).padStart(4, "0");
    const staffRoles = t.staff.map((r) => CONFIG.roles[r]);
    const channel = await discord(env, "POST", `/guilds/${CONFIG.guild_id}/channels`, {
      name: `${type}-${number}-${slug(user.username)}`.slice(0, 90),
      type: 0,
      parent_id: CONFIG.channels.tickets_category,
      topic: `ticket|${user.id}|${type}|${number}`,
      permission_overwrites: [
        { id: CONFIG.guild_id, type: 0, allow: "0", deny: String(BIT.VIEW) },
        { id: user.id, type: 1, allow: String(TALK), deny: "0" },
        { id: CONFIG.app_id, type: 1, allow: String(TALK | BIT.MANAGE_CHANNELS | BIT.MANAGE_MESSAGES | BIT.MANAGE_ROLES), deny: "0" },
        ...staffRoles.map((id) => ({ id, type: 0, allow: String(TALK | BIT.MANAGE_MESSAGES), deny: "0" })),
        // Mods can read tickets they aren't on, but not appeals.
        ...(!staffRoles.includes(CONFIG.roles.mod) ? [{ id: CONFIG.roles.mod, type: 0, allow: "0", deny: String(BIT.VIEW) }] : []),
      ],
    }, { reason: `Ticket #${number} for ${user.username}` });
    await env.KV?.put(openKey, channel.id);
    await env.KV?.put(`ticket:cool:${user.id}`, "1", { expirationTtl: Math.max(60, CONFIG.ticket_cooldown_seconds) });
    const fields = Object.entries(values).filter(([, v]) => v).map(([k, v]) => ({
      name: { player: "Reported player", details: "Details", evidence: "Evidence", punishment: "Punishment", subject: "Subject" }[k] || k,
      value: clip(v, 1024),
    }));
    await discord(env, "POST", `/channels/${channel.id}/messages`, {
      content: `${t.emoji} <@${user.id}> opened a ticket. ${staffRoles.filter((r) => r !== CONFIG.roles.owner).map((r) => `<@&${r}>`).join(" ")}`,
      embeds: [{
        title: `Ticket #${number}: ${t.label}`, color: COLOR, fields,
        footer: { text: "Staff will be with you soon. Press Close when you're done." },
      }],
      components: [row(button("ticket:claim", "Claim", 1, { emoji: { name: "🙋" } }), button("ticket:close", "Close", 4, { emoji: { name: "🔒" } }))],
      allowed_mentions: { users: [user.id], roles: staffRoles.filter((r) => r !== CONFIG.roles.owner) },
    });
    await finish(env, i, `Your ticket is open: <#${channel.id}>`);
  });
}

const ticketInfo = (channel) => {
  const p = channel?.topic?.split("|");
  return p && p[0] === "ticket" ? { owner: p[1], type: p[2], number: p[3] } : null;
};

async function transcript(env, channelId) {
  const all = [];
  let before;
  for (let page = 0; page < 10; page++) {
    const batch = await discord(env, "GET", `/channels/${channelId}/messages?limit=100${before ? `&before=${before}` : ""}`);
    all.push(...batch);
    if (batch.length < 100) break;
    before = batch[batch.length - 1].id;
  }
  return all.reverse().map((m) => {
    const when = new Date(m.timestamp).toISOString().replace("T", " ").slice(0, 16);
    const extras = [
      ...m.embeds.map((e) => `[embed] ${e.title || ""} ${e.description || ""} ${(e.fields || []).map((f) => `${f.name}: ${f.value}`).join(" | ")}`),
      ...m.attachments.map((a) => `[file] ${a.url}`),
    ];
    return `[${when}] ${m.author.username}${m.author.bot ? " [bot]" : ""}: ${m.content}${extras.length ? "\n    " + extras.join("\n    ") : ""}`;
  }).join("\n");
}

async function ticketButton(i, env, ctx, action) {
  const channel = await tryDiscord(env, "GET", `/channels/${i.channel_id}`);
  const info = ticketInfo(channel);
  if (!info) return reply("This isn't a ticket channel.");
  const me = i.member.user;
  const staff = isMod(i) || isStaffMember(i.member);
  if (action === "claim") {
    if (!staff) return reply("Only staff can claim tickets.");
    const embed = { ...i.message.embeds[0], footer: { text: `Claimed by ${me.global_name || me.username}` }, color: BLUE };
    const components = [row(button("ticket:claim", `Claimed by ${clip(me.global_name || me.username, 60)}`, 2, { disabled: true }), button("ticket:close", "Close", 4, { emoji: { name: "🔒" } }))];
    ctx.waitUntil(tryDiscord(env, "POST", `/channels/${i.channel_id}/messages`, { content: `🙋 <@${me.id}> is handling this ticket.`, allowed_mentions: { parse: [] } }));
    return update({ embeds: [embed], components });
  }
  if (action === "close") {
    if (!staff && me.id !== info.owner) return reply("Only the ticket owner or staff can close this.");
    return later(ctx, env, i, async () => {
      await finish(env, i, "Saving the transcript and closing…");
      const text = await transcript(env, i.channel_id);
      const header = `Ticket #${info.number} (${info.type}) opened by ${info.owner}, closed by ${me.username} (${me.id})\n\n`;
      const file = new Blob([header + text], { type: "text/plain" });
      const embed = {
        title: `🎫 Ticket #${info.number} closed`, color: GREY,
        fields: [
          { name: "Type", value: TICKETS[info.type]?.label || info.type, inline: true },
          { name: "Opened by", value: `<@${info.owner}>`, inline: true },
          { name: "Closed by", value: `<@${me.id}>`, inline: true },
        ],
        timestamp: new Date().toISOString(),
      };
      const form = () => {
        const f = new FormData();
        f.append("payload_json", JSON.stringify({ embeds: [embed], allowed_mentions: { parse: [] }, attachments: [{ id: 0, filename: `ticket-${info.number}.txt` }] }));
        f.append("files[0]", file, `ticket-${info.number}.txt`);
        return f;
      };
      await discord(env, "POST", `/channels/${CONFIG.channels.ticket_logs}/messages`, undefined, { form: form() });
      const dmCh = await tryDiscord(env, "POST", "/users/@me/channels", { recipient_id: info.owner });
      if (dmCh) await tryDiscord(env, "POST", `/channels/${dmCh.id}/messages`, undefined, { form: form() });
      await env.KV?.delete(`ticket:open:${info.owner}`);
      await discord(env, "DELETE", `/channels/${i.channel_id}`, undefined, { reason: `Ticket #${info.number} closed by ${me.username}` });
    });
  }
  return reply("Unknown button.");
}

async function cmdTicketAddRemove(i, env, ctx, add) {
  if (!isMod(i)) return reply("Only staff can do that.");
  const channel = await tryDiscord(env, "GET", `/channels/${i.channel_id}`);
  if (!ticketInfo(channel)) return reply("Use this inside a ticket channel.");
  const { user } = options(i);
  if (add) await discord(env, "PUT", `/channels/${i.channel_id}/permissions/${user}`, { type: 1, allow: String(TALK), deny: "0" });
  else await discord(env, "DELETE", `/channels/${i.channel_id}/permissions/${user}`);
  return publicReply({ content: `${add ? "➕ Added" : "➖ Removed"} <@${user}> ${add ? "to" : "from"} this ticket.`, allowed_mentions: { users: add ? [user] : [] } });
}

// ── Moderation ───────────────────────────────────────────────────────────────

const DURATIONS = { "5m": 300, "10m": 600, "30m": 1800, "1h": 3600, "6h": 21600, "12h": 43200, "1d": 86400, "3d": 259200, "1w": 604800 };
const DURATION_TEXT = { "5m": "5 minutes", "10m": "10 minutes", "30m": "30 minutes", "1h": "1 hour", "6h": "6 hours", "12h": "12 hours", "1d": "1 day", "3d": "3 days", "1w": "1 week" };

async function getWarnings(env, id) { return JSON.parse((await env.KV?.get(`warn:${id}`)) || "[]"); }

async function cmdWarn(i, env, ctx) {
  const { user: id, reason } = options(i);
  const target = resolvedUser(i, id);
  const problem = targetProblem(i, target, resolvedMember(i, id));
  if (problem) return reply(problem);
  return later(ctx, env, i, async () => {
    const list = await getWarnings(env, id);
    list.push({ reason, by: i.member.user.id, at: now() });
    await env.KV?.put(`warn:${id}`, JSON.stringify(list.slice(-50)));
    const dmd = await dm(env, id, { embeds: [{ title: `⚠️ You were warned in ${CONFIG.name}`, color: ORANGE, description: reason, footer: { text: `Warning ${list.length}. You can appeal with a ticket.` } }] });
    await log(env, { title: "⚠️ Member warned", color: ORANGE, fields: [
      { name: "Member", value: `<@${id}> ${who(target)}`, inline: true }, { name: "By", value: `<@${i.member.user.id}>`, inline: true },
      { name: "Total warnings", value: String(list.length), inline: true }, { name: "Reason", value: clip(reason, 1024) }] });
    await finish(env, i, `Warned ${who(target)}. That's warning **${list.length}**.${dmd ? "" : " (Their DMs are closed, so they weren't told.)"}`);
  });
}

async function cmdWarnings(i, env, ctx) {
  const { user: id, clear } = options(i);
  const target = resolvedUser(i, id);
  if (clear) {
    if (!isAdmin(i)) return reply("Only Admins can clear warnings.");
    await env.KV?.delete(`warn:${id}`);
    await log(env, { title: "🧹 Warnings cleared", color: GREY, fields: [{ name: "Member", value: `<@${id}>`, inline: true }, { name: "By", value: `<@${i.member.user.id}>`, inline: true }] });
    return reply(`Cleared all warnings for ${who(target)}.`);
  }
  const list = await getWarnings(env, id);
  if (!list.length) return reply(`${who(target)} has no warnings.`);
  return reply("", { embeds: [{ title: `Warnings for ${target?.username || id} (${list.length})`, color: ORANGE,
    description: list.slice(-15).map((w, n) => `**${n + 1}.** <t:${w.at}:d> by <@${w.by}>: ${clip(w.reason, 180)}`).join("\n") }] });
}

async function cmdTimeout(i, env, ctx) {
  const { user: id, duration, reason = "No reason given" } = options(i);
  const target = resolvedUser(i, id);
  const problem = targetProblem(i, target, resolvedMember(i, id));
  if (problem) return reply(problem);
  return later(ctx, env, i, async () => {
    const remove = duration === "off";
    const until = remove ? null : new Date(Date.now() + DURATIONS[duration] * 1000).toISOString();
    await discord(env, "PATCH", `/guilds/${CONFIG.guild_id}/members/${id}`, { communication_disabled_until: until }, { reason: `${i.member.user.username}: ${reason}` });
    if (!remove) await dm(env, id, { embeds: [{ title: `⏳ You were timed out in ${CONFIG.name}`, color: ORANGE, description: `**${DURATION_TEXT[duration]}**: ${reason}` }] });
    await log(env, { title: remove ? "✅ Timeout removed" : "⏳ Member timed out", color: remove ? GREEN : ORANGE, fields: [
      { name: "Member", value: `<@${id}> ${who(target)}`, inline: true }, { name: "By", value: `<@${i.member.user.id}>`, inline: true },
      ...(remove ? [] : [{ name: "Length", value: DURATION_TEXT[duration], inline: true }]), { name: "Reason", value: clip(reason, 1024) }] });
    await finish(env, i, remove ? `Removed the timeout on ${who(target)}.` : `Timed out ${who(target)} for ${DURATION_TEXT[duration]}.`);
  });
}

async function cmdKickBan(i, env, ctx, ban) {
  if (ban && !has(i, 1n << 2n)) return reply("You need the Ban Members permission.");
  const { user: id, reason = "No reason given", delete_messages = "0" } = options(i);
  const target = resolvedUser(i, id);
  const member = resolvedMember(i, id);
  const problem = targetProblem(i, target, member);
  if (problem) return reply(problem);
  if (!ban && !member) return reply("They aren't in the server.");
  return later(ctx, env, i, async () => {
    await dm(env, id, { embeds: [{ title: `${ban ? "🔨 You were banned from" : "👢 You were kicked from"} ${CONFIG.name}`, color: RED, description: reason }] });
    if (ban) await discord(env, "PUT", `/guilds/${CONFIG.guild_id}/bans/${id}`, { delete_message_seconds: Number(delete_messages) }, { reason: `${i.member.user.username}: ${reason}` });
    else await discord(env, "DELETE", `/guilds/${CONFIG.guild_id}/members/${id}`, undefined, { reason: `${i.member.user.username}: ${reason}` });
    await log(env, { title: ban ? "🔨 Member banned" : "👢 Member kicked", color: RED, fields: [
      { name: "Member", value: `<@${id}> ${who(target)}`, inline: true }, { name: "By", value: `<@${i.member.user.id}>`, inline: true },
      { name: "Reason", value: clip(reason, 1024) }] });
    await finish(env, i, `${ban ? "Banned" : "Kicked"} ${who(target)}.`);
  });
}

async function cmdUnban(i, env, ctx) {
  if (!has(i, 1n << 2n)) return reply("You need the Ban Members permission.");
  const { user_id: id, reason = "No reason given" } = options(i);
  if (!/^\d{15,21}$/.test(id)) return reply("That isn't a user ID.");
  return later(ctx, env, i, async () => {
    try { await discord(env, "DELETE", `/guilds/${CONFIG.guild_id}/bans/${id}`, undefined, { reason: `${i.member.user.username}: ${reason}` }); }
    catch (e) { if (e.status === 404) return finish(env, i, "That user isn't banned."); throw e; }
    await log(env, { title: "♻️ Member unbanned", color: GREEN, fields: [{ name: "User", value: `<@${id}> (\`${id}\`)`, inline: true }, { name: "By", value: `<@${i.member.user.id}>`, inline: true }, { name: "Reason", value: clip(reason, 1024) }] });
    await finish(env, i, `Unbanned \`${id}\`.`);
  });
}

async function cmdPurge(i, env, ctx) {
  const { count, user } = options(i);
  return later(ctx, env, i, async () => {
    const msgs = await discord(env, "GET", `/channels/${i.channel_id}/messages?limit=100`);
    const twoWeeks = Date.now() - 13.9 * 86400 * 1000;
    const ids = msgs.filter((m) => (!user || m.author.id === user) && Date.parse(m.timestamp) > twoWeeks && !m.pinned)
      .slice(0, count).map((m) => m.id);
    if (!ids.length) return finish(env, i, "Nothing to delete (messages older than 2 weeks can't be bulk-deleted).");
    if (ids.length === 1) await discord(env, "DELETE", `/channels/${i.channel_id}/messages/${ids[0]}`);
    else await discord(env, "POST", `/channels/${i.channel_id}/messages/bulk-delete`, { messages: ids }, { reason: `Purge by ${i.member.user.username}` });
    await log(env, { title: "🧹 Messages purged", color: GREY, fields: [{ name: "Channel", value: `<#${i.channel_id}>`, inline: true }, { name: "Count", value: String(ids.length), inline: true },
      { name: "By", value: `<@${i.member.user.id}>`, inline: true }, ...(user ? [{ name: "Only from", value: `<@${user}>`, inline: true }] : [])] });
    await finish(env, i, `Deleted ${ids.length} message${ids.length === 1 ? "" : "s"}.`);
  });
}

async function cmdSlowmode(i, env, ctx) {
  const { seconds, channel = i.channel_id } = options(i);
  await discord(env, "PATCH", `/channels/${channel}`, { rate_limit_per_user: seconds }, { reason: `Slowmode by ${i.member.user.username}` });
  ctx.waitUntil(log(env, { title: "🐢 Slowmode changed", color: GREY, fields: [{ name: "Channel", value: `<#${channel}>`, inline: true }, { name: "Delay", value: seconds ? `${seconds}s` : "off", inline: true }, { name: "By", value: `<@${i.member.user.id}>`, inline: true }] }));
  return reply(seconds ? `Slowmode in <#${channel}> is now ${seconds} seconds.` : `Slowmode is off in <#${channel}>.`);
}

async function cmdUserinfo(i, env) {
  const { user: id } = options(i);
  const u = resolvedUser(i, id);
  const m = resolvedMember(i, id);
  const created = Number((BigInt(id) >> 22n) + 1420070400000n) / 1000 | 0;
  const warns = await getWarnings(env, id);
  const fields = [
    { name: "Account created", value: `<t:${created}:D> (<t:${created}:R>)`, inline: true },
    ...(m ? [{ name: "Joined", value: `<t:${Date.parse(m.joined_at) / 1000 | 0}:R>`, inline: true }] : [{ name: "Joined", value: "Not in the server", inline: true }]),
    { name: "Warnings", value: String(warns.length), inline: true },
    ...(m?.roles?.length ? [{ name: "Roles", value: clip(m.roles.map((r) => `<@&${r}>`).join(" "), 1024) }] : []),
    ...(m?.communication_disabled_until && Date.parse(m.communication_disabled_until) > Date.now() ? [{ name: "Timed out until", value: `<t:${Date.parse(m.communication_disabled_until) / 1000 | 0}:f>` }] : []),
  ];
  return reply("", { embeds: [{ title: who(u), color: COLOR, thumbnail: u?.avatar ? { url: `https://cdn.discordapp.com/avatars/${id}/${u.avatar}.png` } : undefined, fields, footer: { text: `ID ${id}` } }] });
}

// ── DMZ guides ───────────────────────────────────────────────────────────────

async function guideIndex(env, kind) { return JSON.parse((await env.KV?.get(`guide:${kind}`)) || "[]"); }

async function cmdGuide(i, env, kind) {
  const { name } = options(i);
  const g = GUIDES[kind];
  if (!name) {
    const list = await guideIndex(env, kind);
    if (!list.length) return reply(`No ${g.noun}s saved yet. Staff can add one with \`/guide-add\`.`);
    return reply("", { embeds: [{ title: `${g.emoji} Saved ${g.noun}s (${list.length})`, color: COLOR,
      description: clip(list.map((e) => `• **${e.title}**`).join("\n"), 4000), footer: { text: `Use /${kind} name:<${g.noun}> to show one` } }] });
  }
  const entry = JSON.parse((await env.KV?.get(`guide:${kind}:${slug(name)}`)) || "null");
  if (!entry) return reply(`No ${g.noun} called **${clip(name, 80)}**. Try \`/${kind}\` with no name to see the list.`);
  return publicReply({ embeds: [{
    title: `${g.emoji} ${entry.title}`, color: COLOR, description: clip(entry.body, 4000),
    image: entry.image ? { url: entry.image } : undefined,
    footer: { text: `${g.title} · added by ${entry.author}` }, timestamp: new Date(entry.updated * 1000).toISOString(),
  }] });
}

async function guideAutocomplete(i, env, kind) {
  const focused = (i.data.options || []).find((o) => o.focused)?.value?.toLowerCase() || "";
  const list = await guideIndex(env, kind);
  return json({ type: 8, data: { choices: list.filter((e) => e.title.toLowerCase().includes(focused)).slice(0, 25).map((e) => ({ name: clip(e.title, 100), value: e.slug })) } });
}

function cmdGuideAdd(i) {
  const { type } = options(i);
  const g = GUIDES[type];
  return modal(`guide:save:${type}`, `Save a ${g.noun}`, [
    { custom_id: "title", label: `${g.title} name`, style: 1, max_length: 80, required: true, placeholder: type === "build" ? "e.g. Stronghold breach SMG" : type === "blueprint" ? "e.g. Blueprint name and weapon" : "e.g. Best exfil on the north side" },
    { custom_id: "body", label: type === "intel" ? "The tip" : "Attachments, perks, how to get it", style: 2, max_length: 3500, required: true },
    { custom_id: "image", label: "Image URL (optional)", style: 1, max_length: 400, required: false, placeholder: "https://…" },
  ]);
}

async function guideSave(i, env, kind) {
  if (!isMod(i)) return reply("Only staff can save guides.");
  const v = modalValues(i);
  const s = slug(v.title);
  if (!s) return reply("That name needs some letters or numbers.");
  const image = /^https:\/\/\S+$/.test(v.image) ? v.image : null;
  const entry = { title: v.title, body: v.body, image, author: i.member.user.username, updated: now() };
  await env.KV?.put(`guide:${kind}:${s}`, JSON.stringify(entry));
  const list = (await guideIndex(env, kind)).filter((e) => e.slug !== s);
  list.push({ slug: s, title: v.title });
  list.sort((a, b) => a.title.localeCompare(b.title));
  await env.KV?.put(`guide:${kind}`, JSON.stringify(list));
  return reply(`Saved **${v.title}**. Anyone can show it with \`/${kind} name:${v.title}\`.`);
}

async function cmdGuideRemove(i, env) {
  const { type, name } = options(i);
  const s = slug(name);
  const list = await guideIndex(env, type);
  if (!list.some((e) => e.slug === s)) return reply("I couldn't find that one.");
  await env.KV?.delete(`guide:${type}:${s}`);
  await env.KV?.put(`guide:${type}`, JSON.stringify(list.filter((e) => e.slug !== s)));
  return reply(`Removed it.`);
}

async function guideRemoveAutocomplete(i, env) {
  const o = Object.fromEntries((i.data.options || []).map((x) => [x.name, x]));
  const kind = o.type?.value;
  if (!GUIDES[kind]) return json({ type: 8, data: { choices: [] } });
  return guideAutocomplete({ data: { options: [o.name] } }, env, kind);
}

// ── Help and panels ──────────────────────────────────────────────────────────

function cmdHelp(i) {
  const lines = [
    "**Squads**",
    "`/lfg`: post a squad card in <#" + CONFIG.channels.find_a_squad + ">. People press Join; when it's full everyone gets pinged.",
    "",
    "**DMZ guides**",
    "`/build`, `/blueprint`, `/intel`: show a saved loadout, blueprint or tip. Leave the name empty to list them.",
    "",
    "**Help**",
    "`/ticket`: open a private ticket with staff. You can also right-click a message → Apps → **Report message**.",
  ];
  if (isMod(i)) lines.push("", "**Staff**",
    "`/warn` `/warnings` `/timeout` `/kick` `/ban` `/unban` `/purge` `/slowmode` `/userinfo`",
    "`/ticket-add` `/ticket-remove` inside a ticket · `/guide-add` `/guide-remove` · `/panel` (Admins)");
  return reply("", { embeds: [{ title: `🪖 ${CONFIG.name} bot`, color: COLOR, description: lines.join("\n") }] });
}

async function cmdPanel(i, env, ctx) {
  if (!isAdmin(i)) return reply("Only Admins can post panels.");
  const { which } = options(i);
  return later(ctx, env, i, async () => {
    if (which === "tickets") {
      await discord(env, "POST", `/channels/${CONFIG.channels.report_issues}/messages`, ticketPanel());
      return finish(env, i, `Posted the ticket panel in <#${CONFIG.channels.report_issues}>.`);
    }
    await discord(env, "POST", `/channels/${CONFIG.channels.find_a_squad}/messages`, {
      embeds: [{ title: "🔎 Find a squad", color: COLOR,
        description: "Type **/lfg** to post a squad card. Pick Duo, Trio or Squad, what you want to do and your platform.\n\n" +
          "Others press **Join**. When the squad is full, everyone gets pinged with the voice channel to jump in.\n" +
          "Cards close on their own after a few hours. Please keep chat in this channel about finding squads." }],
    });
    return finish(env, i, `Posted the squad finder intro in <#${CONFIG.channels.find_a_squad}>.`);
  });
}

async function reportMessage(i, env, ctx) {
  const msg = i.data.resolved.messages[i.data.target_id];
  const link = `https://discord.com/channels/${CONFIG.guild_id}/${msg.channel_id}/${msg.id}`;
  return modal(`ticket:form:report`, "🚨 Report a message", [
    { custom_id: "player", label: "Reported player", style: 1, max_length: 100, required: true, value: clip(`${msg.author.username} (${msg.author.id})`, 100) },
    { custom_id: "details", label: "What's wrong with it?", style: 2, max_length: 1500, required: true },
    { custom_id: "evidence", label: "Message (filled in for you)", style: 2, max_length: 500, required: false, value: clip(`${link}\n"${msg.content}"`, 500) },
  ]);
}

// ── Router ───────────────────────────────────────────────────────────────────

const MOD_ONLY = new Set(["warn", "warnings", "timeout", "kick", "ban", "unban", "purge", "slowmode", "userinfo", "guide-add", "guide-remove"]);

async function handle(i, env, ctx) {
  if (i.type === 1) return json({ type: 1 });
  if (i.guild_id !== CONFIG.guild_id) return reply("This bot only works in The Hajin Exclusion Zone.");
  const name = i.data?.name;

  if (i.type === 4) {
    if (GUIDES[name]) return guideAutocomplete(i, env, name);
    if (name === "guide-remove") return guideRemoveAutocomplete(i, env);
    return json({ type: 8, data: { choices: [] } });
  }

  if (i.type === 2) {
    if (MOD_ONLY.has(name) && !isMod(i)) return reply("That's a staff command.");
    switch (name) {
      case "lfg": return cmdLfg(i, env, ctx);
      case "build": case "blueprint": case "intel": return cmdGuide(i, env, name);
      case "guide-add": return cmdGuideAdd(i);
      case "guide-remove": return cmdGuideRemove(i, env);
      case "ticket": return reply("What do you need help with?", { components: [ticketMenu()] });
      case "ticket-add": return cmdTicketAddRemove(i, env, ctx, true);
      case "ticket-remove": return cmdTicketAddRemove(i, env, ctx, false);
      case "warn": return cmdWarn(i, env, ctx);
      case "warnings": return cmdWarnings(i, env, ctx);
      case "timeout": return cmdTimeout(i, env, ctx);
      case "kick": return cmdKickBan(i, env, ctx, false);
      case "ban": return cmdKickBan(i, env, ctx, true);
      case "unban": return cmdUnban(i, env, ctx);
      case "purge": return cmdPurge(i, env, ctx);
      case "slowmode": return cmdSlowmode(i, env, ctx);
      case "userinfo": return cmdUserinfo(i, env);
      case "panel": return cmdPanel(i, env, ctx);
      case "help": return cmdHelp(i);
      case "Report message": return reportMessage(i, env, ctx);
    }
    return reply("Unknown command.");
  }

  if (i.type === 3) {
    const id = i.data.custom_id;
    if (id.startsWith("lfg:")) return lfgButton(i, env, ctx, id.slice(4));
    if (id === "ticket:type") return ticketForm(i.data.values[0]);
    if (id.startsWith("ticket:")) return ticketButton(i, env, ctx, id.slice(7));
    return reply("Unknown button.");
  }

  if (i.type === 5) {
    const id = i.data.custom_id;
    if (id.startsWith("ticket:form:")) return openTicket(i, env, ctx, id.slice(12));
    if (id.startsWith("guide:save:")) return guideSave(i, env, id.slice(11));
  }
  return reply("I don't know how to handle that.");
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      return new Response(`${CONFIG.name} bot is running.`, { headers: { "content-type": "text/plain" } });
    }
    if (request.method !== "POST" || url.pathname !== "/interactions") return new Response("Not found", { status: 404 });
    const body = await request.text();
    if (!(await verifySignature(request, body, env.PUBLIC_KEY || CONFIG.public_key))) {
      return new Response("Bad signature", { status: 401 });
    }
    try {
      return await handle(JSON.parse(body), env, ctx);
    } catch (e) {
      console.error(e);
      return reply(`⚠️ ${e.status === 403 ? "I'm missing a permission for that." : "Something went wrong."}`);
    }
  },
  async scheduled(event, env, ctx) {
    ctx.waitUntil(closeStaleLfg(env));
  },
};

export { handle, parseCard, lfgCard, CONFIG };
