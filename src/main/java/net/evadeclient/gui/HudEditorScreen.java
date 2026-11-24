package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudLayout;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lay out the HUD. Drag widgets around with snapping (screen edges and centre, other widgets' edges and centres, an
 * optional grid) and guide lines; scroll to resize; right-click a widget for its settings, or empty space to add one.
 *
 * <p>Several widgets at once: Shift+click, drag a box on empty space, or Ctrl+A. A selection moves, resizes, nudges,
 * locks and hides together, and can be aligned and spaced out evenly from the toolbar. Arrow keys nudge (Shift: 10),
 * L locks, Delete hides, R resets; Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) undo and redo every change made here.
 * Layouts offers the built-in layouts and HUD-only share codes (Ctrl+C / Ctrl+V copy and paste one).
 */
public class HudEditorScreen extends Screen {
    private static final int SNAP = 4, GRID = 8, UNDO_LIMIT = 100, BTN_H = 16, BTN_GAP = 4, ROW_GAP = 4;
    /** Undo steps closer together than this on the same thing (scroll notches, held arrows) merge into one. */
    private static final long MERGE_MS = 700;
    private final Screen parent;

    /** The widgets picked (Shift+click, box or Ctrl+A); the first is the one picked first. */
    private final Set<HudElementModule> selection = new LinkedHashSet<>();
    /** The widget under the mouse when a drag started (it snaps; the rest of the selection follows). */
    private HudElementModule dragging;
    private final Map<HudElementModule, int[]> dragFrom = new HashMap<>();
    private double grabX, grabY;
    private String dragBefore;
    /** Box selection on empty space: where it started and where the mouse is (HUD units). */
    private boolean boxing;
    private double boxX0, boxY0, boxX1, boxY1;
    private Set<HudElementModule> boxBase = Set.of();
    private Integer guideX, guideY;
    /** Whether the widget being dragged is snapped to the vertical centre line (stored as centred). */
    private boolean centred;
    /** Mouse position in HUD units, from the last frame (for the keyboard shortcuts). */
    private double mouseHudX = -1, mouseHudY = -1;

    private final Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private String mergeKey;
    private long mergeAt;

    /** An open drop-down (Layouts, Add widget), or null. */
    private Popup popup;
    /** This frame's toolbar buttons (HUD units). */
    private final List<Btn> buttons = new ArrayList<>();

