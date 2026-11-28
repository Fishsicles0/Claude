package net.evadeclient.gui.menu;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The glyph each module shows in its icon tile (menu rows, cards, the settings header). Picked from the nav icon set
 * ({@code textures/gui/nav}) by what the module does: a camera for camera mods, a map pin for waypoints, a keyboard
 * for key and text mods. Anything not matched gets its category's glyph. Cached per module.
 */
public final class ModuleIcons {
    private ModuleIcons() {}

    /** Keyword (lower case, matched in the module name) to icon. Checked in order: the first hit wins. */
    private static final String[][] KEYWORDS = {
            {"waypoint", "waypoint"}, {"world map", "map"}, {"minimap", "map"}, {"map", "map"}, {"compass", "map"},
            {"zoom", "camera"}, {"freelook", "camera"}, {"snaplook", "camera"}, {"camera", "camera"}, {"fov", "camera"},
            {"screenshot", "camera"}, {"clips", "camera"}, {"replay", "camera"}, {"motion blur", "camera"}, {"hurt cam", "camera"},
            {"keystroke", "keyboard"}, {"hotkey", "keyboard"}, {"text", "keyboard"}, {"chat", "keyboard"}, {"emoji", "keyboard"},
            {"togglesprint", "controls"}, {"sprint", "controls"}, {"crosshair", "controls"}, {"placement", "controls"},
            {"break guard", "controls"}, {"outline", "controls"},
            {"friend", "friends"}, {"party", "friends"}, {"voice", "friends"}, {"chat heads", "friends"}, {"discord", "friends"},
            {"streamer", "profiles"}, {"nick", "profiles"}, {"icons", "profiles"}, {"protection", "profiles"},
            {"cosmetic", "wardrobe"}, {"schematic", "schematic"}, {"pack", "mods"}, {"performance", "mods"},
            {"tooltip", "info"}, {"quick play", "server"}, {"auto", "server"}, {"server", "server"}, {"tip", "server"},
            {"tnt", "warn"}, {"damage", "warn"}, {"hit", "warn"}, {"boss", "warn"},
            {"title screen", "all"}, {"scoreboard", "hud"}, {"tab", "hud"}, {"hud", "hud"}, {"armor", "hud"},
            {"fps", "hud"}, {"cps", "hud"}, {"ping", "hud"}, {"coords", "hud"}, {"clock", "hud"}, {"timer", "hud"},
            {"fullbright", "visuals"}, {"fog", "visuals"}, {"render", "visuals"}, {"water", "visuals"},
            {"blur", "visuals"}, {"glint", "visuals"}, {"particle", "visuals"}, {"weather", "visuals"}, {"time", "visuals"},
    };

    private static final Map<Module, String> CACHE = new HashMap<>();

    /** The icon for {@code mod}. */
    public static String of(Module mod) {
        String c = CACHE.get(mod);
        if (c != null) return c;
        String name = mod.getName().toLowerCase(Locale.ROOT);
        String icon = null;
        for (String[] kw : KEYWORDS) if (name.contains(kw[0])) { icon = kw[1]; break; }
        if (icon == null) icon = category(mod.getCategory());
        CACHE.put(mod, icon);
        return icon;
    }

    /** The glyph of a category (the sidebar uses the same ones). */
    public static String category(Category c) {
        return switch (c) {
            case MODS -> "controls";
            case SERVER -> "server";
            case HUD -> "hud";
            case VISUALS -> "visuals";
            case CLIENT -> "settings";
            default -> "misc";
        };
    }
}
