package net.evadeclient.gui;

import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.misc.TextHotkeys;
import net.evadeclient.module.misc.TextHotkeys.Hotkey;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.util.Keybinds;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.KeyChip;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Toggle;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The Text Hotkeys editor: your one-click chat macros in a list you can add to (blank or from a preset), reorder,
 * switch on and off and delete (with Undo). Click a hotkey to edit its text (with a live 256-character counter), its
 * label, the servers it works on and its key, which can be a Ctrl / Shift / Alt combo or a middle or side mouse
 * button. Keys shared with something else are flagged. Test puts the text in the chat box without sending it.
 */
public final class TextHotkeysScreen extends EvadePanelScreen {
    private static final int ROW_H = 36, EDIT_H = 158, PRESET_H = 36;
    private static final long UNDO_MS = 8000;

    /** Which text box has the keyboard. */
    private enum Field { NONE, TEXT, LABEL, SERVERS }

    private Hotkey editing;
    private Field focus = Field.NONE;
    private int cursor;
    /** The hotkey waiting for a key press, and a modifier pressed on its own so far (or -1). */
    private Hotkey listening;
    private int pendingModifier = -1;
    private boolean presetsOpen;
    private Hotkey undoKey;
    private int undoIndex;
    private long undoAt;

    public TextHotkeysScreen(Screen parent) {
        super("Text Hotkeys", parent);
    }

    private static TextHotkeys mod() { return TextHotkeys.get(); }

    private List<Hotkey> list() { TextHotkeys t = mod(); return t == null ? List.of() : t.hotkeys(); }

    @Override protected int prefWidth() { return 720; }
    @Override protected int prefHeight() { return 460; }
    @Override protected String headerIcon() { return "keyboard"; }

    @Override
    protected String subtitle() {
        TextHotkeys t = mod();
        int n = list().size();
        String s = n + " of " + TextHotkeys.MAX_HOTKEYS + " hotkeys  ·  one message per press, at most one every "
                + (t == null ? "0.1" : trim(t.gapMs() / 1000.0)) + " s";
        return t != null && !t.isEnabled() ? "Text Hotkeys is off (Server tab)  ·  " + s : s;
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        boolean full = list().size() >= TextHotkeys.MAX_HOTKEYS;
        String nl = full ? "List full" : "New hotkey";
        int nw = Button.width(kit, nl, Button.Size.SM, full ? null : "plus", false);
        drawButton("new", nl, full ? null : "plus", right - nw, y, nw, Button.Size.SM, PRIMARY, !full, b -> addNew());
        headerReach(right - nw);
        button("presets", "Presets", "list", right - nw - Tokens.S3, y, presetsOpen ? SELECTED : SECONDARY, b -> presetsOpen = !presetsOpen);
    }

    // ------------------------------------------------------------------ list actions

    private void addNew() {
        TextHotkeys t = mod();
        if (t == null) return;
        Hotkey h = t.addHotkey("", "");
        if (h == null) { Notifications.push("Text Hotkeys", "You have " + TextHotkeys.MAX_HOTKEYS + " already, the most there can be", false); return; }
        t.save();
        openEditor(h);
        focusField(Field.TEXT);
        scrollTarget = Integer.MAX_VALUE;
    }

    private void addPreset(int i) {
        TextHotkeys t = mod();
        if (t == null) return;
        Hotkey h = t.addPreset(i);
        if (h == null) { Notifications.push("Text Hotkeys", "You have " + TextHotkeys.MAX_HOTKEYS + " already, the most there can be", false); return; }
        t.save();
        openEditor(h);
        // The /msg preset: straight to the name to replace.
        if (h.text.contains(TextHotkeys.NAME_SLOT)) focusField(Field.TEXT);
        listening = h.text.contains(TextHotkeys.NAME_SLOT) ? null : h;
        pendingModifier = -1;
        scrollTarget = Integer.MAX_VALUE;
    }

    private void delete(Hotkey h) {
        TextHotkeys t = mod();
        if (t == null) return;
        int i = t.edit().indexOf(h);
        if (i < 0) return;
        if (h == editing) closeEditor();
        if (h == listening) listening = null;
        t.edit().remove(i);
        t.save();
        undoKey = h;
        undoIndex = i;
        undoAt = System.currentTimeMillis();
    }

