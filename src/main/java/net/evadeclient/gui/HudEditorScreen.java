package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudLayout;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
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
 * locks and hides together, and can be aligned and spaced out evenly from the toolbar. Stack groups the selection
 * (see {@link net.evadeclient.hud.HudStacks}): stacked widgets move together and sit one under another; Unstack parts them. Arrow keys nudge (Shift: 10),
 * L locks, Delete hides, R resets; Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) undo and redo every change made here.
 * Layouts offers the built-in layouts and HUD-only share codes (Ctrl+C / Ctrl+V copy and paste one).
 */
public class HudEditorScreen extends Screen {
    private static final int SNAP = 4, GRID = 8, UNDO_LIMIT = 100, BTN_H = 16;
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

    /** The open popover (Add widget, Layouts), the shortcut sheet, or none. */
    private enum Pop { NONE, ADD, LAYOUTS, HELP }
    private Pop pop = Pop.NONE;
    /** Where the open popover is anchored (GUI px) and whether it opens above that point. */
    private int popX, popY;
    private boolean popAbove;
    /** Where an Add widget picked from a right-click lands (HUD units), or -1 for the widget's own spot. */
    private int addAtX = -1, addAtY = -1;
    private float addScroll;
    private long popAt, openedAt;

    /** The kit draws the editor's chrome (dock, popovers, the shortcut sheet) in GUI px, over the HUD. */
    private final net.evadeclient.gui.kit.Kit kit = new net.evadeclient.gui.kit.Kit();
    /** This frame's dock and popover rectangles in GUI px (the widgets under them aren't picked). */
    private int dockX1, dockY1, dockX2, dockY2, popX1, popY1, popX2, popY2;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("Edit HUD"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        if (openedAt == 0) openedAt = System.currentTimeMillis();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // The live game, dimmed a quarter so the outlines and the dock read; a touch more at the bottom.
        net.evadeclient.gui.kit.Paint.vgradient(ctx, 0, 0, width, height, 0, 0x38000000, 0x52000000);
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
        renderChrome(ctx, mouseX, mouseY);
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

    // ---- drawing: the HUD pass (HUD units) ---------------------------------------------------------------

    /** What the chrome pass draws over the HUD: outlines, handles and the readout, in GUI px. */
    private HudElementModule hoveredEl;

    private void renderHud(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        boolean moving = dragging != null || boxing;
        // Grid: dots every 8 while dragging (lines would fight the HUD); the centre lines always, faintly.
        if (grid() && moving) {
            for (int gx = GRID; gx < width; gx += GRID)
                for (int gy = GRID; gy < height; gy += GRID)
                    ctx.fill(gx, gy, gx + 1, gy + 1, (gx % (GRID * 4) == 0 && gy % (GRID * 4) == 0) ? 0x40FFFFFF : 0x1CFFFFFF);
        } else if (grid()) {
            for (int gx = GRID * 4; gx < width; gx += GRID * 4) ctx.fill(gx, 0, gx + 1, height, 0x0CFFFFFF);
            for (int gy = GRID * 4; gy < height; gy += GRID * 4) ctx.fill(0, gy, width, gy + 1, 0x0CFFFFFF);
        }
        ctx.fill(width / 2, 0, width / 2 + 1, height, 0x14FFFFFF);
        ctx.fill(0, height / 2, width, height / 2 + 1, 0x14FFFFFF);

        boolean overUi = pop != Pop.NONE || (!moving && overChrome(mouseX * k, mouseY * k));
        hoveredEl = null;
        if (client.player != null) {
            pruneSelection();
            net.evadeclient.hud.HudStacks.apply(width, height, true);
            for (HudElementModule el : EvadeClient.modules.hudElements()) {
                if (!el.isEnabled()) continue;
                el.renderScaled(ctx, width, height, true);
            }
            hoveredEl = dragging != null ? dragging : overUi || boxing ? null : at(mouseX, mouseY);
        }
    }

    private boolean overChrome(double gx, double gy) {
        return (gx >= dockX1 && gx < dockX2 && gy >= dockY1 && gy < dockY2)
                || (pop != Pop.NONE && gx >= popX1 && gx < popX2 && gy >= popY1 && gy < popY2);
    }

    // ---- drawing: the chrome pass (GUI px) ---------------------------------------------------------------

    private void renderChrome(DrawContext ctx, int mx, int my) {
        kit.begin(ctx, mx, my);
        float f = Theme.fade;
        float in = Motion.level() == Motion.Level.OFF ? 1f : Motion.clamp01((System.currentTimeMillis() - openedAt) / (float) Motion.DUR_BASE);
        Theme.fade = f * in;
        try {
            if (client.player != null) {
                drawStacks(ctx);
                drawOutlines(ctx);
            }
            float kk = k;
            if (guideX != null) guide(ctx, guideX * kk, true);
            if (guideY != null) guide(ctx, guideY * kk, false);
            if (boxing) {
                float x1 = (float) Math.min(boxX0, boxX1) * kk, y1 = (float) Math.min(boxY0, boxY1) * kk;
                float x2 = (float) Math.max(boxX0, boxX1) * kk, y2 = (float) Math.max(boxY0, boxY1) * kk;
                net.evadeclient.gui.kit.Paint.rect(ctx, x1, y1, x2, y2, 2, Theme.alpha(Tokens.A500, 0.10f));
                net.evadeclient.gui.kit.Paint.ring(ctx, x1, y1, x2, y2, 2, Theme.alpha(Tokens.A400, 0.9f), 1);
            }
            // The dock gets out of the way while dragging.
            if (dragging == null && !boxing) drawDock(ctx, mx, my);
            else dockX1 = dockY1 = dockX2 = dockY2 = 0;
            drawPopover(ctx, mx, my);
            kit.end();
        } finally {
            Theme.fade = f;
        }
    }

    /** A snapping guide: a 1 device px accent line across the screen at {@code at} GUI px. */
    private void guide(DrawContext ctx, float at, boolean vertical) {
        float t = 1f / Theme.deviceScale(ctx);
        int c = Theme.alpha(Tokens.A400, 0.9f);
        if (vertical) net.evadeclient.gui.kit.Paint.rect(ctx, at, 0, at + t, realOr(height, true), 0, c);
        else net.evadeclient.gui.kit.Paint.rect(ctx, 0, at, realOr(width, false), at + t, 0, c);
    }

    private float realOr(int v, boolean h) { return h ? this.height : this.width; }

    /** Outlines for every widget (faint), the hovered one (white) and the picked ones (accent, with handles), the
     *  lock glyph, and a readout chip over the hovered or dragged widget. */
    private void drawOutlines(DrawContext ctx) {
        float kk = k;
        int sw = this.width, sh = this.height;
        int hudW = Math.round(sw / kk), hudH = Math.round(sh / kk);
        HudElementModule readout = hoveredEl != null ? hoveredEl : selection.size() == 1 ? selection.iterator().next() : null;
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            if (!el.isEnabled()) continue;
            float x1 = el.resolveX(hudW) * kk, y1 = el.resolveY(hudH) * kk;
            float x2 = x1 + el.scaledWidth() * kk, y2 = y1 + el.scaledHeight() * kk;
            boolean sel = selection.contains(el) || (boxing && inBoxGui(el, hudW, hudH));
            boolean hov = el == hoveredEl;
            float o = 1.5f;
            if (sel) {
                net.evadeclient.gui.kit.Paint.ring(ctx, x1 - o, y1 - o, x2 + o, y2 + o, 1.5f, Tokens.A400, 1);
                handles(ctx, x1 - o, y1 - o, x2 + o, y2 + o);
            } else if (hov) {
                net.evadeclient.gui.kit.Paint.ring(ctx, x1 - o, y1 - o, x2 + o, y2 + o, 1.5f, Theme.alpha(0xFFFFFFFF, 0.75f), 1);
            } else {
                net.evadeclient.gui.kit.Paint.ring(ctx, x1 - o, y1 - o, x2 + o, y2 + o, 1.5f, Theme.alpha(0xFFFFFFFF, 0.16f), 1);
            }
            if (el.locked.get()) {
                float lx = x2 - 4, ly = y1 - 6;
                net.evadeclient.gui.kit.Paint.circle(ctx, lx, ly, 11, Tokens.N300);
                PanelGlyphs.draw(ctx, "lock", lx - 4, ly - 4, 8, sel || hov ? Tokens.A400 : Tokens.N900);
            }
        }
        if (readout != null && readout.isEnabled()) readoutChip(ctx, readout, hudW, hudH);
    }

