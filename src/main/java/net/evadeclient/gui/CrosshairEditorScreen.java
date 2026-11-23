package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.mods.Crosshair;
import net.evadeclient.module.mods.CrosshairPattern;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Draw the Custom crosshair on a 15 x 15 grid: left-click (and drag) draws, right-click erases, Mirror paints all
 * four quarters at once. Presets, Clear, Invert, Undo/Redo, and share codes ({@code EVXH1:...}) to copy and paste.
 * Every change applies straight away (and switches the crosshair to the Custom style); Done or Escape saves.
 */
public class CrosshairEditorScreen extends Screen {
    private static final int N = CrosshairPattern.SIZE, C = CrosshairPattern.CENTER;
    private static final int PAD = 12, TITLE_H = 30, BODY_GAP = 10, FOOT_H = 32, COL_W = 148;
    private static final int HEADER = 13, PREVIEW_H = 40, SECTION_GAP = 10, BTN_H = 16, GAP = 4, UNDO_LIMIT = 100;
    /** Preview backdrops: open sky and a dark cave, so both bright and dark crosshairs can be judged. */
    private static final int SKY = 0xFF8FB3DE, CAVE = 0xFF1E1F24;

    private final Screen parent;
    private final Crosshair crosshair;
    private final CrosshairPattern work;
    private final Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private boolean mirror;

    // the stroke being drawn: which button, what it paints, the last cell it reached, the pattern before it
    private int strokeButton = -1;
    private boolean strokeValue;
    private int lastX, lastY;
    private String strokeBefore;

    // layout, worked out in init()
    private int wx, wy, ww, wh, cell, gx, gy, colX, colY, tb, ts, ph;
    private final List<Button> buttons = new ArrayList<>();
    private record Button(String id, int x1, int y1, int x2, int y2) {
        boolean contains(double mx, double my) { return mx >= x1 && mx < x2 && my >= y1 && my < y2; }
    }

    // preview shape, rebuilt when the pattern (or the crosshair's Outline setting) changes
    private Crosshair.Shape preview;
    private String previewFor;

    /** Each preset's cells as rectangles at one pixel per cell (scaled when drawn). */
    private static final int[][] PRESET_RECTS = new int[CrosshairPattern.PRESETS.size()][];
    static {
        for (int i = 0; i < PRESET_RECTS.length; i++)
            PRESET_RECTS[i] = CrosshairPattern.rects(CrosshairPattern.PRESETS.get(i).pattern().mask(), N, N);
    }

    public CrosshairEditorScreen(Screen parent, Crosshair crosshair) {
        super(Text.literal("Crosshair Editor"));
        this.parent = parent;
        this.crosshair = crosshair;
        this.work = crosshair.pattern.pattern().copy();
    }

    private static ClientSettings settings() {
        return EvadeClient.modules.get(ClientSettings.class);
    }

    private static boolean smooth() {
        ClientSettings s = settings();
        return s != null && s.smoothFont.get();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        ClientSettings s = settings();
        if (s != null) s.applyTheme();
        int availW = width - 16, availH = height - 16;
        int bodyMax = availH - TITLE_H - BODY_GAP - FOOT_H;
        // Presets at two pixels per cell when there's room, else at actual size; on a very short screen the
        // preview gets lower too.
        tb = (COL_W - 3 * GAP) / 4;
        ts = 2;
        ph = PREVIEW_H;
        if (columnHeight(tb, ph) > bodyMax) { tb = N + 6; ts = 1; }
        if (columnHeight(tb, ph) > bodyMax) ph = Math.max(N + 9, ph - (columnHeight(tb, ph) - bodyMax));
        int colH = columnHeight(tb, ph);
        // The biggest whole-pixel cells (up to 12) that fit beside the column and above the footer.
        cell = Math.max(4, Math.min(12, Math.min(bodyMax / N, (availW - 3 * PAD - COL_W) / N)));
        int grid = cell * N;
        int bodyH = Math.max(grid, colH);
        ww = PAD + grid + PAD + COL_W + PAD;
        wh = TITLE_H + bodyH + BODY_GAP + FOOT_H;
        wx = (width - ww) / 2;
        wy = Math.max(4, (height - wh) / 2);
        gx = wx + PAD;
        gy = wy + TITLE_H + (bodyH - grid) / 2;
        colX = gx + grid + PAD;
        colY = wy + TITLE_H;

        buttons.clear();
        int y = toolsY();
        int bw = (COL_W - GAP) / 2;
        buttons.add(new Button("clear", colX, y, colX + bw, y + BTN_H));
        buttons.add(new Button("invert", colX + COL_W - bw, y, colX + COL_W, y + BTN_H));
        y += BTN_H + GAP;
        buttons.add(new Button("mirror", colX, y, colX + bw, y + BTN_H));
        buttons.add(new Button("undo", colX + COL_W - bw, y, colX + COL_W, y + BTN_H));
        int fy = footTop() + (FOOT_H - 18) / 2 + 1, right = wx + ww - PAD;
        int doneW = 52, pasteW = 66, copyW = 64;
        buttons.add(new Button("done", right - doneW, fy, right, fy + 18));
        right -= doneW + 6;
        buttons.add(new Button("paste", right - pasteW, fy, right, fy + 18));
        right -= pasteW + 6;
        buttons.add(new Button("copy", right - copyW, fy, right, fy + 18));
    }

