package net.evadeclient.module.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * A horizontal compass strip showing your heading (or the heading of the player you're spectating, or of the
 * camera), with your waypoints, last death and the world spawn marked on it. Centred at the top by default, below
 * any boss bars; steps aside while the tab list is open.
 */
public class CompassHud extends HudElementModule {
    /** Labels from yaw 0 (south) going clockwise, as Minecraft's yaw does. */
    private static final String[] LABELS_4 = {"S", "W", "N", "E"};
    private static final String[] LABELS_8 = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final String[] LABELS_16 = {"S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE"};
    private static final int DEFAULT_Y = 2, STRIP = 13, READOUT = 9;
    private static final int SPAWN_COLOR = 0xFF3BE37A;
    /** Pixels per degree of older versions' strip at Zoom 1 (for moving Zoom onto Field Of View). */
    private static final double LEGACY_PX_PER_DEG = 1.6;

    private final NumberSetting stripWidth = add(new NumberSetting("Width", 180, 100, 400, 10)
            .describe("Width of the strip."));
    private final NumberSetting fov = add(new NumberSetting("Field Of View", 110, 60, 360, 10)
            .describe("How many degrees the strip shows across its width."));
    private final ModeSetting minorTicks = add(new ModeSetting("Minor Ticks", "15°", "Off", "5°", "10°", "15°")
            .describe("Small marks between the labels, every this many degrees."));
    private final BooleanSetting edgeFade = add(new BooleanSetting("Edge Fade", true)
            .describe("Fade the labels and marks out towards the ends of the strip."));
    private final ModeSetting points = add(new ModeSetting("Points", "8 Points", "4 Points", "8 Points", "16 Points")
            .describe("Which directions are labelled: N E S W, with NE SE SW NW, or with NNE ENE and so on too."));
    private final BooleanSetting degreeNumbers = add(new BooleanSetting("Degree Numbers", false)
            .describe("Small bearings (30, 60, ...) between the labels."));
    private final ColorSetting northColor = add(new ColorSetting("North Color", 0xFFFF4D5E)
            .describe("Colour of the N label."));
    private final ColorSetting labelColor = add(new ColorSetting("Label Color", 0xFFFFFFFF)
            .describe("Colour of the other labels (in-between directions a little darker)."));
    private final ColorSetting pointerColor = add(new ColorSetting("Pointer Color", 0xFFE11D2E)
            .describe("Colour of the mark at the centre of the strip."));
    private final ModeSetting readout = add(new ModeSetting("Readout", "Degrees", "Off", "Degrees", "Degrees + Point", "Point + Axis")
            .describe("Your heading as text: in degrees (not where the server hides coordinates), with its compass point, or the point and the axis you face."));
    private final ModeSetting readoutPosition = add(new ModeSetting("Readout Position", "Below", "Below", "Above")
            .describe("Show the readout under the strip or over it."));
    private final ModeSetting headingSource = add(new ModeSetting("Heading Source", "Player", "Player", "Camera")
            .describe("Follow where you (or the player you spectate) face, or where the camera looks, e.g. while using Freelook."));
    private final BooleanSetting waypointMarkers = add(new BooleanSetting("Waypoint Markers", true)
            .describe("Mark your waypoints on the strip (needs Waypoints on)."));
    private final BooleanSetting deathMarker = add(new BooleanSetting("Death Marker", true)
            .describe("Mark where you last died."));
    private final BooleanSetting spawnMarker = add(new BooleanSetting("Spawn Marker", false)
            .describe("Mark the world spawn, while you're in its dimension."));
    private final BooleanSetting markerDistance = add(new BooleanSetting("Marker Distance", true)
            .describe("Show each marker's distance next to it."));
    private final BooleanSetting pinOffScreen = add(new BooleanSetting("Pin Off-Screen", true)
            .describe("Keep markers outside the strip at its ends, pointing the way to turn."));

    public CompassHud() {
        super("Direction HUD", "Compass strip with your heading.", CENTER, DEFAULT_Y);
        width = 180;
        height = STRIP + READOUT;
        setEnabled(true);
    }