    private boolean inBoxGui(HudElementModule el, int hudW, int hudH) {
        double x1 = Math.min(boxX0, boxX1), x2 = Math.max(boxX0, boxX1), y1 = Math.min(boxY0, boxY1), y2 = Math.max(boxY0, boxY1);
        int l = el.resolveX(hudW), t = el.resolveY(hudH);
        return l < x2 && l + el.scaledWidth() > x1 && t < y2 && t + el.scaledHeight() > y1;
    }

    /** Four white corner handles with a soft contact shadow. */
    private void handles(DrawContext ctx, float x1, float y1, float x2, float y2) {
        float s = 5;
        float[][] c = {{x1, y1}, {x2, y1}, {x1, y2}, {x2, y2}};
        for (float[] p : c) {
            if (net.evadeclient.gui.kit.Paint.gpu())
                net.evadeclient.render.ui.UiShape.shape(p[0] - s / 2, p[1] - s / 2, p[0] + s / 2, p[1] + s / 2).radius(1.2f)
                        .fill(Theme.alpha(0xFF000000, 0.5f)).asShadow(1.2f, 0.6f, false).draw(ctx);
            net.evadeclient.gui.kit.Paint.rect(ctx, p[0] - s / 2, p[1] - s / 2, p[0] + s / 2, p[1] + s / 2, 1.2f, 0xFFFFFFFF);
            net.evadeclient.gui.kit.Paint.ring(ctx, p[0] - s / 2, p[1] - s / 2, p[0] + s / 2, p[1] + s / 2, 1.2f, Tokens.A500, 1);
        }
    }

