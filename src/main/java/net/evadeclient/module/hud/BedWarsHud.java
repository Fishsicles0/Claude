package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Hypixel;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BedWars Helper: the upcoming diamond and emerald generator upgrades, the next game event (beds gone, sudden death,
 * game end) and your team's purchased upgrades and traps, on Hypixel.
 *
 * <p>Purely what the game already tells you: the timeline starts at the "Protect your bed..." start message and runs
 * on Hypixel's standard BedWars schedule, and is re-synced from the sidebar's own "Diamond II in 4:23" line whenever
 * the scoreboard shows one. Upgrades come from the team's "X purchased Y" chat lines. Nothing is sent.
 */
public class BedWarsHud extends TextHudModule {
    /** One step of the standard schedule: name and seconds after the game starts. */
    private record Event(String name, int at, Kind kind) {}

    private enum Kind { DIAMOND, EMERALD, OTHER }

    /** Hypixel's standard BedWars timeline (solo, doubles, 3v3v3v3, 4v4v4v4). */
    private static final List<Event> TIMELINE = List.of(
            new Event("Diamond II", 6 * 60, Kind.DIAMOND),
            new Event("Emerald II", 12 * 60, Kind.EMERALD),
            new Event("Diamond III", 18 * 60, Kind.DIAMOND),
            new Event("Emerald III", 24 * 60, Kind.EMERALD),
            new Event("Bed Gone", 30 * 60, Kind.OTHER),
            new Event("Sudden Death", 40 * 60, Kind.OTHER),
            new Event("Game End", 50 * 60, Kind.OTHER));

    private static final String START = "Protect your bed and destroy the enemy beds";
    /** The sidebar's countdown line, e.g. "Diamond II in 4:23". */
    private static final Pattern SIDEBAR_EVENT = Pattern.compile(
            "^(Diamond III|Diamond II|Emerald III|Emerald II|Bed Gone|Sudden Death|Game End) in (\\d{1,2}):(\\d{2})$");
    /** A purchase line: "[RANK] Name purchased Upgrade" or "You purchased Upgrade". */
    private static final Pattern PURCHASE = Pattern.compile("^(?:\\[[^\\]]{1,24}\\] )?([A-Za-z0-9_]{1,16}) purchased (.+?)[.!]?$");
    private static final Pattern TRAP_SET_OFF = Pattern.compile("(?i)\\btrap\\b.*\\b(set off|triggered)\\b");
    private static final Pattern GAME_OVER = Pattern.compile("^(1st Killer|Reward Summary)\\b.*");

    /** Team upgrades: chat name (lower case) to short label. Forges are one upgrade with four tiers. */
    private static final Map<String, String> UPGRADES = new LinkedHashMap<>();
    private static final List<String> FORGES = List.of("iron forge", "golden forge", "emerald forge", "molten forge");
    private static final Map<String, String> TRAPS = new LinkedHashMap<>();

    static {
        UPGRADES.put("sharpened swords", "Sharp");
        UPGRADES.put("reinforced armor", "Prot");
        UPGRADES.put("maniac miner", "Haste");
        UPGRADES.put("heal pool", "Heal Pool");
        UPGRADES.put("dragon buff", "Dragons");
        UPGRADES.put("cushioned boots", "Feather");
        TRAPS.put("it's a trap!", "It's a Trap");
        TRAPS.put("it's a trap", "It's a Trap");
        TRAPS.put("counter-offensive trap", "Counter");
        TRAPS.put("alarm trap", "Alarm");
        TRAPS.put("miner fatigue trap", "Fatigue");
    }

    private final BooleanSetting showGenerators = add(new BooleanSetting("Show Generators", true)
            .describe("The next diamond and emerald generator upgrade with its countdown."));
    private final BooleanSetting showEvents = add(new BooleanSetting("Show Next Event", true)
            .describe("The next of Bed Gone, Sudden Death and Game End."));
    private final BooleanSetting showUpgrades = add(new BooleanSetting("Show Team Upgrades", true)
            .describe("Upgrades your team bought, read from the purchase lines in chat."));
    private final BooleanSetting showTraps = add(new BooleanSetting("Show Traps", true)
            .describe("Your team's trap queue, read from chat."));
    private final BooleanSetting showGameTime = add(new BooleanSetting("Show Game Time", false)
            .describe("How long the game has been running."));