    /** "Back to Mods" hover cross-fade (0 = rest, 1 = hovered), eased like the menu's buttons (about 100 ms). */
    private float barHover;
    /** Clock time of the last frame that drew the bar (NaN after the screen was shown, so no big first step). */
    private double barFrameAt = Double.NaN;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("Edit HUD"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        barFrameAt = Double.NaN;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x50000000);
    }

    // The editor works in HUD units (see Hud.scale()), so elements line up with the in-game HUD at any GUI scale.
    private float k = 1f;
    private int realW, realH;

    private void enterHudSpace() {
        k = net.evadeclient.hud.Hud.scale();
        realW = width; realH = height;
        width = Math.round(realW / k); height = Math.round(realH / k);
    }

    private void leaveHudSpace() { width = realW; height = realH; }

    private Click hud(Click c) { return new Click(c.x() / k, c.y() / k, c.buttonInfo()); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        enterHudSpace();
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(k, k);
        try {
            mouseHudX = mouseX / k;
            mouseHudY = mouseY / k;
            renderHud(ctx, Math.round(mouseX / k), Math.round(mouseY / k), delta);
        } finally {
            ctx.getMatrices().popMatrix();
            leaveHudSpace();
        }
    }

    // ---- undo / redo ------------------------------------------------------------------------------------

    /** Record {@code before} as one undo step (nothing when it equals the layout now). */
    private void remember(String before) {
        if (before == null || before.equals(HudLayout.snapshot())) return;
        undo.push(before);
        while (undo.size() > UNDO_LIMIT) undo.removeLast();
        redo.clear();
        mergeKey = null;
    }

    /** Run {@code edit} as one undo step. */
    private void change(Runnable edit) {
        String before = HudLayout.snapshot();
        edit.run();
        remember(before);
    }

    /**
     * Run {@code edit} as an undo step that merges with the previous one when it is the same kind of change
     * ({@code key}) made just before (scroll notches, a held arrow key).
     */
    private void mergedChange(String key, Runnable edit) {
        long now = System.currentTimeMillis();
        boolean merge = key.equals(mergeKey) && now - mergeAt < MERGE_MS && !undo.isEmpty();
        String before = HudLayout.snapshot();
        edit.run();
        if (merge) {
            redo.clear();
            mergeAt = now;
            return;
        }
        int depth = undo.size();
        remember(before);
        // Only a step that was recorded can take the next ones in.
        mergeKey = undo.size() > depth ? key : null;
        mergeAt = now;
    }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.push(HudLayout.snapshot());
        HudLayout.restore(undo.pop());
        mergeKey = null;
        pruneSelection();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.push(HudLayout.snapshot());
        HudLayout.restore(redo.pop());
        mergeKey = null;
        pruneSelection();
    }

    /** Test hooks. */
    public int undoDepth() { return undo.size(); }
    public int redoDepth() { return redo.size(); }
    public Set<HudElementModule> selection() { return selection; }

    private void pruneSelection() { selection.removeIf(e -> !e.isEnabled()); }

    // ---- geometry helpers -------------------------------------------------------------------------------

    private int left(HudElementModule el) { return el.resolveX(width); }
    private int top(HudElementModule el) { return el.resolveY(height); }

    private boolean inside(HudElementModule el, double mx, double my) {
        int x = left(el), y = top(el);
        return mx >= x && mx <= x + el.scaledWidth() && my >= y && my <= y + el.scaledHeight();
    }

    private HudElementModule at(double mx, double my) {
        if (EvadeClient.modules == null) return null;
        var list = EvadeClient.modules.hudElements();
        for (int i = list.size() - 1; i >= 0; i--) {
            HudElementModule el = list.get(i);
            if (el.isEnabled() && inside(el, mx, my)) return el;
        }
        return null;
    }

    /** What the keys act on: the selection, else the widget under the mouse. */
    private List<HudElementModule> targets() {
        pruneSelection();
        if (!selection.isEmpty()) return new ArrayList<>(selection);
        HudElementModule el = at(mouseHudX, mouseHudY);
        return el == null ? List.of() : List.of(el);
    }

    /** Move {@code el}'s top-left to (x, y), keeping it centred when it was and only moves vertically. */
    private void place(HudElementModule el, int x, int y, boolean keepCentre) {
        el.moveTo(x, y, width, height, keepCentre);
    }

    // ---- the toolbar and hint strip ---------------------------------------------------------------------

    private record Btn(String id, String label, int x1, int y1, int x2, int y2, boolean enabled, boolean accent) {
        boolean hit(double mx, double my) { return mx >= x1 && mx < x2 && my >= y1 && my < y2; }
    }

    private String hint1() {
        return "Drag to move  •  Shift+click or drag a box to pick several  •  Scroll to resize  •  Right-click: settings, or add a widget";
    }

    private String hint2() {
        return "Arrows nudge (Shift: 10)  •  Ctrl+Z undo, Ctrl+Y redo  •  L lock  •  Del hide  •  R reset  •  G grid " + (grid() ? "on" : "off");
    }

    private String[] hintLines() {
        String one = hint1() + "  •  " + hint2();
        return textRenderer.getWidth(one) + 16 <= width ? new String[]{one} : new String[]{hint1(), hint2()};
    }

    private int btnW(String label) { return textRenderer.getWidth(label) + 14; }

    /** Labels of the main row, in order (ids and labels). */
    private List<String[]> mainRow() {
        List<String[]> row = new ArrayList<>();
        row.add(new String[]{"undo", "Undo"});
        row.add(new String[]{"redo", "Redo"});
        row.add(new String[]{"layouts", "Layouts"});
        row.add(new String[]{"add", "Add Widget"});
        row.add(new String[]{"back", "Back to Mods"});
        if (!selection.isEmpty()) {
            boolean allLocked = selection.stream().allMatch(e -> e.locked.get());
            row.add(new String[]{"lock", allLocked ? "Unlock" : "Lock"});
            row.add(new String[]{"hide", "Hide"});
        }
        return row;
    }

    private List<String[]> alignRow() {
        List<String[]> row = new ArrayList<>();
        if (selection.size() < 2) return row;
        for (String a : new String[]{"Left", "Center", "Right", "Top", "Middle", "Bottom"}) row.add(new String[]{"align:" + a, a});
        if (selection.size() >= 3) {
            row.add(new String[]{"space:h", "Space Across"});
            row.add(new String[]{"space:v", "Space Down"});
        }
        return row;
    }

    private int rowWidth(List<String[]> row) {
        int w = 0;
        for (String[] b : row) w += btnW(b[1]) + BTN_GAP;
        return Math.max(0, w - BTN_GAP);
    }

    /** Height of the whole strip: hint lines, the align row (when shown) and the main row. */
    private int stripHeight() {
        int h = hintLines().length * 10 + 6 + BTN_H;
        if (!alignRow().isEmpty()) h += BTN_H + ROW_GAP;
        return h;
    }

    /**
     * Top of the main button row. It sits above the hotbar, hearts and action-bar text (74 GUI pixels from the
     * bottom), or at the top of the screen while the widget under the mouse is down there.
     */
    private int barY(double mx, double my) {
        int bottom = Math.max(height / 2, height - Math.round(74 / k) - BTN_H);
        HudElementModule over = dragging == null && popup == null ? at(mx, my) : null;
        if (over != null) {
            int stripTop = bottom + BTN_H - stripHeight();
            if (top(over) + over.scaledHeight() > stripTop) return stripHeight() - BTN_H + 8;
        }
        return bottom;
    }

    /** Lays out this frame's buttons for a strip whose main row starts at {@code by}. */
    private void layoutButtons(int by) {
        buttons.clear();
        List<String[]> main = mainRow();
        int x = (width - rowWidth(main)) / 2;
        for (String[] b : main) {
            int w = btnW(b[1]);
            boolean enabled = switch (b[0]) {
                case "undo" -> !undo.isEmpty();
                case "redo" -> !redo.isEmpty();
                default -> true;
            };
            buttons.add(new Btn(b[0], b[1], x, by, x + w, by + BTN_H, enabled, b[0].equals("back")));
            x += w + BTN_GAP;
        }
        List<String[]> align = alignRow();
        if (!align.isEmpty()) {
            int ay = by - ROW_GAP - BTN_H;
            x = (width - rowWidth(align)) / 2;
            for (String[] b : align) {
                int w = btnW(b[1]);
                buttons.add(new Btn(b[0], b[1], x, ay, x + w, ay + BTN_H, true, false));
                x += w + BTN_GAP;
            }
        }
    }

    private Btn buttonAt(double mx, double my) {
        for (Btn b : buttons) if (b.hit(mx, my)) return b;
        return null;
    }

    // ---- drawing ----------------------------------------------------------------------------------------

    private void renderHud(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (grid()) {
            for (int gx = GRID; gx < width; gx += GRID) ctx.fill(gx, 0, gx + 1, height, gx % (GRID * 4) == 0 ? 0x14FFFFFF : 0x08FFFFFF);
            for (int gy = GRID; gy < height; gy += GRID) ctx.fill(0, gy, width, gy + 1, gy % (GRID * 4) == 0 ? 0x14FFFFFF : 0x08FFFFFF);
        }
        ctx.fill(width / 2, 0, width / 2 + 1, height, 0x18FFFFFF);
        ctx.fill(0, height / 2, width, height / 2 + 1, 0x18FFFFFF);

        int by = barY(mouseX, mouseY);
        layoutButtons(by);
        boolean overUi = popup != null || (dragging == null && !boxing && buttonAt(mouseX, mouseY) != null);

        HudElementModule hovered = null;
        if (client.player != null) {
            pruneSelection();
            for (HudElementModule el : EvadeClient.modules.hudElements()) {
                if (!el.isEnabled()) continue;
                el.renderScaled(ctx, width, height, true);
            }
            hovered = dragging != null ? dragging : overUi || boxing ? null : at(mouseX, mouseY);
            for (HudElementModule el : EvadeClient.modules.hudElements()) {
                if (!el.isEnabled()) continue;
                int x = left(el), y = top(el), w = el.scaledWidth(), h = el.scaledHeight();
                boolean sel = selection.contains(el) || (boxing && inBox(el));
                int color = el == hovered ? Theme.RED_BRIGHT : sel ? Theme.RED : 0x50FFFFFF;
                ctx.drawStrokedRectangle(x - 1, y - 1, w + 2, h + 2, color);
                if (el.locked.get()) lockGlyph(ctx, x + w - 6, y - 1, sel || el == hovered ? Theme.RED_BRIGHT : 0xC0FFFFFF);
            }
            if (hovered != null) {
                int x = left(hovered), y = top(hovered), h = hovered.scaledHeight();
                String label = hovered.getName() + "  " + Math.round(hovered.scale.get() * 100) + "%"
                        + (hovered.locked.get() ? "  (locked)" : "")
                        + (selection.size() > 1 && selection.contains(hovered) ? "  +" + (selection.size() - 1) : "");
                int lw = textRenderer.getWidth(label) + 6;
                int lx = Math.max(0, Math.min(x - 1, width - lw));
                int ly = y > 12 ? y - 11 : Math.min(height - 11, y + h + 2);
                Theme.shadow(ctx, Theme.SH_HUD, lx, ly - 1, lx + lw, ly + 10, Theme.alpha(0xFF000000, 0.5f));
                Theme.round(ctx, lx, ly - 1, lx + lw, ly + 10, 0xE0000000, Theme.R_HUD);
                ctx.drawText(textRenderer, label, lx + 3, ly + 1, Theme.RED_BRIGHT, false);
            }
        }
        if (guideX != null) ctx.fill(guideX, 0, guideX + 1, height, Theme.RED);
        if (guideY != null) ctx.fill(0, guideY, width, guideY + 1, Theme.RED);
        if (boxing) {
            int x1 = (int) Math.min(boxX0, boxX1), y1 = (int) Math.min(boxY0, boxY1);
            int x2 = (int) Math.max(boxX0, boxX1), y2 = (int) Math.max(boxY0, boxY1);
            ctx.fill(x1, y1, x2, y2, Theme.alpha(Theme.RED, 0.12f));
            ctx.drawStrokedRectangle(x1, y1, x2 - x1, y2 - y1, Theme.RED);
        }

        // The strip (hint and toolbar) gets out of the way while dragging.
        if (dragging == null && !boxing) renderStrip(ctx, mouseX, mouseY, by);
        if (popup != null) popup.render(ctx, mouseX, mouseY);
    }

    private void renderStrip(DrawContext ctx, int mouseX, int mouseY, int by) {
        double now = EvadeMenu.clock.getAsDouble();
        float dtSec = Double.isNaN(barFrameAt) ? 0f : (float) Math.max(0, Math.min(0.1, (now - barFrameAt) / 1000));
        barFrameAt = now;
        var cs = settings();
        boolean anims = cs == null || cs.animations.get();
        Btn hover = popup == null ? buttonAt(mouseX, mouseY) : null;
        float target = hover != null && hover.accent() ? 1f : 0f;
        barHover = anims ? barHover + (target - barHover) * (1 - (float) Math.exp(-22 * dtSec)) : target;
        for (Btn b : buttons) {
            boolean hv = b == hover && b.enabled();
            if (b.accent()) {
                Theme.round(ctx, b.x1(), b.y1(), b.x2(), b.y2(), Theme.lerpColor(Theme.RED, Theme.RED_BRIGHT, barHover));
            } else {
                Theme.roundBordered(ctx, b.x1(), b.y1(), b.x2(), b.y2(), hv ? Theme.CONTROL_HOVER : 0xE017171C,
                        hv ? Theme.BORDER_HOVER : Theme.BORDER);
            }
            int tc = b.accent() ? Theme.ON_ACCENT : !b.enabled() ? Theme.TEXT_FAINT : hv ? Theme.TEXT : Theme.TEXT_SOFT;
            ctx.drawText(textRenderer, b.label(), b.x1() + (b.x2() - b.x1() - textRenderer.getWidth(b.label())) / 2, b.y1() + 4, tc, false);
        }
        String[] lines = hintLines();
        int hw = 0;
        for (String l : lines) hw = Math.max(hw, textRenderer.getWidth(l));
        int rowsTop = alignRow().isEmpty() ? by : by - ROW_GAP - BTN_H;
        int hy = rowsTop - 6 - lines.length * 10;
        int hx = (width - hw) / 2;
        Theme.round(ctx, hx - 6, hy - 3, hx + hw + 6, hy + lines.length * 10 + 1, 0xC0101014);
        for (int i = 0; i < lines.length; i++)
            ctx.drawText(textRenderer, lines[i], (width - textRenderer.getWidth(lines[i])) / 2, hy + i * 10, Theme.TEXT_SOFT, false);
    }

    /** A tiny padlock with its top-left at (x, y). */
    private static void lockGlyph(DrawContext ctx, int x, int y, int color) {
        ctx.fill(x + 1, y - 3, x + 2, y, color);
        ctx.fill(x + 4, y - 3, x + 5, y, color);
        ctx.fill(x + 1, y - 4, x + 5, y - 3, color);
        ctx.fill(x, y, x + 6, y + 4, color);
    }

    // ---- drop-downs -------------------------------------------------------------------------------------

    private record Item(String label, Runnable action) {}

    /** A small list of choices; scrolls with the wheel when it doesn't fit. */
    private final class Popup {
        static final int ROW = 13, MAX_ROWS = 14;
        final String title;
        final List<Item> items;
        int x, y, w, scroll;

        Popup(String title, List<Item> items, double ax, double ay, boolean above) {
            this.title = title;
            this.items = items;
            int tw = textRenderer.getWidth(title) + 12;
            for (Item it : items) tw = Math.max(tw, textRenderer.getWidth(it.label()) + 16);
            w = tw;
            int h = height();
            x = (int) Math.max(4, Math.min(width - w - 4, ax));
            y = (int) (above ? ay - h - 4 : ay);
            y = Math.max(4, Math.min(height - h - 4, y));
        }

        int visible() { return Math.min(MAX_ROWS, items.size()); }
        int height() { return 14 + visible() * ROW + 4; }

        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + height(); }

        int indexAt(double mx, double my) {
            if (!contains(mx, my) || my < y + 14) return -1;
            int i = (int) ((my - y - 14) / ROW) + scroll;
            return i >= 0 && i < items.size() && i < scroll + visible() ? i : -1;
        }

        void render(DrawContext ctx, int mx, int my) {
            int h = height();
            Theme.shadow(ctx, Theme.SH_CARD, x, y, x + w, y + h, Theme.alpha(0xFF000000, 0.6f));
            Theme.roundBordered(ctx, x, y, x + w, y + h, Theme.BG | 0xFF000000, Theme.BORDER);
            ctx.drawText(textRenderer, title, x + 6, y + 4, Theme.TEXT_DIM, false);
            int hi = indexAt(mx, my);
            for (int r = 0; r < visible(); r++) {
                int i = r + scroll;
                Item it = items.get(i);
                int ry = y + 14 + r * ROW;
                boolean on = it.action() != null;
                if (i == hi && on) Theme.round(ctx, x + 2, ry, x + w - 2, ry + ROW, Theme.CONTROL_HOVER, Theme.R_XS);
                ctx.drawText(textRenderer, it.label(), x + 8, ry + 3, !on ? Theme.TEXT_FAINT : i == hi ? Theme.TEXT : Theme.TEXT_SOFT, false);
            }
            if (items.size() > visible()) {
                int track = visible() * ROW;
                int th = Math.max(8, track * visible() / items.size());
                int ty = y + 14 + (track - th) * scroll / Math.max(1, items.size() - visible());
                ctx.fill(x + w - 3, ty, x + w - 2, ty + th, Theme.TEXT_FAINT);
            }
        }

        void scroll(double v) {
            scroll = Math.max(0, Math.min(items.size() - visible(), scroll - (int) Math.signum(v)));
        }
    }

    private void openLayouts(double ax, double ay) {
        List<Item> items = new ArrayList<>();
        for (String p : HudLayout.PRESETS) items.add(new Item(p, () -> applyPreset(p)));
        items.add(new Item("Copy HUD Code", this::copyCode));
        items.add(new Item("Paste HUD Code", this::pasteCode));
        items.add(new Item("Reset All Positions", () -> change(() -> {
            for (HudElementModule el : EvadeClient.modules.hudElements()) { el.resetPosition(); el.scale.reset(); }
        })));
        popup = new Popup("Layouts", items, ax, ay, true);
    }

    private void openAddWidget(double ax, double ay, boolean above) {
        List<Item> items = new ArrayList<>();
        List<HudElementModule> off = new ArrayList<>();
        for (HudElementModule el : EvadeClient.modules.hudElements())
            if (!el.isEnabled() && HudLayout.ownsVisibility(el)) off.add(el);
        off.sort(Comparator.comparing(HudElementModule::getName, String.CASE_INSENSITIVE_ORDER));
        int px = (int) ax, py = (int) ay;
        for (HudElementModule el : off) items.add(new Item(el.getName(), () -> addWidget(el, above ? -1 : px, above ? -1 : py)));
        if (items.isEmpty()) items.add(new Item("Every widget is already on", null));
        popup = new Popup("Add widget", items, ax, ay, above);
    }

    /** Turn {@code el} on; with (x, y) >= 0, put it there (else at its default spot). Selects it. */
    public void addWidget(HudElementModule el, int x, int y) {
        change(() -> {
            el.setEnabled(true);
            if (x >= 0 && y >= 0) place(el, x, y, false);
        });
        selection.clear();
        selection.add(el);
    }

    public void applyPreset(String name) {
        change(() -> HudLayout.applyPreset(name));
        selection.clear();
        Notifications.push("HUD layout", name + " applied. Ctrl+Z puts yours back.", true);
    }

    private void copyCode() {
        String code = HudLayout.shareCode();
        client.keyboard.setClipboard(code);
        Notifications.push("HUD code copied", "Positions, sizes and which widgets show. Paste it in the HUD editor.", true);
    }

    private void pasteCode() {
        String text = client.keyboard.getClipboard();
        String before = HudLayout.snapshot();
        HudLayout.ImportResult r = HudLayout.importCode(text);
        if (!r.ok()) {
            Notifications.push("Couldn't paste HUD code", r.error(), false);
            return;
        }
        remember(before);
        selection.clear();
        Notifications.push("HUD code pasted", r.applied() + " widgets placed. Ctrl+Z puts yours back.", true);
    }

    // ---- actions on the selection -----------------------------------------------------------------------

    private void toggleLock(List<HudElementModule> els) {
        if (els.isEmpty()) return;
        boolean lock = !els.stream().allMatch(e -> e.locked.get());
        change(() -> { for (HudElementModule e : els) e.locked.set(lock); });
    }

    private void hide(List<HudElementModule> els) {
        if (els.isEmpty()) return;
        List<String> kept = new ArrayList<>();
        change(() -> {
            for (HudElementModule e : els) {
                if (HudLayout.ownsVisibility(e)) e.setEnabled(false);
                else kept.add(e.getName());
            }
        });
        selection.removeIf(e -> !e.isEnabled());
        if (!kept.isEmpty())
            Notifications.push("Not hidden", String.join(", ", kept) + ": turn it off in its own settings.", false);
    }

    /** Align the unlocked widgets of the selection: Left, Center, Right, Top, Middle or Bottom. */
    public void align(String how) {
        List<HudElementModule> els = movable();
        if (els.size() < 2) return;
        int minL = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE, minT = Integer.MAX_VALUE, maxB = Integer.MIN_VALUE;
        for (HudElementModule e : els) {
            minL = Math.min(minL, left(e)); maxR = Math.max(maxR, left(e) + e.scaledWidth());
            minT = Math.min(minT, top(e)); maxB = Math.max(maxB, top(e) + e.scaledHeight());
        }
        int fl = minL, fr = maxR, ft = minT, fb = maxB;
        change(() -> {
            for (HudElementModule e : els) {
                int x = left(e), y = top(e), w = e.scaledWidth(), h = e.scaledHeight();
                switch (how) {
                    case "Left" -> x = fl;
                    case "Right" -> x = fr - w;
                    case "Center" -> x = (fl + fr) / 2 - w / 2;
                    case "Top" -> y = ft;
                    case "Bottom" -> y = fb - h;
                    case "Middle" -> y = (ft + fb) / 2 - h / 2;
                    default -> { }
                }
                boolean vertical = how.equals("Top") || how.equals("Bottom") || how.equals("Middle");
                place(e, x, y, vertical && e.x == HudElementModule.CENTER);
            }
        });
    }

    /** Spread the unlocked widgets of the selection out evenly, across ({@code horizontal}) or down. */
    public void distribute(boolean horizontal) {
        List<HudElementModule> els = movable();
        if (els.size() < 3) return;
        els.sort(Comparator.comparingInt(e -> horizontal ? left(e) : top(e)));
        int first = horizontal ? left(els.getFirst()) : top(els.getFirst());
        HudElementModule lastEl = els.getLast();
        int end = horizontal ? left(lastEl) + lastEl.scaledWidth() : top(lastEl) + lastEl.scaledHeight();
        int sizes = 0;
        for (HudElementModule e : els) sizes += horizontal ? e.scaledWidth() : e.scaledHeight();
        float gap = (end - first - sizes) / (float) (els.size() - 1);
        change(() -> {
            float pos = first;
            for (HudElementModule e : els) {
                int p = Math.round(pos);
                if (horizontal) place(e, p, top(e), false);
                else place(e, left(e), p, e.x == HudElementModule.CENTER);
                pos += (horizontal ? e.scaledWidth() : e.scaledHeight()) + gap;
            }
        });
    }

    private List<HudElementModule> movable() {
        pruneSelection();
        List<HudElementModule> out = new ArrayList<>();
        for (HudElementModule e : selection) if (!e.locked.get()) out.add(e);
        return out;
    }

    private void runButton(Btn b) {
        if (!b.enabled()) return;
        switch (b.id()) {
            case "undo" -> undo();
            case "redo" -> redo();
            case "layouts" -> openLayouts(b.x1(), b.y1());
            case "add" -> openAddWidget(b.x1(), b.y1(), true);
            case "back" -> close();
            case "lock" -> toggleLock(new ArrayList<>(selection));
            case "hide" -> hide(new ArrayList<>(selection));
            case "space:h" -> distribute(true);
            case "space:v" -> distribute(false);
            default -> { if (b.id().startsWith("align:")) align(b.id().substring(6)); }
        }
    }

    // ---- mouse ------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        enterHudSpace();
        try { return clicked(hud(click), doubled); } finally { leaveHudSpace(); }
    }

    private boolean clicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int button = click.button();
        if (popup != null) {
            Popup p = popup;
            int i = p.indexAt(mx, my);
            if (p.contains(mx, my)) {
                if (i >= 0 && p.items.get(i).action() != null) {
                    popup = null;
                    p.items.get(i).action().run();
                }
                return true;
            }
            popup = null;
            return true;
        }
        layoutButtons(barY(mx, my));
        Btn b = buttonAt(mx, my);
        if (b != null && button == 0) { runButton(b); return true; }
        boolean shift = (click.buttonInfo().modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 || client.isShiftPressed();
        HudElementModule el = at(mx, my);
        if (el != null) {
            if (button == 1) {
                if (!selection.contains(el)) { selection.clear(); selection.add(el); }
                client.setScreen(EvadeMenu.open(el, this));
                return true;
            }
            if (button != 0) return true;
            if (shift) {
                if (!selection.remove(el)) selection.add(el);
                return true;
            }
            if (!selection.contains(el)) { selection.clear(); selection.add(el); }
            if (el.locked.get()) return true; // picked, but stays put
            dragging = el;
            dragBefore = HudLayout.snapshot();
            centred = el.x == HudElementModule.CENTER;
            grabX = mx;
            grabY = my;
            dragFrom.clear();
            for (HudElementModule s : selection) dragFrom.put(s, new int[]{left(s), top(s)});
            return true;
        }
        if (button == 1) { openAddWidget(mx, my, false); return true; }
        if (button == 0) {
            boxing = true;
            boxX0 = boxX1 = mx;
            boxY0 = boxY1 = my;
            boxBase = shift ? new LinkedHashSet<>(selection) : Set.of();
            if (!shift) selection.clear();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private boolean inBox(HudElementModule el) {
        double x1 = Math.min(boxX0, boxX1), x2 = Math.max(boxX0, boxX1), y1 = Math.min(boxY0, boxY1), y2 = Math.max(boxY0, boxY1);
        int l = left(el), t = top(el);
        return l < x2 && l + el.scaledWidth() > x1 && t < y2 && t + el.scaledHeight() > y1;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        enterHudSpace();
        try { return dragged(hud(click)); } finally { leaveHudSpace(); }
    }

    private boolean dragged(Click click) {
        if (boxing) {
            boxX1 = click.x();
            boxY1 = click.y();
            return true;
        }
        if (dragging == null) return false;
        int[] from = dragFrom.get(dragging);
        int x = (int) Math.round(from[0] + click.x() - grabX), y = (int) Math.round(from[1] + click.y() - grabY);
        int w = dragging.scaledWidth(), h = dragging.scaledHeight();
        guideX = guideY = null;
        centred = false;
        if (grid()) { // snap to the grid; the edge and element guides below still win when close
            x = Math.round(x / (float) GRID) * GRID;
            y = Math.round(y / (float) GRID) * GRID;
        }
        int[] sx = snapX(x, w), sy = snapY(y, h);
        x = sx[0];
        y = sy[0];
        boolean single = dragFrom.size() == 1;
        if (single) {
            dragging.moveTo(x, y, width, height, centred);
            return true;
        }
        int ddx = x - from[0], ddy = y - from[1];
        for (var e : dragFrom.entrySet()) {
            HudElementModule el = e.getKey();
            if (el.locked.get()) continue;
            el.moveTo(e.getValue()[0] + ddx, e.getValue()[1] + ddy, width, height, el == dragging && centred);
        }
        return true;
    }

    /** {x} snapped to the screen and other widgets' left edges, centres and right edges; sets the guide. */
    private int[] snapX(int x, int w) {
        int best = SNAP, out = x;
        int[] offsets = {0, w / 2, w};
        List<int[]> targets = new ArrayList<>(); // {target, isScreenCentre}
        targets.add(new int[]{0, 0});
        targets.add(new int[]{width / 2, 1});
        targets.add(new int[]{width, 0});
        for (HudElementModule o : EvadeClient.modules.hudElements()) {
            if (!o.isEnabled() || dragFrom.containsKey(o)) continue;
            int ol = left(o), ow = o.scaledWidth();
            targets.add(new int[]{ol, 0});
            targets.add(new int[]{ol + ow / 2, 0});
            targets.add(new int[]{ol + ow, 0});
        }
        for (int[] t : targets) {
            for (int i = 0; i < offsets.length; i++) {
                int d = Math.abs(x + offsets[i] - t[0]);
                if (d < best) {
                    best = d;
                    out = t[0] - offsets[i];
                    guideX = t[0];
                    centred = t[1] == 1 && i == 1;
                }
            }
        }
        return new int[]{out};
    }

    /** {y} snapped to the screen, other widgets' tops, middles and bottoms, and stacking 2 apart; sets the guide. */
    private int[] snapY(int y, int h) {
        int best = SNAP, out = y;
        int[] offsets = {0, h / 2, h};
        List<Integer> targets = new ArrayList<>(List.of(0, height / 2, height));
        List<int[]> stacks = new ArrayList<>(); // {top target, guide}
        for (HudElementModule o : EvadeClient.modules.hudElements()) {
            if (!o.isEnabled() || dragFrom.containsKey(o)) continue;
            int ot = top(o), oh = o.scaledHeight();
            targets.add(ot);
            targets.add(ot + oh / 2);
            targets.add(ot + oh);
            stacks.add(new int[]{ot + oh + 2, ot + oh + 2});
            stacks.add(new int[]{ot - 2 - h, ot - 2});
        }
        for (int t : targets) {
            for (int off : offsets) {
                int d = Math.abs(y + off - t);
                if (d < best) { best = d; out = t - off; guideY = t; }
            }
        }
        for (int[] s : stacks) {
            int d = Math.abs(y - s[0]);
            if (d < best) { best = d; out = s[0]; guideY = s[1]; }
        }
        return new int[]{out};
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (boxing) {
            boxing = false;
            enterHudSpace();
            try {
                selection.clear();
                selection.addAll(boxBase);
                for (HudElementModule el : EvadeClient.modules.hudElements()) if (el.isEnabled() && inBox(el)) selection.add(el);
            } finally {
                leaveHudSpace();
            }
        }
        if (dragging != null) remember(dragBefore);
        dragging = null;
        dragBefore = null;
        dragFrom.clear();
        guideX = guideY = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        enterHudSpace();
        try { return scrolled(mx / k, my / k, v); } finally { leaveHudSpace(); }
    }

    private boolean scrolled(double mx, double my, double v) {
        if (popup != null) { if (popup.contains(mx, my)) popup.scroll(v); return true; }
        HudElementModule el = at(mx, my);
        if (el == null) return false;
        List<HudElementModule> els = selection.contains(el) ? new ArrayList<>(selection) : List.of(el);
        mergedChange("scale", () -> {
            for (HudElementModule e : els) if (!e.locked.get()) e.scale.set(e.scale.get() + (v > 0 ? 0.05 : -0.05));
        });
        return true;
    }

    // ---- keys -------------------------------------------------------------------------------------------

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (popup != null) { popup = null; return true; }
            close();
            return true;
        }
        if (dragging != null || boxing) return true;
        enterHudSpace();
        try {
            boolean shiftDown = input.hasShift() || client.isShiftPressed();
            if (input.hasCtrlOrCmd() || client.isCtrlPressed()) {
                if (key == GLFW.GLFW_KEY_Z) { if (shiftDown) redo(); else undo(); return true; }
                if (key == GLFW.GLFW_KEY_C) { copyCode(); return true; }
                if (key == GLFW.GLFW_KEY_V) { pasteCode(); return true; }
                if (key == GLFW.GLFW_KEY_Y) { redo(); return true; }
                if (key == GLFW.GLFW_KEY_A) {
                    selection.clear();
                    for (HudElementModule el : EvadeClient.modules.hudElements()) if (el.isEnabled()) selection.add(el);
                    return true;
                }
            }
            if (input.isCopy()) { copyCode(); return true; }
            if (input.isPaste()) { pasteCode(); return true; }
            switch (key) {
                case GLFW.GLFW_KEY_G -> { settings().hudGrid.set(!grid()); return true; }
                case GLFW.GLFW_KEY_L -> { toggleLock(targets()); return true; }
                case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> { hide(targets()); return true; }
                case GLFW.GLFW_KEY_R -> {
                    List<HudElementModule> els = targets();
                    change(() -> { for (HudElementModule e : els) if (!e.locked.get()) e.resetPosition(); });
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                    List<HudElementModule> els = targets();
                    if (els.isEmpty()) return super.keyPressed(input);
                    if (selection.isEmpty()) selection.addAll(els);
                    int step = shiftDown ? 10 : 1;
                    int dx = key == GLFW.GLFW_KEY_LEFT ? -step : key == GLFW.GLFW_KEY_RIGHT ? step : 0;
                    int dy = key == GLFW.GLFW_KEY_UP ? -step : key == GLFW.GLFW_KEY_DOWN ? step : 0;
                    nudge(els, dx, dy);
                    return true;
                }
                default -> { }
            }
        } finally {
            leaveHudSpace();
        }
        return super.keyPressed(input);
    }

    /** Move widgets by (dx, dy) HUD units; a centred widget stays centred when only moved up or down. */
    private void nudge(List<HudElementModule> els, int dx, int dy) {
        mergedChange("nudge", () -> {
            for (HudElementModule el : els) {
                if (el.locked.get()) continue;
                boolean keepCentre = dx == 0 && el.x == HudElementModule.CENTER;
                el.moveTo(left(el) + dx, top(el) + dy, width, height, keepCentre);
            }
        });
    }

    @Override
    public void close() {
        EvadeClient.config.save();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }

    private static net.evadeclient.module.client.ClientSettings settings() {
        return EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
    }

    private static boolean grid() { return settings().hudGrid.get(); }
}
