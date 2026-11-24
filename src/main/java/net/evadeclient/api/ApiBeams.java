package net.evadeclient.api;

import com.google.gson.JsonObject;
import net.evadeclient.render.RenderContext;
import net.evadeclient.util.ColorUtil;
import net.minecraft.client.MinecraftClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coloured light beams a server places ({@code beam.add / remove}), like a beacon's: a see-through column with a
 * bright core, from the given block up to the build limit (or {@code height} blocks). Terrain hides them like any
 * other block. Beams with a {@code dimension} only show there.
 */
public final class ApiBeams {
    private record Beam(String id, double x, double y, double z, int color, double height, String dim) {}

    static final int MAX = 64;
    private static final int DEFAULT_COLOR = 0xFFE11D2E;
    /** Beams further than this from the camera (horizontally) aren't drawn. */
    private static final double RANGE = 512;

    private static final Map<String, Beam> beams = new LinkedHashMap<>();

    private ApiBeams() {}

    static boolean handle(String type, JsonObject o) {
        switch (type) {
            case "beam.add" -> {
                String id = ApiJson.id(o);
                if (id == null || !ApiJson.hasPos(o)) return false;
                if (!beams.containsKey(id) && beams.size() >= MAX) return false;
                beams.put(id, new Beam(id, Math.floor(ApiJson.num(o, "x", 0)), Math.floor(ApiJson.num(o, "y", 0)),
                        Math.floor(ApiJson.num(o, "z", 0)), ApiJson.color(o, "color", DEFAULT_COLOR) | 0xFF000000,
                        Math.max(0, Math.min(4096, ApiJson.num(o, "height", 0))), ApiJson.dimension(o, "dimension")));
                return true;
            }
            case "beam.remove" -> {
                String id = ApiJson.id(o);
                if (id == null) {
                    boolean any = !beams.isEmpty();
                    beams.clear();
                    return any;
                }
                return beams.remove(id) != null;
            }
            default -> { return false; }
        }
    }

    /** How many beams are set (for tests). */
    public static int count() { return beams.size(); }

    static void render(RenderContext ctx) {
        if (beams.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        String here = mc.world.getRegistryKey().getValue().toString();
        double buildTop = mc.world.getTopYInclusive() + 1;
        var cam = ctx.cameraPos();
        for (Beam b : beams.values()) {
            if (b.dim != null && !b.dim.equals(here)) continue;
            double dx = b.x + 0.5 - cam.x, dz = b.z + 0.5 - cam.z;
            if (dx * dx + dz * dz > RANGE * RANGE) continue;
            double top = b.height > 0 ? b.y + b.height : Math.max(buildTop, b.y + 1);
            if (top <= b.y) continue;
            ctx.boxFilledDepth(b.x + 0.3, b.y, b.z + 0.3, b.x + 0.7, top, b.z + 0.7, ColorUtil.withAlpha(b.color, 0x50));
            ctx.boxFilledDepth(b.x + 0.42, b.y, b.z + 0.42, b.x + 0.58, top, b.z + 0.58, ColorUtil.withAlpha(b.color, 0xB0));
        }
    }

    static void clear() { beams.clear(); }
}
