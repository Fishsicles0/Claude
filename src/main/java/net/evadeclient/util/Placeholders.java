package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Evade's text template engine, shared by Custom Text, the Watermark's Text style and anything else that shows
 * user-written text with live values.
 *
 * <p>Syntax:
 * <ul>
 *   <li>{@code {name}} or {@code {name:arg}}: a live value, e.g. {@code {fps}}, {@code {time:HH:mm}},
 *       {@code {count:totem_of_undying}}, {@code {mod:Zoom}}. Unknown names are left as typed.</li>
 *   <li>{@code <red>}, {@code <#RRGGBB>}, {@code <#RGB>}: colour from here on; {@code </red>} or {@code </>} goes back
 *       to the colour before it, {@code <reset>} to the widget's own colour.</li>
 *   <li>{@code {if ping>150}High ping{else}OK{end}}: shown only when the condition holds. Conditions compare a value
 *       with a number or word ({@code > < >= <= = == !=}), or test a value alone ({@code {if mod:Zoom}}: not empty,
 *       0, Off or ?). {@code !} in front negates. Blocks nest.</li>
 *   <li>{@code \n} starts a new line; a backslash before any other character shows it as is ({@code \{}).</li>
 * </ul>
 *
 * <p>Fair play: every value is something the vanilla client already shows the player about themselves or the world
 * on their screen (F3, the tab list's own ping, their own inventory and stats). Nothing about other players or
 * entities, and coordinates and the biome follow the server's Reduced Debug Info rule like the Coordinates HUD.
 */
public final class Placeholders {
    private Placeholders() {}

    /** Longest line a template produces (characters), so a runaway template can't build a huge string. */
    public static final int MAX_LINE = 512;

    /** Every placeholder name, with an example argument where it takes one (for help text and tests). */
    public static final List<String> NAMES = List.of(
            "fps", "ping", "x", "y", "z", "nether_x", "nether_z", "biome", "dimension", "facing", "facing_short",
            "yaw", "pitch", "time:HH:mm", "day", "server", "count:totem_of_undying", "mod:Zoom", "cps", "rcps",
            "speed", "memory", "memory_used", "memory_max", "health", "food", "armor", "level", "held",
            "durability", "username");

    /** A piece of text in one colour; {@code color} null keeps the widget's own colour. */
    public record Run(String text, Integer color) {}

    // ------------------------------------------------------------------------------------------------ API

    /** {@code template} as styled lines with live values (the first line only when it has no {@code \n}). */
    public static List<Text> lines(String template) {
        return toText(parse(template, Placeholders::resolve));
    }

    /** {@code template} as one styled line (line breaks become spaces). */
    public static MutableText text(String template) {
        List<List<Run>> lines = parse(template, Placeholders::resolve);
        MutableText out = Text.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(" ");
            for (Run r : lines.get(i)) out.append(literal(r));
        }
        return out;
    }

    /** {@code template} as plain text with live values, lines joined with {@code \n}. */
    public static String plain(String template) {
        return plain(template, Placeholders::resolve);
    }

    /**
     * {@code template} as plain text, values from {@code values} ("name" or "name:arg" to a value, null for an
     * unknown name). For tests and previews.
     */
    public static String plain(String template, Function<String, String> values) {
        StringBuilder sb = new StringBuilder();
        List<List<Run>> lines = parse(template, values);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            for (Run r : lines.get(i)) sb.append(r.text());
        }
        return sb.toString();
    }

    /** Whether {@code s} uses any template syntax (a value, a colour tag or a line break). */
    public static boolean isTemplate(String s) {
        return s != null && (s.indexOf('{') >= 0 || s.indexOf('<') >= 0 || s.contains("\\n"));
    }

    /** Lines of coloured runs to Text lines. */
    public static List<Text> toText(List<List<Run>> lines) {
        List<Text> out = new ArrayList<>(lines.size());
        for (List<Run> line : lines) {
            MutableText t = Text.empty();
            for (Run r : line) t.append(literal(r));
            out.add(t);
        }
        return out;
    }

    private static MutableText literal(Run r) {
        MutableText t = Text.literal(r.text());
        return r.color() == null ? t : t.withColor(r.color() & 0xFFFFFF);
    }

    // ------------------------------------------------------------------------------------------------ parser

    /** One open {if}: whether its branch is showing, and whether the text around it was. */
    private static final class Frame {
        final boolean outer;
        boolean cond;
        Frame(boolean outer, boolean cond) { this.outer = outer; this.cond = cond; }
        boolean active() { return outer && cond; }
    }

    /** Parses {@code template} into lines of coloured runs, with values from {@code values}. */
    public static List<List<Run>> parse(String template, Function<String, String> values) {
        List<List<Run>> lines = new ArrayList<>();
        List<Run> line = new ArrayList<>();
        lines.add(line);
        if (template == null || template.isEmpty()) return lines;
        Deque<Integer> colors = new ArrayDeque<>();
        Deque<Frame> frames = new ArrayDeque<>();
        StringBuilder buf = new StringBuilder();
        int[] lineLen = {0};
        int n = template.length();
        int i = 0;
        while (i < n) {
            char c = template.charAt(i);
            boolean active = frames.isEmpty() || frames.peek().active();
            if (c == '\\' && i + 1 < n) {
                char next = template.charAt(i + 1);
                if (active) {
                    if (next == 'n') {
                        flush(line, buf, colors, lineLen);
                        line = new ArrayList<>();
                        lines.add(line);
                        lineLen[0] = 0;
                    } else {
                        buf.append(next);
                    }
                }
                i += 2;
                continue;
            }
            if (c == '{') {
                int close = template.indexOf('}', i + 1);
                if (close < 0) { if (active) buf.append(c); i++; continue; }
                String body = template.substring(i + 1, close).strip();
                String lower = body.toLowerCase(Locale.ROOT);
                if (lower.startsWith("if ") || lower.startsWith("if(")) {
                    boolean cond = active && condition(body.substring(2).strip(), values);
                    frames.push(new Frame(active, cond));
                } else if (lower.equals("else")) {
                    if (!frames.isEmpty()) frames.peek().cond = !frames.peek().cond;
                    else if (active) buf.append('{').append(body).append('}');
                } else if (lower.equals("end") || lower.equals("endif") || lower.equals("/if")) {
                    if (!frames.isEmpty()) frames.pop();
                    else if (active) buf.append('{').append(body).append('}');
                } else if (active) {
                    String v = body.isEmpty() ? null : safe(values, body);
                    buf.append(v == null ? "{" + body + "}" : v);
                }
                i = close + 1;
                continue;
            }
            if (c == '<') {
                int close = template.indexOf('>', i + 1);
                Integer tag = close < 0 ? null : tagColor(template.substring(i + 1, close));
                String body = close < 0 ? "" : template.substring(i + 1, close).strip().toLowerCase(Locale.ROOT);
                boolean closing = close >= 0 && body.startsWith("/") && (body.length() == 1 || tagColor(body.substring(1)) != null);
                boolean reset = body.equals("reset") || body.equals("r");
                if (tag != null || closing || reset) {
                    if (active) {
                        flush(line, buf, colors, lineLen);
                        if (reset) colors.clear();
                        else if (closing) { if (!colors.isEmpty()) colors.pop(); }
                        else colors.push(tag);
                    }
                    i = close + 1;
                    continue;
                }
            }
            if (active) buf.append(c);
            i++;
        }
        flush(line, buf, colors, lineLen);
        return lines;
    }

    private static void flush(List<Run> line, StringBuilder buf, Deque<Integer> colors, int[] lineLen) {
        if (buf.isEmpty()) return;
        String s = buf.toString();
        buf.setLength(0);
        int room = MAX_LINE - lineLen[0];
        if (room <= 0) return;
        if (s.length() > room) s = s.substring(0, room);
        lineLen[0] += s.length();
        Integer color = colors.peek();
        if (!line.isEmpty() && java.util.Objects.equals(line.getLast().color(), color)) {
            Run last = line.removeLast();
            line.add(new Run(last.text() + s, color));
        } else {
            line.add(new Run(s, color));
        }
    }

    private static String safe(Function<String, String> values, String key) {
        try {
            return values.apply(key);
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** The RGB of a colour tag's name ("red", "#ff0000", "#f00", "accent"), or null when it isn't one. */
    public static Integer tagColor(String name) {
        String s = name.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.charAt(0) == '#') {
            String hex = s.substring(1);
            if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            if (hex.length() != 6) return null;
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        switch (s) {
            case "accent" -> { return Theme.RED & 0xFFFFFF; }
            case "grey" -> s = "gray";
            case "dark_grey" -> s = "dark_gray";
            case "pink", "purple" -> s = "light_purple";
            case "orange" -> { return 0xFF8A1F; }
            default -> { }
        }
        Formatting f = Formatting.byName(s);
        return f != null && f.isColor() && f.getColorValue() != null ? f.getColorValue() : null;
    }

    // ------------------------------------------------------------------------------------------------ conditions

    private static final String[] OPS = {">=", "<=", "!=", "==", ">", "<", "="};

    /** Evaluates an {if} condition, e.g. "ping>150", "mod:Zoom", "!mod:Zoom", "dimension=Nether". */
    static boolean condition(String cond, Function<String, String> values) {
        String c = cond.strip();
        if (c.startsWith("(") && c.endsWith(")")) c = c.substring(1, c.length() - 1).strip();
        boolean negate = false;
        if (c.startsWith("!") && !c.startsWith("!=")) { negate = true; c = c.substring(1).strip(); }
        for (String op : OPS) {
            int at = c.indexOf(op);
            if (at <= 0) continue;
            String left = value(c.substring(0, at), values);
            String right = value(c.substring(at + op.length()), values);
            return negate != compare(left, op, right);
        }
        return negate != truthy(value(c, values));
    }

    /** A condition operand: a placeholder (with or without braces) when it is one, else the word itself. */
    private static String value(String operand, Function<String, String> values) {
        String s = operand.strip();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1).strip();
        String v = s.isEmpty() ? null : safe(values, s);
        return v != null ? v : s;
    }

    private static boolean compare(String a, String op, String b) {
        Double x = number(a), y = number(b);
        if (x != null && y != null) {
            int cmp = Double.compare(x, y);
            return switch (op) {
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case "!=" -> cmp != 0;
                default -> cmp == 0;
            };
        }
        boolean eq = a.strip().equalsIgnoreCase(b.strip());
        return switch (op) {
            case "!=" -> !eq;
            case "=", "==" -> eq;
            default -> false;
        };
    }

    /** The number a value starts with ("45%" is 45, "12.5 m/s" is 12.5), or null. */
    static Double number(String s) {
        if (s == null) return null;
        String t = s.strip();
        int end = 0;
        if (end < t.length() && (t.charAt(end) == '-' || t.charAt(end) == '+')) end++;
        int digits = 0;
        while (end < t.length() && (Character.isDigit(t.charAt(end)) || t.charAt(end) == '.')) { end++; digits++; }
        if (digits == 0) return null;
        try {
            return Double.parseDouble(t.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean truthy(String v) {
        if (v == null) return false;
        String s = v.strip();
        if (s.isEmpty() || s.equals("?") || s.equals("-") || s.equalsIgnoreCase("off") || s.equalsIgnoreCase("false")) return false;
        Double d = number(s);
        return d == null || d != 0;
    }

    // ------------------------------------------------------------------------------------------------ values

    private static final Map<String, DateTimeFormatter> TIME_FORMATS = new ConcurrentHashMap<>();
    private static final DateTimeFormatter DEFAULT_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final String[] FACING = {"South", "West", "North", "East"};

    // Speed: blocks per second over the horizontal plane, eased over a few ticks.
    private static double lastX = Double.NaN, lastZ;
    private static long lastTick = Long.MIN_VALUE;
    private static float speed;

    /**
     * The live value of {@code key} ("fps", "time:HH:mm", ...), or null when it isn't a placeholder. Render thread.
     */
    public static String resolve(String key) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String name = key, arg = null;
        int colon = key.indexOf(':');
        if (colon >= 0) {
            name = key.substring(0, colon);
            arg = key.substring(colon + 1);
        }
        name = name.strip().toLowerCase(Locale.ROOT);
        var p = mc.player;
        var w = mc.world;
        switch (name) {
            case "fps": return Integer.toString(mc.getCurrentFps());
            case "ping": return Integer.toString(ping());
            case "time": return time(arg);
            case "memory": {
                Runtime rt = Runtime.getRuntime();
                long used = rt.totalMemory() - rt.freeMemory();
                return Math.round(used * 100.0 / Math.max(1, rt.maxMemory())) + "%";
            }
            case "memory_used": {
                Runtime rt = Runtime.getRuntime();
                return ((rt.totalMemory() - rt.freeMemory()) >> 20) + " MB";
            }
            case "memory_max": return (Runtime.getRuntime().maxMemory() >> 20) + " MB";
            case "cps": return Integer.toString(ClickTracker.leftCps());
            case "rcps": return Integer.toString(ClickTracker.rightCps());
            case "mod": {
                if (arg == null || EvadeClient.modules == null) return "?";
                Module m = EvadeClient.modules.get(arg.strip());
                return m == null ? "?" : m.isEnabled() ? "On" : "Off";
            }
            case "server": return server();
            default: break;
        }
        // Everything below is about you and your world: nothing to show outside one.
        boolean known = switch (name) {
            case "x", "y", "z", "nether_x", "nether_z", "biome", "dimension", "facing", "facing_short", "yaw", "pitch",
                 "day", "count", "speed", "health", "food", "armor", "level", "held", "durability", "username" -> true;
            default -> false;
        };
        if (!known) return null;
        if (p == null || w == null) return "-";
        boolean hidden = p.hasReducedDebugInfo();
        switch (name) {
            case "x": return hidden ? "?" : Integer.toString(MathHelper.floor(p.getX()));
            case "y": return hidden ? "?" : Integer.toString(MathHelper.floor(p.getY()));
            case "z": return hidden ? "?" : Integer.toString(MathHelper.floor(p.getZ()));
            case "nether_x":
            case "nether_z": {
                if (hidden) return "?";
                double v = name.equals("nether_x") ? p.getX() : p.getZ();
                var dim = w.getRegistryKey();
                double f = dim == World.NETHER ? 8 : 1 / 8.0;
                return Integer.toString(MathHelper.floor(v * f));
            }
            case "biome": {
                if (hidden) return "?";
                return w.getBiome(BlockPos.ofFloored(p.getX(), p.getY(), p.getZ())).getKey()
                        .map(k -> pretty(k.getValue().getPath())).orElse("?");
            }
            case "dimension": {
                var dim = w.getRegistryKey();
                if (dim == World.OVERWORLD) return "Overworld";
                if (dim == World.NETHER) return "Nether";
                if (dim == World.END) return "The End";
                return pretty(dim.getValue().getPath());
            }
            case "facing": return FACING[MathHelper.floor(p.getYaw() / 90.0 + 0.5) & 3];
            case "facing_short": return FACING[MathHelper.floor(p.getYaw() / 90.0 + 0.5) & 3].substring(0, 1);
            case "yaw": return String.format(Locale.ROOT, "%.1f", MathHelper.wrapDegrees(p.getYaw()));
            case "pitch": return String.format(Locale.ROOT, "%.1f", p.getPitch());
            case "day": return day();
            case "count": return Integer.toString(count(arg));
            case "speed": return String.format(Locale.ROOT, "%.1f", speed(p.getX(), p.getZ()));
            case "health": return Integer.toString(MathHelper.ceil(p.getHealth()));
            case "food": return Integer.toString(p.getHungerManager().getFoodLevel());
            case "armor": return Integer.toString(p.getArmor());
            case "level": return Integer.toString(p.experienceLevel);
            case "held": {
                ItemStack s = p.getMainHandStack();
                return s.isEmpty() ? "Nothing" : s.getName().getString();
            }
            case "durability": {
                ItemStack s = p.getMainHandStack();
                if (s.isEmpty() || !s.isDamageable() || s.getMaxDamage() <= 0) return "-";
                return Math.round((1f - s.getDamage() / (float) s.getMaxDamage()) * 100) + "%";
            }
            case "username": return mc.getSession() == null ? "-" : mc.getSession().getUsername();
            default: return null;
        }
    }

    /** Your own latency from the player list; 0 in singleplayer or before the server has sent it. */
    private static int ping() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.isInSingleplayer()) return 0;
        var nh = mc.getNetworkHandler();
        var entry = nh == null ? null : nh.getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0 : entry.getLatency();
    }

    private static String time(String pattern) {
        DateTimeFormatter f = DEFAULT_TIME;
        if (pattern != null && !pattern.isBlank()) {
            f = TIME_FORMATS.get(pattern);
            if (f == null) {
                try {
                    f = DateTimeFormatter.ofPattern(pattern, Locale.getDefault(Locale.Category.FORMAT));
                } catch (IllegalArgumentException e) {
                    return "?";
                }
                if (TIME_FORMATS.size() < 32) TIME_FORMATS.put(pattern, f);
            }
        }
        try {
            return f.format(LocalDateTime.now()).replace(' ', ' ').replace(' ', ' ');
        } catch (RuntimeException e) {
            return "?"; // a pattern asking for a time zone or other field a local date-time doesn't have
        }
    }

    /** The world's day (as F3 shows it), or ? while Time Changer replaces the time you see. */
    private static String day() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return "-";
        var tc = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.mods.TimeChanger.class);
        if (tc != null && tc.isEnabled()) return "?";
        return Long.toString(mc.world.getTimeOfDay() / 24000L);
    }

    private static String server() {
        MinecraftClient mc = MinecraftClient.getInstance();
        var entry = mc.getCurrentServerEntry();
        if (entry != null) {
            if (entry.isRealm()) return "Realms";
            if (entry.isLocal()) return "LAN World";
            String a = entry.address == null ? "" : entry.address.strip();
            if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
            return a.isEmpty() ? "Multiplayer" : a;
        }
        if (mc.getServer() != null) return "Singleplayer";
        return mc.getNetworkHandler() != null ? "Multiplayer" : "-";
    }

    /** How many of an item you carry (main inventory, hotbar, off hand), by id ("totem_of_undying", "minecraft:arrow"). */
    private static int count(String id) {
        if (id == null || id.isBlank()) return 0;
        Identifier key = Identifier.tryParse(id.strip().toLowerCase(Locale.ROOT));
        if (key == null || !Registries.ITEM.containsId(key)) return 0;
        var item = Registries.ITEM.get(key);
        return InvUtil.count(s -> !s.isEmpty() && s.isOf(item));
    }

    private static float speed(double x, double z) {
        long tick = EvadeClient.ticks;
        if (Double.isNaN(lastX) || tick - lastTick > 20 || tick < lastTick) {
            lastX = x; lastZ = z; lastTick = tick; speed = 0;
            return 0;
        }
        if (tick > lastTick) {
            double dx = x - lastX, dz = z - lastZ;
            float now = (float) (Math.sqrt(dx * dx + dz * dz) * 20.0 / (tick - lastTick));
            if (now > 100) now = speed; // a teleport, not movement
            speed += (now - speed) * 0.5f;
            if (speed < 0.05f) speed = 0;
            lastX = x; lastZ = z; lastTick = tick;
        }
        return speed;
    }

    /** "dark_forest" to "Dark Forest". */
    static String pretty(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        boolean up = true;
        for (char ch : path.toCharArray()) {
            if (ch == '_' || ch == '/') { sb.append(' '); up = true; continue; }
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return sb.toString();
    }
}