    /** At its default height the strip sits just below vanilla's boss bars while there are any. */
    @Override
    public int resolveY(int screenH) {
        if (y == DEFAULT_Y) return clampY(DEFAULT_Y + HudDraw.bossBarBottom(), screenH);
        return super.resolveY(screenH);
    }

    /** Hidden while the tab player list is open, if the strip sits in the top third where the list is drawn. */
    @Override
    public boolean shouldShow() {
        if (!super.shouldShow()) return false;
        if (mc.inGameHud != null && mc.inGameHud.getPlayerListHud() instanceof net.evadeclient.mixin.PlayerListHudAccessor list
                && list.evade$isVisible() && !(mc.currentScreen instanceof net.evadeclient.gui.HudEditorScreen)) {
            int sh = HudDraw.screenHeight();
            return sh <= 0 || resolveY(sh) >= sh / 3;
        }
        return true;
    }

    /**
     * Older builds had Zoom (pixels per degree), Show Degrees and Intercardinals. Map them onto Field Of View,
     * Readout and Points while those are still at their defaults (saving drops the old keys).
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (settings.has("Zoom") && !settings.has(fov.getName()) && fov.isDefault()) {
            double zoom = number(settings, "Zoom");
            if (zoom > 0) fov.set(stripWidth.get() / (LEGACY_PX_PER_DEG * zoom));
        }
        if (isFalse(settings, "Show Degrees") && !settings.has(readout.getName()) && readout.isDefault()) readout.set("Off");
        if (isFalse(settings, "Intercardinals") && !settings.has(points.getName()) && points.isDefault()) points.set("4 Points");
    }

    private static double number(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static boolean isFalse(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() && !e.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (cam == null || mc.world == null) { empty = true; return; }
        float td = mc.getRenderTickCounter().getTickProgress(true);
        boolean camera = headingSource.is("Camera");
        var view = mc.gameRenderer.getCamera();
        float yaw = MathHelper.wrapDegrees(camera ? view.getYaw() : cam.getYaw(td));
        Vec3d from = camera ? view.getCameraPos() : cam.getLerpedPos(td);

        boolean showReadout = !readout.is("Off");
        boolean above = showReadout && readoutPosition.is("Above");
        width = stripWidth.getInt();
        height = showReadout ? STRIP + READOUT : STRIP;
        int s = above ? top + READOUT : top; // top of the strip
        var tr = mc.textRenderer;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);

        float fovDeg = Math.max(1f, fov.getFloat());
        float half = fovDeg / 2f, pxPerDeg = width / fovDeg;
        int center = left + width / 2;
        int label = labelColor.get(), dim = Theme.lerpColor(label, 0xFF000000, 0.4f), faint = Theme.lerpColor(label, 0xFF000000, 0.6f);

        // Fewer labels when they'd run into each other on a narrow strip or a wide field of view.
        int n = points.is("16 Points") ? 16 : points.is("4 Points") ? 4 : 8;
        while (n > 4 && 360f / n * pxPerDeg < (n == 16 ? 24 : 16)) n /= 2;
        String[] labels = n == 16 ? LABELS_16 : n == 8 ? LABELS_8 : LABELS_4;
        float labelStep = 360f / n;
        // Room (px either side of a label's centre) that ticks and numbers keep clear of: half the widest label + 2.
        float labelClear = n == 16 ? 11 : n == 8 ? 8 : 6;
        boolean numbers = degreeNumbers.get() && 30 * pxPerDeg >= 12;

        ctx.enableScissor(left, s, left + width, s + STRIP);
        try {
            int step = switch (minorTicks.get()) {
                case "5°" -> 5;
                case "10°" -> 10;
                case "15°" -> 15;
                default -> 0;
            };
            while (step > 0 && step * pxPerDeg < 3) step = step * 2 > 45 ? 0 : step * 2;
            for (int deg = 0; step > 0 && deg < 360; deg += step) {
                if (labelGap(deg, labelStep, pxPerDeg) < labelClear || (numbers && deg % 30 == 0)) continue;
                float rel = MathHelper.wrapDegrees(deg - yaw);
                if (Math.abs(rel) > half) continue;
                int px = Math.round(center + rel * pxPerDeg);
                ctx.fill(px, s + 6, px + 1, s + 10, faded(faint, rel, half));
            }

            for (int i = 0; i < n; i++) {
                float rel = MathHelper.wrapDegrees(i * labelStep - yaw);
                if (Math.abs(rel) > half) continue;
                int px = Math.round(center + rel * pxPerDeg);
                String l = labels[i];
                int c = l.equals("N") ? northColor.get() : i % (n / 4) == 0 ? label : dim;
                Text t = styled(l);
                ctx.drawTextWithShadow(tr, t, px - tr.getWidth(t) / 2, s + 4, faded(c, rel, half));
            }

            if (numbers) {
                var m = ctx.getMatrices();
                for (int deg = 0; deg < 360; deg += 30) {
                    if (labelGap(deg, labelStep, pxPerDeg) < labelClear + 4) continue;
                    float rel = MathHelper.wrapDegrees(deg - yaw);
                    if (Math.abs(rel) > half) continue;
                    float px = center + rel * pxPerDeg;
                    Text t = styled(String.valueOf(Math.floorMod(deg + 180, 360)));
                    m.pushMatrix();
                    m.translate(px - tr.getWidth(t) * 0.25f, s + 6);
                    m.scale(0.5f, 0.5f);
                    ctx.drawTextWithShadow(tr, t, 0, 0, faded(dim, rel, half));
                    m.popMatrix();
                }
            }

            drawMarkers(ctx, tr, from, yaw, left, s, center, half, pxPerDeg);
        } finally {
            ctx.disableScissor();
        }

        ctx.fill(center, s + 1, center + 1, s + 4, pointerColor.get());
        if (showReadout) {
            String text = readoutText(yaw, mc.player != null && mc.player.hasReducedDebugInfo());
            Text t = styled(text);
            ctx.drawTextWithShadow(tr, t, center - tr.getWidth(t) / 2, above ? top + 2 : top + STRIP, dim);
        }
    }

    /** Pixels from {@code deg} to the nearest of the {@code labelStep}-apart labels (0 on a label). */
    private static float labelGap(float deg, float labelStep, float pxPerDeg) {
        float k = deg / labelStep;
        return Math.abs(k - Math.round(k)) * labelStep * pxPerDeg;
    }

