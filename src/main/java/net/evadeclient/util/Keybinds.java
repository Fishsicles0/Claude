package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.ModuleManager;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Every key binding in one list: Evade's mod toggle keys (with their Toggle / Hold mode and Ctrl, Shift, Alt
 * modifiers), Evade's key settings (Zoom Key, Menu Key, ...) and the game's own controls, plus who shares a key with
 * whom. Used by the Keybinds page, {@code .binds} and the menu's conflict warnings. Key codes are GLFW key codes, or
 * {@link KeySetting#mouseCode} for mouse buttons.
 */
public final class Keybinds {
    /** Modifier flags a mod bind can need held with its key. */
    public static final int CTRL = 1, SHIFT = 2, ALT = 4, ALL_MODS = CTRL | SHIFT | ALT;

    private Keybinds() {}

    /** What kind of thing a {@link Binding} changes. */
    public enum Kind { MOD, SETTING, GAME }

    /**
     * One binding: {@code owner} is the Module (its toggle key), the KeySetting or the game's KeyBinding. {@code mods}
     * is only ever set for mod toggle keys.
     */
    public record Binding(Kind kind, Object owner, String name, String group, int code, int mods, boolean hold) {
        public boolean bound() { return code != GLFW.GLFW_KEY_UNKNOWN; }
        public boolean game() { return kind == Kind.GAME; }
        /** "Ctrl + Shift + K", or "None". */
        public String keyText() { return comboName(code, mods); }
    }

    // ------------------------------------------------------------------ modifiers

    /** The modifier flags held right now. */
    public static int heldMods() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return 0;
        var w = mc.getWindow();
        int m = 0;
        if (InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_LEFT_CONTROL) || InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_RIGHT_CONTROL)) m |= CTRL;
        if (InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_LEFT_SHIFT) || InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_RIGHT_SHIFT)) m |= SHIFT;
        if (InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_LEFT_ALT) || InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_RIGHT_ALT)) m |= ALT;
        return m;
    }

    /** The modifier flag a key is (both sides count), or 0 for any other key. */
    public static int modifierOf(int code) {
        return switch (code) {
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> CTRL;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> SHIFT;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> ALT;
            default -> 0;
        };
    }

    /** "Ctrl + Shift + " style prefix for {@code mods} ("" for none). */
    public static String modsPrefix(int mods) {
        StringBuilder b = new StringBuilder();
        if ((mods & CTRL) != 0) b.append("Ctrl + ");
        if ((mods & SHIFT) != 0) b.append("Shift + ");
        if ((mods & ALT) != 0) b.append("Alt + ");
        return b.toString();
    }

    public static String comboName(int code, int mods) {
        if (code == GLFW.GLFW_KEY_UNKNOWN) return "None";
        return modsPrefix(mods) + KeySetting.keyName(code);
    }

    // ------------------------------------------------------------------ listing

    /** Every binding: mods (unbound ones too), then Evade key settings, then the game's controls. */
    public static List<Binding> all() {
        List<Binding> out = new ArrayList<>();
        if (EvadeClient.modules == null) return out;
        for (Module m : EvadeClient.modules.all()) {
            if (m.getCategory() == Category.CLIENT) continue;
            out.add(new Binding(Kind.MOD, m, m.getName(), "Evade mods", m.getKey(), m.getBindMods(), m.isHoldBind()));
        }
        for (Module m : EvadeClient.modules.all()) {
            for (Setting<?> s : m.getSettings()) {
                if (s.isHidden() || !(s instanceof KeySetting k)) continue;
                out.add(new Binding(Kind.SETTING, k, settingLabel(m, k), "Evade keys", k.get(), 0, false));
            }
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) {
            for (KeyBinding kb : mc.options.allKeys) {
                String group;
                try { group = kb.getCategory().getLabel().getString(); } catch (RuntimeException e) { group = "Game"; }
                out.add(new Binding(Kind.GAME, kb, Text.translatable(kb.getId()).getString(), group, gameCode(kb), 0, false));
            }
        }
        return out;
    }

    /** "Zoom Key", or "Waypoints Quick Add Key" when the setting's name doesn't already say which mod it is. */
    public static String settingLabel(Module m, KeySetting k) {
        boolean named = m instanceof ClientSettings || ModuleManager.normalize(k.getName()).startsWith(ModuleManager.normalize(m.getName()));
        return named ? k.getName() : m.getName() + " " + k.getName();
    }

    /** A game binding's key as Evade stores codes (mouse buttons as {@link KeySetting#mouseCode}). */
    public static int gameCode(KeyBinding kb) {
        if (kb.isUnbound()) return GLFW.GLFW_KEY_UNKNOWN;
        InputUtil.Key bound = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(kb);
        return switch (bound.getCategory()) {
            case KEYSYM -> bound.getCode();
            case MOUSE -> KeySetting.mouseCode(bound.getCode());
            default -> GLFW.GLFW_KEY_UNKNOWN;
        };
    }

    // ------------------------------------------------------------------ conflicts

    /**
     * Whether two bindings fire on the same press. Evade binds clash when key and modifiers are the same; a game
     * control has no modifiers and fires whatever else is held, so it clashes with any Evade bind on its key.
     * Two game controls on one key are the game's own business and are flagged too.
     */
    public static boolean clash(Binding a, Binding b) {
        if (a.owner() == b.owner() || !a.bound() || !b.bound() || a.code() != b.code()) return false;
        if (a.game() || b.game()) return true;
        return a.mods() == b.mods();
    }

    /** The bindings in {@code all} that clash with {@code b}. */
    public static List<Binding> conflicts(Binding b, List<Binding> all) {
        List<Binding> out = new ArrayList<>();
        for (Binding o : all) if (clash(b, o)) out.add(o);
        return out;
    }

    /** Names of everything that clashes with a mod bind of {@code code} + {@code mods} ({@code self} left out). */
    public static List<String> conflictNames(int code, int mods, Object self) {
        Binding probe = new Binding(Kind.MOD, self, "", "", code, mods, false);
        List<String> out = new ArrayList<>();
        for (Binding o : all()) if (clash(probe, o)) out.add(o.name());
        return out;
    }

    /** How many bindings clash with at least one other one. */
    public static int conflictCount() {
        List<Binding> all = all();
        int n = 0;
        for (Binding b : all) if (!conflicts(b, all).isEmpty()) n++;
        return n;
    }

    // ------------------------------------------------------------------ changing

    /** Bind {@code b} to {@code code} (keeping a mod's modifiers). False when that binding can't take it. */
    public static boolean rebind(Binding b, int code) {
        switch (b.owner()) {
            case Module m -> {
                if (code != GLFW.GLFW_KEY_UNKNOWN && !KeySetting.isValidKey(code) && !KeySetting.isValidMouse(code)) return false;
                m.setKey(code);
            }
            case KeySetting k -> {
                if (!k.accepts(code)) return false;
                if (code == GLFW.GLFW_KEY_UNKNOWN && EvadeClient.modules.get(ClientSettings.class).menuKey == k) return false;
                k.set(code);
            }
            case KeyBinding kb -> {
                InputUtil.Key key = code == GLFW.GLFW_KEY_UNKNOWN ? InputUtil.UNKNOWN_KEY
                        : KeySetting.isMouse(code) ? InputUtil.Type.MOUSE.createFromCode(KeySetting.mouseButton(code))
                        : InputUtil.Type.KEYSYM.createFromCode(code);
                setGameKey(kb, key);
            }
            default -> { return false; }
        }
        saveAfter(b);
        return true;
    }

    /** Put {@code b} back to its default key (and, for a mod, a plain toggle bind). */
    public static void reset(Binding b) {
        switch (b.owner()) {
            case Module m -> {
                m.setKey(EvadeClient.config.defaultKey(m));
                m.setBindMods(0);
                m.setHoldBind(false);
            }
            case KeySetting k -> k.reset();
            case KeyBinding kb -> setGameKey(kb, kb.getDefaultKey());
            default -> {}
        }
        saveAfter(b);
    }

    /** Whether {@code b} is at its default. */
    public static boolean isDefault(Binding b) {
        return switch (b.owner()) {
            case Module m -> m.getKey() == EvadeClient.config.defaultKey(m) && m.getBindMods() == 0 && !m.isHoldBind();
            case KeySetting k -> k.isDefault();
            case KeyBinding kb -> kb.isDefault();
            default -> true;
        };
    }

    private static void setGameKey(KeyBinding kb, InputUtil.Key key) {
        kb.setBoundKey(key);
        KeyBinding.updateKeysByCode();
    }

    private static void saveAfter(Binding b) {
        if (b.game()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null) mc.options.write();
        } else if (EvadeClient.config != null) {
            EvadeClient.config.save();
        }
    }
}
