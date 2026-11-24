package net.evadeclient.chat;

import net.evadeclient.module.misc.ChatEmoji;
import net.evadeclient.module.misc.ChatTabs;
import net.evadeclient.util.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * What Evade adds to the open chat box: the Chat Tabs bar just above it, and the Emoji button at its right end with
 * the emoji grid it opens. Drawn and clicked from ChatScreenV25Mixin. Nothing here sends anything: a picked emoji is
 * only typed into the box.
 */
public final class ChatScreenExtras {
    private ChatScreenExtras() {}

    private static final int CELL = 13, COLS = 11, PAD = 4, HEADER = 11;
    private static final String BUTTON = "☺";

    /** One clickable rectangle and what it stands for (a tab index, -1 for All). */
    private record Chip(int x1, int y1, int x2, int y2, int tab, String label, int unread) {
        boolean has(double x, double y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    private static boolean pickerOpen;

    /** From the chat screen's init: a freshly opened chat box starts with the grid closed. */
    public static void onOpen() { pickerOpen = false; }

    public static boolean pickerOpen() { return pickerOpen; }

    // ------------------------------------------------------------------ chat tabs bar

    private static List<Chip> chips(TextRenderer tr, int height) {
        ChatTabs tabs = ChatTabs.active();
        if (tabs == null) return List.of();
        List<ChatTabs.Tab> list = tabs.tabs();
        if (list.isEmpty()) return List.of();
        List<Chip> out = new ArrayList<>(list.size() + 1);
        int x = 2, y1 = height - 27, y2 = height - 16;
        for (int i = -1; i < list.size(); i++) {
            String label = i < 0 ? "All" : list.get(i).name();
            int unread = i < 0 ? 0 : tabs.unread(i);
            int w = tr.getWidth(label) + 8 + (unread > 0 ? tr.getWidth(" " + count(unread)) : 0);
            out.add(new Chip(x, y1, x + w, y2, i, label, unread));
            x += w + 2;
        }
        return out;
    }

    private static String count(int n) { return n > 99 ? "99+" : String.valueOf(n); }

    /** Draws the tab bar above the chat box (before the command suggestions, which stay on top). */
    public static void renderTabs(DrawContext ctx, TextRenderer tr, int height, int mx, int my) {
        ChatTabs tabs = ChatTabs.active();
        if (tabs == null) return;
        for (Chip c : chips(tr, height)) {
            boolean on = tabs.selected() == c.tab();
            boolean hover = c.has(mx, my);
            int fill = on ? Theme.RED : hover ? 0xC0303038 : 0x90000000;
            ctx.fill(c.x1(), c.y1(), c.x2(), c.y2(), fill);
            int tx = c.x1() + 4;
            ctx.drawText(tr, c.label(), tx, c.y1() + 2, on ? 0xFFFFFFFF : hover ? 0xFFF0F0F0 : 0xFFB8B8C0, false);
            if (c.unread() > 0) {
                ctx.drawText(tr, " " + count(c.unread()), tx + tr.getWidth(c.label()), c.y1() + 2, on ? 0xFFFFE08A : 0xFFFFD21F, false);
            }
        }
    }

    // ------------------------------------------------------------------ emoji button and grid

    private static boolean pickerShown() {
        ChatEmoji e = ChatEmoji.active();
        return e != null && e.showsPicker();
    }

    private static int[] button(int width, int height) { return new int[]{width - 14, height - 14, width - 2, height - 2}; }

    private static List<String> gridEmoji(ChatEmoji e) {
        List<String> out = new ArrayList<>();
        for (Emoji.Entry en : Emoji.ALL) out.add(en.emoji());
        return out;
    }

    /** {x1, y1, x2, y2} of the grid panel, its first row holding your recent emoji. */
    private static int[] panel(int width, int height, int recentRows) {
        int rows = (Emoji.ALL.size() + COLS - 1) / COLS + recentRows;
        int w = COLS * CELL + PAD * 2, h = HEADER + rows * CELL + PAD * 2 + (recentRows > 0 ? 3 : 0);
        int x2 = width - 2, y2 = height - 16;
        return new int[]{x2 - w, Math.max(2, y2 - h), x2, y2};
    }

    /** Draws the emoji button and, when open, the grid with the hovered emoji's code in its header. */
    public static void renderEmoji(DrawContext ctx, TextRenderer tr, int width, int height, int mx, int my) {
        if (!pickerShown()) {
            pickerOpen = false;
            return;
        }
        int[] b = button(width, height);
        boolean hb = mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3];
        ctx.fill(b[0], b[1], b[2], b[3], pickerOpen ? Theme.RED : hb ? 0xC0303038 : 0x60000000);
        ctx.drawText(tr, BUTTON, b[0] + (b[2] - b[0] - tr.getWidth(BUTTON)) / 2 + 1, b[1] + 2, 0xFFFFFFFF, false);
        if (!pickerOpen) return;

        ChatEmoji e = ChatEmoji.active();
        List<String> recents = e.recents();
        int[] p = panel(width, height, recents.isEmpty() ? 0 : 1);
        ctx.fill(p[0], p[1], p[2], p[3], 0xF00E0E11);
        ctx.fill(p[0], p[1], p[2], p[1] + 1, Theme.RED);
        String hovered = null;
        int y = p[1] + PAD + HEADER;
        if (!recents.isEmpty()) {
            hovered = drawRow(ctx, tr, recents, p[0] + PAD, y, mx, my, hovered);
            y += CELL + 3;
            ctx.fill(p[0] + PAD, y - 2, p[2] - PAD, y - 1, 0x40FFFFFF);
        }
        List<String> all = gridEmoji(e);
        for (int i = 0; i < all.size(); i += COLS) {
            hovered = drawRow(ctx, tr, all.subList(i, Math.min(all.size(), i + COLS)), p[0] + PAD, y, mx, my, hovered);
            y += CELL;
        }
        String head = hovered == null ? (recents.isEmpty() ? "Emoji" : "Recent, then all emoji") : ":" + Emoji.codeOf(hovered) + ":";
        ctx.drawText(tr, head, p[0] + PAD, p[1] + PAD, hovered == null ? 0xFF9B9BA6 : 0xFFFFFFFF, false);
    }

