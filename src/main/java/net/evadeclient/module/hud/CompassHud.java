package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal compass strip showing your heading (or the heading of the player you're spectating), with your
 * waypoints marked on it. Centred at the top by default, below any boss bars; steps aside while the tab list is open.
 */
public class CompassHud extends HudElementModule {
    private static final String[] LABELS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final int DEFAULT_Y = 2, BASE_HEIGHT = 22;
    private static final float BASE_PX_PER_DEG = 1.6f;

    private final NumberSetting stripWidth = add(new NumberSetting("Width", 180, 120, 300, 10));
    private final NumberSetting zoom = add(new NumberSetting("Zoom", 1.0, 1.0, 3.0, 0.1)
            .describe("Spread the strip out: higher shows fewer degrees across the same width."));
    private final BooleanSetting showDegrees = add(new BooleanSetting("Show Degrees", true)
            .describe("Your exact heading under the strip (hidden where the server hides coordinates)."));
    private final BooleanSetting intercardinals = add(new BooleanSetting("Intercardinals", true)
            .describe("Label NE, SE, SW and NW as well as N, E, S and W."));
    private final BooleanSetting waypointMarkers = add(new BooleanSetting("Waypoint Markers", true)
            .describe("Mark your waypoints in this dimension on the strip, with their distance."));

    private record Marker(String name, double x, double y, double z, int color) {}

    /** This dimension's waypoints, refreshed once a tick (the list is read from the Waypoints mod). */
    private List<Marker> markers = List.of();
    private long markersTick = -1;

    public CompassHud() {
        super("Direction HUD", "Compass strip with your heading.", CENTER, DEFAULT_Y);
        width = 180;
        height = BASE_HEIGHT;
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

    private void refreshMarkers() {
        long tick = EvadeClient.ticks;
        if (tick == markersTick) return;
        markersTick = tick;
        markers = List.of();
        if (!waypointMarkers.get() || EvadeClient.modules == null || mc.world == null) return;
        Waypoints wp = EvadeClient.modules.get(Waypoints.class);
        if (wp == null || !wp.isEnabled()) return;
        List<Marker> out = new ArrayList<>();
        for (Waypoints.Point p : wp.here())
            out.add(new Marker(p.name(), p.x() + 0.5, p.y() + 0.5, p.z() + 0.5, p.name().equals("Death") ? 0xFFFF7A85 : 0xFFE11D2E));
        markers = out;
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (cam == null) { empty = true; return; }
        boolean smooth = smoothFont.get();
        boolean hidden = mc.player != null && mc.player.hasReducedDebugInfo();
        width = stripWidth.getInt();
        height = BASE_HEIGHT;
        var tr = mc.textRenderer;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        float yaw = MathHelper.wrapDegrees(cam.getYaw());
        int center = left + width / 2;
        float pxPerDeg = BASE_PX_PER_DEG * zoom.getFloat();
        boolean inter = intercardinals.get();
        ctx.enableScissor(left, top, left + width, top + height);
        for (int deg = -180; deg < 540; deg += 15) {
            float rel = MathHelper.wrapDegrees(deg - yaw);
            int px = Math.round(center + rel * pxPerDeg);
            if (px < left - 10 || px > left + width + 10) continue;
            boolean cardinal = deg % 90 == 0;
            if (cardinal || (inter && deg % 45 == 0)) {
                String l = LABELS[Math.floorMod(deg / 45, 8)];
                Text t = styled(l);
                int c = l.equals("N") ? Theme.RED_BRIGHT : cardinal ? Theme.TEXT : Theme.TEXT_DIM;
                ctx.drawTextWithShadow(tr, t, px - tr.getWidth(t) / 2, top + 4, c);
            } else {
                ctx.fill(px, top + 6, px + 1, top + 10, Theme.TEXT_FAINT);
            }
        }

        // Heading under the needle; waypoint markers keep clear of it.
        Text heading = showDegrees.get() && !hidden ? styled(String.valueOf(Math.floorMod(Math.round(yaw + 180), 360))) : null;
        int headingHalf = heading == null ? 0 : tr.getWidth(heading) / 2 + 3;
        refreshMarkers();
        if (!markers.isEmpty()) {
            double cx = cam.getX(), cy = cam.getY(), cz = cam.getZ();
            for (Marker m : markers) {
                double dx = m.x - cx, dz = m.z - cz;
                float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float rel = MathHelper.wrapDegrees(bearing - yaw);
                int px = Math.round(center + rel * pxPerDeg);
                if (px < left - 2 || px > left + width + 2) continue;
                ctx.fill(px - 1, top + 13, px + 1, top + height - 1, m.color);
                if (Math.abs(px - center) <= headingHalf) continue;
                int dist = (int) Math.round(Math.sqrt(dx * dx + (m.y - cy) * (m.y - cy) + dz * dz));
                Text label = styled(m.name + " " + dist + "m");
                int lw = Math.round(tr.getWidth(label) * 0.6f);
                // Label on the side away from the needle, so it never runs over the heading.
                float lx = px > center ? px + 3 : px - 3 - lw;
                ctx.getMatrices().pushMatrix();
                ctx.getMatrices().translate(lx, top + 15);
                ctx.getMatrices().scale(0.6f, 0.6f);
                ctx.drawTextWithShadow(tr, label, 0, 0, m.color);
                ctx.getMatrices().popMatrix();
            }
        }
        ctx.disableScissor();

        ctx.fill(center, top + 1, center + 1, top + 4, Theme.RED);
        if (heading != null) ctx.drawTextWithShadow(tr, heading, center - tr.getWidth(heading) / 2, top + 13, Theme.TEXT_DIM);
    }
}
