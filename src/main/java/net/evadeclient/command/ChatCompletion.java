package net.evadeclient.command;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.setting.StatusSetting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tab completion for Evade's dot commands: command words, mod names, setting names, setting values (modes and
 * on/off), waypoint names and profile names. {@link #complete} says which part of the typed text is being completed
 * and what could go there; the chat screen shows the first option as grey ghost text and Tab cycles through them.
 * Nothing here sends or changes anything.
 */
public final class ChatCompletion {
    private ChatCompletion() {}

    /** Options for the text from {@code start} to the end of what was typed. Empty when there is nothing to offer. */
    public record Result(int start, String partial, List<String> options) {
        static final Result NONE = new Result(0, "", List.of());

        /** Grey text to show after the cursor: the rest of the first option, when it starts with what was typed. */
        public String ghost() {
            if (options.isEmpty()) return null;
            String first = options.getFirst();
            if (first.length() <= partial.length() || !first.regionMatches(true, 0, partial, 0, partial.length())) return null;
            return first.substring(partial.length());
        }

        /** The whole chat text with option {@code i} in place of the partial. */
        public String apply(String text, int i) {
            return text.substring(0, start) + options.get(Math.floorMod(i, options.size()));
        }
    }

    private static final List<String> WP_SUBS = List.of("add", "del", "list", "near", "color", "hide", "show", "rename",
            "share", "target", "clear", "gui");
    private static final List<String> WP_NAMED = List.of("del", "delete", "remove", "color", "colour", "hide", "show",
            "unhide", "rename", "share", "target", "goto", "go");
    private static final List<String> COLORS = List.of("red", "orange", "yellow", "green", "cyan", "blue", "purple",
            "pink", "white", "gray", "default");
    private static final List<String> PROFILE_SUBS = List.of("list", "load", "save", "new", "delete");

    /** What could complete {@code text} (a chat line starting with the command prefix). */
    public static Result complete(String text) {
        try {
            return completeUnsafe(text);
        } catch (RuntimeException e) {
            return Result.NONE; // completion must never get in the way of typing
        }
    }

    private static Result completeUnsafe(String text) {
        if (text == null || !text.startsWith(CommandManager.PREFIX) || text.startsWith(CommandManager.PREFIX + CommandManager.PREFIX)
                || EvadeClient.modules == null || EvadeClient.commands == null) return Result.NONE;
        int p = CommandManager.PREFIX.length();
        int space = text.indexOf(' ');
        if (space < 0) return options(p, text.substring(p), commandWords());
        String word = text.substring(p, space).toLowerCase(Locale.ROOT);
        int argStart = space + 1;
        String rest = text.substring(argStart);
        return switch (word) {
            case "t", "toggle", "share" -> mods(argStart, rest);
            case "bind" -> {
                Tail t = afterModule(argStart, rest);
                if (t == null) yield mods(argStart, rest);
                List<String> keys = new ArrayList<>(List.of("none"));
                if (t.module.getCategory() != Category.CLIENT) for (int b = 3; b <= 8; b++) keys.add("mouse" + b);
                yield options(t.start, t.partial, keys);
            }
            case "set", "reset" -> settingCompletion(argStart, rest, word.equals("set"));
            case "wp", "waypoint", "waypoints" -> waypoint(argStart, rest);
            case "profile", "profiles" -> {
                int sp = rest.indexOf(' ');
                if (sp < 0) {
                    List<String> opts = new ArrayList<>(PROFILE_SUBS);
                    opts.addAll(profileNames());
                    yield options(argStart, rest, opts);
                }
                String sub = rest.substring(0, sp).toLowerCase(Locale.ROOT);
                if (sub.equals("load") || sub.equals("use") || sub.equals("switch") || sub.startsWith("del") || sub.equals("remove"))
                    yield options(argStart + sp + 1, rest.substring(sp + 1), profileNames());
                yield Result.NONE;
            }
            case "binds", "keybinds" -> rest.contains(" ") ? Result.NONE : options(argStart, rest, List.of("conflicts", "gui"));
            case "bench", "benchmark" -> rest.contains(" ") ? Result.NONE : options(argStart, rest, List.of("all", "stop", "10", "30", "60"));
            case "clips", "clip" -> rest.contains(" ") ? Result.NONE : options(argStart, rest, List.of("last", "save"));
            case "coords" -> rest.contains(" ") ? Result.NONE : options(argStart, rest, List.of("copy", "share"));
            default -> Result.NONE;
        };
    }

    /** Built-in command words, then mods' own command words, without duplicates. */
    private static List<String> commandWords() {
        Set<String> out = new LinkedHashSet<>(List.of("toggle", "bind", "binds", "set", "reset", "wp", "profile", "share",
                "import", "modules", "menu", "nick", "help", "bench", "clip", "clips", "voice", "save", "load", "setup"));
        out.addAll(CommandManager.COMMANDS.stream().sorted().toList());
        for (Module m : EvadeClient.modules.all()) for (String a : m.commandAliases()) out.add(a.toLowerCase(Locale.ROOT));
        return new ArrayList<>(out);
    }

    private static List<String> modNames() {
        List<String> out = new ArrayList<>();
        for (Module m : EvadeClient.modules.all()) if (m.getCategory() != Category.CLIENT) out.add(m.getName());
        return out;
    }

    private static Result mods(int start, String partial) {
        return options(start, partial, modNames());
    }

    /** A mod already typed in full (followed by a space), and where what comes after it starts. */
    private record Tail(Module module, int start, String partial) {}

    private static Tail afterModule(int start, String rest) {
        String[] words = rest.split(" ", -1);
        for (int k = words.length - 1; k >= 1; k--) {
            String name = String.join(" ", java.util.Arrays.copyOfRange(words, 0, k));
            Module m = EvadeClient.modules.get(name);
            if (m == null) continue;
            int consumed = name.length() + 1;
            return new Tail(m, start + consumed, rest.substring(consumed));
        }
        return null;
    }

    /** {@code .set <mod> <setting> <value>} and {@code .reset <mod> <setting>}. */
    private static Result settingCompletion(int start, String rest, boolean values) {
        Tail t = afterModule(start, rest);
        if (t == null) {
            List<String> opts = modNames();
            if (values) opts.add("Client Settings");
            return options(start, rest, opts);
        }
        List<Setting<?>> shown = new ArrayList<>();
        for (Setting<?> s : t.module.getSettings()) if (!s.isHidden() && !(s instanceof StatusSetting)) shown.add(s);
        // A setting typed in full and a space: offer its values.
        if (values) {
            String[] words = t.partial.split(" ", -1);
            for (int k = words.length - 1; k >= 1; k--) {
                String name = String.join(" ", java.util.Arrays.copyOfRange(words, 0, k));
                Setting<?> s = CommandManager.setting(t.module, name);
                if (s == null) continue;
                int consumed = name.length() + 1;
                return options(t.start + consumed, t.partial.substring(consumed), valueOptions(s));
            }
        }
        List<String> names = new ArrayList<>();
        for (Setting<?> s : shown) names.add(s.getName());
        return options(t.start, t.partial, names);
    }

    private static List<String> valueOptions(Setting<?> s) {
        return switch (s) {
            case BooleanSetting b -> List.of(b.get() ? "off" : "on", b.get() ? "on" : "off", "toggle");
            case ModeSetting md -> {
                // The next option first, so Tab moves the value along like clicking the chip does.
                List<String> modes = CommandManager.modeOptions(md);
                int i = modes.indexOf(md.get());
                List<String> out = new ArrayList<>();
                for (int k = 1; k <= modes.size(); k++) out.add(modes.get((i + k) % modes.size()));
                yield out;
            }
            case KeySetting k -> List.of("none");
            default -> List.of();
        };
    }

    private static Result waypoint(int start, String rest) {
        int sp = rest.indexOf(' ');
        if (sp < 0) return options(start, rest, WP_SUBS);
        String sub = rest.substring(0, sp).toLowerCase(Locale.ROOT);
        String after = rest.substring(sp + 1);
        int at = start + sp + 1;
        if (!WP_NAMED.contains(sub)) return Result.NONE;
        List<String> names = waypointNames();
        if (sub.equals("hide") || sub.equals("show") || sub.equals("unhide")) names.add(0, "all");
        if (sub.equals("target") || sub.equals("goto") || sub.equals("go")) names.add("off");
        if (sub.startsWith("colo")) {
            // ".wp color <name> <colour>": once a whole name and a space are typed, colours.
            for (String n : waypointNames()) {
                if (after.length() > n.length() && after.regionMatches(true, 0, n, 0, n.length()) && after.charAt(n.length()) == ' ')
                    return options(at + n.length() + 1, after.substring(n.length() + 1), COLORS);
            }
        }
        return options(at, after, names);
    }

    private static List<String> waypointNames() {
        var wp = EvadeClient.modules.get(net.evadeclient.module.visuals.Waypoints.class);
        List<String> out = new ArrayList<>();
        if (wp == null) return out;
        for (var p : wp.allInWorld()) if (!out.contains(p.name())) out.add(p.name());
        return out;
    }

    private static List<String> profileNames() {
        return EvadeClient.profiles == null ? new ArrayList<>() : EvadeClient.profiles.list();
    }

    /**
     * Options matching {@code partial}: those that start with it (as typed) first, then those that start with it
     * ignoring spaces and punctuation, then those containing it. An option equal to the partial is still offered
     * (so Tab can cycle on from it).
     */
    static Result options(int start, String partial, List<String> all) {
        String lower = partial.toLowerCase(Locale.ROOT), norm = ModuleManager.normalize(partial);
        List<String> exact = new ArrayList<>(), loose = new ArrayList<>(), inside = new ArrayList<>();
        for (String o : all) {
            String ol = o.toLowerCase(Locale.ROOT);
            if (ol.startsWith(lower)) exact.add(o);
            else if (!norm.isEmpty() && ModuleManager.normalize(o).startsWith(norm)) loose.add(o);
            else if (norm.length() >= 2 && ModuleManager.normalize(o).contains(norm)) inside.add(o);
        }
        // Exact match first so a finished word cycles on to the next one.
        exact.sort((a, b) -> Boolean.compare(!a.equalsIgnoreCase(partial), !b.equalsIgnoreCase(partial)));
        List<String> out = new ArrayList<>(exact);
        out.addAll(loose);
        out.addAll(inside);
        if (out.isEmpty()) return Result.NONE;
        return new Result(start, partial, out);
    }
}
