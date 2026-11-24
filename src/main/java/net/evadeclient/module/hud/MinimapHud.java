package net.evadeclient.module.hud;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.evadeclient.EvadeClient;
import net.evadeclient.api.ApiTeams;
import net.evadeclient.hud.HudDraw;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;

import java.util.Arrays;

/**
 * A top-down map of the terrain around you, drawn from the chunks your client already has: the map colour of the
 * top block of each column, shaded by height like a vanilla map, with water darkened by its depth. On it: your own
 * position as an arrow, N/E/S/W markers and, optionally, your own waypoints and your coordinates.
 *
 * <p><b>Server-safe by design.</b> It shows the surface only: never players, mobs, items or anything underground,
 * and it draws nothing in dimensions with a ceiling (the Nether), where the "surface" would be the roof or a view
 * into caves. The one exception is chosen by the server: team mates it lists through the Evade Server API
 * ({@code team.set}), and only those already loaded near you. Servers can still switch it off with
 * {@code evade:rules} ("Minimap").
 *
 * <p><b>Cheap to draw.</b> Terrain is painted into a world-aligned texture used as a torus (block (x, z) lives at
 * texel (x mod T, z mod T)), a few chunks per tick, nearest first, each chunk uploaded as its own 16 x 16
 * sub-image. Moving never shifts or rebuilds it: chunks entering the window just take over their slot. Each frame
 * is one textured polygon (a quad, or a 64-sided disc for Circle) whose UVs place you in the middle and turn the
 * map with you; the texture's repeat sampler wraps the torus. No per-frame texture work and no per-frame arrays.
 */
public class MinimapHud extends HudElementModule {
    /** Padding between the panel edge and the map, and the height of the coordinates line. */
    private static final int PAD = 3, LINE = 11;
    /** Colour of ground the client hasn't loaded (yet). */
    private static final int UNKNOWN = 0xFF17171B;
    /** Chunks painted per tick (256 columns each), nearest first. */
    private static final int PAINT_PER_TICK = 6;
    /** Segments of the circular map and its border. */
    private static final int SEGMENTS = 64;
    private static final float[] COS = new float[SEGMENTS + 1], SIN = new float[SEGMENTS + 1];
    private static final int SAMPLE_GRASS = 0xFF5F8F3A, SAMPLE_WATER = 0xFF3A5FC8;

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = 2 * Math.PI * i / SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    private final NumberSetting size = add(new NumberSetting("Size", 120, 64, 256, 8)
            .describe("Width and height of the map in pixels (before the widget's Scale)."));
    private final NumberSetting zoom = add(new NumberSetting("Zoom", 1.0, 0.5, 4.0, 0.25)
            .describe("Pixels per block. Lower shows more ground, higher shows more detail."));
    private final ModeSetting shape = add(new ModeSetting("Shape", "Square", "Square", "Circle")
            .describe("A square map, or a round one."));
    private final BooleanSetting rotateMap = add(new BooleanSetting("Rotate Map", false)
            .describe("Turn the map with you so the way you face is always up. Off keeps north up and turns your arrow instead."));
    private final BooleanSetting showCoords = add(new BooleanSetting("Show Coordinates", true)
            .describe("Your block position under the map (hidden where the server hides coordinates from F3)."));
    private final BooleanSetting showWaypoints = add(new BooleanSetting("Show Waypoints", true)
            .describe("Your waypoints (and any the server adds) as dots, pinned to the edge when they're off the map (needs Waypoints on)."));
    private final BooleanSetting showTeammates = add(new BooleanSetting("Show Teammates", true)
            .describe("Team mates the server shares with Evade, as dots: only players already loaded near you, never anyone else."));
    private final BooleanSetting showCardinals = add(new BooleanSetting("Show Cardinal Directions", true)
            .describe("N, E, S and W around the edge of the map."));
    private final ModeSetting border = add(new ModeSetting("Border", "Accent", "Accent", "Custom", "Off")
            .describe("A thin line around the map: in the accent colour, in Border Color, or none."));
    private final ColorSetting borderColor = add(new ColorSetting("Border Color", 0xFFFFFFFF)
            .describe("Colour of the border when Border is Custom."));
    private final ColorSetting arrowColor = add(new ColorSetting("Arrow Color", 0xFFFFFFFF)
            .describe("Colour of your position arrow."));

