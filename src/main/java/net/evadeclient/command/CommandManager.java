package net.evadeclient.command;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.setting.StringSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Client-side chat commands, prefixed with '.', never sent to the server. Only messages whose first word is a
 * known command are taken; anything else ("...", ". lol", ".5 hearts left") goes to chat as typed.
 * Mod and setting names can be typed with or without spaces: ".toggle Nick Hider", ".toggle nickhider".
 */
public class CommandManager {
    public static final String PREFIX = ".";

    /** Built-in command words. Module-owned command aliases must be accepted by {@link #isCommand} too. */
    public static final Set<String> COMMANDS = Set.of("help", "clips", "clip", "t", "toggle", "bind", "set",
            "waypoint", "waypoints", "wp", "modules", "list", "nick", "voice", "save", "load", "menu", "gui", "evade", "bench", "benchmark",
            "binds", "keybinds", "reset", "profile", "profiles", "share", "import", "setup");

    /** Set while re-sending an escaped ".." message, so it isn't parsed again. */
    private boolean resending;

    /**
     * A mistake in what the user typed: shown in red, without a stack trace. Module commands
     * ({@link Module#command}) may throw it too, e.g. {@code throw new CommandError("Usage: .coords [copy|share]")}.
     */
    public static final class CommandError extends RuntimeException {
        public CommandError(String message) { super(message, null, false, false); }
    }

    /** @return false to cancel the chat message. */
    public boolean handleChat(String message) {
        if (resending || !message.startsWith(PREFIX)) return true;
        if (message.startsWith(PREFIX + PREFIX)) {
            // ".." escapes a command so it reaches the server as chat: "..help" sends ".help". Everything else
            // that starts with ".." ("...", "..lol") is plain chat and goes out exactly as typed.
            String rest = message.substring(PREFIX.length());
            if (!intercepts(rest.substring(PREFIX.length()))) return true;
            resend(rest);
            return false;
        }
        String body = message.substring(PREFIX.length());
        if (!intercepts(body)) return true;
        String[] args = body.trim().split("\\s+");
        String word = args[0].toLowerCase(Locale.ROOT);
        if (!isCommand(word)) {
            print("§cUnknown command. Did you mean §d." + nearMiss(args) + "§c? §7(start with .. to send it as chat)");
            return false;
        }
        try {
            execute(args);
        } catch (CommandError e) {
            print("§c" + e.getMessage());
        } catch (Exception e) {
            EvadeClient.LOG.error("Command .{} failed", word, e);
            print("§cError: " + e.getMessage());
        }
        return false;
    }

    /** Whether text typed after the '.' is a command (or a near miss of one) that Evade should handle. */
    private boolean intercepts(String body) {
        if (body.isEmpty() || Character.isWhitespace(body.charAt(0))) return false;
        String[] args = body.trim().split("\\s+");
        return isCommand(args[0].toLowerCase(Locale.ROOT)) || nearMiss(args) != null;
    }

    /** Whether {@code word} (lower case, without the prefix) names a command: built in, or owned by a module. */
    public boolean isCommand(String word) {
        return COMMANDS.contains(word) || commandOwner(word) != null;
    }

    /** The first module whose {@link Module#commandAliases()} contains {@code word} (ignoring case), or null. */
    private static Module commandOwner(String word) {
        if (EvadeClient.modules == null || word.isEmpty()) return null;
        for (Module m : EvadeClient.modules.all()) {
            for (String alias : m.commandAliases()) if (alias.equalsIgnoreCase(word)) return m;
        }
        return null;
    }

