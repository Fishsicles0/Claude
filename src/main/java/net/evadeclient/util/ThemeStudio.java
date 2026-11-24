package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.minecraft.client.MinecraftClient;

import java.util.Arrays;
import java.util.Locale;

/**
 * Custom menu themes on top of the presets: any accent colour (Accent Color: Custom), and optional window, card and
 * text colours that replace the preset's (a colour with no alpha means "follow the preset"). Themes travel as short
 * {@code EVTH1:} codes: preset, accent, then the four colours in hex (0 = follow).
 */
public final class ThemeStudio {
    private ThemeStudio() {}

    public static final String PREFIX = "EVTH1:";
    public static final String CUSTOM = "Custom";
    private static String appliedKey = "";

    /** Accent Color's choices: the preset accents plus Custom. */
    public static String[] accentOptions() {
        String[] out = Arrays.copyOf(Theme.ACCENTS, Theme.ACCENTS.length + 1);
        out[out.length - 1] = CUSTOM;
        return out;
    }

    /** Applies the settings' theme (cheap no-op when nothing changed). */
    public static void apply(ClientSettings cs) {
        String preset = cs.theme.get(), accent = cs.accent.get();
        boolean custom = CUSTOM.equals(accent);
        int ca = cs.customAccent.get(), win = cs.windowColor.get(), card = cs.cardColor.get(), text = cs.textColor.get();
        String key = preset + "/" + accent + "/" + (custom ? ca : 0) + "/" + win + "/" + card + "/" + text;
        if (key.equals(appliedKey)) return;
        appliedKey = key;
        Theme.invalidate();
        Theme.apply(preset, custom ? "Red" : accent);
        if (custom) accent(0xFF000000 | ca);
        boolean surfaces = false;
        if (set(win)) {
            int w = 0xFF000000 | win;
            Theme.BG = 0xF2000000 | (win & 0xFFFFFF);
            Theme.SIDEBAR = Theme.lerpColor(w, 0xFFFFFFFF, 0.02f);
            Theme.INPUT = Theme.lerpColor(w, 0xFFFFFFFF, 0.03f);
            if (!set(card)) cards(Theme.lerpColor(w, 0xFFFFFFFF, 0.06f));
            surfaces = true;
        }
        if (set(card)) {
            cards(0xFF000000 | card);
            surfaces = true;
        }
        if (surfaces) {
            Theme.DIVIDER = Theme.CARD_HOVER;
            Theme.TRACK = Theme.lerpColor(Theme.CONTROL_HOVER, 0xFF808080, 0.12f);
            Theme.BORDER_HOVER = Theme.lerpColor(Theme.BORDER, 0xFFFFFFFF, 0.12f);
            Theme.ROW = Theme.lerpColor(Theme.INPUT, Theme.CARD, 0.4f);
            Theme.ROW_HOVER = Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, 0.4f);
            Theme.HUD_BG = 0x90000000 | (Theme.BG & 0xFFFFFF);
        }
        if (set(text)) {
            int t = 0xFF000000 | text, bg = 0xFF000000 | Theme.BG;
            Theme.TEXT = t;
            Theme.TEXT_SOFT = Theme.lerpColor(t, bg, 0.32f);
            Theme.TEXT_DIM = Theme.lerpColor(t, bg, 0.38f);
            Theme.TEXT_HEADER = Theme.lerpColor(t, bg, 0.55f);
            Theme.TEXT_FAINT = Theme.lerpColor(t, bg, 0.65f);
        }
    }

    private static boolean set(int c) { return c >>> 24 != 0; }

    private static void accent(int c) {
        Theme.RED = c;
        Theme.RED_BRIGHT = Theme.brighten(c);
        Theme.RED_DARK = Theme.lerpColor(c, 0xFF000000, 0.46f);
        Theme.ON_ACCENT = Theme.relLum(c) > 0.30f ? 0xFF141418 : 0xFFFFFFFF;
    }

    private static void cards(int card) {
        Theme.CARD = card;
        Theme.CARD_HOVER = Theme.lerpColor(card, 0xFFFFFFFF, 0.05f);
        Theme.BORDER = Theme.lerpColor(card, 0xFFFFFFFF, 0.10f);
        Theme.CONTROL = Theme.lerpColor(card, 0xFFFFFFFF, 0.02f);
        Theme.CONTROL_HOVER = Theme.lerpColor(card, 0xFFFFFFFF, 0.08f);
    }

    // ------------------------------------------------------------------ codes

    /** The settings' theme as an EVTH1: code. */
    public static String export(ClientSettings cs) {
        return PREFIX + cs.theme.get() + "," + cs.accent.get() + "," + hex(cs.customAccent.get(), true) + ","
                + hex(cs.windowColor.get(), false) + "," + hex(cs.cardColor.get(), false) + "," + hex(cs.textColor.get(), false);
    }

    private static String hex(int c, boolean always) {
        if (!always && !set(c)) return "0";
        return String.format(Locale.ROOT, "%06X", c & 0xFFFFFF);
    }

    /**
     * Applies an EVTH1: code to the settings. Returns null on success, or what was wrong with it. Nothing changes
     * unless the whole code is valid.
     */
    public static String importCode(ClientSettings cs, String code) {
        if (code == null) return "No code";
        code = code.strip();
        if (!code.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) return "Not an Evade theme code (EVTH1:...)";
        String[] p = code.substring(PREFIX.length()).split(",", -1);
        if (p.length != 6) return "That theme code is incomplete";
        String preset = match(Theme.PRESETS, p[0]), accent = match(accentOptions(), p[1]);
        if (preset == null) return "Unknown theme preset: " + p[0];
        if (accent == null) return "Unknown accent: " + p[1];
        int[] c = new int[4];
        for (int i = 0; i < 4; i++) {
            String h = p[2 + i].strip();
            if (h.equals("0") && i > 0) { c[i] = 0; continue; }
            if (!h.matches("[0-9a-fA-F]{6}")) return "Bad colour in theme code: " + h;
            c[i] = 0xFF000000 | Integer.parseInt(h, 16);
        }
        cs.theme.set(preset);
        cs.accent.set(accent);
        cs.customAccent.set(c[0]);
        cs.windowColor.set(c[1]);
        cs.cardColor.set(c[2]);
        cs.textColor.set(c[3]);
        apply(cs);
        if (EvadeClient.config != null) EvadeClient.config.save();
        return null;
    }

    private static String match(String[] options, String s) {
        for (String o : options) if (o.equalsIgnoreCase(s.strip())) return o;
        return null;
    }

    /** Copy Theme Code: to the clipboard, with a toast. */
    public static void copy(ClientSettings cs) {
        MinecraftClient.getInstance().keyboard.setClipboard(export(cs));
        Notifications.push("Theme", "Theme code copied", true);
    }

    /** Paste Theme Code: from the clipboard, with a toast saying how it went. */
    public static void paste(ClientSettings cs) {
        String err = importCode(cs, MinecraftClient.getInstance().keyboard.getClipboard());
        Notifications.push("Theme", err == null ? "Theme applied" : err, err == null);
    }
}
