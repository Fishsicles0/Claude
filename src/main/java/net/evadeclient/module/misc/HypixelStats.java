package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.HypixelKeyScreen;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Hypixel;
import net.evadeclient.util.HypixelApi;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Hypixel Stats: a player's BedWars star, SkyWars level, Duels wins or network level after their name in the tab
 * list, looked up with your own Hypixel API key. Nothing about names above heads changes.
 *
 * <p>The key lives only in your Evade config (a hidden setting, so it never goes into shared profile codes) and is
 * sent only to api.hypixel.net. Lookups run on Hypixel only, off the render thread, cached for 10 minutes per player
 * and rate-limited well under Hypixel's limit (see {@link HypixelApi}).
 */
public class HypixelStats extends Module {
    private final ModeSetting stat = add(new ModeSetting("Stat", "Auto", "Auto", "Network Level", "BedWars Star", "SkyWars Level", "Duels Wins")
            .describe("What to show after each name. Auto picks the stat of the game you're in (from the scoreboard), else network level."));
    private final BooleanSetting showSelf = add(new BooleanSetting("Show Yourself", true)
            .describe("Also show your own stat in the tab list."));
    /** The key itself: saved in the config but hidden, so it is never shown, searched or put in a profile code. */
    private final StringSetting apiKey = add(new StringSetting("API Key Value", "", "").maxLength(64).hidden());
    @SuppressWarnings("unused")
    private final StringSetting keyRow = add(new StatusSetting("Hypixel API Key", this::keyDisplay, "click to set")
            .onClick(this::openKeyScreen)
            .describe("Needs your own personal Hypixel API key: create one at developer.hypixel.net, then click here to paste it. "
                    + "It stays in your Evade config and is only ever sent to api.hypixel.net."));
    @SuppressWarnings("unused")
    private final StringSetting status = add(new StatusSetting("Status", this::statusDisplay, "")
            .describe("What the lookups are doing right now."));

    /** The stat Auto resolved to this tick (from the sidebar title). */
    private String autoStat = "Network Level";

    public HypixelStats() {
        super("Hypixel Stats", "Shows Hypixel stats (BedWars star, SkyWars level, network level) next to names in the tab list. "
                + "Needs your own Hypixel API key from developer.hypixel.net.", Category.MISC);
    }

    public static HypixelStats instance() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(HypixelStats.class);
    }

    private String key() { return apiKey.get() == null ? "" : apiKey.get().strip(); }

    private String keyDisplay() {
        String k = key();
        if (k.isEmpty()) return "Not set (click)";
        return HypixelApi.validKey(k) ? "Set (click to change)" : "Invalid (click)";
    }

    private String statusDisplay() { return HypixelApi.status(key()); }

    private void openKeyScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new HypixelKeyScreen(mc.currentScreen, key(), this::setKey));
    }

    /** Stores a new key (blank clears it) and saves the config. Cached results are dropped when it changes. */
    public void setKey(String key) {
        String k = key == null ? "" : key.strip();
        if (k.equals(key())) return;
        apiKey.set(k);
        HypixelApi.clearQueue();
        HypixelApi.clearCache();
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    public boolean hasKey() { return HypixelApi.validKey(key()); }

    @Override
    public void onTick() {
        if (!Hypixel.connected()) {
            HypixelApi.clearQueue();
            return;
        }
        if (stat.is("Auto")) {
            String title = Hypixel.sidebarTitle().toUpperCase(Locale.ROOT);
            autoStat = title.contains("BED WARS") || title.contains("BEDWARS") ? "BedWars Star"
                    : title.contains("SKYWARS") ? "SkyWars Level"
                    : title.contains("DUELS") ? "Duels Wins"
                    : "Network Level";
        }
        HypixelApi.tick(key());
    }

    @Override
    protected void onDisable() { HypixelApi.clearQueue(); }

    @Override
    public String getSuffix() { return stat.get(); }

    /**
     * The tab-list name for {@code entry} with the stat appended, or {@code name} unchanged (module off, not on
     * Hypixel, no key, nothing cached yet). Render thread; never waits for the network.
     */
    public static Text decorate(PlayerListEntry entry, Text name) {
        HypixelStats m = instance();
        if (m == null || !m.isEnabled() || name == null || entry == null || !m.hasKey() || !Hypixel.connected()) return name;
        UUID id = entry.getProfile().id();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!m.showSelf.get() && mc.player != null && mc.player.getUuid().equals(id)) return name;
        HypixelApi.Stats s = HypixelApi.lookup(id);
        if (s == null || !s.found()) return name;
        Text suffix = m.format(s);
        if (suffix == null) return name;
        return Text.empty().append(name).append(Text.literal(" ")).append(suffix);
    }

    private Text format(HypixelApi.Stats s) {
        String which = stat.is("Auto") ? autoStat : stat.get();
        return switch (which) {
            case "BedWars Star" -> Text.literal("[" + s.bedwarsStar() + "✫]").formatted(prestige(s.bedwarsStar()));
            case "SkyWars Level" -> s.skywarsLevel().isEmpty() ? null
                    : Text.literal("[" + s.skywarsLevel() + "]").formatted(Formatting.AQUA);
            case "Duels Wins" -> Text.literal(s.duelsWins() + "W").formatted(Formatting.GREEN);
            default -> {
                MutableText t = Text.literal("Lv" + Math.max(1, s.networkLevel()));
                yield t.formatted(Formatting.GOLD);
            }
        };
    }

    /** BedWars prestige colour of a star count (one per hundred stars, gold from 1000 up). */
    private static Formatting prestige(int star) {
        return switch (Math.max(0, star) / 100) {
            case 0 -> Formatting.GRAY;
            case 1 -> Formatting.WHITE;
            case 2 -> Formatting.GOLD;
            case 3 -> Formatting.AQUA;
            case 4 -> Formatting.DARK_GREEN;
            case 5 -> Formatting.DARK_AQUA;
            case 6 -> Formatting.DARK_RED;
            case 7 -> Formatting.LIGHT_PURPLE;
            case 8 -> Formatting.BLUE;
            case 9 -> Formatting.DARK_PURPLE;
            default -> Formatting.YELLOW;
        };
    }

    @Override
    public List<String> commandAliases() { return List.of("hypixelkey"); }

    @Override
    public void command(String[] args) {
        // Opens the key screen (after chat closes); the key is never typed into chat, where it would stay in history.
        MinecraftClient.getInstance().send(this::openKeyScreen);
    }

    @Override
    public List<String> commandHelp() { return List.of("§d.hypixelkey§7 - set your Hypixel API key for Hypixel Stats"); }
}
