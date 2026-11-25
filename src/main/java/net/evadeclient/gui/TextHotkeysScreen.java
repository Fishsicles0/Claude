package net.evadeclient.gui;

import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.misc.TextHotkeys;
import net.evadeclient.module.misc.TextHotkeys.Hotkey;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.util.Keybinds;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
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
    private static final int ROW_H = 32, EDIT_H = 132, PRESET_H = 30;
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

    @Override protected int prefWidth() { return 680; }
    @Override protected int prefHeight() { return 440; }

    @Override
    protected String subtitle() {
        TextHotkeys t = mod();
        int n = list().size();
        String s = n + " of " + TextHotkeys.MAX_HOTKEYS + " hotkeys  ·  one message per press, at most one every 1.5 s";
        return t != null && !t.isEnabled() ? "Text Hotkeys is off (Server tab)  ·  " + s : s;
    }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        boolean full = list().size() >= TextHotkeys.MAX_HOTKEYS;
        int x = button(ctx, "new", full ? "List full" : "+ New", right, y, mx, my, full ? 0 : 1, b -> addNew());
        button(ctx, "presets", presetsOpen ? "Hide presets" : "Presets", x - 6, y, mx, my, presetsOpen ? 1 : 0, b -> presetsOpen = !presetsOpen);
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
            String msg = "No hotkeys yet. Click + New, or a preset above to start from.";
            text(ctx, msg, cx + (cw - width(msg, Role.BODY)) / 2, y + 24, Theme.TEXT_DIM, Role.BODY);
            return y - top + 60;
        }
        for (int i = 0; i < list.size(); i++) {
            Hotkey h = list.get(i);
            if (inView(y, ROW_H)) renderRow(ctx, t, h, i, list.size(), y, mx, my);
            y += ROW_H;
            if (h == editing) {
                if (inView(y, EDIT_H)) renderEditor(ctx, t, h, y, mx, my);
                y += EDIT_H;
            }
        }
        return y - top + 6;
    }

    private void renderPresets(DrawContext ctx, int y, int mx, int my) {
        Theme.roundBordered(ctx, cx, y, cx + cw, y + PRESET_H - 2, Theme.CARD, Theme.BORDER);
        text(ctx, "Add a preset", cx + 10, y + 10, Theme.TEXT_SOFT, Role.LABEL);
        int x = cx + 18 + width("Add a preset", Role.LABEL);
        for (int i = 0; i < TextHotkeys.PRESETS.size(); i++) {
            String label = TextHotkeys.PRESETS.get(i)[1].strip();
            int w = width(label, Role.LABEL) + 14;
            if (x + w > cx + cw - 6) break;
            boolean hv = mouseIn(mx, my, x, y + 6, x + w, y + 22);
            float h = hover("preset:" + i, hv);
            Theme.roundBordered(ctx, x, y + 6, x + w, y + 22, Theme.lerpColor(Theme.CONTROL, Theme.CONTROL_HOVER, h),
                    Theme.lerpColor(Theme.BORDER, Theme.RED, h * 0.8f));
            text(ctx, label, x + 7, y + 10, Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, h), Role.LABEL);
            final int pick = i;
            contentHit(x, y + 6, x + w, y + 22, b -> addPreset(pick));
            x += w + 5;
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
        boolean hv = mouseIn(mx, my, cx, y, cx + cw, y + ROW_H - 3);
        float hf = hover("row:" + id(h), hv);
        Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 3, open ? Theme.tint(0.08f) : Theme.lerpColor(Theme.ROW, Theme.ROW_HOVER, hf));
        List<String> clash = clashOf(t, h);
        if (!clash.isEmpty()) Theme.round(ctx, cx, y + 5, cx + 2, y + ROW_H - 8, Theme.DANGER, 1);
        contentHit(cx, y, cx + cw, y + ROW_H - 3, b -> { if (open) closeEditor(); else openEditor(h); });

        // On/off
        float on = hover("on:" + id(h), h.enabled);
        Theme.toggle(ctx, cx + 8, y + 10, on);
        contentHit(cx + 4, y + 4, cx + 32, y + ROW_H - 7, b -> { h.enabled = !h.enabled; t.save(); });

        // Buttons on the right, then the key box
        int right = cx + cw - 6, by = y + 6;
        right = button(ctx, "del:" + id(h), "Delete", right, by, mx, my, 2, b -> delete(h)) - 4;
        right = index < count - 1 ? button(ctx, "down:" + id(h), "▼", right, by, mx, my, 0, b -> move(h, 1)) - 3 : right - 22;
        right = index > 0 ? button(ctx, "up:" + id(h), "▲", right, by, mx, my, 0, b -> move(h, -1)) - 4 : right - 22;
        right = button(ctx, "edit:" + id(h), open ? "Done" : "Edit", right, by, mx, my, open ? 1 : 0,
                b -> { if (open) closeEditor(); else openEditor(h); }) - 8;
        int keyRight = right;
        int keyLeft = renderKeyBox(ctx, h, !clash.isEmpty(), keyRight, by, mx, my);

        // Title and details
        int tx = cx + 38;
        int maxW = keyLeft - 10 - tx;
        text(ctx, ellipsize(h.title(), Role.STRONG, maxW), tx, y + 5, h.enabled ? Theme.TEXT : Theme.TEXT_FAINT, Role.STRONG);
        String sub;
        int subColor = Theme.TEXT_DIM;
        if (!clash.isEmpty()) {
            sub = "Shares " + h.keyText() + " with " + String.join(", ", clash);
            subColor = Theme.DANGER;
        } else {
            StringBuilder b = new StringBuilder();
            if (!h.label.isBlank()) b.append(h.text.isBlank() ? "no text yet" : h.text.strip());
            else if (h.text.isBlank()) b.append("no text yet");
            else b.append(h.text.strip().startsWith("/") ? "command" : "chat message");
            b.append("  ·  ").append(h.servers.isBlank() ? "every server" : "only on " + h.servers.strip().replace("|", ", "));
            if (!h.enabled) b.append("  ·  off");
            if (h.text.contains(TextHotkeys.NAME_SLOT)) { b.append("  ·  replace ").append(TextHotkeys.NAME_SLOT); subColor = Theme.WARN; }
            sub = b.toString();
        }
        text(ctx, ellipsize(sub, Role.SMALL, maxW), tx, y + 17, subColor, Role.SMALL);
    }

    /** The key box ending at {@code right}; returns its left edge. Click to set, right-click to clear. */
    private int renderKeyBox(DrawContext ctx, Hotkey h, boolean conflict, int right, int y, int mx, int my) {
        boolean l = h == listening;
        String label = l ? (pendingModifier >= 0 ? Keybinds.modsPrefix(Keybinds.modifierOf(pendingModifier)) + "..." : "Press a key...")
                : h.keyText();
        int bw = Math.max(84, width(label, Role.LABEL) + 16);
        int x1 = right - bw;
        boolean hv = mouseIn(mx, my, x1, y, right, y + 16);
        float bh = hover("key:" + id(h), hv);
        int border = l ? Theme.RED : conflict ? Theme.DANGER : Theme.lerpColor(Theme.BORDER, Theme.BORDER_HOVER, bh);
        Theme.roundBordered(ctx, x1, y, right, y + 16, Theme.lerpColor(Theme.CONTROL, Theme.CONTROL_HOVER, bh), border);
        int tc = l ? Theme.RED_BRIGHT : conflict ? Theme.DANGER : h.bound() ? Theme.TEXT : Theme.TEXT_FAINT;
        text(ctx, label, x1 + (bw - width(label, Role.LABEL)) / 2, y + 4, tc, Role.LABEL);
        contentHit(x1, y, right, y + 16, b -> clickKey(h, b));
        return x1;
    }

    private void renderEditor(DrawContext ctx, TextHotkeys t, Hotkey h, int y, int mx, int my) {
        Theme.roundBordered(ctx, cx + 8, y - 2, cx + cw, y + EDIT_H - 6, Theme.CARD, Theme.BORDER);
        int lx = cx + 20, vx = cx + 96, fx2 = cx + cw - 12;
        int row = y + 6;

        // Text, with its live counter
        text(ctx, "Text", lx, row + 4, Theme.TEXT_SOFT, Role.LABEL);
        int len = h.text.codePointCount(0, h.text.length());
        String count = len + " / " + TextHotkeys.MAX_CHARS;
        int countW = width(count, Role.SMALL);
        int countColor = len >= TextHotkeys.MAX_CHARS ? Theme.DANGER : len >= TextHotkeys.MAX_CHARS - 20 ? Theme.WARN : Theme.TEXT_DIM;
        field(ctx, Field.TEXT, h.text, "a chat message, or a command starting with /", vx, row, fx2 - countW - 10, mx, my);
        text(ctx, count, fx2 - countW, row + 5, countColor, Role.SMALL);
        row += 19;
        String note = "Starts with / for a command.  {server} and {time} are filled in when sent.";
        if (h.text.contains(TextHotkeys.NAME_SLOT)) note = "Replace " + TextHotkeys.NAME_SLOT + " with the player's name: it is never filled in for you.";
        else if (h.text.indexOf('{') >= 0) {
            String shown = t.expand(h.text.strip());
            note = (shown.length() > TextHotkeys.MAX_CHARS ? "Too long once filled in: " : "Sends: ") + shown;
        }
        text(ctx, ellipsize(note, Role.SMALL, fx2 - vx), vx, row, h.text.contains(TextHotkeys.NAME_SLOT) ? Theme.WARN : Theme.TEXT_FAINT, Role.SMALL);
        row += 14;

        // Label
        text(ctx, "Label", lx, row + 4, Theme.TEXT_SOFT, Role.LABEL);
        field(ctx, Field.LABEL, h.label, "optional, shown when it sends (e.g. Lobby)", vx, row, Math.min(fx2, vx + 240), mx, my);
        row += 22;

        // Servers
        text(ctx, "Servers", lx, row + 4, Theme.TEXT_SOFT, Role.LABEL);
        field(ctx, Field.SERVERS, h.servers, "every server  (or e.g. hypixel.net | mc.example.org)", vx, row, fx2, mx, my);
        row += 19;
        String where = h.servers.isBlank() ? "Works everywhere. Only on servers whose address contains one of these, separated by |."
                : TextHotkeys.worksOn(h, t.serverAddress()) ? "Works on the server you're on." : "Doesn't work where you are now.";
        text(ctx, ellipsize(where, Role.SMALL, fx2 - vx), vx, row, Theme.TEXT_FAINT, Role.SMALL);
        row += 15;

        // Key and actions
        text(ctx, "Key", lx, row + 4, Theme.TEXT_SOFT, Role.LABEL);
        List<String> clash = clashOf(t, h);
        int kx2 = vx + Math.max(84, width(h.keyText(), Role.LABEL) + 16);
        renderKeyBox(ctx, h, !clash.isEmpty(), kx2, row, mx, my);
        String keyNote = h == listening ? "Hold Ctrl / Shift / Alt with it for a combo  ·  side mouse buttons work  ·  Esc cancels  ·  Delete clears"
                : !clash.isEmpty() ? "Also on this key: " + String.join(", ", clash) : "Click to set, right-click to clear";
        int bx = button(ctx, "test:" + id(h), "Test in chat", fx2, row, mx, my, 0, b -> test(h));
        text(ctx, ellipsize(keyNote, Role.SMALL, bx - kx2 - 16), kx2 + 8, row + 5, !clash.isEmpty() && h != listening ? Theme.DANGER : Theme.TEXT_FAINT, Role.SMALL);
    }

    /** A one-line text box. Typing changes the hotkey right away. */
    private void field(DrawContext ctx, Field f, String value, String placeholder, int x1, int y, int x2, int mx, int my) {
        boolean focused = focus == f;
        boolean hv = mouseIn(mx, my, x1, y, x2, y + 16);
        Theme.roundBordered(ctx, x1, y, x2, y + 16, Theme.INPUT, focused ? Theme.RED : hv ? Theme.BORDER_HOVER : Theme.BORDER);
        int inner = x2 - x1 - 12;
        if (value.isEmpty() && !focused) {
            text(ctx, ellipsize(placeholder, Role.BODY, inner), x1 + 6, y + 4, Theme.TEXT_FAINT, Role.BODY);
        } else if (!focused) {
            text(ctx, ellipsize(value, Role.BODY, inner), x1 + 6, y + 4, Theme.TEXT, Role.BODY);
        } else {
            // Scroll so the cursor stays in view.
            int c = Math.min(cursor, value.length());
            int start = 0;
            while (start < c && width(value.substring(start, c), Role.BODY) > inner) start++;
            String shown = value.substring(start);
            while (!shown.isEmpty() && width(shown, Role.BODY) > inner) shown = shown.substring(0, shown.length() - 1);
            text(ctx, shown, x1 + 6, y + 4, Theme.TEXT, Role.BODY);
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                int cxp = x1 + 6 + width(value.substring(start, c), Role.BODY);
                Theme.fill(ctx, cxp, y + 3, cxp + 1, y + 13, Theme.TEXT);
            }
        }
        contentHit(x1, y, x2, y + 16, b -> focusField(f));
    }

    @Override
    protected void renderOverlay(DrawContext ctx, int mx, int my) {
        long now = System.currentTimeMillis();
        if (undoKey != null && now - undoAt < UNDO_MS) {
            String msg = "Deleted " + undoKey.title();
            int bw = Math.min(cw, width(msg, Role.LABEL) + width("Undo", Role.LABEL) + 44);
            int x1 = wx + (ww - bw) / 2, y1 = wy + wh - 30;
            Theme.shadow(ctx, Theme.SH_CARD, x1, y1, x1 + bw, y1 + 22, 0x90000000);
            Theme.roundBordered(ctx, x1, y1, x1 + bw, y1 + 22, Theme.CARD, Theme.BORDER);
            text(ctx, ellipsize(msg, Role.LABEL, bw - 60), x1 + 10, y1 + 7, Theme.TEXT, Role.LABEL);
            button(ctx, "undo", "Undo", x1 + bw - 6, y1 + 3, mx, my, 1, b -> undo());
        } else {
            undoKey = null;
            String hint = listening != null ? "Press a key (hold Ctrl / Shift / Alt with it for a combo)  ·  Esc cancels  ·  Delete clears"
                    : "Each press sends its text once  ·  nothing sends while a menu or chat is open  ·  .hotkeys opens this";
            text(ctx, ellipsize(hint, Role.SMALL, cw), cx, wy + wh - 10, Theme.TEXT_FAINT, Role.SMALL);
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