    /** "FPS · 92×18 · x 8 y 6 · 100%" over the widget (under it when there's no room above). */
    private void readoutChip(DrawContext ctx, HudElementModule el, int hudW, int hudH) {
        int x = el.resolveX(hudW), y = el.resolveY(hudH), w = el.scaledWidth(), h = el.scaledHeight();
        String more = selection.size() > 1 && selection.contains(el) ? "  ·  +" + (selection.size() - 1) : "";
        String text = el.getName() + "  ·  " + w + "×" + h + "  ·  x " + x + "  y " + y + "  ·  " + Math.round(el.scale.get() * 100) + "%"
                + (el.locked.get() ? "  ·  locked" : "") + more;
        int tw = UiFont.tabularWidth(textRenderer, text, Role.TINY, kit.smooth) + 16;
        float kk = k;
        float cx1 = Math.max(4, Math.min(x * kk, this.width - tw - 4));
        float cy1 = y * kk > 26 ? y * kk - 22 : (y + h) * kk + 6;
        cy1 = Math.min(cy1, this.height - 22);
        net.evadeclient.gui.kit.Paint.surface(ctx, cx1, cy1, cx1 + tw, cy1 + 16, 8, Tokens.E4, 0, Float.NaN);
        net.evadeclient.gui.kit.Paint.circle(ctx, cx1 + 8, cy1 + 8, 5, el.locked.get() ? Tokens.N600 : Tokens.A500);
        UiFont.drawTabular(ctx, textRenderer, text, Math.round(cx1 + 14), Math.round(UiFont.centredY(Role.TINY, cy1 + 8, kit.smooth)),
                Tokens.N950, Role.TINY, kit.smooth);
    }

    // ---- the dock ----------------------------------------------------------------------------------------

    private static final int DOCK_H = 36, BTN = 26;

    /** One dock entry: an icon button, a labelled button, a divider or the primary Done. */
    private record DockItem(String id, String glyph, String label, String tip, boolean enabled, boolean on, Runnable run) {}

    private List<DockItem> dockItems() {
        List<DockItem> out = new ArrayList<>();
        out.add(new DockItem("undo", "undo", null, "Undo (Ctrl+Z)", !undo.isEmpty(), false, this::undo));
        out.add(new DockItem("redo", "redo", null, "Redo (Ctrl+Y)", !redo.isEmpty(), false, this::redo));
        out.add(null);
        out.add(new DockItem("add", "plus", "Add widget", null, true, pop == Pop.ADD, () -> togglePop(Pop.ADD, -1, -1)));
        out.add(new DockItem("layouts", "hud", "Layouts", null, true, pop == Pop.LAYOUTS, () -> togglePop(Pop.LAYOUTS, -1, -1)));
        pruneSelection();
        if (selection.size() >= 2) {
            out.add(null);
            String[][] al = {{"Left", "align_left"}, {"Center", "align_hcenter"}, {"Right", "align_right"},
                    {"Top", "align_top"}, {"Middle", "align_vcenter"}, {"Bottom", "align_bottom"}};
            for (String[] a : al) out.add(new DockItem("align:" + a[0], a[1], null, "Align " + a[0].toLowerCase(java.util.Locale.ROOT), true, false, () -> align(a[0])));
            if (selection.size() >= 3) {
                out.add(new DockItem("space:h", "dist_h", null, "Space evenly across", true, false, () -> distribute(true)));
                out.add(new DockItem("space:v", "dist_v", null, "Space evenly down", true, false, () -> distribute(false)));
            }
        }
        if (!selection.isEmpty()) {
            out.add(null);
            boolean allLocked = selection.stream().allMatch(e -> e.locked.get());
            out.add(new DockItem("lock", allLocked ? "lock" : "unlock", null, allLocked ? "Unlock (L)" : "Lock in place (L)", true, allLocked,
                    () -> toggleLock(new ArrayList<>(selection))));
            out.add(new DockItem("hide", "eye_off", null, "Hide (Delete)", true, false, () -> hide(new ArrayList<>(selection))));
            if (selection.stream().anyMatch(net.evadeclient.hud.HudStacks::inStack))
                out.add(new DockItem("unstack", "layers", null, "Unstack: move them on their own again", true, true, this::unstackSelection));
            else if (selection.size() >= 2)
                out.add(new DockItem("stack", "layers", null, "Stack: they move together, one under another", true, false, this::stackSelection));
        }
        out.add(null);
        out.add(new DockItem("grid", "grid", null, grid() ? "Grid on (G)" : "Snap to a grid (G)", true, grid(), () -> settings().hudGrid.set(!grid())));
        out.add(new DockItem("help", "help", null, "Shortcuts (?)", true, pop == Pop.HELP, () -> togglePop(Pop.HELP, -1, -1)));
        return out;
    }