    /** {@code color} faded towards the ends of the strip (Edge Fade). */
    private int faded(int color, float rel, float half) {
        if (!edgeFade.get() || half <= 0) return color;
        float f = rel / half;
        return Theme.alpha(color, 1f - 0.85f * f * f);
    }

    /** "247°", "247° SW" or "SW (-X)"; degrees give way to the point where the server hides coordinates. */
    private String readoutText(float yaw, boolean reduced) {
        int heading = Math.floorMod(Math.round(yaw + 180), 360);
        String point = LABELS_8[Math.floorMod(Math.round(yaw / 45f), 8)];
        return switch (readout.get()) {
            case "Degrees + Point" -> reduced ? point : heading + "° " + point;
            case "Point + Axis" -> point + " (" + axis(Direction.fromHorizontalDegrees(yaw)) + ")";
            default -> reduced ? point : heading + "°";
        };
    }

    private static String axis(Direction d) {
        return switch (d) {
            case NORTH -> "-Z";
            case SOUTH -> "+Z";
            case WEST -> "-X";
            default -> "+X";
        };
    }

    // ------------------------------------------------------------------ markers

    /**
     * Waypoints (from the Waypoints mod's per-tick list), your last death, the world spawn and your target, each
     * as a small triangle at the top of the strip. Never entities.
     */
    private void drawMarkers(DrawContext ctx, TextRenderer tr, Vec3d from, float yaw, int left, int s, int center,
                             float half, float pxPerDeg) {
        boolean wpOn = waypointMarkers.get(), deathOn = deathMarker.get();
        Waypoints wp = EvadeClient.modules == null ? null : EvadeClient.modules.get(Waypoints.class);
        boolean fromWaypoints = wp != null && wp.isEnabled() && wp.compassMarkers();
        Waypoints.Shown target = null;
        if (fromWaypoints) {
            for (Waypoints.Shown sh : wp.visible()) {
                if (sh.target()) { target = sh; continue; } // drawn last, on top
                if (Waypoints.isDeath(sh.point()) ? !deathOn : !wpOn) continue;
                int c = sh.converted() ? Theme.alpha(sh.color(), 0.6f) : sh.color();
                marker(ctx, tr, from, sh.x() + 0.5, sh.z() + 0.5, sh.distance(), yaw, left, s, center, half, pxPerDeg, c, null);
            }
        }
        // Where Waypoints keeps no death points, the death the server told the client about (as a recovery compass).
        if (deathOn && mc.player != null && (!fromWaypoints || !wp.recordsDeaths())) {
            GlobalPos death = mc.player.getLastDeathPos().orElse(null);
            if (death != null && death.dimension() == mc.world.getRegistryKey())
                marker(ctx, tr, from, death.pos().getX() + 0.5, death.pos().getZ() + 0.5, -1, yaw, left, s, center,
                        half, pxPerDeg, Waypoints.deathColor(), "Death");
        }
        if (spawnMarker.get()) {
            GlobalPos spawn = mc.world.getSpawnPoint().globalPos();
            if (spawn.dimension() == mc.world.getRegistryKey())
                marker(ctx, tr, from, spawn.pos().getX() + 0.5, spawn.pos().getZ() + 0.5, -1, yaw, left, s, center,
                        half, pxPerDeg, SPAWN_COLOR, "Spawn");
        }
        if (target != null) {
            int c = target.converted() ? Theme.alpha(target.color(), 0.6f) : target.color();
            marker(ctx, tr, from, target.x() + 0.5, target.z() + 0.5, target.distance(), yaw, left, s, center, half,
                    pxPerDeg, c, target.point().name());
        }
    }

