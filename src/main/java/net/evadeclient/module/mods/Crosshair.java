package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;

/** Replaces the vanilla crosshair with a configurable one that can change colour on targets. */
public class Crosshair extends Module {
    private final ModeSetting style = add(new ModeSetting("Style", "Cross", "Cross", "Dot", "Circle", "Cross + Dot", "T"));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFFFFFFFF));
    private final ColorSetting targetColor = add(new ColorSetting("Target Color", 0xFFE11D2E));
    private final BooleanSetting highlight = add(new BooleanSetting("Highlight Targets", true));
    private final NumberSetting size = add(new NumberSetting("Size", 5, 1, 15, 1));
    private final NumberSetting gap = add(new NumberSetting("Gap", 2, 0, 10, 1));
    private final NumberSetting thickness = add(new NumberSetting("Thickness", 1, 1, 4, 1));
    private final BooleanSetting outline = add(new BooleanSetting("Outline", true));
    private final BooleanSetting thirdPerson = add(new BooleanSetting("Show In 3rd Person", false));

    public Crosshair() { super("Crosshair", "Custom crosshair.", Category.MODS); }

    public void draw(DrawContext ctx) {
        if (mc.options.getPerspective() != Perspective.FIRST_PERSON && !thirdPerson.get()) return;
        if (mc.options.hudHidden) return;
        int cx = ctx.getScaledWindowWidth() / 2, cy = ctx.getScaledWindowHeight() / 2;
        boolean onTarget = highlight.get() && mc.crosshairTarget instanceof EntityHitResult e && e.getEntity() instanceof LivingEntity;
        int c = onTarget ? targetColor.get() : color.get();
        int s = size.getInt(), g = gap.getInt(), t = thickness.getInt();
        int h = t / 2;
        switch (style.get()) {
            case "Dot" -> rect(ctx, cx - h - 1, cy - h - 1, cx + h + 1, cy + h + 1, c);
            case "Circle" -> circle(ctx, cx, cy, s, c);
            case "T" -> {
                rect(ctx, cx - g - s, cy - h, cx - g, cy - h + t, c);
                rect(ctx, cx + g + 1, cy - h, cx + g + 1 + s, cy - h + t, c);
                rect(ctx, cx - h, cy + g + 1, cx - h + t, cy + g + 1 + s, c);
            }
            default -> {
                rect(ctx, cx - g - s, cy - h, cx - g, cy - h + t, c);
                rect(ctx, cx + g + 1, cy - h, cx + g + 1 + s, cy - h + t, c);
                rect(ctx, cx - h, cy - g - s, cx - h + t, cy - g, c);
                rect(ctx, cx - h, cy + g + 1, cx - h + t, cy + g + 1 + s, c);
                if (style.is("Cross + Dot")) rect(ctx, cx - h, cy - h, cx - h + t, cy - h + t, c);
            }
        }
    }

    private void rect(DrawContext ctx, int x1, int y1, int x2, int y2, int c) {
        if (outline.get()) ctx.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xC0000000);
        ctx.fill(x1, y1, x2, y2, c);
    }

    private void circle(DrawContext ctx, int cx, int cy, int r, int c) {
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
            rect(ctx, x, y, x + 1, y + 1, c);
        }
    }
}
