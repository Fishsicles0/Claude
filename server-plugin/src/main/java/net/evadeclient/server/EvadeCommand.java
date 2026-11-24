/*
 * Evade Server API - Copyright (c) 2026 Evade Client contributors
 * SPDX-License-Identifier: MIT (see LICENSE)
 */
package net.evadeclient.server;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /evade list | info | waypoint | notify | reload | cosmetic grant. */
final class EvadeCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX = "§8[§bEvade§8] §7";
    private static final List<String> SUBCOMMANDS = List.of("list", "info", "waypoint", "notify", "reload", "cosmetic");

    private final EvadePlugin plugin;

    EvadeCommand(EvadePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                if (!allowed(sender, "evade.command.list")) return true;
                Map<Player, EvadeClientInfo> players = plugin.snapshot();
                sender.sendMessage(PREFIX + "Evade players online: §f" + players.size());
                players.forEach((p, i) -> sender.sendMessage("§8 - §f" + p.getName() + " §7v" + i.version() + " (API " + i.api() + ")"));
                long legacy = plugin.getServer().getOnlinePlayers().stream().filter(plugin::isLegacy).count();
                if (legacy > 0) sender.sendMessage("§8 + §7" + legacy + " on an older Evade Client (rules only)");
            }
            case "info" -> {
                if (!allowed(sender, "evade.command.list")) return true;
                Player target = target(sender, args, 1);
                if (target == null) return true;
                EvadeClientInfo i = plugin.info(target);
                if (i == null) {
                    sender.sendMessage(PREFIX + target.getName() + (plugin.isLegacy(target) ? " uses an older Evade Client (rules only)." : " is not using Evade Client."));
                } else {
                    sender.sendMessage(PREFIX + "§f" + target.getName() + "§7: Evade Client §f" + i.version() + "§7, API §f" + i.api());
                    sender.sendMessage(PREFIX + "Features: §f" + (i.features().isEmpty() ? "-" : String.join(", ", i.features())));
                }
            }
            case "waypoint" -> {
                if (!allowed(sender, "evade.command.test")) return true;
                Player target = target(sender, args, 1);
                if (target == null || !requireEvade(sender, target)) return true;
                Location at = sender instanceof Player self && self.getWorld().equals(target.getWorld()) ? self.getLocation() : target.getLocation();
                EvadeApi.sendWaypoint(target, "evade-test", "Test waypoint", at, 0xFFAA00);
                sender.sendMessage(PREFIX + "Sent a test waypoint to " + target.getName() + " at "
                        + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ".");
            }
            case "notify" -> {
                if (!allowed(sender, "evade.command.test")) return true;
                Player target = target(sender, args, 1);
                if (target == null || !requireEvade(sender, target)) return true;
                String text = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "This is a test notification from the server.";
                EvadeApi.notify(target, "Evade Server API", text, 5, "info");
                sender.sendMessage(PREFIX + "Sent a test notification to " + target.getName() + ".");
            }
            case "reload" -> {
                if (!allowed(sender, "evade.command.reload")) return true;
                plugin.reload();
                sender.sendMessage(PREFIX + "Reloaded config.yml and re-sent the rules to " + plugin.evadePlayers().size() + " Evade player(s).");
            }
            case "cosmetic", "cosmetics" -> {
                if (!allowed(sender, "evade.command.cosmetics")) return true;
                grantCosmetic(sender, label, args);
            }
            default -> {
                sender.sendMessage(PREFIX + "Evade Server API " + plugin.getDescription().getVersion());
                sender.sendMessage("§f/" + label + " list §7- online Evade players");
                sender.sendMessage("§f/" + label + " info <player> §7- a player's client version and features");
                sender.sendMessage("§f/" + label + " waypoint [player] §7- send a test waypoint");
                sender.sendMessage("§f/" + label + " notify [player] [text] §7- send a test notification");
                sender.sendMessage("§f/" + label + " reload §7- reload config.yml");
                sender.sendMessage("§f/" + label + " cosmetic grant <player> <item> §7- give a player one of your partner cosmetics");
            }
        }
        return true;
    }

    /** /evade cosmetic grant <player> <item>: one of this server's partner cosmetics, through evadeclient.com. */
    private void grantCosmetic(CommandSender sender, String label, String[] args) {
        if (args.length != 4 || !args[1].equalsIgnoreCase("grant")) {
            sender.sendMessage(PREFIX + "Usage: §f/" + label + " cosmetic grant <player> <item>");
            return;
        }
        if (!PartnerCosmetics.configured(plugin.settings())) {
            sender.sendMessage(PREFIX + "§cPartner cosmetics are off. Set cosmetics.partner-id and cosmetics.partner-key in config.yml.");
            return;
        }
        OfflinePlayer target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null) target = plugin.getServer().getOfflinePlayerIfCached(args[2]);
        if (target == null) {
            sender.sendMessage(PREFIX + "§cNo player called " + args[2] + " has played here.");
            return;
        }
        String name = target.getName() == null ? args[2] : target.getName();
        String item = args[3];
        sender.sendMessage(PREFIX + "Giving " + item + " to " + name + "...");
        EvadeApi.grantPartnerCosmetic(target, item).whenComplete((granted, err) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (err == null) {
                        sender.sendMessage(PREFIX + (granted ? "Gave §f" + item + "§7 to §f" + name + "§7."
                                : "§f" + name + "§7 already has §f" + item + "§7."));
                    } else {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        sender.sendMessage(PREFIX + "§cCouldn't give " + item + ": " + cause.getMessage());
                    }
                }));
    }

    private static boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage(PREFIX + "§cYou don't have permission to do that.");
        return false;
    }

    /** args[index] as an online player, or the sender itself when it's a player and no name was given. */
    private Player target(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player p = plugin.getServer().getPlayerExact(args[index]);
            if (p == null) sender.sendMessage(PREFIX + "§cNo online player called " + args[index] + ".");
            return p;
        }
        if (sender instanceof Player p) return p;
        sender.sendMessage(PREFIX + "§cName a player.");
        return null;
    }

    private boolean requireEvade(CommandSender sender, Player target) {
        if (plugin.isEvade(target)) return true;
        sender.sendMessage(PREFIX + "§c" + target.getName() + " is not using Evade Client.");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("cosmetic")) {
            if ("grant".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("grant");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("cosmetic")) {
            for (Player p : plugin.getServer().getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(p.getName());
        } else if (args.length == 2 && List.of("info", "waypoint", "notify").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Player p : plugin.getServer().getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
        }
        return out;
    }
}
