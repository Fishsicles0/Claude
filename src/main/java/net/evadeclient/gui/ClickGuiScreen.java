package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.util.ColorUtil;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Category panels you can drag around.
 * Left-click a module to toggle it, right-click to expand its settings.
 * HUD elements are shown here too and can be dragged into place.
 */
public class ClickGuiScreen extends Screen {
    private static ClickGuiScreen instance;

    private final List<Panel> panels = new ArrayList<>();
    private Panel dragging;
    private HudElementModule draggingHud;
    private double dragOffX, dragOffY;

    /** Module whose keybind is currently being set (waits for the next key press). */
    Module binding;

    private ClickGuiScreen() {
        super(Text.literal("Evade ClickGUI"));
        int x = 10;
        for (Category c : Category.values()) {
            panels.add(new Panel(this, c, x, 20));
            x += Panel.WIDTH + 8;
        }
    }

    public static ClickGuiScreen get() {
        if (instance == null) instance = new ClickGuiScreen();
        return instance;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        renderHudElements(ctx, mouseX, mouseY);
        for (Panel p : panels) p.render(ctx, textRenderer, mouseX, mouseY);

        String hint = binding != null
                ? "Press a key to bind " + binding.getName() + " (Esc/Delete to clear)"
                : "Left-click: toggle   Right-click: settings   Drag panels and HUD boxes to move";
        ctx.drawCenteredTextWithShadow(textRenderer, hint, width / 2, height - 14, ColorUtil.TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int button = click.button();
        // Iterate top-most panel first (last drawn).
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel p = panels.get(i);
            if (p.isOverHeader(mx, my)) {
                if (button == 0) {
                    dragging = p;
                    dragOffX = mx - p.x;
                    dragOffY = my - p.y;
                } else if (button == 1) {
                    p.open = !p.open;
                }
                bringToFront(p);
                return true;
            }
            if (p.mouseClicked(mx, my, button)) return true;
        }
        if (button == 0) {
            HudElementModule el = hudAt(mx, my);
            if (el != null) {
                draggingHud = el;
                dragOffX = mx - el.resolveX(width);
                dragOffY = my - el.resolveY(height);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        draggingHud = null;
        for (Panel p : panels) p.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        double mx = click.x(), my = click.y();
        if (dragging != null) {
            dragging.x = (int) (mx - dragOffX);
            dragging.y = (int) (my - dragOffY);
            return true;
        }
        if (draggingHud != null) {
            draggingHud.moveTo((int) (mx - dragOffX), (int) (my - dragOffY), width, height);
            return true;
        }
        for (Panel p : panels) p.mouseDragged(mx);
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();
        if (binding != null) {
            boolean clear = keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_DELETE
                    || keyCode == GLFW.GLFW_KEY_BACKSPACE;
            binding.setKey(clear ? GLFW.GLFW_KEY_UNKNOWN : keyCode);
            binding = null;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    private void renderHudElements(DrawContext ctx, int mouseX, int mouseY) {
        if (client == null || client.player == null) return;
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            if (!el.isEnabled()) continue;
            int x = el.resolveX(width), y = el.resolveY(height);
            el.render(ctx, x, y, true);
            boolean hover = el == draggingHud || (mouseX >= x && mouseX <= x + el.getWidth() && mouseY >= y && mouseY <= y + el.getHeight());
            int c = hover ? ColorUtil.ACCENT : 0x60FFFFFF;
            ctx.fill(x - 1, y - 1, x + el.getWidth() + 1, y, c);
            ctx.fill(x - 1, y + el.getHeight(), x + el.getWidth() + 1, y + el.getHeight() + 1, c);
            ctx.fill(x - 1, y, x, y + el.getHeight(), c);
            ctx.fill(x + el.getWidth(), y, x + el.getWidth() + 1, y + el.getHeight(), c);
            if (hover) ctx.drawTextWithShadow(textRenderer, el.getName(), x, y - 10, ColorUtil.ACCENT);
        }
    }

    private HudElementModule hudAt(double mx, double my) {
        if (client == null || client.player == null) return null;
        for (HudElementModule el : EvadeClient.modules.hudElements()) {
            if (!el.isEnabled()) continue;
            int x = el.resolveX(width), y = el.resolveY(height);
            if (mx >= x && mx <= x + el.getWidth() && my >= y && my <= y + el.getHeight()) return el;
        }
        return null;
    }

    @Override
    public void close() {
        binding = null;
        draggingHud = null;
        EvadeClient.config.save();
        super.close();
    }

    @Override
    public boolean shouldPause() { return false; }

    private void bringToFront(Panel p) {
        panels.remove(p);
        panels.add(p);
    }
}