    private int itemWidth(DockItem d) {
        if (d == null) return 9;
        if (d.label() != null) return net.evadeclient.gui.kit.Button.width(kit, d.label(), net.evadeclient.gui.kit.Button.Size.SM, null, false) + 14;
        return BTN;
    }

    private void drawDock(DrawContext ctx, int mx, int my) {
        List<DockItem> items = dockItems();
        int doneW = net.evadeclient.gui.kit.Button.width(kit, "Done", net.evadeclient.gui.kit.Button.Size.MD, null, false);
        int w = 10 + doneW;
        for (DockItem d : items) w += itemWidth(d) + 2;
        w += 6;
        int sw = this.width, sh = this.height;
        // Above the hotbar, hearts and action-bar text; at the top while the widget under the mouse is down there.
        int bottomY = sh - 74 - DOCK_H;
        int y = Math.max(sh / 2, bottomY);
        HudElementModule over = hoveredEl;
        if (over != null) {
            int hudH = Math.round(sh / k);
            float ob = (over.resolveY(hudH) + over.scaledHeight()) * k;
            if (ob > y - 6) y = 10;
        }
        float wa = kit.anim("dock#w", w, Motion.SMOOTH);
        float ya = kit.anim("dock#y", y, Motion.SMOOTH);
        int x1 = Math.round((sw - wa) / 2f), y1 = Math.round(ya);
        int x2 = x1 + Math.round(wa), y2 = y1 + DOCK_H;
        dockX1 = x1; dockY1 = y1; dockX2 = x2; dockY2 = y2;
        net.evadeclient.gui.kit.Paint.surface(ctx, x1, y1, x2, y2, 12, Tokens.E4, 0, mx - x1);
        kit.barrier("dock", x1, y1, x2, y2, null);
        int x = x1 + 6, cy = y1 + DOCK_H / 2;
        var m = ctx.getMatrices();
        ctx.enableScissor(x1, y1, x2, y2);
        try {
            for (DockItem d : items) {
                if (d == null) {
                    net.evadeclient.gui.kit.Paint.rect(ctx, x + 4, cy - 9, x + 5, cy + 9, 0, Theme.alpha(0xFFFFFFFF, 0.08f));
                    x += 9 + 2;
                    continue;
                }
                if (d.label() != null) {
                    int bw = itemWidth(d);
                    dockLabelButton(ctx, d, x, cy - 13, bw, 26);
                    x += bw + 2;
                } else {
                    PanelGlyphs.button(kit, "dock:" + d.id(), x, cy - BTN / 2, BTN, d.glyph(), d.tip(), d.enabled(), d.on(), b -> { if (b == 0) d.run().run(); });
                    x += BTN + 2;
                }
            }
            int dx = x2 - 6 - doneW;
            net.evadeclient.gui.kit.Button.draw(kit, "dock:done", dx, cy - 12, doneW, net.evadeclient.gui.kit.Button.Size.MD,
                    net.evadeclient.gui.kit.Button.Variant.PRIMARY, "Done", null, true, this::close);
        } finally {
            ctx.disableScissor();
        }
        // A one-time hint for the shortcuts, fading after a few seconds.
        long age = System.currentTimeMillis() - openedAt;
        if (age < 7000 && pop == Pop.NONE) {
            float a = age < 5500 ? 1f : 1f - (age - 5500) / 1500f;
            float fd = Theme.fade;
            Theme.fade = fd * a;
            String hint = "Press ? for every shortcut";
            int hw = kit.width(hint, Role.SMALL) + 18;
            int hx = (sw - hw) / 2, hy = y1 < sh / 2 ? y2 + 6 : y1 - 22;
            net.evadeclient.gui.kit.Paint.pill(ctx, hx, hy, hx + hw, hy + 16, Theme.alpha(Tokens.N300, 0.92f));
            kit.textCentredY(hint, hx + 9, hy + 8, Tokens.N900, Role.SMALL);
            Theme.fade = fd;
        }
    }