    // ---- terrain texture (a torus of chunks x chunks slots, 16 texels each)
    private NativeImageBackedTexture texture;
    /** Slots per side; the texture is {@code chunks * 16} texels square. */
    private int chunks, texSize;
    /** Which chunk each slot holds, and whether it is painted from real terrain (else cleared to UNKNOWN). */
    private int[] slotCx, slotCz;
    private byte[] slotState;
    private static final byte SLOT_FREE = 0, SLOT_CLEARED = 1, SLOT_PAINTED = 2;
    /** Chunk offsets of the window, nearest first. */
    private int[] ringDx, ringDz;
    private int nearCount, nearCursor, farCursor;
    private ClientWorld lastWorld;
    private final BlockPos.Mutable pos = new BlockPos.Mutable();
    private final int[] northHeights = new int[16], rowHeights = new int[16];

    // ---- per-frame geometry (two sets, alternated per draw, so nothing is allocated per frame)
    private final Geometry[] bgGeo = {new Geometry(4 * 4 * (SEGMENTS / 2)), new Geometry(4 * 4 * (SEGMENTS / 2))};
    private final Geometry[] mapGeo = {new Geometry(4 * 4 * (SEGMENTS / 2)), new Geometry(4 * 4 * (SEGMENTS / 2))};
    private final Geometry[] ringGeo = {new Geometry(4 * 4 * SEGMENTS), new Geometry(4 * 4 * SEGMENTS)};
    private final Geometry[] lakeGeo = {new Geometry(4 * 4 * (SEGMENTS / 2)), new Geometry(4 * 4 * (SEGMENTS / 2))};
    private final Geometry[] arrowGeo = {new Geometry(16), new Geometry(16)};
    private final Geometry[] arrowFillGeo = {new Geometry(16), new Geometry(16)};
    private int geoSet;
    private final Vector2f tmp = new Vector2f();

    /** UV transform for the frame being drawn (see {@link #uv}). */
    private float uCentre, vCentre, rotCos, rotSin, invZoomTex;

    // ---- cached text
    private Style cachedStyle;
    private boolean cachedSmooth;
    private final Text[] cardinalText = new Text[4];
    private Text coordsText = Text.empty();
    private int coordsX = Integer.MIN_VALUE, coordsY, coordsZ;
    private boolean coordsSmooth;

    public MinimapHud() {
        super("Minimap", "Top-down map of the terrain around you, with your waypoints.", -1, 175);
        width = height = 120 + 2 * PAD;
    }

    @Override
    protected void onDisable() {
        release();
    }

    /** Test hook: how many chunk slots hold real terrain right now (0 with no texture, e.g. in the Nether). */
    public int paintedChunks() {
        if (slotState == null) return 0;
        int count = 0;
        for (byte b : slotState) if (b == SLOT_PAINTED) count++;
        return count;
    }

    /** Test hook: the terrain texture's side in texels (0 when none is allocated). */
    public int textureSize() { return texSize; }

    // ================================================================== tick: paint terrain

    @Override
    public void onTick() {
        ClientWorld world = mc.world;
        ClientPlayerEntity p = mc.player;
        if (world == null || p == null) return;
        if (world.getDimension().hasCeiling()) {
            // Nothing is mapped under a ceiling; free the memory until we leave.
            if (texture != null) release();
            lastWorld = world;
            return;
        }
        int n = windowChunks();
        if (texture == null || n != chunks) allocate(n);
        else if (world != lastWorld) clearAll();
        lastWorld = world;
        update(world, p);
    }

    /** Slots per side for the current Size and Zoom: the map's half-diagonal plus two chunks of slack, rounded up to 8. */
    private int windowChunks() {
        double halfBlocks = size.get() / 2.0 / Math.max(0.25, zoom.get()) * Math.sqrt(2);
        int n = 2 * ((int) Math.ceil(halfBlocks / 16.0) + 2);
        return Math.min(64, (n + 7) & ~7);
    }

    private void allocate(int n) {
        release();
        chunks = n;
        texSize = n * 16;
        texture = new NativeImageBackedTexture(() -> "evade_minimap", texSize, texSize, false);
        slotCx = new int[n * n];
        slotCz = new int[n * n];
        slotState = new byte[n * n];
        // Window offsets [-n/2, n/2) sorted by distance (key: squared distance, then index).
        int half = n / 2;
        long[] keys = new long[n * n];
        for (int i = 0; i < n * n; i++) {
            int dx = i % n - half, dz = i / n - half;
            keys[i] = ((long) (dx * dx + dz * dz) << 32) | i;
        }
        Arrays.sort(keys);
        ringDx = new int[n * n];
        ringDz = new int[n * n];
        nearCount = 0;
        for (int i = 0; i < keys.length; i++) {
            int idx = (int) keys[i];
            ringDx[i] = idx % n - half;
            ringDz[i] = idx / n - half;
            if (Math.abs(ringDx[i]) <= 1 && Math.abs(ringDz[i]) <= 1) nearCount = i + 1;
        }
        nearCursor = farCursor = 0;
        clearAll();
    }