    /** When the current game started (ms, wall clock), or 0 outside a game. */
    private long startedAt;
    private boolean over;
    private Object world;
    /** Upgrade label to tier (1 for single-tier upgrades). Insertion order is purchase order. */
    private final Map<String, Integer> upgrades = new LinkedHashMap<>();
    private int forge;
    private final List<String> traps = new ArrayList<>();

    public BedWarsHud() {
        super("BedWars Helper", "Upcoming diamond and emerald upgrades, the next game event and your team's upgrades. Hypixel BedWars.",
                -3, 120);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && isEnabled() && message != null) onChat(message.getString());
        });
    }

    // ------------------------------------------------------------------ tracking

    @Override
    public void onTick() {
        if (mc.world != world) {
            world = mc.world;
            reset();
        }
        if (!Hypixel.connected()) return;
        long now = System.currentTimeMillis();
        for (String line : Hypixel.sidebarLines()) {
            Matcher m = SIDEBAR_EVENT.matcher(line);
            if (!m.matches()) continue;
            Event e = event(m.group(1));
            if (e == null) break;
            int remaining = Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
            long start = now - (e.at() - remaining) * 1000L;
            // The sidebar counts whole seconds: only move the timeline when it drifted more than that.
            if (startedAt == 0 || Math.abs(start - startedAt) > 1500) startedAt = start;
            break;
        }
    }

    /** Reads one system chat line (client thread). */
    void onChat(String raw) {
        if (!Hypixel.connected()) return;
        String s = Hypixel.strip(raw);
        if (s.isEmpty()) return;
        if (s.contains(START)) {
            reset();
            startedAt = System.currentTimeMillis();
            return;
        }
        if (startedAt == 0) return;
        if (GAME_OVER.matcher(s).matches()) { over = true; return; }
        if (TRAP_SET_OFF.matcher(s).find() && !s.contains(" purchased ")) {
            if (!traps.isEmpty()) traps.removeFirst();
            return;
        }
        Matcher m = PURCHASE.matcher(s);
        if (m.matches()) purchased(m.group(2).strip());
    }

    private void purchased(String what) {
        String lower = what.toLowerCase(Locale.ROOT);
        String trap = TRAPS.get(lower);
        if (trap != null) {
            if (traps.size() < 3) traps.add(trap);
            return;
        }
        int f = FORGES.indexOf(lower);
        if (f >= 0) { forge = Math.max(forge, f + 1); return; }
        // "Reinforced Armor II": the base name, then an optional Roman numeral tier.
        String base = lower;
        int tier = 1;
        int sp = lower.lastIndexOf(' ');
        if (sp > 0) {
            int t = roman(lower.substring(sp + 1));
            if (t > 0) { base = lower.substring(0, sp); tier = t; }
        }
        String label = UPGRADES.get(base);
        if (label != null) upgrades.merge(label, tier, Math::max);
    }

    private static int roman(String s) {
        return switch (s) {
            case "i" -> 1;
            case "ii" -> 2;
            case "iii" -> 3;
            case "iv" -> 4;
            default -> 0;
        };
    }

    private static Event event(String name) {
        for (Event e : TIMELINE) if (e.name().equals(name)) return e;
        return null;
    }

    private void reset() {
        startedAt = 0;
        over = false;
        upgrades.clear();
        forge = 0;
        traps.clear();
    }

    @Override
    protected void onDisable() { reset(); }

    // ------------------------------------------------------------------ drawing

    @Override
    protected List<String> lines(boolean preview) { return List.of(); }

    @Override
    protected List<Text> styledLines(boolean preview) {
        if (preview && (startedAt == 0 || over)) return sample();
        if (startedAt == 0 || over || !Hypixel.connected()) return List.of();
        int t = (int) ((System.currentTimeMillis() - startedAt) / 1000);
        if (t < 0) t = 0;
        return build(t, upgrades, forge, traps);
    }

    private List<Text> sample() {
        Map<String, Integer> up = new LinkedHashMap<>();
        up.put("Sharp", 1);
        up.put("Prot", 2);
        up.put("Haste", 1);
        return build(4 * 60 + 37, up, 1, List.of("Alarm"));
    }

    private List<Text> build(int t, Map<String, Integer> up, int forgeTier, List<String> trapList) {
        List<Text> out = new ArrayList<>();
        if (showGameTime.get()) out.add(line("Time ", Formatting.GRAY, clock(t), Formatting.WHITE));
        if (showGenerators.get()) {
            out.add(generator(t, Kind.DIAMOND, "Diamond", Formatting.AQUA));
            out.add(generator(t, Kind.EMERALD, "Emerald", Formatting.GREEN));
        }
        if (showEvents.get()) {
            for (Event e : TIMELINE) {
                if (e.kind() != Kind.OTHER || e.at() <= t) continue;
                out.add(line(e.name() + " in ", Formatting.RED, clock(e.at() - t), Formatting.WHITE));
                break;
            }
        }
        if (showUpgrades.get()) {
            List<String> parts = new ArrayList<>();
            if (forgeTier > 0) parts.add(switch (forgeTier) {
                case 1 -> "Iron Forge";
                case 2 -> "Gold Forge";
                case 3 -> "Emerald Forge";
                default -> "Molten Forge";
            });
            for (var e : up.entrySet()) {
                boolean tiered = !e.getKey().equals("Sharp") && !e.getKey().equals("Heal Pool") && !e.getKey().equals("Dragons");
                parts.add(tiered ? e.getKey() + " " + numeral(e.getValue()) : e.getKey());
            }
            if (!parts.isEmpty()) {
                // Two per line, so the widget stays narrow.
                for (int i = 0; i < parts.size(); i += 2) {
                    String text = parts.get(i) + (i + 1 < parts.size() ? ", " + parts.get(i + 1) : "");
                    out.add(i == 0 ? line("Upgrades ", Formatting.GRAY, text, Formatting.GOLD)
                            : Text.literal(text).formatted(Formatting.GOLD));
                }
            }
        }
        if (showTraps.get() && !trapList.isEmpty())
            out.add(line("Traps ", Formatting.GRAY, String.join(", ", trapList), Formatting.LIGHT_PURPLE));
        return out;
    }

    /** "Diamond II in 4:23", or "Diamond III (max)" once the last tier is reached. */
    private Text generator(int t, Kind kind, String name, Formatting color) {
        int tier = 1;
        for (Event e : TIMELINE) {
            if (e.kind() != kind) continue;
            if (e.at() > t) return line(e.name() + " in ", color, clock(e.at() - t), Formatting.WHITE);
            tier++;
        }
        return line(name + " " + numeral(tier) + " ", color, "(max)", Formatting.GRAY);
    }

    private static MutableText line(String a, Formatting ca, String b, Formatting cb) {
        return Text.literal(a).formatted(ca).append(Text.literal(b).formatted(cb));
    }

    private static String numeral(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(n);
        };
    }

    private static String clock(int seconds) {
        seconds = Math.max(0, seconds);
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    // ------------------------------------------------------------------ test hooks

    /** Test hook: feed one chat line as if Hypixel sent it (ignores the server check). */
    public void chatForTest(String line) {
        String s = Hypixel.strip(line);
        if (s.contains(START)) { reset(); startedAt = System.currentTimeMillis(); return; }
        Matcher m = PURCHASE.matcher(s);
        if (m.matches()) purchased(m.group(2).strip());
    }

    /** Test hook: purchased upgrades as label to tier, plus "Forge" for the forge tier. */
    public Map<String, Integer> upgradesForTest() {
        Map<String, Integer> out = new LinkedHashMap<>(upgrades);
        if (forge > 0) out.put("Forge", forge);
        return out;
    }

    /** Test hook: whether a game timeline is running. */
    public boolean runningForTest() { return startedAt != 0 && !over; }
}