    /** Every command word, built-in ones first, then module aliases (lower case, for typo suggestions). */
    private static List<String> commandWords() {
        List<String> out = new ArrayList<>(COMMANDS);
        if (EvadeClient.modules != null) {
            for (Module m : EvadeClient.modules.all())
                for (String alias : m.commandAliases()) out.add(alias.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * A command one typo away from the first word, when the message clearly wasn't meant as chat: either the
     * rest of it names a mod (".togle Zoom") or it is a single long word (".toggel").
     */
    private String nearMiss(String[] args) {
        String word = args[0].toLowerCase(Locale.ROOT);
        if (word.length() < 4 || !word.chars().allMatch(c -> c >= 'a' && c <= 'z')) return null;
        boolean commandLike = args.length == 1 ? word.length() >= 5 : longestModule(args, 1, args.length) != null;
        if (!commandLike) return null;
        for (String c : commandWords()) if (c.length() >= 4 && distance(word, c) == 1) return c;
        return null;
    }

    private void resend(String message) {
        var nh = MinecraftClient.getInstance().getNetworkHandler();
        if (nh == null) return;
        resending = true;
        try {
            nh.sendChatMessage(message);
        } finally {
            resending = false;
        }
    }

    private void execute(String[] a) {
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help();
            case "clips", "clip" -> EvadeClient.modules.get(net.evadeclient.module.misc.Clips.class).command(a);
            case "t", "toggle" -> {
                need(a, 2, ".toggle <mod>");
                for (Module m : modules(a, 1)) {
                    if (m.getCategory() == Category.CLIENT) throw new CommandError(m.getName() + " is always on.");
                    boolean was = m.isEnabled();
                    m.toggle();
                    if (m.isEnabled() != was) print(m.getName() + " " + (m.isEnabled() ? "§aenabled" : "§cdisabled"));
                }
            }
            case "bind" -> bind(a);
            case "set" -> set(a);
            case "waypoint", "waypoints", "wp" -> EvadeClient.modules.get(net.evadeclient.module.visuals.Waypoints.class).command(a);
            case "modules", "list" -> print(EvadeClient.modules.all().stream()
                    .filter(m -> m.getCategory() != Category.CLIENT)
                    .map(m -> (m.isEnabled() ? "§a" : "§7") + m.getName())
                    .collect(Collectors.joining("§8, ")));
            case "nick" -> {
                var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
                if (a.length < 2) { print("Nickname: §c" + nh.nick.get() + "§7. Use .nick <name>"); break; }
                String name = join(a, 1, a.length);
                if (name.length() > 16) name = name.substring(0, 16);
                nh.nick.set(name);
                if (!nh.isEnabled()) nh.setEnabled(true);
                EvadeClient.config.save();
                print("Nick Hider on. You now show as §c" + name);
            }
            case "voice" -> net.evadeclient.module.misc.VoiceChat.openMenu();
            case "bench", "benchmark" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("stop")) {
                    if (net.evadeclient.util.BenchmarkScene.running()) { net.evadeclient.util.BenchmarkScene.cancel(); print("Benchmark scene stopped."); }
                    else if (net.evadeclient.util.BenchmarkAll.running()) net.evadeclient.util.BenchmarkAll.cancel();
                    else { net.evadeclient.util.Benchmark.cancel(); print("Benchmark stopped."); }
                }
                else if (a.length > 1 && a[1].equalsIgnoreCase("all")) net.evadeclient.util.BenchmarkAll.start(net.evadeclient.util.BenchmarkAll.DEFAULT_SECONDS);
                else if (a.length > 1 && a[1].equalsIgnoreCase("scene")) net.evadeclient.util.BenchmarkScene.command(a);
                else {
                    int secs = 30;
                    if (a.length > 1) try { secs = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) {}
                    if (net.evadeclient.util.Benchmark.start(secs)) print("§7Benchmarking for " + Math.max(5, Math.min(120, secs)) + " s" + (MinecraftClient.getInstance().isInSingleplayer() ? " (the camera turns one full circle)" : " (stand still; nothing is sent to the server)") + "...");
                    else print("§cJoin a world first (or a benchmark is already running).");
                }
            }
            case "binds", "keybinds" -> ExtraCommands.binds(a);
            case "reset" -> ExtraCommands.reset(a);
            case "profile", "profiles" -> ExtraCommands.profile(a);
            case "share" -> ExtraCommands.share(a);
            case "import" -> ExtraCommands.importCode(a);
            case "setup" -> ExtraCommands.setup();
            case "save" -> { EvadeClient.config.save(); print("Config saved."); }
            case "load" -> { EvadeClient.config.load(); print("Config loaded."); }
            case "menu", "gui", "evade" -> {
                // After the chat screen has closed, or it would close the menu straight away.
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.send(() -> mc.setScreen(new EvadeMenu()));
            }
            default -> {
                Module owner = commandOwner(a[0]);
                if (owner != null) owner.command(a);
                else print("§cUnknown command. Try .help");
            }
        }
    }

