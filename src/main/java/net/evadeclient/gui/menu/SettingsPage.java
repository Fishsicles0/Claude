package net.evadeclient.gui.menu;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.ItemListRow;
import net.evadeclient.gui.Motion;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Dropdown;
import net.evadeclient.gui.kit.KeyChip;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Segmented;
import net.evadeclient.gui.kit.Slider;
import net.evadeclient.gui.kit.Toggle;
import net.evadeclient.module.Category;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ItemListSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Keybinds;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.StringHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * A module's settings page (and Client Settings' own page). Header: back, the module's icon tile, its name with the
 * master switch right beside it, its description, and Reset once anything differs from the default. Rows sit in
 * sections: KEYS first (the toggle key chip, Toggle / Hold and the Ctrl / Shift / Alt modifiers on one row), then
 * the module's own groups ({@link Setting#group}) or, without them, groups by kind of control, then SHARE.
 *
 * <p>Every setting is a kit control: switches, sliders with a typed value chip, segmented choices or dropdowns
 * (never the old cyclers), key chips that listen for a key, colour swatches that open the colour picker, and text
 * fields. A setting that {@link Setting#dependsOn depends on} a switch is indented under it and dimmed while it is
 * off. Right-click a changed setting's name (or middle-click its row) to reset it.
 */
public final class SettingsPage {
    private static final int K_SECTION = 0, K_KEYBIND = 1, K_SETTING = 2, K_SHARE = 3;

    private record Row(int kind, Object item, String label, String desc, boolean dependent, int h) {}

    private final MenuHost host;
    private final List<Row> rows = new ArrayList<>();
    private Module built;
    private int builtCount = -1;

    /** The module (its keybind) or KeySetting waiting for a key, and since when. */
    private Object listening;
    private double listenAt;
    private Object rejectRow;
    private double rejectAt;
    private String rejectMsg = "";
    private Editor editor;
    private NumberSetting hoverSlider;

    private static final int ROW_H = 28, ROW_DESC_H = 38, SECTION_H = 26, SLIDER_W = 236;

    public SettingsPage(MenuHost host) { this.host = host; }

    private static ClientSettings cs() { return EvadeClient.modules.get(ClientSettings.class); }

    // ------------------------------------------------------------------ rows

    private void build(Module mod) {
        int count = mod.getSettings().size();
        if (mod == built && count == builtCount) return;
        built = mod;
        builtCount = count;
        rows.clear();
        boolean client = mod.getCategory() == Category.CLIENT;
        List<Setting<?>> keys = new ArrayList<>(), rest = new ArrayList<>();
        for (Setting<?> st : mod.getSettings()) {
            if (st.isHidden()) continue;
            if (st instanceof KeySetting && st.getGroup() == null) keys.add(st); else rest.add(st);
        }
        if (!client || !keys.isEmpty()) {
            section("Keys");
            if (!client) rows.add(new Row(K_KEYBIND, mod, "Toggle key", "Press it to switch " + mod.getName() + " on and off", false, ROW_DESC_H));
            for (Setting<?> st : keys) add(st);
        }
        Map<String, List<Setting<?>>> groups = new LinkedHashMap<>();
        boolean explicit = rest.stream().anyMatch(s -> s.getGroup() != null);
        for (Setting<?> st : rest) groups.computeIfAbsent(groupOf(st, explicit, rest.size()), g -> new ArrayList<>()).add(st);
        for (var e : groups.entrySet()) {
            section(e.getKey());
            for (Setting<?> st : e.getValue()) add(st);
        }
        if (!client) {
            section("Share");
            rows.add(new Row(K_SHARE, "share", "Share settings", "A code with this mod's settings, for chat or a message", false, ROW_DESC_H));
        }
    }

    /** The section of a setting: its own group, else a group by kind of control (only when there are many). */
    private static String groupOf(Setting<?> st, boolean explicit, int total) {
        if (st.getGroup() != null) return st.getGroup();
        if (explicit || total <= 6) return "Options";
        if (st instanceof NumberSetting) return "Adjust";
        if (st instanceof ColorSetting) return "Colours";
        if (st instanceof KeySetting) return "Keys";
        if (st instanceof StringSetting) return "More";
        return "Options";
    }

    private void section(String name) { rows.add(new Row(K_SECTION, null, name, null, false, SECTION_H)); }

    private void add(Setting<?> st) {
        String d = st.getDescription();
        boolean desc = d != null && !d.isEmpty();
        rows.add(new Row(K_SETTING, st, st.getName(), desc ? d : null, st.getDependsOn() != null, desc ? ROW_DESC_H : ROW_H));
    }

    // ------------------------------------------------------------------ render

    public void render(Kit k, Module mod, boolean modulePage) {
        build(mod);
        hoverSlider = null;
        int cx = host.cx(), cw = host.cw(), top = host.top();
        int listTop = top + 62, listBottom = host.cy() + host.ch();
        MenuScroll scroll = host.scroll();
        int contentH = 0;
        for (Row r : rows) contentH += r.h;
        scroll.clamp(contentH + 8, listBottom - listTop);

        int si = scroll.px();
        int right = cx + cw - 10;
        k.ctx.enableScissor(cx - 12, listTop, cx + cw + 12, listBottom);
        scroll.push(k.ctx);
        try {
            int y = listTop - si;
            boolean firstSection = true;
            for (Row r : rows) {
                if (y + r.h >= listTop && y <= listBottom) {
                    switch (r.kind) {
                        case K_SECTION -> {
                            if (!firstSection) Paint.fadeLine(k.ctx, cx, y + 3, cx + cw, Theme.alpha(0xFFFFFFFF, 0.05f));
                            UiFont.caps(k.ctx, k.tr, r.label.toUpperCase(java.util.Locale.ROOT), cx + 2, y + 12, Tokens.N700, Role.CAPS, 0.9f, k.smooth);
                        }
                        default -> row(k, mod, r, cx, y, cw, right, listTop, listBottom);
                    }
                }
                if (r.kind == K_SECTION) firstSection = false;
                y += r.h;
            }
        } finally {
            scroll.pop(k.ctx);
            k.ctx.disableScissor();
        }
        scroll.chrome(k, "setbar", cx - 12, listTop, cx + cw, listBottom);
        k.barrier("set#top", cx - 12, top, cx + cw + 20, listTop, null);
        k.barrier("set#bottom", cx - 12, listBottom, cx + cw + 20, listBottom + 40, null);
        header(k, mod, modulePage, cx, cw, top);
        Paint.fadeLine(k.ctx, cx - 8, listTop - 6, cx + cw + 8, Theme.alpha(0xFFFFFFFF, 0.06f));
    }

    private void header(Kit k, Module mod, boolean modulePage, int cx, int cw, int top) {
        int x = cx;
        float cy = top + 14;
        if (modulePage) {
            Button.icon(k, "back", x - 4, Math.round(cy - 12), 24, "chevron_l", "Back  (Esc)", true, host::leaveModulePage);
            x += 26;
        }
        // Icon tile: the module's glyph, accent while it is on.
        boolean on = modulePage && mod.isEnabled();
        Paint.surface(k.ctx, x, cy - 14, x + 28, cy + 14, Tokens.R_SM, Tokens.E3.noAmbient(), 0, Float.NaN);
        Icons.draw(k.ctx, modulePage ? ModuleIcons.of(mod) : "settings", x + 6, Math.round(cy - 8), 16, on ? Tokens.A400 : Tokens.N900);
        x += 28 + 10;
        boolean changed = hasChanged(mod);
        int resetW = changed ? Button.width(k, "Reset", Button.Size.SM, "reset", false) : 0;
        int titleMax = host.headerRight() - x - resetW - 70;
        String name = host.fit(modulePage ? mod.getName() : "Settings", Role.HEADLINE, titleMax);
        k.textCentredY(name, x, cy - 3, Tokens.N950, Role.HEADLINE);
        int nw = k.width(name, Role.HEADLINE);
        if (modulePage) {
            Toggle.draw(k, "master", x + nw + 12, Math.round(cy - 3 - Toggle.LG_H / 2f), true, mod.isEnabled(), true, mod::toggle);
            k.tooltip("master", mod.isEnabled() ? "On" : "Off", "Click or press Space to switch " + mod.getName(), x + nw + 12, Math.round(cy - 12), x + nw + 48, Math.round(cy + 6));
        }
        String blurb = net.evadeclient.util.ServerRules.isDisabled(mod) ? "Disabled by this server" : mod.getDescription();
        k.text(host.fit(blurb, Role.SMALL, host.headerRight() - x - resetW - 12), x, Math.round(cy + 9),
                net.evadeclient.util.ServerRules.isDisabled(mod) ? Tokens.WARN : Tokens.N700, Role.SMALL);
        if (changed) {
            Button.draw(k, "reset", host.headerRight() - resetW, Math.round(cy - 10), resetW, Button.Size.SM, Button.Variant.GHOST, "Reset", "reset", true, () -> {
                for (Setting<?> st : mod.getSettings()) st.reset();
                if (mod instanceof HudElementModule hem) hem.resetPosition();
                EvadeClient.config.save();
            });
            k.tooltip("reset", "Reset every setting", "Back to Evade's defaults", host.headerRight() - resetW, Math.round(cy - 10), host.headerRight(), Math.round(cy + 10));
        }
    }

    private static boolean hasChanged(Module mod) {
        for (Setting<?> st : mod.getSettings()) if (changed(st)) return true;
        return false;
    }

    private static boolean changed(Setting<?> st) {
        return !st.isHidden() && !(st instanceof StatusSetting) && !st.isDefault();
    }

    private static String rowId(Object item, Module mod) {
        return mod.getName() + "/" + (item instanceof Setting<?> st ? st.getName() : item instanceof Module ? "keybind" : String.valueOf(item));
    }

    private void row(Kit k, Module mod, Row r, int cx, int y, int cw, int right, int viewTop, int viewBottom) {
        String rid = rowId(r.item, mod);
        boolean inView = k.my >= viewTop && k.my < viewBottom;
        boolean hover = inView && k.mx >= cx - 4 && k.mx < cx + cw + 4 && k.my >= y && k.my < y + r.h - 2;
        float hov = k.anim("rh:" + rid, hover ? 1f : 0f);
        if (hov > 0.01f) Paint.rect(k.ctx, cx - 4, y, cx + cw + 4, y + r.h - 2, Tokens.R_SM, Theme.alpha(Tokens.N250, 0.5f * hov));
        boolean enabled = !(r.item instanceof Setting<?> st0) || st0.getDependsOn() == null || Boolean.TRUE.equals(st0.getDependsOn().get());
        int lx = cx + 8 + (r.dependent ? 16 : 0);
        float midY = y + (r.h - 2) / 2f;
        float base = Theme.fade;
        if (!enabled) Theme.fade = base * 0.4f;
        try {
            if (r.dependent) Paint.rect(k.ctx, cx + 12, y + 4, cx + 13, y + r.h - 6, 0, Theme.alpha(0xFFFFFFFF, 0.08f));
            if (r.item instanceof Setting<?> st && changed(st)) Paint.circle(k.ctx, lx - 5, r.desc != null ? y + 11 : midY, 4, Tokens.A500);
            // The row itself: right-click its name / middle-click resets a changed setting.
            k.hit("row:" + rid, cx - 4, y, cx + cw + 4, y + r.h - 2, (b, mx, my) -> {
                if (r.item instanceof Setting<?> st && changed(st) && (b == GLFW.GLFW_MOUSE_BUTTON_MIDDLE || (b == 1 && mx < lx + k.width(r.label, Role.BODY) + 8))) {
                    st.reset();
                    EvadeClient.config.save();
                } else if (b == 0 && r.item instanceof BooleanSetting bs && enabled) {
                    bs.toggle();
                } else if (b == 0 && (r.kind == K_KEYBIND || r.item instanceof KeySetting)) {
                    startListening(r.kind == K_KEYBIND ? mod : r.item);
                }
            }, false);
            int left = control(k, mod, r, rid, right, y, midY, hover, enabled);
            int labelMax = Math.max(60, left - lx - 16);
            if (r.desc != null) {
                k.text(host.fit(r.label, Role.BODY, labelMax), lx, y + 7, Tokens.N950, Role.BODY);
                k.text(host.fit(r.desc, Role.SMALL, labelMax), lx, y + 21, Tokens.N700, Role.SMALL);
            } else {
                k.textCentredY(host.fit(r.label, Role.BODY, labelMax), lx, midY, Tokens.N950, Role.BODY);
            }
            String how = how(r.item, mod);
            if (how != null && hover) k.tooltip("row:" + rid, r.desc != null ? r.desc : r.label, how, cx, y, cx + cw, y + r.h - 2);
        } finally {
            Theme.fade = base;
        }
    }

    private String how(Object item, Module mod) {
        if (item instanceof Module || item instanceof KeySetting) {
            Object self = item;
            int code = self instanceof Module m ? m.getKey() : ((KeySetting) self).get();
            List<String> users = conflicts(code, self);
            String c = users.isEmpty() ? "" : "Also used by " + String.join(", ", users) + ". ";
            return c + (isMenuKey(self) ? "Click to change · right-click resets to Right Shift"
                    : acceptsMouse(self) ? "Click, then press a key or a middle / side mouse button · right-click clears" : "Click to change · right-click clears");
        }
        if (item instanceof NumberSetting) return "Drag, scroll or use ← → (Shift ×10) · click the value to type it";
        if (item instanceof ColorSetting) return "Click the swatch for the colour picker";
        if (item instanceof StringSetting st && editable(st)) return "Click to edit · Enter saves, Esc cancels";
        if (item instanceof Setting<?> st && changed(st)) return "Right-click the name to reset it";
        return null;
    }

    // ------------------------------------------------------------------ controls

    /** Draws the row's control right-aligned at {@code right}; returns its left edge (the label stops before it). */
    private int control(Kit k, Module mod, Row r, String rid, int right, int y, float midY, boolean hover, boolean enabled) {
        Object item = r.item;
        int my = Math.round(midY);
        if (r.kind == K_SHARE) {
            int w = Button.draw(k, "share#paste", right - Button.width(k, "Paste code", Button.Size.SM, null, false), my - 10, 0,
                    Button.Size.SM, Button.Variant.SECONDARY, "Paste code", null, true, () -> share(mod, false));
            Button.draw(k, "share#copy", right - w - 6 - Button.width(k, "Copy code", Button.Size.SM, null, false), my - 10, 0,
                    Button.Size.SM, Button.Variant.SECONDARY, "Copy code", null, true, () -> share(mod, true));
            return right - w - 6 - Button.width(k, "Copy code", Button.Size.SM, null, false);
        }
        if (r.kind == K_KEYBIND) {
            int x = keyChip(k, mod, rid, right, my);
            x -= 10;
            int sw = Segmented.width(k, List.of("Toggle", "Hold"));
            Segmented.draw(k, "bm:" + rid, x - sw, my - Segmented.H / 2, List.of("Toggle", "Hold"), mod.isHoldBind() ? "Hold" : "Toggle", true, o -> {
                mod.setHoldBind(o.equals("Hold"));
                EvadeClient.config.save();
            });
            x -= sw + 10;
            int[] bits = {Keybinds.ALT, Keybinds.SHIFT, Keybinds.CTRL};
            String[] names = {"Alt", "Shift", "Ctrl"};
            for (int i = 0; i < 3; i++) {
                int bit = bits[i];
                x -= modifier(k, "mod:" + names[i] + ":" + rid, x, my, names[i], (mod.getBindMods() & bit) != 0, () -> {
                    mod.setBindMods(mod.getBindMods() ^ bit);
                    EvadeClient.config.save();
                }) + 4;
            }
            int ww = UiFont.capsWidth(k.tr, "With", Role.CAPS_SM, 0.8f, k.smooth);
            UiFont.caps(k.ctx, k.tr, "With", x - 2 - ww, my - 3, Tokens.N600, Role.CAPS_SM, 0.8f, k.smooth);
            return x - 2 - ww;
        }
        if (item instanceof KeySetting) return keyChip(k, item, rid, right, my);
        int left = right - 120;
        if (item instanceof BooleanSetting b) {
            Toggle.draw(k, "c:" + rid, right - Toggle.MD_W, my - Toggle.MD_H / 2, false, b.get(), enabled, () -> { b.toggle(); EvadeClient.config.save(); });
            left = right - Toggle.MD_W;
        } else if (item instanceof NumberSetting n) {
            int sx = right - SLIDER_W;
            left = sx;
            Slider.draw(k, "c:" + rid, sx, my - 8, SLIDER_W, n.get(), n.getMin(), n.getMax(), n.getStep(),
                    editor != null && editor.target == n ? "" : n.display(), enabled, v -> n.set(v));
            int vx = right - Slider.VALUE_W;
            if (editor != null && editor.target == n) {
                editor.draw(k, vx - 20, my - 9, right, my + 9);
            } else if (enabled) {
                k.hit("v:" + rid, vx, my - 8, right, my + 8, (b, mx, mmy) -> {
                    if (b == 0) editor = new Editor(n, n.display(), 12, cp -> Character.isDigit(cp) || cp == '.' || cp == ',' || cp == '-');
                }, false);
                if (k.hovered("v:" + rid)) Paint.ring(k.ctx, vx, my - 8, right, my + 8, Tokens.R_XS, Theme.alpha(Tokens.A400, 0.6f), 1);
                k.tooltip("v:" + rid, "Type a value", n.getMin() == Math.rint(n.getMin()) && n.getMax() == Math.rint(n.getMax())
                        ? plain(n.getMin()) + " to " + plain(n.getMax()) : null, vx, my - 8, right, my + 8);
            }
            if (hover && enabled) hoverSlider = n;
        } else if (item instanceof ModeSetting md) {
            List<String> opts = md.getModes();
            int segW = Segmented.width(k, opts);
            if (opts.size() <= 3 && segW <= 240) {
                Segmented.draw(k, "c:" + rid, right - segW, my - Segmented.H / 2, opts, md.get(), enabled, o -> { md.set(o); EvadeClient.config.save(); });
                left = right - segW;
            } else {
                int w = 96;
                for (String o : opts) w = Math.max(w, k.width(o, Role.BODY) + 40);
                w = Math.min(w, 220);
                Dropdown.draw(k, "c:" + rid, right - w, my - Dropdown.H / 2, w, opts, md.get(), enabled, o -> { md.set(o); EvadeClient.config.save(); });
                left = right - w;
            }
        } else if (item instanceof ColorSetting c) {
            left = colour(k, c, rid, right, my, enabled);
        } else if (item instanceof ItemListSetting il) {
            int w = ItemListRow.draw(k.ctx, il, right, my, hover);
            k.hit("c:" + rid, right - w - 4, my - 10, right, my + 10, () -> { if (il.clickAction() != null) il.clickAction().run(); });
            left = right - w - 4;
        } else if (item instanceof StringSetting st) {
            left = text(k, st, rid, right, my, enabled);
        }
        return left;
    }

    private static String plain(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** A modifier pill (Ctrl / Shift / Alt) ending at {@code right}; returns its width. */
    private static int modifier(Kit k, String id, int right, int my, String label, boolean on, Runnable toggle) {
        int w = k.width(label, Role.LABEL) + 14, x = right - w;
        int st = k.state(id, x, my - 9, right, my + 9, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float t = k.anim(id + "#t", on ? 1f : 0f);
        Paint.surface(k.ctx, x, my - 9, right, my + 9, Tokens.R_XS, Tokens.SUNKEN.withFill(Theme.lerpColor(Tokens.N150, Tokens.N250, hov * 0.6f)), 0, Float.NaN);
        if (t > 0.01f) {
            Paint.rect(k.ctx, x, my - 9, right, my + 9, Tokens.R_XS, Theme.alpha(Tokens.ACCENT_SURFACE, t));
            Paint.ring(k.ctx, x, my - 9, right, my + 9, Tokens.R_XS, Theme.alpha(Tokens.A500, 0.75f * t), 1);
        }
        k.textCentredY(label, x + 7, my, Theme.lerpColor(Tokens.N700, Tokens.N950, Math.max(t, hov * 0.6f)), Role.LABEL);
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, my - 9, right, my + 9, Tokens.R_XS, Tokens.FOCUS_RING);
        k.hit(id, x, my - 9, right, my + 9, toggle);
        k.tooltip(id, on ? "Needs " + label + " held too" : "Also hold " + label + "?", null, x, my - 9, right, my + 9);
        return w;
    }

    /** The key chip of a module keybind or a KeySetting ending at {@code right}; returns its left x. */
    private int keyChip(Kit k, Object self, String rid, int right, int my) {
        double now = host.now();
        boolean l = listening == self;
        int code = self instanceof Module m ? m.getKey() : ((KeySetting) self).get();
        boolean rejected = rejectRow == self && now - rejectAt < 1400;
        List<String> users = l ? List.of() : conflicts(code, self);
        KeyChip.Mode mode = l ? KeyChip.Mode.LISTENING : code == GLFW.GLFW_KEY_UNKNOWN ? KeyChip.Mode.NONE
                : !users.isEmpty() || rejected ? KeyChip.Mode.CONFLICT : KeyChip.Mode.BOUND;
        String label = self instanceof Module m ? Keybinds.comboName(code, m.getBindMods()) : KeySetting.keyName(code);
        label = KeyChip.label(label);
        int w = KeyChip.width(k, mode, label);
        String tip = !users.isEmpty() ? "Also bound to: " + String.join(", ", users) : null;
        KeyChip.draw(k, "key:" + rid, right - w, my - KeyChip.H / 2, mode, label, tip, () -> startListening(self));
        String hint = rejected ? rejectMsg : l ? (isMenuKey(self) ? "Esc cancel · Del reset" : "Esc cancel · Del clear") : null;
        if (hint != null) {
            int hw = k.width(hint, Role.SMALL);
            k.textCentredY(hint, right - w - 8 - hw, my, rejected ? Tokens.DANGER : Tokens.N700, Role.SMALL);
            return right - w - 16 - hw;
        }
        return right - w;
    }

    /** A colour: its hex in tabular digits and a swatch that opens the colour picker. */
    private int colour(Kit k, ColorSetting c, String rid, int right, int my, boolean enabled) {
        String id = "c:" + rid;
        int sw = 26, x = right - sw;
        boolean themed = c.get() >>> 24 == 0;
        int st = k.state(id, x - 70, my - 9, right, my + 9, enabled);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        if (themed) {
            Paint.dashed(k.ctx, x, my - 8, right, my + 8, Tokens.N500);
            k.textCentredY("A", x + (sw - k.width("A", Role.LABEL)) / 2, my, Tokens.N800, Role.LABEL);
        } else {
            if ((c.get() >>> 24) < 0xFF) checker(k, x, my - 8, right, my + 8);
            Paint.rect(k.ctx, x, my - 8, right, my + 8, Tokens.R_XS, c.get());
            Paint.ring(k.ctx, x, my - 8, right, my + 8, Tokens.R_XS, Theme.alpha(0xFFFFFFFF, 0.16f + 0.4f * hov), 1);
        }
        String hex = themed ? "Theme" : c.hasAlpha() && (c.get() >>> 24) != 0xFF ? String.format("#%08X", c.get()) : c.display();
        int hw = themed ? k.width(hex, Role.LABEL) : UiFont.tabularWidth(k.tr, hex, Role.LABEL, k.smooth);
        float ty = UiFont.centredY(Role.LABEL, my, k.smooth);
        if (themed) k.text(hex, x - 8 - hw, Math.round(ty), Tokens.N800, Role.LABEL);
        else UiFont.drawTabular(k.ctx, k.tr, hex, x - 8 - hw, Math.round(ty), Theme.lerpColor(Tokens.N800, Tokens.N950, hov), Role.LABEL, k.smooth);
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, my - 8, right, my + 8, Tokens.R_XS, Tokens.FOCUS_RING);
        if (enabled) k.hit(id, x - 8 - hw, my - 9, right, my + 9, () -> host.openPicker(c, x - 6, my));
        return x - 8 - hw;
    }

    private static void checker(Kit k, int x1, int y1, int x2, int y2) {
        for (int y = y1; y < y2; y += 4)
            for (int x = x1; x < x2; x += 4)
                Theme.fill(k.ctx, x, y, Math.min(x + 4, x2), Math.min(y + 4, y2), ((x - x1) / 4 + (y - y1) / 4) % 2 == 0 ? 0xFF8A8D96 : 0xFF54575F);
    }

    /** A text setting: an editable field, an action button, or a read-only status. */
    private int text(Kit k, StringSetting st, String rid, int right, int my, boolean enabled) {
        int maxW = Math.max(120, host.cw() / 2 - 40);
        if (editor != null && editor.target == st) {
            int ew = Math.min(maxW, Math.max(140, k.width(editor.text, Role.BODY) + 20));
            editor.draw(k, right - ew, my - 11, right, my + 11);
            return right - ew;
        }
        String id = "c:" + rid;
        if (st.clickAction() != null) {
            String label = st.get().isEmpty() ? st.hint() : st.get();
            label = host.fit(label, Role.LABEL, maxW - 20);
            int w = Button.width(k, label, Button.Size.SM, null, false);
            Button.draw(k, id, right - w, my - 10, w, Button.Size.SM, Button.Variant.SECONDARY, label, null, enabled, st.clickAction());
            return right - w;
        }
        if (!editable(st)) {
            String v = host.fit(st.get(), Role.LABEL, maxW);
            k.textCentredY(v, right - k.width(v, Role.LABEL), my, Tokens.N800, Role.LABEL);
            return right - k.width(v, Role.LABEL);
        }
        String v = host.fit(st.get(), Role.BODY, maxW - 16);
        int w = Math.min(maxW, Math.max(120, k.width(v, Role.BODY) + 20));
        int s = k.state(id, right - w, my - 11, right, my + 11, enabled);
        float hov = k.anim(id + "#h", (s & Kit.HOVER) != 0 ? 1f : 0f);
        Paint.surface(k.ctx, right - w, my - 11, right, my + 11, Tokens.R_SM, Tokens.SUNKEN.withFill(Theme.lerpColor(Tokens.N150, Tokens.N200, hov)), 0, Float.NaN);
        k.textCentredY(v.isEmpty() ? st.hint() : v, right - w + 8, my, v.isEmpty() ? Tokens.N600 : Tokens.N900, Role.BODY);
        Icons.draw(k.ctx, "edit", right - 16, my - 5, 10, Theme.lerpColor(Tokens.N600, Tokens.N900, hov));
        if ((s & Kit.FOCUSED) != 0) Paint.focusRing(k, right - w, my - 11, right, my + 11, Tokens.R_SM, Tokens.FOCUS_RING);
        if (enabled) k.hit(id, right - w, my - 11, right, my + 11, () -> editor = new Editor(st, st.get(), stringLimit(st), StringHelper::isValidChar));
        return right - w;
    }

    private static boolean editable(StringSetting st) { return !(st instanceof StatusSetting) && st.clickAction() == null; }

    private static int stringLimit(StringSetting st) {
        var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
        if (nh != null && nh.nick == st) return Math.min(16, st.getMaxLength());
        return st.getMaxLength() < StringSetting.DEFAULT_MAX_LENGTH ? st.getMaxLength() : 32;
    }

    private static void share(Module mod, boolean copy) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (copy) {
            String code = net.evadeclient.config.ModShare.code(mod);
            if (code == null) { Notifications.push(mod.getName(), "Couldn't make a code", false); return; }
            mc.keyboard.setClipboard(code);
            Notifications.push(mod.getName(), "Settings code copied", true);
        } else {
            var r = net.evadeclient.config.ModShare.apply(mc.keyboard.getClipboard(), mod);
            Notifications.push(mod.getName(), r.ok() ? "Settings pasted from the code" : r.error(), r.ok());
        }
    }

    // ------------------------------------------------------------------ key binding

    private static boolean isMenuKey(Object target) { return target == cs().menuKey; }

    private static boolean acceptsMouse(Object target) {
        return target instanceof Module || (target instanceof KeySetting k && k.allowMouse);
    }

    private static List<String> conflicts(int code, Object self) {
        if (code == GLFW.GLFW_KEY_UNKNOWN) return List.of();
        if (self instanceof Module m) return Keybinds.conflictNames(code, m.getBindMods(), m);
        return EvadeClient.modules.usersOf(code, self);
    }

    private void startListening(Object target) {
        listening = target;
        listenAt = host.now();
    }

    private boolean bindKey(Object target, int code) {
        boolean valid = KeySetting.isMouse(code) ? KeySetting.isValidMouse(code) : code != GLFW.GLFW_KEY_UNKNOWN && KeySetting.isValidKey(code);
        if (!valid) { reject(target, "Can't bind that"); return false; }
        if (!isMenuKey(target) && code == cs().menuKey.get()) { reject(target, "That's the Menu Key"); return false; }
        if (target instanceof Module m) m.setKey(code);
        else if (target instanceof KeySetting k) {
            if (!k.accepts(code)) { reject(target, "Keys only here"); return false; }
            k.set(code);
        }
        rejectRow = null;
        EvadeClient.config.save();
        return true;
    }

    private static void clearKey(Object target) {
        if (target instanceof Module m) m.setKey(GLFW.GLFW_KEY_UNKNOWN);
        else if (target instanceof KeySetting k) {
            if (isMenuKey(k)) k.reset(); else k.set(GLFW.GLFW_KEY_UNKNOWN);
        }
        EvadeClient.config.save();
    }

    private void reject(Object target, String why) {
        rejectRow = target;
        rejectAt = host.now();
        rejectMsg = why;
    }

    // ------------------------------------------------------------------ input (the host asks first)

    /** Whether a key capture or a text field is open (the Menu Key's release doesn't close the menu then). */
    public boolean busy() { return listening != null || editor != null; }

    /** A click while a field is open or a key is being captured. Returns true when used. */
    public boolean preClick(double mx, double my, int button) {
        if (editor != null) {
            if (editor.contains(mx, my)) { editor.clickAt(host.kit(), mx); return true; }
            commit();
        }
        if (listening != null) {
            Object target = listening;
            if (button >= GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                if (!acceptsMouse(target)) reject(target, "Keys only here");
                else if (bindKey(target, KeySetting.mouseCode(button))) listening = null;
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) clearKey(target);
            listening = null;
            return true;
        }
        // A right-click on a key chip clears it (the chip itself only handles the left button).
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Kit k = host.kit();
            Module mod = host.selected() != null ? host.selected() : (host.page() == MenuPage.SETTINGS ? cs() : null);
            if (mod != null) for (Row r : rows) {
                if (r.kind != K_KEYBIND && !(r.item instanceof KeySetting)) continue;
                if (k.hovered("key:" + rowId(r.item, mod))) { clearKey(r.kind == K_KEYBIND ? mod : r.item); return true; }
            }
        }
        return false;
    }

    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (editor != null) { editor.key(input); return true; }
        if (listening != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { listening = null; return true; }
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) { clearKey(listening); listening = null; return true; }
            if (bindKey(listening, key)) listening = null;
            return true;
        }
        return false;
    }

    public boolean charTyped(CharInput input) {
        if (editor != null) { if (input.isValidChar()) editor.insert(input.asString()); return true; }
        return listening != null;
    }

    /** The wheel over a slider row steps it (Shift: 10 steps). */
    public boolean wheel(double amount, boolean shift) {
        if (hoverSlider == null || editor != null) return false;
        NumberSetting n = hoverSlider;
        double step = n.getStep() > 0 ? n.getStep() : (n.getMax() - n.getMin()) / 100;
        if (!(step > 0)) return false;
        n.set(n.get() + (amount > 0 ? 1 : -1) * (shift ? 10 : 1) * step);
        return true;
    }

    /** Leaving the page (or the menu): apply an open field, stop listening. */
    public void leave() {
        commit();
        listening = null;
    }

    private void commit() {
        Editor e = editor;
        editor = null;
        if (e == null || e.text.equals(e.initial)) return;
        boolean applied = false;
        switch (e.target) {
            case NumberSetting n -> {
                try {
                    double d = Double.parseDouble(e.text.trim().replace(',', '.'));
                    if (Double.isFinite(d)) {
                        n.set(d);
                        applied = true;
                        if (d < n.getMin() || d > n.getMax())
                            Notifications.push(n.getName(), "Allowed " + plain(n.getMin()) + " to " + plain(n.getMax()) + ": set to " + n.display(), false);
                    }
                } catch (NumberFormatException ignored) { }
            }
            case StringSetting st -> {
                String v = e.text.strip();
                if (!v.isEmpty()) { st.set(v.length() > e.max ? v.substring(0, e.max) : v); applied = true; }
            }
            default -> { }
        }
        if (applied) EvadeClient.config.save();
        else Notifications.push(e.target.getName(), "Not a valid value: kept " + e.target.display(), false);
    }

    /** A one-line text field opened on a row to type a number or a short text. Starts with everything selected. */
    private final class Editor {
        final Setting<?> target;
        final String initial;
        final int max;
        final IntPredicate allowed;
        String text;
        int caret, scroll;
        boolean all = true;
        double typedAt = host.now();
        int x1, y1, x2, y2;

        Editor(Setting<?> target, String initial, int max, IntPredicate allowed) {
            this.target = target;
            this.initial = initial;
            this.max = max;
            this.allowed = allowed;
            this.text = initial;
            this.caret = initial.length();
        }

        void draw(Kit k, int x1, int y1, int x2, int y2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            Paint.glow(k, x1, y1, x2, y2, Tokens.R_SM, 2, Theme.alpha(Tokens.A600, 0.35f));
            Paint.surface(k.ctx, x1, y1, x2, y2, Tokens.R_SM, Tokens.SUNKEN, 0, Float.NaN);
            Paint.ring(k.ctx, x1, y1, x2, y2, Tokens.R_SM, Tokens.A400, 1);
            float cy = (y1 + y2) / 2f;
            int caretPx = all ? 0 : k.width(text.substring(0, Math.min(caret, text.length())), Role.BODY);
            int room = Math.max(1, x2 - x1 - 12);
            scroll = Math.max(0, Math.min(scroll, caretPx));
            scroll = Math.max(scroll, caretPx - room);
            int tx = x1 + 6 - scroll;
            k.ctx.enableScissor(x1 + 2, y1, x2 - 2, y2);
            try {
                if (all && !text.isEmpty()) Paint.rect(k.ctx, tx - 1, cy - 6, tx + k.width(text, Role.BODY) + 1, cy + 6, 2, Theme.alpha(Tokens.A600, 0.45f));
                k.textCentredY(text, tx, cy, Tokens.N950, Role.BODY);
                boolean blink = Motion.level() == Motion.Level.OFF || Math.floorMod((long) Math.floor((host.now() - typedAt) / 530), 2L) == 0;
                if (!all && blink) Theme.fill(k.ctx, tx + caretPx, Math.round(cy - 5), tx + caretPx + 1, Math.round(cy + 5), Tokens.A400);
            } finally {
                k.ctx.disableScissor();
            }
        }

        boolean contains(double mx, double my) { return mx >= x1 && mx < x2 && my >= y1 && my < y2; }

        void clickAt(Kit k, double mx) {
            all = false;
            int tx = x1 + 6 - scroll, best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i <= text.length(); i++) {
                double d = Math.abs(tx + k.width(text.substring(0, i), Role.BODY) - mx);
                if (d < bestD) { bestD = d; best = i; }
            }
            caret = best;
            typedAt = host.now();
        }

        void insert(String s) {
            StringBuilder ok = new StringBuilder();
            s.codePoints().filter(allowed).forEach(ok::appendCodePoint);
            if (ok.isEmpty()) return;
            if (all) { text = ""; caret = 0; all = false; }
            String add = ok.toString();
            if (text.length() + add.length() > max) add = add.substring(0, Math.max(0, max - text.length()));
            text = text.substring(0, caret) + add + text.substring(caret);
            caret += add.length();
            typedAt = host.now();
        }

        void key(KeyInput in) {
            int key = in.key();
            typedAt = host.now();
            if (in.isEscape()) { editor = null; return; }
            if (in.isEnter()) { commit(); return; }
            if (in.isSelectAll()) { all = true; return; }
            MinecraftClient mc = MinecraftClient.getInstance();
            if (in.isCopy() || in.isCut()) {
                mc.keyboard.setClipboard(text);
                if (in.isCut()) { text = ""; caret = 0; all = false; }
                return;
            }
            if (in.isPaste()) { insert(net.evadeclient.gui.EvadeMenu.clean(mc.keyboard.getClipboard())); return; }
            switch (key) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (all) { text = ""; caret = 0; all = false; }
                    else if (caret > 0) {
                        int from = in.hasCtrlOrCmd() ? 0 : caret - 1;
                        text = text.substring(0, from) + text.substring(caret);
                        caret = from;
                    }
                }
                case GLFW.GLFW_KEY_DELETE -> {
                    if (all) { text = ""; caret = 0; all = false; }
                    else if (caret < text.length()) text = text.substring(0, caret) + text.substring(caret + 1);
                }
                case GLFW.GLFW_KEY_LEFT -> { caret = all ? 0 : Math.max(0, caret - 1); all = false; }
                case GLFW.GLFW_KEY_RIGHT -> { caret = all ? text.length() : Math.min(text.length(), caret + 1); all = false; }
                case GLFW.GLFW_KEY_HOME -> { caret = 0; all = false; }
                case GLFW.GLFW_KEY_END -> { caret = text.length(); all = false; }
                default -> { }
            }
        }
    }
}
