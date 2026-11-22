package net.evadeclient.command;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.stream.Collectors;

/** Client-side chat commands, prefixed with '.', never sent to the server. */
public class CommandManager {
    public static final String PREFIX = ".";

    /** @return false to cancel the chat message. */
    public boolean handleChat(String message) {
        if (!message.startsWith(PREFIX)) return true;
        String[] args = message.substring(PREFIX.length()).trim().split("\\s+");
        if (args.length == 0 || args[0].isEmpty()) return false;
        try {
            execute(args);
        } catch (Exception e) {
            print("§cError: " + e.getMessage());
        }
        return false;
    }

    private void execute(String[] a) {
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                print("§d.toggle <module>§7 - toggle a module");
                print("§d.bind <module> <key|none>§7 - set a keybind");
                print("§d.set <module> <setting> <value>§7 - change a setting");
                print("§d.modules§7 - list modules");
                print("§d.waypoint§7 - waypoints (add <name>, del <name>, list, clear)");
                print("§d.save§7 / §d.load§7 - config");
                print("§d.clips§7 [last|save]§7 - open Evade Clips, edit your last clip, or save one now");
            }
            case "clips", "clip" -> net.evadeclient.module.misc.Clips.command(a);
            case "t", "toggle" -> {
                Module m = module(a, 1);
                m.toggle();
                print(m.getName() + " " + (m.isEnabled() ? "§aenabled" : "§cdisabled"));
            }
            case "bind" -> {
                Module m = module(a, 1);
                need(a, 3);
                int key = parseKey(a[2]);
                m.setKey(key);
                print(m.getName() + " bound to §d" + (key == GLFW.GLFW_KEY_UNKNOWN ? "None" : a[2].toUpperCase(Locale.ROOT)));
                EvadeClient.config.save();
            }
            case "set" -> {
                Module m = module(a, 1);
                need(a, 4);
                Setting<?> s = m.getSettings().stream()
                        .filter(x -> x.getName().replace(" ", "").equalsIgnoreCase(a[2]))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("No setting " + a[2]));
                if (s instanceof BooleanSetting b) b.set(Boolean.parseBoolean(a[3]) || a[3].equalsIgnoreCase("on"));
                else if (s instanceof NumberSetting n) n.set(Double.parseDouble(a[3]));
                else if (s instanceof ModeSetting md) md.fromJson(new com.google.gson.JsonPrimitive(a[3]));
                print(m.getName() + " " + s.getName() + " = §d" + s.display());
                EvadeClient.config.save();
            }
            case "waypoint", "waypoints", "wp" -> EvadeClient.modules.get(net.evadeclient.module.visuals.Waypoints.class).command(a);
            case "modules", "list" -> print(EvadeClient.modules.all().stream()
                    .map(m -> (m.isEnabled() ? "§a" : "§7") + m.getName())
                    .collect(Collectors.joining("§8, ")));
            case "save" -> { EvadeClient.config.save(); print("Config saved."); }
            case "load" -> { EvadeClient.config.load(); print("Config loaded."); }
            default -> print("§cUnknown command. Try .help");
        }
    }

    private Module module(String[] a, int i) {
        need(a, i + 1);
        Module m = EvadeClient.modules.get(a[i]);
        if (m == null) throw new IllegalArgumentException("No module named " + a[i]);
        return m;
    }

    private static void need(String[] a, int n) {
        if (a.length < n) throw new IllegalArgumentException("Not enough arguments. Try .help");
    }

    private static int parseKey(String name) {
        if (name.equalsIgnoreCase("none")) return GLFW.GLFW_KEY_UNKNOWN;
        String translation = "key.keyboard." + name.toLowerCase(Locale.ROOT)
                .replace("rshift", "right.shift").replace("lshift", "left.shift")
                .replace("rctrl", "right.control").replace("lctrl", "left.control")
                .replace("ralt", "right.alt").replace("lalt", "left.alt");
        InputUtil.Key key = InputUtil.fromTranslationKey(translation);
        if (key == InputUtil.UNKNOWN_KEY) throw new IllegalArgumentException("Unknown key " + name);
        return key.getCode();
    }

    public static void print(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal("\u00a7c\u00a7lEVADE \u00a78\u00bb \u00a7r" + msg));
    }
}