    private static void help() {
        print("§d.toggle <mod>§7 - turn mods on or off (type names with or without spaces)");
        print("§d.bind <mod> <key|mouse4|none>§7 - set a keybind");
        print("§d.set <mod> <setting> [value]§7 - change a setting, or show it");
        print("§d.modules§7 - list mods");
        print("§d.binds§7 [conflicts|gui] - list keybinds, or open the Keybinds page");
        print("§d.reset <mod> [setting]§7 - put a mod's settings (or one setting) back to default");
        print("§d.profile§7 [list|load|save|new|delete] [name] - switch and manage profiles");
        print("§d.share <mod>§7 / §d.import <code>§7 - copy one mod's settings as a code, or apply one");
        print("§d.setup§7 - run the first-time setup again");
        print("§7Press §dTab§7 while typing a command to complete it");
        print("§d.menu§7 - open the Evade menu");
        print("§d.wp§7 - waypoints (add, del, list, near, color, hide, show, rename, share, target, clear)");
        print("§d.nick <name>§7 - set your Nick Hider name");
        print("§d.clip§7 - save the last seconds as a clip");
        print("§d.voice§7 - open the voice chat menu");
        print("§d.bench [seconds]§7 - measure your FPS (average and 1% low) and compare with the last run");
        print("§d.bench all§7 - measure every FPS Mode and recommend the best one for this PC");
        print("§d.bench scene§7 - a repeatable 60 s flight in a temporary fixed-seed world, with a result card");
        print("§d.save§7 / §d.load§7 - config");
        print("§d.clips§7 [last|save]§7 - open Evade Clips (or the clips folder), edit your last clip, or save one now");
        for (Module m : EvadeClient.modules.all()) {
            try {
                for (String line : m.commandHelp()) print(line);
            } catch (Exception e) {
                EvadeClient.LOG.error("Help for {} failed", m.getName(), e);
            }
        }
        print("§7Start with §d..§7 to send a command as chat, e.g. §d..help");
    }

    // ---- .bind ----------------------------------------------------------

    private void bind(String[] a) {
        need(a, 3, ".bind <mod> <key|none>");
        // The longest mod name that still leaves a key after it: ".bind Nick Hider right shift".
        Match match = null;
        for (int end = a.length - 1; end > 1 && match == null; end--) {
            Module m = EvadeClient.modules.get(join(a, 1, end));
            if (m != null) match = new Match(m, end);
        }
        if (match == null) throw notFound(join(a, 1, a.length - 1));
        Module m = match.module;
        if (m.getCategory() == Category.CLIENT) throw new CommandError(m.getName() + " has no keybind. Use .set " + m.getName() + " Menu Key <key>");
        String keyName = join(a, match.end, a.length);
        int key = parseKey(keyName, true);
        m.setKey(key);
        print(m.getName() + " bound to §d" + KeySetting.keyName(key));
        warnConflicts(key, m);
        EvadeClient.config.save();
    }

    // ---- .set -----------------------------------------------------------

