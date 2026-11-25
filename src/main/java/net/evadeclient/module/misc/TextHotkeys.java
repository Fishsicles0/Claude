package net.evadeclient.module.misc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.util.Keybinds;
import net.evadeclient.util.Notifications;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Up to twenty keys (or Ctrl / Shift / Alt combos, or a mouse side button) that each send one chat message or
 * command you typed yourself ("/hub", "gg"): one-click macros. One press sends it once: holding the key never repeats
 * it, a hotkey never sends more than its one line, and at most one message goes out every 1.5 seconds whichever keys
 * you press, so a hotkey can never flood a server. Nothing is ever sent on its own (no timers, no reacting to chat or
 * events), only on your key press, and only while no menu or chat is open. A hotkey can be limited to the servers it
 * is meant for, so "/hub" only fires where /hub exists. The only placeholders are your own harmless info: {server}
 * and {time}; never coordinates or anything about other players.
 */
public class TextHotkeys extends Module {
    /** Most hotkeys a player can have. */
    public static final int MAX_HOTKEYS = 20;
    /** How many slots the old fixed layout had ("Hotkey 1 Key" ... "Hotkey 9 Text"), for migrating old configs. */
    public static final int OLD_SLOTS = 9;
    /** At most one message per this many milliseconds, across every hotkey. */
    public static final long MIN_GAP_MS = 1500;
    /** Longest message a player can type in chat. */
    public static final int MAX_CHARS = 256;
    public static final int MAX_LABEL = 32, MAX_SERVERS = 160;
    /** Text the /msg preset leaves for the player to replace; a hotkey still holding it is never sent. */
    public static final String NAME_SLOT = "<name>";

    /** One-click starting points in the editor: {label, text}. The player edits them after adding. */
    public static final List<String[]> PRESETS = List.of(
            new String[]{"GG", "gg"},
            new String[]{"Hub", "/hub"},
            new String[]{"Lobby", "/lobby"},
            new String[]{"Party Accept", "/party accept"},
            new String[]{"Party List", "/p list"},
            new String[]{"Message", "/msg " + NAME_SLOT + " "});

    /** One hotkey. Mutable: the editor changes it in place, then the module saves. */
    public static final class Hotkey {
        public int key = GLFW.GLFW_KEY_UNKNOWN;
        /** {@link Keybinds} modifier flags needed with {@link #key}. */
        public int mods;
        public String text = "";
        /** Shown in the confirmation toast and the editor list; empty uses the text. */
        public String label = "";
        /** Address parts separated by |: the hotkey only works on a server whose address contains one. Empty = everywhere. */
        public String servers = "";
        public boolean enabled = true;

        public Hotkey() {}

        public Hotkey(String label, String text) {
            this.label = clean(label, MAX_LABEL);
            this.text = clean(text, MAX_CHARS);
        }

        public boolean bound() { return key != GLFW.GLFW_KEY_UNKNOWN; }

        /** "Ctrl + K", or "None". */
        public String keyText() { return Keybinds.comboName(key, mods); }

        /** The label, or the text when there is none, or "Empty hotkey". */
        public String title() {
            if (!label.isBlank()) return label.strip();
            return text.isBlank() ? "Empty hotkey" : text.strip();
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            if (mods != 0) o.addProperty("mods", mods);
            o.addProperty("text", text);
            if (!label.isEmpty()) o.addProperty("label", label);
            if (!servers.isEmpty()) o.addProperty("servers", servers);
            if (!enabled) o.addProperty("enabled", false);
            return o;
        }

        /** Reads one saved hotkey, keeping only what a hotkey can hold (a config or profile can hold anything). */
        static Hotkey fromJson(JsonObject o) {
            Hotkey h = new Hotkey();
            try { int k = o.get("key").getAsInt(); if (validKey(k)) h.key = k; } catch (RuntimeException ignored) {}
            try { if (o.has("mods")) h.mods = o.get("mods").getAsInt() & Keybinds.ALL_MODS; } catch (RuntimeException ignored) {}
            h.text = clean(str(o, "text"), MAX_CHARS);
            h.label = clean(str(o, "label"), MAX_LABEL);
            h.servers = clean(str(o, "servers"), MAX_SERVERS);
            try { if (o.has("enabled")) h.enabled = o.get("enabled").getAsBoolean(); } catch (RuntimeException ignored) {}
            if (!h.bound()) h.mods = 0;
            return h;
        }