    private void undo() {
        TextHotkeys t = mod();
        if (t == null || undoKey == null) return;
        if (t.hotkeys().size() >= TextHotkeys.MAX_HOTKEYS) { Notifications.push("Text Hotkeys", "The list is full", false); return; }
        t.edit().add(Math.min(undoIndex, t.hotkeys().size()), undoKey);
        t.save();
        undoKey = null;
    }

    private void move(Hotkey h, int by) {
        TextHotkeys t = mod();
        if (t == null) return;
        t.move(t.hotkeys().indexOf(h), by);
        t.save();
    }

    private void openEditor(Hotkey h) {
        editing = h;
        focus = Field.NONE;
    }

    private void closeEditor() {
        editing = null;
        focus = Field.NONE;
    }

    private void test(Hotkey h) {
        TextHotkeys t = mod();
        if (t == null) return;
        if (client == null || client.player == null) { Notifications.push("Text Hotkeys", "Join a world to try it in chat", false); return; }
        if (h.text.isBlank()) { Notifications.push("Text Hotkeys", "Type some text first", false); return; }
        // In the chat box, for you to read and send (or not) yourself.
        client.setScreen(new ChatScreen(t.expand(h.text.strip()), false));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        TextHotkeys t = mod();
        if (t == null) return 0;
        int y = top;
        List<Hotkey> list = t.hotkeys();
        clashes.clear();
        if (presetsOpen || list.isEmpty()) {
            if (inView(y, PRESET_H)) renderPresets(ctx, y, mx, my);
            y += PRESET_H + 4;
        }
        if (list.isEmpty()) {
            return y - top + EmptyState.draw(kit, "empty", cx + cw / 2, y + 16, "keyboard", "No hotkeys yet",
                    "Make one with New hotkey, or start from a preset above.", null, null) + 40;
        }
        for (int i = 0; i < list.size(); i++) {
            Hotkey h = list.get(i);
            if (inView(y, ROW_H)) {
                float a = enter(i), fd = Theme.fade;
                Theme.fade = fd * a;
                try { renderRow(ctx, t, h, i, list.size(), y + Math.round((1 - a) * 6), mx, my); } finally { Theme.fade = fd; }
            }
            y += ROW_H;
            if (h == editing) {
                if (inView(y, EDIT_H)) renderEditor(ctx, t, h, y, mx, my);
                y += EDIT_H;
            }
        }
        return y - top + 6;
    }

    private void renderPresets(DrawContext ctx, int y, int mx, int my) {
        Paint.surface(ctx, cx, y, cx + cw, y + PRESET_H - 4, Tokens.R_MD, Tokens.SUNKEN.withFill(Tokens.N150), 0, Float.NaN);
        PanelGlyphs.draw(ctx, "sparkle", cx + 10, y + (PRESET_H - 4) / 2f - 6, 12, Tokens.N600);
        kit.textCentredY("Start from", cx + 28, y + (PRESET_H - 4) / 2f, Tokens.N700, Role.LABEL);
        int x = cx + 36 + width("Start from", Role.LABEL);
        for (int i = 0; i < TextHotkeys.PRESETS.size(); i++) {
            String label = TextHotkeys.PRESETS.get(i)[1].strip();
            int w = Button.width(kit, label, Button.Size.SM, null, false);
            if (x + w > cx + cw - 6) break;
            final int pick = i;
            drawButton("preset:" + i, label, null, x, y + (PRESET_H - 4 - 20) / 2, w, Button.Size.SM, SECONDARY, true, b -> addPreset(pick));
            x += w + Tokens.S2;
        }
    }

    /** Who shares each hotkey's key, worked out once per frame. */
    private final java.util.Map<Hotkey, List<String>> clashes = new java.util.IdentityHashMap<>();

    private List<String> clashOf(TextHotkeys t, Hotkey h) {
        return h.enabled ? clashes.computeIfAbsent(h, t::conflicts) : List.of();
    }

    private static String id(Hotkey h) { return Integer.toHexString(System.identityHashCode(h)); }