    /** A ghost button with a glyph and a label (Add widget, Layouts), lit while its popover is open. */
    private void dockLabelButton(DrawContext ctx, DockItem d, int x, int y, int w, int h) {
        String id = "dock:" + d.id();
        int st = kit.state(id, x, y, x + w, y + h, true);
        float hov = kit.anim(id + "#h", (st & net.evadeclient.gui.kit.Kit.HOVER) != 0 ? 1f : 0f);
        if (d.on()) net.evadeclient.gui.kit.Paint.rect(ctx, x, y, x + w, y + h, Tokens.R_SM, Tokens.ACCENT_SURFACE);
        else if (hov > 0.01f) net.evadeclient.gui.kit.Paint.rect(ctx, x, y, x + w, y + h, Tokens.R_SM, Theme.alpha(Tokens.N250, 0.8f * hov));
        int col = d.on() ? Tokens.N950 : Theme.lerpColor(Tokens.N800, Tokens.N950, hov);
        PanelGlyphs.draw(ctx, d.glyph(), x + 8, y + h / 2f - 6, 12, d.on() ? Tokens.A400 : col);
        kit.textCentredY(d.label(), x + 25, y + h / 2f, col, Role.LABEL);
        if ((st & net.evadeclient.gui.kit.Kit.FOCUSED) != 0)
            net.evadeclient.gui.kit.Paint.focusRing(kit, x, y, x + w, y + h, Tokens.R_SM, Tokens.FOCUS_RING);
        kit.hit(id, x, y, x + w, y + h, d.run());
    }

    // ---- popovers ----------------------------------------------------------------------------------------

    /** Open {@code p} (or close it when it is open). From a right-click, (hx, hy) is where a new widget goes. */
    private void togglePop(Pop p, int hx, int hy) {
        if (pop == p && hx < 0) { pop = Pop.NONE; return; }
        pop = p;
        popAt = System.currentTimeMillis();
        addScroll = 0;
        addAtX = hx;
        addAtY = hy;
        net.evadeclient.gui.kit.SearchField.set(kit, "add#q", "");
        if (p == Pop.ADD) kit.focus("add#q", false);
        if (hx >= 0) { popX = Math.round(hx * k); popY = Math.round(hy * k); popAbove = false; }
        else {
            // Over the dock entry that opened it.
            popX = (dockX1 + dockX2) / 2;
            popY = dockY1 < this.height / 2 ? dockY2 + 6 : dockY1 - 6;
            popAbove = dockY1 >= this.height / 2;
        }
    }

    private void closePop() {
        pop = Pop.NONE;
        if (kit.isFocused("add#q")) kit.focus(null, false);
    }

