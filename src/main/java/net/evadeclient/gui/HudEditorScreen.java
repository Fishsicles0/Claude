package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Drag HUD elements around with snapping to screen edges, the centre and other elements.
 * Scroll over an element to resize it; right-click to open its settings (Escape there comes back here).
 * Arrow keys nudge the element under the mouse (Shift: 8 at a time) and R puts it back at its default spot.
 */
public class HudEditorScreen extends Screen {
    private static final int SNAP = 4, GRID = 8;
    private static final int BAR_W = 120, BAR_H = 18;
    private static final String HINT_1 = "Drag to move  •  Scroll to resize  •  Right-click for settings";
    private final Screen parent;
    private HudElementModule dragging;
    /** The element last clicked or dragged: arrow keys and R act on it when the mouse isn't over one. */
    private HudElementModule selected;
    private double offX, offY;
    private Integer guideX, guideY;
    /** Whether the element being dragged is snapped to the vertical centre line (stored as centred). */
    private boolean centred;
    /** Mouse position in HUD units, from the last frame (for the keyboard shortcuts). */
    private double mouseHudX = -1, mouseHudY = -1;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("Edit HUD"));
        this.parent = parent;
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

    // ---- the bottom strip: hint and the Back button ------------------------------------------------------

    private String hint2() {
        return "Arrows to nudge (Shift: 8)  •  R to reset  •  G: grid " + (grid() ? "on" : "off");
    }

    /** Hint lines: one when it fits the screen, else two. */
    private String[] hintLines() {
        String one = HINT_1 + "  •  " + hint2();
        return textRenderer.getWidth(one) + 16 <= width ? new String[]{one} : new String[]{HINT_1, hint2()};
    }

    /**
     * Top of the Back button. It sits above the hotbar, hearts and action-bar text (74 GUI pixels from the
     * bottom), or at the top of the screen while the element under the mouse is down there.
     */
    private int barY(double mx, double my) {
        int lines = hintLines().length;
        int bottom = Math.max(height / 2, height - Math.round(74 / k) - BAR_H);
        HudElementModule over = dragging == null ? at(mx, my) : null;
        if (over != null) {
            int stripTop = bottom - 6 - lines * 10;
            if (over.resolveY(height) + over.scaledHeight() > stripTop) return 8 + lines * 10 + 6;
        }
        return bottom;
    }

    private int barX() { return (width - BAR_W) / 2; }

    private boolean onBar(double mx, double my, int by) {
        int bx = barX();
        return mx >= bx && mx < bx + BAR_W && my >= by && my < by + BAR_H;
    }

    private void renderHud(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        // grid (when snapping to it) and centre guides
        if (grid()) {
            for (int gx = GRID; gx < width; gx += GRID) ctx.fill(gx, 0, gx + 1, height, gx % (GRID * 4) == 0 ? 0x14FFFFFF : 0x08FFFFFF);
            for (int gy = GRID; gy < height; gy += GRID) ctx.fill(0, gy, width, gy + 1, gy % (GRID * 4) == 0 ? 0x14FFFFFF : 0x08FFFFFF);
        }
        ctx.fill(width / 2, 0, width / 2 + 1, height, 0x18FFFFFF);
        ctx.fill(0, height / 2, width, height / 2 + 1, 0x18FFFFFF);

        HudElementModule hovered = null;
        if (client.player != null) {
            for (HudElementModule el : EvadeClient.modules.hudElements()) {
                if (!el.isEnabled()) continue;
                el.renderScaled(ctx, width, height, true);
            }
            hovered = dragging != null ? dragging : at(mouseX, mouseY);
            for (HudElementModule el : EvadeClient.modules.hudElements()) {
                if (!el.isEnabled()) continue;
                int x = el.resolveX(width), y = el.resolveY(height), w = el.scaledWidth(), h = el.scaledHeight();
                boolean hover = el == hovered;
                ctx.drawStrokedRectangle(x - 1, y - 1, w + 2, h + 2, hover ? Theme.RED : el == selected ? 0x90FFFFFF : 0x50FFFFFF);
            }
            if (hovered != null) {
                // Name and size, kept on screen: above the element, or below it near the top edge.
                int x = hovered.resolveX(width), y = hovered.resolveY(height), h = hovered.scaledHeight();
                String label = hovered.getName() + "  " + Math.round(hovered.scale.get() * 100) + "%";
                int lw = textRenderer.getWidth(label) + 6;
                int lx = Math.max(0, Math.min(x - 1, width - lw));
                int ly = y > 12 ? y - 11 : Math.min(height - 11, y + h + 2);
                Theme.round(ctx, lx, ly - 1, lx + lw, ly + 10, 0xE0000000);
                ctx.drawText(textRenderer, label, lx + 3, ly + 1, Theme.RED_BRIGHT, false);
            }
        }
        if (guideX != null) ctx.fill(guideX, 0, guideX + 1, height, Theme.RED);
        if (guideY != null) ctx.fill(0, guideY, width, guideY + 1, Theme.RED);

        // The strip (hint and Back button) gets out of the way while dragging.
        if (dragging != null) return;
        int by = barY(mouseX, mouseY), bx = barX();
        boolean hb = hovered == null && onBar(mouseX, mouseY, by);
        Theme.round(ctx, bx, by, bx + BAR_W, by + BAR_H, hb ? Theme.RED_BRIGHT : Theme.RED);
        String t = "Back to Mods";
        ctx.drawText(textRenderer, t, bx + (BAR_W - textRenderer.getWidth(t)) / 2, by + 5, Theme.ON_ACCENT, false);
        String[] lines = hintLines();
        int hw = 0;
        for (String l : lines) hw = Math.max(hw, textRenderer.getWidth(l));
        int hy = by - 6 - lines.length * 10;
        int hx = (width - hw) / 2;
        Theme.round(ctx, hx - 6, hy - 3, hx + hw + 6, hy + lines.length * 10 + 1, 0xC0101014);
        for (int i = 0; i < lines.length; i++)
            ctx.drawText(textRenderer, lines[i], (width - textRenderer.getWidth(lines[i])) / 2, hy + i * 10, Theme.TEXT_SOFT, false);
    }

    private boolean inside(HudElementModule el, double mx, double my) {
        int x = el.resolveX(width), y = el.resolveY(height);
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

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        enterHudSpace();
        try { return clicked(hud(click), doubled); } finally { leaveHudSpace(); }
    }

    private boolean clicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        // Elements first: one under the strip can still be grabbed (Escape always leaves the editor).
        HudElementModule el = at(mx, my);
        if (el != null) {
            selected = el;
            if (click.button() == 1) {
                client.setScreen(EvadeMenu.open(el, this));
                return true;
            }
            dragging = el;
            centred = el.x == HudElementModule.CENTER;
            offX = mx - el.resolveX(width);
            offY = my - el.resolveY(height);
            return true;
        }
        if (click.button() == 0 && onBar(mx, my, barY(mx, my))) { close(); return true; }
        selected = null;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        enterHudSpace();
        try { return dragged(hud(click), dx / k, dy / k); } finally { leaveHudSpace(); }
    }

    private boolean dragged(Click click, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(click, dx, dy);
        int x = (int) Math.round(click.x() - offX), y = (int) Math.round(click.y() - offY);
        int w = dragging.scaledWidth(), h = dragging.scaledHeight();
        guideX = guideY = null;
        centred = false;
        if (grid()) { // snap to the grid; the edge and element guides below still win when close
            x = Math.round(x / (float) GRID) * GRID;
            y = Math.round(y / (float) GRID) * GRID;
        }
        // Snap edges/centre to screen edges and centre.
        int[] xs = {0, width / 2, width}, ys = {0, height / 2, height};
        for (int target : xs) {
            if (Math.abs(x - target) < SNAP) { x = target; guideX = target; centred = false; }
            else if (Math.abs(x + w / 2 - target) < SNAP) { x = target - w / 2; guideX = target; centred = target == width / 2; }
            else if (Math.abs(x + w - target) < SNAP) { x = target - w; guideX = target; centred = false; }
        }
        for (int target : ys) {
            if (Math.abs(y - target) < SNAP) { y = target; guideY = target; }
            else if (Math.abs(y + h / 2 - target) < SNAP) { y = target - h / 2; guideY = target; }
            else if (Math.abs(y + h - target) < SNAP) { y = target - h; guideY = target; }
        }
        // Then align with other elements: matching left/right edges, or stacking just above/below.
        for (HudElementModule o : EvadeClient.modules.hudElements()) {
            if (o == dragging || !o.isEnabled()) continue;
            int ox = o.resolveX(width), oy = o.resolveY(height), ow = o.scaledWidth(), oh = o.scaledHeight();
            if (guideX == null) {
                if (Math.abs(x - ox) < SNAP) { x = ox; guideX = ox; }
                else if (Math.abs(x + w - (ox + ow)) < SNAP) { x = ox + ow - w; guideX = ox + ow; }
            }
            if (guideY == null) {
                if (Math.abs(y - (oy + oh + 2)) < SNAP) { y = oy + oh + 2; guideY = oy + oh + 2; }
                else if (Math.abs(y + h + 2 - oy) < SNAP) { y = oy - h - 2; guideY = oy - 2; }
            }
        }
        dragging.moveTo(x, y, width, height, centred);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        guideX = guideY = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        enterHudSpace();
        try { return scrolled(mx / k, my / k, h, v); } finally { leaveHudSpace(); }
    }

    private boolean scrolled(double mx, double my, double h, double v) {
        HudElementModule el = at(mx, my);
        if (el == null) return false;
        el.scale.set(el.scale.get() + (v > 0 ? 0.05 : -0.05));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (key == GLFW.GLFW_KEY_G) { settings().hudGrid.set(!grid()); return true; }
        if (dragging == null && (key == GLFW.GLFW_KEY_R || key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
                || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            enterHudSpace();
            try {
                HudElementModule el = at(mouseHudX, mouseHudY);
                if (el == null && selected != null && selected.isEnabled()) el = selected;
                if (el == null) return super.keyPressed(input);
                selected = el;
                if (key == GLFW.GLFW_KEY_R) {
                    el.resetPosition();
                    return true;
                }
                int step = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 8 : 1;
                int dx = key == GLFW.GLFW_KEY_LEFT ? -step : key == GLFW.GLFW_KEY_RIGHT ? step : 0;
                int dy = key == GLFW.GLFW_KEY_UP ? -step : key == GLFW.GLFW_KEY_DOWN ? step : 0;
                // A centred element stays centred when only moved up or down.
                boolean keepCentre = dx == 0 && el.x == HudElementModule.CENTER;
                el.moveTo(el.resolveX(width) + dx, el.resolveY(height) + dy, width, height, keepCentre);
                return true;
            } finally {
                leaveHudSpace();
            }
        }
        return super.keyPressed(input);
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
