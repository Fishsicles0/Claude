package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.util.Keybinds;
import net.evadeclient.util.Keybinds.Binding;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.KeyChip;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Segmented;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every key binding in one place: Evade's mod keys (with Toggle / Hold and modifiers), Evade's key settings and the
 * game's own controls, grouped, searchable, with clashes in red. Click a key to rebind it (hold Ctrl, Shift or Alt
 * with the key to make a mod bind need them), right-click to clear, and Reset puts one back to its default.
 */
public final class KeybindsScreen extends EvadePanelScreen {
    private static final int ROW_H = 28, HEAD_H = 24;

    /** The binding waiting for a key, and a modifier pressed on its own (bound if released with nothing else). */
    private Binding listening;
    private int pendingModifier = -1;
    private boolean conflictsOnly;
    private List<Binding> all = List.of();
    private long allAt;

    public KeybindsScreen(Screen parent) {
        super("Keybinds", parent);
    }

    /** From the menu's sidebar: comes back to whatever screen was open. */
    public static void openFromCurrent() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new KeybindsScreen(mc.currentScreen));
    }

    @Override protected int prefWidth() { return 720; }
    @Override protected int prefHeight() { return 460; }
    @Override protected boolean hasSearch() { return true; }
    @Override protected String searchHint() { return "Search keys and mods"; }
    @Override protected String headerIcon() { return "keyboard"; }

    @Override
    protected String subtitle() {
        int n = conflictCount();
        return n == 0 ? "Click a key to change it · right-click clears · no conflicts"
                : "Click a key to change it · right-click clears · " + n + " binding" + (n == 1 ? "" : "s") + " share a key";
    }

    /** Bindings, refreshed a few times a second (another mod can change keys while this is open). */
    private List<Binding> bindings() {
        long now = System.currentTimeMillis();
        if (now - allAt > 250) { all = Keybinds.all(); allAt = now; }
        return all;
    }

    private void refresh() { allAt = 0; }

    private int conflictCount() {
        List<Binding> b = bindings();
        int n = 0;
        for (Binding x : b) if (!Keybinds.conflicts(x, b).isEmpty()) n++;
        return n;
    }

    @Override
    protected void renderToolbar(DrawContext ctx, int x, int y, int right, int mx, int my) {
        int n = conflictCount();
        List<String> opts = List.of("All keys", n == 0 ? "Conflicts" : "Conflicts  " + n);
        int w = Segmented.width(kit, opts);
        Segmented.draw(kit, "filter", right - w, y + 1, opts, conflictsOnly ? opts.get(1) : opts.get(0), true, o -> {
            boolean c = o.startsWith("Conflicts");
            if (c != conflictsOnly) { conflictsOnly = c; scrollTarget = 0; restartEntrance(); }
        });
    }

    /** Rows to show: groups in order, each a header then its bindings (clashes first), after the search and filter. */
    private List<Object> rows() {
        List<Binding> list = bindings();
        String q = search.strip().toLowerCase(Locale.ROOT);
        List<Object> out = new ArrayList<>();
        String group = null;
        List<Binding> clashing = new ArrayList<>(), rest = new ArrayList<>();
        for (Binding b : list) {
            boolean clash = !Keybinds.conflicts(b, list).isEmpty();
            if (conflictsOnly && !clash) continue;
            if (!q.isEmpty() && !(b.name().toLowerCase(Locale.ROOT).contains(q) || b.keyText().toLowerCase(Locale.ROOT).contains(q)
                    || b.group().toLowerCase(Locale.ROOT).contains(q))) continue;
            if (!b.group().equals(group)) {
                flush(out, clashing, rest);
                group = b.group();
                out.add(group);
            }
            (clash ? clashing : rest).add(b);
        }
        flush(out, clashing, rest);
        return out;
    }

    private static void flush(List<Object> out, List<Binding> clashing, List<Binding> rest) {
        out.addAll(clashing);
        out.addAll(rest);
        clashing.clear();
        rest.clear();
    }

    /** How many rows each group header heads (for its count). */
    private static int groupSize(List<Object> rows, int from) {
        int n = 0;
        for (int i = from + 1; i < rows.size() && !(rows.get(i) instanceof String); i++) n++;
        return n;
    }

    private static String groupIcon(String g) {
        return switch (g) {
            case "Evade mods" -> "mods";
            case "Evade keys" -> "keyboard";
            case "Text hotkeys", "Text Hotkeys" -> "edit";
            default -> "controls";
        };
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        List<Object> rows = rows();
        List<Binding> list = bindings();
        int y = top;
        if (rows.isEmpty()) {
            boolean none = conflictsOnly && search.isBlank();
            int h = EmptyState.draw(kit, "empty", cx + cw / 2, top + 24, none ? "check" : "search",
                    none ? "No conflicts" : "Nothing matches", none ? "Every key does one thing." : "Try a mod's name or a key, like \"R\".",
                    null, null);
            return h + 48;
        }
        int i = 0;
        for (int r = 0; r < rows.size(); r++) {
            Object o = rows.get(r);
            if (o instanceof String g) {
                if (r > 0) y += Tokens.S4;
                if (inView(y, HEAD_H)) {
                    Icons.draw(ctx, groupIcon(g), cx + 1, y + 5, 11, Tokens.N600);
                    section(g.toUpperCase(Locale.ROOT), String.valueOf(groupSize(rows, r)), cx + 17, y - 1);
                }
                y += HEAD_H;
                continue;
            }
            Binding b = (Binding) o;
            if (inView(y, ROW_H)) {
                float a = enter(i);
                float f = Theme.fade;
                Theme.fade = f * a;
                int dy = Math.round((1 - a) * 6);
                try { renderRow(ctx, b, list, y + dy, mx, my); } finally { Theme.fade = f; }
            }
            i++;
            y += ROW_H;
        }
        return y - top + 6;
    }

    private void renderRow(DrawContext ctx, Binding b, List<Binding> list, int y, int mx, int my) {
        String key = b.kind() + ":" + b.name();
        int y2 = y + ROW_H - 2;
        boolean hv = mouseIn(mx, my, cx, y, cx + cw, y2);
        float h = hover("row:" + key, hv);
        rowSurface(cx, y, cx + cw, y2, h, false);
        List<Binding> clash = Keybinds.conflicts(b, list);
        boolean conflict = !clash.isEmpty();
        float mid = y + (ROW_H - 2) / 2f;
        int nameColor = b.game() ? Tokens.N800 : Tokens.N950;
        String name = ellipsize(b.name(), Role.BODY, cw / 2 - 20);
        kit.textCentredY(name, cx + 10, mid, nameColor, Role.BODY);
        int nx = cx + 10 + width(name, Role.BODY) + Tokens.S3;
        if (b.hold() && b.bound()) nx += Badge.draw(kit, nx, Math.round(mid - Badge.H / 2f), "Hold", Badge.Tone.NEUTRAL) + Tokens.S2;

        int right = cx + cw - 6;
        // Reset (only when it isn't at its default)
        if (!Keybinds.isDefault(b)) {
            right -= 20;
            Button.icon(kit, "reset:" + key, right, Math.round(mid - 10), 20, "reset", "Reset to default", true, () -> {
                Keybinds.reset(b);
                refresh();
            });
            right -= Tokens.S2;
        }
        // Key chip
        boolean l = listening != null && listening.owner() == b.owner();
        KeyChip.Mode mode = l ? KeyChip.Mode.LISTENING : conflict ? KeyChip.Mode.CONFLICT : b.bound() ? KeyChip.Mode.BOUND : KeyChip.Mode.NONE;
        String label = l && pendingModifier >= 0 ? Keybinds.modsPrefix(Keybinds.modifierOf(pendingModifier)) + "…" : KeyChip.label(b.keyText());
        String tip = null;
        if (conflict) {
            List<String> names = new ArrayList<>();
            for (Binding c : clash) names.add(c.name());
            tip = "Also bound to: " + String.join(", ", names);
        }
        int kw = Math.max(76, KeyChip.width(kit, mode, label));
        int x1 = right - kw;
        String id = "key:" + key;
        if (l && pendingModifier >= 0) {
            Paint.sunken(ctx, x1, mid - KeyChip.H / 2f, right, mid + KeyChip.H / 2f, Tokens.R_XS);
            Paint.ring(ctx, x1, mid - KeyChip.H / 2f, right, mid + KeyChip.H / 2f, Tokens.R_XS, Tokens.A400, 2);
            kit.textCentredY(label, x1 + (kw - width(label, Role.TINY)) / 2, mid, Tokens.N950, Role.TINY);
        } else {
            // Centre the chip in a fixed-width slot so the key column lines up.
            int cwid = KeyChip.width(kit, mode, label);
            KeyChip.draw(kit, id, x1 + (kw - cwid), Math.round(mid - KeyChip.H / 2f), mode, label, tip, () -> {});
        }
        contentHit(id, x1, Math.round(mid - KeyChip.H / 2f), right, Math.round(mid + KeyChip.H / 2f), btn -> clickKey(b, btn));
        // Who else is on this key, in words (the warn chip carries the colour)
        if (conflict) {
            String also = "Also " + clash.get(0).name() + (clash.size() > 1 ? " +" + (clash.size() - 1) : "");
            int maxW = x1 - 10 - (cx + cw / 2);
            if (maxW > 30) {
                String s = ellipsize(also, Role.SMALL, maxW);
                kit.textCentredY(s, x1 - 8 - width(s, Role.SMALL), mid, Tokens.N700, Role.SMALL);
            }
        }
    }

    private void clickKey(Binding b, int button) {
        if (listening != null && listening.owner() == b.owner()) {
            // A click while waiting: middle/side buttons bind where allowed, anything else cancels.
            if (button >= GLFW.GLFW_MOUSE_BUTTON_MIDDLE) bind(b, KeySetting.mouseCode(button), 0);
            else listening = null;
            return;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (b.owner() == menuKey()) Keybinds.reset(b);
            else Keybinds.rebind(b, GLFW.GLFW_KEY_UNKNOWN);
            refresh();
            return;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            listening = b;
            pendingModifier = -1;
        }
    }

    private static Object menuKey() {
        ClientSettings cs = settings();
        return cs == null ? null : cs.menuKey;
    }

    /** Bind the listening entry to {@code code} with {@code mods} (mods only stick on mod binds). */
    private void bind(Binding b, int code, int mods) {
        listening = null;
        pendingModifier = -1;
        ClientSettings cs = settings();
        if (cs != null && b.owner() != cs.menuKey && !b.game() && code == cs.menuKey.get() && mods == 0) {
            Notifications.push("Keybinds", "That's the Menu Key", false);
            return;
        }
        if (!Keybinds.rebind(b, code)) {
            Notifications.push("Keybinds", "Can't bind that here", false);
            return;
        }
        if (b.owner() instanceof Module m) {
            m.setBindMods(mods);
            EvadeClient.config.save();
        }
        refresh();
        List<String> clash = new ArrayList<>();
        for (Binding o : bindings()) if (o.owner() == b.owner()) for (Binding c : Keybinds.conflicts(o, bindings())) clash.add(c.name());
        if (!clash.isEmpty()) Notifications.push(b.name(), "Shares a key with " + String.join(", ", clash), false);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (listening != null) {
            int key = input.key();
            if (key == GLFW.GLFW_KEY_ESCAPE) { listening = null; pendingModifier = -1; return true; }
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                Binding b = listening;
                listening = null;
                if (b.owner() == menuKey()) Keybinds.reset(b); else Keybinds.rebind(b, GLFW.GLFW_KEY_UNKNOWN);
                refresh();
                return true;
            }
            if (Keybinds.modifierOf(key) != 0) {
                // Wait: either another key follows (a combo) or this modifier is released alone (bind to it).
                pendingModifier = key;
                return true;
            }
            int mods = 0;
            if (listening.kind() == Keybinds.Kind.MOD) {
                int m = input.modifiers();
                if ((m & GLFW.GLFW_MOD_CONTROL) != 0) mods |= Keybinds.CTRL;
                if ((m & GLFW.GLFW_MOD_SHIFT) != 0) mods |= Keybinds.SHIFT;
                if ((m & GLFW.GLFW_MOD_ALT) != 0) mods |= Keybinds.ALT;
            }
            bind(listening, key, mods);
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE && !searchFocused()) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        if (listening != null && pendingModifier >= 0 && input.key() == pendingModifier) {
            bind(listening, pendingModifier, 0);
            return true;
        }
        return super.keyReleased(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        boolean wasListening = listening != null;
        Binding before = listening;
        boolean handled = super.mouseClicked(click, doubled);
        // A click anywhere else cancels waiting for a key.
        if (wasListening && listening == before && before != null && !handled) listening = null;
        return handled;
    }

    @Override protected int footerHeight() { return 30; }

    @Override
    protected void renderFooter(DrawContext ctx, int x1, int y, int x2, int mx, int my) {
        float mid = y + 15;
        String hint = listening != null ? "Press a key · hold Ctrl, Shift or Alt with it for a combo · Esc cancels · Delete clears"
                : "Toggle or Hold and the modifiers of a mod key are on each mod's page too";
        Icons.draw(ctx, listening != null ? "keyboard" : "info", x1, Math.round(mid - 6), 12, listening != null ? Tokens.A400 : Tokens.N600);
        kit.textCentredY(ellipsize(hint, Role.SMALL, x2 - x1 - 18), x1 + 18, mid, Tokens.N700, Role.SMALL);
    }
}