    private void renderRow(DrawContext ctx, TextHotkeys t, Hotkey h, int index, int count, int y, int mx, int my) {
        boolean open = h == editing;
        int y2 = y + ROW_H - 3;
        boolean hv = mouseIn(mx, my, cx, y, cx + cw, y2);
        float hf = hover("row:" + id(h), hv);
        rowSurface(cx, y, cx + cw, y2, hf, open);
        float mid = (y + y2) / 2f;
        List<String> clash = clashOf(t, h);
        contentHit("row:" + id(h), cx, y, cx + cw, y2, b -> { if (open) closeEditor(); else openEditor(h); });

        // On/off
        Toggle.draw(kit, "on:" + id(h), cx + 10, Math.round(mid - Toggle.MD_H / 2f), false, h.enabled, true, () -> { h.enabled = !h.enabled; t.save(); });

        // Actions on the right (quiet), then the key chip
        int right = cx + cw - 8, by = Math.round(mid - 10);
        right = button("del:" + id(h), "Delete", null, right, by, DANGER, b -> delete(h)) - Tokens.S2;
        right -= 20;
        glyphButton("down:" + id(h), right, by, 20, "chev_down", "Move down", index < count - 1, false, b -> move(h, 1));
        right -= 20;
        glyphButton("up:" + id(h), right, by, 20, "chev_up", "Move up", index > 0, false, b -> move(h, -1));
        right -= Tokens.S2;
        right = button("edit:" + id(h), open ? "Done" : "Edit", open ? "check" : "edit", right, by, open ? SELECTED : SECONDARY,
                b -> { if (open) closeEditor(); else openEditor(h); }) - Tokens.S4;
        int keyLeft = renderKeyBox(ctx, h, !clash.isEmpty(), right, Math.round(mid - KeyChip.H / 2f), mx, my);

        // Title and details
        int tx = cx + 10 + Toggle.MD_W + Tokens.S5;
        int maxW = keyLeft - 10 - tx;
        kit.textCentredY(ellipsize(h.title(), Role.STRONG, maxW), tx, mid - 6, h.enabled ? Tokens.N950 : Tokens.N600, Role.STRONG);
        String sub;
        String icon = null;
        int iconColor = 0;
        if (!clash.isEmpty()) {
            sub = "Shares " + h.keyText() + " with " + String.join(", ", clash);
            icon = "warn";
            iconColor = Tokens.WARN;
        } else {
            StringBuilder b = new StringBuilder();
            if (!h.label.isBlank()) b.append(h.text.isBlank() ? "no text yet" : h.text.strip());
            else if (h.text.isBlank()) b.append("no text yet");
            else b.append(h.text.strip().startsWith("/") ? "command" : "chat message");
            b.append("  ·  ").append(h.servers.isBlank() ? "every server" : "only on " + h.servers.strip().replace("|", ", "));
            if (!h.enabled) b.append("  ·  off");
            if (h.text.contains(TextHotkeys.NAME_SLOT)) { b.append("  ·  replace ").append(TextHotkeys.NAME_SLOT); icon = "warn"; iconColor = Tokens.WARN; }
            sub = b.toString();
        }
        int sx = tx;
        if (icon != null) { Icons.draw(ctx, icon, sx, Math.round(mid + 7 - 5), 10, iconColor); sx += 14; }
        kit.textCentredY(ellipsize(sub, Role.SMALL, maxW - (sx - tx)), sx, mid + 7, Tokens.N700, Role.SMALL);
    }

    /** The key chip ending at {@code right} (top at y); returns its left edge. Click to set, right-click to clear. */
    private int renderKeyBox(DrawContext ctx, Hotkey h, boolean conflict, int right, int y, int mx, int my) {
        boolean l = h == listening;
        KeyChip.Mode mode = l ? KeyChip.Mode.LISTENING : conflict ? KeyChip.Mode.CONFLICT : h.bound() ? KeyChip.Mode.BOUND : KeyChip.Mode.NONE;
        String label = KeyChip.label(h.keyText());
        int slot = Math.max(76, KeyChip.width(kit, mode, label));
        int x1 = right - slot;
        String id = "key:" + id(h);
        if (l && pendingModifier >= 0) {
            String p = Keybinds.modsPrefix(Keybinds.modifierOf(pendingModifier)) + "…";
            Paint.sunken(ctx, x1, y, right, y + KeyChip.H, Tokens.R_XS);
            Paint.ring(ctx, x1, y, right, y + KeyChip.H, Tokens.R_XS, Tokens.A400, 2);
            kit.textCentredY(p, x1 + (slot - width(p, Role.TINY)) / 2, y + KeyChip.H / 2f, Tokens.N950, Role.TINY);
        } else {
            int w = KeyChip.width(kit, mode, label);
            KeyChip.draw(kit, id, right - w, y, mode, label, conflict ? "Also on this key: " + String.join(", ", clashes.getOrDefault(h, List.of())) : null, () -> {});
        }
        contentHit(id, x1, y, right, y + KeyChip.H, b -> clickKey(h, b));
        return x1;
    }

