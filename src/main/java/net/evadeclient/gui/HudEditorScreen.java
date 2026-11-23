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
 * Scroll over an element to resize it; right-click to open its settings.
 */
public class HudEditorScreen extends Screen {
    private static final int SNAP = 4, GRID = 8;
    private final Screen parent;
    private HudElementModule dragging;
    private double offX, offY;
    private Integer guideX, guideY;

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
            renderHud(ctx, Math.round(mouseX / k), Math.round(mouseY / k), delta);
        } finally {
            ctx.getMatrices().popMatrix();
            leaveHudSpace();
        }
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

        if (client.player != null) {
            for (HudElementModule el : EvadeClient.modules.hudElements()) {
                if (!el.isEnabled()) continue;
                el.renderScaled(ctx, width, height, true);
                int x = el.resolveX(width), y = el.resolveY(height), w = el.scaledWidth(), h = el.scaledHeight();
                boolean hover = el == dragging || (dragging == null && inside(el, mouseX, mouseY));
                int c = hover ? Theme.RED : 0x50FFFFFF;
                ctx.drawStrokedRectangle(x - 1, y - 1, w + 2, h + 2, c);
                if (hover) {
                    String label = el.getName() + "  " + Math.round(el.scale.get() * 100) + "%";
                    int ly = y > 12 ? y - 11 : y + h + 2;
                    Theme.round(ctx, x - 1, ly - 1, x + textRenderer.getWidth(label) + 5, ly + 10, 0xE0000000);
                    ctx.drawText(textRenderer, label, x + 2, ly + 1, Theme.RED_BRIGHT, false);
                }
            }
        }
        if (guideX != null) ctx.fill(guideX, 0, guideX + 1, height, Theme.RED);
        if (guideY != null) ctx.fill(0, guideY, width, guideY + 1, Theme.RED);

        // bottom bar
        int bw = 120, bx = (width - bw) / 2, by = height - 30;
        boolean hb = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 18;
        Theme.round(ctx, bx, by, bx + bw, by + 18, hb ? Theme.RED_BRIGHT : Theme.RED);
        String t = "Back to Mods";
        ctx.drawText(textRenderer, t, bx + (bw - textRenderer.getWidth(t)) / 2, by + 5, 0xFFFFFFFF, false);
        String hint = "Drag to move  •  Scroll to resize  •  Right-click for settings  •  G: grid " + (grid() ? "on" : "off");
        ctx.drawText(textRenderer, hint, (width - textRenderer.getWidth(hint)) / 2, by - 12, Theme.TEXT_DIM, false);
    }

    private boolean inside(HudElementModule el, double mx, double my) {
        int x = el.resolveX(width), y = el.resolveY(height);
        return mx >= x && mx <= x + el.scaledWidth() && my >= y && my <= y + el.scaledHeight();
    }

    private HudElementModule at(double mx, double my) {
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
        int bw = 120, bx = (width - bw) / 2, by = height - 30;
        if (mx >= bx && mx < bx + bw && my >= by && my < by + 18) { close(); return true; }
        HudElementModule el = at(mx, my);
        if (el == null) return super.mouseClicked(click, doubled);
        if (click.button() == 1) {
            client.setScreen(EvadeMenu.open(el));
            return true;
        }
        dragging = el;
        offX = mx - el.resolveX(width);
        offY = my - el.resolveY(height);
        return true;
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
        if (grid()) { // snap to the grid; the edge and element guides below still win when close
            x = Math.round(x / (float) GRID) * GRID;
            y = Math.round(y / (float) GRID) * GRID;
        }
        // Snap edges/centre to screen edges and centre.
        int[] xs = {0, width / 2, width}, ys = {0, height / 2, height};
        for (int target : xs) {
            if (Math.abs(x - target) < SNAP) { x = target; guideX = target; }
            else if (Math.abs(x + w / 2 - target) < SNAP) { x = target - w / 2; guideX = target; }
            else if (Math.abs(x + w - target) < SNAP) { x = target - w; guideX = target; }
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
        dragging.moveTo(x, y, width, height);
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
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (input.key() == GLFW.GLFW_KEY_G) { settings().hudGrid.set(!grid()); return true; }
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
