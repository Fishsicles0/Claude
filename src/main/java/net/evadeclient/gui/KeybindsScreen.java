package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.util.Keybinds;
import net.evadeclient.util.Keybinds.Binding;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
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
    private static final int ROW_H = 22, HEAD_H = 20;

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

    @Override protected int prefWidth() { return 700; }
    @Override protected int prefHeight() { return 440; }
    @Override protected boolean hasSearch() { return true; }

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
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        String label = conflictsOnly ? "Showing conflicts" : "Conflicts only";
        button(ctx, "conf", label, right - 24, y, mx, my, conflictsOnly ? 1 : 0, b -> { conflictsOnly = !conflictsOnly; scrollTarget = 0; });
    }

    /** Rows to show: groups in order, each a header then its bindings, after the search and the conflicts filter. */
    private List<Object> rows() {
        List<Binding> list = bindings();
        String q = search.strip().toLowerCase(Locale.ROOT);
        List<Object> out = new ArrayList<>();
        String group = null;
        for (Binding b : list) {
            if (conflictsOnly && Keybinds.conflicts(b, list).isEmpty()) continue;
            if (!q.isEmpty() && !(b.name().toLowerCase(Locale.ROOT).contains(q) || b.keyText().toLowerCase(Locale.ROOT).contains(q)
                    || b.group().toLowerCase(Locale.ROOT).contains(q))) continue;
            if (!b.group().equals(group)) { group = b.group(); out.add(group); }
            out.add(b);
        }
        return out;
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        List<Object> rows = rows();
        List<Binding> list = bindings();
        int y = top;
        if (rows.isEmpty()) {
            String msg = conflictsOnly ? "No conflicts: every key does one thing" : "Nothing matches \"" + search.strip() + "\"";
            text(ctx, msg, cx + (cw - width(msg, Role.BODY)) / 2, y + 30, Theme.TEXT_DIM, Role.BODY);
            return 60;
        }
        for (Object r : rows) {
            if (r instanceof String g) {
                if (inView(y, HEAD_H)) UiFont_caps(ctx, g, cx + 2, y + 8);
                y += HEAD_H;
                continue;
            }
            Binding b = (Binding) r;
            if (inView(y, ROW_H)) renderRow(ctx, b, list, y, mx, my);
            y += ROW_H;
        }
        return y - top + 6;
    }

    private void UiFont_caps(DrawContext ctx, String s, int x, int y) {
        net.evadeclient.util.UiFont.caps(ctx, textRenderer, s, x, y, Theme.TEXT_HEADER, Role.CAPS, 1.5f, smooth);
    }

    private void renderRow(DrawContext ctx, Binding b, List<Binding> list, int y, int mx, int my) {
        boolean hv = mouseIn(mx, my, cx, y, cx + cw, y + ROW_H - 2);
        float h = hover("row:" + b.kind() + b.name(), hv);
        Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 2, Theme.lerpColor(Theme.ROW, Theme.ROW_HOVER, h));
        List<Binding> clash = Keybinds.conflicts(b, list);
        boolean conflict = !clash.isEmpty();
        if (conflict) Theme.round(ctx, cx, y + 4, cx + 2, y + ROW_H - 6, Theme.DANGER, 1);
        int nameColor = b.game() ? Theme.TEXT_SOFT : Theme.TEXT;
        text(ctx, ellipsize(b.name(), Role.BODY, cw / 2 - 20), cx + 10, y + 6, nameColor, Role.BODY);

        int right = cx + cw - 6;
        // Reset (only when it isn't at its default)
        if (!Keybinds.isDefault(b)) {
            right = button(ctx, "reset:" + b.kind() + b.name(), "Reset", right, y + 2, mx, my, 0, btn -> {
                Keybinds.reset(b);
                refresh();
            }) - 6;
        }
        // Key box
        boolean l = listening != null && listening.owner() == b.owner();
        String label = l ? (pendingModifier >= 0 ? Keybinds.modsPrefix(Keybinds.modifierOf(pendingModifier)) + "..." : "Press a key...")
                : b.keyText() + (b.hold() && b.bound() ? " (hold)" : "");
        int bw = Math.max(70, width(label, Role.LABEL) + 16);
        int x1 = right - bw;
        boolean boxHv = mouseIn(mx, my, x1, y + 2, right, y + ROW_H - 4);
        float bh = hover("key:" + b.kind() + b.name(), boxHv);
        int border = l ? Theme.RED : conflict ? Theme.DANGER : Theme.lerpColor(Theme.BORDER, Theme.BORDER_HOVER, bh);
        Theme.roundBordered(ctx, x1, y + 2, right, y + ROW_H - 4, Theme.lerpColor(Theme.CONTROL, Theme.CONTROL_HOVER, bh), border);
        int tc = l ? Theme.RED_BRIGHT : conflict ? Theme.DANGER : b.bound() ? Theme.TEXT : Theme.TEXT_FAINT;
        text(ctx, label, x1 + (bw - width(label, Role.LABEL)) / 2, y + 7, tc, Role.LABEL);
        contentHit(x1, y + 2, right, y + ROW_H - 4, btn -> clickKey(b, btn));
        // Who else is on this key
        if (conflict) {
            List<String> names = new ArrayList<>();
            for (Binding c : clash) names.add(c.name());
            String also = "Also: " + String.join(", ", names);
            int maxW = x1 - 10 - (cx + cw / 2);
            if (maxW > 30) {
                String s = ellipsize(also, Role.SMALL, maxW);
                text(ctx, s, x1 - 8 - width(s, Role.SMALL), y + 7, Theme.DANGER, Role.SMALL);
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
        if (input.key() == GLFW.GLFW_KEY_ESCAPE && !searchFocused) { close(); return true; }
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

    @Override
    protected void renderOverlay(DrawContext ctx, int mx, int my) {
        String hint = listening != null ? "Press a key (hold Ctrl/Shift/Alt with it for a combo) · Esc cancels · Delete clears"
                : "Mod keys: set Toggle or Hold and modifiers on each mod's page too";
        text(ctx, ellipsize(hint, Role.SMALL, cw), cx, wy + wh - 10, Theme.TEXT_FAINT, Role.SMALL);
    }
}
