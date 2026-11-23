package net.evadeclient.module.mods;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.debug.DebugHudEntries;
import net.minecraft.client.option.AttackIndicator;
import net.minecraft.client.option.Perspective;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

/**
 * Replaces the vanilla crosshair with a configurable one that can change colour on targets.
 *
 * <p>It follows the vanilla rules for when a crosshair shows (spectators only see it over something they can open,
 * F3's 3D crosshair replaces it, and it hides while Freelook turns the camera away from where you aim) and keeps
 * the vanilla attack-cooldown indicator under it.
 */
public class Crosshair extends Module {
    private static final Identifier INDICATOR_FULL = Identifier.ofVanilla("hud/crosshair_attack_indicator_full");
    private static final Identifier INDICATOR_BACKGROUND = Identifier.ofVanilla("hud/crosshair_attack_indicator_background");
    private static final Identifier INDICATOR_PROGRESS = Identifier.ofVanilla("hud/crosshair_attack_indicator_progress");
    private static final int OUTLINE_COLOR = 0xC0000000;

    private final ModeSetting style = add(new ModeSetting("Style", "Cross", "Cross", "Dot", "Circle", "Cross + Dot", "T", "Custom"));
    public final CrosshairPattern.PatternSetting pattern = add(new CrosshairPattern.PatternSetting("Pattern",
            CrosshairPattern.preset("Cross + Dot"), "click: open editor")
            .describe("The Custom style's pixels. Click to draw your own, pick a preset or paste a code."));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFFFFFFFF));
    private final ColorSetting targetColor = add(new ColorSetting("Target Color", 0xFFE11D2E));
    private final BooleanSetting highlight = add(new BooleanSetting("Highlight Targets", true));
    private final NumberSetting size = add(new NumberSetting("Size", 5, 1, 15, 1));
    private final NumberSetting gap = add(new NumberSetting("Gap", 2, 0, 10, 1));
    private final NumberSetting thickness = add(new NumberSetting("Thickness", 1, 1, 4, 1));
    private final BooleanSetting outline = add(new BooleanSetting("Outline", true));
    private final BooleanSetting attackIndicator = add(new BooleanSetting("Show Attack Indicator", true)
            .describe("The vanilla attack-cooldown bar under the crosshair, when Attack Indicator is set to Crosshair in Video Settings."));
    private final BooleanSetting thirdPerson = add(new BooleanSetting("Show In 3rd Person", false));

    /** The shape for the current settings, rebuilt only when one of them changes. */
    private Shape shape;
    private String shapeStyle, shapeCode;
    private int shapeSize = -1, shapeGap = -1, shapeThickness = -1;
    private boolean shapeOutline;

    public Crosshair() {
        super("Crosshair", "Custom crosshair.", Category.MODS);
        pattern.onClick(() -> mc.setScreen(new net.evadeclient.gui.CrosshairEditorScreen(mc.currentScreen, this)));
    }

    // ------------------------------------------------------------------ HUD

    public void draw(DrawContext ctx) {
        if (mc.options.hudHidden || mc.player == null) return;
        boolean firstPerson = mc.options.getPerspective() == Perspective.FIRST_PERSON;
        if (!firstPerson && !thirdPerson.get()) return;
        // Spectators only get a crosshair over something they can open (vanilla's rule).
        if (mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR
                && !spectatorCanUse(mc.crosshairTarget)) return;
        // F3's 3D crosshair is drawn in the world instead (first person only, as vanilla).
        if (firstPerson && mc.debugHudEntryList.isEntryVisible(DebugHudEntries.THREE_DIMENSIONAL_CROSSHAIR)) return;
        // Freelook turns the camera away from where you aim, so a crosshair would point at the wrong thing.
        Freelook fl = EvadeClient.modules == null ? null : EvadeClient.modules.get(Freelook.class);
        if (fl != null && fl.active()) return;

        ctx.createNewRootLayer();
        int cx = ctx.getScaledWindowWidth() / 2, cy = ctx.getScaledWindowHeight() / 2;
        boolean onTarget = highlight.get() && mc.crosshairTarget instanceof EntityHitResult e && e.getEntity() instanceof LivingEntity;
        currentShape().draw(ctx, cx, cy, onTarget ? targetColor.get() : color.get());
        if (attackIndicator.get()) drawAttackIndicator(ctx);
    }

    /** Vanilla's shouldRenderSpectatorCrosshair: an entity or block with a screen to open. */
    private boolean spectatorCanUse(HitResult hit) {
        if (hit == null) return false;
        if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult e) {
            return e.getEntity() instanceof NamedScreenHandlerFactory;
        }
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult b && mc.world != null) {
            BlockPos pos = b.getBlockPos();
            return mc.world.getBlockState(pos).createScreenHandlerFactory(mc.world, pos) != null;
        }
        return false;
    }

    /** The vanilla crosshair attack indicator, exactly as vanilla draws it (Video Settings, Attack Indicator: Crosshair). */
    private void drawAttackIndicator(DrawContext ctx) {
        if (mc.options.getAttackIndicator().getValue() != AttackIndicator.CROSSHAIR) return;
        float progress = mc.player.getAttackCooldownProgress(0.0F);
        boolean full = false;
        if (mc.targetedEntity instanceof LivingEntity && progress >= 1.0F) {
            full = mc.player.getAttackCooldownProgressPerTick() > 5.0F && mc.targetedEntity.isAlive();
            AttackRangeComponent range = mc.player.getActiveOrMainHandStack().get(DataComponentTypes.ATTACK_RANGE);
            full &= range == null || (mc.crosshairTarget != null && range.isWithinRange(mc.player, mc.crosshairTarget.getPos()));
        }
        int x = ctx.getScaledWindowWidth() / 2 - 8, y = ctx.getScaledWindowHeight() / 2 - 7 + 16;
        if (full) {
            ctx.drawGuiTexture(RenderPipelines.CROSSHAIR, INDICATOR_FULL, x, y, 16, 16);
        } else if (progress < 1.0F) {
            int w = (int) (progress * 17.0F);
            ctx.drawGuiTexture(RenderPipelines.CROSSHAIR, INDICATOR_BACKGROUND, x, y, 16, 4);
            ctx.drawGuiTexture(RenderPipelines.CROSSHAIR, INDICATOR_PROGRESS, 16, 4, 0, 0, x, y, w, 4);
        }
    }

    // ------------------------------------------------------------------ shape

    private Shape currentShape() {
        String st = style.get();
        int s = size.getInt(), g = gap.getInt(), t = thickness.getInt();
        boolean ol = outline.get();
        String code = st.equals("Custom") ? pattern.code() : null;
        if (shape == null || !st.equals(shapeStyle) || s != shapeSize || g != shapeGap || t != shapeThickness
                || ol != shapeOutline || !java.util.Objects.equals(code, shapeCode)) {
            shape = Shape.build(st, s, g, t, ol, code == null ? null : pattern.pattern());
            shapeStyle = st; shapeSize = s; shapeGap = g; shapeThickness = t; shapeOutline = ol; shapeCode = code;
        }
        return shape;
    }

    /** The Custom style for {@code p} with the current Outline setting (the editor's previews). */
    public Shape customShape(CrosshairPattern p) {
        return Shape.build("Custom", size.getInt(), gap.getInt(), thickness.getInt(), outline.get(), p);
    }

    /** Colour of the crosshair when not over a target. */
    public int color() { return color.get(); }

    /** Whether the crosshair has its dark outline. */
    public boolean outlined() { return outline.get(); }

    /** Switch to the Custom style (the editor does this as soon as you change the pattern). */
    public boolean useCustomStyle() {
        if (style.is("Custom")) return false;
        style.set("Custom");
        return true;
    }

    /**
     * A crosshair rasterised once into rectangles, relative to the centre pixel: the outline (exactly the pixels
     * around the shape, so its translucent black never doubles up) and the shape itself. Nothing overlaps, so a
     * translucent colour is even too.
     */
    public record Shape(int[] fill, int[] outline) {
        /** Raster size; the centre pixel sits at (O, O). Big enough for the largest Cross (gap 10 + size 15 + thickness). */
        private static final int N = 72, O = N / 2;

        public void draw(DrawContext ctx, int cx, int cy, int color) {
            for (int i = 0; i < outline.length; i += 4)
                ctx.fill(cx + outline[i], cy + outline[i + 1], cx + outline[i + 2], cy + outline[i + 3], OUTLINE_COLOR);
            for (int i = 0; i < fill.length; i += 4)
                ctx.fill(cx + fill[i], cy + fill[i + 1], cx + fill[i + 2], cy + fill[i + 3], color);
        }

        static Shape build(String style, int s, int g, int t, boolean withOutline, CrosshairPattern pattern) {
            boolean[] m = new boolean[N * N];
            int h = t / 2;
            switch (style) {
                case "Dot" -> {
                    // Size sets the radius (Size 1-2 with thickness 1 is a single pixel); round from radius 2 up.
                    int r = (s <= 2 ? 0 : Math.max(1, s / 3)) + h;
                    for (int y = -r; y <= r; y++)
                        for (int x = -r; x <= r; x++)
                            if (x * x + y * y <= r * r + r) set(m, x, y);
                }
                case "Circle" -> {
                    // A ring of radius Size and width Thickness, every pixel exactly once.
                    double in = s - t / 2.0, out = s + t / 2.0;
                    int e = s + t;
                    for (int y = -e; y <= e; y++) {
                        for (int x = -e; x <= e; x++) {
                            double d = Math.sqrt(x * x + y * y);
                            if (d >= in && d < out) set(m, x, y);
                        }
                    }
                }
                case "Custom" -> {
                    if (pattern != null) {
                        int c = CrosshairPattern.CENTER;
                        for (int y = 0; y < CrosshairPattern.SIZE; y++)
                            for (int x = 0; x < CrosshairPattern.SIZE; x++)
                                if (pattern.get(x, y)) set(m, x - c, y - c);
                    }
                }
                default -> {
                    // Cross, Cross + Dot and T: arms Size long, Gap pixels clear of the centre pixel.
                    rect(m, -g - s, -h, -g, -h + t);
                    rect(m, g + 1, -h, g + 1 + s, -h + t);
                    if (!style.equals("T")) rect(m, -h, -g - s, -h + t, -g);
                    rect(m, -h, g + 1, -h + t, g + 1 + s);
                    // At Gap 0 the arms meet, so the centre belongs to them too (no hole in the middle).
                    if (g == 0 || style.equals("Cross + Dot")) rect(m, -h, -h, -h + t, -h + t);
                }
            }
            boolean[] ring = new boolean[N * N];
            if (withOutline) {
                for (int y = 1; y < N - 1; y++) {
                    for (int x = 1; x < N - 1; x++) {
                        int i = y * N + x;
                        if (m[i]) continue;
                        ring[i] = m[i - 1] || m[i + 1] || m[i - N] || m[i + N]
                                || m[i - N - 1] || m[i - N + 1] || m[i + N - 1] || m[i + N + 1];
                    }
                }
            }
            return new Shape(offset(CrosshairPattern.rects(m, N, N)), offset(CrosshairPattern.rects(ring, N, N)));
        }

        /** Raster coordinates to coordinates relative to the centre pixel. */
        private static int[] offset(int[] r) {
            for (int i = 0; i < r.length; i++) r[i] -= O;
            return r;
        }

        private static void set(boolean[] m, int x, int y) {
            x += O; y += O;
            // One pixel of margin all round, for the outline.
            if (x >= 1 && y >= 1 && x < N - 1 && y < N - 1) m[y * N + x] = true;
        }

        private static void rect(boolean[] m, int x1, int y1, int x2, int y2) {
            for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) set(m, x, y);
        }
    }
}