    private void release() {
        if (texture != null) texture.close();
        texture = null;
        chunks = texSize = 0;
        slotCx = slotCz = null;
        slotState = null;
        ringDx = ringDz = null;
        lastWorld = null;
    }

    /** Forget everything painted (new world or dimension): fill with UNKNOWN and upload once. */
    private void clearAll() {
        NativeImage img = texture.getImage();
        if (img != null) {
            img.fillRect(0, 0, texSize, texSize, UNKNOWN);
            texture.upload();
        }
        Arrays.fill(slotState, SLOT_FREE);
    }

    /**
     * One tick of painting: walk the window nearest first, painting loaded chunks whose slot doesn't hold them yet
     * (at most {@link #PAINT_PER_TICK}) and clearing slots that a chunk not loaded has just taken over. Then
     * repaint one chunk around you (block changes nearby show within half a second) and, every other tick, one
     * chunk anywhere in range.
     */
    private void update(ClientWorld world, ClientPlayerEntity p) {
        NativeImage img = texture.getImage();
        if (img == null) return;
        ClientChunkManager cm = world.getChunkManager();
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        int reach = mc.options.getClampedViewDistance() + 2;
        int paint = PAINT_PER_TICK, clear = 2 * chunks;
        int total = ringDx.length;
        for (int i = 0; i < total && (paint > 0 || clear > 0); i++) {
            int dx = ringDx[i], dz = ringDz[i];
            int cx = pcx + dx, cz = pcz + dz;
            int slot = slot(cx, cz);
            boolean ours = slotState[slot] != SLOT_FREE && slotCx[slot] == cx && slotCz[slot] == cz;
            if (ours && slotState[slot] == SLOT_PAINTED) continue;
            WorldChunk chunk = paint > 0 && Math.max(Math.abs(dx), Math.abs(dz)) <= reach
                    ? cm.getChunk(cx, cz, ChunkStatus.FULL, false) : null;
            if (chunk != null) {
                paintChunk(world, cm, img, chunk, cx, cz, slot);
                paint--;
            } else if (!ours && clear > 0) {
                int bx = Math.floorMod(cx, chunks) * 16, bz = Math.floorMod(cz, chunks) * 16;
                img.fillRect(bx, bz, 16, 16, UNKNOWN);
                uploadSlot(img, bx, bz);
                slotCx[slot] = cx;
                slotCz[slot] = cz;
                slotState[slot] = SLOT_CLEARED;
                clear--;
            }
        }
        // Refresh: blocks change (you build, dig, water flows); this picks the changes up without a world hook.
        if (nearCount > 0) refresh(world, cm, img, pcx, pcz, nearCursor++ % nearCount, reach);
        if ((EvadeClient.ticks & 1) == 0) refresh(world, cm, img, pcx, pcz, farCursor++ % total, reach);
        if (nearCursor >= nearCount * 1000) nearCursor = 0;
        if (farCursor >= total * 1000) farCursor = 0;
    }

