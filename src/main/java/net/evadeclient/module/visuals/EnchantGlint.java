package net.evadeclient.module.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ColorSetting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

/**
 * Recolours the enchantment glint on items and armour. The tinted pixels are written straight into the
 * existing glint textures on the GPU (render layers hold on to those textures, so they must not be replaced).
 */
public class EnchantGlint extends Module {
    public final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E));
    private int applied;
    private Object appliedTo;

    public EnchantGlint() {
        super("EnchantGlint", "Customise the colour of the enchantment glow.", Category.VISUALS);
    }

    /** True once the current colour has been uploaded (for tests). */
    public boolean isApplied() { return applied != 0; }

    @Override protected void onEnable() { applied = 0; appliedTo = null; }

    @Override
    protected void onDisable() {
        write(ItemRenderer.ITEM_ENCHANTMENT_GLINT, 0);
        write(ItemRenderer.ENTITY_ENCHANTMENT_GLINT, 0);
        applied = 0;
        appliedTo = null;
    }

    @Override
    public void onTick() {
        int c = color.get() | 0xFF000000;
        // re-apply after a colour change, or after a resource reload created fresh textures
        Object current = mc.getTextureManager().getTexture(ItemRenderer.ITEM_ENCHANTMENT_GLINT);
        if (c == applied && current == appliedTo) return;
        boolean ok = write(ItemRenderer.ITEM_ENCHANTMENT_GLINT, c) & write(ItemRenderer.ENTITY_ENCHANTMENT_GLINT, c);
        if (ok) { applied = c; appliedTo = current; }
    }

    /** Upload the glint at {@code id}, tinted to {@code color} (0 = the untouched original). */
    private boolean write(Identifier id, int color) {
        try (var in = mc.getResourceManager().open(id); NativeImage img = NativeImage.read(in)) {
            if (color != 0) {
                int tr = color >> 16 & 255, tg = color >> 8 & 255, tb = color & 255;
                for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
                    int p = img.getColorArgb(x, y);
                    int a = p >>> 24, l = Math.max(p >> 16 & 255, Math.max(p >> 8 & 255, p & 255));
                    img.setColorArgb(x, y, (a << 24) | (tr * l / 255 << 16) | (tg * l / 255 << 8) | (tb * l / 255));
                }
            }
            var gpu = mc.getTextureManager().getTexture(id).getGlTexture();
            if (gpu == null || gpu.getWidth(0) != img.getWidth() || gpu.getHeight(0) != img.getHeight()) return false;
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(gpu, img);
            return true;
        } catch (Exception e) {
            net.evadeclient.EvadeClient.LOG.warn("EnchantGlint: couldn't write {}", id, e);
            return false;
        }
    }
}
