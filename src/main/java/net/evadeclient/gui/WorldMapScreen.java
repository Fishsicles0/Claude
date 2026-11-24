package net.evadeclient.gui;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.evadeclient.EvadeClient;
import net.evadeclient.api.ApiTeams;
import net.evadeclient.api.ApiWaypoints;
import net.evadeclient.map.MapDraw;
import net.evadeclient.map.MapShading;
import net.evadeclient.map.MapStore;
import net.evadeclient.map.MapTextures;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.misc.StreamerMode;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.module.visuals.Waypoints.Point;
import net.evadeclient.module.visuals.WorldMap;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.ServerRules;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The World Map: the ground you've explored in this dimension, full screen. Drag to move, scroll to zoom (around the
 * cursor), click to add a waypoint, right-click a waypoint to edit it, make it your target or delete it. Shows your
 * position arrow, your waypoints (and the server's), the team mates a server shares that are loaded near you, and
 * under the cursor its coordinates (not where the server hides coordinates from F3, nor in Streamer Mode) and, for
 * chunks loaded right now, its biome. Never anything you haven't loaded, nothing underground, no other players.
 */
public final class WorldMapScreen extends Screen {
    private static final int BG = 0xFF0C0C0F, BAR_H = 26;
    private static final float MIN_ZOOM_ABS = 0.0625f, MAX_ZOOM = 16f;
    /** The view kept between openings in the same session (world, dimension): position and zoom. */
    private static double savedX, savedZ;
    private static float savedZoom = 1f;
    private static String savedFor;

    private final Screen parent;
    private final MapTextures textures = new MapTextures();
    private double camX, camZ;
    private float zoom;
    private boolean smooth = true;
    private boolean followPlayer;

    // input
    private boolean pressed, dragged;
    private double pressX, pressY;
    private final List<Hit> hits = new ArrayList<>();
    private record Hit(int x1, int y1, int x2, int y2, IntConsumer action) {}

    // right-click menu on a waypoint
    private Point menuPoint;
    private int menuX, menuY;

    // what the cursor info said last frame (tests)
    private List<String> lastInfo = List.of();

    private WorldMapScreen(Screen parent) {
        super(Text.literal("World Map"));
        this.parent = parent;
    }

    /** Opens the map (key, command, menu), or says why it can't. */
    public static void open(Screen parent) {
        MinecraftClient mc = MinecraftClient.getInstance();
        WorldMap wm = EvadeClient.modules == null ? null : EvadeClient.modules.get(WorldMap.class);
        if (mc.world == null || mc.player == null) { Notifications.push("World Map", "Join a world to see its map", false); return; }
        if (wm == null || ServerRules.isDisabled(wm)) { Notifications.push("World Map", "Disabled by this server", false); return; }
        if (!wm.isEnabled()) { Notifications.push("World Map", "Turn on World Map (Visuals) to use it", false); return; }
        mc.setScreen(new WorldMapScreen(parent));
    }

    /** From the menu's sidebar. */
    public static void openFromMenu() { open(MinecraftClient.getInstance().currentScreen); }

    private static WorldMap wm() { return EvadeClient.modules == null ? null : EvadeClient.modules.get(WorldMap.class); }
    private static Waypoints wp() { return EvadeClient.modules == null ? null : EvadeClient.modules.get(Waypoints.class); }

    @Override
    protected void init() {
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        smooth = s == null || s.smoothFont.get();
        if (zoom == 0) {
            String here = MapStore.dimension() + "|" + (client.world == null ? "" : client.world.hashCode());
            if (here.equals(savedFor)) { camX = savedX; camZ = savedZ; zoom = savedZoom; }
            else centre();
        }
    }

    private void centre() {
        ClientPlayerEntity p = client.player;
        if (p != null) { camX = p.getX(); camZ = p.getZ(); }
        if (zoom == 0) zoom = 1f;
    }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void removed() {
        savedX = camX; savedZ = camZ; savedZoom = zoom;
        savedFor = MapStore.dimension() + "|" + (client.world == null ? "" : client.world.hashCode());
        textures.close();
        super.removed();
    }

    @Override
    public void tick() {
        WorldMap w = wm();
        // The server switched the map off (or you left): close.
        if (client.world == null || w == null || !w.isEnabled() || ServerRules.isDisabled(w)) close();
    }

    // ================================================================== maths

    private float sx(double wx) { return (float) ((wx - camX) * zoom + width / 2.0); }
    private float sy(double wz) { return (float) ((wz - camZ) * zoom + height / 2.0); }
    private double wx(double sx) { return (sx - width / 2.0) / zoom + camX; }
    private double wz(double sy) { return (sy - height / 2.0) / zoom + camZ; }

    /** Zoomed out no further than a screenful of regions fits in the map's memory. */
    private float minZoom() {
        int cap = Math.max(4, (int) (MapStore.capacity() * 0.75));
        float z = MIN_ZOOM_ABS;
        while (z < 1f) {
            double cols = width / (512.0 * z) + 2, rows = height / (512.0 * z) + 2;
            if (cols * rows <= cap) break;
            z *= 1.25f;
        }
        return z;
    }

    private int shadeMode() { WorldMap w = wm(); return w == null ? MapShading.SMOOTH : MapShading.mode(w.shading.get()); }
    private boolean waterDepth() { WorldMap w = wm(); return w == null || w.waterDepth.get(); }

    private boolean coordsHidden() {
        return (client.player != null && client.player.hasReducedDebugInfo()) || StreamerMode.hidesCoordinates();
    }

    // ================================================================== render

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, BG);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        hits.clear();
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) return;
        zoom = MathHelper.clamp(zoom, minZoom(), MAX_ZOOM);
        float td = client.getRenderTickCounter().getTickProgress(true);
        double px = MathHelper.lerp(td, p.lastX, p.getX()), pz = MathHelper.lerp(td, p.lastZ, p.getZ());
        if (followPlayer) { camX = px; camZ = pz; }

        boolean ceiling = client.world.getDimension().hasCeiling();
        // GUI elements in one layer are batched by pipeline, not drawn in order: each stage gets its own layer.
        ctx.createNewRootLayer();
        int mode = shadeMode();
        boolean wd = waterDepth();
        if (!ceiling && textures.begin(mode, wd)) {
            drawTerrain(ctx, mode, wd);
            ctx.createNewRootLayer();
            WorldMap w = wm();
            if (w == null || w.showWaypoints.get()) drawWaypoints(ctx, mx, my);
            if (w == null || w.showTeammates.get()) drawTeammates(ctx, td);
            ctx.createNewRootLayer();
            drawArrow(ctx, sx(px), sy(pz), p.getYaw(td));
        } else {
            String a = ceiling && client.world.getRegistryKey() == World.NETHER ? "No map in the Nether" : ceiling ? "No map in this dimension" : "Nothing mapped here yet";
            String b = ceiling ? "Its ground is under a solid roof: mapping below it would show caves, so Evade doesn't."
                    : "Walk around: the ground you load is mapped as you go.";
            centred(ctx, a, height / 2 - 12, Theme.TEXT, Role.TITLE);
            centred(ctx, b, height / 2 + 8, Theme.TEXT_DIM, Role.BODY);
        }

        ctx.createNewRootLayer();
        drawBar(ctx, mx, my, ceiling);
        if (!ceiling) drawInfo(ctx, mx, my);
        if (menuPoint != null) { ctx.createNewRootLayer(); drawMenu(ctx, mx, my); }
        textures.trim(Math.max(16, MapStore.capacity() / 2));
    }

    private void drawTerrain(DrawContext ctx, int mode, boolean wd) {
        int rx0 = MathHelper.floor(wx(0)) >> 9, rx1 = MathHelper.floor(wx(width)) >> 9;
        int rz0 = MathHelper.floor(wz(0)) >> 9, rz1 = MathHelper.floor(wz(height)) >> 9;
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                GpuTextureView v = textures.view(rx, rz, mode, wd);
                if (v == null) continue;
                MapDraw.texture(ctx, v, sx(rx * 512.0), sy(rz * 512.0), sx((rx + 1) * 512.0), sy((rz + 1) * 512.0), 0xFFFFFFFF);
            }
        }
    }

    /** Waypoints of this dimension (yours, then the server's), with names when there's room. */
    private void drawWaypoints(DrawContext ctx, int mx, int my) {
        Waypoints w = wp();
        if (w == null) return;
        String dim = w.currentDim();
        Point target = w.target();
        boolean labels = zoom >= 0.35f;
        for (Point pt : w.allInWorld()) {
            if (!pt.dim().equals(dim) || (pt.hidden() && !pt.equals(target))) continue;
            drawPoint(ctx, pt.x(), pt.z(), Waypoints.color(pt), pt.name(), pt.equals(target), labels || near(pt.x(), pt.z(), mx, my));
        }
        for (ApiWaypoints.ServerWaypoint s : ApiWaypoints.all()) {
            if (s.dim() != null && !s.dim().equals(dim)) continue;
            drawPoint(ctx, s.x(), s.z(), s.color(), s.name(), false, labels || near(s.x(), s.z(), mx, my));
        }
    }

    private boolean near(int x, int z, double mx, double my) {
        float dx = sx(x + 0.5) - (float) mx, dy = sy(z + 0.5) - (float) my;
        return dx * dx + dy * dy <= 36;
    }

    private void drawPoint(DrawContext ctx, int x, int z, int color, String name, boolean target, boolean label) {
        float cx = sx(x + 0.5), cy = sy(z + 0.5);
        if (cx < -40 || cy < -20 || cx > width + 40 || cy > height + 20) return;
        int d = target ? 8 : 6;
        int ix = Math.round(cx - d / 2f), iy = Math.round(cy - d / 2f);
        if (target) Theme.disc(ctx, ix - 3, iy - 3, d + 6, Theme.alpha(0xFFFFFFFF, 0.5f));
        Theme.disc(ctx, ix - 1, iy - 1, d + 2, 0xC0000000);
        Theme.disc(ctx, ix, iy, d, color | 0xFF000000);
        if (label && name != null) {
            int tw = UiFont.width(textRenderer, name, Role.SMALL, smooth);
            int tx = Math.round(cx - tw / 2f), ty = iy + d + 3;
            Theme.round(ctx, tx - 3, ty - 2, tx + tw + 3, ty + 8, 0xA0000000, 2);
            UiFont.draw(ctx, textRenderer, name, tx, ty, Theme.TEXT, Role.SMALL, smooth);
        }
    }

    /** Team mates the server shared, only those already loaded near you (as on the Minimap). */
    private void drawTeammates(DrawContext ctx, float td) {
        if (!ApiTeams.any()) return;
        for (ApiTeams.Member m : ApiTeams.loaded()) {
            var o = m.player();
            float cx = sx(MathHelper.lerp(td, o.lastX, o.getX())), cy = sy(MathHelper.lerp(td, o.lastZ, o.getZ()));
            int ix = Math.round(cx - 2.5f), iy = Math.round(cy - 2.5f);
            Theme.disc(ctx, ix - 1, iy - 1, 7, 0xC0000000);
            Theme.disc(ctx, ix, iy, 5, m.color());
        }
    }

    private void drawArrow(DrawContext ctx, float cx, float cy, float yaw) {
        float dx = -MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE), dy = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE);
        float qx = -dy, qy = dx;
        dart(ctx, cx, cy, dx, dy, qx, qy, 1.9f, 0xC0000000);
        dart(ctx, cx, cy, dx, dy, qx, qy, 1.35f, 0xFFFFFFFF);
    }

    private static void dart(DrawContext ctx, float cx, float cy, float dx, float dy, float qx, float qy, float k, int color) {
        float tip = 5f * k, back = 3.5f * k, side = 3.5f * k, notch = 1.2f * k;
        MapDraw.quad(ctx, cx + dx * tip, cy + dy * tip, cx - dx * back + qx * side, cy - dy * back + qy * side,
                cx - dx * notch, cy - dy * notch, cx - dx * back - qx * side, cy - dy * back - qy * side, color);
    }

    private void drawBar(DrawContext ctx, int mx, int my, boolean ceiling) {
        ctx.fillGradient(0, 0, width, BAR_H + 8, 0xE0000000, 0x00000000);
        text(ctx, "World Map", 10, 8, Theme.TEXT, Role.TITLE);
        String dim = Waypoints.dimensionName(client.world.getRegistryKey().getValue().toString());
        text(ctx, dim + " · explored ground only", 10 + UiFont.width(textRenderer, "World Map", Role.TITLE, smooth) + 8, 12, Theme.TEXT_DIM, Role.SMALL);
        int x = button(ctx, "Close", width - 8, 6, mx, my, b -> close());
        x = button(ctx, "Waypoints", x - 6, 6, mx, my, b -> client.setScreen(new WaypointManagerScreen(this)));
        if (!ceiling) {
            x = button(ctx, followPlayer ? "Following" : "Follow", x - 6, 6, mx, my, b -> followPlayer = !followPlayer);
            button(ctx, "Centre", x - 6, 6, mx, my, b -> { followPlayer = false; centre(); });
        }
    }

    /** Bottom-left: the cursor's coordinates and biome, and the controls. */
    private void drawInfo(DrawContext ctx, int mx, int my) {
        List<String> lines = cursorInfo(mx, my);
        lastInfo = lines;
        int y = height - 10 - 11 * lines.size();
        int w = 0;
        for (String s : lines) w = Math.max(w, UiFont.width(textRenderer, s, Role.LABEL, smooth));
        if (!lines.isEmpty()) {
            Theme.round(ctx, 6, y - 5, 6 + w + 12, height - 6, 0xB00A0A0C, Theme.R);
            for (String s : lines) { text(ctx, s, 12, y, Theme.TEXT, Role.LABEL); y += 11; }
        }
        String hint = "Drag to move · Scroll to zoom · Click: add waypoint · Right-click a waypoint: edit";
        int hw = UiFont.width(textRenderer, hint, Role.SMALL, smooth);
        text(ctx, hint, width - hw - 10, height - 12, Theme.TEXT_FAINT, Role.SMALL);
    }

    /**
     * What the info box says about screen point (mx, my): "X 120  Y 64  Z -40" (hidden where the server hides
     * coordinates, or in Streamer Mode) and the biome, only for a chunk loaded right now.
     */
    public List<String> cursorInfo(double mx, double my) {
        List<String> out = new ArrayList<>();
        if (client.world == null || my < BAR_H) return out;
        int x = MathHelper.floor(wx(mx)), z = MathHelper.floor(wz(my));
        WorldChunk chunk = client.world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        boolean explored = MapStore.column(x, z) != 0;
        if (!explored && chunk == null) return out;
        boolean hidden = coordsHidden();
        if (hidden) out.add("Coordinates hidden");
        else if (chunk != null) out.add("X " + x + "  Y " + chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) + "  Z " + z);
        else out.add("X " + x + "  Z " + z);
        if (chunk != null && !hidden) {
            int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
            client.world.getBiome(new BlockPos(x, y, z)).getKey().ifPresent(k ->
                    out.add(Text.translatable(Util.createTranslationKey("biome", k.getValue())).getString()));
        }
        return out;
    }

    /** Test hook: the info box's lines last frame. */
    public List<String> infoForTest() { return lastInfo; }

    /** Test hook: world block under screen point (x, y). */
    public double[] worldAtForTest(double x, double y) { return new double[]{wx(x), wz(y)}; }

    /** Test hook: screen point of world block (x, z). */
    public float[] screenAtForTest(double x, double z) { return new float[]{sx(x), sy(z)}; }

    // ================================================================== right-click menu

    private void drawMenu(DrawContext ctx, int mx, int my) {
        Waypoints w = wp();
        if (w == null) { menuPoint = null; return; }
        String[] items = {"Edit", w.target() != null && w.target().equals(menuPoint) ? "Clear target" : "Set as target", "Delete"};
        int mw = 0;
        for (String s : items) mw = Math.max(mw, UiFont.width(textRenderer, s, Role.LABEL, smooth));
        mw = Math.max(mw, UiFont.width(textRenderer, menuPoint.name(), Role.SMALL, smooth)) + 20;
        int x1 = Math.min(menuX, width - mw - 4), y1 = Math.min(menuY, height - 18 - items.length * 16);
        int y2 = y1 + 16 + items.length * 16;
        Theme.shadow(ctx, Theme.SH_CARD, x1, y1, x1 + mw, y2, 0x90000000);
        Theme.roundBordered(ctx, x1, y1, x1 + mw, y2, Theme.CARD, Theme.BORDER);
        text(ctx, menuPoint.name(), x1 + 8, y1 + 5, Theme.TEXT_DIM, Role.SMALL);
        Point pt = menuPoint;
        for (int i = 0; i < items.length; i++) {
            int iy = y1 + 15 + i * 16;
            boolean hv = mx >= x1 && mx < x1 + mw && my >= iy && my < iy + 16;
            if (hv) Theme.fill(ctx, x1 + 2, iy, x1 + mw - 2, iy + 16, Theme.CONTROL_HOVER);
            text(ctx, items[i], x1 + 8, iy + 4, i == 2 ? Theme.DANGER : Theme.TEXT, Role.LABEL);
            int idx = i;
            hits.add(new Hit(x1, iy, x1 + mw, iy + 16, b -> menuAction(pt, idx)));
        }
        hits.add(new Hit(x1, y1, x1 + mw, y2, b -> {}));
    }

    private void menuAction(Point pt, int idx) {
        Waypoints w = wp();
        menuPoint = null;
        if (w == null) return;
        switch (idx) {
            case 0 -> client.setScreen(WaypointManagerScreen.editing(this, pt));
            case 1 -> w.setTarget(pt.equals(w.target()) ? null : pt);
            case 2 -> { if (w.deletePoint(pt) >= 0) Notifications.push("Waypoints", "Deleted " + pt.name(), true); }
            default -> {}
        }
    }

    /** Your own waypoint (this dimension) under screen point (mx, my), or null. */
    private Point pointAt(double mx, double my) {
        Waypoints w = wp();
        if (w == null) return null;
        String dim = w.currentDim();
        Point best = null;
        float bestD = 49;
        for (Point pt : w.allInWorld()) {
            if (!pt.dim().equals(dim) || pt.hidden()) continue;
            float dx = sx(pt.x() + 0.5) - (float) mx, dy = sy(pt.z() + 0.5) - (float) my;
            float d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = pt; }
        }
        return best;
    }

    /** Opens the right-click menu for the waypoint under (mx, my); false if there is none. */
    public boolean openMenuAt(double mx, double my) {
        Point pt = pointAt(mx, my);
        if (pt == null) return false;
        menuPoint = pt;
        menuX = (int) mx;
        menuY = (int) my;
        return true;
    }

    /** Test hook: the waypoint whose menu is open. */
    public Point menuForTest() { return menuPoint; }

    /** A click on the map: a new waypoint there (on the ground if that chunk is loaded, else at your height). */
    public Point addWaypointAt(double mx, double my) {
        Waypoints w = wp();
        if (w == null || client.world == null || client.world.getDimension().hasCeiling()) return null;
        int x = MathHelper.floor(wx(mx)), z = MathHelper.floor(wz(my));
        WorldChunk chunk = client.world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null && MapStore.column(x, z) == 0) {
            Notifications.push("World Map", "You haven't explored there", false);
            return null;
        }
        int y = chunk != null ? chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) + 1 : client.player.getBlockY();
        Point added = w.addAt(w.freeName("Waypoint"), null, new BlockPos(x, y, z));
        if (added != null) Notifications.push("Waypoints", "Added " + added.name() + " · right-click it to edit", true);
        return added;
    }

    // ================================================================== input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (mx >= h.x1 && mx < h.x2 && my >= h.y1 && my < h.y2) { h.action.accept(click.button()); return true; }
        }
        if (menuPoint != null) { menuPoint = null; return true; }
        if (client.world == null || client.world.getDimension().hasCeiling()) return true;
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { openMenuAt(mx, my); return true; }
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pressed = true;
            dragged = false;
            pressX = mx;
            pressY = my;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (!pressed) return false;
        if (!dragged && Math.abs(click.x() - pressX) + Math.abs(click.y() - pressY) > 3) dragged = true;
        if (dragged) {
            followPlayer = false;
            camX -= dx / zoom;
            camZ -= dy / zoom;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (pressed && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pressed = false;
            if (!dragged && click.y() >= BAR_H) addWaypointAt(click.x(), click.y());
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (v == 0) return false;
        double bx = wx(mx), bz = wz(my);
        zoom = MathHelper.clamp(zoom * (float) Math.pow(1.25, v), minZoom(), MAX_ZOOM);
        // Keep the block under the cursor where it is.
        camX = bx - (mx - width / 2.0) / zoom;
        camZ = bz - (my - height / 2.0) / zoom;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        WorldMap w = wm();
        if (key == GLFW.GLFW_KEY_ESCAPE && menuPoint != null) { menuPoint = null; return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE || (w != null && key == w.openKey.get() && key != GLFW.GLFW_KEY_UNKNOWN)) {
            if (w != null && key == w.openKey.get()) w.holdOff();
            close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_C) { followPlayer = false; centre(); return true; }
        return super.keyPressed(input);
    }

    /** Test hook: region textures on the GPU. */
    public int texturesForTest() { return textures.count(); }

    /** Test hook: zoom and centre. */
    public void setViewForTest(double x, double z, float z0) { camX = x; camZ = z; zoom = z0; followPlayer = false; }

    // ================================================================== small UI helpers

    private void text(DrawContext ctx, String s, int x, int y, int color, Role r) {
        UiFont.draw(ctx, textRenderer, s, x, y, color, r, smooth);
    }

    private void centred(DrawContext ctx, String s, int y, int color, Role r) {
        int w = UiFont.width(textRenderer, s, r, smooth);
        text(ctx, s, (width - w) / 2, y, color, r);
    }

    private int button(DrawContext ctx, String label, int right, int y, int mx, int my, IntConsumer action) {
        int w = UiFont.width(textRenderer, label, Role.LABEL, smooth) + 14;
        int x1 = right - w;
        boolean hv = mx >= x1 && mx < right && my >= y && my < y + 16;
        Theme.roundBordered(ctx, x1, y, right, y + 16, hv ? Theme.CONTROL_HOVER : Theme.CONTROL, hv ? Theme.RED : Theme.BORDER);
        text(ctx, label, x1 + 7, y + 4, hv ? Theme.TEXT : Theme.TEXT_SOFT, Role.LABEL);
        hits.add(new Hit(x1, y, right, y + 16, action));
        return x1;
    }
}