    private void set(String[] a) {
        need(a, 2, ".set <mod> <setting> [value]");
        Module first = null;
        int firstEnd = 2;
        // Longest mod name first; fall back to a shorter one if the rest doesn't name one of its settings.
        for (int end = a.length; end > 1; end--) {
            Module m = EvadeClient.modules.get(join(a, 1, end));
            if (m == null) continue;
            if (first == null) { first = m; firstEnd = end; }
            if (end == a.length) { listSettings(m); return; }
            for (int sEnd = a.length; sEnd > end; sEnd--) {
                Setting<?> s = setting(m, join(a, end, sEnd));
                if (s != null) { apply(m, s, join(a, sEnd, a.length)); return; }
            }
        }
        if (first == null) throw notFound(join(a, 1, a.length));
        throw new CommandError("No setting \"" + join(a, firstEnd, a.length) + "\" on " + first.getName()
                + ". Try .set " + first.getName());
    }

    /** A setting by name, ignoring case, spaces, symbols and units, with or without the mod's name in front. */
    static Setting<?> setting(Module m, String typed) {
        String t = ModuleManager.normalize(typed);
        if (t.isEmpty()) return null;
        String mod = ModuleManager.normalize(m.getName());
        for (Setting<?> s : m.getSettings()) if (!s.isHidden() && keys(s.getName(), mod).contains(t)) return s;
        return null;
    }

    private static Set<String> keys(String name, String mod) {
        String full = ModuleManager.normalize(name), bare = ModuleManager.normalize(name.replaceAll("\\(.*?\\)", ""));
        Set<String> out = new java.util.HashSet<>(List.of(full, bare));
        for (String k : List.of(full, bare)) if (k.startsWith(mod) && k.length() > mod.length()) out.add(k.substring(mod.length()));
        return out;
    }

    private void listSettings(Module m) {
        List<String> parts = new ArrayList<>();
        for (Setting<?> s : m.getSettings()) if (!s.isHidden()) parts.add("§7" + s.getName() + " §d" + s.display());
        if (parts.isEmpty()) { print(m.getName() + " has no settings."); return; }
        print(m.getName() + ": " + String.join("§8, ", parts));
    }

    private void apply(Module m, Setting<?> s, String v) {
        String label = ModuleManager.normalize(s.getName()).startsWith(ModuleManager.normalize(m.getName()))
                ? s.getName() : m.getName() + " " + s.getName();
        if (s instanceof StatusSetting) throw new CommandError(label + " is read-only.");
        if (v.isEmpty() && !(s instanceof StringSetting)) {
            print(label + " = §d" + s.display() + "§7" + hint(s));
            return;
        }
        String note = "";
        switch (s) {
            case BooleanSetting b -> b.set(parseBoolean(v, b.get()));
            case NumberSetting n -> {
                double d = parseNumber(v);
                n.set(d);
                if (d < n.getMin() || d > n.getMax()) note = " §7(allowed " + fmt(n.getMin()) + " to " + fmt(n.getMax()) + ")";
            }
            case ModeSetting md -> md.set(parseMode(md, v));
            case KeySetting k -> {
                int key = parseKey(v, k.allowMouse);
                if (!k.accepts(key)) throw new CommandError(label + " can't use a mouse button.");
                k.set(key);
            }
            case ColorSetting c -> c.set(parseColor(v));
            case StringSetting st -> st.set(v);
            default -> throw new CommandError(label + " can't be set from chat. Open the menu with .menu");
        }
        print(label + " = §d" + (s.display().isEmpty() ? "(empty)" : s.display()) + note);
        if (s instanceof KeySetting k) warnConflicts(k.get(), k);
        EvadeClient.config.save();
    }

    /** What a setting accepts, for ".set <mod> <setting>" without a value. */
    private static String hint(Setting<?> s) {
        return switch (s) {
            case BooleanSetting b -> " (on, off or toggle)";
            case NumberSetting n -> " (" + fmt(n.getMin()) + " to " + fmt(n.getMax()) + ")";
            case ModeSetting md -> " (" + String.join(", ", modeOptions(md)) + ")";
            case KeySetting k -> k.allowMouse ? " (a key, mouse3-mouse8 or none)" : " (a key or none)";
            case ColorSetting c -> " (#RRGGBB)";
            default -> "";
        };
    }