        private static String str(JsonObject o, String k) {
            try { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? "" : e.getAsString(); } catch (RuntimeException e) { return ""; }
        }
    }

    /** The hotkey list, saved with the config as a JSON array (hidden: the editor screen shows it). */
    public static final class HotkeyList extends Setting<List<Hotkey>> {
        HotkeyList() { super("Hotkeys", List.of()); }

        /** The list to change (copied off the shared default on first change). */
        public List<Hotkey> edit() {
            if (!(value instanceof ArrayList)) value = new ArrayList<>(value);
            return value;
        }

        @Override public void set(List<Hotkey> v) { value = v == null ? List.of() : new ArrayList<>(v); }
        @Override public boolean isDefault() { return value.isEmpty(); }

        @Override
        public JsonElement toJson() {
            JsonArray a = new JsonArray();
            for (Hotkey h : value) a.add(h.toJson());
            return a;
        }

        @Override
        public void fromJson(JsonElement json) {
            List<Hotkey> out = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray()) {
                if (out.size() >= MAX_HOTKEYS) break;
                if (e != null && e.isJsonObject()) out.add(Hotkey.fromJson(e.getAsJsonObject()));
            }
            value = out;
        }

        @Override public String display() { return value.size() + " hotkeys"; }
    }

    private final HotkeyList list = add(new HotkeyList().hidden());
    private final StatusSetting editor = add((StatusSetting) new StatusSetting("Edit Hotkeys", this::editorLabel, "click: open")
            .onClick(() -> openEditor(mc.currentScreen))
            .describe("Add, change, reorder and test your one-click chat macros (auto text, autotext) in the hotkey editor (also .hotkeys)."));
    private final BooleanSetting confirm = add(new BooleanSetting("Confirmation", true)
            .describe("A small notification with what was sent (and the hotkey's label), or why a press was skipped."));
    private final BooleanSetting addToHistory = add(new BooleanSetting("Add To Chat History", true)
            .describe("Put sent messages in your chat history, so the up arrow in chat brings them back."));

    /** Whether each bound key code was down last time we looked (edge detection: one press, one send). */
    private final Map<Integer, Boolean> wasDown = new HashMap<>();
    private long lastSent = Long.MIN_VALUE / 2;
    /** Test hooks: what was last sent (with the slash for commands), how many were sent, and a fake server address. */
    public String lastSentForTest;
    public int sentCountForTest;
    public String serverForTest;

    public TextHotkeys() {
        super("Text Hotkeys", "One-click macros: keys that send a chat message or command you typed, once per press.", Category.SERVER);
    }

    public static TextHotkeys get() { return EvadeClient.modules == null ? null : EvadeClient.modules.get(TextHotkeys.class); }

    /** The hotkeys, in order (change them through {@link #edit()} and then {@link #save()}). */
    public List<Hotkey> hotkeys() { return list.get(); }

    public List<Hotkey> edit() { return list.edit(); }

    public void save() { if (EvadeClient.config != null) EvadeClient.config.save(); }

    private String editorLabel() {
        int n = hotkeys().size(), bound = 0;
        for (Hotkey h : hotkeys()) if (h.enabled && h.bound() && !h.text.isBlank()) bound++;
        return n == 0 ? "none yet" : bound + " of " + n + " ready";
    }

    public void openEditor(net.minecraft.client.gui.screen.Screen parent) {
        mc.setScreen(new net.evadeclient.gui.TextHotkeysScreen(parent));
    }

    // ------------------------------------------------------------------ list changes

    /** A new empty hotkey at the end, or null when the list is full. */
    public Hotkey addHotkey(String label, String text) {
        if (hotkeys().size() >= MAX_HOTKEYS) return null;
        Hotkey h = new Hotkey(label, text);
        edit().add(h);
        return h;
    }

    /** Adds preset {@code index} of {@link #PRESETS} (unbound, for the player to edit), or null when full. */
    public Hotkey addPreset(int index) {
        String[] p = PRESETS.get(index);
        return addHotkey(p[0], p[1]);
    }

    /** Moves hotkey {@code i} by {@code by} places (clamped). */
    public void move(int i, int by) {
        List<Hotkey> l = edit();
        int j = Math.max(0, Math.min(l.size() - 1, i + by));
        if (i < 0 || i >= l.size() || i == j) return;
        l.add(j, l.remove(i));
    }

    // ------------------------------------------------------------------ old configs

    /** Configs from before the editor had nine fixed slots ("Hotkey 1 Key", "Hotkey 1 Text", ...): bring them over. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (settings.has(list.getName())) return;
        boolean old = false;
        List<Hotkey> out = new ArrayList<>();
        for (int n = 1; n <= OLD_SLOTS; n++) {
            JsonElement k = settings.get("Hotkey " + n + " Key"), t = settings.get("Hotkey " + n + " Text");
            if (k == null && t == null) continue;
            old = true;
            Hotkey h = new Hotkey();
            try { if (k != null && validKey(k.getAsInt())) h.key = k.getAsInt(); } catch (RuntimeException ignored) {}
            try { if (t != null) h.text = clean(t.getAsString(), MAX_CHARS); } catch (RuntimeException ignored) {}
            if (h.bound() || !h.text.isBlank()) out.add(h);
        }
        if (old) list.set(out);
    }

    // ------------------------------------------------------------------ keybinds page

    /** The bound hotkeys as {@link Keybinds} entries, so the Keybinds page and conflict warnings see them. */
    public static List<Keybinds.Binding> bindings() {
        TextHotkeys t = get();
        List<Keybinds.Binding> out = new ArrayList<>();
        if (t == null) return out;
        for (Hotkey h : t.hotkeys()) {
            if (!h.enabled || !h.bound()) continue;
            out.add(new Keybinds.Binding(Keybinds.Kind.SETTING, h, "Text Hotkey: " + h.title(), "Text Hotkeys", h.key, h.mods, false));
        }
        return out;
    }

    /** Names of everything sharing {@code h}'s key combo: other hotkeys, mods, Evade keys and the game's controls. */
    public List<String> conflicts(Hotkey h) {
        List<String> out = new ArrayList<>();
        if (!h.bound()) return out;
        for (Hotkey o : hotkeys()) {
            if (o == h || !o.enabled || o.key != h.key || o.mods != h.mods) continue;
            out.add("Hotkey \"" + o.title() + "\"");
        }
        for (String n : Keybinds.conflictNames(h.key, h.mods, h)) if (!n.startsWith("Text Hotkey: ")) out.add(n);
        return out;
    }

    // ------------------------------------------------------------------ pressing

    @Override
    protected void onEnable() {
        // Keys already held when the module turns on don't count as a press.
        wasDown.clear();
        for (Hotkey h : hotkeys()) if (h.bound()) wasDown.put(h.key, down(h.key));
    }

    @Override
    public void onTick() { poll(); }

    /** Polled every frame too, so a tap shorter than a tick still counts (once). */
    @Override
    public void onRender(RenderContext ctx) { poll(); }

    private void poll() {
        boolean inGame = mc.currentScreen == null && mc.player != null;
        List<Integer> pressed = null;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Hotkey h : hotkeys()) {
            if (!h.bound() || !seen.add(h.key)) continue;
            boolean d = down(h.key);
            Boolean was = wasDown.put(h.key, d);
            // A key going down while a menu or chat is open is typing, not a hotkey.
            if (d && was != null && !was && inGame) {
                if (pressed == null) pressed = new ArrayList<>();
                pressed.add(h.key);
            }
        }
        wasDown.keySet().retainAll(seen);
        if (pressed == null) return;
        int held = Keybinds.heldMods();
        for (int code : pressed) press(code, held);
    }

    /**
     * Key {@code code} just went down with {@code held} modifiers: send the one hotkey it means. Of the enabled hotkeys
     * on that key that work on this server and whose modifiers are all held, only the most specific counts (so Ctrl + K
     * doesn't also send plain K's text), and of those only the first in the list: one press, one message.
     */
    void press(int code, int held) {
        Hotkey best = null;
        int bestBits = -1;
        String server = serverAddress();
        for (Hotkey h : hotkeys()) {
            if (!h.enabled || h.key != code || (h.mods & ~held) != 0 || !worksOn(h, server)) continue;
            int bits = Integer.bitCount(h.mods);
            if (bits > bestBits) { best = h; bestBits = bits; }
        }
        if (best != null) fire(best);
    }

    private void fire(Hotkey h) {
        String title = h.label.isBlank() ? getName() : h.label.strip();
        String text = h.text == null ? "" : h.text.strip();
        if (text.isEmpty() || text.equals("/")) {
            if (confirm.get()) Notifications.push(title, "This hotkey has no text yet", false);
            return;
        }
        if (text.contains(NAME_SLOT)) {
            if (confirm.get()) Notifications.push(title, "Replace " + NAME_SLOT + " with a name first (.hotkeys)", false);
            return;
        }
        text = expand(text);
        if (text.length() > MAX_CHARS) {
            if (confirm.get()) Notifications.push(title, "Too long once {server} and {time} are filled in", false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSent < MIN_GAP_MS) {
            if (confirm.get()) Notifications.push(title, "Wait a moment before sending again", false);
            return;
        }
        ClientPlayNetworkHandler net = mc.getNetworkHandler();
        if (net == null) return;
        lastSent = now;
        lastSentForTest = text;
        sentCountForTest++;
        if (addToHistory.get()) mc.inGameHud.getChatHud().addToMessageHistory(text);
        if (text.startsWith("/")) net.sendChatCommand(text.substring(1));
        else net.sendChatMessage(text);
        if (confirm.get()) Notifications.push(title, "Sent " + shorten(text), true);
    }

    // ------------------------------------------------------------------ servers and placeholders

    /** This server's address in lower case, or "" in singleplayer and on the title screen. */
    public String serverAddress() {
        if (serverForTest != null) return serverForTest.toLowerCase(Locale.ROOT);
        var e = mc.getCurrentServerEntry();
        if (e == null || mc.isInSingleplayer() || e.address == null) return "";
        return e.address.strip().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code h} works on {@code server} (from {@link #serverAddress()}): no filter, or a part it contains. */
    public static boolean worksOn(Hotkey h, String server) {
        String f = h.servers == null ? "" : h.servers.strip();
        if (f.isEmpty()) return true;
        for (String part : f.split("\\|")) {
            String p = part.strip().toLowerCase(Locale.ROOT);
            if (!p.isEmpty() && !server.isEmpty() && server.contains(p)) return true;
        }
        return false;
    }

    /**
     * Fills in the only two placeholders: {server} (the address you joined, "singleplayer" in your own world, or
     * "this server" while Streamer Mode hides addresses) and {time} (your clock, HH:mm). Nothing about where you
     * are in the world and nothing about other players.
     */
    public String expand(String text) {
        if (text.indexOf('{') < 0) return text;
        String out = text;
        if (out.contains("{server}")) {
            String s = serverAddress();
            if (s.isEmpty()) s = mc.isInSingleplayer() ? "singleplayer" : "this server";
            else if (StreamerMode.hidesServerAddress()) s = "this server";
            out = out.replace("{server}", s);
        }
        if (out.contains("{time}")) out = out.replace("{time}", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        return out;
    }

    // ------------------------------------------------------------------ command

    @Override public List<String> commandAliases() { return List.of("hotkeys", "macros"); }

    @Override
    public List<String> commandHelp() { return List.of("§d.hotkeys§7 - open the Text Hotkeys editor (one-click chat macros)"); }

    @Override
    public void command(String[] args) {
        mc.send(() -> openEditor(null));
        if (!isEnabled()) CommandManager.print("§7Text Hotkeys is off: turn it on (Server) for your hotkeys to send.");
    }

    // ------------------------------------------------------------------ helpers

    private static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 39) + "…";
    }

    /** A key a hotkey can use: a keyboard key or a middle/side mouse button. */
    public static boolean validKey(int code) {
        return KeySetting.isValidKey(code) || KeySetting.isValidMouse(code);
    }

    /** {@code s} without control characters (line breaks, tabs) and cut to {@code max} characters. */
    public static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0, n = 0; i < s.length() && n < max; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == '§') continue;
            out.appendCodePoint(cp);
            n++;
        }
        return out.toString();
    }

    private boolean down(int code) {
        if (code == GLFW.GLFW_KEY_UNKNOWN || mc.getWindow() == null) return false;
        if (KeySetting.isMouse(code))
            return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), KeySetting.mouseButton(code)) == GLFW.GLFW_PRESS;
        return InputUtil.isKeyPressed(mc.getWindow(), code);
    }

    /** Test hook: forget the 1.5 s gap, as if the last message went out long ago. */
    public void resetGapForTest() { lastSent = Long.MIN_VALUE / 2; }

    /** Test hook: acts as if {@code code} was pressed in game with {@code held} modifiers. */
    public void pressForTest(int code, int held) { press(code, held); }
}