    private static int columnHeight(int tb, int ph) {
        return HEADER + ph + SECTION_GAP + HEADER + 2 * tb + GAP + SECTION_GAP + 2 * BTN_H + GAP;
    }

    private int footTop() { return wy + wh - FOOT_H; }
    private int previewY() { return colY + HEADER; }
    private int presetsY() { return previewY() + ph + SECTION_GAP + HEADER; }
    private int toolsY() { return presetsY() + 2 * tb + GAP + SECTION_GAP; }

    private int presetAt(double mx, double my) {
        int py = presetsY();
        for (int i = 0; i < CrosshairPattern.PRESETS.size(); i++) {
            int x = colX + (i % 4) * (tb + GAP), y = py + (i / 4) * (tb + GAP);
            if (mx >= x && mx < x + tb && my >= y && my < y + tb) return i;
        }
        return -1;
    }

    /** The grid cell under the mouse as {x, y}, or null outside the grid. */
    private int[] cellAt(double mx, double my) {
        int x = (int) Math.floor((mx - gx) / cell), y = (int) Math.floor((my - gy) / cell);
        return CrosshairPattern.inside(x, y) ? new int[]{x, y} : null;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ClientSettings s = settings();
        if (s != null && s.blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0x90000000, 0xB0000000);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        boolean sm = smooth();
        // window: the shadow's middle is filled too, so the slightly see-through body shows no frame where its
        // edge slices end (as the menu does)
        Theme.Shadow sh = Theme.SH_WINDOW;
        Theme.shadow(ctx, sh, wx, wy, wx + ww, wy + wh, 0x90000000);
        int in = sh.slice() - sh.pad();
        Theme.fill(ctx, wx + in, wy + in, wx + ww - in, wy + wh - in, 0x90000000);
        Theme.round(ctx, wx, wy, wx + ww, wy + wh, Theme.BG, Theme.R_WIN);
        Theme.stroke(ctx, wx, wy, wx + ww, wy + wh, Theme.BORDER, Theme.R_WIN);
        UiFont.draw(ctx, textRenderer, "Crosshair Editor", wx + PAD, wy + 10, Theme.TEXT, Role.TITLE, sm);
        // Top right: the pixel count, or why the game still shows another crosshair.
        int n = work.count();
        boolean off = !crosshair.isEnabled();
        String status = off ? "Crosshair is off" : n + (n == 1 ? " pixel" : " pixels");
        UiFont.draw(ctx, textRenderer, status, wx + ww - PAD - UiFont.width(textRenderer, status, Role.LABEL, sm), wy + 13,
                off ? Theme.WARN : Theme.TEXT_DIM, Role.LABEL, sm);

        renderGrid(ctx, mouseX, mouseY);
        renderColumn(ctx, mouseX, mouseY, sm);
        renderFooter(ctx, mouseX, mouseY, sm);
    }

