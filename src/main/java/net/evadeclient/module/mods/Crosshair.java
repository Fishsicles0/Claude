package net.evadeclient.module.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.hud.debug.DebugHudEntries;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.AttackIndicator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;

import java.util.Arrays;
import java.util.Objects;

/**
 * Replaces the vanilla crosshair with a configurable one that can change colour on targets.
 *
 * <p>It follows the vanilla rules for when a crosshair shows (spectators only see it over something they can open,
 * F3's 3D crosshair replaces it, and it hides while Freelook turns the camera away from where you aim) and draws your
 * own attack cooldown under it, as vanilla's indicator, a bar or a ring. Everything it shows is about you: your
 * cooldown, your bow charge, your speed.
 */
public class Crosshair extends Module {
    private static final Identifier INDICATOR_FULL = Identifier.ofVanilla("hud/crosshair_attack_indicator_full");
    private static final Identifier INDICATOR_BACKGROUND = Identifier.ofVanilla("hud/crosshair_attack_indicator_background");
    private static final Identifier INDICATOR_PROGRESS = Identifier.ofVanilla("hud/crosshair_attack_indicator_progress");
    private static final String INVERT = "Invert (Vanilla Blend)";
    /** Width of the attack indicator's Ring, in GUI pixels. */
    private static final float RING_WIDTH = 1.5f;