    /**
     * One marker: a down-pointing triangle where the point's bearing is on the strip, or (Pin Off-Screen) a side
     * arrow at the end of the strip it is past. Beside it, on the side away from the pointer, its name (target,
     * death, spawn) and distance at half size. {@code dist} below 0 means "work it out from {@code from}".
     */
    private void marker(DrawContext ctx, TextRenderer tr, Vec3d from, double x, double z, double dist, float yaw,
                        int left, int s, int center, float half, float pxPerDeg, int color, String name) {
        double dx = x - from.x, dz = z - from.z;
        if (dx * dx + dz * dz < 0.25) return; // standing on it: no bearing to show
        float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = MathHelper.wrapDegrees(bearing - yaw);
        int px, side = 0;
        if (Math.abs(rel) <= half) {
            px = Math.round(center + rel * pxPerDeg);
            ctx.fill(px - 2, s + 1, px + 3, s + 2, color);
            ctx.fill(px - 1, s + 2, px + 2, s + 3, color);
            ctx.fill(px, s + 3, px + 1, s + 4, color);
        } else {
            if (!pinOffScreen.get()) return;
            side = rel > 0 ? 1 : -1;
            px = side > 0 ? left + width - 4 : left + 4;
            // Tip pointing out of the strip, towards the way to turn.
            ctx.fill(px - side, s + 1, px - side + 1, s + 6, color);
            ctx.fill(px, s + 2, px + 1, s + 5, color);
            ctx.fill(px + side, s + 3, px + side + 1, s + 4, color);
        }

        boolean showDist = markerDistance.get();
        if (name == null && !showDist) return;
        if (dist < 0) dist = Math.sqrt(dx * dx + dz * dz);
        String d = showDist ? distance(dist) : null;
        String text = name == null ? d : d == null ? name : name + " " + d;
        Text t = styled(text);
        float w = tr.getWidth(t) * 0.5f;
        boolean right = side != 0 ? side < 0 : px >= center;
        float tx = right ? px + 4 : px - 3 - w;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(tx, s + 1);
        m.scale(0.5f, 0.5f);
        ctx.drawTextWithShadow(tr, t, 0, 0, color | 0xFF000000);
        m.popMatrix();
    }

    /** "85m", "1.2km". */
    private static String distance(double blocks) {
        long m = Math.round(blocks);
        if (m < 1000) return m + "m";
        return String.format(java.util.Locale.ROOT, "%.1fkm", m / 1000.0);
    }
}