    private void renderEditor(DrawContext ctx, TextHotkeys t, Hotkey h, int y, int mx, int my) {
        Paint.surface(ctx, cx + 12, y - 1, cx + cw, y + EDIT_H - 6, Tokens.R_MD, Tokens.SUNKEN.withFill(Tokens.N150), 0, Float.NaN);
        Paint.slant(ctx, cx + 4, y + 6, cx + 6, y + EDIT_H - 13, Theme.alpha(Tokens.A500, 0.5f));
        int lx = cx + 26, vx = cx + 100, fx2 = cx + cw - 12;
        int row = y + 8;

        // Text, with its live counter
        label("Text", lx, row + 11);
        int len = h.text.codePointCount(0, h.text.length());
        String count = len + " / " + TextHotkeys.MAX_CHARS;
        int countW = UiFont.tabularWidth(textRenderer, count, Role.SMALL, smooth);
        int countColor = len >= TextHotkeys.MAX_CHARS ? Tokens.DANGER : len >= TextHotkeys.MAX_CHARS - 20 ? Tokens.WARN : Tokens.N700;
        field(ctx, Field.TEXT, h.text, "a chat message, or a command starting with /", vx, row, fx2 - countW - 10, mx, my);
        UiFont.drawTabular(ctx, textRenderer, count, fx2 - countW, Math.round(UiFont.centredY(Role.SMALL, row + 11, smooth)), countColor, Role.SMALL, smooth);
        row += 26;
        String note = "Starts with / for a command.  {server} and {time} are filled in when sent.";
        boolean warnNote = h.text.contains(TextHotkeys.NAME_SLOT);
        if (warnNote) note = "Replace " + TextHotkeys.NAME_SLOT + " with the player's name: it is never filled in for you.";
        else if (h.text.indexOf('{') >= 0) {
            String shown = t.expand(h.text.strip());
            note = (shown.length() > TextHotkeys.MAX_CHARS ? "Too long once filled in: " : "Sends: ") + shown;
        }
        int nx = vx;
        if (warnNote) { Icons.draw(ctx, "warn", nx, row, 10, Tokens.WARN); nx += 14; }
        kit.text(ellipsize(note, Role.SMALL, fx2 - nx), nx, row, Tokens.N700, Role.SMALL);
        row += 14;

        // Label
        label("Label", lx, row + 11);
        field(ctx, Field.LABEL, h.label, "optional, shown when it sends (e.g. Lobby)", vx, row, Math.min(fx2, vx + 260), mx, my);
        row += 28;

        // Servers
        label("Servers", lx, row + 11);
        field(ctx, Field.SERVERS, h.servers, "every server  (or e.g. hypixel.net | mc.example.org)", vx, row, fx2, mx, my);
        row += 26;
        String where = h.servers.isBlank() ? "Works everywhere. Only on servers whose address contains one of these, separated by |."
                : TextHotkeys.worksOn(h, t.serverAddress()) ? "Works on the server you're on." : "Doesn't work where you are now.";
        kit.text(ellipsize(where, Role.SMALL, fx2 - vx), vx, row, Tokens.N700, Role.SMALL);
        row += 16;

        // Key and actions
        label("Key", lx, row + 10);
        List<String> clash = clashOf(t, h);
        int kx2 = vx + Math.max(76, KeyChip.width(kit, h == listening ? KeyChip.Mode.LISTENING : KeyChip.Mode.BOUND, KeyChip.label(h.keyText())));
        renderKeyBox(ctx, h, !clash.isEmpty(), kx2, row + 1, mx, my);
        String keyNote = h == listening ? "Hold Ctrl / Shift / Alt with it for a combo  ·  side mouse buttons work  ·  Esc cancels  ·  Delete clears"
                : !clash.isEmpty() ? "Also on this key: " + String.join(", ", clash) : "Click to set, right-click to clear";
        int bx = button("test:" + id(h), "Test in chat", "keyboard", fx2, row, SECONDARY, b -> test(h));
        kit.textCentredY(ellipsize(keyNote, Role.SMALL, bx - kx2 - 16), kx2 + 8, row + 10, Tokens.N700, Role.SMALL);
    }