    private void drawPopover(DrawContext ctx, int mx, int my) {
        if (pop == Pop.NONE) { popX1 = popY1 = popX2 = popY2 = 0; return; }
        float t = Motion.level() == Motion.Level.OFF ? 1f : Motion.standard(Motion.clamp01((System.currentTimeMillis() - popAt) / (float) Motion.DUR_QUICK));
        float fd = Theme.fade;
        Theme.fade = fd * t;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            if (Motion.decorative()) m.translate(0, (1 - t) * (popAbove ? 4 : -4));
            switch (pop) {
                case ADD -> drawAdd(ctx, mx, my);
                case LAYOUTS -> drawLayouts(ctx, mx, my);
                case HELP -> drawHelp(ctx);
                default -> { }
            }
        } finally {
            m.popMatrix();
            Theme.fade = fd;
        }
    }

    /** Places a w x h popover at the anchor, kept on screen; registers the outside-click barrier. */
    private int[] placePop(int w, int h, boolean centred) {
        int sw = this.width, sh = this.height;
        kit.barrier("pop#outside", 0, 0, sw, sh, this::closePop);
        int x = centred ? popX - w / 2 : popX;
        int y = popAbove ? popY - h : popY;
        x = Math.max(6, Math.min(sw - w - 6, x));
        y = Math.max(6, Math.min(sh - h - 6, y));
        popX1 = x; popY1 = y; popX2 = x + w; popY2 = y + h;
        net.evadeclient.gui.kit.Paint.surface(kit.ctx, x, y, x + w, y + h, Tokens.R_MD, Tokens.E4, 0, Float.NaN);
        kit.barrier("pop#body", x, y, x + w, y + h, null);
        return new int[]{x, y};
    }

    private List<HudElementModule> addable() {
        String q = net.evadeclient.gui.kit.SearchField.text(kit, "add#q").strip().toLowerCase(java.util.Locale.ROOT);
        List<HudElementModule> off = new ArrayList<>();
        for (HudElementModule el : EvadeClient.modules.hudElements())
            if (!el.isEnabled() && HudLayout.ownsVisibility(el)
                    && (q.isEmpty() || el.getName().toLowerCase(java.util.Locale.ROOT).contains(q)
                    || el.getDescription().toLowerCase(java.util.Locale.ROOT).contains(q))) off.add(el);
        off.sort(Comparator.comparing(HudElementModule::getName, String.CASE_INSENSITIVE_ORDER));
        return off;
    }

    private static final int ADD_W = 300, ADD_ROW = 30, ADD_ROWS = 9;

    private void drawAdd(DrawContext ctx, int mx, int my) {
        List<HudElementModule> list = addable();
        int listH = ADD_ROWS * ADD_ROW;
        int h = 12 + 16 + 8 + net.evadeclient.gui.kit.SearchField.H + 8 + listH + 10;
        int[] p = placePop(ADD_W, h, addAtX < 0);
        int x = p[0] + 10, y = p[1] + 12, w = ADD_W - 20;
        kit.text("Add widget", x, y, Tokens.N950, Role.STRONG);
        String n = list.size() + (list.size() == 1 ? " off" : " off");
        kit.text(n, x + w - kit.width(n, Role.SMALL), y + 1, Tokens.N600, Role.SMALL);
        y += 16 + 4;
        net.evadeclient.gui.kit.SearchField.draw(kit, "add#q", x, y, w, "Search widgets", null, true);
        y += net.evadeclient.gui.kit.SearchField.H + 8;
        int max = Math.max(0, list.size() * ADD_ROW - listH);
        addScroll = Math.max(0, Math.min(max, addScroll));
        float sc = kit.anim("add#scroll", addScroll, Motion.SMOOTH);
        ctx.enableScissor(x - 4, y, x + w + 4, y + listH);
        kit.clipHits(x - 4, y, x + w + 4, y + listH);
        try {
            if (list.isEmpty()) {
                String msg = net.evadeclient.gui.kit.SearchField.text(kit, "add#q").isBlank() ? "Every widget is already on" : "Nothing matches";
                Icons.draw(ctx, "check", x + w / 2 - 6, y + 30, 12, Tokens.N600);
                kit.textCentredY(msg, x + (w - kit.width(msg, Role.SMALL)) / 2, y + 52, Tokens.N700, Role.SMALL);
            }
            for (int i = 0; i < list.size(); i++) {
                int ry = y + i * ADD_ROW - Math.round(sc);
                if (ry + ADD_ROW < y || ry > y + listH) continue;
                HudElementModule el = list.get(i);
                String id = "add:" + el.getName();
                int st = kit.state(id, x, ry, x + w, ry + ADD_ROW - 2, true);
                float hov = kit.anim(id + "#h", (st & net.evadeclient.gui.kit.Kit.HOVER) != 0 ? 1f : 0f);
                if (hov > 0.01f) net.evadeclient.gui.kit.Paint.rect(ctx, x, ry, x + w, ry + ADD_ROW - 2, Tokens.R_SM, Theme.alpha(Tokens.N350, hov));
                net.evadeclient.gui.kit.Paint.rect(ctx, x + 4, ry + 4, x + 24, ry + 24, Tokens.R_XS, Tokens.N250);
                Icons.draw(ctx, "hud", x + 8, ry + 8, 12, Theme.lerpColor(Tokens.N800, Tokens.A400, hov));
                kit.textCentredY(kit.ellipsize(el.getName(), Role.STRONG, w - 60), x + 32, ry + 9, Tokens.N950, Role.STRONG);
                kit.textCentredY(kit.ellipsize(el.getDescription(), Role.SMALL, w - 40), x + 32, ry + 20, Tokens.N700, Role.SMALL);
                if (hov > 0.01f) PanelGlyphs.draw(ctx, "plus", x + w - 18, ry + 8, 12, Tokens.N900);
                if ((st & net.evadeclient.gui.kit.Kit.FOCUSED) != 0)
                    net.evadeclient.gui.kit.Paint.focusRing(kit, x, ry, x + w, ry + ADD_ROW - 2, Tokens.R_SM, Tokens.FOCUS_RING);
                int ax = addAtX, ay = addAtY;
                kit.hit(id, x, ry, x + w, ry + ADD_ROW - 2, () -> { closePop(); addWidget(el, ax, ay); });
            }
        } finally {
            kit.unclipHits();
            ctx.disableScissor();
        }
        if (max > 0) net.evadeclient.gui.kit.Scrollbar.draw(kit, "add#bar", x + w + 8, y, y + listH, sc, max, v -> { addScroll = v; kit.snapAnim("add#scroll", v); });
    }

    private void drawLayouts(DrawContext ctx, int mx, int my) {
        List<String[]> rows = new ArrayList<>();
        for (String p : HudLayout.PRESETS) rows.add(new String[]{"preset", p});
        rows.add(null);
        rows.add(new String[]{"copy", "Copy HUD code", "copy", "Ctrl C"});
        rows.add(new String[]{"paste", "Paste HUD code", "list", "Ctrl V"});
        rows.add(null);
        rows.add(new String[]{"reset", "Reset all positions", "reset", null});
        int w = 236, h = 12 + 18 + 4;
        for (String[] r : rows) h += r == null ? 9 : 24;
        h += 8;
        int[] p = placePop(w, h, true);
        int x = p[0] + 6, y = p[1] + 10;
        kit.text("Layouts", x + 6, y, Tokens.N950, Role.STRONG);
        y += 22;
        for (String[] r : rows) {
            if (r == null) {
                net.evadeclient.gui.kit.Paint.rect(ctx, x + 6, y + 4, x + w - 18, y + 5, 0, Theme.alpha(0xFFFFFFFF, 0.06f));
                y += 9;
                continue;
            }
            String id = "lay:" + r[1];
            int st = kit.state(id, x, y, x + w - 12, y + 22, true);
            float hov = kit.anim(id + "#h", (st & net.evadeclient.gui.kit.Kit.HOVER) != 0 ? 1f : 0f);
            boolean danger = r[0].equals("reset");
            if (hov > 0.01f) net.evadeclient.gui.kit.Paint.rect(ctx, x, y, x + w - 12, y + 22, Tokens.R_SM,
                    danger ? Theme.alpha(Tokens.DANGER, 0.14f * hov) : Theme.alpha(Tokens.N350, hov));
            String glyph = r[0].equals("preset") ? "hud" : r[2];
            int col = danger ? Tokens.DANGER : Theme.lerpColor(Tokens.N800, Tokens.N950, hov);
            PanelGlyphs.draw(ctx, glyph, x + 8, y + 5, 12, danger ? Tokens.DANGER : Tokens.N700);
            kit.textCentredY(r[1], x + 28, y + 11, col, Role.BODY);
            if (r.length > 3 && r[3] != null) {
                String chip = r[3];
                int cw = kit.width(chip, Role.TINY) + 10;
                net.evadeclient.gui.kit.Paint.rect(ctx, x + w - 18 - cw, y + 4, x + w - 18, y + 18, Tokens.R_XS, Tokens.N150);
                kit.textCentredY(chip, x + w - 18 - cw + 5, y + 11, Tokens.N700, Role.TINY);
            }
            if ((st & net.evadeclient.gui.kit.Kit.FOCUSED) != 0)
                net.evadeclient.gui.kit.Paint.focusRing(kit, x, y, x + w - 12, y + 22, Tokens.R_SM, Tokens.FOCUS_RING);
            kit.hit(id, x, y, x + w - 12, y + 22, () -> {
                closePop();
                switch (r[0]) {
                    case "preset" -> applyPreset(r[1]);
                    case "copy" -> copyCode();
                    case "paste" -> pasteCode();
                    default -> change(() -> {
                        for (HudElementModule el : EvadeClient.modules.hudElements()) { el.resetPosition(); el.scale.reset(); }
                    });
                }
            });
            y += 24;
        }
    }

    /** The shortcut sheet: a modal with the editor's keys in two columns of key chips. */
    private void drawHelp(DrawContext ctx) {
        int sw = this.width, sh = this.height;
        kit.barrier("help#scrim", 0, 0, sw, sh, this::closePop);
        Theme.fill(ctx, 0, 0, sw, sh, Theme.alpha(Tokens.N0, 0.55f));
        String[][] keys = {
                {"Drag", "Move (snaps to edges and other widgets)"}, {"Shift Click", "Pick several"},
                {"Drag on empty", "Box-pick widgets"}, {"Ctrl A", "Pick every widget"},
                {"Scroll", "Resize"}, {"Arrows", "Nudge 1 (Shift: 10)"},
                {"Right-click", "Widget settings, or add one on empty space"}, {"L", "Lock in place"},
                {"Delete", "Hide"}, {"R", "Reset position"},
                {"G", "Snap to a grid"}, {"Ctrl Z", "Undo (Ctrl Y redo)"},
                {"Ctrl C", "Copy the HUD as a code"}, {"Ctrl V", "Paste a HUD code"},
        };
        int colW = 300, keyW = 104, rowH = 26, cols = sw >= 2 * colW + 80 ? 2 : 1;
        int rowsN = (keys.length + cols - 1) / cols;
        int w = cols * colW + 40 + (cols - 1) * 16, h = 20 + 30 + rowsN * rowH + 16 + 24 + 20;
        int x = (sw - w) / 2, y = (sh - h) / 2;
        popX1 = x; popY1 = y; popX2 = x + w; popY2 = y + h;
        net.evadeclient.gui.kit.Paint.surface(ctx, x, y, x + w, y + h, Tokens.R_LG, Tokens.E5, 0, Float.NaN);
        kit.barrier("help#body", x, y, x + w, y + h, null);
        kit.text("HUD editor shortcuts", x + 20, y + 18, Tokens.N950, Role.HEADLINE);
        int ty = y + 50;
        for (int i = 0; i < keys.length; i++) {
            int c = cols == 2 ? i % 2 : 0, r = cols == 2 ? i / 2 : i;
            int cx0 = x + 20 + c * (colW + 16), cy0 = ty + r * rowH;
            int kw = net.evadeclient.gui.kit.KeyChip.draw(kit, "help:" + i, cx0, cy0, net.evadeclient.gui.kit.KeyChip.Mode.BOUND, keys[i][0], null, () -> {});
            kit.textCentredY(kit.ellipsize(keys[i][1], Role.SMALL, colW - Math.max(kw, keyW) - 12), cx0 + Math.max(kw, keyW) + 10,
                    cy0 + net.evadeclient.gui.kit.KeyChip.H / 2f, Tokens.N800, Role.SMALL);
        }
        int bw = net.evadeclient.gui.kit.Button.width(kit, "Got it", net.evadeclient.gui.kit.Button.Size.MD, null, false);
        net.evadeclient.gui.kit.Button.draw(kit, "help#ok", x + w - 20 - bw, y + h - 20 - 24, bw, net.evadeclient.gui.kit.Button.Size.MD,
                net.evadeclient.gui.kit.Button.Variant.SECONDARY, "Got it", null, true, this::closePop);
    }

    /** A faint frame around each stack, so grouped widgets read as one (GUI px, a slanted edge light on the left). */
    private void drawStacks(DrawContext ctx) {
        float kk = k;
        int hudW = Math.round(this.width / kk), hudH = Math.round(this.height / kk);
        for (List<HudElementModule> stack : net.evadeclient.hud.HudStacks.stacks().values()) {
            int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE, n = 0;
            for (HudElementModule el : stack) {
                if (!el.isEnabled()) continue;
                n++;
                x1 = Math.min(x1, el.resolveX(hudW)); y1 = Math.min(y1, el.resolveY(hudH));
                x2 = Math.max(x2, el.resolveX(hudW) + el.scaledWidth()); y2 = Math.max(y2, el.resolveY(hudH) + el.scaledHeight());
            }
            if (n < 2) continue;
            float gx1 = x1 * kk - 4, gy1 = y1 * kk - 4, gx2 = x2 * kk + 4, gy2 = y2 * kk + 4;
            net.evadeclient.gui.kit.Paint.ring(ctx, gx1, gy1, gx2, gy2, 4, Theme.alpha(Tokens.A500, 0.4f), 1);
            net.evadeclient.gui.kit.Paint.pill(ctx, gx1 - 1, gy1 + 4, gx1 + 1, gy2 - 4, Theme.alpha(Tokens.A500, 0.8f));
        }
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

    /** Groups the selection into a stack: they move together and sit one under another (top to bottom as now). */
    public void stackSelection() {
        pruneSelection();
        if (selection.size() < 2) return;
        List<HudElementModule> els = new ArrayList<>(selection);
        change(() -> net.evadeclient.hud.HudStacks.group(els, width, height));
        Notifications.push("HUD stack", els.size() + " widgets stacked: they move together. Unstack to separate them.", true);
    }

    /** Takes the selected widgets' stacks apart (the widgets stay where they are). */
    public void unstackSelection() {
        List<HudElementModule> els = new ArrayList<>(selection);
        change(() -> net.evadeclient.hud.HudStacks.ungroup(els));
    }

    private List<HudElementModule> movable() {
        pruneSelection();
        List<HudElementModule> out = new ArrayList<>();
        for (HudElementModule e : selection) if (!e.locked.get()) out.add(e);
        return out;
    }

    // ---- mouse ------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        // The chrome (dock, popovers, sheet) first, in GUI px; then the HUD, in HUD units.
        if (kit.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (pop != Pop.NONE) { closePop(); return true; }
        enterHudSpace();
        try { return clicked(hud(click), doubled); } finally { leaveHudSpace(); }
    }

    private boolean clicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int button = click.button();
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
            if (!selection.contains(el)) {
                selection.clear();
                // A stacked widget brings its whole stack (Shift+click picks one on its own).
                for (HudElementModule m : net.evadeclient.hud.HudStacks.members(el)) if (m.isEnabled()) selection.add(m);
                selection.add(el);
            }
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
        if (button == 1) { togglePop(Pop.ADD, (int) Math.round(mx), (int) Math.round(my)); return true; }
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
        if (kit.mouseDragged(click.x(), click.y(), click.button())) return true;
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
        kit.mouseReleased(click.x(), click.y(), click.button());
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
        if (pop != Pop.NONE) {
            if (pop == Pop.ADD && overChrome(mx * k, my * k)) addScroll -= (float) v * ADD_ROW;
            return true;
        }
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
            if (pop != Pop.NONE) { closePop(); return true; }
            close();
            return true;
        }
        // The Add widget search takes typing while it has the keyboard.
        if (pop == Pop.ADD && kit.isFocused("add#q") && kit.keyPressed(key, input.modifiers())) return true;
        if (pop != Pop.NONE && (key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
                && kit.keyPressed(key, input.modifiers())) return true;
        if (key == GLFW.GLFW_KEY_SLASH || key == GLFW.GLFW_KEY_F1) { togglePop(Pop.HELP, -1, -1); return true; }
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
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        if (pop == Pop.ADD && kit.charTyped(input.codepoint())) return true;
        return super.charTyped(input);
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
