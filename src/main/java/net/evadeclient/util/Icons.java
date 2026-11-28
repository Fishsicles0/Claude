package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * The white line icons in textures/gui/nav, tinted by colour. Each icon ships as a 24, 48, 96 and 192 px mip chain with
 * LINEAR sampling; the smallest source that is at least as large as the icon on screen is used, so icons stay
 * crisp at every GUI scale without being minified past one level.
 */
public final class Icons {
    private static final Map<String, Identifier> IDS = new HashMap<>();
    /** Whether each (name, size) texture exists; a missing larger size falls back to the 24 px file. */
    private static final Map<String, Boolean> PRESENT = new HashMap<>();

    private Icons() {}

    /** Draw icon {@code name} at {@code size} x {@code size} GUI pixels, tinted {@code color} (with {@link Theme#fade}). */
    public static void draw(DrawContext ctx, String name, int x, int y, int size, int color) {
        int c = Theme.fa(color);
        if (size <= 0 || c >>> 24 == 0) return;
        // The regular gear's thin teeth break up below 11 px; a bolder six-tooth gear reads better there.
        if (size <= 10 && name.equals("settings")) name = "settings_sm";
        float px = size * Theme.deviceScale(ctx);
        int src = px <= 24 ? 24 : px <= 48 ? 48 : px <= 96 ? 96 : 192;
        Identifier id = id(name, src);
        if (src != 24 && !present(name, src, id)) { src = 24; id = id(name, 24); }
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, size, size, src, src, src, src, c);
    }

    private static Identifier id(String name, int src) {
        String key = src == 24 ? name : name + "_" + src;
        return IDS.computeIfAbsent(key, k -> Identifier.of("evade", "textures/gui/nav/" + k + ".png"));
    }

    private static boolean present(String name, int src, Identifier id) {
        return PRESENT.computeIfAbsent(name + "_" + src, k -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            return mc == null || mc.getResourceManager() == null || mc.getResourceManager().getResource(id).isPresent();
        });
    }
}