    private void refresh(ClientWorld world, ClientChunkManager cm, NativeImage img, int pcx, int pcz, int i, int reach) {
        int dx = ringDx[i], dz = ringDz[i];
        if (Math.max(Math.abs(dx), Math.abs(dz)) > reach) return;
        int cx = pcx + dx, cz = pcz + dz;
        WorldChunk chunk = cm.getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk != null) paintChunk(world, cm, img, chunk, cx, cz, slot(cx, cz));
    }

    private int slot(int cx, int cz) {
        return Math.floorMod(cz, chunks) * chunks + Math.floorMod(cx, chunks);
    }

    /** Paints one chunk's 16 x 16 columns into its slot and uploads just that square. */
    private void paintChunk(ClientWorld world, ClientChunkManager cm, NativeImage img, WorldChunk chunk, int cx, int cz, int slot) {
        int bx = Math.floorMod(cx, chunks) * 16, bz = Math.floorMod(cz, chunks) * 16;
        int x0 = cx << 4, z0 = cz << 4;
        int bottom = world.getBottomY();
        // Heights of the row just north of the chunk, for the shading of its first row.
        WorldChunk north = cm.getChunk(cx, cz - 1, ChunkStatus.FULL, false);
        for (int lx = 0; lx < 16; lx++)
            northHeights[lx] = north != null ? north.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, 15) : Integer.MIN_VALUE;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, lz);
                int argb;
                if (y < bottom) {
                    argb = UNKNOWN; // the void (End islands' edges)
                    rowHeights[lx] = Integer.MIN_VALUE;
                } else {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    MapColor color = state.getMapColor(world, pos);
                    // See-through blocks (glass, barriers) show what's under them, as on a vanilla map.
                    for (int steps = 0; color == MapColor.CLEAR && y > bottom && steps < 16; steps++) {
                        pos.setY(--y);
                        state = chunk.getBlockState(pos);
                        color = state.getMapColor(world, pos);
                    }
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty() && fluid.isIn(FluidTags.WATER)) {
                        // Depth tint: count the water below the surface (up to 12 blocks).
                        int depth = 1;
                        while (depth < 12 && y - depth > bottom) {
                            pos.setY(y - depth);
                            if (!chunk.getBlockState(pos).getFluidState().isIn(FluidTags.WATER)) break;
                            depth++;
                        }
                        MapColor.Brightness b = depth <= 2 ? MapColor.Brightness.HIGH
                                : depth <= 5 ? MapColor.Brightness.NORMAL
                                : depth <= 9 ? MapColor.Brightness.LOW : MapColor.Brightness.LOWEST;
                        argb = MapColor.WATER_BLUE.getRenderColor(b);
                    } else if (color == MapColor.CLEAR) {
                        argb = UNKNOWN;
                    } else {
                        int northY = lz == 0 ? northHeights[lx] : rowHeights[lx];
                        MapColor.Brightness b = northY == Integer.MIN_VALUE || northY == y ? MapColor.Brightness.NORMAL
                                : y > northY ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
                        argb = color.getRenderColor(b);
                    }
                    rowHeights[lx] = y;
                }
                img.setColorArgb(bx + lx, bz + lz, argb | 0xFF000000);
            }
        }
        uploadSlot(img, bx, bz);
        slotCx[slot] = cx;
        slotCz[slot] = cz;
        slotState[slot] = SLOT_PAINTED;
    }

    private void uploadSlot(NativeImage img, int bx, int bz) {
        if (texture.getGlTexture() == null) return;
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getGlTexture(), img, 0, 0, bx, bz, 16, 16, bx, bz);
    }

    // ================================================================== render

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        int s = size.getInt();
        boolean circle = shape.is("Circle");
        ClientPlayerEntity p = mc.player;
        ClientWorld world = mc.world;
        boolean live = p != null && world != null;
        if (!live && !preview) { empty = true; return; }
        boolean reduced = live && p.hasReducedDebugInfo();
        boolean coordsLine = showCoords.get() && (!live || !reduced);

        width = s + 2 * PAD;
        height = s + 2 * PAD + (coordsLine ? LINE : 0);
        float r = s / 2f;
        float cx = left + PAD + r, cy = top + PAD + r;
        int set = geoSet;
        geoSet ^= 1;

        // Background panel (a disc for Circle).
        if (background.get()) {
            if (!circle) Theme.hudBox(ctx, left, top, width, height);
            else {
                int fill = Theme.hudFill();
                if ((fill >>> 24) != 0) {
                    Geometry g = bgGeo[set].reset();
                    fan(g, cx, cy, r + PAD, false);
                    g.submit(ctx, RenderPipelines.GUI, TextureSetup.empty(), false, fill, cx, cy, r + PAD);
                }
            }
        }

        boolean ceiling = live && world.getDimension().hasCeiling();
        float td = live ? mc.getRenderTickCounter().getTickProgress(true) : 0f;
        float yaw = live ? p.getYaw(td) : 200f;
        float viewYaw = rotateMap.get() ? yaw : 180f; // 180: north up
        rotCos = MathHelper.cos(viewYaw * MathHelper.RADIANS_PER_DEGREE);
        rotSin = MathHelper.sin(viewYaw * MathHelper.RADIANS_PER_DEGREE);

        // The map itself.
        if (live && !ceiling && texture != null && texture.getGlTextureView() != null) {
            double px = MathHelper.lerp(td, p.lastX, p.getX()), pz = MathHelper.lerp(td, p.lastZ, p.getZ());
            uCentre = (float) (px - Math.floor(px / texSize) * texSize);
            vCentre = (float) (pz - Math.floor(pz / texSize) * texSize);
            invZoomTex = 1f / (zoom.getFloat() * texSize);
            float uc = uCentre / texSize, vc = vCentre / texSize;
            Geometry g = mapGeo[set].reset();
            if (circle) fan(g, cx, cy, r, true);
            else g.quad(cx - r, cy - r, uvU(-r, -r, uc), uvV(-r, -r, vc),
                        cx - r, cy + r, uvU(-r, r, uc), uvV(-r, r, vc),
                        cx + r, cy + r, uvU(r, r, uc), uvV(r, r, vc),
                        cx + r, cy - r, uvU(r, -r, uc), uvV(r, -r, vc));
            TextureSetup setup = TextureSetup.of(texture.getGlTextureView(),
                    RenderSystem.getSamplerCache().getRepeated(FilterMode.NEAREST));
            g.submit(ctx, RenderPipelines.GUI_TEXTURED, setup, true, 0xFFFFFFFF, cx, cy, r);
        } else {
            // Placeholder ground: sample terrain in the editor, a plain unknown fill otherwise.
            boolean sample = !live;
            Geometry g = mapGeo[set].reset();
            shapeFill(g, cx, cy, r, circle);
            g.submit(ctx, RenderPipelines.GUI, TextureSetup.empty(), false, sample ? SAMPLE_GRASS : UNKNOWN, cx, cy, r);
            if (sample) {
                Geometry lake = lakeGeo[set].reset();
                fan(lake, cx + r * 0.35f, cy - r * 0.3f, r * 0.28f, false);
                lake.submit(ctx, RenderPipelines.GUI, TextureSetup.empty(), false, SAMPLE_WATER, cx + r * 0.35f, cy - r * 0.3f, r * 0.28f);
            }
        }

        // Border.
        int borderArgb = border.is("Off") ? 0 : border.is("Custom") ? borderColor.get() | 0xFF000000 : Theme.RED | 0xFF000000;
        if (borderArgb != 0) {
            if (circle) {
                Geometry g = ringGeo[set].reset();
                ring(g, cx, cy, r - 1f, r + 0.5f);
                g.submit(ctx, RenderPipelines.GUI, TextureSetup.empty(), false, borderArgb, cx, cy, r + 1);
            } else {
                int x1 = left + PAD, y1 = top + PAD, x2 = x1 + s, y2 = y1 + s;
                Theme.fill(ctx, x1, y1, x2, y1 + 1, borderArgb);
                Theme.fill(ctx, x1, y2 - 1, x2, y2, borderArgb);
                Theme.fill(ctx, x1, y1 + 1, x1 + 1, y2 - 1, borderArgb);
                Theme.fill(ctx, x2 - 1, y1 + 1, x2, y2 - 1, borderArgb);
            }
        }

        if (ceiling) {
            String msg = world.getRegistryKey() == World.NETHER ? "No map in the Nether" : "No map here";
            HudDraw.smallText(ctx, styled(msg), cx, cy - 3, Theme.TEXT_DIM, s - 8);
        } else {
            if (showWaypoints.get() && live) drawWaypoints(ctx, p, td, cx, cy, r, circle);
            if (showTeammates.get() && live) drawTeammates(ctx, p, td, cx, cy, r, circle);
            if (live) drawPartyPings(ctx, p, td, cx, cy, r, circle);
            drawArrow(ctx, set, cx, cy, yaw);
        }
        if (showCardinals.get()) drawCardinals(ctx, cx, cy, r, circle);

        if (coordsLine) {
            int bx = live ? p.getBlockX() : 128, by = live ? p.getBlockY() : 64, bz = live ? p.getBlockZ() : -302;
            Text t = coordsText(bx, by, bz);
            int tw = mc.textRenderer.getWidth(t);
            int ty = top + s + 2 * PAD + 1;
            if (circle && background.get()) {
                int fill = Theme.hudFill();
                if ((fill >>> 24) != 0) Theme.round(ctx, (int) cx - tw / 2 - 3, ty - 2, (int) cx + (tw + 1) / 2 + 3, ty + 9, fill, Theme.R_HUD);
            }
            ctx.drawTextWithShadow(mc.textRenderer, t, Math.round(cx - tw / 2f), ty, Theme.TEXT);
        }
    }

    // ---------------------------------------------------------------- map maths

    /**
     * World offset (blocks east, south) of the map point {@code (sx, sy)} pixels from the centre, as a texture
     * UV offset: rotated by the view yaw and divided by zoom and texture size.
     */
    private float uvU(float sx, float sy, float uc) {
        return uc + (-rotCos * sx + rotSin * sy) * invZoomTex;
    }

    private float uvV(float sx, float sy, float vc) {
        return vc + (-rotSin * sx - rotCos * sy) * invZoomTex;
    }

    /** Map pixel offset of a world offset (blocks east, south) at the current zoom and rotation, into {@link #tmp}. */
    private Vector2f project(double wx, double wz) {
        float z = zoom.getFloat();
        tmp.set((float) (-rotCos * wx - rotSin * wz) * z, (float) (rotSin * wx - rotCos * wz) * z);
        return tmp;
    }

    /** Pulls {@link #tmp} onto the map's edge (inset by {@code inset}) when it lies outside; returns whether it did. */
    private boolean clampToEdge(float r, boolean circle, float inset) {
        float lim = r - inset;
        float m = circle ? (float) Math.sqrt(tmp.x * tmp.x + tmp.y * tmp.y) : Math.max(Math.abs(tmp.x), Math.abs(tmp.y));
        if (m <= lim) return false;
        if (m > 0) tmp.mul(lim / m);
        return true;
    }

    // ---------------------------------------------------------------- overlays

    private void drawArrow(DrawContext ctx, int set, float cx, float cy, float yaw) {
        // Facing as a world vector (east, south), then onto the map.
        float fx = -MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE), fz = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE);
        float dx = -rotCos * fx - rotSin * fz, dy = rotSin * fx - rotCos * fz;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) { dx = 0; dy = -1; } else { dx /= len; dy /= len; }
        float qx = -dy, qy = dx; // perpendicular
        Geometry g = arrowGeo[set].reset();
        dart(g, cx, cy, dx, dy, qx, qy, 1.45f);
        g.submit(ctx, RenderPipelines.GUI, TextureSetup.empty(), false, 0xC0000000, cx, cy, 9);
        Geometry f = arrowFillGeo[set].reset();
        dart(f, cx, cy, dx, dy, qx, qy, 1f);
        f.submit(ctx, RenderPipelines.GUI, TextureSetup.empty(), false, arrowColor.get() | 0xFF000000, cx, cy, 6);
    }

    /** The arrow: tip ahead, two back corners and a notch, scaled by {@code k}. */
    private static void dart(Geometry g, float cx, float cy, float dx, float dy, float qx, float qy, float k) {
        float tip = 5f * k, back = 3.5f * k, side = 3.5f * k, notch = 1.2f * k;
        g.quad(cx + dx * tip, cy + dy * tip, 0, 0,
                cx - dx * back + qx * side, cy - dy * back + qy * side, 0, 0,
                cx - dx * notch, cy - dy * notch, 0, 0,
                cx - dx * back - qx * side, cy - dy * back - qy * side, 0, 0);
    }

    private static final double[][] CARDINAL_DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final String[] CARDINAL_LABELS = {"N", "E", "S", "W"};

    private void drawCardinals(DrawContext ctx, float cx, float cy, float r, boolean circle) {
        refreshTextCache();
        for (int i = 0; i < 4; i++) {
            project(CARDINAL_DIRS[i][0] * r * 4, CARDINAL_DIRS[i][1] * r * 4);
            clampToEdge(r, circle, 5f);
            int color = i == 0 ? Theme.RED_BRIGHT : 0xFFE6E6EA;
            HudDraw.smallText(ctx, cardinalText[i], cx + tmp.x, cy + tmp.y - 3, color, 0);
        }
    }

    private void drawWaypoints(DrawContext ctx, ClientPlayerEntity p, float td, float cx, float cy, float r, boolean circle) {
        Waypoints wp = EvadeClient.modules == null ? null : EvadeClient.modules.get(Waypoints.class);
        if (wp == null || !wp.isEnabled()) return;
        double px = MathHelper.lerp(td, p.lastX, p.getX()), pz = MathHelper.lerp(td, p.lastZ, p.getZ());
        var list = wp.visible();
        for (int i = 0, n = list.size(); i < n; i++) {
            Waypoints.Shown sh = list.get(i);
            project(sh.x() + 0.5 - px, sh.z() + 0.5 - pz);
            boolean edge = clampToEdge(r, circle, 3f);
            int c = sh.converted() ? Theme.alpha(sh.color(), 0.6f) : sh.color();
            int d = sh.target() ? 6 : edge ? 4 : 5;
            if (edge) c = Theme.alpha(c, 0.8f);
            int x = Math.round(cx + tmp.x - d / 2f), y = Math.round(cy + tmp.y - d / 2f);
            Theme.disc(ctx, x - 1, y - 1, d + 2, 0xA0000000);
            Theme.disc(ctx, x, y, d, c);
        }
    }

    /**
     * Team mates a server shared through the Evade Server API, as small rimmed dots in their team colour: only
     * players in the client's loaded entity list that the server listed, and only while they're on the map (never
     * pinned to the edge).
     */
    private void drawTeammates(DrawContext ctx, ClientPlayerEntity p, float td, float cx, float cy, float r, boolean circle) {
        if (!ApiTeams.any()) return;
        double px = MathHelper.lerp(td, p.lastX, p.getX()), pz = MathHelper.lerp(td, p.lastZ, p.getZ());
        for (ApiTeams.Member m : ApiTeams.loaded()) {
            var o = m.player();
            project(MathHelper.lerp(td, o.lastX, o.getX()) - px, MathHelper.lerp(td, o.lastZ, o.getZ()) - pz);
            if (clampToEdge(r, circle, 2f)) continue;
            int d = 4;
            int x = Math.round(cx + tmp.x - d / 2f), y = Math.round(cy + tmp.y - d / 2f);
            Theme.disc(ctx, x - 1, y - 1, d + 2, 0xC0000000);
            Theme.disc(ctx, x, y, d, m.color());
        }
    }

    /**
     * Party pings (Party Ping's Show On Minimap): a white-rimmed dot in the ping's colour, fading with it, pinned to the
     * edge while it's off the map.
     */
    private void drawPartyPings(DrawContext ctx, ClientPlayerEntity p, float td, float cx, float cy, float r, boolean circle) {
        var dots = net.evadeclient.module.misc.PartyPing.minimapDots();
        if (dots.isEmpty()) return;
        double px = MathHelper.lerp(td, p.lastX, p.getX()), pz = MathHelper.lerp(td, p.lastZ, p.getZ());
        for (var dot : dots) {
            project(dot.x() - px, dot.z() - pz);
            clampToEdge(r, circle, 3f);
            float a = (dot.color() >>> 24) / 255f;
            int d = 5;
            int x = Math.round(cx + tmp.x - d / 2f), y = Math.round(cy + tmp.y - d / 2f);
            Theme.disc(ctx, x - 1, y - 1, d + 2, Theme.alpha(0xFFFFFFFF, a));
            Theme.disc(ctx, x, y, d, dot.color());
        }
    }

    private void refreshTextCache() {
        boolean smooth = smoothFont.get();
        Style st = smooth ? HudElementModule.smoothStyle() : null;
        if (cardinalText[0] != null && smooth == cachedSmooth && st == cachedStyle) return;
        cachedSmooth = smooth;
        cachedStyle = st;
        for (int i = 0; i < 4; i++) cardinalText[i] = styled(CARDINAL_LABELS[i]);
    }

    /** "128 64 -302", rebuilt only when the block position or font changes. */
    private Text coordsText(int x, int y, int z) {
        boolean smooth = smoothFont.get();
        if (x != coordsX || y != coordsY || z != coordsZ || smooth != coordsSmooth) {
            coordsX = x;
            coordsY = y;
            coordsZ = z;
            coordsSmooth = smooth;
            coordsText = styled(x + " " + y + " " + z);
        }
        return coordsText;
    }

    // ---------------------------------------------------------------- shapes

    /** The map area: a quad, or a disc. UVs (if wanted) come from {@link #uvU}/{@link #uvV}. */
    private void shapeFill(Geometry g, float cx, float cy, float r, boolean circle) {
        if (circle) fan(g, cx, cy, r, false);
        else g.quad(cx - r, cy - r, 0, 0, cx - r, cy + r, 0, 0, cx + r, cy + r, 0, 0, cx + r, cy - r, 0, 0);
    }

    /** A disc of radius {@code r} as {@link #SEGMENTS}/2 quads (two fan triangles each). */
    private void fan(Geometry g, float cx, float cy, float r, boolean textured) {
        float uc = uCentre / Math.max(1, texSize), vc = vCentre / Math.max(1, texSize);
        float cu = textured ? uc : 0, cv = textured ? vc : 0;
        for (int i = 0; i < SEGMENTS; i += 2) {
            float ax = COS[i] * r, ay = SIN[i] * r;
            float bx = COS[i + 1] * r, by = SIN[i + 1] * r;
            float ex = COS[i + 2] * r, ey = SIN[i + 2] * r;
            if (textured) {
                g.quad(cx, cy, cu, cv,
                        cx + ax, cy + ay, uvU(ax, ay, uc), uvV(ax, ay, vc),
                        cx + bx, cy + by, uvU(bx, by, uc), uvV(bx, by, vc),
                        cx + ex, cy + ey, uvU(ex, ey, uc), uvV(ex, ey, vc));
            } else {
                g.quad(cx, cy, 0, 0, cx + ax, cy + ay, 0, 0, cx + bx, cy + by, 0, 0, cx + ex, cy + ey, 0, 0);
            }
        }
    }

    /** An annulus between {@code rIn} and {@code rOut}. */
    private static void ring(Geometry g, float cx, float cy, float rIn, float rOut) {
        for (int i = 0; i < SEGMENTS; i++) {
            g.quad(cx + COS[i] * rOut, cy + SIN[i] * rOut, 0, 0,
                    cx + COS[i] * rIn, cy + SIN[i] * rIn, 0, 0,
                    cx + COS[i + 1] * rIn, cy + SIN[i + 1] * rIn, 0, 0,
                    cx + COS[i + 1] * rOut, cy + SIN[i + 1] * rOut, 0, 0);
        }
    }

    // ---------------------------------------------------------------- GUI geometry

    /**
     * A reusable vertex list (x, y, u, v per vertex, four vertices per quad) sent to the GUI renderer as one
     * element. The element keeps a reference to the array, which the renderer reads at the end of the same frame;
     * the module alternates two sets so a second draw in one frame can't overwrite the first.
     */
    private static final class Geometry {
        final float[] v;
        int n;
        /** Scratch for bounds (render thread only). */
        private static final Vector2f P = new Vector2f();

        Geometry(int floats) { v = new float[floats]; }

        Geometry reset() { n = 0; return this; }

        /** One quad, turned to vanilla fill's winding so the GUI pipeline's culling keeps it. */
        void quad(float ax, float ay, float au, float av, float bx, float by, float bu, float bv,
                  float cx, float cy, float cu, float cv, float dx, float dy, float du, float dv) {
            if (n + 16 > v.length) return;
            float area = (ax * by - bx * ay) + (bx * cy - cx * by) + (cx * dy - dx * cy) + (dx * ay - ax * dy);
            if (area > 0) {
                put(ax, ay, au, av); put(dx, dy, du, dv); put(cx, cy, cu, cv); put(bx, by, bu, bv);
            } else {
                put(ax, ay, au, av); put(bx, by, bu, bv); put(cx, cy, cu, cv); put(dx, dy, du, dv);
            }
        }

        private void put(float x, float y, float u, float w) {
            v[n++] = x; v[n++] = y; v[n++] = u; v[n++] = w;
        }

        /** Adds the quads as one GUI element; {@code ext} is the shape's half-size around (cx, cy), for its bounds. */
        void submit(DrawContext ctx, RenderPipeline pipeline, TextureSetup setup, boolean textured, int color,
                    float cx, float cy, float ext) {
            if (n == 0 || (color >>> 24) == 0) return;
            Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices());
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            Vector2f p = P;
            for (int i = 0; i < 4; i++) {
                pose.transformPosition(cx + ((i & 1) == 0 ? -ext : ext), cy + ((i & 2) == 0 ? -ext : ext), p);
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
            ctx.state.addSimpleElement(new Element(pipeline, setup, pose, v, n, textured, color, scissor, bounds));
        }
    }

    private record Element(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose, float[] v, int n,
                           boolean textured, int color, ScreenRect scissorArea, ScreenRect bounds)
            implements SimpleGuiElementRenderState {
        @Override
        public void setupVertices(VertexConsumer vertices) {
            if (textured) {
                for (int i = 0; i + 3 < n; i += 4) vertices.vertex(pose, v[i], v[i + 1]).texture(v[i + 2], v[i + 3]).color(color);
            } else {
                for (int i = 0; i + 3 < n; i += 4) vertices.vertex(pose, v[i], v[i + 1]).color(color);
            }
        }
    }
}
