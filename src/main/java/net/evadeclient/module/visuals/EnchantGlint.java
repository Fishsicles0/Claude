package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.awt.Color;

/**
 * Restyles the enchantment glint: a solid colour, a two-colour gradient or a rainbow, brighter or dimmer, faster or
 * slower, in a bigger or smaller pattern; worn armour can follow the items, have its own colour, keep vanilla's or
 * show no glint at all.
 *
 * The styled pixels are written straight into the existing glint textures on the GPU (render layers hold on to those
 * textures, so they must not be replaced). Each glint's original pixels are read once per GPU texture and kept, so
 * style changes and the rainbow never re-read the resource; a resource reload creates a new GPU texture, which is
 * noticed and re-read. A texture is only re-uploaded when what it should show changed. Speed and Scale change the
 * glint's scrolling texture matrix instead (TextureTransformMixin).
 */
public class EnchantGlint extends Module {
    /** The rainbow moves on every this many ticks (0.15 s): smooth enough for a slow cycle, cheap for the GPU. */
    private static final int RAINBOW_INTERVAL = 3;
    /** One rainbow cycle, in milliseconds. */
    private static final long RAINBOW_PERIOD = 4000;

    public final ModeSetting style = add(new ModeSetting("Style", "Solid", "Solid", "Gradient", "Rainbow")
            .describe("Solid: one colour. Gradient: Color blending into Second Color across the glint. Rainbow: every colour, slowly cycling."));
    public final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E)
            .describe("Glint colour on items: in your hand, in inventories and on the ground (Solid and Gradient)."));
    public final ColorSetting secondColor = add(new ColorSetting("Second Color", 0xFF3D7BFF)
            .describe("The other end of the Gradient style."));
    public final NumberSetting rainbowSaturation = add(new NumberSetting("Rainbow Saturation %", 80, 20, 100, 5)
            .describe("How vivid the Rainbow style's colours are: lower is more pastel."));
    public final NumberSetting intensity = add(new NumberSetting("Intensity %", 100, 25, 250, 25)
            .describe("Brightness of the glint's sheen (100 = as bright as the original)."));
    public final ModeSetting armorGlint = add(new ModeSetting("Armor Glint", "Same", "Same", "Custom", "Vanilla", "Hidden")
            .describe("Glint on worn armour: the same style as items, Armor Color, the game's own purple, or none at all."));
    public final ColorSetting armorColor = add(new ColorSetting("Armor Color", 0xFFA95CFF)
            .describe("Glint colour on armour worn by you and other players and mobs (Armor Glint: Custom)."));
    public final NumberSetting speed = add(new NumberSetting("Speed", 1.0, 0.0, 5.0, 0.1)
            .describe("How fast the glint pattern scrolls, on top of the game's Glint Speed option (0 = still)."));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.25, 4.0, 0.25)
            .describe("Size of the glint pattern (1 = vanilla, higher = bigger streaks)."));

    private static EnchantGlint instance;

    private final Glint item = new Glint(ItemRenderer.ITEM_ENCHANTMENT_GLINT);
    private final Glint armor = new Glint(ItemRenderer.ENTITY_ENCHANTMENT_GLINT);
    private float rainbowPhase = Float.NaN;
    private int rainbowTicks;

    public EnchantGlint() {
        super("EnchantGlint", "Restyle the enchantment glow: colour, gradient or rainbow, brightness, speed and size.", Category.VISUALS);
        instance = this;
    }

    /** True once the item glint shows a restyled version (for tests). */
    public boolean isApplied() { return item.applied != null && item.applied.kind != Look.ORIGINAL; }

    /** Textures only: safe on the title screen too, so a switch there is undone (or applied) right away. */
    @Override
    protected boolean alwaysRunHooks() { return true; }

    /** Speed for the glint's texture matrix: 1 while the module is off. Called while rendering, so a field read. */
    public static float speed() {
        EnchantGlint g = instance;
        return g != null && g.isEnabled() ? g.speed.getFloat() : 1f;
    }

    /** Scale for the glint's pattern: 1 while the module is off. */
    public static float patternScale() {
        EnchantGlint g = instance;
        if (g == null || !g.isEnabled()) return 1f;
        float s = g.scale.getFloat();
        return s > 0f ? s : 1f;
    }

    @Override
    public void onConfigLoaded(JsonObject settings) {
        // Before "Armor Color" existed, one colour was used for both glints.
        if (!settings.has(armorColor.getName()) && settings.has(color.getName())) armorColor.set(color.get());
        // Before "Style" and "Armor Glint" there was a "Rainbow" switch (cycling both glints) and armour always used
        // Armor Color. Saved configs always carry "Rainbow"; old share codes only when it was on.
        JsonElement oldRainbow = settings.get("Rainbow");
        if (oldRainbow == null && settings.has(style.getName())) return;
        boolean rainbow = isTrue(oldRainbow);
        if (rainbow && style.isDefault()) style.set("Rainbow");
        if (armorGlint.isDefault() && !rainbow && settings.has(armorColor.getName())
                && (armorColor.get() & 0xFFFFFF) != (color.get() & 0xFFFFFF)) {
            armorGlint.set("Custom");
        }
    }

    private static boolean isTrue(JsonElement e) {
        try {
            return e != null && e.isJsonPrimitive() && e.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    protected void onEnable() {
        rainbowPhase = Float.NaN;
        rainbowTicks = 0;
        if (texturesReady()) apply();
    }

    @Override
    protected void onDisable() {
        if (!texturesReady()) return;
        item.restore();
        armor.restore();
    }

    @Override
    public void onTick() {
        if (texturesReady()) apply();
    }

    /**
     * The texture manager and the GPU device exist, this is the render thread and no resource load is running (so
     * nothing here loads a glint texture early, and a reload's new textures are picked up once it is done).
     */
    private static boolean texturesReady() {
        return mc.getTextureManager() != null && mc.getOverlay() == null
                && RenderSystem.tryGetDevice() != null && RenderSystem.isOnRenderThread();
    }

    private void apply() {
        if (style.is("Rainbow") && (Float.isNaN(rainbowPhase) || ++rainbowTicks >= RAINBOW_INTERVAL)) {
            rainbowTicks = 0;
            rainbowPhase = (System.currentTimeMillis() % RAINBOW_PERIOD) / (float) RAINBOW_PERIOD;
        }
        Look itemLook = itemLook();
        Look armorLook = switch (armorGlint.get()) {
            case "Custom" -> new Look(Look.SOLID, armorColor.get(), 0, 0f, 0f, intensity.getInt());
            case "Vanilla" -> Look.UNTOUCHED;
            case "Hidden" -> Look.HIDDEN;
            default -> itemLook;
        };
        item.apply(itemLook);
        armor.apply(armorLook);
    }

    private Look itemLook() {
        int i = intensity.getInt();
        return switch (style.get()) {
            case "Gradient" -> new Look(Look.GRADIENT, color.get(), secondColor.get(), 0f, 0f, i);
            case "Rainbow" -> new Look(Look.RAINBOW, 0, 0, rainbowSaturation.getFloat() / 100f,
                    Float.isNaN(rainbowPhase) ? 0f : rainbowPhase, i);
            default -> new Look(Look.SOLID, color.get(), 0, 0f, 0f, i);
        };
    }

    /**
     * What a glint texture should show. Compared as a whole (record equality) against what was last uploaded, so a
     * texture is only re-uploaded when its own inputs changed.
     */
    private record Look(int kind, int color, int second, float saturation, float phase, int intensity) {
        static final int ORIGINAL = 0, SOLID = 1, GRADIENT = 2, RAINBOW = 3, BLACK = 4;
        static final Look UNTOUCHED = new Look(ORIGINAL, 0, 0, 0f, 0f, 100);
        static final Look HIDDEN = new Look(BLACK, 0, 0, 0f, 0f, 100);
    }

    /** One glint texture: its original pixels (per GPU texture), a reusable upload buffer, and what it shows now. */
    private static final class Glint {
        private final Identifier id;
        /** The GPU texture {@link #original} was read for; a different one means the resources were reloaded. */
        private GpuTexture source;
        private int[] original;
        private int width, height;
        private NativeImage buffer;
        /** What the texture shows now; null or ORIGINAL when it holds its original pixels. */
        Look applied;
        /** Set when the resource couldn't be used with {@link #source}, so it isn't retried every tick. */
        private boolean failed;

        Glint(Identifier id) { this.id = id; }

        /** Shows {@code look}, uploading only when it differs from what the texture already shows. */
        void apply(Look look) {
            GpuTexture gpu = current();
            if (gpu == null) return;
            if (gpu != source) adopt(gpu);
            if (failed) return;
            Look shown = applied == null ? Look.UNTOUCHED : applied;
            if (look.equals(shown)) return;
            if (upload(gpu, look)) applied = look;
        }

        /** Puts the original pixels back (if the texture was restyled) and frees the upload buffer. */
        void restore() {
            GpuTexture gpu = source == null ? null : current();
            if (gpu != null && gpu == source && applied != null && applied.kind != Look.ORIGINAL && !failed) {
                upload(gpu, Look.UNTOUCHED);
            }
            applied = null;
            if (buffer != null) { buffer.close(); buffer = null; }
        }

        /** The glint's GPU texture now, or null while it doesn't exist yet (startup, mid-reload). */
        private GpuTexture current() {
            try {
                TextureManager textures = mc.getTextureManager();
                AbstractTexture tex = textures == null ? null : textures.getTexture(id);
                return tex == null ? null : tex.getGlTexture();
            } catch (IllegalStateException e) {
                return null;
            }
        }

        /** A new GPU texture (first use or after a reload) holds the untouched pixels: read and keep them. */
        private void adopt(GpuTexture gpu) {
            source = gpu;
            applied = null;
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
         * Writes the original pixels restyled to {@code look} into the GPU texture. Each pixel keeps its alpha and its
         * brightness (the brightest channel, scaled by Intensity), so the glint's pattern stays and only its colour
         * changes. Gradient and Rainbow vary along the diagonal and wrap seamlessly, as the texture repeats.
         */
        private boolean upload(GpuTexture gpu, Look look) {
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device == null || original == null) return false;
            try {
                if (buffer == null) buffer = new NativeImage(width, height, false);
                int[] hues = look.kind == Look.RAINBOW ? hueTable(look.saturation) : null;
                int r1 = look.color >> 16 & 255, g1 = look.color >> 8 & 255, b1 = look.color & 255;
                int r2 = look.second >> 16 & 255, g2 = look.second >> 8 & 255, b2 = look.second & 255;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int p = original[y * width + x];
                        if (look.kind != Look.ORIGINAL) {
                            int a = p >>> 24;
                            int l = Math.max(p >> 16 & 255, Math.max(p >> 8 & 255, p & 255));
                            l = Math.min(255, l * look.intensity / 100);
                            int tr, tg, tb;
                            switch (look.kind) {
                                case Look.GRADIENT -> {
                                    // A triangle wave over the diagonal: Color at the edges, Second Color in the middle.
                                    float s = x / (float) width + y / (float) height;
                                    float f = s - (float) Math.floor(s);
                                    float t = 1f - Math.abs(2f * f - 1f);
                                    tr = Math.round(r1 + (r2 - r1) * t);
                                    tg = Math.round(g1 + (g2 - g1) * t);
                                    tb = Math.round(b1 + (b2 - b1) * t);
                                }
                                case Look.RAINBOW -> {
                                    float s = look.phase + x / (float) width + y / (float) height;
                                    int rgb = hues[(int) ((s - (float) Math.floor(s)) * hues.length) % hues.length];
                                    tr = rgb >> 16 & 255;
                                    tg = rgb >> 8 & 255;
                                    tb = rgb & 255;
                                }
                                case Look.BLACK -> { tr = 0; tg = 0; tb = 0; } // the glint adds its colour: black adds nothing
                                default -> { tr = r1; tg = g1; tb = b1; }
                            }
                            p = (a << 24) | (tr * l / 255 << 16) | (tg * l / 255 << 8) | (tb * l / 255);
                        }
                        buffer.setColorArgb(x, y, p);
                    }
                }
                device.createCommandEncoder().writeToTexture(gpu, buffer);
                return true;
            } catch (Exception e) {
                EvadeClient.LOG.warn("EnchantGlint: couldn't write {}", id, e);
                failed = true;
                return false;
            }
        }

        /** 256 rainbow colours at this saturation, full brightness. */
        private static int[] hueTable(float saturation) {
            int[] t = new int[256];
            for (int i = 0; i < t.length; i++) t[i] = Color.HSBtoRGB(i / (float) t.length, saturation, 1f) & 0xFFFFFF;
            return t;
        }
    }
}