    private static boolean parseBoolean(String v, boolean current) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "y", "1", "enable", "enabled" -> true;
            case "off", "false", "no", "n", "0", "disable", "disabled" -> false;
            case "toggle", "flip" -> !current;
            default -> throw new CommandError("Use on, off or toggle, not \"" + v + "\".");
        };
    }

    private static double parseNumber(String v) {
        String t = v.trim();
        if (t.endsWith("%")) t = t.substring(0, t.length() - 1).trim();
        try {
            double d = Double.parseDouble(t.replace(',', '.'));
            if (Double.isFinite(d)) return d;
        } catch (NumberFormatException ignored) {}
        throw new CommandError("\"" + v + "\" isn't a number.");
    }

    /** Exact option, then the same ignoring case and spaces, then a unique start ("rain" for Rainbow). */
    private static String parseMode(ModeSetting md, String v) {
        List<String> options = modeOptions(md);
        String t = ModuleManager.normalize(v);
        for (String o : options) if (o.equalsIgnoreCase(v.trim())) return o;
        for (String o : options) if (ModuleManager.normalize(o).equals(t)) return o;
        List<String> starts = options.stream().filter(o -> !t.isEmpty() && ModuleManager.normalize(o).startsWith(t)).toList();
        if (starts.size() == 1) return starts.getFirst();
        throw new CommandError("\"" + v + "\" isn't an option. Options: " + String.join(", ", options));
    }

    /** A ModeSetting's options in menu order. */
    static List<String> modeOptions(ModeSetting md) {
        return md.getModes();
    }

    private static int parseColor(String v) {
        String h = v.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("#")) h = h.substring(1);
        else if (h.startsWith("0x")) h = h.substring(2);
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        try {
            if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
            if (h.length() == 8) return (int) Long.parseLong(h, 16);
        } catch (NumberFormatException ignored) {}
        throw new CommandError("Use a colour like #E11D2E, not \"" + v + "\".");
    }

    // ---- keys -----------------------------------------------------------

    private static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("rshift", "right.shift"), Map.entry("lshift", "left.shift"), Map.entry("shift", "left.shift"),
            Map.entry("rightshift", "right.shift"), Map.entry("leftshift", "left.shift"),
            Map.entry("rctrl", "right.control"), Map.entry("lctrl", "left.control"), Map.entry("ctrl", "left.control"),
            Map.entry("rightctrl", "right.control"), Map.entry("leftctrl", "left.control"), Map.entry("control", "left.control"),
            Map.entry("rightcontrol", "right.control"), Map.entry("leftcontrol", "left.control"),
            Map.entry("ralt", "right.alt"), Map.entry("lalt", "left.alt"), Map.entry("alt", "left.alt"),
            Map.entry("rightalt", "right.alt"), Map.entry("leftalt", "left.alt"),
            Map.entry("esc", "escape"), Map.entry("del", "delete"), Map.entry("ins", "insert"), Map.entry("return", "enter"),
            Map.entry("pgup", "page.up"), Map.entry("pageup", "page.up"), Map.entry("pgdn", "page.down"),
            Map.entry("pgdown", "page.down"), Map.entry("pagedown", "page.down"),
            Map.entry("caps", "caps.lock"), Map.entry("capslock", "caps.lock"), Map.entry("numlock", "num.lock"),
            Map.entry("scrolllock", "scroll.lock"), Map.entry("printscreen", "print.screen"),
            Map.entry("grave", "grave.accent"), Map.entry("`", "grave.accent"), Map.entry("tilde", "grave.accent"),
            Map.entry(",", "comma"), Map.entry(".", "period"), Map.entry("/", "slash"), Map.entry(";", "semicolon"),
            Map.entry("'", "apostrophe"), Map.entry("[", "left.bracket"), Map.entry("]", "right.bracket"),
            Map.entry("\\", "backslash"), Map.entry("-", "minus"), Map.entry("=", "equal"));

    /**
     * A key or mouse button from what the user typed: "H", "F5", "right shift", "rshift", "keypad 1", the names
     * the menu shows, "mouse3" (middle) to "mouse8", or "none".
     */
    private static int parseKey(String typed, boolean allowMouse) {
        String raw = typed.trim().toLowerCase(Locale.ROOT);
        String flat = ModuleManager.normalize(raw);
        if (raw.isEmpty()) throw new CommandError("Name a key, or none.");
        if (flat.equals("none") || flat.equals("unbind") || flat.equals("clear")) return GLFW.GLFW_KEY_UNKNOWN;

        int button = -1;
        if (flat.equals("middle") || flat.equals("mmb") || flat.equals("middlemouse") || flat.equals("mousemiddle")) button = 2;
        else if (flat.matches("(mouse|button|mb)[3-8]")) button = flat.charAt(flat.length() - 1) - '1';
        if (button >= 0) {
            if (!allowMouse) throw new CommandError("This can't use a mouse button.");
            return KeySetting.mouseCode(button);
        }

        String alias = KEY_ALIASES.get(raw);
        if (alias == null) alias = KEY_ALIASES.get(flat);
        if (alias == null && flat.matches("(numpad|kp)\\d")) alias = "keypad." + flat.charAt(flat.length() - 1);
        String dotted = alias != null ? alias : String.join(".", raw.split("[\\s_\\-]+"));
        InputUtil.Key key = InputUtil.fromTranslationKey("key.keyboard." + dotted);
        if (key == InputUtil.UNKNOWN_KEY || key.getCategory() != InputUtil.Type.KEYSYM || !KeySetting.isValidKey(key.getCode()))
            throw new CommandError("Unknown key \"" + typed.trim() + "\".");
        return key.getCode();
    }

    private static void warnConflicts(int code, Object self) {
        List<String> others = EvadeClient.modules.usersOf(code, self);
        if (!others.isEmpty()) print("§7Also on this key: §f" + String.join("§7, §f", others));
    }

    // ---- mod names --------------------------------------------------------

    record Match(Module module, int end) {}

    /** The longest run of words from {@code from} (ending at or before {@code maxEnd}) that names a mod. */
    static Match longestModule(String[] a, int from, int maxEnd) {
        for (int end = maxEnd; end > from; end--) {
            Module m = EvadeClient.modules.get(join(a, from, end));
            if (m != null) return new Match(m, end);
        }
        return null;
    }

    /** Every mod named from {@code from} on (".toggle zoom fullbright" toggles both). */
    private static List<Module> modules(String[] a, int from) {
        List<Module> out = new ArrayList<>();
        int i = from;
        while (i < a.length) {
            Match m = longestModule(a, i, a.length);
            if (m == null) throw notFound(join(a, i, a.length));
            out.add(m.module);
            i = m.end;
        }
        return out;
    }

    static CommandError notFound(String typed) {
        String guess = closestModule(typed);
        return new CommandError("No mod named \"" + typed + "\"." + (guess == null ? " Try .modules" : " Did you mean " + guess + "?"));
    }

    /** The mod whose name starts with, or is within two typos of, what was typed. */
    private static String closestModule(String typed) {
        String t = ModuleManager.normalize(typed);
        if (t.length() < 2) return null;
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Module m : EvadeClient.modules.all()) {
            if (m.getCategory() == Category.CLIENT) continue;
            String n = ModuleManager.normalize(m.getName());
            int score = n.startsWith(t) && t.length() >= 3 ? 0 : distance(t, n);
            if (score < bestScore) { bestScore = score; best = m.getName(); }
        }
        return bestScore <= 2 ? best : null;
    }

    /** Edit distance where swapping two neighbouring letters counts as one typo. */
    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
            }
        }
        return d[a.length()][b.length()];
    }

    // ---- helpers ----------------------------------------------------------

    static String join(String[] a, int from, int to) {
        return from >= to ? "" : String.join(" ", java.util.Arrays.copyOfRange(a, from, to)).trim();
    }

    static void need(String[] a, int n, String usage) {
        if (a.length < n) throw new CommandError("Usage: " + usage);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    public static void print(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal("§c§lEVADE §8» §r" + msg));
    }
}
