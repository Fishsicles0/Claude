package net.evadeclient.module.visuals;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.util.ColorUtil;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

/**
 * Recolours the enchantment glint: one colour for items (in hand, in inventories, on the ground) and one for worn
 * armour, or a rainbow for both. The tinted pixels are written straight into the existing glint textures on the GPU
 * (render layers hold on to those textures, so they must not be replaced). Each glint's original pixels are read once
 * per GPU texture and kept, so colour changes and the rainbow never re-read the resource; a resource reload creates a
 * new GPU texture, which is noticed and re-read.
 */
public class EnchantGlint extends Module {
    /** Rainbow re-uploads every this many ticks (0.15 s): smooth enough for a slow cycle, cheap for the GPU. */
    private static final int RAINBOW_INTERVAL = 3;

    public final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E)
            .describe("Glint colour on items: in your hand, in inventories and on the ground."));
    public final ColorSetting armorColor = add(new ColorSetting("Armor Color", 0xFFE11D2E)
            .describe("Glint colour on armour worn by you and other players and mobs."));
    public final BooleanSetting rainbow = add(new BooleanSetting("Rainbow", false)
            .describe("Cycles both glints through every colour."));

    private final Glint item = new Glint(ItemRenderer.ITEM_ENCHANTMENT_GLINT);
    private final Glint armor = new Glint(ItemRenderer.ENTITY_ENCHANTMENT_GLINT);
    private int rainbowColor, rainbowTicks;

    public EnchantGlint() {
        super("EnchantGlint", "Customise the colour of the enchantment glow.", Category.VISUALS);
    }

    /** True once the current item colour has been uploaded (for tests). */
    public boolean isApplied() { return item.applied != 0; }

    /** Configs from before Armor Color existed used one colour for both: keep it that way for them. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (!settings.has(armorColor.getName()) && settings.has(color.getName())) armorColor.set(color.get());
    }

    @Override
    protected void onEnable() {
        rainbowColor = 0;
        rainbowTicks = 0;
    }

    @Override
    protected void onDisable() {
        item.restore();
        armor.restore();
    }

    @Override
    public void onTick() {
        int itemTarget, armorTarget;
        if (rainbow.get()) {
            if (rainbowColor == 0 || ++rainbowTicks >= RAINBOW_INTERVAL) {
                rainbowTicks = 0;
                rainbowColor = ColorUtil.rainbow(0, 1f, 1f);
            }
            itemTarget = armorTarget = rainbowColor;
        } else {
            rainbowColor = 0;
            itemTarget = color.get() | 0xFF000000;
            armorTarget = armorColor.get() | 0xFF000000;
        }
        item.apply(itemTarget);
        armor.apply(armorTarget);
    }

    /** One glint texture: its original pixels (per GPU texture), a reusable upload buffer, and what it shows now. */
    private static final class Glint {
        private final Identifier id;
        /** The GPU texture {@link #original} was read for; a different one means the resources were reloaded. */
        private GpuTexture source;
        private int[] original;
        private int width, height;
        private NativeImage buffer;
        /** The ARGB colour currently uploaded, or 0 when the texture holds its original pixels. */
        int applied;
        /** Set when the resource couldn't be used with {@link #source}, so it isn't retried every tick. */
        private boolean failed;

        Glint(Identifier id) { this.id = id; }

        /** Shows {@code argb} (opaque), uploading only when it differs from what the texture already shows. */
        void apply(int argb) {
            GpuTexture gpu = current();
            if (gpu == null) return;
            if (gpu != source) adopt(gpu);
            if (failed || argb == applied) return;
            if (upload(gpu, argb)) applied = argb;
        }

        /** Puts the original pixels back (if the texture was tinted) and frees the upload buffer. */
        void restore() {
            GpuTexture gpu = current();
            if (gpu != null && gpu == source && applied != 0 && !failed) upload(gpu, 0);
            applied = 0;
            if (buffer != null) { buffer.close(); buffer = null; }
        }

        /** The glint's GPU texture now, or null while it doesn't exist yet (mid-reload). */
        private GpuTexture current() {
            try {
                AbstractTexture tex = mc.getTextureManager().getTexture(id);
                return tex == null ? null : tex.getGlTexture();
            } catch (IllegalStateException e) {
                return null;
            }
        }

        /** A new GPU texture (first use or after a reload) holds the untinted pixels: read and keep them. */
        private void adopt(GpuTexture gpu) {
            source = gpu;
            applied = 0;
            failed = false;
            original = null;
            if (buffer != null) { buffer.close(); buffer = null; }
            try (var in = mc.getResourceManager().open(id); NativeImage img = NativeImage.read(in)) {
                if (img.getWidth() != gpu.getWidth(0) || img.getHeight() != gpu.getHeight(0)) {
                    EvadeClient.LOG.warn("EnchantGlint: {} is {}x{} but its texture is {}x{}; leaving it alone",
                            id, img.getWidth(), img.getHeight(), gpu.getWidth(0), gpu.getHeight(0));
                    failed = true;
                    return;
                }
                width = img.getWidth();
                height = img.getHeight();
                original = new int[width * height];
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) original[y * width + x] = img.getColorArgb(x, y);
            } catch (Exception e) {
                EvadeClient.LOG.warn("EnchantGlint: couldn't read {}", id, e);
                failed = true;
            }
        }

        /**
         * Writes the original pixels tinted to {@code argb} (0 = untinted) into the GPU texture. Each pixel keeps its
         * alpha and its brightness (the brightest channel), so the glint's pattern stays and only its hue changes.
         */
        private boolean upload(GpuTexture gpu, int argb) {
            try {
                if (buffer == null) buffer = new NativeImage(width, height, false);
                int tr = argb >> 16 & 255, tg = argb >> 8 & 255, tb = argb & 255;
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int p = original[y * width + x];
                    if (argb != 0) {
                        int a = p >>> 24, l = Math.max(p >> 16 & 255, Math.max(p >> 8 & 255, p & 255));
                        p = (a << 24) | (tr * l / 255 << 16) | (tg * l / 255 << 8) | (tb * l / 255);
                    }
                    buffer.setColorArgb(x, y, p);
                }
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(gpu, buffer);
                return true;
            } catch (Exception e) {
                EvadeClient.LOG.warn("EnchantGlint: couldn't write {}", id, e);
                failed = true;
                return false;
            }
        }
    }
}
