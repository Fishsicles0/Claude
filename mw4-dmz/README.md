# MW4 DMZ bot (The Hajin Exclusion Zone)

A Discord bot that runs on Cloudflare Workers. Discord calls the worker for every slash command, button
press and form, so nothing has to stay online.

| Feature | Commands |
|---|---|
| Squad finder | `/lfg` posts a card in #find-a-squad with Join / Leave / Close. When it's full, everyone is pinged with the Duo/Trio/Squad voice channel. Cards close after 3 hours. |
| Tickets | `/panel tickets` posts a dropdown in #report-issues. Each ticket gets a private channel under TICKETS ONLY. Staff can Claim it, and either side can Close it, which saves a transcript to #ticket-logs and DMs it to the member. `/ticket`, plus right-click a message → Apps → **Report message**. Appeals are Admin/Owner only. |
| Mod tools | `/warn` `/warnings` `/timeout` `/kick` `/ban` `/unban` `/purge` `/slowmode` `/userinfo` `/ticket-add` `/ticket-remove`, each logged to #server-logs. Mods can't act on staff; Admins can. |
| DMZ guides | `/build` `/blueprint` `/intel` show saved guides (with autocomplete). Staff add them with `/guide-add` and delete them with `/guide-remove`. |

`secure_server.py` is the one-off tidy-up. It sets verification to Medium, turns on the explicit-media filter,
gives Mods access to the Staff Area and read access to the logs, and adds AutoMod rules that alert in #server-logs.

## Deploy

```bash
cd mw4-dmz/worker
npx wrangler kv namespace create KV         # put the id in wrangler.toml
npx wrangler secret put DISCORD_TOKEN       # the MW4 DMZ bot token
npx wrangler deploy                         # prints https://mw4-dmz-bot.<you>.workers.dev
cd ..
python register.py endpoint https://mw4-dmz-bot.<you>.workers.dev
python register.py commands
```
Then run `/panel tickets` and `/panel lfg` in Discord.

Message logs (deletes, edits, joins, leaves) need a bot that is always connected. Turn on Koya's logging and point it at #server-logs.