    private void renderGrid(DrawContext ctx, int mx, int my) {
        // Cells leave a 1 px line on their right and bottom (from 6 px up), so the frame is one pixel short there.
        int line = cell >= 6 ? 1 : 0, end = cell * N - line;
        Theme.round(ctx, gx - 3, gy - 3, gx + end + 3, gy + end + 3, Theme.INPUT, Theme.R);
        Theme.stroke(ctx, gx - 3, gy - 3, gx + end + 3, gy + end + 3, Theme.BORDER, Theme.R);
        int on = paintColor();
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int x1 = gx + x * cell, y1 = gy + y * cell;
                int c;
                if (work.get(x, y)) c = on;
                else if (x == C && y == C) c = 0xFF2A2A33;
                else if (x == C || y == C) c = 0xFF1D1D24;
                else c = (x + y) % 2 == 0 ? 0xFF17171C : 0xFF141418;
                ctx.fill(x1, y1, x1 + cell - line, y1 + cell - line, c);
            }
        }
        // The middle of the screen: a small accent mark on the centre cell.
        if (!work.get(C, C)) {
            int m = Math.max(1, cell / 4), x1 = gx + C * cell + (cell - line - m) / 2, y1 = gy + C * cell + (cell - line - m) / 2;
            ctx.fill(x1, y1, x1 + m, y1 + m, Theme.alpha(Theme.RED, 0.8f));
        }
        // Where the next click lands (and its mirror images).
        int[] h = strokeButton < 0 ? cellAt(mx, my) : null;
        if (h != null) {
            for (int[] p : targets(h[0], h[1])) {
                ctx.drawStrokedRectangle(gx + p[0] * cell - 1, gy + p[1] * cell - 1, cell + 1, cell + 1, Theme.RED_BRIGHT);
            }
        }
    }

    /** Painted cells in the crosshair's colour, or a light grey when that colour would vanish on the dark grid. */
    private int paintColor() {
        int c = crosshair.color() | 0xFF000000;
        return Theme.relLum(c) < 0.06f ? 0xFFB8B8C0 : c;
    }

    private void renderColumn(DrawContext ctx, int mx, int my, boolean sm) {
        // preview, at actual size on a light and a dark backdrop
        caps(ctx, "Preview", colX, colY + 2, sm);
        int py = previewY(), half = (COL_W - GAP) / 2;
        Crosshair.Shape shape = previewShape();
        int color = crosshair.color();
        Theme.round(ctx, colX, py, colX + half, py + ph, SKY, Theme.R);
        Theme.round(ctx, colX + COL_W - half, py, colX + COL_W, py + ph, CAVE, Theme.R);
        shape.draw(ctx, colX + half / 2, py + ph / 2, color);
        shape.draw(ctx, colX + COL_W - half + half / 2, py + ph / 2, color);

        // presets
        int hover = presetAt(mx, my);
        int sy = presetsY();
        caps(ctx, "Presets", colX, sy - HEADER + 2, sm);
        if (hover >= 0) {
            String name = CrosshairPattern.PRESETS.get(hover).name();
            UiFont.draw(ctx, textRenderer, name, colX + COL_W - UiFont.width(textRenderer, name, Role.SMALL, sm), sy - HEADER + 2, Theme.TEXT_SOFT, Role.SMALL, sm);
        }
        for (int i = 0; i < CrosshairPattern.PRESETS.size(); i++) {
            int x = colX + (i % 4) * (tb + GAP), y = sy + (i / 4) * (tb + GAP);
            boolean current = CrosshairPattern.PRESETS.get(i).pattern().equals(work);
            Theme.round(ctx, x, y, x + tb, y + tb, i == hover ? Theme.CONTROL_HOVER : Theme.CONTROL, Theme.R);
            Theme.stroke(ctx, x, y, x + tb, y + tb, current ? Theme.RED : i == hover ? Theme.BORDER_HOVER : Theme.BORDER, Theme.R);
            int ox = x + (tb - N * ts) / 2, oy = y + (tb - N * ts) / 2;
            int[] r = PRESET_RECTS[i];
            int c = current || i == hover ? Theme.TEXT : Theme.TEXT_SOFT;
            for (int k = 0; k < r.length; k += 4)
                ctx.fill(ox + r[k] * ts, oy + r[k + 1] * ts, ox + r[k + 2] * ts, oy + r[k + 3] * ts, c);
        }

        // tools
        caps(ctx, "Tools", colX, toolsY() - HEADER + 2, sm);
        for (Button b : buttons) {
            switch (b.id()) {
                case "clear" -> button(ctx, b, "Clear", false, !work.isEmpty(), mx, my, sm);
                case "invert" -> button(ctx, b, "Invert", false, true, mx, my, sm);
                case "mirror" -> button(ctx, b, mirror ? "Mirror: On" : "Mirror: Off", mirror, true, mx, my, sm);
                case "undo" -> button(ctx, b, "Undo", false, !undo.isEmpty(), mx, my, sm);
                default -> { }
            }
        }
    }

    private void renderFooter(DrawContext ctx, int mx, int my, boolean sm) {
        int fy = footTop();
        Theme.fill(ctx, wx + 1, fy, wx + ww - 1, fy + 1, Theme.DIVIDER);
        int left = wx + PAD, limit = Integer.MAX_VALUE;
        for (Button b : buttons) {
            switch (b.id()) {
                case "copy" -> { button(ctx, b, "Copy Code", false, true, mx, my, sm); limit = Math.min(limit, b.x1()); }
                case "paste" -> { button(ctx, b, "Paste Code", false, true, mx, my, sm); limit = Math.min(limit, b.x1()); }
                case "done" -> primary(ctx, b, "Done", mx, my, sm);
                default -> { }
            }
        }
        // Controls, when they fit beside the buttons.
        for (String hint : new String[]{"Left-click draws, right-click erases  •  Ctrl+Z undo", "Left draws, right erases"}) {
            if (left + UiFont.width(textRenderer, hint, Role.SMALL, sm) <= limit - 8) {
                UiFont.draw(ctx, textRenderer, hint, left, fy + (FOOT_H - 6) / 2 + 1, Theme.TEXT_FAINT, Role.SMALL, sm);
                break;
            }
        }
    }

    private void caps(DrawContext ctx, String s, int x, int y, boolean sm) {
        UiFont.caps(ctx, textRenderer, s, x, y, Theme.TEXT_HEADER, Role.CAPS, 1f, sm);
    }

    /** A secondary button; {@code on} lights it in the accent (a toggle that is on), a disabled one is dimmed. */
    private void button(DrawContext ctx, Button b, String label, boolean on, boolean enabled, int mx, int my, boolean sm) {
        boolean hv = enabled && b.contains(mx, my);
        int bg = on ? Theme.alpha(Theme.RED, hv ? 0.32f : 0.22f) : hv ? Theme.CONTROL_HOVER : Theme.CONTROL;
        int border = on || hv ? Theme.RED : Theme.BORDER;
        Theme.roundBordered(ctx, b.x1(), b.y1(), b.x2(), b.y2(), bg, border);
        int tc = !enabled ? Theme.TEXT_FAINT : on ? Theme.RED_BRIGHT : hv ? Theme.TEXT : Theme.TEXT_SOFT;
        int w = UiFont.width(textRenderer, label, Role.LABEL, sm);
        UiFont.draw(ctx, textRenderer, label, b.x1() + (b.x2() - b.x1() - w) / 2, b.y1() + (b.y2() - b.y1() - 7) / 2, tc, Role.LABEL, sm);
    }

    /** The solid accent button (Done). */
    private void primary(DrawContext ctx, Button b, String label, int mx, int my, boolean sm) {
        int bg = b.contains(mx, my) ? Theme.RED_BRIGHT : Theme.RED;
        Theme.roundBordered(ctx, b.x1(), b.y1(), b.x2(), b.y2(), bg, bg);
        int w = UiFont.width(textRenderer, label, Role.STRONG, sm);
        UiFont.draw(ctx, textRenderer, label, b.x1() + (b.x2() - b.x1() - w) / 2, b.y1() + 5, Theme.ON_ACCENT, Role.STRONG, sm);
    }

    private Crosshair.Shape previewShape() {
        String key = work.encode() + crosshair.outlined();
        if (preview == null || !key.equals(previewFor)) {
            preview = crosshair.customShape(work);
            previewFor = key;
        }
        return preview;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int button = click.button();
        int[] c = cellAt(mx, my);
        if (c != null && (button == 0 || button == 1)) {
            strokeButton = button;
            strokeValue = button == 0 && !work.get(c[0], c[1]);
            strokeBefore = work.encode();
            lastX = c[0];
            lastY = c[1];
            paint(c[0], c[1]);
            return true;
        }
        if (button != 0) return super.mouseClicked(click, doubled);
        int preset = presetAt(mx, my);
        if (preset >= 0) {
            change(() -> work.load(CrosshairPattern.PRESETS.get(preset).pattern()));
            return true;
        }
        for (Button b : buttons) {
            if (!b.contains(mx, my)) continue;
            switch (b.id()) {
                case "clear" -> change(work::clear);
                case "invert" -> change(work::invert);
                case "mirror" -> mirror = !mirror;
                case "undo" -> undo();
                case "copy" -> copyCode();
                case "paste" -> pasteCode();
                case "done" -> close();
                default -> { }
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (strokeButton < 0) return super.mouseDragged(click, dx, dy);
        int x = (int) Math.floor((click.x() - gx) / cell), y = (int) Math.floor((click.y() - gy) / cell);
        // Every cell on the way from the last one, so a quick drag leaves no gaps (cells off the grid are skipped).
        int x0 = lastX, y0 = lastY, ddx = Math.abs(x - x0), ddy = -Math.abs(y - y0);
        int sx = x0 < x ? 1 : -1, sy = y0 < y ? 1 : -1, err = ddx + ddy;
        for (int guard = 0; guard < 4 * N * N; guard++) {
            paint(x0, y0);
            if (x0 == x && y0 == y) break;
            int e2 = 2 * err;
            if (e2 >= ddy) { err += ddy; x0 += sx; }
            if (e2 <= ddx) { err += ddx; y0 += sy; }
        }
        lastX = x;
        lastY = y;
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (strokeButton >= 0 && click.button() == strokeButton) {
            strokeButton = -1;
            if (strokeBefore != null && !strokeBefore.equals(work.encode())) remember(strokeBefore);
            strokeBefore = null;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { close(); return true; }
        if (input.isCopy()) { copyCode(); return true; }
        if (input.isPaste()) { pasteCode(); return true; }
        if (input.hasCtrlOrCmd()) {
            if (key == GLFW.GLFW_KEY_Z) { if (input.hasShift()) redo(); else undo(); return true; }
            if (key == GLFW.GLFW_KEY_Y) { redo(); return true; }
        }
        if (strokeButton < 0) {
            switch (key) {
                case GLFW.GLFW_KEY_M -> { mirror = !mirror; return true; }
                case GLFW.GLFW_KEY_I -> { change(work::invert); return true; }
                case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> { change(work::clear); return true; }
                default -> { }
            }
        }
        return super.keyPressed(input);
    }

    /** The cell and, with Mirror on, its images in the other three quarters. */
    private List<int[]> targets(int x, int y) {
        List<int[]> out = new ArrayList<>(4);
        out.add(new int[]{x, y});
        if (mirror) {
            int mx = N - 1 - x, my = N - 1 - y;
            if (mx != x) out.add(new int[]{mx, y});
            if (my != y) out.add(new int[]{x, my});
            if (mx != x && my != y) out.add(new int[]{mx, my});
        }
        return out;
    }

    private void paint(int x, int y) {
        if (!CrosshairPattern.inside(x, y)) return;
        boolean changed = false;
        for (int[] p : targets(x, y)) {
            if (work.get(p[0], p[1]) != strokeValue) {
                work.set(p[0], p[1], strokeValue);
                changed = true;
            }
        }
        if (changed) apply();
    }

    /** Run an edit as one undo step; nothing is recorded if it changes nothing. */
    private void change(Runnable edit) {
        String before = work.encode();
        edit.run();
        if (before.equals(work.encode())) return;
        remember(before);
        apply();
    }

    private void remember(String before) {
        undo.push(before);
        while (undo.size() > UNDO_LIMIT) undo.removeLast();
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(work.encode());
        restore(undo.pop());
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(work.encode());
        restore(redo.pop());
    }

    private void restore(String code) {
        CrosshairPattern p = CrosshairPattern.decode(code);
        if (p == null) return;
        work.load(p);
        apply();
    }

    /** Put the pattern on the crosshair now, switching it to the Custom style the first time. */
    private void apply() {
        crosshair.pattern.set(work.encode());
        if (crosshair.useCustomStyle()) Notifications.push("Crosshair", "Style set to Custom", true);
    }

    private void copyCode() {
        if (client == null) return;
        client.keyboard.setClipboard(work.shareCode());
        Notifications.push("Crosshair", "Code copied", true);
    }

    private void pasteCode() {
        if (client == null) return;
        CrosshairPattern p = CrosshairPattern.parse(client.keyboard.getClipboard());
        if (p == null) {
            Notifications.push("Nothing to paste", "Copy a crosshair code (" + CrosshairPattern.PREFIX + "...) first", false);
            return;
        }
        change(() -> work.load(p));
        Notifications.push("Crosshair", "Code pasted", true);
    }

    @Override
    public void close() {
        if (strokeBefore != null && !strokeBefore.equals(work.encode())) remember(strokeBefore);
        strokeButton = -1;
        strokeBefore = null;
        if (EvadeClient.config != null) EvadeClient.config.save();
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