    private void label(String s, int x, float centreY) { kit.textCentredY(s, x, centreY, Tokens.N800, Role.LABEL); }

    /** A one-line text box in the kit's input style (sunken, accent ring and caret when focused). Typing changes the
     *  hotkey right away. */
    private void field(DrawContext ctx, Field f, String value, String placeholder, int x1, int y, int x2, int mx, int my) {
        boolean focused = focus == f;
        String id = "field:" + f;
        boolean hv = mouseIn(mx, my, x1, y, x2, y + 22);
        float hov = hover(id, hv), foc = kit.anim(id + "#f", focused ? 1f : 0f);
        int h = 22;
        Paint.surface(ctx, x1, y, x2, y + h, Tokens.R_SM, Tokens.SUNKEN.withFill(Theme.lerpColor(Tokens.N100, Tokens.N200, 0.5f * hov)), 0, Float.NaN);
        if (foc > 0.01f) {
            Paint.glow(kit, x1, y, x2, y + h, Tokens.R_SM, 2, Theme.alpha(Tokens.A600, 0.3f * foc));
            Paint.ring(ctx, x1, y, x2, y + h, Tokens.R_SM, Theme.alpha(Tokens.A400, foc), 1);
        }
        int inner = x2 - x1 - 16;
        float cyy = y + h / 2f;
        if (value.isEmpty() && !focused) {
            kit.textCentredY(ellipsize(placeholder, Role.BODY, inner), x1 + 8, cyy, Tokens.N600, Role.BODY);
        } else if (!focused) {
            kit.textCentredY(ellipsize(value, Role.BODY, inner), x1 + 8, cyy, Tokens.N950, Role.BODY);
        } else {
            // Scroll so the cursor stays in view.
            int c = Math.min(cursor, value.length());
            int start = 0;
            while (start < c && width(value.substring(start, c), Role.BODY) > inner) start++;
            String shown = value.substring(start);
            while (!shown.isEmpty() && width(shown, Role.BODY) > inner) shown = shown.substring(0, shown.length() - 1);
            kit.textCentredY(shown, x1 + 8, cyy, Tokens.N950, Role.BODY);
            if ((System.currentTimeMillis() / 530) % 2 == 0 || Motion.level() == Motion.Level.OFF) {
                int cxp = x1 + 8 + width(value.substring(start, c), Role.BODY);
                Theme.fill(ctx, cxp, Math.round(cyy - 5), cxp + 1, Math.round(cyy + 5), Tokens.A400);
            }
        }
        contentHit(id, x1, y, x2, y + h, b -> focusField(f));
    }

    @Override protected int footerHeight() { return 30; }

    @Override
    protected void renderFooter(DrawContext ctx, int x1, int y, int x2, int mx, int my) {
        String hint = listening != null ? "Press a key · hold Ctrl, Shift or Alt with it for a combo · Esc cancels · Delete clears"
                : "Each press sends its text once · nothing sends while a menu or chat is open · .hotkeys opens this";
        Icons.draw(ctx, listening != null ? "keyboard" : "info", x1, y + 9, 12, listening != null ? Tokens.A400 : Tokens.N600);
        kit.textCentredY(ellipsize(hint, Role.SMALL, x2 - x1 - 18), x1 + 18, y + 15, Tokens.N700, Role.SMALL);
    }

