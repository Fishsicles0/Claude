package net.evadeclient.command;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager.CommandError;
import net.evadeclient.config.ModShare;
import net.evadeclient.module.Category;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.setting.Setting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.util.Keybinds;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code .binds}, {@code .reset}, {@code .profile}, {@code .share}, {@code .import} and {@code .setup}. */
final class ExtraCommands {
    private ExtraCommands() {}

    private static void print(String s) { CommandManager.print(s); }

    // ---- .binds ---------------------------------------------------------

    static void binds(String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("gui") || sub.equals("menu") || sub.equals("page")) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.send(() -> mc.setScreen(new net.evadeclient.gui.KeybindsScreen(null)));
            return;
        }
        boolean onlyConflicts = sub.startsWith("conflict");
        List<Keybinds.Binding> all = Keybinds.all();
        List<String> lines = new ArrayList<>();
        for (Keybinds.Binding b : all) {
            if (b.game() || !b.bound()) continue;
            List<Keybinds.Binding> clash = Keybinds.conflicts(b, all);
            if (onlyConflicts && clash.isEmpty()) continue;
            StringBuilder line = new StringBuilder("§f").append(b.name()).append(" §8» §d").append(b.keyText());
            if (b.hold()) line.append(" §7(hold)");
            if (!clash.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Keybinds.Binding c : clash) names.add(c.name());
                line.append(" §c(also: ").append(String.join(", ", names)).append(")");
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            print(onlyConflicts ? "§aNo keybind conflicts." : "No Evade keybinds set. §d.bind <mod> <key>§7 or §d.binds gui");
            return;
        }
        print((onlyConflicts ? "Conflicting keybinds" : "Evade keybinds") + " §8(" + lines.size() + ")§7:");
        for (String l : lines) print(l);
        if (!onlyConflicts) print("§7Open the Keybinds page with §d.binds gui");
    }

    // ---- .reset ---------------------------------------------------------

    static void reset(String[] a) {
        CommandManager.need(a, 2, ".reset <mod> [setting]");
        // The longest mod name that leaves a setting name after it (or nothing).
        for (int end = a.length; end > 1; end--) {
            Module m = EvadeClient.modules.get(CommandManager.join(a, 1, end));
            if (m == null) continue;
            if (end == a.length) {
                int n = 0;
                for (Setting<?> s : m.getSettings()) {
                    if (s.isHidden() || s instanceof StatusSetting || s.isDefault()) continue;
                    s.reset();
                    n++;
                }
                if (m instanceof HudElementModule h) h.resetPosition();
                EvadeClient.config.save();
                print(m.getName() + ": " + (n == 0 ? "already at defaults." : "reset " + n + " setting" + (n == 1 ? "" : "s") + " to default."));
                return;
            }
            Setting<?> s = CommandManager.setting(m, CommandManager.join(a, end, a.length));
            if (s != null) {
                if (s instanceof StatusSetting) throw new CommandError(s.getName() + " is read-only.");
                s.reset();
                EvadeClient.config.save();
                print(m.getName() + " " + s.getName() + " = §d" + s.display() + " §7(default)");
                return;
            }
        }
        throw CommandManager.notFound(CommandManager.join(a, 1, a.length));
    }

    // ---- .profile -------------------------------------------------------

    static void profile(String[] a) {
        var pm = EvadeClient.profiles;
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        String name = CommandManager.join(a, 2, a.length);
        switch (sub) {
            case "list", "ls" -> {
                List<String> parts = new ArrayList<>();
                for (String p : pm.list()) parts.add(p.equals(pm.active()) ? "§a" + p + " §7(active)" : "§f" + p);
                print("Profiles: " + String.join("§8, ", parts));
            }
            case "load", "use", "switch" -> {
                String p = existing(name, "Usage: .profile load <name>");
                if (p.equals(pm.active())) { print("§d" + p + "§7 is already active."); return; }
                if (pm.switchTo(p)) print("Switched to profile §d" + p);
                else throw new CommandError("Couldn't load " + p + ".");
            }
            case "save" -> {
                if (name.isEmpty() || name.equalsIgnoreCase(pm.active())) {
                    EvadeClient.config.save();
                    print("Saved profile §d" + pm.active());
                } else if (pm.exists(name)) {
                    throw new CommandError("A profile called " + name + " exists. Load it first, or pick a new name.");
                } else if (pm.create(name)) {
                    print("Saved your current setup as §d" + net.evadeclient.config.ProfileManager.sanitize(name) + "§7 (now active).");
                } else throw new CommandError("Can't use that name.");
            }
            case "new", "create" -> {
                if (name.isEmpty()) throw new CommandError("Usage: .profile new <name>");
                if (pm.create(name)) print("Created profile §d" + net.evadeclient.config.ProfileManager.sanitize(name) + "§7 from your current setup.");
                else throw new CommandError("That name is taken or can't be used.");
            }
            case "delete", "del", "remove" -> {
                String p = existing(name, "Usage: .profile delete <name>");
                if (pm.delete(p)) print("Deleted profile §d" + p);
                else throw new CommandError("Can't delete " + p + " (the active profile or the last one can't be deleted).");
            }
            default -> {
                // ".profile PvP" loads it.
                String p = find(CommandManager.join(a, 1, a.length));
                if (p == null) throw new CommandError("Usage: .profile [list|load|save|new|delete] [name]");
                if (pm.switchTo(p)) print("Switched to profile §d" + p);
                else print("§d" + p + "§7 is already active.");
            }
        }
    }

    private static String existing(String name, String usage) {
        if (name.isEmpty()) throw new CommandError(usage);
        String p = find(name);
        if (p == null) throw new CommandError("No profile called " + name + ". Try .profile list");
        return p;
    }

    /** A profile by name, ignoring case. */
    static String find(String name) {
        for (String p : EvadeClient.profiles.list()) if (p.equalsIgnoreCase(name.strip())) return p;
        return null;
    }

    // ---- .share / .import -------------------------------------------------

    static void share(String[] a) {
        CommandManager.need(a, 2, ".share <mod>");
        Module m = EvadeClient.modules.get(CommandManager.join(a, 1, a.length));
        if (m == null) throw CommandManager.notFound(CommandManager.join(a, 1, a.length));
        if (m.getCategory() == Category.CLIENT) throw new CommandError("Client Settings can't be shared this way; share a profile instead.");
        String code = ModShare.code(m);
        if (code == null) throw new CommandError("Couldn't make a code for " + m.getName() + ".");
        MinecraftClient.getInstance().keyboard.setClipboard(code);
        print("Copied a code for §d" + m.getName() + "§7 (" + code.length() + " characters). Paste it with §d.import <code>§7 or on the mod's page.");
    }

    static void importCode(String[] a) {
        String text = CommandManager.join(a, 1, a.length);
        if (text.isEmpty()) text = MinecraftClient.getInstance().keyboard.getClipboard();
        ModShare.Result r = ModShare.apply(text, null);
        if (!r.ok()) throw new CommandError(r.error() + ".");
        print("Applied the code to §d" + r.module().getName() + "§7.");
    }

    // ---- .setup -----------------------------------------------------------

    static void setup() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new net.evadeclient.gui.SetupWizardScreen(mc.currentScreen)));
    }
}
