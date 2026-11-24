package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Projection;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.util.Util;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Shows a countdown above primed TNT (and primed TNT minecarts) that you can actually see: anything behind a wall is
 * left out, so it never tells you more than your own eyes do. A cart's fuse is only known roughly on the client, so
 * its time is shown as an estimate ("~3.2s").
 */
public class TntTimer extends Module {
    /** Vanilla's fuse for lit TNT and primed carts, in ticks (the bar's full length unless a longer fuse is seen). */
    private static final int DEFAULT_FUSE = 80;
    private static final int BAR_ONLY_WIDTH = 20;

    public final NumberSetting range = add(new NumberSetting("Range", 48, 8, 128, 4)
            .describe("How far away (in blocks) TNT gets a timer."));
    public final NumberSetting showBelow = add(new NumberSetting("Show Below", 0, 0, 10, 0.5)
            .describe("Only show a timer once this many seconds are left. 0 shows it for the whole fuse."));
    public final NumberSetting maxTimers = add(new NumberSetting("Max Timers", 0, 0, 32, 1)
            .describe("Show at most this many timers, nearest first. 0 is no limit."));
    public final BooleanSetting minecarts = add(new BooleanSetting("Minecarts", true)
            .describe("Also time primed TNT minecarts (an estimate, shown with ~)."));
    public final ModeSetting format = add(new ModeSetting("Format", "Seconds", "Seconds", "Ticks", "Bar", "Seconds+Bar")
            .describe("Count down in seconds, in game ticks (20 per second), as a shrinking bar, or seconds with a bar under them."));
    public final NumberSetting decimals = add(new NumberSetting("Decimals", 1, 0, 2, 1)
            .describe("Decimal places for seconds. 0 rounds up to whole seconds."));
    public final ModeSetting colorMode = add(new ModeSetting("Color Mode", "Gradient", "Gradient", "Threshold", "Static")
            .describe("Gradient fades from Safe Color to Danger Color, Threshold switches at Danger At, Static always uses Safe Color."));
    public final ColorSetting safeColor = add(new ColorSetting("Safe Color", 0xFF3BE37A)
            .describe("Colour while there is plenty of time left."));
    public final ColorSetting dangerColor = add(new ColorSetting("Danger Color", 0xFFE11D2E)
            .describe("Colour as the TNT is about to go off."));
    public final NumberSetting dangerAt = add(new NumberSetting("Danger At", 4.0, 0.5, 10, 0.5)
            .describe("Seconds left at which the colour turns fully to Danger Color."));
    public final NumberSetting flashBelow = add(new NumberSetting("Flash Below", 1.0, 0, 4, 0.5)
            .describe("Flash white in the last seconds of the fuse. 0 never flashes."));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1, 0.5, 3.0, 0.1)
            .describe("Size of the timer."));
    public final BooleanSetting distanceScaling = add(new BooleanSetting("Distance Scaling", false)
            .describe("Shrink timers on far-away TNT (down to 40%)."));
    public final NumberSetting heightOffset = add(new NumberSetting("Height Offset", 0.5, 0.0, 2.0, 0.1)
            .describe("How far above the TNT (in blocks) the timer floats."));
    public final BooleanSetting background = add(new BooleanSetting("Background", true)
            .describe("Draw a dark panel behind the timer."));
    public final NumberSetting backgroundOpacity = add(new NumberSetting("Background Opacity", 56, 0, 100, 5)
            .describe("How solid the panel behind the timer is, in percent."));
    public final BooleanSetting textShadow = add(new BooleanSetting("Text Shadow", true)
            .describe("Draw the countdown with a drop shadow."));

    /** Visible primed TNT in range, nearest first, rebuilt every tick. */
    private final List<Entity> tracked = new ArrayList<>();
    private final List<Entity> candidates = new ArrayList<>();
    /** The fuse each TNT had when first seen (at least {@link #DEFAULT_FUSE}), the bar's full length. */
    private final Int2IntOpenHashMap startFuse = new Int2IntOpenHashMap();
    private final IntOpenHashSet seen = new IntOpenHashSet();
    private ClientWorld trackedWorld;
    private int shown;

    public TntTimer() {
        super("TNT Timer", "Shows a countdown above primed TNT you can see.", Category.VISUALS);
        setEnabled(true);
    }

    /** How many timers were drawn last frame (for tests). */
    public int shown() { return shown; }

    @Override
    protected void onDisable() { clear(); }

    private void clear() {
        tracked.clear();
        candidates.clear();
        startFuse.clear();
        seen.clear();
        trackedWorld = null;
        shown = 0;
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;
        if (mc.world != trackedWorld) {
            clear();
            trackedWorld = mc.world;
        }
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double r2 = range.get() * range.get();
        double below = showBelow.get();
        candidates.clear();
        seen.clear();
        for (Entity e : mc.world.getEntities()) {
            int fuse = fuseOf(e);
            if (fuse < 0 || e.isRemoved()) continue;
            int id = e.getId();
            seen.add(id);
            if (!startFuse.containsKey(id)) startFuse.put(id, Math.max(DEFAULT_FUSE, fuse));
            if (e.squaredDistanceTo(mc.player) > r2) continue;
            // One tick of slack: the HUD counts down between ticks, so it may dip under the threshold before the next.
            if (below > 0 && (fuse - 1) / 20.0 > below) continue;
            candidates.add(e);
        }
        startFuse.keySet().removeIf(id -> !seen.contains(id));

        candidates.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(cam)));
        tracked.clear();
        int max = maxTimers.getInt();
        for (Entity e : candidates) {
            if (max > 0 && tracked.size() >= max) break;
            if (canSee(cam, e)) tracked.add(e);
        }
        candidates.clear();
    }

    /** The fuse of primed TNT or a primed cart (when Minecarts is on), or -1 for anything else. */
    private int fuseOf(Entity e) {
        if (e instanceof TntEntity tnt) return tnt.getFuse();
        if (e instanceof TntMinecartEntity cart && minecarts.get() && cart.isPrimed()) return cart.getFuseTicks();
        return -1;
    }

    /**
     * Whether nothing solid stands between the camera and the TNT's middle or its top. Uses the blocks' camera
     * shapes, so glass and panes (which you can see through) don't hide it.
     */
    private boolean canSee(Vec3d cam, Entity e) {
        Box b = e.getBoundingBox();
        Vec3d mid = b.getCenter();
        return clearLine(cam, mid) || clearLine(cam, new Vec3d(mid.x, b.maxY, mid.z));
    }

    private boolean clearLine(Vec3d from, Vec3d to) {
        return mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.VISUAL,
                RaycastContext.FluidHandling.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        shown = 0;
        if (mc.world == null || mc.player == null || mc.world != trackedWorld || tracked.isEmpty()) return;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double below = showBelow.get();
        boolean ticksFmt = format.is("Ticks"), barOnly = format.is("Bar");
        boolean bar = barOnly || format.is("Seconds+Bar");
        int bgAlpha = background.get() ? (int) Math.round(backgroundOpacity.get() * 2.55) : 0;
        // Far timers first, so nearer ones are drawn on top where they overlap.
        for (int i = tracked.size() - 1; i >= 0; i--) {
            Entity e = tracked.get(i);
            if (e.isRemoved()) continue;
            int fuse = fuseOf(e);
            if (fuse < 0) continue;
            float ticks = Math.max(0, fuse - tickDelta);
            float secs = ticks / 20f;
            if (below > 0 && secs > below) continue;

            Vec3d pos = e.getLerpedPos(tickDelta).add(0, e.getHeight() + heightOffset.get(), 0);
            double[] p = Projection.toScreen(pos);
            if (p == null) continue;
            float s = scale.getFloat();
            if (distanceScaling.get()) s *= (float) MathHelper.clamp(12 / Math.max(0.001, pos.distanceTo(cam)), 0.4, 1.0);

            boolean estimate = e instanceof TntMinecartEntity;
            String text = barOnly ? null : (estimate ? "~" : "") + (ticksFmt
                    ? (int) Math.ceil(ticks) + "t"
                    : seconds(secs, decimals.getInt()));
            int color = color(secs);

            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate((int) p[0], (int) p[1]); // whole pixels keep the text crisp at scale 1
            ctx.getMatrices().scale(s, s);
            if (text == null) {
                // Bar alone: a slim gauge centred on the anchor.
                int x = -BAR_ONLY_WIDTH / 2;
                if (bgAlpha > 0) ctx.fill(x - 3, -3, x + BAR_ONLY_WIDTH + 3, 3, bgAlpha << 24);
                drawBar(ctx, x, -1, BAR_ONLY_WIDTH, fraction(e, ticks), color);
            } else {
                int w = mc.textRenderer.getWidth(text);
                int x = -w / 2, y = -4;
                if (bgAlpha > 0) ctx.fill(x - 3, y - 2, x + w + 3, y + (bar ? 14 : 10), bgAlpha << 24);
                ctx.drawText(mc.textRenderer, text, x, y, color, textShadow.get());
                if (bar) drawBar(ctx, x, y + 10, w, fraction(e, ticks), color);
            }
            ctx.getMatrices().popMatrix();
            shown++;
        }
    }

    /** A 2 px tall gauge {@code width} wide, filled from the left to {@code frac}. */
    private static void drawBar(DrawContext ctx, int x, int y, int width, float frac, int color) {
        int filled = Math.round(width * frac);
        ctx.fill(x, y, x + width, y + 2, 0x40FFFFFF);
        if (filled > 0) ctx.fill(x, y, x + filled, y + 2, color);
    }

    /** How much of this TNT's fuse is left, 0 to 1. */
    private float fraction(Entity e, float ticks) {
        int start = Math.max(1, startFuse.getOrDefault(e.getId(), DEFAULT_FUSE));
        return MathHelper.clamp(ticks / start, 0f, 1f);
    }

    /** The label colour for {@code secs} left, flashing white at 4 Hz in the last Flash Below seconds. */
    private int color(float secs) {
        double flash = flashBelow.get();
        if (flash > 0 && secs <= flash && (Util.getMeasuringTimeMs() / 125) % 2 == 0) return 0xFFFFFFFF;
        int safe = safeColor.get() | 0xFF000000, danger = dangerColor.get() | 0xFF000000;
        float at = dangerAt.getFloat();
        if (colorMode.is("Static")) return safe;
        if (colorMode.is("Threshold")) return secs <= at ? danger : safe;
        return Theme.lerpColor(danger, safe, secs / at) | 0xFF000000;
    }

    /** Seconds left, rounded up so a live TNT never reads 0 ("4s" with no decimals, "3.2s" with one). */
    static String seconds(float secs, int decimals) {
        if (decimals <= 0) return (int) Math.ceil(secs - 1e-4) + "s";
        double f = Math.pow(10, Math.min(2, decimals));
        double v = Math.ceil(secs * f - 1e-4) / f;
        return String.format(Locale.ROOT, "%." + Math.min(2, decimals) + "f", v) + "s";
    }

    /** "Show Ticks" (on/off) became the Format mode. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (settings == null || settings.has(format.getName())) return;
        JsonElement old = settings.get("Show Ticks");
        if (old == null) return;
        try {
            if (old.getAsBoolean()) format.set("Ticks");
        } catch (RuntimeException ignored) {
            // unreadable: keep the default
        }
    }
}