    private final ModeSetting style = add(new ModeSetting("Style", "Cross",
            "Cross", "Dot", "Circle", "Cross + Dot", "T", "X", "Square", "Arrow", "Circle + Dot", "Custom")
            .describe("The crosshair's shape. Custom uses the pixels you draw in the editor (Pattern)."));
    public final CrosshairPattern.PatternSetting pattern = add(new CrosshairPattern.PatternSetting("Pattern",
            CrosshairPattern.preset("Cross + Dot"), "click: open editor")
            .describe("The Custom style's pixels. Click to draw your own, pick a preset or paste a code."));
    private final ModeSetting colorMode = add(new ModeSetting("Color Mode", "Static", "Static", INVERT, "Chroma")
            .describe("Static uses Color. Invert flips what's behind it like the vanilla crosshair (always readable, no outline). Chroma cycles through the rainbow."));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFFFFFFFF));
    private final NumberSetting chromaSpeed = add(new NumberSetting("Chroma Speed", 1.0, 0.2, 5.0, 0.1)
            .describe("How fast Chroma cycles: 1 goes round the rainbow every 4 seconds."));
    private final ColorSetting targetColor = add(new ColorSetting("Target Color", 0xFFE11D2E));
    private final BooleanSetting highlight = add(new BooleanSetting("Highlight Targets", true)
            .describe("Use Target Color while you aim at a mob or player (not with Invert)."));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", 1.0, 0.1, 1.0, 0.05)
            .describe("How solid the crosshair, its outline and the Bar or Ring indicator are."));
    private final NumberSetting size = add(new NumberSetting("Size", 5, 1, 15, 1)
            .describe("Arm length, or the radius of Circle and Square."));
    private final NumberSetting gap = add(new NumberSetting("Gap", 2, 0, 10, 1)
            .describe("Empty pixels between the centre and the arms."));
    private final NumberSetting thickness = add(new NumberSetting("Thickness", 1, 1, 4, 1)
            .describe("Width of the arms and of the Square's border."));
    private final NumberSetting dotSize = add(new NumberSetting("Dot Size", 2, 1, 6, 1)
            .describe("The dot of Dot, Cross + Dot and Circle + Dot: 1 is a single pixel, each step adds a pixel all round."));
    private final NumberSetting circleThickness = add(new NumberSetting("Circle Thickness", 1, 1, 4, 0.5)
            .describe("Width of the ring of Circle and Circle + Dot."));
    private final NumberSetting rotation = add(new NumberSetting("Rotation", 0, 0, 90, 5)
            .describe("Turns the crosshair clockwise around its centre (45 makes a Cross an X)."));
    private final BooleanSetting outline = add(new BooleanSetting("Outline", true)
            .describe("A dark edge around the crosshair so it stays visible on bright backgrounds."));
    private final ColorSetting outlineColor = add(new ColorSetting("Outline Color", 0xC0000000));
    private final NumberSetting outlineThickness = add(new NumberSetting("Outline Thickness", 1, 1, 3, 1)
            .describe("How many pixels the Outline is wide (only while Outline is on)."));
    private final ModeSetting dynamicGap = add(new ModeSetting("Dynamic Gap", "Off", "Off", "Bow Charge", "Movement", "Both")
            .describe("Opens the crosshair while you draw a bow or crossbow (closing as it charges) or while you move."));
    private final NumberSetting dynamicStrength = add(new NumberSetting("Dynamic Strength", 4, 1, 12, 1)
            .describe("The most pixels Dynamic Gap adds."));
    private final NumberSetting dynamicSmoothing = add(new NumberSetting("Dynamic Smoothing", 10, 2, 30, 1)
            .describe("How quickly Dynamic Gap follows: higher is snappier."));
    private final ModeSetting attackIndicator = add(new ModeSetting("Attack Indicator", "Follow Vanilla",
            "Follow Vanilla", "Off", "Vanilla Texture", "Bar", "Ring")
            .describe("Your attack cooldown under the crosshair. Follow Vanilla shows vanilla's when Video Settings' Attack Indicator is Crosshair."));
    private final ColorSetting indicatorColor = add(new ColorSetting("Indicator Color", 0xFFFFFFFF));
    private final NumberSetting indicatorWidth = add(new NumberSetting("Indicator Width", 16, 8, 40, 1)
            .describe("Length of the Bar indicator."));
    private final NumberSetting indicatorOffset = add(new NumberSetting("Indicator Offset", 9, 4, 24, 1)
            .describe("How far below the centre the indicator sits; the Ring's radius."));
    private final BooleanSetting thirdPerson = add(new BooleanSetting("Show In 3rd Person", false));
    public final BooleanSetting hitMarker = add(new BooleanSetting("Hit Marker", false)
            .describe("A small X flashes around the crosshair when the server confirms a hit you landed (melee or projectile)."));
    private final ColorSetting hitMarkerColor = add(new ColorSetting("Hit Marker Color", 0xFFFFFFFF));
    private final NumberSetting hitMarkerTime = add(new NumberSetting("Hit Marker Time", 0.3, 0.1, 1.0, 0.05)
            .describe("Seconds the hit marker stays before fading out."));

    /** The raster for the current settings, rebuilt only when one of them changes. */
    private Shape shape;
    private Object shapeKey;

    /** Extra gap from Dynamic Gap, eased towards its target every frame. */
    private float dynamic;
    private long dynamicAt;

    public Crosshair() {
        super("Crosshair", "Custom crosshair.", Category.MODS);
        pattern.onClick(() -> mc.setScreen(new net.evadeclient.gui.CrosshairEditorScreen(mc.currentScreen, this)));
    }

    /**
     * Settings saved before Dot Size, Circle Thickness and the Attack Indicator mode existed: carry their look over.
     * Only those configs still have "Show Attack Indicator" (share codes leave defaults out, so a missing key alone
     * doesn't mean an old config).
     */
    @Override
    public void onConfigLoaded(JsonObject saved) {
        if (saved == null || !saved.has("Show Attack Indicator") || saved.has("Attack Indicator")) return;
        // "Show Attack Indicator" (on/off) became the Attack Indicator mode; on was Follow Vanilla.
        JsonElement old = saved.get("Show Attack Indicator");
        if (old.isJsonPrimitive() && old.getAsJsonPrimitive().isBoolean() && !old.getAsBoolean()) attackIndicator.set("Off");
        int s = size.getInt(), h = thickness.getInt() / 2;
        // Dot used to grow with Size and Thickness, Cross + Dot's dot was Thickness wide.
        if (!saved.has("Dot Size")) {
            if (style.is("Dot")) dotSize.set((double) ((s <= 2 ? 0 : Math.max(1, s / 3)) + h + 1));
            else if (style.is("Cross + Dot")) dotSize.set((double) (h + 1));
        }
        // Circle's ring was Thickness wide.
        if (!saved.has("Circle Thickness") && style.is("Circle")) circleThickness.set(thickness.get());
    }

    // ------------------------------------------------------------------ HUD

    /**
     * Draws the crosshair for this frame. Returns false when vanilla should draw this element instead (F3's 3D
     * crosshair is showing, so vanilla's own rules apply), true when it was handled here (drawn, or hidden on purpose).
     */
    public boolean draw(DrawContext ctx) {
        if (mc.options.hudHidden || mc.player == null) return true;
        boolean firstPerson = mc.options.getPerspective().isFirstPerson();
        // F3's 3D crosshair is drawn in the world instead (first person only), exactly as vanilla decides.
        if (firstPerson && mc.debugHudEntryList.isEntryVisible(DebugHudEntries.THREE_DIMENSIONAL_CROSSHAIR)) return false;
        if (!firstPerson && !thirdPerson.get()) return true;
        // Spectators only get a crosshair over something they can open (vanilla's rule).
        if (mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR
                && !spectatorCanUse(mc.crosshairTarget)) return true;
        // Freelook turns the camera away from where you aim, so a crosshair would point at the wrong thing.
        Freelook fl = EvadeClient.modules == null ? null : EvadeClient.modules.get(Freelook.class);
        if (fl != null && fl.active()) return true;

        float extra = dynamicGap();
        ctx.createNewRootLayer();
        int cx = ctx.getScaledWindowWidth() / 2, cy = ctx.getScaledWindowHeight() / 2;
        float op = opacity.getFloat();
        boolean invert = colorMode.is(INVERT);
        RenderPipeline pipeline;
        int fill, edge;
        if (invert) {
            // Vanilla's blend: white flips what's behind it; grey flips it partly, which is how Opacity works here.
            int g = Math.round(255 * op);
            fill = 0xFF000000 | g << 16 | g << 8 | g;
            edge = 0;
            pipeline = RenderPipelines.GUI_INVERT;
        } else {
            boolean onTarget = highlight.get() && mc.crosshairTarget instanceof EntityHitResult e && e.getEntity() instanceof LivingEntity;
            int base = onTarget ? targetColor.get() : colorMode.is("Chroma") ? chroma(color.get()) : color.get();
            fill = withOpacity(base, op);
            edge = outline.get() ? withOpacity(outlineColor.get(), op) : 0;
            pipeline = RenderPipelines.GUI;
        }

        Matrix3x2fStack m = ctx.getMatrices();
        float rot = rotation.getFloat();
        if (rot != 0) {
            m.pushMatrix();
            m.translate(cx + 0.5f, cy + 0.5f);
            m.rotate((float) Math.toRadians(rot));
            m.translate(-(cx + 0.5f), -(cy + 0.5f));
        }
        try {
            Shape sh = currentShape(Math.round(extra));
            Batch out = new Batch(), in = new Batch();
            sh.addTo(out, in, cx, cy);
            String st = style.get();
            if (st.equals("Circle") || st.equals("Circle + Dot")) {
                float r = size.getFloat() + extra, t = circleThickness.getFloat();
                float rIn = Math.max(0, r - t / 2), rOut = r + t / 2;
                in.arc(cx + 0.5f, cy + 0.5f, rIn, rOut, 0, 360);
                if (outline.get()) {
                    int ot = outlineThickness.getInt();
                    if (rIn > 0) out.arc(cx + 0.5f, cy + 0.5f, Math.max(0, rIn - ot), rIn, 0, 360);
                    out.arc(cx + 0.5f, cy + 0.5f, rOut, rOut + ot, 0, 360);
                }
            }
            if (!invert) out.submit(ctx, RenderPipelines.GUI, edge);
            in.submit(ctx, pipeline, fill);
        } finally {
            if (rot != 0) m.popMatrix();
        }
        drawAttackIndicator(ctx, cx, cy, op);
        drawHitMarker(ctx, cx, cy, op);
        return true;
    }

    /**
     * Hit Marker: four short diagonal strokes around the centre after a confirmed hit (the server named you as the
     * cause in its damage event; see HitConfirm), fading out over Hit Marker Time.
     */
    private void drawHitMarker(DrawContext ctx, int cx, int cy, float op) {
        if (!hitMarker.get()) return;
        long life = Math.round(hitMarkerTime.get() * 1000);
        long since = net.evadeclient.util.HitConfirm.sinceLastHit();
        if (since > life) return;
        float a = 1f - since / (float) life;
        int c = withOpacity(hitMarkerColor.get(), op * a);
        float x = cx + 0.5f, y = cy + 0.5f, in = 3.5f + 2f * (1 - a), out = in + 4f, w = 0.75f;
        Batch b = new Batch();
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sy = -1; sy <= 1; sy += 2) {
                float ax = x + sx * in, ay = y + sy * in, bx = x + sx * out, by = y + sy * out;
                // A stroke along the diagonal, w wide on each side of it.
                float nx = -sy * w, ny = sx * w;
                b.quad(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, ax - nx, ay - ny);
            }
        b.submit(ctx, RenderPipelines.GUI, c);
    }

    /** Test hook: whether a hit marker would be drawn this frame. */
    public boolean hitMarkerShowing() {
        return hitMarker.get() && net.evadeclient.util.HitConfirm.sinceLastHit() <= Math.round(hitMarkerTime.get() * 1000);
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

    // ------------------------------------------------------------------ colour

    /** {@code argb} with its alpha multiplied by {@code opacity}. */
    private static int withOpacity(int argb, float opacity) {
        int a = Math.round((argb >>> 24) * Math.max(0, Math.min(1, opacity)));
        return a << 24 | (argb & 0xFFFFFF);
    }

    /** The rainbow colour for now, keeping {@code argb}'s alpha. */
    private int chroma(int argb) {
        double hue = (System.currentTimeMillis() / 4000.0 * chromaSpeed.get()) % 1.0;
        return (argb & 0xFF000000) | (java.awt.Color.HSBtoRGB((float) hue, 0.65f, 1f) & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ dynamic gap

    /** The extra gap for this frame: eased towards your bow charge and/or speed. Only your own player is read. */
    private float dynamicGap() {
        long now = System.nanoTime();
        float dt = dynamicAt == 0 ? 0 : Math.min(0.25f, (now - dynamicAt) / 1_000_000_000f);
        dynamicAt = now;
        String mode = dynamicGap.get();
        if (mode.equals("Off")) return dynamic = 0;
        ClientPlayerEntity p = mc.player;
        float target = 0;
        if (p != null) {
            float strength = dynamicStrength.getFloat();
            if (!mode.equals("Movement")) target = Math.max(target, strength * (1 - chargeProgress(p)));
            if (!mode.equals("Bow Charge")) {
                double speed = p.getVelocity().horizontalLength() / 0.28;
                target = Math.max(target, strength * (float) Math.min(1, speed));
            }
        }
        float k = 1 - (float) Math.exp(-dynamicSmoothing.get() * dt);
        dynamic += (target - dynamic) * k;
        if (Math.abs(target - dynamic) < 0.01f) dynamic = target;
        return dynamic;
    }

    /**
     * How charged the bow or crossbow you're drawing is (1 when not drawing one, so it adds no gap): the crosshair
     * opens when you start drawing and closes as the shot gets ready. A loaded crossbow adds nothing.
     */
    private static float chargeProgress(ClientPlayerEntity p) {
        if (!p.isUsingItem()) return 1;
        ItemStack active = p.getActiveItem();
        if (active.getItem() instanceof BowItem) return BowItem.getPullProgress(p.getItemUseTime());
        if (active.getItem() instanceof CrossbowItem) {
            if (CrossbowItem.isCharged(active)) return 1;
            int pull = CrossbowItem.getPullTime(active, p);
            return pull <= 0 ? 1 : Math.min(1f, p.getItemUseTime() / (float) pull);
        }
        return 1;
    }

    // ------------------------------------------------------------------ attack indicator

    /** Your own attack cooldown, in the Attack Indicator style. */
    private void drawAttackIndicator(DrawContext ctx, int cx, int cy, float op) {
        String mode = attackIndicator.get();
        if (mode.equals("Off")) return;
        if (mode.equals("Follow Vanilla") && mc.options.getAttackIndicator().getValue() != AttackIndicator.CROSSHAIR) return;
        float progress = mc.player.getAttackCooldownProgress(0.0F);
        boolean full = attackReady(progress);
        if (!full && progress >= 1.0F) return; // vanilla shows nothing once charged unless you aim at something
        int offset = indicatorOffset.getInt();
        switch (mode) {
            case "Bar" -> {
                int w = indicatorWidth.getInt(), x = cx - w / 2, y = cy + offset;
                int filled = full ? w : Math.round(progress * w);
                int c = withOpacity(indicatorColor.get(), op);
                Batch out = new Batch(), track = new Batch(), bar = new Batch();
                if (outline.get()) {
                    int o = outlineThickness.getInt();
                    out.frame(x - o, y - o, x + w + o, y + 2 + o, o);
                }
                if (filled < w) track.rect(x + filled, y, x + w, y + 2);
                if (filled > 0) bar.rect(x, y, x + filled, y + 2);
                out.submit(ctx, RenderPipelines.GUI, withOpacity(outlineColor.get(), op));
                track.submit(ctx, RenderPipelines.GUI, withOpacity(c, 0.3f));
                bar.submit(ctx, RenderPipelines.GUI, c);
            }
            case "Ring" -> {
                float px = cx + 0.5f, py = cy + 0.5f, rIn = offset, rOut = offset + RING_WIDTH;
                float sweep = full ? 360 : progress * 360;
                int c = withOpacity(indicatorColor.get(), op);
                Batch out = new Batch(), track = new Batch(), ring = new Batch();
                if (outline.get()) {
                    int o = outlineThickness.getInt();
                    out.arc(px, py, Math.max(0, rIn - o), rIn, 0, 360);
                    out.arc(px, py, rOut, rOut + o, 0, 360);
                }
                if (sweep < 360) track.arc(px, py, rIn, rOut, sweep, 360 - sweep);
                if (sweep > 0) ring.arc(px, py, rIn, rOut, 0, sweep);
                out.submit(ctx, RenderPipelines.GUI, withOpacity(outlineColor.get(), op));
                track.submit(ctx, RenderPipelines.GUI, withOpacity(c, 0.3f));
                ring.submit(ctx, RenderPipelines.GUI, c);
            }
            default -> {
                // Follow Vanilla and Vanilla Texture: vanilla's sprites, drawn exactly as vanilla does.
                int x = ctx.getScaledWindowWidth() / 2 - 8, y = ctx.getScaledWindowHeight() / 2 + offset;
                if (full) {
                    ctx.drawGuiTexture(RenderPipelines.CROSSHAIR, INDICATOR_FULL, x, y, 16, 16);
                } else {
                    ctx.drawGuiTexture(RenderPipelines.CROSSHAIR, INDICATOR_BACKGROUND, x, y, 16, 4);
                    ctx.drawGuiTexture(RenderPipelines.CROSSHAIR, INDICATOR_PROGRESS, 16, 4, 0, 0, x, y, (int) (progress * 17.0F), 4);
                }
            }
        }
    }

    /** Vanilla's "full" indicator: charged, aiming at a living thing in reach (InGameHud.renderCrosshair). */
    private boolean attackReady(float progress) {
        if (!(mc.targetedEntity instanceof LivingEntity) || progress < 1.0F) return false;
        boolean full = mc.player.getAttackCooldownProgressPerTick() > 5.0F && mc.targetedEntity.isAlive();
        AttackRangeComponent range = mc.player.getActiveOrMainHandStack().get(DataComponentTypes.ATTACK_RANGE);
        return full && (range == null || (mc.crosshairTarget != null && range.isWithinRange(mc.player, mc.crosshairTarget.getPos())));
    }

    // ------------------------------------------------------------------ shape

    private Shape currentShape(int extraGap) {
        String st = style.get();
        int s = size.getInt(), g = gap.getInt(), t = thickness.getInt(), dot = dotSize.getInt(), ot = outlineThickness.getInt();
        boolean ol = outline.get();
        String code = st.equals("Custom") ? pattern.code() : null;
        Object key = Arrays.asList(st, s, g + extraGap, t, dot, ol, ot, outlineColor.get(), code);
        if (shape == null || !Objects.equals(key, shapeKey)) {
            // Circle and Square grow with the dynamic gap; the others move their arms out.
            boolean grows = st.equals("Square");
            shape = Shape.build(st, grows ? s + extraGap : s, grows ? g : g + extraGap, t, dot, ol, ot,
                    outlineColor.get(), code == null ? null : pattern.pattern());
            shapeKey = key;
        }
        return shape;
    }

    /** The Custom style for {@code p} with the current Outline settings (the editor's previews). */
    public Shape customShape(CrosshairPattern p) {
        return Shape.build("Custom", size.getInt(), gap.getInt(), thickness.getInt(), dotSize.getInt(), outline.get(),
                outlineThickness.getInt(), outlineColor.get(), p);
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
     * within Outline Thickness of the shape, so its translucent colour never doubles up) and the shape itself.
     * Nothing overlaps, so a translucent colour is even too. Circle rings are drawn as smooth geometry instead
     * (see {@link Batch#arc}).
     */
    public record Shape(int[] fill, int[] outline, int outlineColor) {
        /** Raster size; the centre pixel sits at (O, O). Big enough for the largest Cross plus the largest dynamic gap. */
        private static final int N = 96, O = N / 2;
        /** Margin kept clear on every side, so the thickest outline is never cut off. */
        private static final int MARGIN = 3;

        /** Draws it in {@code color} with its outline colour, centred on the pixel at (cx, cy). */
        public void draw(DrawContext ctx, int cx, int cy, int color) {
            Batch out = new Batch(), in = new Batch();
            addTo(out, in, cx, cy);
            out.submit(ctx, RenderPipelines.GUI, outlineColor);
            in.submit(ctx, RenderPipelines.GUI, color);
        }

        void addTo(Batch out, Batch in, int cx, int cy) {
            for (int i = 0; i < outline.length; i += 4)
                out.rect(cx + outline[i], cy + outline[i + 1], cx + outline[i + 2], cy + outline[i + 3]);
            for (int i = 0; i < fill.length; i += 4)
                in.rect(cx + fill[i], cy + fill[i + 1], cx + fill[i + 2], cy + fill[i + 3]);
        }

        static Shape build(String style, int s, int g, int t, int dot, boolean withOutline, int ot, int outlineColor,
                           CrosshairPattern pattern) {
            boolean[] m = new boolean[N * N];
            int h = t / 2;
            switch (style) {
                case "Dot", "Circle + Dot" -> disc(m, dot - 1);
                case "Circle" -> { } // the ring is geometry, drawn by the module
                case "Custom" -> {
                    if (pattern != null) {
                        int c = CrosshairPattern.CENTER;
                        for (int y = 0; y < CrosshairPattern.SIZE; y++)
                            for (int x = 0; x < CrosshairPattern.SIZE; x++)
                                if (pattern.get(x, y)) set(m, x - c, y - c);
                    }
                }
                case "X" -> {
                    // Four diagonal arms, Size long, Gap pixels clear of the centre; Thickness widens them.
                    for (int i = g + 1; i <= g + s; i++) {
                        for (int k = -h; k < -h + t; k++) {
                            set(m, i, i + k); set(m, -i, i + k);
                            set(m, i, -i + k); set(m, -i, -i + k);
                        }
                    }
                    if (g == 0) rect(m, -h, -h, -h + t, -h + t);
                }
                case "Square" -> {
                    // A hollow square Size from the centre, with a Thickness-wide border.
                    for (int y = -s; y <= s; y++)
                        for (int x = -s; x <= s; x++)
                            if (Math.max(Math.abs(x), Math.abs(y)) > s - t) set(m, x, y);
                }
                case "Arrow" -> {
                    // A chevron pointing up at the centre: two arms going down and out, Gap pixels clear of it.
                    for (int i = g + 1; i <= g + s; i++) {
                        for (int k = 0; k < t; k++) { set(m, i, i + k); set(m, -i, i + k); }
                    }
                    if (g == 0) rect(m, -h, -h, -h + t, -h + t);
                }
                default -> {
                    // Cross, Cross + Dot and T: arms Size long, Gap pixels clear of the centre pixel.
                    rect(m, -g - s, -h, -g, -h + t);
                    rect(m, g + 1, -h, g + 1 + s, -h + t);
                    if (!style.equals("T")) rect(m, -h, -g - s, -h + t, -g);
                    rect(m, -h, g + 1, -h + t, g + 1 + s);
                    // At Gap 0 the arms meet, so the centre belongs to them too (no hole in the middle).
                    if (g == 0) rect(m, -h, -h, -h + t, -h + t);
                    if (style.equals("Cross + Dot")) disc(m, dot - 1);
                }
            }
            boolean[] ring = withOutline ? dilate(m, Math.max(1, Math.min(MARGIN, ot))) : new boolean[N * N];
            return new Shape(offset(CrosshairPattern.rects(m, N, N)), offset(CrosshairPattern.rects(ring, N, N)), outlineColor);
        }

        /** The pixels within {@code d} (8-way) of the shape that aren't part of it. */
        private static boolean[] dilate(boolean[] m, int d) {
            boolean[] across = new boolean[N * N], out = new boolean[N * N];
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    boolean any = false;
                    for (int k = Math.max(0, x - d); k <= Math.min(N - 1, x + d) && !any; k++) any = m[y * N + k];
                    across[y * N + x] = any;
                }
            }
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    int i = y * N + x;
                    if (m[i]) continue;
                    boolean any = false;
                    for (int k = Math.max(0, y - d); k <= Math.min(N - 1, y + d) && !any; k++) any = across[k * N + x];
                    out[i] = any;
                }
            }
            return out;
        }

        /** A round dot of radius {@code r} (0 is the centre pixel alone, 1 is 3 x 3). */
        private static void disc(boolean[] m, int r) {
            r = Math.max(0, r);
            for (int y = -r; y <= r; y++)
                for (int x = -r; x <= r; x++)
                    if (x * x + y * y <= r * r + r) set(m, x, y);
        }

        /** Raster coordinates to coordinates relative to the centre pixel. */
        private static int[] offset(int[] r) {
            for (int i = 0; i < r.length; i++) r[i] -= O;
            return r;
        }

        private static void set(boolean[] m, int x, int y) {
            x += O; y += O;
            if (x >= MARGIN && y >= MARGIN && x < N - MARGIN && y < N - MARGIN) m[y * N + x] = true;
        }

        private static void rect(boolean[] m, int x1, int y1, int x2, int y2) {
            for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) set(m, x, y);
        }
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Quads collected for one GUI element in one colour: rectangles and ring segments, sent to the GUI renderer as
     * a single element so a smooth ring costs one entry, not one per segment.
     */
    static final class Batch {
        private float[] v = new float[64];
        private int n;

        void rect(float x0, float y0, float x1, float y1) {
            if (x1 <= x0 || y1 <= y0) return;
            quad(x0, y0, x0, y1, x1, y1, x1, y0);
        }

        /** A rectangular frame {@code w} wide just inside (x0, y0)-(x1, y1), as four non-overlapping strips. */
        void frame(float x0, float y0, float x1, float y1, float w) {
            rect(x0, y0, x1, y0 + w);
            rect(x0, y1 - w, x1, y1);
            rect(x0, y0 + w, x0 + w, y1 - w);
            rect(x1 - w, y0 + w, x1, y1 - w);
        }

        /**
         * The part of the ring between radii {@code rIn} and {@code rOut} around (cx, cy) from {@code start}
         * degrees (0 is straight up) clockwise through {@code sweep} degrees. At least 64 segments for a full ring
         * wider than 8 pixels, fewer for small ones.
         */
        void arc(float cx, float cy, float rIn, float rOut, float start, float sweep) {
            if (rOut <= rIn || sweep <= 0) return;
            sweep = Math.min(360, sweep);
            int full = rOut > 8 ? Math.min(160, Math.max(64, (int) Math.ceil(rOut * 6))) : Math.max(24, (int) Math.ceil(rOut * 6));
            int segs = Math.max(1, (int) Math.ceil(full * sweep / 360f));
            for (int i = 0; i < segs; i++) {
                double a0 = Math.toRadians(start + sweep * i / segs), a1 = Math.toRadians(start + sweep * (i + 1) / segs);
                float s0 = (float) Math.sin(a0), c0 = (float) -Math.cos(a0), s1 = (float) Math.sin(a1), c1 = (float) -Math.cos(a1);
                quad(cx + s0 * rIn, cy + c0 * rIn, cx + s0 * rOut, cy + c0 * rOut,
                        cx + s1 * rOut, cy + c1 * rOut, cx + s1 * rIn, cy + c1 * rIn);
            }
        }

        /** One quad, turned to the same winding as vanilla's fill so the GUI pipeline's culling keeps it. */
        void quad(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
            float area = (ax * by - bx * ay) + (bx * cy - cx * by) + (cx * dy - dx * cy) + (dx * ay - ax * dy);
            if (n + 8 > v.length) v = Arrays.copyOf(v, v.length * 2);
            if (area > 0) {
                v[n++] = ax; v[n++] = ay; v[n++] = dx; v[n++] = dy; v[n++] = cx; v[n++] = cy; v[n++] = bx; v[n++] = by;
            } else {
                v[n++] = ax; v[n++] = ay; v[n++] = bx; v[n++] = by; v[n++] = cx; v[n++] = cy; v[n++] = dx; v[n++] = dy;
            }
        }

        /** Adds the quads to the GUI as one element with the current transform and scissor. */
        void submit(DrawContext ctx, RenderPipeline pipeline, int color) {
            if (n == 0 || (color >>> 24) == 0) return;
            Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices());
            Vector2f p = new Vector2f();
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i += 2) {
                pose.transformPosition(v[i], v[i + 1], p);
                minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
                maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
            }
            int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY);
            ScreenRect bounds = new ScreenRect(x0, y0, Math.max(1, (int) Math.ceil(maxX) - x0), Math.max(1, (int) Math.ceil(maxY) - y0));
            ScreenRect scissor = ctx.scissorStack.peekLast();
            if (scissor != null) {
                bounds = scissor.intersection(bounds);
                if (bounds == null) return;
            }
            ctx.state.addSimpleElement(new Quads(pipeline, pose, Arrays.copyOf(v, n), color, scissor, bounds));
        }
    }

    /** Any number of single-colour quads as one GUI element (vanilla's fill draws exactly one). */
    private record Quads(RenderPipeline pipeline, Matrix3x2fc pose, float[] v, int color,
                         ScreenRect scissorArea, ScreenRect bounds) implements SimpleGuiElementRenderState {
        @Override
        public TextureSetup textureSetup() { return TextureSetup.empty(); }

        @Override
        public void setupVertices(VertexConsumer vertices) {
            for (int i = 0; i + 1 < v.length; i += 2) vertices.vertex(pose, v[i], v[i + 1]).color(color);
        }
    }
}
