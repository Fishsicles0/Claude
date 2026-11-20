package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.ColorUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

class Panel {
    static final int WIDTH = 110;
    static final int HEADER_H = 16;
    static final int ROW_H = 14;

    private final ClickGuiScreen screen;
    private final Category category;
    private final List<Module> modules;
    private final Set<Module> expanded = new HashSet<>();
    int x, y;
    boolean open = true;

    private NumberSetting draggingSlider;
    private int sliderX;

    Panel(ClickGuiScreen screen, Category category, int x, int y) {
        this.screen = screen;
        this.category = category;
        this.modules = EvadeClient.modules.byCategory(category);
        this.x = x;
        this.y = y;
    }

    boolean isOverHeader(double mx, double my) {
        return mx >= x && mx <= x + WIDTH && my >= y && my <= y + HEADER_H;
    }

    void render(DrawContext ctx, TextRenderer tr, int mx, int my) {
        ctx.fill(x, y, x + WIDTH, y + HEADER_H, ColorUtil.HEADER_BG);
        ctx.fill(x, y + HEADER_H - 1, x + WIDTH, y + HEADER_H, ColorUtil.ACCENT);
        ctx.drawTextWithShadow(tr, category.displayName, x + 5, y + 4, ColorUtil.TEXT);
        ctx.drawTextWithShadow(tr, open ? "-" : "+", x + WIDTH - 10, y + 4, ColorUtil.TEXT_DIM);
        if (!open) return;

        int cy = y + HEADER_H;
        for (Module m : modules) {
            boolean hover = within(mx, my, cy, ROW_H);
            ctx.fill(x, cy, x + WIDTH, cy + ROW_H, ColorUtil.PANEL_BG);
            if (m.isEnabled()) ctx.fill(x, cy, x + 2, cy + ROW_H, ColorUtil.ACCENT);
            if (hover) ctx.fill(x, cy, x + WIDTH, cy + ROW_H, ColorUtil.HOVER);
            ctx.drawTextWithShadow(tr, m.getName(), x + 6, cy + 3, m.isEnabled() ? ColorUtil.ACCENT : ColorUtil.TEXT);
            ctx.drawTextWithShadow(tr, expanded.contains(m) ? "v" : "..", x + WIDTH - 12, cy + 3, ColorUtil.TEXT_DIM);
            cy += ROW_H;

            if (expanded.contains(m)) {
                for (Setting<?> s : m.getSettings()) {
                    renderSetting(ctx, tr, s, cy, mx, my);
                    cy += ROW_H;
                }
                renderBind(ctx, tr, m, cy, mx, my);
                cy += ROW_H;
            }
        }
        ctx.fill(x, cy, x + WIDTH, cy + 2, ColorUtil.HEADER_BG);
    }

    private void renderSetting(DrawContext ctx, TextRenderer tr, Setting<?> s, int cy, int mx, int my) {
        ctx.fill(x, cy, x + WIDTH, cy + ROW_H, 0xF00C0C10);
        if (within(mx, my, cy, ROW_H)) ctx.fill(x, cy, x + WIDTH, cy + ROW_H, ColorUtil.HOVER);

        if (s instanceof NumberSetting n) {
            double pct = (n.get() - n.getMin()) / (n.getMax() - n.getMin());
            ctx.fill(x + 4, cy + ROW_H - 2, x + 4 + (int) ((WIDTH - 8) * pct), cy + ROW_H - 1, ColorUtil.ACCENT);
        }
        int valueColor = s instanceof BooleanSetting b && b.get() ? ColorUtil.ACCENT : ColorUtil.TEXT;
        ctx.drawTextWithShadow(tr, s.getName(), x + 8, cy + 3, ColorUtil.TEXT_DIM);
        String v = s.display();
        ctx.drawTextWithShadow(tr, v, x + WIDTH - 5 - tr.getWidth(v), cy + 3, valueColor);
    }

    private void renderBind(DrawContext ctx, TextRenderer tr, Module m, int cy, int mx, int my) {
        ctx.fill(x, cy, x + WIDTH, cy + ROW_H, 0xF00C0C10);
        if (within(mx, my, cy, ROW_H)) ctx.fill(x, cy, x + WIDTH, cy + ROW_H, ColorUtil.HOVER);
        ctx.drawTextWithShadow(tr, "Bind", x + 8, cy + 3, ColorUtil.TEXT_DIM);
        String v = screen.binding == m ? "..." : keyName(m.getKey());
        ctx.drawTextWithShadow(tr, v, x + WIDTH - 5 - tr.getWidth(v), cy + 3, ColorUtil.TEXT);
    }

    boolean mouseClicked(double mx, double my, int button) {
        if (!open) return false;
        int cy = y + HEADER_H;
        for (Module m : modules) {
            if (within(mx, my, cy, ROW_H)) {
                if (button == 0) m.toggle();
                else if (button == 1 && !expanded.remove(m)) expanded.add(m);
                return true;
            }
            cy += ROW_H;
            if (!expanded.contains(m)) continue;

            for (Setting<?> s : m.getSettings()) {
                if (within(mx, my, cy, ROW_H)) {
                    clickSetting(s, mx, button);
                    return true;
                }
                cy += ROW_H;
            }
            if (within(mx, my, cy, ROW_H)) {
                screen.binding = button == 0 ? m : null;
                if (button == 1) m.setKey(GLFW.GLFW_KEY_UNKNOWN);
                return true;
            }
            cy += ROW_H;
        }
        return false;
    }

    private void clickSetting(Setting<?> s, double mx, int button) {
        if (s instanceof BooleanSetting b) b.toggle();
        else if (s instanceof ModeSetting m) m.cycle(button == 0);
        else if (s instanceof NumberSetting n && button == 0) {
            draggingSlider = n;
            sliderX = x;
            updateSlider(mx);
        }
    }

    void mouseDragged(double mx) {
        if (draggingSlider != null) updateSlider(mx);
    }

    void mouseReleased() {
        draggingSlider = null;
    }

    private void updateSlider(double mx) {
        NumberSetting n = draggingSlider;
        double pct = Math.max(0, Math.min(1, (mx - sliderX - 4) / (WIDTH - 8)));
        n.set(n.getMin() + pct * (n.getMax() - n.getMin()));
    }

    private boolean within(double mx, double my, int rowY, int h) {
        return mx >= x && mx <= x + WIDTH && my >= rowY && my < rowY + h;
    }

    static String keyName(int key) {
        if (key == GLFW.GLFW_KEY_UNKNOWN) return "None";
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }
}