    private static String drawRow(DrawContext ctx, TextRenderer tr, List<String> row, int x, int y, int mx, int my, String hovered) {
        for (int i = 0; i < row.size(); i++) {
            int cx = x + i * CELL;
            boolean h = mx >= cx && mx < cx + CELL && my >= y && my < y + CELL;
            if (h) {
                ctx.fill(cx, y, cx + CELL, y + CELL, 0x60FFFFFF);
                hovered = row.get(i);
            }
            String s = row.get(i);
            ctx.drawText(tr, s, cx + (CELL - tr.getWidth(s)) / 2, y + 2, 0xFFFFFFFF, false);
        }
        return hovered;
    }

    /** The emoji under the mouse in the open grid, or null. */
    private static String emojiAt(int width, int height, double mx, double my) {
        ChatEmoji e = ChatEmoji.active();
        if (e == null) return null;
        List<String> recents = e.recents();
        int[] p = panel(width, height, recents.isEmpty() ? 0 : 1);
        int x = p[0] + PAD, y = p[1] + PAD + HEADER;
        if (mx < x || mx >= x + COLS * CELL) return null;
        int col = (int) ((mx - x) / CELL);
        if (!recents.isEmpty()) {
            if (my >= y && my < y + CELL) return col < recents.size() ? recents.get(col) : null;
            y += CELL + 3;
        }
        if (my < y) return null;
        int index = (int) ((my - y) / CELL) * COLS + col;
        List<String> all = gridEmoji(e);
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    // ------------------------------------------------------------------ clicks

    /**
     * A left click in the chat screen: true when Evade's tabs, emoji button or grid took it. A picked emoji is
     * written into {@code field} at the cursor.
     */
    public static boolean click(TextRenderer tr, TextFieldWidget field, int width, int height, double mx, double my) {
        if (pickerShown()) {
            int[] b = button(width, height);
            if (mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3]) {
                pickerOpen = !pickerOpen;
                return true;
            }
            if (pickerOpen) {
                ChatEmoji e = ChatEmoji.active();
                int[] p = panel(width, height, e.recents().isEmpty() ? 0 : 1);
                if (mx >= p[0] && mx < p[2] && my >= p[1] && my < p[3]) {
                    String picked = emojiAt(width, height, mx, my);
                    if (picked != null && field != null && field.getText().length() + picked.length() <= 256) {
                        field.write(picked);
                        e.used(picked);
                    }
                    return true;
                }
                pickerOpen = false;
            }
        }
        ChatTabs tabs = ChatTabs.active();
        if (tabs != null) {
            for (Chip c : chips(tr, height)) {
                if (c.has(mx, my)) {
                    tabs.select(c.tab());
                    MinecraftClient.getInstance().getSoundManager().play(
                            net.minecraft.client.sound.PositionedSoundInstance.ui(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    return true;
                }
            }
        }
        return false;
    }

    /** Escape with the grid open closes just the grid. */
    public static boolean escape() {
        if (!pickerOpen) return false;
        pickerOpen = false;
        return true;
    }
}
