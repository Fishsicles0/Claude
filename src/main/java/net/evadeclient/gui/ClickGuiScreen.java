package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.util.ColorUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Category panels you can drag around.
 * Left-click a module to toggle it, right-click to expand its settings.
 */
public class ClickGuiScreen extends Screen {
    private static ClickGuiScreen instance;

    private final List<Panel> panels = new ArrayList<>();
    private Panel dragging;
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
        for (Panel p : panels) p.render(ctx, textRenderer, mouseX, mouseY);

        String hint = binding != null
                ? "Press a key to bind " + binding.getName() + " (Esc/Delete to clear)"
                : "Left-click: toggle   Right-click: settings   Drag headers to move";
        ctx.drawCenteredTextWithShadow(textRenderer, hint, width / 2, height - 14, ColorUtil.TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
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
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = null;
        for (Panel p : panels) p.mouseReleased();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            dragging.x = (int) (mx - dragOffX);
            dragging.y = (int) (my - dragOffY);
            return true;
        }
        for (Panel p : panels) p.mouseDragged(mx);
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        binding = null;
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