    @Override
    protected void renderOverlay(DrawContext ctx, int mx, int my) {
        long now = System.currentTimeMillis();
        if (undoKey != null && now - undoAt < UNDO_MS) {
            String msg = "Deleted " + undoKey.title();
            int bw = Math.min(cw, width(msg, Role.BODY) + Button.width(kit, "Undo", Button.Size.SM, "reset", false) + 40);
            float in = Motion.decorative() ? Motion.standard(Motion.clamp01((now - undoAt) / (float) Motion.DUR_BASE)) : 1f;
            int x1 = wx + (ww - bw) / 2, y1 = wy + wh - 36 + Math.round((1 - in) * 8);
            float f = Theme.fade;
            Theme.fade = f * Math.min(1f, in * 1.5f);
            try {
                Paint.surface(ctx, x1, y1, x1 + bw, y1 + 28, Tokens.R_MD, Tokens.E4, 0, Float.NaN);
                Icons.draw(ctx, "check", x1 + 10, y1 + 8, 12, Tokens.OK);
                kit.textCentredY(ellipsize(msg, Role.BODY, bw - 100), x1 + 28, y1 + 14, Tokens.N950, Role.BODY);
                button("undo", "Undo", "reset", x1 + bw - 4, y1 + 4, SECONDARY, b -> undo());
                float left = 1f - (now - undoAt) / (float) UNDO_MS;
                Paint.rect(ctx, x1 + 8, y1 + 26, x1 + 8 + (bw - 16) * left, y1 + 27, 0.5f, Theme.alpha(Tokens.OK, 0.6f));
            } finally {
                Theme.fade = f;
            }
        } else {
            undoKey = null;
        }
    }

    // ------------------------------------------------------------------ text boxes

    private String fieldValue(Field f) {
        if (editing == null) return "";
        return switch (f) {
            case TEXT -> editing.text;
            case LABEL -> editing.label;
            case SERVERS -> editing.servers;
            case NONE -> "";
        };
    }

    private int fieldMax(Field f) {
        return switch (f) {
            case TEXT -> TextHotkeys.MAX_CHARS;
            case LABEL -> TextHotkeys.MAX_LABEL;
            case SERVERS -> TextHotkeys.MAX_SERVERS;
            case NONE -> 0;
        };
    }

    private void setField(Field f, String v) {
        if (editing == null) return;
        String c = TextHotkeys.clean(v, fieldMax(f));
        switch (f) {
            case TEXT -> editing.text = c;
            case LABEL -> editing.label = c;
            case SERVERS -> editing.servers = c;
            case NONE -> {}
        }
        cursor = Math.min(cursor, c.length());
    }

    private void focusField(Field f) {
        if (editing == null) return;
        focus = f;
        cursor = fieldValue(f).length();
        listening = null;
    }

    private void insert(String s) {
        String v = fieldValue(focus);
        int room = fieldMax(focus) - v.codePointCount(0, v.length());
        if (room <= 0) return;
        s = TextHotkeys.clean(s, room);
        if (s.isEmpty()) return;
        int c = Math.min(cursor, v.length());
        setField(focus, v.substring(0, c) + s + v.substring(c));
        cursor = Math.min(c + s.length(), fieldValue(focus).length());
    }

    private boolean fieldKey(KeyInput input) {
        int key = input.key();
        boolean ctrl = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        String v = fieldValue(focus);
        int c = Math.min(cursor, v.length());
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> focus = Field.NONE;
            case GLFW.GLFW_KEY_TAB -> focusField(switch (focus) { case TEXT -> Field.LABEL; case LABEL -> Field.SERVERS; default -> Field.TEXT; });
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (c == 0) break;
                int from = ctrl ? wordStart(v, c) : v.offsetByCodePoints(c, -1);
                setField(focus, v.substring(0, from) + v.substring(c));
                cursor = from;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (c >= v.length()) break;
                setField(focus, v.substring(0, c) + v.substring(v.offsetByCodePoints(c, 1)));
                cursor = c;
            }
            case GLFW.GLFW_KEY_LEFT -> cursor = c == 0 ? 0 : ctrl ? wordStart(v, c) : v.offsetByCodePoints(c, -1);
            case GLFW.GLFW_KEY_RIGHT -> cursor = c >= v.length() ? v.length() : v.offsetByCodePoints(c, 1);
            case GLFW.GLFW_KEY_HOME -> cursor = 0;
            case GLFW.GLFW_KEY_END -> cursor = v.length();
            case GLFW.GLFW_KEY_V -> { if (ctrl && client != null) insert(client.keyboard.getClipboard()); }
            case GLFW.GLFW_KEY_C -> { if (ctrl && client != null) client.keyboard.setClipboard(v); }
            default -> {}
        }
        return true;
    }

    private static int wordStart(String v, int c) {
        int i = c;
        while (i > 0 && v.charAt(i - 1) == ' ') i--;
        while (i > 0 && v.charAt(i - 1) != ' ') i--;
        return i;
    }

    // ------------------------------------------------------------------ key capture

    private void clickKey(Hotkey h, int button) {
        if (listening == h) {
            // A click while waiting: middle/side buttons bind, anything else cancels.
            if (button >= GLFW.GLFW_MOUSE_BUTTON_MIDDLE) bind(h, KeySetting.mouseCode(button), 0);
            else listening = null;
            return;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { bind(h, GLFW.GLFW_KEY_UNKNOWN, 0); return; }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            focus = Field.NONE;
            listening = h;
            pendingModifier = -1;
        }
    }

    private void bind(Hotkey h, int code, int mods) {
        listening = null;
        pendingModifier = -1;
        TextHotkeys t = mod();
        if (t == null) return;
        if (code != GLFW.GLFW_KEY_UNKNOWN && !TextHotkeys.validKey(code)) {
            Notifications.push("Text Hotkeys", "Can't use that key", false);
            return;
        }
        ClientSettings cs = settings();
        if (cs != null && code != GLFW.GLFW_KEY_UNKNOWN && code == cs.menuKey.get() && mods == 0) {
            Notifications.push("Text Hotkeys", "That's the Menu Key", false);
            return;
        }
        h.key = code;
        h.mods = code == GLFW.GLFW_KEY_UNKNOWN ? 0 : mods & Keybinds.ALL_MODS;
        t.save();
        List<String> clash = t.conflicts(h);
        if (!clash.isEmpty()) Notifications.push(h.title(), "Shares " + h.keyText() + " with " + String.join(", ", clash), false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (listening != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { listening = null; pendingModifier = -1; return true; }
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) { bind(listening, GLFW.GLFW_KEY_UNKNOWN, 0); return true; }
            if (Keybinds.modifierOf(key) != 0) {
                // Wait: either another key follows (a combo) or this modifier is released alone (bind to it).
                pendingModifier = key;
                return true;
            }
            int m = input.modifiers(), mods = 0;
            if ((m & GLFW.GLFW_MOD_CONTROL) != 0) mods |= Keybinds.CTRL;
            if ((m & GLFW.GLFW_MOD_SHIFT) != 0) mods |= Keybinds.SHIFT;
            if ((m & GLFW.GLFW_MOD_ALT) != 0) mods |= Keybinds.ALT;
            // Also whatever modifier is held right now (some input paths don't report the flags with the key).
            mods |= Keybinds.heldMods();
            if (pendingModifier >= 0) mods |= Keybinds.modifierOf(pendingModifier);
            bind(listening, key, mods);
            return true;
        }
        if (focus != Field.NONE && editing != null) return fieldKey(input);
        if (key == GLFW.GLFW_KEY_Z && (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && undoKey != null) { undo(); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (editing != null) { closeEditor(); return true; }
            close();
            return true;
        }
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
    public boolean charTyped(CharInput input) {
        if (listening != null) return true;
        if (focus != Field.NONE && editing != null) {
            if (input.isValidChar()) insert(input.asString());
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        Hotkey before = listening;
        // Middle and side buttons bind while waiting for a key, wherever the mouse is.
        if (before != null && click.button() >= GLFW.GLFW_MOUSE_BUTTON_MIDDLE) { bind(before, KeySetting.mouseCode(click.button()), 0); return true; }
        focus = Field.NONE; // a click on a text box focuses it again
        boolean handled = super.mouseClicked(click, doubled);
        if (before != null && listening == before) listening = null;
        return handled;
    }

    @Override
    public void removed() {
        TextHotkeys t = mod();
        if (t != null) t.save();
        super.removed();
    }

    // ------------------------------------------------------------------ test hooks

    public void editForTest(int index) { openEditor(list().get(index)); }
    public void focusTextForTest() { focusField(Field.TEXT); }
    public void captureKeyForTest(int index) { listening = list().get(index); pendingModifier = -1; focus = Field.NONE; }
    public boolean capturingForTest() { return listening != null; }
    public void addPresetForTest(int i) { addPreset(i); }
    public void addNewForTest() { addNew(); }
}
